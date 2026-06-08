package com.example.txrecovery.adapter.splunk;

import com.example.txrecovery.config.SplunkProperties;
import com.example.txrecovery.domain.model.TransactionDto;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SplunkClientTest {

    private MockWebServer server;
    private SplunkClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        SplunkProperties props = new SplunkProperties(
                server.url("/").toString(),
                "test-token",
                Duration.ofMillis(50),
                Duration.ofSeconds(5),
                false,
                "search index=foo earliest=${earliest} latest=${latest}"
        );
        WebClient wc = WebClient.builder().baseUrl(props.baseUrl()).build();
        client = new SplunkClient(wc, props);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void happyPath_createPollFetch() throws InterruptedException {
        // create-job -> SID
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"sid\":\"abc-123\"}"));
        // poll -> not done
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {"entry":[{"content":{"isDone":false,"dispatchState":"RUNNING","resultCount":0}}]}
                        """));
        // poll -> done
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {"entry":[{"content":{"isDone":true,"dispatchState":"DONE","resultCount":1}}]}
                        """));
        // results
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {"results":[{
                            "id":"tx-1",
                            "_time":"2026-06-06T12:00:00.000+00:00",
                            "amount":"9.99",
                            "status":"OK",
                            "_raw":"raw"
                        }]}
                        """));

        List<TransactionDto> out = client.runSearch("search foo");

        assertThat(out).hasSize(1);
        assertThat(out.get(0).id()).isEqualTo("tx-1");

        // First request should carry the bearer token and be a form POST.
        RecordedRequest create = server.takeRequest();
        assertThat(create.getMethod()).isEqualTo("POST");
        assertThat(create.getPath()).isEqualTo("/services/search/jobs");
        assertThat(create.getHeader("Authorization")).isEqualTo("Bearer test-token");
        assertThat(create.getBody().readUtf8()).contains("search=search+foo");
    }

    @Test
    void server5xx_isPropagated() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("boom"));
        assertThatThrownBy(() -> client.runSearch("search foo"))
                .isInstanceOf(WebClientResponseException.class);
    }

    @Test
    void slowJob_timesOut() {
        // create-job succeeds
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"sid\":\"abc-123\"}"));
        // every poll says not-done; tight poll-timeout (300ms) trips the deadline.
        for (int i = 0; i < 20; i++) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .setBody("""
                            {"entry":[{"content":{"isDone":false,"dispatchState":"RUNNING","resultCount":0}}]}
                            """));
        }

        SplunkProperties tight = new SplunkProperties(
                server.url("/").toString(),
                "test-token",
                Duration.ofMillis(50),
                Duration.ofMillis(300),
                false,
                "search index=foo earliest=${earliest} latest=${latest}"
        );
        SplunkClient tightClient = new SplunkClient(
                WebClient.builder().baseUrl(tight.baseUrl()).build(), tight);

        assertThatThrownBy(() -> tightClient.runSearch("search foo"))
                .isInstanceOfAny(SplunkTimeoutException.class, IllegalStateException.class);
    }
}
