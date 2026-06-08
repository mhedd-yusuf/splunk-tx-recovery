package com.example.txrecovery.domain.service;

import com.example.txrecovery.adapter.persistence.WatermarkRepository;
import com.example.txrecovery.config.RecoveryProperties;
import com.example.txrecovery.domain.model.Watermark;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WatermarkServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-06T12:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final RecoveryProperties PROPS = new RecoveryProperties(
            "0 */5 * * * *", Duration.ofMinutes(5), 1000, Duration.ofMinutes(10));

    @Test
    void nextWindow_subtractsOverlapFromWatermark() {
        WatermarkRepository repo = mock(WatermarkRepository.class);
        Instant wm = Instant.parse("2026-06-06T11:30:00Z");
        when(repo.find(WatermarkRepository.SPLUNK)).thenReturn(Optional.of(new Watermark("splunk", wm)));

        TimeWindow w = new WatermarkService(repo, PROPS, FIXED).nextWindow();

        assertThat(w.earliest()).isEqualTo(Instant.parse("2026-06-06T11:25:00Z"));
        assertThat(w.latest()).isEqualTo(NOW);
    }

    @Test
    void nextWindow_emptyWatermarkClampsAtEpoch() {
        WatermarkRepository repo = mock(WatermarkRepository.class);
        when(repo.find(WatermarkRepository.SPLUNK)).thenReturn(Optional.empty());

        TimeWindow w = new WatermarkService(repo, PROPS, FIXED).nextWindow();

        // Without clamping this would be EPOCH - 5m (pre-1970), which Splunk rejects.
        assertThat(w.earliest()).isEqualTo(Instant.EPOCH);
        assertThat(w.latest()).isEqualTo(NOW);
    }

    @Test
    void nextWindow_futureWatermarkProducesMinimalWindow() {
        WatermarkRepository repo = mock(WatermarkRepository.class);
        // Pretend the watermark is way in the future (clock skew / bad reset).
        Instant wmFuture = NOW.plus(Duration.ofHours(1));
        when(repo.find(WatermarkRepository.SPLUNK))
                .thenReturn(Optional.of(new Watermark("splunk", wmFuture)));

        TimeWindow w = new WatermarkService(repo, PROPS, FIXED).nextWindow();

        // Window must still satisfy earliest < latest.
        assertThat(w.earliest()).isBefore(w.latest());
        assertThat(w.latest()).isEqualTo(NOW);
    }

    @Test
    void advance_writesToRepository() {
        WatermarkRepository repo = mock(WatermarkRepository.class);
        Instant target = Instant.parse("2026-06-06T13:00:00Z");

        new WatermarkService(repo, PROPS, FIXED).advance(target);

        ArgumentCaptor<Instant> cap = ArgumentCaptor.forClass(Instant.class);
        verify(repo, times(1)).update(eq(WatermarkRepository.SPLUNK), cap.capture());
        assertThat(cap.getValue()).isEqualTo(target);
    }

    @Test
    void advance_neverCalledImplicitlyByNextWindow() {
        // Documenting the invariant: nextWindow is a pure read; it must not advance.
        WatermarkRepository repo = mock(WatermarkRepository.class);
        when(repo.find(WatermarkRepository.SPLUNK))
                .thenReturn(Optional.of(new Watermark("splunk", Instant.parse("2026-06-06T11:00:00Z"))));

        new WatermarkService(repo, PROPS, FIXED).nextWindow();

        verify(repo, never()).update(eq(WatermarkRepository.SPLUNK), org.mockito.ArgumentMatchers.any());
    }
}
