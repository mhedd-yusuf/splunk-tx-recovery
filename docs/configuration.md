# Configuration reference

Every property the service reads, with its type, default, and meaning.

All properties can be overridden by env vars (Spring's relaxed binding:
`splunk.base-url` → `SPLUNK_BASE_URL`).

## Splunk adapter — `splunk.*`

| property                | type      | default                       | meaning                                                                                                                                 |
| ----------------------- | --------- | ----------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| `splunk.base-url`       | string    | `https://localhost:8089`      | Splunk REST API base URL.                                                                                                               |
| `splunk.token`          | string    | (env `SPLUNK_TOKEN`)          | Splunk bearer token. Required. Never hardcode.                                                                                          |
| `splunk.poll-interval`  | Duration  | `1s`                          | How often we re-check the Splunk search job's `isDone` status.                                                                          |
| `splunk.poll-timeout`   | Duration  | `60s`                         | Hard cap on a single Splunk call (create-job, poll, or fetch-results). Exceeding this throws `SplunkTimeoutException` and aborts the run. |
| `splunk.trust-self-signed` | boolean | `true`                      | Trust self-signed TLS certs. **Dev only.** Must be `false` in non-dev.                                                                  |
| `splunk.spl-query`      | string    | (see `application.yml`)       | SPL query template. MUST contain `${earliest}` and `${latest}` placeholders.                                                            |

## Recovery pipeline — `recovery.*`

| property                  | type      | default          | meaning                                                                                                                                       |
| ------------------------- | --------- | ---------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| `recovery.schedule-cron`  | string    | `0 */5 * * * *`  | Spring cron expression for the scheduled run (6 fields, second-precision). Use `-` to disable.                                                |
| `recovery.overlap-window` | Duration  | `5m`             | Subtracted from the watermark when computing the next query's `earliest`. Catches late-arriving Splunk events. Duplicates are deduped by MERGE. |
| `recovery.batch-size`     | int       | `1000`           | Hint for batching at the persistence layer. Currently informational — MERGE batches the entire run.                                           |
| `recovery.lock-at-most-for` | Duration | `10m`           | ShedLock TTL: if the holding instance dies, another may take the lock after this duration.                                                    |

## Datasource — `spring.datasource.*`

| property                          | type    | default                                       | meaning                            |
| --------------------------------- | ------- | --------------------------------------------- | ---------------------------------- |
| `spring.datasource.url`           | string  | `jdbc:oracle:thin:@//localhost:1521/FREEPDB1` | Oracle JDBC URL.                   |
| `spring.datasource.username`      | string  | (env `DB_USER`)                               | DB user (must own the schema).     |
| `spring.datasource.password`      | string  | (env `DB_PASSWORD`)                           | DB password.                       |
| `spring.datasource.driver-class-name` | string | `oracle.jdbc.OracleDriver`                | Don't override.                    |
| `spring.datasource.hikari.maximum-pool-size` | int | `10`                                  | Hikari pool max size.              |
| `spring.datasource.hikari.minimum-idle` | int | `2`                                         | Hikari pool min idle.              |

## Flyway — `spring.flyway.*`

| property                    | type    | default                  | meaning                                                                                |
| --------------------------- | ------- | ------------------------ | -------------------------------------------------------------------------------------- |
| `spring.flyway.enabled`     | boolean | `true`                   | Run migrations at startup.                                                             |
| `spring.flyway.locations`   | list    | `classpath:db/migration` | Migration source location.                                                             |
| `spring.flyway.schemas`     | list    | (the JDBC user)          | Override only if the DB user is not the schema owner.                                  |

## Resilience4j — `resilience4j.*`

The `splunk` instance is referenced by `@Retry(name="splunk")` and
`@CircuitBreaker(name="splunk")` on `SplunkClient.runSearch`.

| property                                                                            | default                | meaning                                                                  |
| ----------------------------------------------------------------------------------- | ---------------------- | ------------------------------------------------------------------------ |
| `resilience4j.retry.instances.splunk.max-attempts`                                  | `3`                    | Max retry attempts (including the first call).                           |
| `resilience4j.retry.instances.splunk.wait-duration`                                 | `500ms`                | Initial backoff.                                                         |
| `resilience4j.retry.instances.splunk.exponential-backoff-multiplier`                | `2`                    | Backoff multiplier.                                                      |
| `resilience4j.circuitbreaker.instances.splunk.sliding-window-size`                  | `20`                   | Calls considered for the failure rate.                                   |
| `resilience4j.circuitbreaker.instances.splunk.failure-rate-threshold`               | `50`                   | Open when failure rate ≥ this %.                                         |
| `resilience4j.circuitbreaker.instances.splunk.wait-duration-in-open-state`          | `30s`                  | How long the breaker stays OPEN before half-opening.                     |

## Actuator — `management.*`

| property                                            | default                                | meaning                                          |
| --------------------------------------------------- | -------------------------------------- | ------------------------------------------------ |
| `management.endpoints.web.exposure.include`         | `health,info,metrics,prometheus`       | Endpoints exposed over HTTP.                     |
| `management.endpoint.health.probes.enabled`         | `true`                                 | Adds `/health/liveness` and `/health/readiness`. |
| `management.prometheus.metrics.export.enabled`      | `true`                                 | Enables `/actuator/prometheus`.                  |

## Logging — `logging.*`

JSON output is gated on the `prod` or `staging` Spring profile (see
`logback-spring.xml`). Default profile emits a human-readable console format.

## Required env vars

These have no sensible default and the app will fail to start without them:

- `SPLUNK_TOKEN`
- `DB_USER`
- `DB_PASSWORD`
