package dev.muhib.s23optimizer.core.power

import dev.muhib.s23optimizer.core.ShellParsers

/** Parsers accept incomplete vendor dumps. Missing, denied and malformed data stay unknown. */
object PowerParsers {
    private const val UID = "(?:u\\d+[as]\\d+|\\d+)"
    private const val NUMBER = "(?:\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)"
    private val uidHeader = Regex("^\\s*($UID):\\s*$")
    private fun readable(text: String) = text.isNotBlank() && !Regex("Permission Denial|SecurityException|^Error:", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)).containsMatchIn(text)
    private fun number(value: String?) = value?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    private fun count(value: String?) = value?.toLongOrNull()?.takeIf { it >= 0 }
    private fun sum(a: Long?, b: Long): Long? = runCatching { Math.addExact(a ?: 0, b) }.getOrNull()

    fun uid(token: String): Int? {
        token.toIntOrNull()?.let { return it.takeIf { value -> value >= 0 } }
        val match = Regex("u(\\d+)([as])(\\d+)").matchEntire(token) ?: return null
        val user = match.groupValues[1].toLongOrNull() ?: return null
        val app = match.groupValues[3].toLongOrNull() ?: return null
        if (user > Int.MAX_VALUE / 100_000 || app > 99_999) return null
        val result = user * 100_000 + app + if (match.groupValues[2] == "a") 10_000 else 0
        return result.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    fun batteryOverview(battery: String, stats: String): BatteryOverview {
        fun field(key: String): Double? = if (readable(battery)) number(Regex("(?m)^\\s*${Regex.escape(key)}:\\s*([+-]?[\\d.]+)\\s*$").find(battery)?.groupValues?.get(1)) else null
        fun drain(key: String): Double? = if (readable(stats)) number(Regex("(?mi)^\\s*${Regex.escape(key)}:\\s*($NUMBER)(?: mAh)?\\s*$").find(stats)?.groupValues?.get(1)) else null
        val level = field("level"); val scale = field("scale")
        val temp = if (readable(battery)) Regex("(?m)^\\s*temperature:\\s*(-?\\d+)\\s*$").find(battery)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in -500..1000 }?.div(10.0) else null
        val duration = if (readable(stats)) Regex("(?m)^\\s*Time on battery:\\s*([^\\(]+)").find(stats)?.groupValues?.get(1)?.trim()?.let(ShellParsers::durationMs) else null
        return BatteryOverview(
            levelPct = if (level != null && scale != null && scale > 0 && level <= scale) level * 100 / scale else null,
            health = mapOf(2.0 to "good", 3.0 to "overheat", 4.0 to "dead", 5.0 to "over voltage", 6.0 to "failure", 7.0 to "cold")[field("health")],
            temperatureC = temp,
            screenOnDrainMah = drain("Discharge screen on"), screenOffDrainMah = drain("Discharge screen off"),
            screenOnDischargePct = drain("Amount discharged while screen on"), screenOffDischargePct = drain("Amount discharged while screen off"),
            onBatteryMs = duration,
            startClock = if (readable(stats)) Regex("(?m)^\\s*Start clock time:\\s*([\\d-]+(?:[ T][\\d:.]+)?)\\s*$").find(stats)?.groupValues?.get(1) else null
        )
    }

    fun topDrainers(text: String, packages: Map<Int, List<String>>): List<UidPower> {
        if (!readable(text)) return emptyList()
        val result = linkedMapOf<Int, UidPower>()
        var inPower = false
        var indent = 0
        val estimate = Regex("^\\s*Uid ($UID):\\s*($NUMBER)(?:\\s.*)?$", RegexOption.IGNORE_CASE)
        text.lineSequence().forEach { line ->
            if (line.trim().equals("Estimated power use (mAh):", true)) { inPower = true; indent = line.takeWhile(Char::isWhitespace).length }
            else if (inPower) {
                if (line.isNotBlank() && line.takeWhile(Char::isWhitespace).length <= indent) inPower = false
                if (inPower) estimate.matchEntire(line)?.let { match ->
                    val id = uid(match.groupValues[1]); val mah = number(match.groupValues[2])
                    if (id != null && mah != null) result[id] = UidPower(id, packages[id].orEmpty().distinct(), mah)
                }
            }
        }
        return result.values.sortedByDescending { it.estimatedMah }
    }

    private fun uidLines(text: String, consume: (Int, String) -> Unit) {
        if (!readable(text)) return
        var current: Int? = null; var indent = 0
        text.lineSequence().forEach { line ->
            val header = uidHeader.matchEntire(line)
            if (header != null) { current = uid(header.groupValues[1]); indent = line.takeWhile(Char::isWhitespace).length }
            else {
                if (line.isNotBlank() && line.takeWhile(Char::isWhitespace).length <= indent) current = null
                current?.let { consume(it, line.trim()) }
            }
        }
    }

    fun wakelocks(text: String): List<PartialWakelock> {
        val result = mutableListOf<PartialWakelock>()
        val lock = Regex("^Wake lock (.+): (.+?) partial(?:\\s.*)?$")
        uidLines(text) { id, line ->
            lock.matchEntire(line)?.let { match ->
                ShellParsers.durationMs(match.groupValues[2])?.let { duration ->
                    val times = count(Regex("\\((\\d+) times\\)").find(line)?.groupValues?.get(1))
                    result += PartialWakelock(id, match.groupValues[1], duration, times)
                }
            }
        }
        return result.sortedByDescending { it.totalTimeMs }
    }

    fun alarmsJobs(text: String): List<AlarmJobCount> {
        val result = linkedMapOf<Int, AlarmJobCount>()
        uidLines(text) { id, line ->
            val alarms = count(Regex("^Wakeup alarm .+: (\\d+) times\\s*$").matchEntire(line)?.groupValues?.get(1))
            val jobs = if (line.startsWith("Job ")) count(Regex("\\((\\d+) times\\)").find(line)?.groupValues?.get(1)) else null
            if (alarms != null || jobs != null) {
                val old = result[id] ?: AlarmJobCount(id)
                result[id] = old.copy(alarmCount = if (alarms == null) old.alarmCount else sum(old.alarmCount, alarms), jobCount = if (jobs == null) old.jobCount else sum(old.jobCount, jobs))
            }
        }
        return result.values.sortedByDescending { (it.alarmCount ?: 0).toDouble() + (it.jobCount ?: 0) }
    }

    fun standbyBucket(text: String): Int? {
        if (!readable(text)) return null
        val match = Regex("^(?:[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+:\\s*)?(?:bucket=)?([a-zA-Z_]+|\\d+)$").matchEntire(text.trim()) ?: return null
        val value = match.groupValues[1]
        return mapOf("active" to 10, "working_set" to 20, "frequent" to 30, "rare" to 40, "restricted" to 45, "never" to 50)[value.lowercase()]
            ?: value.toIntOrNull()?.takeIf { it in setOf(5, 10, 20, 30, 40, 45, 50) }
    }
    fun standbyBuckets(text: String): Map<String, Int> {
        if (!readable(text)) return emptyMap()
        return text.lineSequence().mapNotNull { line ->
            Regex("^\\s*([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+):\\s*(.+?)\\s*$").matchEntire(line)?.let { m ->
                standbyBucket(m.groupValues[2])?.let { m.groupValues[1] to it }
            }
        }.toMap()
    }
    fun backgroundRestriction(text: String): String? {
        if (!readable(text)) return null
        if (text.trim() == "No operations.") return "default"
        fun mode(prefix: String) = Regex("(?m)^\\s*${prefix}RUN_ANY_IN_BACKGROUND:\\s*(allow|ignore|deny|default|foreground)(?:;.*)?\\s*$").find(text)?.groupValues?.get(1)
        return mode("") ?: mode("Uid mode:\\s*")
    }

    fun procstats(text: String): List<ProcessSummary> {
        if (!readable(text)) return emptyList()
        val rows = mutableListOf<ProcessSummary>()
        var current: ProcessSummary? = null
        fun finish() { current?.takeIf { it.averagePssKb != null || it.backgroundPssKb != null }?.let(rows::add); current = null }
        val header = Regex("^\\s*\\*\\s+([^\\s/]+)\\s*/\\s*($UID)\\s*/.*$")
        val pss = Regex("\\(\\s*($NUMBER)(KB|MB|GB|K|M|G)\\s*-\\s*($NUMBER)(KB|MB|GB|K|M|G)\\s*-", RegexOption.IGNORE_CASE)
        text.lineSequence().forEach { line ->
            val match = header.matchEntire(line)
            if (match != null) { finish(); uid(match.groupValues[2])?.let { current = ProcessSummary(match.groupValues[1], it, null, null) } }
            else if (line.isNotBlank() && !line.first().isWhitespace()) finish()
            else current?.let { row ->
                val memory = pss.find(line)
                val value = memory?.let { number(it.groupValues[3])?.times(when (it.groupValues[4].uppercase().first()) { 'G' -> 1048576; 'M' -> 1024; else -> 1 }) }?.takeIf { it.isFinite() }
                if (value != null) {
                    if (line.trim().startsWith("TOTAL:", true)) current = row.copy(averagePssKb = value)
                    else if (Regex("^\\s*(Bg|Background|Service|Service-Restarting|Receiver|Cached)(?:[ :].*)?$", RegexOption.IGNORE_CASE).matches(line)) current = row.copy(backgroundPssKb = maxOf(row.backgroundPssKb ?: 0.0, value))
                }
            }
        }
        finish()
        return rows.sortedByDescending { it.backgroundPssKb ?: it.averagePssKb ?: 0.0 }
    }
}
