package dev.muhib.s23optimizer.core

data class MetricDiff(val id: String, val label: String, val before: Metric?, val after: Metric?, val delta: Double?, val reason: String)
data class Comparison(val metrics: List<MetricDiff>, val commonScoreCriteria: List<String>, val beforeScore: Score, val afterScore: Score, val caveat: String)

object ReportDiff {
    fun compare(before: AuditSnapshot, after: AuditSnapshot): Comparison {
        val left = before.metrics.associateBy { it.id }; val right = after.metrics.associateBy { it.id }
        val metrics = (left.keys + right.keys).map { id ->
            val a = left[id]; val b = right[id]
            val reason = when {
                after.takenAt <= before.takenAt -> "AFTER must be later than BEFORE."
                a?.value == null || b?.value == null -> "Unavailable in one or both snapshots."
                !a.value.isFinite() || !b.value.isFinite() -> "Invalid measurement."
                a.source != b.source || a.unit != b.unit -> "Measurement source or unit changed."
                a.source == "unavailable" -> "Source unavailable."
                a.cumulative || b.cumulative -> "Cumulative counters have different observation windows; no gain inferred."
                else -> "Measured difference; does not establish causation."
            }
            val delta = if (reason.startsWith("Measured")) b!!.value!! - a!!.value!! else null
            MetricDiff(id, a?.label ?: b!!.label, a, b, delta, reason)
        }
        val scoreIds = Scoring.calculate(before.metrics).criteria.map { it.id }.toSet()
        val common = metrics.filter { it.delta != null && it.id in scoreIds &&
            Scoring.calculate(listOfNotNull(it.before)).total != null && Scoring.calculate(listOfNotNull(it.after)).total != null }.map { it.id }
        return Comparison(metrics, common, Scoring.calculate(before.metrics.filter { it.id in common }), Scoring.calculate(after.metrics.filter { it.id in common }),
            "Differences describe these two observations, not causation or proven battery-life gains. Match charge state, workload and ambient temperature. Per-UID estimates and foreground time cover different cumulative windows.")
    }
}
