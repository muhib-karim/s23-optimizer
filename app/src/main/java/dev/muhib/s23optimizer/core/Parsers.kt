package dev.muhib.s23optimizer.core

object ShellParsers {
    private val durationPart = Regex("(\\d+)(ms|d|h|m|s)")
    fun durationMs(text: String): Long? {
        val normalized = text.replace(" ", "").trim()
        if (normalized.isEmpty()) return null
        val matches = durationPart.findAll(normalized).toList()
        if (matches.joinToString("") { it.value } != normalized) return null
        return runCatching { matches.fold(0L) { total, m ->
            val factor = when (m.groupValues[2]) { "d" -> DAY_MS; "h" -> 3_600_000L; "m" -> 60_000L; "s" -> 1000L; else -> 1L }
            Math.addExact(total, Math.multiplyExact(m.groupValues[1].toLong(), factor))
        } }.getOrNull()
    }
    fun appOp(text: String): String? {
        if (text.trim() == "No operations.") return "default"
        return Regex("(?m)^\\s*RUN_ANY_IN_BACKGROUND:\\s*(allow|ignore|deny|default|foreground)(?:;.*)?\\s*$")
            .find(text)?.groupValues?.get(1)
    }
    fun bucket(text: String): String? {
        val trimmed = text.trim()
        val number = if (trimmed.matches(Regex("\\d+"))) trimmed else
            Regex("^[A-Za-z0-9_.]+:\\s*bucket=(\\d+)$").find(trimmed)?.groupValues?.get(1)
        return number?.takeIf { it.toIntOrNull() in setOf(5, 10, 20, 30, 40, 45, 50) }
    }
    fun enabled(text: String): String? {
        val line = text.lineSequence().firstOrNull { Regex("^\\s*User 0:").containsMatchIn(it) } ?: return null
        if (!Regex("\\binstalled=true\\b").containsMatchIn(line) || Regex("\\b(hidden|suspended)=true\\b").containsMatchIn(line)) return null
        return Regex("\\benabled=([0-4])\\b").find(line)?.groupValues?.get(1)
    }
    fun animation(text: String): String? {
        val value = text.trim()
        if (value == "null") return value
        return value.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..20.0 }?.let { canonical(it) }
    }
    fun canonical(value: Double): String = if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}

data class BatteryApp(val estimatedMah: Double? = null, val partialWakeMs: Long? = null, val wakeupAlarms: Long? = null)
data class BatteryStats(val apps: Map<Int, BatteryApp>, val window: String, val warnings: List<String>)

object BatteryStatsParser {
    private val uidToken = "(?:u\\d+a\\d+|u\\d+s\\d+|\\d+)"
    private fun uid(text: String): Int? {
        text.toIntOrNull()?.let { return it }
        val m = Regex("u(\\d+)([as])(\\d+)").matchEntire(text) ?: return null
        val user = m.groupValues[1].toLongOrNull() ?: return null
        val app = m.groupValues[3].toLongOrNull() ?: return null
        val result = user * 100_000L + app + if (m.groupValues[2] == "a") 10_000L else 0L
        return result.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
    }
    fun parse(text: String): BatteryStats {
        if (text.contains("Permission Denial", true) || text.isBlank()) return BatteryStats(emptyMap(), "unavailable", listOf("Battery statistics unavailable or permission denied."))
        val apps = mutableMapOf<Int, BatteryApp>()
        val warnings = mutableListOf<String>()
        var inPower = false
        var current: Int? = null
        val estimate = Regex("^\\s*Uid ($uidToken): ([0-9]+(?:\\.[0-9]+)?)(?:\\s.*)?$")
        val header = Regex("^\\s*($uidToken):\\s*$")
        text.lineSequence().forEach { line ->
            if (line.trim() == "Estimated power use (mAh):") { inPower = true; current = null }
            val e = if (inPower) estimate.matchEntire(line) else null
            if (e != null) {
                val id = uid(e.groupValues[1])
                val power = e.groupValues[2].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                if (id != null && power != null) apps[id] = (apps[id] ?: BatteryApp()).copy(estimatedMah = power)
            }
            val h = header.matchEntire(line)
            if (h != null) { current = uid(h.groupValues[1]); inPower = false }
            val id = current
            if (id != null) {
                val wake = Regex("^\\s*Wake lock .+: (.+?) partial(?:\\s.*)?$").matchEntire(line)
                val duration = wake?.let { ShellParsers.durationMs(it.groupValues[1]) }
                if (duration != null) {
                    val old = apps[id] ?: BatteryApp()
                    apps[id] = old.copy(partialWakeMs = (old.partialWakeMs ?: 0) + duration)
                }
                val alarms = Regex("^\\s*Wakeup alarm .+: (\\d+) times\\s*$").matchEntire(line)?.groupValues?.get(1)?.toLongOrNull()
                if (alarms != null) {
                    val old = apps[id] ?: BatteryApp()
                    apps[id] = old.copy(wakeupAlarms = (old.wakeupAlarms ?: 0) + alarms)
                }
            }
        }
        if (apps.isEmpty()) warnings += "No recognised per-UID measurements; this vendor format is unavailable."
        if (apps.values.none { it.estimatedMah != null }) warnings += "No readable estimated per-UID drain. Foreground time is a separate fallback, not energy."
        warnings += "UID estimates are aggregate since last charge, may be shared by packages, and are not measured per-app energy. Missing fields remain unavailable."
        val window = if (text.contains("Statistics since last charge:")) "Aggregate since last charge" else "Aggregation window unavailable"
        return BatteryStats(apps, window, warnings)
    }
}
