package dev.muhib.s23optimizer.core.agent

import kotlinx.serialization.json.*

data class MeasuredDelta(val key: String, val label: String, val unit: String, val before: Double?, val after: Double?, val delta: Double?, val note: String)

object AgentMeasurements {
    fun compare(before: AgentSnapshot, after: AgentSnapshot): List<MeasuredDelta> {
        val first = before.tools["battery_overview"] as? JsonObject ?: JsonObject(emptyMap())
        val next = after.tools["battery_overview"] as? JsonObject ?: JsonObject(emptyMap())
        val sameWindow = first["startClock"] is JsonPrimitive && first["startClock"] != JsonNull && first["startClock"] == next["startClock"]
        return listOf(
            Triple("levelPct", "Battery level", "%"), Triple("temperatureC", "Battery temperature", "°C"),
            Triple("screenOnDrainMah", "Screen-on estimated discharge", "mAh"), Triple("screenOffDrainMah", "Screen-off estimated discharge", "mAh"),
            Triple("onBatteryMs", "Time on battery", "ms")
        ).map { (key, label, unit) ->
            val a = (first[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
            val b = (next[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
            val cumulative = key in setOf("screenOnDrainMah", "screenOffDrainMah", "onBatteryMs")
            val note = when {
                after.takenAt <= before.takenAt -> "A later measurement is required."
                a == null || b == null -> "Unavailable in one or both measurements."
                cumulative && !sameWindow -> "Charge window changed or is unknown; counters are not comparable."
                cumulative && b < a -> "Counter reset or changed; no delta calculated."
                cumulative -> "Additional accumulation between observations, not a saving."
                else -> "Observed difference; workload and charging may differ."
            }
            val delta = if (a != null && b != null && after.takenAt > before.takenAt && (!cumulative || (sameWindow && b >= a))) b - a else null
            MeasuredDelta(key, label, unit, a, b, delta, note)
        }
    }
}
