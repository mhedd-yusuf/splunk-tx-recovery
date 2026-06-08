package com.example.txrecovery.domain.service;

import com.example.txrecovery.adapter.persistence.WatermarkRepository;
import com.example.txrecovery.config.RecoveryProperties;
import com.example.txrecovery.domain.model.Watermark;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * Owns the watermark — the timestamp of the last successfully processed Splunk
 * event — and computes the time window for the next read.
 *
 * <h2>Watermark semantics</h2>
 * <ul>
 *   <li><b>Inclusive of the prior end? No.</b> Each run queries the half-open
 *       interval {@code [watermark - overlap, now)}. The watermark itself was
 *       already covered by the prior run, but we deliberately re-query a small
 *       window before it (the <i>overlap</i>) to catch events that arrived in
 *       Splunk after the run completed but with timestamps inside the previous
 *       window — late-arriving logs. Duplicates introduced by this re-read are
 *       filtered by the Oracle MERGE in {@code TransactionRepository}, not by
 *       the query.</li>
 *
 *   <li><b>Watermark advances on success only.</b> The orchestrator calls
 *       {@link #advance(Instant)} only after a batch has been successfully
 *       persisted. If anything fails before that (Splunk error, DB error,
 *       parser error), the watermark stays where it was and the next run
 *       re-reads the same window. This gives "at-least-once" semantics for
 *       Splunk reads and, combined with MERGE-based dedup, "exactly-once"
 *       semantics for Oracle writes.</li>
 *
 *   <li><b>Watermark is wall-clock time, not job time.</b> We advance to the
 *       {@code latest} of the window we just read (which is captured at the
 *       start of the run from {@link Clock#instant()}), <i>not</i> to the
 *       maximum event timestamp in the batch. An empty batch still advances
 *       the watermark — otherwise a quiet period would cause an ever-growing
 *       re-read window.</li>
 *
 *   <li><b>Initial value.</b> Flyway V2 inserts a row with {@code ts = epoch}
 *       so the first run reads everything Splunk has indexed. Operators can
 *       pre-seed a more recent value (see {@code docs/runbook.md}) before the
 *       first run if a full backfill is not desired.</li>
 * </ul>
 */
@Service
public class WatermarkService {

    private final WatermarkRepository repo;
    private final RecoveryProperties props;
    private final Clock clock;

    public WatermarkService(WatermarkRepository repo, RecoveryProperties props, Clock clock) {
        this.repo = repo;
        this.props = props;
        this.clock = clock;
    }

    /** Returns the half-open {@code [watermark - overlap, now)} window for the next read. */
    public TimeWindow nextWindow() {
        Instant watermark = repo.find(WatermarkRepository.SPLUNK)
                .map(Watermark::timestamp)
                .orElse(Instant.EPOCH);
        Instant earliest = watermark.minus(props.overlapWindow());
        // Clamp to epoch: pre-1970 timestamps are meaningless and Splunk's
        // search-job endpoint rejects them. Happens on the first run when the
        // seed watermark is EPOCH and overlap subtraction underflows.
        if (earliest.isBefore(Instant.EPOCH)) {
            earliest = Instant.EPOCH;
        }
        Instant latest = clock.instant();
        if (!earliest.isBefore(latest)) {
            // Pathological case: clock skew or a future-dated watermark. We do
            // not throw — we let the orchestrator decide. Returning a 1ms
            // window keeps the SPL builder's invariant (earliest < latest).
            earliest = latest.minusMillis(1);
        }
        return new TimeWindow(earliest, latest);
    }

    /** Advances the watermark to the given instant. MUST only be called after a successful batch. */
    public void advance(Instant to) {
        repo.update(WatermarkRepository.SPLUNK, to);
    }

    /** Returns the current watermark instant (epoch if no row exists). */
    public Instant current() {
        return repo.find(WatermarkRepository.SPLUNK)
                .map(Watermark::timestamp)
                .orElse(Instant.EPOCH);
    }
}
