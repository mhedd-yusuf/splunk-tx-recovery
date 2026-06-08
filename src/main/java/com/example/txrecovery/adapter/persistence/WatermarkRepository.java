package com.example.txrecovery.adapter.persistence;

import com.example.txrecovery.domain.model.Watermark;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * Persists and reads the single-row {@code watermark} table.
 *
 * <p>There is one row per logical source; this service only uses the
 * {@code 'splunk'} row. The update statement is unconditional — the orchestrator
 * is responsible for only advancing the watermark on success.</p>
 */
@Repository
public class WatermarkRepository {

    public static final String SPLUNK = "splunk";

    private final NamedParameterJdbcTemplate jdbc;

    public WatermarkRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Reads the current watermark row, if present. */
    public Optional<Watermark> find(String name) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT name, ts FROM watermark WHERE name = :name",
                    new MapSqlParameterSource("name", name),
                    (rs, i) -> new Watermark(
                            rs.getString("name"),
                            rs.getTimestamp("ts").toInstant())));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** Overwrites the watermark row's timestamp. */
    public void update(String name, Instant ts) {
        int n = jdbc.update(
                "UPDATE watermark SET ts = :ts, updated_at = SYSTIMESTAMP WHERE name = :name",
                new MapSqlParameterSource()
                        .addValue("ts", Timestamp.from(ts))
                        .addValue("name", name));
        if (n == 0) {
            throw new IllegalStateException(
                    "No watermark row for name=" + name + " — V2 migration should have inserted it.");
        }
    }
}
