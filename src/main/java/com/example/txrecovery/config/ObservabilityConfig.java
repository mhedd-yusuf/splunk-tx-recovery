package com.example.txrecovery.config;

import com.example.txrecovery.domain.service.WatermarkService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * Custom Micrometer instruments published to {@code /actuator/prometheus}:
 * <ul>
 *   <li>{@code splunk.search.duration}    — timer for one Splunk runSearch call</li>
 *   <li>{@code splunk.search.results.count} — counter for the number of rows received</li>
 *   <li>{@code transactions.inserted}     — counter for MERGE inserts</li>
 *   <li>{@code transactions.skipped}      — counter for MERGE-skipped duplicates</li>
 *   <li>{@code transactions.failed}       — counter for rows that could not be persisted</li>
 *   <li>{@code watermark.lag.seconds}     — gauge of {@code now - currentWatermark}</li>
 * </ul>
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    public Timer splunkSearchDuration(MeterRegistry registry) {
        return Timer.builder("splunk.search.duration")
                .description("Time to run one Splunk search (create + poll + fetch)")
                .register(registry);
    }

    @Bean
    public Counter splunkSearchResultsCount(MeterRegistry registry) {
        return Counter.builder("splunk.search.results.count")
                .description("Total rows received from Splunk searches")
                .register(registry);
    }

    @Bean
    public Counter transactionsInserted(MeterRegistry registry) {
        return Counter.builder("transactions.inserted")
                .description("Transactions inserted via Oracle MERGE")
                .register(registry);
    }

    @Bean
    public Counter transactionsSkipped(MeterRegistry registry) {
        return Counter.builder("transactions.skipped")
                .description("Duplicate transactions skipped by Oracle MERGE")
                .register(registry);
    }

    @Bean
    public Counter transactionsFailed(MeterRegistry registry) {
        return Counter.builder("transactions.failed")
                .description("Transactions that failed to persist")
                .register(registry);
    }

    /** Registers a gauge that always reports {@code now - currentWatermark} in seconds. */
    @Bean
    public WatermarkLagGauge watermarkLagGauge(MeterRegistry registry,
                                               WatermarkService watermarkService,
                                               Clock clock) {
        WatermarkLagGauge gauge = new WatermarkLagGauge(watermarkService, clock);
        registry.gauge("watermark.lag.seconds", gauge, WatermarkLagGauge::lagSeconds);
        return gauge;
    }

    /** Thin holder for the watermark-lag computation so Micrometer can poll it. */
    public static final class WatermarkLagGauge {
        private final WatermarkService watermarkService;
        private final Clock clock;

        WatermarkLagGauge(WatermarkService watermarkService, Clock clock) {
            this.watermarkService = watermarkService;
            this.clock = clock;
        }

        double lagSeconds() {
            try {
                return Duration.between(watermarkService.current(), clock.instant()).toSeconds();
            } catch (RuntimeException e) {
                return Double.NaN;
            }
        }
    }
}
