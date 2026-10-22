package dev.muhib.s23optimizer.platform

import android.app.Activity
import android.content.SharedPreferences
import android.os.Process
import android.os.SystemClock
import android.view.ViewTreeObserver
import dev.muhib.s23optimizer.core.StartLog
import dev.muhib.s23optimizer.core.StartSample

/** On-device counter for this app's cold start: process start to the first drawn frame. */
class StartupTimer(private val prefs: SharedPreferences) {
    fun samples(): List<StartSample> = StartLog.decode(prefs.getString(KEY, "").orEmpty())
    fun record(sample: StartSample) { prefs.edit().putString(KEY, StartLog.append(prefs.getString(KEY, "").orEmpty(), sample)).apply() }

    /** Call from the launcher activity's onCreate. Records at most once per process, and only for a launch-started process. */
    fun attach(activity: Activity, restored: Boolean) {
        if (restored || measured) return
        measured = true
        // A process started earlier for background work is not a cold launch.
        if (SystemClock.uptimeMillis() - Process.getStartUptimeMillis() > COLD_WINDOW_MS) return
        val decor = activity.window.decorView
        val listener = object : ViewTreeObserver.OnDrawListener {
            private var done = false
            override fun onDraw() {
                if (done) return
                done = true
                record(StartSample(System.currentTimeMillis(), SystemClock.uptimeMillis() - Process.getStartUptimeMillis()))
                // Listeners cannot be removed during dispatch.
                decor.post { decor.viewTreeObserver.removeOnDrawListener(this) }
            }
        }
        decor.viewTreeObserver.addOnDrawListener(listener)
    }

    companion object {
        private const val KEY = "cold_starts"
        private const val COLD_WINDOW_MS = 10_000L
        @Volatile private var measured = false
    }
}
