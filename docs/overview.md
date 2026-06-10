# Overview: what this project does, and why ShedLock is here

This is the friendly, plain-English companion to [`design.md`](design.md). Read
this first if you're new to the codebase.

## What the project does (in one paragraph)

Transactions get logged to Splunk by other systems. We need a permanent,
queryable copy of those transactions in an Oracle database for reporting,
auditing, and reconciliation. This service is the bridge: every few minutes it
asks Splunk *"give me the transactions that happened since the last time I
asked"*, then writes them into Oracle. It guarantees two things — **nothing
gets lost**, and **nothing gets written twice** — even when Splunk is slow,
the database is briefly unavailable, or the service itself crashes and
restarts.

## How it works, told as a story

1. **A timer fires every 5 minutes.** (`RecoveryScheduler`)
2. **It looks up the watermark** — a single timestamp stored in the
   `watermark` table that says *"I have already processed everything up to
   this point in time."* (`WatermarkService`)
3. **It builds a time window**: from `watermark − 5 minutes` to `now`. The
   5-minute backwards overlap is deliberate — Splunk sometimes indexes events
   a little late, so we re-read the recent past to catch stragglers.
4. **It asks Splunk** for all transaction events in that window using SPL
   (Splunk's query language), via Splunk's REST API. (`SplunkClient`)
5. **It writes the results to Oracle** using a `MERGE` statement, which inserts
   new rows and silently ignores rows whose `id` is already there. This is
   how the 5-minute overlap doesn't create duplicates. (`TransactionRepository`)
6. **It advances the watermark** to the end of the window — but *only if* the
   write succeeded. If anything failed, the watermark stays put and the next
   run will retry the same window.

The whole point of the watermark plus the MERGE is the two guarantees: the
watermark moving forward means "we have this data"; the MERGE means "trying
to write it twice is harmless."

## What the moving pieces are

| Component | Job |
|-----------|-----|
| **Splunk** | The source. Holds transaction events for a limited retention period. |
| **Oracle** | The destination. Long-term storage, queryable by analysts. |
| **This service** | The pump that moves data from Splunk to Oracle, safely. |
| **The watermark table** | One row, one timestamp. "How far have I got?" |
| **The transactions table** | The actual copy of the data in Oracle. |
| **The shedlock table** | The mutex (see next section). |
| **Flyway** | Creates and versions the three tables above on startup. |
| **Actuator + Prometheus** | Health checks and metrics for ops. |

---

## What ShedLock is, and why it's here

### The problem ShedLock solves

In production, you almost never run one copy of a service like this. You run
two or three for redundancy — if one crashes or you're deploying a new
version, another keeps working. That's standard.

But there's a catch with scheduled work. If three copies of this service are
all running, and all three have `@Scheduled(cron = "0 */5 * * * *")`, then
**at 12:00, 12:05, 12:10, etc., all three instances simultaneously try to do
the same recovery run.** Three of them would each ask Splunk for the same
window, get the same events, and try to write them to Oracle at the same
moment.

Now, our MERGE statement already makes duplicate writes harmless — so we
wouldn't get duplicate rows. But:

- We'd hit Splunk 3× harder than needed.
- The watermark would get advanced by whoever finishes first, and the others
  would do wasted work on the (now already-processed) window.
- Worst case, race conditions in watermark reads could cause a window to be
  skipped.

We need **exactly one instance** to do each scheduled run, even when multiple
instances are alive. That's a distributed mutex.

### How ShedLock provides that

[ShedLock](https://github.com/lukas-krecan/ShedLock) is a small library that
turns any shared database (or Redis, or Zookeeper) into a distributed lock
specifically for `@Scheduled` methods. We use the JDBC variant, backed by
Oracle.

It works like this:

1. When the timer fires on **any** instance, ShedLock's interceptor wakes up
   first (before our code runs).
2. It tries to `INSERT` or `UPDATE` a row in the `shedlock` table with the
   lock name `RecoveryScheduler_run`, setting `lock_until = now + 10 minutes`.
3. The `name` column is the primary key, so only **one** instance can win
   that write. The other two get a constraint violation and quietly skip the
   run.
4. The winner runs `RecoveryService.runOnce()`. When it finishes, ShedLock
   updates `lock_until` to the current time, releasing the lock.
5. If the winner *crashes mid-run* and never releases, the `lock_until = now
   + 10 minutes` acts as a deadline. After 10 minutes, the next scheduled
   tick can grab the lock.

This is why the `lockAtMostFor` value matters: it's the "what if the lock
holder died?" timeout. We set it to 10 minutes in `application.yml`
(`recovery.lock-at-most-for: 10m`) — long enough that a real run will always
finish first, short enough that a crash doesn't block recovery for hours.

### Where ShedLock lives in this codebase

| Piece | Where | What it does |
|-------|-------|--------------|
| Annotation on the scheduled method | `RecoveryScheduler.java:28` | `@SchedulerLock(name = "RecoveryScheduler_run", lockAtMostFor = "${recovery.lock-at-most-for}")` |
| Lock provider config | `ShedLockConfig.java` | Tells ShedLock to use Oracle via JDBC, and to use the DB's clock (not the JVM's) |
| Lock table | `V3__create_shedlock.sql` | Creates the `shedlock` table on first startup |
| Gradle deps | `build.gradle` | `shedlock-spring` + `shedlock-provider-jdbc-template` |

### A nuance: `usingDbTime()`

`ShedLockConfig` configures the provider with `.usingDbTime()`. This makes
ShedLock use Oracle's `SYSTIMESTAMP` for all lock bookkeeping instead of the
JVM's clock. Why this matters: if instance A's clock is 30 seconds ahead of
instance B's, and they both compare against their own clocks, they can both
think they hold the lock at the same moment. Using the **database's clock**
as the single source of truth eliminates that class of bug. The DB is the
arbiter; the app clocks don't have to agree with each other.

### Could we just use one instance?

Yes — and for local dev that's exactly what happens. ShedLock with one
instance is just a no-op acquire-and-release on every tick, costing a few
milliseconds. The reason it's in the codebase is that you don't want to add
it the day you scale to two instances and discover the duplicate-work
problem in production. It's cheap insurance that's already wired up.

### Inspecting the lock

```bash
docker exec -it tx-recovery-oracle \
  sqlplus appuser/appuser@//localhost:1521/FREEPDB1
SQL> SELECT * FROM shedlock;
```

You'll see one row per lock name (so, one row total in this project), with
`lock_until` showing when the current holder's grace period expires and
`locked_by` showing which instance grabbed it. If `lock_until` is in the
past, the lock is free.

---

## Related reading

- [`design.md`](design.md) — formal pipeline and watermark semantics
- [`code-walkthrough.md`](code-walkthrough.md) — annotated layer-by-layer tour
- [`runbook.md`](runbook.md) — operational procedures (resetting the watermark, backfill)
- [`database-access.md`](database-access.md) — querying Oracle directly
