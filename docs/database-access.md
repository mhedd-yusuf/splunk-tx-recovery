# Database access & data verification

How to connect to the Oracle Free container started by `docker compose up -d`
and confirm that the recovery service is doing the right thing.

## Connection parameters

| field              | value                                            |
| ------------------ | ------------------------------------------------ |
| host               | `localhost`                                      |
| port               | `1521`                                           |
| service name       | `FREEPDB1` (the pluggable DB)                    |
| app user           | `appuser` / `appuser` — owns `transactions`, `watermark`, `shedlock` |
| SYS / SYSDBA       | `sys` / `oracle` — only needed for DBA-level work |
| JDBC URL           | `jdbc:oracle:thin:@//localhost:1521/FREEPDB1`    |

The app uses `appuser`. Use that for everything in this doc unless you need DBA
privileges.

## Option 1 — `sqlplus` inside the container (no install on host)

```bash
docker exec -it tx-recovery-oracle sqlplus appuser/appuser@//localhost:1521/FREEPDB1
```

You're now at a `SQL>` prompt inside the container, connected as `appuser`.
Type `exit;` to leave.

For DBA work (rare):

```bash
docker exec -it tx-recovery-oracle sqlplus sys/oracle@//localhost:1521/FREEPDB1 as sysdba
```

## Option 2 — `sqlplus` from the host

Requires Oracle Instant Client. On macOS:

```bash
brew install instantclient-basic instantclient-sqlplus
sqlplus appuser/appuser@//localhost:1521/FREEPDB1
```

## Option 3 — GUI tool (DBeaver / SQL Developer / IntelliJ DataGrip)

Create a new Oracle connection with:

- **Host:** `localhost`
- **Port:** `1521`
- **Database / Service name:** `FREEPDB1` (pick "Service name", not "SID")
- **User:** `appuser`
- **Password:** `appuser`

The driver class is `oracle.jdbc.OracleDriver`; most GUIs bundle it.

---

## Verification queries

All of these run as `appuser`. **Identifier case:** when you don't quote them,
Oracle uppercases identifiers internally — so `transactions` works in `FROM`
clauses but you'll see `TRANSACTIONS` in `USER_TABLES`.

### 1. Sanity check — do the tables exist?

```sql
SELECT table_name
  FROM user_tables
 ORDER BY table_name;
```

Expected: `SHEDLOCK`, `TRANSACTIONS`, `WATERMARK`.

If anything is missing, Flyway didn't run. Check the application logs for
`Successfully applied N migrations` at startup, and:

```sql
SELECT version, description, success, installed_on
  FROM flyway_schema_history
 ORDER BY installed_rank;
```

You should see V1/V2/V3 with `SUCCESS = 1`.

> If the DB is empty and you expected data: Splunk's `app_logs` index doesn't
> exist on a fresh stack. Run `./scripts/seed-splunk.sh` to bootstrap the index
> and push 20 synthetic events, then wait for one scheduler tick.

### 2. How many transactions have we persisted?

```sql
SELECT COUNT(*) AS total,
       MIN(ts)  AS oldest,
       MAX(ts)  AS newest,
       MAX(created_at) AS last_insert_at
  FROM transactions;
```

`total` should grow on each scheduled run (every 5 min by default).
`last_insert_at` confirms the most recent successful MERGE.

### 3. The 20 most recently inserted rows

```sql
SELECT id, ts, amount, status, created_at
  FROM (
    SELECT id, ts, amount, status, created_at
      FROM transactions
     ORDER BY created_at DESC
  )
 WHERE ROWNUM <= 20;
```

(Oracle's `ROWNUM` trick is required pre-12c; modern Oracle accepts
`FETCH FIRST 20 ROWS ONLY` too.)

### 4. Has a specific transaction landed?

```sql
SELECT id, ts, amount, status, created_at
  FROM transactions
 WHERE id = 'tx-12345';
```

If empty, see "Is this transaction missing?" in [`runbook.md`](runbook.md).

### 5. What is the current watermark?

```sql
SELECT name, ts, updated_at
  FROM watermark
 WHERE name = 'splunk';
```

- `ts` is the latest Splunk window-end the service has confirmed-persisted.
- `updated_at` is when the watermark last advanced — should be no older than
  `cron interval + max run time` (default ~5 min).

If `updated_at` is stale, the scheduler isn't running or every run is failing.
Check the app logs for `recovery-run ... FAILED` lines.

### 6. Watermark lag (same metric the app exposes as a gauge)

```sql
SELECT EXTRACT(DAY    FROM (SYSTIMESTAMP - ts)) * 86400
     + EXTRACT(HOUR   FROM (SYSTIMESTAMP - ts)) * 3600
     + EXTRACT(MINUTE FROM (SYSTIMESTAMP - ts)) * 60
     + EXTRACT(SECOND FROM (SYSTIMESTAMP - ts))   AS lag_seconds
  FROM watermark
 WHERE name = 'splunk';
```

Compare against `watermark_lag_seconds` from `/actuator/prometheus`.

### 7. ShedLock state — who is holding the scheduler lock?

```sql
SELECT name, lock_until, locked_at, locked_by
  FROM shedlock;
```

- `lock_until` in the **past** → lock is free, next scheduler tick can take it.
- `lock_until` in the **future** → an instance is holding it; if `locked_by`
  is a dead host, ShedLock auto-releases at `lock_until` (default 10 min TTL,
  see `recovery.lock-at-most-for`).

### 8. Read a CLOB (raw_payload) — sqlplus formatting

CLOBs print as `(huge)` by default. To see them:

```sql
SET LONG 4000
SET LONGCHUNKSIZE 4000
SET LINESIZE 200

SELECT id, raw_payload
  FROM transactions
 WHERE id = 'tx-12345';
```

### 9. Are MERGE duplicates being absorbed as expected?

There's no in-DB counter for this — duplicates are silent at the DB level by
design. Compare the application's `transactions.skipped` counter against
`transactions.inserted`:

```bash
curl -s localhost:8080/actuator/prometheus \
  | grep -E '^transactions_(inserted|skipped|failed)_total'
```

A healthy run typically shows `skipped >= 0` (most of the overlap window
contains rows we already have) and `failed = 0`.

### 10. Inserts per minute (sanity-check throughput)

```sql
SELECT TO_CHAR(created_at, 'YYYY-MM-DD HH24:MI') AS minute,
       COUNT(*)                                  AS rows_inserted
  FROM transactions
 WHERE created_at > SYSTIMESTAMP - INTERVAL '1' HOUR
 GROUP BY TO_CHAR(created_at, 'YYYY-MM-DD HH24:MI')
 ORDER BY minute DESC;
```

---

## Destructive operations (be careful)

These are dev-only — never run them against production.

```sql
-- Wipe persisted transactions but keep schema/migrations
DELETE FROM transactions;
COMMIT;

-- Reset watermark to epoch (next run will re-pull everything Splunk has)
UPDATE watermark
   SET ts = TIMESTAMP '1970-01-01 00:00:00.000 +00:00',
       updated_at = SYSTIMESTAMP
 WHERE name = 'splunk';
COMMIT;

-- Force-release the ShedLock (only if you're sure no instance is running)
DELETE FROM shedlock WHERE name = 'RecoveryScheduler_run';
COMMIT;
```

For more on backfilling and watermark resets, see [`runbook.md`](runbook.md).
