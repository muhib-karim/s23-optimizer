package dev.muhib.s23optimizer.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.muhib.s23optimizer.core.PhaseStats
import java.util.Locale

@Composable
internal fun MeasuredScreen(vm: OptimizerViewModel, busy: Boolean, exportCsv: () -> Unit) {
    val panel by vm.measured.collectAsStateWithLifecycle()
    val sampling by vm.batterySamples.collectAsStateWithLifecycle()
    fun signed(value: Double) = String.format(Locale.getDefault(), "%+.2f", value)
    Page {
        Text("Before / after, measured on this phone", style = MaterialTheme.typography.headlineMedium)
        val (start, end) = panel.window
        Note(when {
            start == null -> "No tweak is applied yet, so every measurement counts as the baseline (Before)."
            end == null -> "Before ends at your first applied tweak (${date(start)}); After runs from then on. Windows that cross it are not counted."
            else -> "After runs from your first applied tweak (${date(start)}) to your restore (${date(end)}). Later samples count on neither side."
        })
        Panel("Battery drain") {
            Phase("Before", panel.before) { stats -> stats.drain?.let { "${number(it.pctPerHour)} %/h over ${number(it.hours)} h (${it.windows} window(s))" } }
            Phase("After", panel.after) { stats -> stats.drain?.let { "${number(it.pctPerHour)} %/h over ${number(it.hours)} h (${it.windows} window(s))" } }
            Text("Change: ${panel.drainDelta?.let { "${signed(it)} %/h" } ?: "not compared"}", style = MaterialTheme.typography.titleMedium)
            Note("Counter: Android's battery level (ACTION_BATTERY_CHANGED) stored with each audit or battery sample. Only discharging pairs 5 min to 12 h apart with no level rise count, and at least 1 h is needed. Screen time, signal and temperature differ between days, so this is an observation, not proof that a tweak caused it.")
        }
        Panel("App start time") {
            Phase("Before", panel.before) { stats -> stats.startMedianMs?.let { "$it ms median of ${stats.starts} cold start(s)" } }
            Phase("After", panel.after) { stats -> stats.startMedianMs?.let { "$it ms median of ${stats.starts} cold start(s)" } }
            Text("Change: ${panel.startDeltaMs?.let { "%+d ms".format(it) } ?: "not compared"}", style = MaterialTheme.typography.titleMedium)
            Note("Counter: this app's own cold start, from process start to the first drawn frame (last 50 launches). A responsiveness proxy for the phone, not a benchmark of other apps.")
        }
        Panel("Collect samples") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = sampling, onCheckedChange = vm::setBatterySamples)
                Text("Hourly battery samples", Modifier.padding(start = 12.dp).weight(1f))
            }
            Note("Off by default. WorkManager stores the battery level about once an hour while this is on. It reads no shell and changes nothing.")
            OutlinedButton(onClick = vm::sampleBattery, enabled = !busy) { Text("Take a battery sample now") }
        }
        Button(onClick = exportCsv, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Export CSV") }
        Note("The CSV holds the summary rows plus every raw battery sample and cold start, each labelled before or after.")
    }
}

@Composable
private fun Phase(label: String, stats: PhaseStats, value: (PhaseStats) -> String?) {
    Text("$label: ${value(stats) ?: "not enough data"}")
}
