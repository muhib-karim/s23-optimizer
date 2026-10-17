package dev.muhib.s23optimizer.platform

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Process
import android.provider.AlarmClock
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import dev.muhib.s23optimizer.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Inventory(val apps: List<AppProfile>, val usageGranted: Boolean, val warning: String?)

class AppInventory(private val context: Context) {
    private val pm get() = context.packageManager
    fun usageGranted(): Boolean = runCatching {
        context.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    suspend fun load(now: Long = System.currentTimeMillis()): Inventory = withContext(Dispatchers.IO) {
        val granted = usageGranted()
        var warning: String? = null
        val usage: Map<String, UsageStats> = if (granted) runCatching {
            context.getSystemService(UsageStatsManager::class.java).queryAndAggregateUsageStats(now - 30 * DAY_MS, now)
        }.getOrElse { warning = "UsageStats query failed; idle status is unknown."; emptyMap() } else emptyMap()
        val protected = mutableMapOf<String, MutableList<String>>()
        fun protect(pkg: String?, reason: String) { if (!pkg.isNullOrBlank()) protected.getOrPut(pkg) { mutableListOf() }.add(reason) }
        // A failed protection scan blocks every recommendation; absence is not assumed safe.
        val protectionScan = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_ALL).forEach { protect(it.activityInfo.packageName, "Home launcher") }
            pm.queryIntentActivities(Intent(AlarmClock.ACTION_SET_ALARM), PackageManager.MATCH_ALL).forEach { protect(it.activityInfo.packageName, "Alarm handler") }
            pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")), PackageManager.MATCH_ALL).forEach { protect(it.activityInfo.packageName, "Navigation handler") }
            pm.queryIntentServices(Intent(VpnService.SERVICE_INTERFACE), PackageManager.MATCH_ALL).forEach { protect(it.serviceInfo.packageName, "VPN service") }
            pm.queryIntentServices(Intent("android.view.InputMethod"), PackageManager.MATCH_ALL).forEach { protect(it.serviceInfo.packageName, "Input method") }
            pm.queryIntentServices(Intent("android.accessibilityservice.AccessibilityService"), PackageManager.MATCH_ALL).forEach { protect(it.serviceInfo.packageName, "Accessibility service") }
            protect(Telephony.Sms.getDefaultSmsPackage(context), "Default messaging")
            protect(context.getSystemService(TelecomManager::class.java).defaultDialerPackage, "Default dialer")
            context.getSystemService(DevicePolicyManager::class.java).activeAdmins.orEmpty().forEach { protect(it.packageName, "Device administrator") }
            listOf(Settings.Secure.ENABLED_INPUT_METHODS, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).forEach { key ->
                Settings.Secure.getString(context.contentResolver, key).orEmpty().split(':').forEach { component -> protect(component.substringBefore('/').substringBefore(';'), "Enabled input/accessibility component") }
            }
        }
        val startup: Set<String>? = runCatching {
            (pm.queryBroadcastReceivers(Intent(Intent.ACTION_BOOT_COMPLETED), PackageManager.MATCH_DISABLED_COMPONENTS) +
                pm.queryBroadcastReceivers(Intent(Intent.ACTION_PACKAGE_ADDED, Uri.parse("package:dev.muhib.s23optimizer")), PackageManager.MATCH_DISABLED_COMPONENTS))
                .map { it.activityInfo.packageName }.toSet()
        }.getOrNull()
        val packages = pm.getInstalledPackages(PackageManager.MATCH_DISABLED_COMPONENTS)
        val apps = packages.mapNotNull { pkg ->
            val app = pkg.applicationInfo ?: return@mapNotNull null
            val stats = usage[pkg.packageName]
            val reasons = protected[pkg.packageName].orEmpty().toMutableList()
            if (protectionScan.isFailure) reasons += "Protection scan incomplete; restrictions are blocked."
            if (app.category == ApplicationInfo.CATEGORY_MAPS) reasons += "Navigation category"
            if (Process.myUid() / 100_000 != 0) reasons += "Owner profile required"
            AppProfile(pkg.packageName, runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(pkg.packageName), app.uid,
                (app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0,
                app.enabled, pkg.firstInstallTime, stats?.lastTimeUsed?.takeIf { it > 0 },
                stats?.totalTimeInForeground, reasons, startupCandidate = startup?.contains(pkg.packageName))
        }.sortedBy { it.label.lowercase() }
        Inventory(apps, granted, warning)
    }
}
