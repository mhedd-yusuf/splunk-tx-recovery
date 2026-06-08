package com.example.txrecovery.adapter.splunk;

import com.example.txrecovery.config.SplunkProperties;
import com.example.txrecovery.domain.model.TransactionDto;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Splunk REST adapter. Executes the standard search-job lifecycle:
 * <ol>
 *   <li>{@code POST /services/search/jobs} — create job, get SID</li>
 *   <li>{@code GET  /services/search/jobs/{sid}} — poll until {@code isDone=true}</li>
 *   <li>{@code GET  /services/search/jobs/{sid}/results} — fetch results JSON</li>
 * </ol>
 *
 * <p>Resilience4j retry + circuit breaker are layered on top in Step 7 via
 * annotations. The reactive {@link reactor.util.retry.Retry} used here only
 * covers polling cadence — not failure retry.</p>
 */
public class SplunkClient {

    private static final Logger log = LoggerFactory.getLogger(SplunkClient.class);

    private final WebClient webClient;
    private final SplunkProperties props;

    public SplunkClient(WebClient webClient, SplunkProperties props) {
        this.webClient = webClient;
        this.props = props;
    }

    /** Runs the given SPL query and blocks until results are available or the poll timeout fires. */
    @CircuitBreaker(name = "splunk")
    @io.github.resilience4j.retry.annotation.Retry(name = "splunk")
    public List<TransactionDto> runSearch(String spl) {
        String sid = createJob(spl).block(props.pollTimeout());
        if (sid == null) {
            throw new SplunkTimeoutException("Splunk did not return a SID within poll-timeout");
        }
        log.debug("Created Splunk search job sid={}", sid);

        waitForCompletion(sid).block(props.pollTimeout());
        log.debug("Splunk search job sid={} completed", sid);

        SplunkResultsResponse results = fetchResults(sid).block(props.pollTimeout());
        if (results == null || results.results() == null) {
            return List.of();
        }
        return SplunkResultParser.parse(results.results());
    }

    Mono<String> createJob(String spl) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("search", spl);
        form.add("output_mode", "json");
        form.add("exec_mode", "normal");

        return webClient.post()
                .uri("/services/search/jobs")
                .headers(this::auth)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(SplunkJobResponse.class)
                .map(SplunkJobResponse::sid);
    }

    Mono<Void> waitForCompletion(String sid) {
        Instant deadline = Instant.now().plus(props.pollTimeout());
        return pollStatus(sid)
                .flatMap(status -> {
                    if (status.isDone()) {
                        return Mono.empty();
                    }
                    if (Instant.now().isAfter(deadline)) {
                        return Mono.error(new SplunkTimeoutException(
                                "Splunk job sid=" + sid + " did not complete within poll-timeout"));
                    }
                    return Mono.error(new RetrySignal());
                })
                .retryWhen(Retry.fixedDelay(Long.MAX_VALUE, props.pollInterval())
                        .filter(t -> t instanceof RetrySignal))
                .then();
    }

    Mono<SplunkJobStatusResponse> pollStatus(String sid) {
        return webClient.get()
                .uri("/services/search/jobs/{sid}?output_mode=json", sid)
                .headers(this::auth)
                .retrieve()
                .bodyToMono(SplunkJobStatusResponse.class);
    }

    Mono<SplunkResultsResponse> fetchResults(String sid) {
        return webClient.get()
                .uri("/services/search/jobs/{sid}/results?output_mode=json&count=0", sid)
                .headers(this::auth)
                .retrieve()
                .bodyToMono(SplunkResultsResponse.class);
    }

    private void auth(HttpHeaders headers) {
        headers.setBearerAuth(props.token());
    }

    /** Marker error used to drive Reactor's retryWhen-based polling loop. */
    private static final class RetrySignal extends RuntimeException {
        RetrySignal() { super(null, null, false, false); }
    }
}
