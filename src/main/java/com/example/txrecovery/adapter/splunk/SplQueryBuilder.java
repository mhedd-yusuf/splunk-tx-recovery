package com.example.txrecovery.adapter.splunk;

import java.time.Instant;

/**
 * Substitutes {@code ${earliest}} and {@code ${latest}} placeholders in the
 * configured SPL query template with epoch-second timestamps.
 *
 * <p>Splunk's inline {@code earliest=}/{@code latest=} time modifiers accept
 * three formats: epoch seconds, relative time (e.g. {@code -7d}), or a quoted
 * string with an explicit {@code timeformat}. ISO-8601 ({@code 2026-06-07T...Z})
 * is <i>not</i> valid here — Splunk fails to parse the search and the resulting
 * job's {@code /results} endpoint returns 400. We use epoch seconds because
 * they are unambiguous and require no quoting.</p>
 *
 * <p>Kept as a tiny standalone class so it is trivial to unit-test and so the
 * SPL template stays the single source of truth (no inline SPL anywhere in
 * Java code).</p>
 */
public final class SplQueryBuilder {

    private final String template;

    public SplQueryBuilder(String template) {
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException("SPL template must not be blank");
        }
        if (!template.contains("${earliest}") || !template.contains("${latest}")) {
            throw new IllegalArgumentException(
                    "SPL template must contain ${earliest} and ${latest} placeholders");
        }
        this.template = template;
    }

    /** Returns the SPL query with the time-window placeholders substituted. */
    public String build(Instant earliest, Instant latest) {
        if (earliest == null || latest == null) {
            throw new IllegalArgumentException("earliest and latest must be non-null");
        }
        if (!earliest.isBefore(latest)) {
            throw new IllegalArgumentException("earliest must be strictly before latest");
        }
        return template
                .replace("${earliest}", Long.toString(earliest.getEpochSecond()))
                .replace("${latest}", Long.toString(latest.getEpochSecond()));
    }
}
