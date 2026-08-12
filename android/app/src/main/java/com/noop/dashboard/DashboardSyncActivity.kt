package com.noop.dashboard

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * A door another app on this phone can knock on to say "upload now".
 *
 * ## Why this exists
 *
 * The dashboard's "I just worked out" button was honest but useless. Effort is
 * the number that moves most during a session, and the uploader runs on a
 * 3-hour period — so a tap right after training almost always read the strap's
 * PRE-workout figure and could do nothing about it except tell you to go open
 * NOOP yourself. Correct, and not what anyone wants at the end of a workout.
 *
 * Nothing on a web page can reach into another Android app. But the two apps
 * are on the same phone, and Android already has the mechanism: a deep link.
 * The dashboard's WebView hands any non-http scheme to the OS, so a
 * `noopwg://sync` navigation lands here.
 *
 * ## Why an Activity rather than a BroadcastReceiver
 *
 * Background-start restrictions. From Android 10 a process without a visible
 * component generally cannot start work on a whim, and background broadcasts
 * are heavily throttled — the exact conditions under which this would appear to
 * work in testing and silently do nothing in real use months later. Being
 * launched as an Activity is the case the platform explicitly allows.
 *
 * It shows no UI and finishes immediately, so the visible effect is a flicker
 * and a return to wherever you were.
 *
 * ## What it is NOT
 *
 * Not a general remote-control surface. It takes no parameters and can do
 * exactly one thing: send data this phone already holds to the endpoint this
 * phone is already configured for. The worst a hostile caller achieves is a
 * redundant upload of your own data to your own server.
 *
 * NOTE: this is fork-only, like the rest of com.noop.dashboard, and must never
 * be proposed upstream — NOOP is deliberately offline (no server, no account,
 * no sync), and the uploader this triggers exists in violation of that scope.
 */
class DashboardSyncActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // schedule(), not uploadNow(). This Activity finishes microseconds from
        // now and leaves no visible component behind, so the process becomes a
        // candidate for death immediately — and a raw coroutine doing network
        // I/O would go with it. That failure is nasty precisely because it is
        // load-dependent: it works every time on a developer's phone with the
        // screen on, and drops uploads on a real one that is cold and busy.
        //
        // schedule() enqueues a WorkManager one-shot (REPLACE) alongside the
        // existing 3-hour periodic. WorkManager owns the execution guarantee,
        // survives process death, and already waits for a network — which is
        // the whole reason the uploader was built on it in the first place.
        runCatching { DashboardUploader.schedule(applicationContext) }
            .onFailure { Log.w(TAG, "sync-on-demand could not be enqueued", it) }
            .onSuccess { Log.i(TAG, "sync-on-demand enqueued") }

        // No UI, no transition. The caller is the dashboard app and it wants
        // to stay in front — the user tapped a button there, not here.
        finish()
        overridePendingTransition(0, 0)
    }

    private companion object {
        const val TAG = "DashboardSync"
    }
}
