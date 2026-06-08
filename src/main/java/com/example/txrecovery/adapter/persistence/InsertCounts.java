package com.example.txrecovery.adapter.persistence;

/**
 * The per-batch outcome of {@code TransactionRepository.merge}.
 *
 * <p>Oracle's {@code MERGE} returns affected-row counts via {@code batchUpdate};
 * a {@code 0} from a per-row MERGE means a duplicate was skipped, a {@code 1}
 * means a new row was inserted.</p>
 */
public record InsertCounts(int inserted, int skippedDuplicate) {

    public static InsertCounts empty() {
        return new InsertCounts(0, 0);
    }
}
