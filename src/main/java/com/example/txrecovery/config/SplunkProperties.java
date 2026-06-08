package com.example.txrecovery.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuration for the Splunk REST adapter.
 *
 * <p>The SPL query is intentionally externalized here so it can be tuned without
 * recompiling the application. Use {@code ${earliest}} and {@code ${latest}}
 * placeholders — the application substitutes ISO-8601 timestamps at runtime.</p>
 */
@Validated
@ConfigurationProperties(prefix = "splunk")
public record SplunkProperties(

        @NotBlank
        String baseUrl,

        @NotBlank
        String token,

        @NotNull
        Duration pollInterval,

        @NotNull
        Duration pollTimeout,

        boolean trustSelfSigned,

        @NotBlank
        String splQuery
) {
}
