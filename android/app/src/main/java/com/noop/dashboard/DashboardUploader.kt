package com.noop.dashboard

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.noop.data.WhoopDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Pushes NOOP's nightly metrics to a private whoop-garmin dashboard.
 *
 * ## Two rules this file exists to keep
 *
 * **1. No arithmetic, no unit conversion.** It reads NOOP's own columns and
 * posts them under NOOP's own names, in NOOP's own units (`totalSleepMin` in
 * MINUTES, `startTs` in unix SECONDS). The server converts, in one tested
 * mapper. A converter here would be a SECOND mapper that drifts the first time
 * NOOP migrates a column — silently, because the server ln-transforms HRV
 * before scoring, so a scale error never shows in a recovery number.
 *
 * **2. Nightly metrics only — never workouts.** The dashboard's runs come from
 * a Garmin watch, which records cadence, running power and per-mile splits that
 * a wrist strap cannot. Uploading NOOP's workouts would at best duplicate them
 * and at worst override them.
 *
 * That second rule is stronger than it looks. NOOP's `WorkoutRow.sport` is FREE
 * TEXT and part of the primary key, and retroactively-detected bouts are stored
 * with `sport = "detected"` — so a run the strap detected but you never
 * labelled would pass any "is this a run?" filter and land in the dashboard as
 * a non-run. Combined with `startTs` drifting as more HR arrives (which re-keys
 * the row, since startTs is in the PK), workout upload cannot currently be made
 * both run-free and duplicate-free. Dailies have no such problem.
 *
 * ## Additive by design
 *
 * Nothing in the upstream tree is modified except a single
 * `DashboardUploader.schedule(this)` line in NoopApplication.onCreate. It uses
 * only DAO methods that already exist, so `WhoopDao.kt` is untouched and
 * rebasing onto upstream stays trivial.
 */
object DashboardUploader {

    private const val TAG = "DashboardUploader"
    private const val WORK = "whoop_garmin_dashboard_upload"
    private const val WORK_ONCE = "whoop_garmin_dashboard_upload_once"
    private const val PREFS = "dashboard_uploader"

    /** How far back to re-send on a ROUTINE run. Re-sending an overlapping
     *  window is intentional: the server upserts, and the strap's own store is
     *  only ~14 days deep, so a gap that scrolls off it is gone for good. */
    private const val LOOKBACK_DAYS = 21L

    /** How far back on the FIRST run against an empty server.
     *
     * A fixed 21-day window is wrong for the initial sync: a store seeded from
     * a WHOOP CSV export can hold years of nights, and if none of them fall in
     * the last three weeks the uploader sends nothing and reports success —
     * which looks identical to "working, nothing new". Ask the server what it
     * has; if it has nothing, send everything. */
    private const val BACKFILL_DAYS = 3650L

    /** Bump this whenever the SERVER changes how it reads the payload.
     *
     * A routine run only re-sends [LOOKBACK_DAYS] anchored to *today*, so a
     * server-side fix can never reach rows older than that window — and if the
     * strap has not been worn recently, the window is empty and the run is a
     * no-op that reports success.
     *
     * That is not hypothetical. The dashboard bounded Effort to 0-21 (WHOOP's
     * scale) when NOOP reports it on 0-100, so it dropped the value on 94% of
     * days. Fixing the bound backfilled nothing: the strap's history ends
     * ~2025-07-15, every routine run found the last three weeks empty, and
     * 1,333 days kept a null Effort with the client insisting it was in sync.
     *
     * Bumping this forces one full re-send, then records the new epoch. */
    private const val SYNC_EPOCH = 2

    /** Room requires an explicit LIMIT on the sleep read. Sized for a full
     *  backfill (a multi-year imported history), not just three weeks — a
     *  silent truncation here would lose the oldest nights without a word. */
    private const val SLEEP_ROW_LIMIT = 5000

    /** What the device registry falls back to before anything is paired — the
     *  same default NoopApplication documents. */
    private const val DEFAULT_DEVICE_ID = "my-whoop"

    fun configure(context: Context, baseUrl: String, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("base_url", baseUrl.trimEnd('/'))
            .putString("token", token)
            .apply()
        schedule(context)
    }

    /**
     * Pick up configuration dropped as a file, so no UI and no rebuild is
     * needed to point this at a server.
     *
     *   adb push dashboard.json /sdcard/Android/data/<applicationId>/files/
     *
     * with `{"baseUrl": "...", "token": "..."}`. That directory is the app's
     * own external files dir, which adb can write WITHOUT root and without any
     * manifest permission — so this costs the fork nothing.
     *
     * The file is DELETED once imported: a bearer token should not sit in
     * plaintext on shared storage, where any app with media access could read
     * it. Config lands in SharedPreferences, which is app-private.
     */
    fun importConfigFileIfPresent(context: Context): Boolean {
        val f = java.io.File(context.getExternalFilesDir(null), "dashboard.json")
        if (!f.exists()) return false
        return try {
            val o = JSONObject(f.readText())
            val url = o.optString("baseUrl").trim()
            val token = o.optString("token").trim()
            if (url.isEmpty() || token.isEmpty()) {
                Log.w(TAG, "dashboard.json missing baseUrl or token; ignoring")
                false
            } else {
                configure(context, url, token)
                Log.i(TAG, "configured from dashboard.json -> ${url.take(40)}")
                true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "dashboard.json is not valid JSON; ignoring", t)
            false
        } finally {
            // Delete on ANY outcome, including a malformed file — leaving a
            // half-read token on disk is the worst case.
            runCatching { f.delete() }
        }
    }

    fun isConfigured(context: Context): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return !p.getString("base_url", null).isNullOrBlank() &&
            !p.getString("token", null).isNullOrBlank()
    }

    /**
     * Every 3 hours, network-connected only.
     *
     * WorkManager's periodic floor is 15 minutes, but there is no point being
     * more eager than the data: nightly metrics change once a night, and NOOP
     * re-offloads roughly every 15 minutes while connected anyway. KEEP rather
     * than REPLACE so an app restart doesn't reset the interval clock and
     * starve the job on a phone that reboots often.
     */
    fun schedule(context: Context) {
        // Check for dropped config before giving up — this is what makes
        // "adb push, then open the app" work with no UI.
        if (!isConfigured(context)) importConfigFileIfPresent(context)
        if (!isConfigured(context)) return
        val req = PeriodicWorkRequestBuilder<UploadWorker>(3, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)

        // ALSO fire once now. A PeriodicWorkRequest does not run on
        // registration — WorkManager places the first execution somewhere
        // inside the first interval, at its own discretion, so "I set it up"
        // and "it has ever run" can be hours apart with nothing to look at.
        // Opening the app should sync; this makes that true.
        //
        // REPLACE, not KEEP: a stale queued one-shot from a previous launch is
        // worthless next to a fresh one, and the server upserts anyway.
        wm.enqueueUniqueWork(
            WORK_ONCE,
            androidx.work.ExistingWorkPolicy.REPLACE,
            androidx.work.OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build(),
        )
    }

    /** Fire now. Worth wiring to a debug button so a failure is diagnosable
     *  without waiting three hours. */
    suspend fun uploadNow(context: Context): Result<String> = runCatching {
        if (!isConfigured(context)) importConfigFileIfPresent(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val base = prefs.getString("base_url", null) ?: return@runCatching "not configured"
        val token = prefs.getString("token", null) ?: return@runCatching "not configured"
        // Send everything when the server has nothing, and when the server has
        // started reading the payload differently than it did last time we
        // synced — an incremental window cannot repair rows it never revisits.
        val full = prefs.getInt("sync_epoch", 0) < SYNC_EPOCH || !serverHasData(base, token)
        val lookback = if (full) BACKFILL_DAYS else LOOKBACK_DAYS
        val payload = buildPayload(context, lookback) ?: return@runCatching "nothing to send"
        val result = post("$base/api/ingest/whoop", token, payload)
        // Only after the post returns: post() throws on any non-2xx, so
        // recording the epoch before this line would mark a failed backfill
        // as done and there would be no second attempt.
        if (full) prefs.edit().putInt("sync_epoch", SYNC_EPOCH).apply()
        result
    }

    /**
     * Read the last [LOOKBACK_DAYS] of dailyMetric + sleepSession straight out
     * of Room, as-is.
     *
     * Uses the EXISTING two-bounded queries with an open upper bound rather
     * than adding `...Since` methods, specifically so `WhoopDao.kt` — a shared
     * upstream file — stays untouched and rebases cleanly.
     */
    /** Does the dashboard already hold rows for this source?
     *
     * Failing OPEN (returning true) on any error is deliberate: if the status
     * check is unreachable we fall back to the small incremental window rather
     * than blindly shipping a decade of rows over a phone connection. */
    private fun serverHasData(base: String, token: String): Boolean = try {
        val conn = (URL("$base/api/ingest/whoop/status").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 90_000
            setRequestProperty("X-Ingest-Token", token)
        }
        val body = try {
            if (conn.responseCode in 200..299)
                conn.inputStream.bufferedReader().use { it.readText() } else ""
        } finally { conn.disconnect() }
        // {"ok":true,"whoop":null} when empty; {"whoop":{"days":N,...}} when not.
        val whoop = JSONObject(body).optJSONObject("whoop")
        (whoop?.optInt("days") ?: 0) > 0
    } catch (t: Throwable) {
        Log.w(TAG, "status check failed; assuming the server has data", t)
        true
    }

    private suspend fun buildPayload(context: Context, lookbackDays: Long): String? {
        val dao = WhoopDatabase.get(context).whoopDao()
        val deviceId = dao.activeDeviceId() ?: DEFAULT_DEVICE_ID

        val fromDay = LocalDate.now().minusDays(lookbackDays).toString()
        // "9999-12-31" as the upper bound: `day` is a "YYYY-MM-DD" TEXT column
        // compared lexicographically, so this is an open-ended range.
        val metrics = dao.dailyMetricsRange(deviceId, fromDay, "9999-12-31")
        if (metrics.isEmpty()) return null

        val fromTs = System.currentTimeMillis() / 1000 - lookbackDays * 86_400L
        // startTs is unix SECONDS, not millis — passing millis here silently
        // returns zero rows rather than failing.
        val sessions = dao.sleepSessions(deviceId, fromTs, Long.MAX_VALUE, SLEEP_ROW_LIMIT)

        val mArr = JSONArray()
        for (m in metrics) {
            mArr.put(JSONObject().apply {
                put("day", m.day)
                putOrNull("totalSleepMin", m.totalSleepMin)
                putOrNull("efficiency", m.efficiency)
                putOrNull("deepMin", m.deepMin)
                putOrNull("remMin", m.remMin)
                putOrNull("lightMin", m.lightMin)
                putOrNull("disturbances", m.disturbances)
                putOrNull("restingHr", m.restingHr)
                putOrNull("avgHrv", m.avgHrv)
                putOrNull("recovery", m.recovery)
                putOrNull("strain", m.strain)
                putOrNull("respRateBpm", m.respRateBpm)
                putOrNull("spo2Pct", m.spo2Pct)
                putOrNull("skinTempDevC", m.skinTempDevC)
                putOrNull("steps", m.steps)
                putOrNull("activeKcalEst", m.activeKcalEst)
            })
        }

        val sArr = JSONArray()
        for (s in sessions) {
            sArr.put(JSONObject().apply {
                put("startTs", s.startTs)
                put("endTs", s.endTs)
                // A bedtime the user corrected by hand. The server prefers it
                // over the detected startTs — sent raw, not resolved here,
                // because effectiveStartTs is a computed Kotlin val and the
                // server already knows the precedence rule.
                putOrNull("startTsAdjusted", s.startTsAdjusted)
                putOrNull("efficiency", s.efficiency)
            })
        }

        return JSONObject().apply {
            put("metrics", mArr)
            put("sessions", sArr)
            put("source", "whoop")
        }.toString()
    }

    /** JSONObject.put(key, null) stores the STRING "null"; this stores a real null. */
    private fun JSONObject.putOrNull(key: String, value: Any?) {
        if (value == null) put(key, JSONObject.NULL) else put(key, value)
    }

    private fun post(url: String, token: String, body: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            // Generous: the dashboard runs on a free host that can take ~50 s
            // to wake from a cold start.
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Ingest-Token", token)
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("HTTP $code: ${text.take(300)}")
            return text
        } finally {
            conn.disconnect()
        }
    }

    class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            return uploadNow(applicationContext).fold(
                onSuccess = { Log.i(TAG, "uploaded: $it"); Result.success() },
                // retry(), not failure(): a sleeping free-tier host or a phone
                // on a captive portal is transient, and WorkManager's backoff
                // is exactly the right response.
                onFailure = { Log.w(TAG, "upload failed, will retry", it); Result.retry() },
            )
        }
    }
}
