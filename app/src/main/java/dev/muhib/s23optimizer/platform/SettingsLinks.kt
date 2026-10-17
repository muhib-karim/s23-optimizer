package dev.muhib.s23optimizer.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

data class SettingsLink(val title: String, val howTo: String, val intent: Intent?, val generic: Boolean)

object SettingsLinks {
    @Suppress("DEPRECATION")
    private fun resolve(context: Context, intent: Intent): Boolean = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.exported == true
    private fun link(context: Context, title: String, how: String, intents: List<Intent>): SettingsLink {
        val chosen = intents.firstOrNull { resolve(context, it) }
        return SettingsLink(title, how, chosen ?: Intent(Settings.ACTION_SETTINGS).takeIf { resolve(context, it) }, chosen == null)
    }
    fun all(context: Context): List<SettingsLink> = listOf(
        link(context, "Adaptive battery", "Settings → Battery → Background usage limits / More battery settings → Adaptive battery. Availability and wording vary with One UI.", listOf(Intent("android.settings.ADAPTIVE_BATTERY_SETTINGS"))),
        link(context, "Protect battery", "Settings → Battery → Battery protection. Older One UI offers an 85% cap; One UI 6.1/7 may offer Maximum at 80% or a selectable cap. Choose the option this phone exposes. A cap trades daily runtime for lower charging stress.", listOf(Intent("com.samsung.android.sm.ACTION_BATTERY_PROTECTION"))),
        link(context, "Light performance profile", "Settings → Device care → Performance profile → Light (or Battery → More battery settings). Trades peak performance for lower power use; some workloads and games are exempt.", listOf(Intent("com.samsung.android.sm.ACTION_PERFORMANCE_PROFILE"))),
        link(context, "Motion smoothness", "Settings → Display → Motion smoothness. Adaptive permits up to 120 Hz; Standard uses 60 Hz with less fluid motion and potentially lower display power. Actual savings depend on content.", listOf(Intent("com.samsung.settings.MotionSmoothness"), Intent(Settings.ACTION_DISPLAY_SETTINGS))),
        link(context, "Sleeping / deep sleeping apps", "Settings → Battery → Background usage limits → Sleeping apps / Deep sleeping apps. Deep sleep can delay notifications and background work; review each app.", listOf(Intent("com.samsung.android.sm.ACTION_SLEEPING_APPS"))),
        link(context, "Battery usage", "Settings → Battery → View details / Battery usage. Compare similar days and charging patterns.", listOf(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))),
        link(context, "Developer options", "Settings → About phone → Software information → tap Build number seven times. Then Settings → Developer options → Wireless debugging.", listOf(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))),
        link(context, "Usage Access", "Settings → Apps → Special access → Usage data access → S23 Optimizer. Enables foreground-time evidence; it does not expose exact per-app energy.", listOf(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}")), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))),
        link(context, "Battery optimisation", "Settings → Apps → Special access → Optimise battery usage. Review exceptions deliberately; unrestricted apps may consume more battery.", listOf(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))),
        link(context, "Storage management", "Settings → Device care → Storage. Review files yourself. This app never erases app data or clears all caches.", listOf(Intent("android.os.storage.action.MANAGE_STORAGE"), Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)))
    )
    fun appDetails(context: Context, pkg: String): SettingsLink = link(context, "App info", "Open Settings → Apps → select this app.", listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))))
    fun launch(context: Context, link: SettingsLink): String? {
        val intent = link.intent ?: return "No Settings activity is available. ${link.howTo}"
        return runCatching { context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); null }
            .getOrElse {
                val fallback = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (resolve(context, fallback)) runCatching { context.startActivity(fallback) }
                "Shortcut could not open. ${link.howTo}"
            }
    }
}
