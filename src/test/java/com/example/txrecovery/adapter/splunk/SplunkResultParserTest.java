package com.example.txrecovery.adapter.splunk;

import com.example.txrecovery.domain.model.TransactionDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SplunkResultParserTest {

    @Test
    void parsesIsoOffsetTime() {
        List<TransactionDto> out = SplunkResultParser.parse(List.of(Map.of(
                "id", "tx-1",
                "_time", "2026-06-06T12:00:00.000+00:00",
                "amount", "12.50",
                "status", "OK",
                "_raw", "raw"
        )));
        assertThat(out).hasSize(1);
        TransactionDto t = out.get(0);
        assertThat(t.id()).isEqualTo("tx-1");
        assertThat(t.timestamp()).isEqualTo(Instant.parse("2026-06-06T12:00:00Z"));
        assertThat(t.amount()).isEqualByComparingTo(new BigDecimal("12.50"));
        assertThat(t.status()).isEqualTo("OK");
        assertThat(t.rawPayload()).isEqualTo("raw");
    }

    @Test
    void skipsRowsMissingId() {
        List<TransactionDto> out = SplunkResultParser.parse(List.of(
                Map.of("_time", "2026-06-06T12:00:00.000+00:00"),
                Map.of("id", "", "_time", "2026-06-06T12:00:00.000+00:00"),
                Map.of("id", "tx-1", "_time", "2026-06-06T12:00:00.000+00:00")
        ));
        assertThat(out).extracting(TransactionDto::id).containsExactly("tx-1");
    }

    @Test
    void parsesNumericAmount() {
        List<TransactionDto> out = SplunkResultParser.parse(List.of(Map.of(
                "id", "tx-1",
                "_time", "2026-06-06T12:00:00.000+00:00",
                "amount", 42.25
        )));
        assertThat(out.get(0).amount()).isEqualByComparingTo(new BigDecimal("42.25"));
    }
}
