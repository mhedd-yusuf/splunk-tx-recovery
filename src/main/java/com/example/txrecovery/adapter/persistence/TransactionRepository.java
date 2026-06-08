package com.example.txrecovery.adapter.persistence;

import com.example.txrecovery.domain.model.TransactionDto;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Collection;

/**
 * Oracle-backed transaction persistence.
 *
 * <p>Uses Oracle {@code MERGE INTO ... USING (SELECT ... FROM dual) ON (id=?)
 * WHEN NOT MATCHED THEN INSERT ...} — the canonical Oracle upsert. We
 * deliberately do <i>not</i> use PostgreSQL's {@code INSERT ... ON CONFLICT}
 * (Oracle does not support it) and we do <i>not</i> use the per-row
 * {@code INSERT + catch DuplicateKeyException} fallback because it costs one
 * round-trip per row.</p>
 */
@Repository
public class TransactionRepository {

    /**
     * MERGE statement. The {@code dual} subquery feeds one row of bind values;
     * the {@code ON (t.id = src.id)} predicate is the dedup gate. There is no
     * {@code WHEN MATCHED} clause — we never update existing transactions.
     */
    private static final String MERGE_SQL = """
            MERGE INTO transactions t
            USING (SELECT :id AS id,
                          :ts AS ts,
                          :amount AS amount,
                          :status AS status,
                          :raw_payload AS raw_payload
                     FROM dual) src
              ON (t.id = src.id)
            WHEN NOT MATCHED THEN
              INSERT (id, ts, amount, status, raw_payload)
              VALUES (src.id, src.ts, src.amount, src.status, src.raw_payload)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public TransactionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Merges the batch into the {@code transactions} table; returns insert/skip counts. */
    public InsertCounts merge(Collection<TransactionDto> batch) {
        if (batch == null || batch.isEmpty()) {
            return InsertCounts.empty();
        }
        SqlParameterSource[] params = batch.stream()
                .map(TransactionRepository::toParams)
                .toArray(SqlParameterSource[]::new);

        int[] perRow = jdbc.batchUpdate(MERGE_SQL, params);

        int inserted = 0;
        int skipped = 0;
        for (int n : perRow) {
            // For per-row MERGE, Oracle returns 1 on insert and 0 on duplicate.
            // SUCCESS_NO_INFO (-2) is treated as inserted (best effort — JDBC
            // driver may report it for batched MERGEs).
            if (n == 0) skipped++;
            else inserted++;
        }
        return new InsertCounts(inserted, skipped);
    }

    /** Test/diagnostic helper — used by integration tests and the runbook. */
    public long count() {
        Long n = jdbc.getJdbcTemplate().queryForObject("SELECT COUNT(*) FROM transactions", Long.class);
        return n == null ? 0L : n;
    }

    private static SqlParameterSource toParams(TransactionDto t) {
        return new MapSqlParameterSource()
                .addValue("id", t.id())
                .addValue("ts", t.timestamp() == null ? null : Timestamp.from(t.timestamp()))
                .addValue("amount", t.amount())
                .addValue("status", t.status())
                .addValue("raw_payload", t.rawPayload());
    }
}
