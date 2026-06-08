package com.example.txrecovery.adapter.splunk;

import com.example.txrecovery.domain.model.TransactionDto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Maps raw Splunk result rows (untyped {@code Map<String,Object>}) into
 * {@link TransactionDto} instances.
 *
 * <p>Splunk's {@code _time} field is normally ISO-8601 with offset
 * (e.g. {@code 2026-06-06T12:34:56.000+00:00}). Numeric epoch is also tolerated
 * because some search modes emit it that way.</p>
 */
public final class SplunkResultParser {

    private SplunkResultParser() {
    }

    /** Converts a list of Splunk result rows into {@link TransactionDto}s, skipping rows missing an {@code id}. */
    public static List<TransactionDto> parse(List<Map<String, Object>> rows) {
        Objects.requireNonNull(rows, "rows");
        return rows.stream()
                .map(SplunkResultParser::parseRow)
                .filter(Objects::nonNull)
                .toList();
    }

    private static TransactionDto parseRow(Map<String, Object> row) {
        String id = asString(row.get("id"));
        if (id == null || id.isBlank()) {
            return null;
        }
        return new TransactionDto(
                id,
                parseInstant(asString(row.get("_time"))),
                parseAmount(row.get("amount")),
                asString(row.get("status")),
                asString(row.get("_raw"))
        );
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static BigDecimal parseAmount(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        String s = v.toString().trim();
        return s.isEmpty() ? null : new BigDecimal(s);
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(s));
        } catch (DateTimeParseException ignored) {
            // Fall through to epoch-seconds fallback.
        }
        try {
            return Instant.ofEpochSecond((long) Double.parseDouble(s));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Unparseable _time value: " + s, e);
        }
    }
}
