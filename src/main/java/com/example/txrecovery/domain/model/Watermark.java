package com.example.txrecovery.domain.model;

import java.time.Instant;

/** The persisted high-watermark for a named source (we use {@code "splunk"}). */
public record Watermark(String name, Instant timestamp) {
}
