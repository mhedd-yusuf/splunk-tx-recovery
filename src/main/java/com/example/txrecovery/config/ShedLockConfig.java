package com.example.txrecovery.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Wires ShedLock against the Oracle {@code shedlock} table created in Flyway V3.
 *
 * <p>{@code defaultLockAtMostFor} is a safety net for callers that forget to
 * set it — every callsite in this codebase sets its own via the annotation, so
 * this value is just a fallback.</p>
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        // usingDbTime() makes ShedLock use the database's SYSTIMESTAMP for lock
        // bookkeeping, which avoids clock-skew issues across app instances.
        // It is mutually exclusive with withTimeZone(...) — the DB is the clock.
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .withTableName("shedlock")
                        .usingDbTime()
                        .build());
    }
}
