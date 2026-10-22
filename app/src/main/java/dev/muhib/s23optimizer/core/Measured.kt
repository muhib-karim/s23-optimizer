package dev.muhib.s23optimizer.core

import java.util.Locale

/** Battery level at one moment, from ACTION_BATTERY_CHANGED as stored in a snapshot. */
data class BatterySample(val takenAt: Long, val levelPct: Double, val discharging: Boolean) {
    companion object {
        fun from(snapshot: AuditSnapshot): BatterySample? {
            val level = snapshot.metrics.firstOrNull { it.id == "battery.level_pct" }?.value?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: return null
            // "Not charging" means plugged in but held; only an explicit Discharging counts as drain.
            val state = snapshot.metrics.firstOrNull { it.id == "battery.charging" }?.display
            return BatterySample(snapshot.takenAt, level, state == "Discharging")
        }
    }
}

/** One cold start of this app: process start to first drawn frame. */
data class StartSample(val at: Long, val millis: Long)

data class DrainRate(val pctPerHour: Double, val hours: Double, val windows: Int)

object DrainMeter {
    const val MIN_GAP_MS = 5 * 60_000L
    const val MAX_GAP_MS = 12 * 3_600_000L
    const val MIN_TOTAL_MS = 3_600_000L

    /**
     * Sums level drops over consecutive discharging pairs. A pair is skipped when either end is not
     * discharging, the gap is outside 5 min–12 h, or the level rose (a charge happened in between).
     */
    fun rate(samples: List<BatterySample>, from: Long? = null, until: Long? = null): DrainRate? {
        val ordered = samples.filter { (from == null || it.takenAt >= from) && (until == null || it.takenAt < until) }.sortedBy { it.takenAt }
        var dropped = 0.0
        var millis = 0L
        var windows = 0
        ordered.zipWithNext { a, b ->
            val gap = b.takenAt - a.takenAt
            if (a.discharging && b.discharging && gap in MIN_GAP_MS..MAX_GAP_MS && b.levelPct <= a.levelPct) {
                dropped += a.levelPct - b.levelPct
                millis += gap
                windows++
            }
        }
        if (millis < MIN_TOTAL_MS) return null
        val hours = millis / 3_600_000.0
        return DrainRate(dropped / hours, hours, windows)
    }
}

data class PhaseStats(val drain: DrainRate?, val startMedianMs: Long?, val starts: Int)

/** After = [start, end): from the first change that took effect until the last restore, or open while any change is still in effect. */
data class MeasureWindow(val start: Long?, val end: Long?)

data class MeasuredPanel(val window: MeasureWindow, val before: PhaseStats, val after: PhaseStats) {
    val pivot: Long? get() = window.start
    val drainDelta: Double? get() = before.drain?.let { b -> after.drain?.let { it.pctPerHour - b.pctPerHour } }
    val startDeltaMs: Long? get() = before.startMedianMs?.let { b -> after.startMedianMs?.let { it - b } }
}

object MeasuredValue {
    private val tookEffect = setOf(JournalStatus.APPLIED, JournalStatus.NEEDS_REVIEW, JournalStatus.UNDO_FAILED, JournalStatus.UNDONE)
    private val inEffect = setOf(JournalStatus.APPLIED, JournalStatus.NEEDS_REVIEW, JournalStatus.UNDO_FAILED)

    /** Failed and never-run rows are ignored; a full restore closes the window instead of erasing it. */
    fun window(journal: List<JournalEntry>): MeasureWindow {
        val start = journal.filter { it.status in tookEffect }.minOfOrNull { it.createdAt } ?: return MeasureWindow(null, null)
        val end = if (journal.any { it.status in inEffect }) null else journal.filter { it.status == JournalStatus.UNDONE }.mapNotNull { it.completedAt }.maxOrNull()
        return MeasureWindow(start, end)
    }

    fun build(samples: List<BatterySample>, starts: List<StartSample>, window: MeasureWindow): MeasuredPanel {
        val pivot = window.start
        fun phase(from: Long?, until: Long?): PhaseStats {
            val times = starts.filter { (from == null || it.at >= from) && (until == null || it.at < until) }.map { it.millis }
            return PhaseStats(DrainMeter.rate(samples, from, until), median(times), times.size)
        }
        val after = if (pivot == null) PhaseStats(null, null, 0) else phase(pivot, window.end)
        return MeasuredPanel(window, phase(null, pivot), after)
    }

    fun csv(panel: MeasuredPanel, samples: List<BatterySample>, starts: List<StartSample>): String = buildString {
        val (start, end) = panel.window
        fun phaseOf(time: Long) = when {
            end != null && time >= end -> "restored"
            start != null && time >= start -> "after"
            else -> "before"
        }
        fun number(value: Double) = String.format(Locale.ROOT, "%.2f", value)
        appendLine("kind,phase,time_ms,value,unit,detail")
        for ((name, stats) in listOf("before" to panel.before, "after" to panel.after)) {
            val drain = stats.drain?.let { "${number(it.pctPerHour)},%/h,discharge over ${String.format(Locale.ROOT, "%.1f", it.hours)} h in ${it.windows} window(s)" }
            val start = stats.startMedianMs?.let { "$it,ms,median cold start of ${stats.starts} launch(es)" }
            appendLine("summary,$name,,${drain ?: ",%/h,not enough data"}")
            appendLine("summary,$name,,${start ?: ",ms,no cold starts recorded"}")
        }
        samples.sortedBy { it.takenAt }.forEach { appendLine("battery,${phaseOf(it.takenAt)},${it.takenAt},${number(it.levelPct)},%,${if (it.discharging) "discharging" else "not discharging"}") }
        starts.sortedBy { it.at }.forEach { appendLine("start,${phaseOf(it.at)},${it.at},${it.millis},ms,cold start") }
    }.trimEnd()

    private fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}

/** Cold starts persisted as "at,millis;at,millis" (newest last), small enough for SharedPreferences. */
object StartLog {
    const val MAX = 50
    private const val MAX_PLAUSIBLE_MS = 60_000L
    fun decode(text: String): List<StartSample> = text.split(';').mapNotNull { part ->
        val fields = part.split(',')
        val at = fields.getOrNull(0)?.toLongOrNull()
        val millis = fields.getOrNull(1)?.toLongOrNull()
        if (fields.size == 2 && at != null && millis != null && millis in 1..MAX_PLAUSIBLE_MS) StartSample(at, millis) else null
    }
    fun append(text: String, sample: StartSample): String = (decode(text) + sample).takeLast(MAX).joinToString(";") { "${it.at},${it.millis}" }
}
