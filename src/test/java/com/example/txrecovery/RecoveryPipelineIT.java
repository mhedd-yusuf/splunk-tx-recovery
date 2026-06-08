package com.example.txrecovery;

import com.example.txrecovery.adapter.persistence.TransactionRepository;
import com.example.txrecovery.adapter.persistence.WatermarkRepository;
import com.example.txrecovery.adapter.splunk.SplQueryBuilder;
import com.example.txrecovery.adapter.splunk.SplunkClient;
import com.example.txrecovery.adapter.splunk.SplunkTimeoutException;
import com.example.txrecovery.config.RecoveryProperties;
import com.example.txrecovery.config.SplunkProperties;
import com.example.txrecovery.domain.service.RecoveryRunSummary;
import com.example.txrecovery.domain.service.RecoveryService;
import com.example.txrecovery.domain.service.WatermarkService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.oracle.OracleContainer;

import javax.sql.DataSource;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Full happy-path + failure-mode integration test.
 *
 * <p>Heavy: starts an Oracle Free container (~1-2 min cold). Tagged
 * {@code integration} so {@code mvn test} skips it by default; run via
 * {@code mvn verify -Pintegration-tests}.</p>
 */
@Tag("integration")
class RecoveryPipelineIT {

    private static OracleContainer ORACLE;
    private static DataSource DATA_SOURCE;
    private static NamedParameterJdbcTemplate JDBC;

    private MockWebServer splunkMock;

    @BeforeAll
    static void bootDb() {
        ORACLE = new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
                .withUsername("appuser")
                .withPassword("appuser");
        ORACLE.start();

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(ORACLE.getJdbcUrl());
        cfg.setUsername(ORACLE.getUsername());
        cfg.setPassword(ORACLE.getPassword());
        cfg.setMaximumPoolSize(4);
        DATA_SOURCE = new HikariDataSource(cfg);

        Flyway.configure().dataSource(DATA_SOURCE).locations("classpath:db/migration").load().migrate();
        JDBC = new NamedParameterJdbcTemplate(DATA_SOURCE);
    }

    @BeforeEach
    void setUp() throws IOException {
        splunkMock = new MockWebServer();
        splunkMock.start();
        // Reset state between cases.
        JDBC.getJdbcTemplate().update("DELETE FROM transactions");
        JDBC.getJdbcTemplate().update(
                "UPDATE watermark SET ts = TIMESTAMP '2026-06-06 11:00:00.000 +00:00' WHERE name = 'splunk'");
    }

    @AfterEach
    void tearDown() throws IOException {
        splunkMock.shutdown();
    }

    @Test
    void happyPath_persistsAndAdvancesWatermark() {
        enqueueCreate("sid-1");
        enqueueStatusDone();
        enqueueResults("""
                {"results":[
                  {"id":"tx-1","_time":"2026-06-06T11:15:00.000+00:00","amount":"5","status":"OK","_raw":"r1"},
                  {"id":"tx-2","_time":"2026-06-06T11:20:00.000+00:00","amount":"6","status":"OK","_raw":"r2"}
                ]}""");

        RecoveryService svc = buildService(Duration.ofSeconds(5));
        RecoveryRunSummary s = svc.runOnce();

        assertThat(s.received()).isEqualTo(2);
        assertThat(s.inserted()).isEqualTo(2);
        assertThat(repo().count()).isEqualTo(2);
        assertThat(watermarkRepo().find(WatermarkRepository.SPLUNK))
                .isPresent()
                .hasValueSatisfying(w -> assertThat(w.timestamp()).isAfter(Instant.parse("2026-06-06T11:00:00Z")));
    }

    @Test
    void duplicate_isSkippedOnSecondRun() {
        // Run 1
        enqueueCreate("sid-1");
        enqueueStatusDone();
        enqueueResults("""
                {"results":[{"id":"tx-1","_time":"2026-06-06T11:15:00.000+00:00","amount":"5","status":"OK","_raw":"r"}]}
                """);
        buildService(Duration.ofSeconds(5)).runOnce();

        // Run 2 — same tx-1 (overlap window) plus tx-2.
        enqueueCreate("sid-2");
        enqueueStatusDone();
        enqueueResults("""
                {"results":[
                  {"id":"tx-1","_time":"2026-06-06T11:15:00.000+00:00","amount":"5","status":"OK","_raw":"r"},
                  {"id":"tx-2","_time":"2026-06-06T11:30:00.000+00:00","amount":"7","status":"OK","_raw":"r"}
                ]}""");
        RecoveryRunSummary s2 = buildService(Duration.ofSeconds(5)).runOnce();

        assertThat(s2.received()).isEqualTo(2);
        assertThat(s2.inserted()).isEqualTo(1);
        assertThat(s2.skippedDuplicate()).isEqualTo(1);
        assertThat(repo().count()).isEqualTo(2);
    }

    @Test
    void splunk5xx_doesNotAdvanceWatermark() {
        splunkMock.enqueue(new MockResponse().setResponseCode(503).setBody("boom"));

        Instant before = watermarkRepo().find(WatermarkRepository.SPLUNK).orElseThrow().timestamp();
        RecoveryService svc = buildService(Duration.ofSeconds(5));

        assertThatThrownBy(svc::runOnce).isInstanceOf(RuntimeException.class);

        Instant after = watermarkRepo().find(WatermarkRepository.SPLUNK).orElseThrow().timestamp();
        assertThat(after).isEqualTo(before);
        assertThat(repo().count()).isZero();
    }

    @Test
    void splunkTimeout_doesNotAdvanceWatermark() {
        // create-job succeeds but every poll reports not-done -> tight timeout fires.
        enqueueCreate("sid-1");
        for (int i = 0; i < 50; i++) {
            enqueueStatusNotDone();
        }
        Instant before = watermarkRepo().find(WatermarkRepository.SPLUNK).orElseThrow().timestamp();

        // 300ms poll timeout, 50ms cadence -> well-defined timeout.
        RecoveryService svc = buildService(Duration.ofMillis(300));

        assertThatThrownBy(svc::runOnce).isInstanceOfAny(SplunkTimeoutException.class, RuntimeException.class);

        Instant after = watermarkRepo().find(WatermarkRepository.SPLUNK).orElseThrow().timestamp();
        assertThat(after).isEqualTo(before);
    }

    // ----- helpers -----

    private void enqueueCreate(String sid) {
        splunkMock.enqueue(new MockResponse()
                .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"sid\":\"" + sid + "\"}"));
    }

    private void enqueueStatusDone() {
        splunkMock.enqueue(new MockResponse()
                .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {"entry":[{"content":{"isDone":true,"dispatchState":"DONE","resultCount":1}}]}
                        """));
    }

    private void enqueueStatusNotDone() {
        splunkMock.enqueue(new MockResponse()
                .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {"entry":[{"content":{"isDone":false,"dispatchState":"RUNNING","resultCount":0}}]}
                        """));
    }

    private void enqueueResults(String json) {
        splunkMock.enqueue(new MockResponse()
                .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody(json));
    }

    private TransactionRepository repo() { return new TransactionRepository(JDBC); }
    private WatermarkRepository watermarkRepo() { return new WatermarkRepository(JDBC); }

    private RecoveryService buildService(Duration pollTimeout) {
        SplunkProperties splunkProps = new SplunkProperties(
                splunkMock.url("/").toString(),
                "test-token",
                Duration.ofMillis(50),
                pollTimeout,
                false,
                "search index=foo earliest=${earliest} latest=${latest}");
        WebClient wc = WebClient.builder().baseUrl(splunkProps.baseUrl()).build();
        SplunkClient splunk = new SplunkClient(wc, splunkProps);

        RecoveryProperties recoveryProps = new RecoveryProperties(
                "0 */5 * * * *", Duration.ofMinutes(5), 1000, Duration.ofMinutes(10));

        WatermarkService wmSvc = new WatermarkService(watermarkRepo(), recoveryProps, Clock.systemUTC());

        MeterRegistry reg = new SimpleMeterRegistry();
        return new RecoveryService(
                wmSvc,
                new SplQueryBuilder(splunkProps.splQuery()),
                splunk,
                repo(),
                Clock.systemUTC(),
                Timer.builder("splunk.search.duration").register(reg),
                Counter.builder("splunk.search.results.count").register(reg),
                Counter.builder("transactions.inserted").register(reg),
                Counter.builder("transactions.skipped").register(reg),
                Counter.builder("transactions.failed").register(reg));
    }
}
