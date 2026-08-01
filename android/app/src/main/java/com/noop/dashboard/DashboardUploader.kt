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
    private const val PREFS = "dashboard_uploader"

    /** How far back to re-send every run. Re-sending an overlapping window is
     *  intentional: the server upserts, and the strap's own store is only ~14
     *  days deep, so a gap that scrolls off it is gone for good. */
    private const val LOOKBACK_DAYS = 21L

    /** Room requires an explicit LIMIT on the sleep read. Three weeks of nights
     *  plus naps is nowhere near this. */
    private const val SLEEP_ROW_LIMIT = 500

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
        if (!isConfigured(context)) return
        val req = PeriodicWorkRequestBuilder<UploadWorker>(3, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Fire now. Worth wiring to a debug button so a failure is diagnosable
     *  without waiting three hours. */
    suspend fun uploadNow(context: Context): Result<String> = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val base = prefs.getString("base_url", null) ?: return@runCatching "not configured"
        val token = prefs.getString("token", null) ?: return@runCatching "not configured"
        val payload = buildPayload(context) ?: return@runCatching "nothing to send"
        post("$base/api/ingest/whoop", token, payload)
    }

    /**
     * Read the last [LOOKBACK_DAYS] of dailyMetric + sleepSession straight out
     * of Room, as-is.
     *
     * Uses the EXISTING two-bounded queries with an open upper bound rather
     * than adding `...Since` methods, specifically so `WhoopDao.kt` — a shared
     * upstream file — stays untouched and rebases cleanly.
     */
    private suspend fun buildPayload(context: Context): String? {
        val dao = WhoopDatabase.get(context).whoopDao()
        val deviceId = dao.activeDeviceId() ?: DEFAULT_DEVICE_ID

        val fromDay = LocalDate.now().minusDays(LOOKBACK_DAYS).toString()
        // "9999-12-31" as the upper bound: `day` is a "YYYY-MM-DD" TEXT column
        // compared lexicographically, so this is an open-ended range.
        val metrics = dao.dailyMetricsRange(deviceId, fromDay, "9999-12-31")
        if (metrics.isEmpty()) return null

        val fromTs = System.currentTimeMillis() / 1000 - LOOKBACK_DAYS * 86_400L
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
