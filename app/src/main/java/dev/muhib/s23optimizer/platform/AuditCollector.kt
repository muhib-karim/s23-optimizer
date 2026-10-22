package dev.muhib.s23optimizer.platform

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import dev.muhib.s23optimizer.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class AuditCollector(private val context: Context, private val access: ShizukuAccess, private val shell: ShellRunner, val inventory: AppInventory) {
    private val mutex = Mutex()
    private var lastHeadroomRead: Long? = null

    // One linear pass of independent, null-safe reads; splitting it would scatter the snapshot schema.
    @Suppress("CyclomaticComplexMethod")
    suspend fun collect(role: String): AuditSnapshot = mutex.withLock { withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val metrics = mutableListOf<Metric>()
        val notes = mutableListOf(
            "${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}).",
            "Snapshot collection is sequential, not instantaneous. Captured values may change during the audit.",
            "No benchmark, measured battery-life gain, guaranteed per-app energy, or per-app private-storage measurement is claimed."
        )
        fun add(id: String, label: String, value: Double?, unit: String, source: String, note: String = "", display: String? = null, cumulative: Boolean = false) {
            metrics += metric(id, label, value, unit, source, note, display, cumulative)
        }
        metrics += batteryMetrics()

        val storage = runCatching { StatFs(context.filesDir.absolutePath) }.getOrNull()
        val total = storage?.totalBytes?.takeIf { it > 0 }
        val free = storage?.availableBytes
        add("storage.total_gib", "Data storage total", total?.div(1073741824.0), "GiB", "StatFs (app data volume)")
        add("storage.free_gib", "Available storage", free?.div(1073741824.0), "GiB", "StatFs (app data volume)")
        add("storage.used_gib", "Used / reserved storage", if (total != null && free != null) (total - free) / 1073741824.0 else null, "GiB", "StatFs (app data volume)", "Total minus bytes available to apps; includes reserved space.")
        add("storage.free_pct", "Free storage", if (total != null && free != null) free * 100.0 / total else null, "%", "StatFs (app data volume)")
        add("storage.per_app", "Private per-app storage", null, "MiB", "unavailable", "Not measured; shell access to private app data is not assumed.")
        val memory = runCatching { ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) } }.getOrNull()
        add("ram.total_mib", "Total RAM", memory?.totalMem?.div(1048576.0), "MiB", "ActivityManager.MemoryInfo")
        add("ram.available_mib", "Available RAM", memory?.availMem?.div(1048576.0), "MiB", "ActivityManager.MemoryInfo", "Cached RAM is useful. Free RAM is not scored and no RAM cleaning is performed.")
        add("ram.low", "Low-memory signal", memory?.let { if (it.lowMemory) 1.0 else 0.0 }, "boolean", "ActivityManager.MemoryInfo")
        val power = context.getSystemService(PowerManager::class.java)
        val thermal = runCatching { power.currentThermalStatus.toDouble() }.getOrNull()
        add("thermal.status", "Thermal status", thermal, "status", "PowerManager.getCurrentThermalStatus", "0 none, 1 light, 2 moderate, 3 severe, 4 critical, 5 emergency, 6 shutdown.")
        val elapsed = SystemClock.elapsedRealtime()
        val headroom = if (Build.VERSION.SDK_INT >= 30 && (lastHeadroomRead == null || elapsed - lastHeadroomRead!! >= 10_000)) {
            lastHeadroomRead = elapsed
            runCatching { power.getThermalHeadroom(0).toDouble().takeIf { it.isFinite() && it >= 0 } }.getOrNull()
        } else null
        add("thermal.headroom", "Thermal headroom", headroom, "ratio", "PowerManager.getThermalHeadroom(0)", "1.0 corresponds to severe throttling. NaN, unsupported sensors or reads within 10 seconds are unavailable. This is not spare performance.")
        val display = runCatching { context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) }.getOrNull()
        add("display.refresh_hz", "Current display refresh rate", display?.refreshRate?.toDouble(), "Hz", "Display.getRefreshRate", "Point-in-time display mode; adaptive refresh can vary.")
        val modes = display?.supportedModes?.map { it.refreshRate }?.distinct()?.sorted()
        add("display.supported_hz", "Supported refresh rates", modes?.maxOrNull()?.toDouble(), "Hz max", "Display.getSupportedModes", display = modes?.joinToString { "${ShellParsers.canonical(it.toDouble())} Hz" })
        for (key in Commands.animationKeys) {
            val read = runCatching { Settings.Global.getString(context.contentResolver, key) }.getOrNull()
            val value = read?.let { ShellParsers.animation(it) }?.toDoubleOrNull()
            add("animation.$key", key.replace('_', ' '), value, "×", "Settings.Global", "A faster animation changes transition duration, not CPU speed.")
        }
        access.refresh()
        val shellReady = access.state.value.ready
        notes += access.state.value.message
        val cpuDirs = runCatching { File("/sys/devices/system/cpu").listFiles()?.filter { it.name.matches(Regex("cpu\\d+")) }?.sortedBy { it.name.removePrefix("cpu").toInt() } }.getOrNull()
        if (cpuDirs.isNullOrEmpty()) add("cpu.frequencies", "CPU frequencies", null, "kHz", "unavailable", "CPU frequency paths are not readable.")
        cpuDirs?.take(32)?.forEach { cpu ->
            val path = "${cpu.absolutePath}/cpufreq/scaling_cur_freq"
            val local = runCatching { File(path).readText().trim().toLongOrNull()?.takeIf { it > 0 } }.getOrNull()
            val remote = if (local == null && shellReady) shell.run(ShellCommand(listOf("cat", path))).let { if (it.success) it.stdout.trim().toLongOrNull()?.takeIf { n -> n > 0 } else null } else null
            add("cpu.${cpu.name}.khz", "${cpu.name} current frequency", (local ?: remote)?.toDouble(), "kHz", if (local != null) "sysfs $path" else "Shizuku cat $path", "Instantaneous scaling sample; an offline core or denied read is unavailable.")
        }

        val inv = inventory.load(started)
        inv.warning?.let(notes::add)
        if (!inv.usageGranted) notes += "Usage Access is off. Foreground time and idle eligibility are unavailable."
        add("apps.user", "User-installed apps", inv.apps.count { !it.system }.toDouble(), "apps", "PackageManager.getInstalledPackages")
        add("apps.system", "System / updated-system apps", inv.apps.count { it.system }.toDouble(), "apps", "PackageManager.getInstalledPackages")
        add("apps.disabled", "Disabled apps", inv.apps.count { !it.enabled }.toDouble(), "apps", "PackageManager.ApplicationInfo.enabled")
        val ownBucket = runCatching { context.getSystemService(UsageStatsManager::class.java).appStandbyBucket }.getOrNull()
        add("standby.self", "Optimizer standby bucket", ownBucket?.toDouble(), "bucket", "UsageStatsManager.getAppStandbyBucket", "Public API reports only this app's bucket.")
        val buckets = if (shellReady) {
            val output = shell.run(ShellCommand(listOf("am", "get-standby-bucket", "--user", "0")))
            if (output.success) output.stdout.lineSequence().mapNotNull { line ->
                val match = Regex("^([A-Za-z][A-Za-z0-9_.]+):\\s*(?:bucket=)?(\\d+)\\s*$").matchEntire(line.trim())
                match?.let { m -> ShellParsers.bucket(m.groupValues[2])?.toInt()?.let { m.groupValues[1] to it } }
            }.toMap() else emptyMap()
        } else emptyMap()
        val stats = if (shellReady) shell.run(ShellCommand(listOf("dumpsys", "batterystats", "--charged"))).let { result ->
            if (result.success) BatteryStatsParser.parse(result.stdout) else BatteryStats(emptyMap(), "unavailable", listOf("batterystats unavailable: ${result.stderr.take(300)}"))
        } else BatteryStats(emptyMap(), "unavailable", listOf("Per-UID drain, wakelocks and alarms require readable Shizuku batterystats."))
        notes += stats.window
        notes += stats.warnings
        if (stats.apps.values.none { it.estimatedMah != null }) add("batterystats.energy", "Per-UID estimated drain", null, "mAh", "unavailable", "Foreground time is shown separately when Usage Access is granted.", cumulative = true)
        if (stats.apps.values.none { it.partialWakeMs != null }) add("batterystats.wakelocks", "Partial wakelocks", null, "ms", "unavailable", "No recognised readable wakelock data.", cumulative = true)
        if (stats.apps.values.none { it.wakeupAlarms != null }) add("batterystats.alarms", "Wakeup alarms", null, "alarms", "unavailable", "No recognised readable alarm data.", cumulative = true)
        stats.apps.forEach { (uid, app) ->
            val source = "Shizuku dumpsys batterystats --charged / UID $uid"
            add("uid.$uid.mah", "UID $uid estimated drain", app.estimatedMah, "mAh", source, "${stats.window}; packages can share this UID.", cumulative = true)
            add("uid.$uid.wake_ms", "UID $uid partial wakelocks", app.partialWakeMs?.toDouble(), "ms", source, stats.window, cumulative = true)
            add("uid.$uid.alarms", "UID $uid wakeup alarms", app.wakeupAlarms?.toDouble(), "alarms", source, stats.window, cumulative = true)
        }
        val apps = inv.apps.map { app ->
            val stat = stats.apps[app.uid]
            val bucket = buckets[app.packageName] ?: if (app.packageName == context.packageName) ownBucket else null
            app.copy(estimatedMah = stat?.estimatedMah, partialWakeMs = stat?.partialWakeMs, wakeupAlarms = stat?.wakeupAlarms, standbyBucket = bucket,
                standbySource = when { buckets.containsKey(app.packageName) -> "Shizuku am get-standby-bucket"; bucket != null -> "UsageStatsManager.getAppStandbyBucket"; else -> "unavailable" },
                drainSource = if (stat != null) "Shizuku dumpsys batterystats --charged / UID ${app.uid}" else "unavailable")
        }
        apps.forEach { app ->
            add("app.${app.packageName}.foreground_ms", "${app.label} foreground time (30-day query)", app.foregroundMs?.toDouble(), "ms", "UsageStatsManager.queryAndAggregateUsageStats", "Foreground time is not energy use. OS retention may be shorter than the requested window.", cumulative = true)
            add("app.${app.packageName}.bucket", "${app.label} standby bucket", app.standbyBucket?.toDouble(), "bucket", app.standbySource)
        }
        notes += "Collection finished ${System.currentTimeMillis() - started} ms after the snapshot timestamp."
        AuditSnapshot(takenAt = started, role = role, metrics = metrics, apps = apps, notes = notes)
    } }

    private fun metric(id: String, label: String, value: Double?, unit: String, source: String, note: String = "", display: String? = null, cumulative: Boolean = false): Metric {
        val safe = value?.takeIf { it.isFinite() }
        return Metric(id, label, safe, unit, if (safe == null && display == null) "unavailable" else source, note, cumulative, display)
    }

    private fun batteryMetrics(): List<Metric> {
        val metrics = mutableListOf<Metric>()
        fun add(id: String, label: String, value: Double?, unit: String, source: String, note: String = "", display: String? = null, cumulative: Boolean = false) {
            metrics += metric(id, label, value, unit, source, note, display, cumulative)
        }
        val battery = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val bm = context.getSystemService(BatteryManager::class.java)
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.takeIf { it >= 0 }
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1)?.takeIf { it > 0 }
        val percent = if (level != null && scale != null) level * 100.0 / scale else runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }?.toDouble() }.getOrNull()
        add("battery.level_pct", "Battery level", percent, "%", if (level != null && scale != null) "ACTION_BATTERY_CHANGED" else "BatteryManager.BATTERY_PROPERTY_CAPACITY", "Charge level is not battery health and is excluded from scoring.")
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.takeIf { it in 1..5 }
        add("battery.charging", "Charging state", status?.takeIf { it != BatteryManager.BATTERY_STATUS_UNKNOWN }?.let { if (it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL) 1.0 else 0.0 }, "boolean", "ACTION_BATTERY_CHANGED", display = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"; BatteryManager.BATTERY_STATUS_FULL -> "Full"; BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"; BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"; else -> null
        })
        val health = battery?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)?.takeIf { it in 2..7 }
        add("battery.health", "Battery health flag", health?.toDouble(), "enum", "ACTION_BATTERY_CHANGED", "OS health flag, not remaining capacity percentage.", when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"; BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"; BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"; BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"; BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"; BatteryManager.BATTERY_HEALTH_COLD -> "Cold"; else -> null
        })
        val temperature = battery?.takeIf { it.hasExtra(BatteryManager.EXTRA_TEMPERATURE) }?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it in -500..1000 }?.div(10.0)
        add("battery.temp_c", "Battery temperature", temperature, "°C", "ACTION_BATTERY_CHANGED", "Changes with charging, workload and ambient temperature.")
        val cycles = if (Build.VERSION.SDK_INT >= 34) battery?.takeIf { it.hasExtra(BatteryManager.EXTRA_CYCLE_COUNT) }?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)?.takeIf { it >= 0 }?.toDouble() else null
        add("battery.cycles", "Battery cycle count", cycles, "cycles", "ACTION_BATTERY_CHANGED.EXTRA_CYCLE_COUNT", "Only where exposed by this OS and battery hardware.", cumulative = true)
        val chargeCounter = runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeIf { it > 0 }?.toDouble() }.getOrNull()
        add("battery.charge_uah", "Remaining charge counter", chargeCounter, "µAh", "BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER", "Hardware estimate; not design capacity or a health percentage.")
        return metrics
    }

    /** Battery-only snapshot for the drain panel (role SAMPLE); no shell, no app inventory. */
    suspend fun batterySample(): AuditSnapshot = withContext(Dispatchers.IO) {
        AuditSnapshot(takenAt = System.currentTimeMillis(), role = SAMPLE, metrics = batteryMetrics())
    }

    companion object { const val SAMPLE = "SAMPLE" }
}
