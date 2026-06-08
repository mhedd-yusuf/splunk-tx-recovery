package com.example.txrecovery.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Placeholder transaction record produced by the Splunk adapter and consumed by
 * the persistence adapter.
 *
 * <p><b>TODO (placeholder):</b> replace these fields with the real transaction
 * schema. This is intentionally minimal so the rest of the pipeline can be
 * exercised end-to-end without committing to a concrete payload yet.</p>
 *
 * <p>Keep this as a record (immutable, value-based equality). Any rename here
 * must be mirrored in:</p>
 * <ul>
 *   <li>{@code adapter.splunk.SplunkResultParser} — field extraction</li>
 *   <li>{@code adapter.persistence.TransactionRepository} — MERGE statement</li>
 *   <li>{@code db/migration/V1__create_transactions.sql} — column list</li>
 * </ul>
 */
public record TransactionDto(
        String id,
        Instant timestamp,
        BigDecimal amount,
        String status,
        String rawPayload
) {
}
