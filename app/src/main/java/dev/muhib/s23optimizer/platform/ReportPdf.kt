package dev.muhib.s23optimizer.platform

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import dev.muhib.s23optimizer.core.*
import java.io.OutputStream
import java.text.DateFormat
import java.util.Date
import java.util.Locale

object ReportPdf {
    fun write(before: AuditSnapshot, after: AuditSnapshot, output: OutputStream) {
        require(after.takenAt > before.takenAt) { "AFTER must be later than BEFORE." }
        val comparison = ReportDiff.compare(before, after)
        PdfDocument().use { document ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 10f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL) }
            var number = 0
            var page: PdfDocument.Page? = null
            var y = 0f
            fun nextPage() {
                page?.let { document.finishPage(it) }
                number++
                page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, number).create())
                y = 42f
                page!!.canvas.drawText("S23 Optimizer • measured report • page $number", 36f, y, paint)
                y += 26f
            }
            fun line(text: String) {
                // Break at glyph boundaries if a package/path is wider than the page.
                var remaining = text.replace('\n', ' ')
                if (remaining.isEmpty()) { y += 8; return }
                while (remaining.isNotEmpty()) {
                    if (page == null || y > 800) nextPage()
                    val count = paint.breakText(remaining, true, 523f, null).coerceAtLeast(1)
                    val end = if (count < remaining.length) remaining.lastIndexOf(' ', count).takeIf { it > count / 2 } ?: count else count
                    page!!.canvas.drawText(remaining.substring(0, end), 36f, y, paint)
                    y += 15f
                    remaining = remaining.substring(end).trimStart()
                }
            }
            fun time(value: Long) = DateFormat.getDateTimeInstance().format(Date(value))
            fun score(s: Score) = "${s.total?.toString() ?: "unavailable"}/100; measured weight ${s.measuredWeight}/100"
            fun value(m: Metric?): String = if (m == null) "unavailable" else "${m.display ?: m.value?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "unavailable"} ${m.unit} [${m.source}]"
            line("BEFORE: ${time(before.takenAt)} (snapshot ${before.id}, ${before.role})")
            line("AFTER: ${time(after.takenAt)} (snapshot ${after.id}, ${after.role})")
            line("Elapsed: ${String.format(Locale.ROOT, "%.1f", (after.takenAt - before.takenAt) / 3_600_000.0)} hours")
            line(comparison.caveat)
            line("Condition score, not a speed benchmark or battery-life estimate. Common criteria: ${comparison.commonScoreCriteria.joinToString().ifEmpty { "none" }}")
            line("Comparable score BEFORE: ${score(comparison.beforeScore)}; AFTER: ${score(comparison.afterScore)}")
            for ((label, snapshot) in listOf("BEFORE" to before, "AFTER" to after)) {
                line("")
                line("$label independent score: ${score(Scoring.calculate(snapshot.metrics))}")
                Scoring.calculate(snapshot.metrics).criteria.forEach { criterion ->
                    line("${criterion.label}: raw ${criterion.raw ?: "unavailable"} ${criterion.unit}; weight ${criterion.weight}; points ${criterion.points?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "excluded"}; source ${criterion.source}. ${criterion.formula}")
                }
                snapshot.notes.forEach { line("$label note: $it") }
            }
            line("")
            line("Metric differences")
            comparison.metrics.forEach { metric ->
                line("${metric.label} (${metric.id})")
                line("BEFORE ${value(metric.before)}; AFTER ${value(metric.after)}")
                line("Delta ${metric.delta?.let { String.format(Locale.ROOT, "%+.2f", it) } ?: "not compared"} ${metric.after?.unit.orEmpty()}. ${metric.reason}")
                listOfNotNull(metric.before?.note, metric.after?.note).filter { it.isNotBlank() }.distinct().forEach(::line)
            }
            for ((label, snapshot) in listOf("BEFORE" to before, "AFTER" to after)) {
                line("")
                line("$label installed app evidence (shared UIDs may repeat estimates; never sum them)")
                snapshot.apps.forEach { app ->
                    line("${app.label} (${app.packageName}, UID ${app.uid}): ${if (app.system) "system" else "user"}, enabled ${app.enabled} [PackageManager]")
                    line("Estimated drain ${app.estimatedMah ?: "unavailable"} mAh; partial wake ${app.partialWakeMs ?: "unavailable"} ms; alarms ${app.wakeupAlarms ?: "unavailable"} [${app.drainSource}]")
                    line("Foreground ${app.foregroundMs ?: "unavailable"} ms; last use ${app.lastUsedMs?.let(::time) ?: "unavailable"} [UsageStatsManager, requested 30 days]")
                    line("Bucket ${app.standbyBucket ?: "unavailable"} [${app.standbySource}]; startup receiver candidate ${app.startupCandidate ?: "unavailable"} [PackageManager receiver query, heuristic only]")
                    if (app.protectedReasons.isNotEmpty()) line("Protection: ${app.protectedReasons.joinToString()}")
                }
            }
            page?.let { document.finishPage(it) }
            document.writeTo(output)
        }
    }
}
