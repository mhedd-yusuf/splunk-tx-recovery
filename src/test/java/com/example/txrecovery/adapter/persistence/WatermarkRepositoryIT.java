package com.example.txrecovery.adapter.persistence;

import com.example.txrecovery.domain.model.Watermark;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class WatermarkRepositoryIT {

    private final WatermarkRepository repo = new WatermarkRepository(OracleTestSupport.JDBC);

    @Test
    void v2MigrationSeedsTheSplunkRow() {
        Optional<Watermark> w = repo.find(WatermarkRepository.SPLUNK);
        assertThat(w).isPresent();
        assertThat(w.get().timestamp()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void updatePersistsRoundTrip() {
        Instant target = Instant.parse("2026-06-06T12:34:56Z");
        repo.update(WatermarkRepository.SPLUNK, target);
        assertThat(repo.find(WatermarkRepository.SPLUNK))
                .map(Watermark::timestamp)
                .contains(target);
    }
}
