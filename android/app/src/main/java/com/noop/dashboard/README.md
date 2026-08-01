# whoop-garmin dashboard uploader (fork-only)

Pushes NOOP's nightly metrics to a private whoop-garmin dashboard every 3 hours.

## Footprint

One new package (`com/noop/dashboard/`) plus **one line** in
`NoopApplication.onCreate`. `WhoopDao.kt` is deliberately untouched — the
uploader uses `dailyMetricsRange(deviceId, from, "9999-12-31")` and
`sleepSessions(deviceId, from, Long.MAX_VALUE, limit)`, which already exist, so
rebasing onto upstream is a one-line conflict at worst.

## Build and configure

```bash
cd android && ./gradlew assembleDebug
```

Then call `configure` once — a debug button, or temporarily in `onCreate`:

```kotlin
DashboardUploader.configure(
    this,
    baseUrl = "https://whoop-garmin-api.onrender.com",
    token   = "<the INGEST_TOKEN set on the server>",
)
```

Generate the token with `python -c "import secrets; print(secrets.token_urlsafe(32))"`
and set it as `INGEST_TOKEN` on Render. Ingest **fails closed** if it is unset —
a missing secret switches ingest off rather than switching authentication off.

Verify:

```bash
curl -s -H "X-Ingest-Token: $INGEST_TOKEN" \
  https://whoop-garmin-api.onrender.com/api/ingest/whoop/status | python3 -m json.tool
```

## Two rules if you edit DashboardUploader.kt

**Do no arithmetic.** It posts NOOP's column names in NOOP's units
(`totalSleepMin` in minutes, `startTs` in unix seconds) and the server converts,
in one tested mapper. A conversion added here becomes a second mapper that
drifts the moment NOOP migrates a column — silently, because the server
ln-transforms HRV before scoring, so a scale error never shows up in a recovery
number, only in a displayed tile.

**Do not upload workouts.** The dashboard's runs come from a Garmin watch, which
records cadence, running power and per-mile splits a wrist strap cannot.

That second rule is not just preference. `WorkoutRow.sport` is free text and part
of the primary key, and retroactively-detected bouts are stored as
`sport = "detected"` — so a run the strap detected but you never labelled would
pass any "is this a run?" filter and land in the dashboard as a non-run. Add
`startTs` drifting as more HR arrives (which re-keys the row, since startTs is in
the PK) and workout upload cannot currently be made both run-free and
duplicate-free. Nightly metrics have neither problem.

## Gotchas worth knowing

- `dailyMetric.day` is a `"YYYY-MM-DD"` TEXT column compared lexicographically;
  `sleepSession.startTs` is unix **SECONDS** as a Long. Passing millis to the
  sleep query silently returns zero rows.
- `sleepSession.effectiveStartTs` is a computed Kotlin val, **not a column** —
  it cannot be used in a Room `@Query`. The uploader sends raw `startTs` and
  `startTsAdjusted` and lets the server apply the precedence.
- The store is multi-source (`my-whoop`, `<id>-noop`, `whoop-<mac>`, …). The
  uploader reads `dao.activeDeviceId()`, falling back to `"my-whoop"`, which is
  the same default NoopApplication documents.
- `WhoopDatabase.close()` nulls the singleton so a backup import can swap the
  file, so the DAO is re-fetched on every upload rather than held.
