package com.example.txrecovery.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuration for the recovery pipeline: scheduling cadence, overlap window,
 * batch size, and ShedLock TTL.
 *
 * <p>The {@code overlapWindow} is the duration we subtract from the watermark
 * when computing the {@code earliest} bound of the next Splunk query. It exists
 * to catch late-arriving log events; duplicates are filtered by the dedup layer
 * (Oracle MERGE), not by the query itself.</p>
 */
@Validated
@ConfigurationProperties(prefix = "recovery")
public record RecoveryProperties(

        @NotBlank
        String scheduleCron,

        @NotNull
        Duration overlapWindow,

        @Min(1)
        int batchSize,

        @NotNull
        Duration lockAtMostFor
) {
}
