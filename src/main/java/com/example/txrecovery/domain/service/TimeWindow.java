package com.example.txrecovery.domain.service;

import java.time.Instant;

/** A half-open time window {@code [earliest, latest)} used for one Splunk read. */
public record TimeWindow(Instant earliest, Instant latest) {

    public TimeWindow {
        if (earliest == null || latest == null) {
            throw new IllegalArgumentException("earliest and latest must be non-null");
        }
        if (!earliest.isBefore(latest)) {
            throw new IllegalArgumentException(
                    "earliest must be strictly before latest (got " + earliest + " >= " + latest + ")");
        }
    }
}
