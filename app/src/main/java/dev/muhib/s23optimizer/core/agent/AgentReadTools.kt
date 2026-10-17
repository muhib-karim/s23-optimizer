package dev.muhib.s23optimizer.core.agent

import dev.muhib.s23optimizer.core.*
import dev.muhib.s23optimizer.core.power.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Fixed read argv only. The model never supplies shell text or command arguments. */
class AgentReadTools(private val shell: ShellRunner) {
    private suspend fun read(vararg args: String): String = try {
        shell.run(ShellCommand(args.toList())).let { result -> if (result.success && !result.stderr.contains("Exception", true)) result.stdout else "" }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { "" }

    suspend fun collect(apps: List<AppProfile>, progress: (String) -> Unit = {}): AgentSnapshot = withContext(Dispatchers.Default) {
        val takenAt = System.currentTimeMillis()
        val uidMap = apps.groupBy { it.uid }.mapValues { (_, values) -> values.map { it.packageName } }
        val users = apps.filter { !it.system && ToolRegistry.packagePattern.matches(it.packageName) }
        progress("Reading battery overview and since-charge statistics…")
        val battery = read("dumpsys", "battery")
        val stats = read("dumpsys", "batterystats", "--charged")
        val overview = PowerParsers.batteryOverview(battery, stats)
        val drainers = PowerParsers.topDrainers(stats, uidMap)
        progress("Reading standby buckets…")
        val buckets = PowerParsers.standbyBuckets(read("am", "get-standby-bucket", "--user", "0"))
        progress("Reading sampled background restrictions…")
        val ranked = drainers.flatMap { it.packages }.withIndex().associate { it.value to it.index }
        val sample = users.sortedWith(compareBy<AppProfile> { ranked[it.packageName] ?: Int.MAX_VALUE }.thenBy { it.packageName }).take(30)
        val restrictions = sample.mapIndexed { index, app ->
            progress("Reading background restrictions ${index + 1}/${sample.size}…")
            val mode = PowerParsers.backgroundRestriction(read("cmd", "appops", "get", "--user", "0", app.packageName, "RUN_ANY_IN_BACKGROUND"))
            buildJsonObject { put("pkg", app.packageName); put("mode", mode?.let(::JsonPrimitive) ?: JsonNull) }
        }
        progress("Reading three-hour process statistics…")
        val processes = PowerParsers.procstats(read("dumpsys", "procstats", "--hours", "3"))
        val tools = linkedMapOf<String, JsonElement>(
            "battery_overview" to buildJsonObject {
                put("levelPct", overview.levelPct); put("health", overview.health)
                put("temperatureC", overview.temperatureC); put("screenOnDrainMah", overview.screenOnDrainMah)
                put("screenOffDrainMah", overview.screenOffDrainMah); put("screenOnDischargePct", overview.screenOnDischargePct)
                put("screenOffDischargePct", overview.screenOffDischargePct); put("onBatteryMs", overview.onBatteryMs)
                put("startClock", overview.startClock)
                put("note", "Health is an OS flag. Drain is cumulative since charge; unknown fields are null.")
            },
            "top_drainers" to JsonArray(drainers.take(50).map { row -> buildJsonObject {
                put("uid", row.uid); put("packages", JsonArray(row.packages.map(::JsonPrimitive))); put("estimatedMah", row.estimatedMah)
            } }),
            "wakelocks" to JsonArray(PowerParsers.wakelocks(stats).take(50).map { row -> buildJsonObject {
                put("uid", row.uid); put("name", row.name); put("totalTimeMs", row.totalTimeMs); put("count", row.count)
            } }),
            "alarms_jobs" to JsonArray(PowerParsers.alarmsJobs(stats).take(50).map { row -> buildJsonObject {
                put("uid", row.uid); put("alarmCount", row.alarmCount); put("jobCount", row.jobCount)
            } }),
            "standby_buckets" to JsonArray(users.map { app -> buildJsonObject { put("pkg", app.packageName); put("bucket", buckets[app.packageName]) } }),
            "background_restrictions" to buildJsonObject {
                put("sampled", sample.size); put("totalUserApps", users.size); put("truncated", sample.size < users.size)
                put("apps", JsonArray(restrictions))
            },
            "procstats" to JsonArray(processes.take(50).map { row -> buildJsonObject {
                put("process", row.process); put("uid", row.uid); put("averagePssKb", row.averagePssKb); put("backgroundPssKb", row.backgroundPssKb)
            } })
        )
        AgentSnapshot(takenAt, tools)
    }
}
