package com.example.txrecovery.domain.service;

import com.example.txrecovery.adapter.persistence.InsertCounts;
import com.example.txrecovery.adapter.persistence.TransactionRepository;
import com.example.txrecovery.adapter.persistence.WatermarkRepository;
import com.example.txrecovery.adapter.splunk.SplQueryBuilder;
import com.example.txrecovery.adapter.splunk.SplunkClient;
import com.example.txrecovery.config.RecoveryProperties;
import com.example.txrecovery.domain.model.TransactionDto;
import com.example.txrecovery.domain.model.Watermark;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecoveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-06T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final RecoveryProperties PROPS = new RecoveryProperties(
            "0 */5 * * * *", Duration.ofMinutes(5), 1000, Duration.ofMinutes(10));
    private static final String TEMPLATE =
            "search index=foo earliest=${earliest} latest=${latest}";

    @Test
    void happyPath_advancesWatermarkToWindowEnd() {
        WatermarkRepository wmRepo = mock(WatermarkRepository.class);
        when(wmRepo.find(WatermarkRepository.SPLUNK))
                .thenReturn(Optional.of(new Watermark("splunk", Instant.parse("2026-06-06T11:30:00Z"))));
        WatermarkService wmSvc = new WatermarkService(wmRepo, PROPS, CLOCK);

        SplunkClient splunk = mock(SplunkClient.class);
        when(splunk.runSearch(any())).thenReturn(List.of(
                new TransactionDto("tx-1", NOW, new BigDecimal("1.00"), "OK", "raw")));

        TransactionRepository txRepo = mock(TransactionRepository.class);
        when(txRepo.merge(any())).thenReturn(new InsertCounts(1, 0));

        MeterRegistry reg = new SimpleMeterRegistry();
        RecoveryService svc = new RecoveryService(
                wmSvc, new SplQueryBuilder(TEMPLATE), splunk, txRepo, CLOCK,
                Timer.builder("splunk.search.duration").register(reg),
                Counter.builder("splunk.search.results.count").register(reg),
                Counter.builder("transactions.inserted").register(reg),
                Counter.builder("transactions.skipped").register(reg),
                Counter.builder("transactions.failed").register(reg));

        RecoveryRunSummary s = svc.runOnce();

        assertThat(s.received()).isEqualTo(1);
        assertThat(s.inserted()).isEqualTo(1);
        assertThat(s.advanced()).isTrue();
        assertThat(s.watermarkAfter()).isEqualTo(NOW);
        verify(wmRepo, times(1)).update(eq(WatermarkRepository.SPLUNK), eq(NOW));
    }

    @Test
    void splunkFailure_doesNotAdvanceWatermark() {
        WatermarkRepository wmRepo = mock(WatermarkRepository.class);
        when(wmRepo.find(WatermarkRepository.SPLUNK))
                .thenReturn(Optional.of(new Watermark("splunk", Instant.parse("2026-06-06T11:30:00Z"))));
        WatermarkService wmSvc = new WatermarkService(wmRepo, PROPS, CLOCK);

        SplunkClient splunk = mock(SplunkClient.class);
        when(splunk.runSearch(any())).thenThrow(new RuntimeException("Splunk 500"));

        TransactionRepository txRepo = mock(TransactionRepository.class);

        MeterRegistry reg = new SimpleMeterRegistry();
        RecoveryService svc = new RecoveryService(
                wmSvc, new SplQueryBuilder(TEMPLATE), splunk, txRepo, CLOCK,
                Timer.builder("splunk.search.duration").register(reg),
                Counter.builder("splunk.search.results.count").register(reg),
                Counter.builder("transactions.inserted").register(reg),
                Counter.builder("transactions.skipped").register(reg),
                Counter.builder("transactions.failed").register(reg));

        assertThatThrownBy(svc::runOnce).hasMessageContaining("Splunk 500");

        verify(wmRepo, never()).update(any(), any());
        verify(txRepo, never()).merge(any());
    }

    @Test
    void persistenceFailure_doesNotAdvanceWatermark() {
        WatermarkRepository wmRepo = mock(WatermarkRepository.class);
        when(wmRepo.find(WatermarkRepository.SPLUNK))
                .thenReturn(Optional.of(new Watermark("splunk", Instant.parse("2026-06-06T11:30:00Z"))));
        WatermarkService wmSvc = new WatermarkService(wmRepo, PROPS, CLOCK);

        SplunkClient splunk = mock(SplunkClient.class);
        when(splunk.runSearch(any())).thenReturn(List.of(
                new TransactionDto("tx-1", NOW, new BigDecimal("1.00"), "OK", "raw")));

        TransactionRepository txRepo = mock(TransactionRepository.class);
        when(txRepo.merge(any())).thenThrow(new DataAccessResourceFailureException("oracle down"));

        MeterRegistry reg = new SimpleMeterRegistry();
        RecoveryService svc = new RecoveryService(
                wmSvc, new SplQueryBuilder(TEMPLATE), splunk, txRepo, CLOCK,
                Timer.builder("splunk.search.duration").register(reg),
                Counter.builder("splunk.search.results.count").register(reg),
                Counter.builder("transactions.inserted").register(reg),
                Counter.builder("transactions.skipped").register(reg),
                Counter.builder("transactions.failed").register(reg));

        assertThatThrownBy(svc::runOnce).hasMessageContaining("oracle down");
        verify(wmRepo, never()).update(any(), any());
    }

    @Test
    void emptyBatch_stillAdvancesWatermark() {
        // Documented invariant: quiet periods must still advance the watermark
        // so the overlap window doesn't grow without bound.
        WatermarkRepository wmRepo = mock(WatermarkRepository.class);
        when(wmRepo.find(WatermarkRepository.SPLUNK))
                .thenReturn(Optional.of(new Watermark("splunk", Instant.parse("2026-06-06T11:30:00Z"))));
        WatermarkService wmSvc = new WatermarkService(wmRepo, PROPS, CLOCK);

        SplunkClient splunk = mock(SplunkClient.class);
        when(splunk.runSearch(any())).thenReturn(List.of());

        TransactionRepository txRepo = mock(TransactionRepository.class);
        when(txRepo.merge(any())).thenReturn(InsertCounts.empty());

        MeterRegistry reg = new SimpleMeterRegistry();
        RecoveryService svc = new RecoveryService(
                wmSvc, new SplQueryBuilder(TEMPLATE), splunk, txRepo, CLOCK,
                Timer.builder("splunk.search.duration").register(reg),
                Counter.builder("splunk.search.results.count").register(reg),
                Counter.builder("transactions.inserted").register(reg),
                Counter.builder("transactions.skipped").register(reg),
                Counter.builder("transactions.failed").register(reg));

        RecoveryRunSummary s = svc.runOnce();

        assertThat(s.received()).isZero();
        assertThat(s.advanced()).isTrue();
        verify(wmRepo, times(1)).update(eq(WatermarkRepository.SPLUNK), eq(NOW));
    }
}
