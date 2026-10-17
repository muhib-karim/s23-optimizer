package dev.muhib.s23optimizer.core.power

data class BatteryOverview(
    val levelPct: Double? = null,
    val health: String? = null,
    val temperatureC: Double? = null,
    val screenOnDrainMah: Double? = null,
    val screenOffDrainMah: Double? = null,
    val screenOnDischargePct: Double? = null,
    val screenOffDischargePct: Double? = null,
    val onBatteryMs: Long? = null,
    val startClock: String? = null
)

/** Shared UIDs have one estimate and potentially several packages, never split energy. */
data class UidPower(val uid: Int, val packages: List<String>, val estimatedMah: Double)
data class PartialWakelock(val uid: Int, val name: String, val totalTimeMs: Long, val count: Long?)
data class AlarmJobCount(val uid: Int, val alarmCount: Long? = null, val jobCount: Long? = null)
data class ProcessSummary(val process: String, val uid: Int, val averagePssKb: Double?, val backgroundPssKb: Double?)
