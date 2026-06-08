# Recreating this project with GitHub Copilot in IntelliJ

A copy-pasteable prompt that produces an equivalent service from scratch
using GitHub Copilot Chat in IntelliJ IDEA.

The prompt is the original product spec **plus** every non-obvious gotcha
discovered while building it the first time — things Copilot would otherwise
need 3-5 iterations to get right (Splunk SPL time format, ShedLock 5.x API
changes, JDK 25 Mockito, etc.).

---

## How to use this in IntelliJ IDEA

### 1. Set up an empty project

1. `File → New → Project…`
2. Pick **Gradle**, JDK 21, **Groovy DSL** (not Kotlin DSL — the prompt
   pins this).
3. Group: `com.example`, Artifact: `splunk-tx-recovery`. Click **Create**.
4. Delete the auto-generated `build.gradle`, `settings.gradle`, and any
   `src/` skeleton. Copilot regenerates them. An empty tree is the cleanest
   baseline.

### 2. Open Copilot Chat and configure it

- `View → Tool Windows → GitHub Copilot Chat` (or `Cmd/Ctrl + Shift + C`).
- **Mode selector** (top of the chat panel — `Ask` / `Edit` / `Agent`):
  pick **Agent**. Agent mode can read the workspace, create new files,
  modify multiple files in one turn, and iterate against tool output —
  exactly what scaffolding a project needs. Ask is read-only Q&A; Edit
  produces single-file diffs you have to apply by hand.
- **Model picker** (next to the mode selector): pick **GPT-5.4** (the
  full model). **Do not use GPT-5.4 mini for this task** — mini is faster
  but routinely drops or simplifies constraints in long multi-file prompts
  like this one. Use mini only for follow-up single-file fixes after the
  scaffold exists.

### 3. Send the prompt in one shot

Paste the entire prompt below (between the `=== PROMPT START ===` and
`=== PROMPT END ===` markers) into the chat input. Hit send.

Agent will then:

1. Restate the plan and the file tree it intends to create.
2. Create the files one by one, showing each in a panel with **Accept** /
   **Reject** / **Accept All** buttons.
3. Run shell commands (`./gradlew test`, etc.) and iterate on the output
   automatically — accept or deny each command as it asks.

**Reviewing as you go.** Don't blanket-Accept-All for the first 20 files —
skim each one. If the first few files come out wrong (wrong Spring Boot
version, missing constraint), Reject and add a one-line correction in the
chat: `Use Spring Boot 3.4.3 exactly, not 3.5.x`. Once a few files look
right, Accept All is reasonable.

### 4. If Agent stalls or truncates

GPT-5.4 in Agent mode occasionally pauses mid-stream and asks a clarifying
question. Answer it briefly and tell it to continue: `Yes, proceed with
the remaining files in stage 5 onward.`

If you genuinely run out of room (rare with GPT-5.4 full), say:
`Pause here. Confirm what stage you're on, then continue with the next
stage.`

### 5. Drive verification yourself

Agent will probably run `./gradlew test` on its own — let it. If a test
fails, it'll usually try to fix it. Cap the iteration after two or three
fix attempts on the same file: if Agent is going in circles, Reject the
last change and reply with a specific instruction:

> Stop iterating on `WatermarkServiceTest`. The actual problem is that the
> EPOCH clamp is missing in `WatermarkService.nextWindow()`. Add it before
> the latest-comparison guard.

Don't ask Agent "is the project correct?" — run the verification checklist
at the bottom of this file. Targeted fixes work; open-ended audits don't.

---

## The prompt

Copy from `=== PROMPT START ===` to `=== PROMPT END ===` (exclusive of the
fences). Paste verbatim into the IntelliJ Copilot Chat input. (In Agent
mode you don't need to prefix with `@workspace` — Agent always has
workspace access.)

```
=== PROMPT START ===

You are a senior Java engineer. Build a complete, production-quality Spring
Boot service that periodically pulls transaction events from Splunk via the
REST API, deduplicates them, and persists them to Oracle. The service must
guarantee that no event is read twice and no event between reads is missed.

Produce file contents as separate fenced code blocks, with each fence's
opening line naming the file path (e.g. ```java path=build.gradle).
Use a hexagonal-ish layered structure. Do not stub anything except where
this prompt explicitly says "placeholder".

## Tech stack (use these exact versions / pins)

- Java 21 (LTS)
- Spring Boot 3.4.3
- Gradle 8.x (Groovy DSL, build.gradle — NOT Kotlin DSL)
- Oracle Database 19c+ in production; gvenzl/oracle-free for local dev
- Oracle JDBC driver: com.oracle.database.jdbc:ojdbc11:23.6.0.24.10
- Flyway 10.x with the separate flyway-database-oracle module (Oracle support
  moved out of flyway-core in 10.x)
- Spring Data JDBC (NOT JPA — no lazy-loading surprises)
- Spring's WebClient for Splunk REST calls (NOT the Splunk Java SDK)
- ShedLock 5.16+ (shedlock-spring + shedlock-provider-jdbc-template)
- Resilience4j 2.2.x (resilience4j-spring-boot3 + resilience4j-reactor)
- Micrometer + micrometer-registry-prometheus
- Logback + net.logstash.logback:logstash-logback-encoder for structured JSON logs
- Testcontainers 1.20.x with the org.testcontainers:oracle-free module
- com.squareup.okhttp3:mockwebserver for Splunk-side test doubles
- JUnit 5, AssertJ, Mockito

JDK-25 compatibility note: Spring Boot 3.4 pins Mockito 5.14 / ByteBuddy
1.15 which do NOT support JDK 25 bytecode. In build.gradle, force-override
to mockito 5.18.0 and byte-buddy 1.17.6 via dependencyManagement.

## Functional requirements

### Splunk integration

- Authenticate with a Bearer token from configuration (env var, never hard-
  coded).
- Run a configurable SPL query via the standard search-job lifecycle:
  POST /services/search/jobs → poll GET /services/search/jobs/{sid} → fetch
  GET /services/search/jobs/{sid}/results?output_mode=json.
- The SPL query MUST live in application.yml with ${earliest} and ${latest}
  placeholders. The application substitutes them at runtime; no SPL string
  appears in Java code.
- CRITICAL: substitute placeholders with epoch seconds, NOT ISO-8601. Splunk's
  inline earliest=/latest= modifiers accept epoch seconds, relative time
  (-7d), or quoted strings with explicit timeformat. ISO-8601 with T/Z
  causes Splunk to parse-fail the search and return 400 on /results.
- Parse results into a placeholder record TransactionDto with five fields:
  id (String), timestamp (Instant), amount (BigDecimal), status (String),
  rawPayload (String). Put a TODO comment on this record clearly marking it
  as a placeholder for the real schema.

### Watermark-based incremental reads (core correctness requirement)

- Single-row watermark table holding the timestamp of the last successfully
  processed Splunk event window.
- Each scheduled run queries Splunk for events in [watermark - overlap, now),
  where overlap is a configurable Duration (default 5 minutes) that catches
  late-arriving logs.
- Duplicates introduced by the overlap window are filtered by the DB layer,
  NOT by the query.
- The watermark advances ONLY after the batch is successfully persisted.
  Any failure aborts the run with the watermark untouched.
- An empty batch still advances the watermark (otherwise a quiet period
  would cause an ever-growing re-read window).
- CRITICAL: clamp earliest to Instant.EPOCH. With the seed watermark at
  epoch, naive watermark - overlap underflows to 1969, which Splunk rejects.
- Document all of the above in a Javadoc block on WatermarkService.

### Deduplication (Oracle-specific)

- The transactions table has PRIMARY KEY (id) on the natural key.
- Inserts use Oracle MERGE INTO transactions USING (SELECT … FROM dual)
  ON (id = :id) WHEN NOT MATCHED THEN INSERT ... — no WHEN MATCHED clause,
  duplicates are silently absorbed.
- Use NamedParameterJdbcTemplate.batchUpdate so MERGE is properly batched.
- Do NOT use INSERT ... ON CONFLICT (that is PostgreSQL syntax; Oracle has
  no such construct).
- Log per-run counts: received, inserted, skipped_duplicate, failed.

### Scheduling

- Spring @Scheduled with a cron expression bound from recovery.schedule-cron
  (default "0 */5 * * * *").
- Wrap with @SchedulerLock so multiple instances don't run concurrently.
- ShedLock 5.x API note: the attribute is lockAtMostFor (String, not
  lockAtMostForString). And on JdbcTemplateLockProvider, usingDbTime() is
  mutually exclusive with withTimeZone(...) — pick usingDbTime().

### Resilience

- Splunk calls wrapped with Resilience4j @Retry (exponential backoff, max 3)
  and @CircuitBreaker (count-based window of 20, opens at 50% failure rate).
- Polling has a configurable hard timeout (default 60s).
- IMPORTANT: avoid the Java import collision between
  io.github.resilience4j.retry.annotation.Retry and reactor.util.retry.Retry
  by using the fully-qualified @Retry annotation on SplunkClient.

### Observability

- Expose /actuator/health, /actuator/metrics, /actuator/prometheus.
- Custom Micrometer metrics: splunk.search.duration (timer),
  splunk.search.results.count (counter), transactions.inserted (counter),
  transactions.skipped (counter), transactions.failed (counter),
  watermark.lag.seconds (gauge of now - currentWatermark).
- Structured JSON logging gated on the prod or staging Spring profile via
  logback-spring.xml + logstash-logback-encoder. Human-readable console for
  the default profile.
- Propagate runId, windowStart, windowEnd via SLF4J MDC.
- One INFO summary line per successful run with all counts and the window.

## Architecture

Use this exact package layout:

    com.example.txrecovery
    ├── config/              # @Configuration classes + @ConfigurationProperties records
    ├── domain/
    │   ├── model/           # TransactionDto, Watermark, TimeWindow, RecoveryRunSummary
    │   └── service/         # RecoveryService (orchestrator), WatermarkService
    ├── adapter/
    │   ├── splunk/          # SplunkClient, response DTOs, SplQueryBuilder, SplunkResultParser
    │   └── persistence/     # TransactionRepository, WatermarkRepository, InsertCounts
    ├── scheduling/          # RecoveryScheduler (@Scheduled entry point)
    └── TxRecoveryApplication.java

The RecoveryService is the single orchestrator and the only class that knows
the full pipeline. Adapters know nothing about each other.

## Configuration (application.yml — match this exactly)

    spring:
      application:
        name: splunk-tx-recovery
      datasource:
        url: jdbc:oracle:thin:@//localhost:1521/FREEPDB1
        username: ${DB_USER}
        password: ${DB_PASSWORD}
        driver-class-name: oracle.jdbc.OracleDriver
      flyway:
        enabled: true
        locations: classpath:db/migration

    splunk:
      base-url: https://localhost:8089
      token: ${SPLUNK_TOKEN}
      poll-interval: 1s
      poll-timeout: 60s
      trust-self-signed: true   # dev only — document this in code
      spl-query: |
        search index=app_logs sourcetype=transactions
        earliest=${earliest} latest=${latest}
        | table id, _time, amount, status, _raw

    recovery:
      schedule-cron: "0 */5 * * * *"
      overlap-window: 5m
      batch-size: 1000
      lock-at-most-for: 10m

    management:
      endpoints:
        web:
          exposure:
            include: health,info,metrics,prometheus

    resilience4j:
      retry:
        instances:
          splunk:
            max-attempts: 3
            wait-duration: 500ms
            enable-exponential-backoff: true
            exponential-backoff-multiplier: 2
      circuitbreaker:
        instances:
          splunk:
            sliding-window-size: 20
            failure-rate-threshold: 50
            wait-duration-in-open-state: 30s

## Oracle-specific rules (apply throughout)

- Use VARCHAR2 (not VARCHAR), NUMBER(19,4) (not NUMERIC), TIMESTAMP(6) WITH
  TIME ZONE for instants, CLOB for any string that might exceed 4000 bytes.
- Identifiers unquoted and lowercase in SQL (Oracle uppercases them
  internally — don't use quoted identifiers).
- The ShedLock table must use Oracle types from the ShedLock docs:
  name VARCHAR2(64) PK, lock_until TIMESTAMP(3), locked_at TIMESTAMP(3),
  locked_by VARCHAR2(255).
- Flyway V2 must seed watermark with one row: name='splunk', ts=epoch.

## Docker Compose (docker-compose.yml)

Two services: Oracle and Splunk. Apple Silicon notes apply:

- Oracle: gvenzl/oracle-free:latest-faststart. Env: ORACLE_PASSWORD=oracle,
  APP_USER=appuser, APP_USER_PASSWORD=appuser. Expose 1521.
- Splunk: pin to splunk/splunk:9.0.5 (newer images bundle a MongoDB build
  that requires AVX, which Apple Silicon Rosetta does NOT emulate; 9.0.5
  bundles MongoDB 4.x and runs fine). Set platform: linux/amd64 explicitly.
  Env: SPLUNK_START_ARGS=--accept-license, SPLUNK_PASSWORD=changeme-please,
  SPLUNK_HEC_TOKEN=00000000-0000-0000-0000-000000000000. Expose 8000, 8089,
  8088.
- Healthcheck for Splunk: curl -sSk (NOT -f — Splunk's /server/info always
  returns 401 to anonymous requests, which -f treats as failure).

## Tests

- Unit tests for SplQueryBuilder (placeholder substitution, epoch-second
  format), SplunkResultParser (ISO + epoch _time, missing id),
  WatermarkService (overlap math, EPOCH clamp, advance-on-success),
  RecoveryService (happy + Splunk failure + DB failure + empty batch),
  SplunkClient (happy path + 5xx + poll timeout) against MockWebServer.
- Integration tests tagged with @Tag("integration") using
  org.testcontainers:oracle-free for a real Oracle and MockWebServer for
  Splunk. Must cover: happy path persists + advances watermark, duplicate
  on second run is skipped, Splunk 5xx leaves watermark unchanged, Splunk
  timeout leaves watermark unchanged.
- Gradle config: default test task EXCLUDES @Tag("integration"); a separate
  integrationTest task includes them. check depends only on test.

## Scripts

Create scripts/seed-splunk.sh (executable bash):
- Probes Splunk mgmt API at https://localhost:8089
- Creates index app_logs if missing (POST /services/data/indexes,
  idempotent — handle 200 / 404 / create-201 / other)
- Pushes COUNT (default 20) synthetic transaction events via HEC at
  https://localhost:8088/services/collector/event with HEC token from env
  (default 00000000-0000-0000-0000-000000000000)
- Each event: {time, index, sourcetype:"transactions", event:{id, amount, status}}
- Stagger timestamps over the past hour
- Verify with a oneshot SPL | stats count at the end
- Override knobs: COUNT, SPLUNK_USER, SPLUNK_PASS, HEC_TOKEN, INDEX

## Documentation

Create these markdown files:
- README.md — one-page quickstart (docker compose up; export env vars;
  seed-splunk.sh; ./gradlew bootRun; verification queries)
- docs/design.md — architecture, watermark semantics, MERGE rationale
  (explicitly noting why not ON CONFLICT), out-of-scope section, Mermaid
  pipeline diagram
- docs/runbook.md — start the service, health checks, "is this transaction
  missing?" investigation queries, manual watermark reset SQL, backfill,
  pause via RECOVERY_SCHEDULE_CRON=-, ShedLock inspection
- docs/configuration.md — every property × type × default × meaning
- docs/database-access.md — sqlplus inside the container, GUI tools,
  verification queries
- docs/code-walkthrough.md — layer-by-layer annotated tour with Mermaid
  sequence diagrams

## Style / constraints

- Constructor injection only. No field injection.
- @ConfigurationProperties records, validated with @Validated + jakarta.validation.
- Records (not classes / not Lombok) for all immutable data.
- One-line Javadoc on every public service method.
- The only allowed TODO comments are: (1) on TransactionDto marking it as a
  placeholder for the real schema; (2) on the trust-self-signed TLS branch
  in SplunkClientConfig marking it as dev-only.
- No premature abstraction. Don't extract interfaces unless there are
  genuinely two implementations (SplunkClient and the repositories are real
  seams; most other things aren't).
- Don't add features, refactor surrounding code, or design for hypothetical
  future requirements.

## Build order

You may produce the full project in one turn (you are running in Agent
mode and can create files directly). But order the work this way so the
file tree is reviewable top-to-bottom and tests run as they're added:

1. Project skeleton: build.gradle, settings.gradle, gradle.properties,
   application.yml, logback-spring.xml, TxRecoveryApplication, config
   properties records, docker-compose.yml, README quickstart, .gitignore.
2. Splunk adapter with MockWebServer unit tests.
3. Persistence layer (Flyway migrations + repositories) with Testcontainers
   integration tests.
4. WatermarkService with unit tests.
5. RecoveryService orchestrator wiring it together, with unit tests.
6. Scheduling + ShedLock wiring.
7. Resilience4j + observability wiring.
8. Scripts and documentation.

After each stage, run `./gradlew compileJava` (and `./gradlew test` after
stages that add tests). If a step fails, fix it before moving on — do not
proceed with a broken intermediate state.

When the whole project is built, run `./gradlew test` one final time and
report the test counts.

=== PROMPT END ===
```

---

## Verification checklist

Agent will run most of these itself. Re-run them yourself in the IntelliJ
Terminal (`Alt+F12`) once Agent reports done:

```bash
# 1. Compile cleanly
./gradlew compileJava compileTestJava
# Expected: BUILD SUCCESSFUL

# 2. Unit tests
./gradlew test
# Expected: 20+ tests, all green, @Tag("integration") tests skipped

# 3. Docker stack
docker compose up -d
docker compose ps
# Expected: oracle and splunk containers, both eventually healthy

# 4. Seed Splunk
./scripts/seed-splunk.sh
# Expected: all five steps green

# 5. Boot the app (Ctrl+C once you see "Started TxRecoveryApplication")
export SPLUNK_TOKEN=<minted via REST as documented in docs/database-access.md>
export DB_USER=appuser DB_PASSWORD=appuser
./gradlew bootRun
# Expected: starts cleanly; within one cron tick prints
# "recovery-run runId=... received=20 inserted=20"

# 6. Verify in DB
docker exec tx-recovery-oracle sqlplus -S appuser/appuser@//localhost:1521/FREEPDB1 <<< "SELECT COUNT(*) FROM transactions;"
# Expected: 20

# 7. Integration tests (slow, requires Docker)
./gradlew integrationTest
# Expected: green
```

If something fails, send the exact error to Agent with the file path:

> Fix `src/main/java/.../X.java`. `./gradlew test` fails with:
> ```
> <paste compiler / test / runtime error verbatim>
> ```

Targeted, path-named fixes work; open-ended "review the project" prompts
waste turns.

---

## Most common Copilot stumbles (pre-empted by the prompt)

| Symptom Copilot produces                                | Why the prompt heads it off                                  |
| ------------------------------------------------------- | ------------------------------------------------------------ |
| `INSERT ... ON CONFLICT` on Oracle                      | Explicit "use MERGE, NOT ON CONFLICT" with reason            |
| `lockAtMostForString` on @SchedulerLock                 | Note about ShedLock 5.x API change                           |
| `withTimeZone(...).usingDbTime()` chained               | Note about mutual exclusion                                  |
| Splunk SPL with `earliest=2026-06-08T...Z`              | "epoch seconds, NOT ISO-8601" with the 400-on-/results reason|
| Pre-epoch earliest on first run                         | Clamp-at-EPOCH requirement                                   |
| Mockito errors on JDK 25                                | Version override mandate                                     |
| Splunk container restart-loops on Apple Silicon         | Pin to 9.0.5 + AVX rationale                                 |
| Healthcheck marks healthy Splunk as unhealthy           | `-sSk` not `-fk` mandate                                     |
| `@Retry` import collision with `reactor.util.retry.Retry` | "use fully-qualified annotation" note                       |
| Kotlin DSL (`build.gradle.kts`) instead of Groovy       | Explicit "NOT Kotlin DSL" pin                                |

## IntelliJ + Agent mode tips

- **Mode/model rule of thumb:**
  - **Agent + GPT-5.4 (full)** — initial scaffold, big refactors,
    anything multi-file.
  - **Edit + GPT-5.4 mini** — single-file fixes once the project exists
    (faster, cheap). Open the file, select the region, hit Edit, describe
    the change.
  - **Ask + GPT-5.4 mini** — "what does this do?" / "why is this here?"
    questions. No file mutations.
- **Approve commands deliberately.** In Agent mode every shell command
  pops a confirmation. Read the command before approving. Reject and
  rephrase if Agent wants to do something invasive (e.g. `rm -rf`,
  global `git` operations, package installs you don't recognise).
- **Checkpointing.** Commit to git after each stage Agent finishes
  (`git add . && git commit -m "stage N: ..."`). If a later stage corrupts
  earlier work you can `git reset --hard` to the last good commit without
  losing the scaffold.
- **Run configurations:** after stage 1, IntelliJ auto-detects the Spring
  Boot main class and offers a run config. Use it instead of
  `./gradlew bootRun` inside the IDE — you get the debugger and DevTools
  live restart.
- **Database tool window:** `View → Tool Windows → Database`, add an Oracle
  data source pointing at `jdbc:oracle:thin:@//localhost:1521/FREEPDB1` with
  appuser/appuser. Faster than `sqlplus` for verification queries.
- **Mermaid preview:** install the *Mermaid* IntelliJ plugin to preview the
  diagrams in `docs/diagrams/*.mmd` without leaving the IDE.
- **HTTP Client:** if Agent generates `.http` files under
  `src/test/resources/`, they let you hit `/actuator/prometheus` and Splunk
  REST endpoints directly from the editor (`Cmd+Click` on the URL).
