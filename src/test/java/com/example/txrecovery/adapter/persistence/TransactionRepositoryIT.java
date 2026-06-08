package com.example.txrecovery.adapter.persistence;

import com.example.txrecovery.domain.model.TransactionDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class TransactionRepositoryIT {

    private final TransactionRepository repo = new TransactionRepository(OracleTestSupport.JDBC);

    @BeforeEach
    void clean() {
        OracleTestSupport.JDBC.getJdbcTemplate().update("DELETE FROM transactions");
    }

    @Test
    void mergeInsertsNewRows() {
        InsertCounts c = repo.merge(List.of(
                tx("tx-1"), tx("tx-2"), tx("tx-3")
        ));
        assertThat(c.inserted()).isEqualTo(3);
        assertThat(c.skippedDuplicate()).isZero();
        assertThat(repo.count()).isEqualTo(3);
    }

    @Test
    void mergeIsIdempotent_duplicatesSkipped() {
        repo.merge(List.of(tx("tx-1"), tx("tx-2")));
        InsertCounts c = repo.merge(List.of(tx("tx-2"), tx("tx-3")));
        assertThat(c.inserted()).isEqualTo(1);
        assertThat(c.skippedDuplicate()).isEqualTo(1);
        assertThat(repo.count()).isEqualTo(3);
    }

    @Test
    void mergePreservesOriginalRow() {
        // First write — amount=10, status=PENDING.
        repo.merge(List.of(new TransactionDto(
                "tx-1", Instant.parse("2026-06-06T12:00:00Z"),
                new BigDecimal("10.00"), "PENDING", "raw1")));
        // Re-merge of the same id with different data should NOT overwrite.
        InsertCounts c = repo.merge(List.of(new TransactionDto(
                "tx-1", Instant.parse("2026-06-06T12:00:00Z"),
                new BigDecimal("99.99"), "REVERSED", "raw2")));
        assertThat(c.skippedDuplicate()).isEqualTo(1);

        String status = OracleTestSupport.JDBC.getJdbcTemplate()
                .queryForObject("SELECT status FROM transactions WHERE id = 'tx-1'", String.class);
        assertThat(status).isEqualTo("PENDING");
    }

    @Test
    void emptyBatchIsNoop() {
        assertThat(repo.merge(List.of()).inserted()).isZero();
    }

    private static TransactionDto tx(String id) {
        return new TransactionDto(
                id,
                Instant.parse("2026-06-06T12:00:00Z"),
                new BigDecimal("1.00"),
                "OK",
                "raw");
    }
}
