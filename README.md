# splunk-tx-recovery

A Spring Boot service that periodically pulls transaction events from Splunk via
the REST API, deduplicates them, and persists them to Oracle — with
**no-data-loss** and **no-double-read** guarantees driven by a persistent
watermark and an overlap window.

## Quickstart

### 1. Start the local stack

```bash
docker compose up -d
```

This starts:
- Oracle Free (`localhost:1521`, service `FREEPDB1`, user `appuser / appuser`)
- Splunk (`http://localhost:8000`, admin / `changeme-please`)

Oracle takes ~30–60s on first boot; Splunk takes ~60–90s. Watch with
`docker compose ps`.

### 2. Get a Splunk bearer token

In Splunk Web (`http://localhost:8000`) → Settings → Tokens → enable token auth
→ New Token → user `admin`, audience `tx-recovery`, no expiry. Copy the token.

CLI alternative:
```bash
docker exec -it tx-recovery-splunk \
  /opt/splunk/bin/splunk add token -name tx-recovery -user admin \
  -auth admin:changeme-please
```

### 3. Run the app

```bash
export SPLUNK_TOKEN=<token from step 2>
export DB_USER=appuser
export DB_PASSWORD=appuser
./gradlew bootRun
```

The scheduler runs every 5 minutes (`recovery.schedule-cron` in
`application.yml`). On startup, Flyway creates the `transactions`, `watermark`,
and `shedlock` tables.

### 4. Seed Splunk with test events (optional, recommended for end-to-end verification)

Splunk starts empty, so the recovery service correctly receives `0` events and
the DB stays empty — looks like nothing's happening. Run this once to create
the `app_logs` index and push 20 synthetic transactions via HEC:

```bash
./scripts/seed-splunk.sh           # 20 events
COUNT=100 ./scripts/seed-splunk.sh # or as many as you want
```

Within one cron tick (≤ 5 min) the events will appear in Oracle. Verify:

```bash
docker exec -it tx-recovery-oracle sqlplus appuser/appuser@//localhost:1521/FREEPDB1
SQL> SELECT COUNT(*) FROM transactions;
```

### 5. Observe

- Health: `http://localhost:8080/actuator/health`
- Metrics: `http://localhost:8080/actuator/prometheus`

## Tests

```bash
./gradlew test              # unit tests only (excludes @Tag("integration"))
./gradlew integrationTest   # Testcontainers (slow — pulls Oracle Free image)
./gradlew check             # = test (integrationTest is opt-in)
```

## Configuration

See [`docs/configuration.md`](docs/configuration.md) for every property.

## Architecture & runbook

- [`docs/overview.md`](docs/overview.md) — plain-English "what does this service do?" + why ShedLock is wired in
- [`docs/design.md`](docs/design.md) — pipeline, watermark semantics, MERGE rationale, out-of-scope
- [`docs/code-walkthrough.md`](docs/code-walkthrough.md) — annotated tour of every layer, with sequence diagrams
- [`docs/runbook.md`](docs/runbook.md) — manual watermark reset, backfill, "is this transaction missing?"
- [`docs/database-access.md`](docs/database-access.md) — connect to Oracle, verify data, watermark/ShedLock inspection
- [`docs/diagrams/`](docs/diagrams/) — Mermaid sources (LucidChart-importable)
- [`docs/copilot-prompt.md`](docs/copilot-prompt.md) — self-contained prompt to recreate this project with GitHub Copilot
- [`docs/build-and-nexus.md`](docs/build-and-nexus.md) — Java 25 / Spring Boot 4.0.6 / Gradle 9.5 setup + company Nexus configuration
