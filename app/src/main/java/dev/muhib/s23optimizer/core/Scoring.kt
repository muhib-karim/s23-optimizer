package dev.muhib.s23optimizer.core

import kotlin.math.roundToInt

data class ScoreCriterion(val id: String, val label: String, val weight: Int, val raw: Double?, val unit: String, val source: String, val points: Double?, val formula: String)
data class Score(val total: Int?, val measuredWeight: Int, val criteria: List<ScoreCriterion>) {
    val excluded: List<ScoreCriterion> get() = criteria.filter { it.points == null }
}

object Scoring {
    private data class Rule(val id: String, val label: String, val weight: Int, val unit: String, val formula: String, val range: ClosedFloatingPointRange<Double>, val evaluate: (Double) -> Double)
    private val rules = listOf(
        Rule("storage.free_pct", "Free storage", 35, "%", "100 at ≥15% free; 0 at ≤2%; linear between.", 0.0..100.0) { (it - 2) / 13 * 100 },
        Rule("battery.temp_c", "Battery temperature", 30, "°C", "100 between 10–35°C; linear to 0 at −10°C or 50°C.", -50.0..100.0) { if (it < 10) (it + 10) / 20 * 100 else (50 - it) / 15 * 100 },
        Rule("thermal.status", "Thermal status", 35, "status", "100 at NONE (0); 75 LIGHT; 50 MODERATE; 25 SEVERE; 0 at CRITICAL or higher.", 0.0..6.0) { 100 - it * 25 }
    )
    fun calculate(metrics: List<Metric>): Score {
        val criteria = rules.map { rule ->
            val metric = metrics.firstOrNull { it.id == rule.id }
            val raw = metric?.value?.takeIf { it.isFinite() && it in rule.range && metric.source != "unavailable" }
            ScoreCriterion(rule.id, rule.label, rule.weight, raw, rule.unit, metric?.source ?: "unavailable", raw?.let { rule.evaluate(it).coerceIn(0.0, 100.0) }, rule.formula)
        }
        val weight = criteria.filter { it.points != null }.sumOf { it.weight }
        val total = if (weight == 0) null else (criteria.sumOf { (it.points ?: 0.0) * it.weight } / weight).roundToInt()
        return Score(total, weight, criteria)
    }
}
