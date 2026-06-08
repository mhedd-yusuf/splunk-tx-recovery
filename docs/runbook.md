# Runbook

## Seed Splunk with synthetic events (dev only)

The `app_logs` index doesn't exist on a fresh Splunk. To exercise the full
pipeline end-to-end:

```bash
./scripts/seed-splunk.sh           # creates index, pushes 20 events
COUNT=200 ./scripts/seed-splunk.sh # bigger batch
```

The script is idempotent — re-running it pushes a fresh batch of events with
new ids. Pre-existing ids are absorbed by Oracle MERGE (this is exactly the
dedup contract the service guarantees).

## Start the service locally

```bash
docker compose up -d                          # Oracle + Splunk
export SPLUNK_TOKEN=...                       # see README for how to obtain
export DB_USER=appuser
export DB_PASSWORD=appuser
./gradlew bootRun
```

## Health checks

- App liveness: `curl -s localhost:8080/actuator/health/liveness`
- App readiness: `curl -s localhost:8080/actuator/health/readiness`
- Splunk reachable from the app: tail logs for `recovery-run runId=...` once
  per cron tick. If you see `WARN  recovery-run ... FAILED`, the watermark
  did NOT advance — the next tick will retry the same window.

> For connecting to Oracle (sqlplus inside the container, GUI tools, etc.) and
> general verification queries, see [`database-access.md`](database-access.md).

## Investigate "is this transaction missing?"

Given a Splunk event with `id = X`:

```sql
-- 1. Did we ever persist it?
SELECT id, ts, status, created_at FROM transactions WHERE id = 'X';

-- 2. What is the current watermark?
SELECT name, ts, updated_at FROM watermark WHERE name = 'splunk';

-- 3. Was X's timestamp inside any window we've processed?
--    If ts(X) < watermark.ts, then we should already have it. If MERGE
--    silently skipped, look for a previous insert with the same id (any
--    upstream system retrying the same id with different content would have
--    been ignored — see design.md "out of scope: replay").
```

If `X` is missing and `ts(X) < watermark.ts`:

- Check `transactions.failed` counter — non-zero means a batch was lost.
- Check the application logs around `windowStart..windowEnd` covering `ts(X)`
  for `WARN recovery-run ... FAILED` lines.

## Manually reset the watermark

For example, to re-process the last 24 hours of events:

```sql
-- Connect as appuser
UPDATE watermark
   SET ts = SYSTIMESTAMP - INTERVAL '24' HOUR,
       updated_at = SYSTIMESTAMP
 WHERE name = 'splunk';
COMMIT;
```

The next scheduled run will read everything from then on. **Duplicates are
safe** — Oracle MERGE will skip events that are already in `transactions`.

To reset to the beginning of time (a full backfill):

```sql
UPDATE watermark
   SET ts = TIMESTAMP '1970-01-01 00:00:00.000 +00:00',
       updated_at = SYSTIMESTAMP
 WHERE name = 'splunk';
COMMIT;
```

⚠️ Full backfills can produce a single huge Splunk job. Consider tightening
`splunk.spl-query` to add a narrower time bound before resetting, or do it
in chunks (set watermark to T-24h, wait, then T-24h again).

## Backfill a specific historical window

The simplest way: temporarily lower the watermark, let one or two scheduled
runs cover the window, then verify.

```sql
-- Backfill 2026-06-01 through 2026-06-02.
UPDATE watermark
   SET ts = TIMESTAMP '2026-06-01 00:00:00.000 +00:00'
 WHERE name = 'splunk';
COMMIT;
```

Wait for at least one cron tick. Confirm with the count query above.

If you need to backfill a window that has *already been passed* by the
watermark and you do not want to rewind production, the safer approach is to
run a one-off invocation against a different `watermark.name` and a different
output table — but that is a code change, not a runbook step.

## Pause the scheduler

Set the cron to "never" without redeploying:

```bash
export RECOVERY_SCHEDULE_CRON="-"
```

Spring Boot binds `recovery.schedule-cron` from the env, and `-` disables
the schedule. ShedLock is also unaffected; manually triggering a run requires
calling `RecoveryService.runOnce()` from a test or by re-enabling the schedule.

## Investigate ShedLock state

```sql
SELECT name, lock_until, locked_at, locked_by FROM shedlock;
```

If `lock_until` is in the past, the lock is free. If it's in the future and
`locked_by` is a dead instance, ShedLock will auto-expire it at
`lock_until` — no manual cleanup needed.

## Common alerts

| Symptom                        | Likely cause                                | Action                                                                 |
| ------------------------------ | ------------------------------------------- | ---------------------------------------------------------------------- |
| `watermark.lag.seconds` rising | Splunk slow or app failing                  | Check `recovery-run ... FAILED` logs; check Splunk health             |
| `transactions.failed` non-zero | DB error during MERGE                       | Inspect logs; the run will retry the same window next tick            |
| Circuit breaker OPEN            | Splunk returning 5xx                        | Resilience4j will half-open after 30s; investigate Splunk             |
| No scheduled runs at all       | ShedLock stuck OR cron set to `-`           | Inspect `shedlock` table and `RECOVERY_SCHEDULE_CRON`                 |
