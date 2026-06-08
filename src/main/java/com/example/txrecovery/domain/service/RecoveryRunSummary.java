package com.example.txrecovery.domain.service;

import java.time.Duration;
import java.time.Instant;

/** The outcome of one orchestration run; useful for logging and tests. */
public record RecoveryRunSummary(
        TimeWindow window,
        long received,
        long inserted,
        long skippedDuplicate,
        long failed,
        Duration elapsed,
        Instant watermarkBefore,
        Instant watermarkAfter,
        boolean advanced
) {
}
