package com.example.txrecovery.adapter.splunk;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SplQueryBuilderTest {

    private static final String TEMPLATE = """
            search index=app_logs sourcetype=transactions
            earliest=${earliest} latest=${latest}
            | table id, _time, amount, status, _raw
            """;

    @Test
    void substitutesPlaceholdersAsEpochSeconds() {
        SplQueryBuilder b = new SplQueryBuilder(TEMPLATE);
        Instant earliest = Instant.parse("2026-06-06T00:00:00Z");
        Instant latest = Instant.parse("2026-06-06T01:00:00Z");

        String spl = b.build(earliest, latest);

        // Splunk's inline earliest/latest modifiers accept unquoted epoch seconds.
        assertThat(spl).contains("earliest=" + earliest.getEpochSecond());
        assertThat(spl).contains("latest=" + latest.getEpochSecond());
        assertThat(spl).doesNotContain("${");
        assertThat(spl).doesNotContain("T00:00:00"); // no ISO leak-through
    }

    @Test
    void rejectsTemplateWithoutPlaceholders() {
        assertThatThrownBy(() -> new SplQueryBuilder("search index=foo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("placeholders");
    }

    @Test
    void rejectsBlankTemplate() {
        assertThatThrownBy(() -> new SplQueryBuilder("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullBounds() {
        SplQueryBuilder b = new SplQueryBuilder(TEMPLATE);
        assertThatThrownBy(() -> b.build(null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvertedWindow() {
        SplQueryBuilder b = new SplQueryBuilder(TEMPLATE);
        Instant later = Instant.parse("2026-06-06T01:00:00Z");
        Instant earlier = Instant.parse("2026-06-06T00:00:00Z");
        assertThatThrownBy(() -> b.build(later, earlier))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly before");
    }
}
