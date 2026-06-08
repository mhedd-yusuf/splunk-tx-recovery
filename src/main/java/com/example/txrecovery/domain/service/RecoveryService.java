package com.example.txrecovery.domain.service;

import com.example.txrecovery.adapter.persistence.InsertCounts;
import com.example.txrecovery.adapter.persistence.TransactionRepository;
import com.example.txrecovery.adapter.splunk.SplQueryBuilder;
import com.example.txrecovery.adapter.splunk.SplunkClient;
import com.example.txrecovery.domain.model.TransactionDto;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The single orchestrator for one recovery run. This is the only class that
 * knows the full pipeline; adapters and the watermark service all know nothing
 * about each other.
 *
 * <pre>
 *   nextWindow -> buildSpl -> splunkClient.runSearch -> repo.merge -> watermark.advance
 * </pre>
 *
 * <p>The watermark is advanced <i>only</i> if MERGE succeeds. Any exception
 * thrown by Splunk or the DB aborts the run; the watermark is untouched and
 * the next scheduled run re-reads the same window.</p>
 */
@Service
public class RecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

    private final WatermarkService watermarkService;
    private final SplQueryBuilder queryBuilder;
    private final SplunkClient splunkClient;
    private final TransactionRepository transactionRepository;
    private final Clock clock;

    private final Timer splunkSearchTimer;
    private final Counter splunkResultsCounter;
    private final Counter insertedCounter;
    private final Counter skippedCounter;
    private final Counter failedCounter;

    public RecoveryService(WatermarkService watermarkService,
                           SplQueryBuilder queryBuilder,
                           SplunkClient splunkClient,
                           TransactionRepository transactionRepository,
                           Clock clock,
                           Timer splunkSearchDuration,
                           Counter splunkSearchResultsCount,
                           Counter transactionsInserted,
                           Counter transactionsSkipped,
                           Counter transactionsFailed) {
        this.watermarkService = watermarkService;
        this.queryBuilder = queryBuilder;
        this.splunkClient = splunkClient;
        this.transactionRepository = transactionRepository;
        this.clock = clock;
        this.splunkSearchTimer = splunkSearchDuration;
        this.splunkResultsCounter = splunkSearchResultsCount;
        this.insertedCounter = transactionsInserted;
        this.skippedCounter = transactionsSkipped;
        this.failedCounter = transactionsFailed;
    }

    /** Executes one recovery run end-to-end and returns the summary. */
    public RecoveryRunSummary runOnce() {
        String runId = UUID.randomUUID().toString();
        Instant start = clock.instant();
        TimeWindow window = watermarkService.nextWindow();
        Instant watermarkBefore = watermarkService.current();

        MDC.put("runId", runId);
        MDC.put("windowStart", window.earliest().toString());
        MDC.put("windowEnd", window.latest().toString());
        try {
            String spl = queryBuilder.build(window.earliest(), window.latest());

            List<TransactionDto> received = splunkSearchTimer.record(() -> splunkClient.runSearch(spl));
            splunkResultsCounter.increment(received.size());

            InsertCounts counts;
            try {
                counts = transactionRepository.merge(received);
            } catch (RuntimeException e) {
                failedCounter.increment(received.size());
                log.error("Persistence failed; watermark NOT advanced. received={}", received.size(), e);
                throw e;
            }
            insertedCounter.increment(counts.inserted());
            skippedCounter.increment(counts.skippedDuplicate());

            // Success: advance watermark to the window's end.
            watermarkService.advance(window.latest());
            Instant watermarkAfter = window.latest();

            Duration elapsed = Duration.between(start, clock.instant());
            RecoveryRunSummary summary = new RecoveryRunSummary(
                    window,
                    received.size(),
                    counts.inserted(),
                    counts.skippedDuplicate(),
                    0L,
                    elapsed,
                    watermarkBefore,
                    watermarkAfter,
                    true);

            log.info("recovery-run runId={} window=[{}..{}) received={} inserted={} skipped_duplicate={} failed={} elapsed_ms={} watermark_advanced_to={}",
                    runId,
                    window.earliest(), window.latest(),
                    summary.received(), summary.inserted(), summary.skippedDuplicate(), summary.failed(),
                    elapsed.toMillis(),
                    watermarkAfter);

            return summary;
        } catch (RuntimeException e) {
            Duration elapsed = Duration.between(start, clock.instant());
            log.warn("recovery-run runId={} window=[{}..{}) FAILED after {} ms; watermark stays at {}",
                    runId, window.earliest(), window.latest(), elapsed.toMillis(), watermarkBefore, e);
            throw e;
        } finally {
            MDC.remove("runId");
            MDC.remove("windowStart");
            MDC.remove("windowEnd");
        }
    }
}
