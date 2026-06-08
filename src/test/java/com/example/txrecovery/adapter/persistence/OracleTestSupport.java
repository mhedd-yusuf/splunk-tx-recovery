package com.example.txrecovery.adapter.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.oracle.OracleContainer;

import javax.sql.DataSource;

/**
 * Shared bootstrap for integration tests: a Singleton {@link OracleContainer},
 * a pooled {@link DataSource}, and Flyway run once.
 *
 * <p>The container is started once per JVM (the static block), which makes
 * subsequent tests in the same run fast. Each test class still runs in its own
 * schema thanks to the test container's default user.</p>
 */
final class OracleTestSupport {

    static final OracleContainer ORACLE;
    static final DataSource DATA_SOURCE;
    static final NamedParameterJdbcTemplate JDBC;

    static {
        ORACLE = new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
                .withUsername("appuser")
                .withPassword("appuser");
        ORACLE.start();

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(ORACLE.getJdbcUrl());
        cfg.setUsername(ORACLE.getUsername());
        cfg.setPassword(ORACLE.getPassword());
        cfg.setMaximumPoolSize(4);
        DATA_SOURCE = new HikariDataSource(cfg);

        Flyway.configure()
                .dataSource(DATA_SOURCE)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        JDBC = new NamedParameterJdbcTemplate(DATA_SOURCE);
    }

    private OracleTestSupport() {
    }
}
