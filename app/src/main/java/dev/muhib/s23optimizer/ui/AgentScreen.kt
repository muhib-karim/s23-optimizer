package dev.muhib.s23optimizer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.muhib.s23optimizer.core.agent.*
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AgentScreen(vm: OptimizerViewModel, openHistory: () -> Unit) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val access by vm.access.collectAsStateWithLifecycle()
    val analysis by vm.agentAnalysis.collectAsStateWithLifecycle()
    val progress by vm.agentProgress.collectAsStateWithLifecycle()
    val before by vm.agentBefore.collectAsStateWithLifecycle()
    val after by vm.agentAfter.collectAsStateWithLifecycle()
    val decisions by vm.agentDecisions.collectAsStateWithLifecycle()
    val hasKey by vm.agentHasKey.collectAsStateWithLifecycle()
    var settings by rememberSaveable { mutableStateOf(!hasKey) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Agent", style = MaterialTheme.typography.headlineMedium)
        Text("Understand battery activity, then choose each change.")
        Text(access.message, style = MaterialTheme.typography.bodySmall)
        Text("Analyze sends a bounded summary of battery readings, package names and process activity to your configured model provider. Model advice can be wrong; every action needs your review. No automatic changes.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { settings = !settings }, enabled = !busy) { Text(if (settings) "Hide model settings" else "Model settings") }
        if (settings) AgentModelSettings(vm)
        Button(onClick = vm::analyzeAgent, enabled = !busy && access.ready && hasKey, modifier = Modifier.fillMaxWidth()) { Text("Analyze") }
        if (!hasKey) Text("Save your provider and API key to enable analysis.")
        if (progress.isNotBlank()) Text(progress)
        analysis?.let { result ->
            result.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (result.error == null) {
                Text("Findings", style = MaterialTheme.typography.titleLarge)
                if (result.findings.isEmpty()) Text("The model returned no findings.")
                result.findings.forEach { Text(it) }
                Text("Proposed actions", style = MaterialTheme.typography.titleLarge)
                Text("Existing idle-evidence and protected-app checks still apply. An ineligible proposal will be blocked before confirmation.", style = MaterialTheme.typography.bodySmall)
                if (result.proposals.isEmpty()) Text("No changes proposed. Keep observing under comparable conditions.")
                result.proposals.forEachIndexed { index, proposal ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val action = proposal.action
                            Text(when (action) {
                                is AgentAction.Standby -> "Move to ${action.bucket.wire} standby"
                                is AgentAction.RestrictBackground -> "Restrict background activity"
                                is AgentAction.ForceStop -> "Force-stop this app"
                            }, style = MaterialTheme.typography.titleMedium)
                            Text(action.pkg)
                            Text(proposal.reason)
                            Text(if (action.revertible) "May delay notifications, syncing and scheduled work. Prior setting is saved for Undo."
                                else "Cannot undo. Stops current work and may prevent notifications until you reopen the app.", style = MaterialTheme.typography.bodySmall)
                            decisions[index]?.let { Text(it) } ?: Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = { vm.prepareAgent(index) }, enabled = !busy && access.ready) { Text("Apply") }
                                OutlinedButton(onClick = { vm.skipAgent(index) }, enabled = !busy) { Text("Skip") }
                            }
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = openHistory, modifier = Modifier.fillMaxWidth()) { Text("History / Undo") }
        before?.let { baseline ->
            Text("Before: ${DateFormat.getDateTimeInstance().format(Date(baseline.takenAt))}", style = MaterialTheme.typography.bodySmall)
            Text("Re-measure later under comparable use. These are observations, not proof of a battery-life gain.")
            Button(onClick = vm::remeasureAgent, enabled = !busy && access.ready, modifier = Modifier.fillMaxWidth()) { Text("Re-measure") }
            after?.let { measured ->
                Text("After: ${DateFormat.getDateTimeInstance().format(Date(measured.takenAt))}", style = MaterialTheme.typography.bodySmall)
                AgentMeasurements.compare(baseline, measured).forEach { delta ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(delta.label, style = MaterialTheme.typography.titleMedium)
                            fun number(value: Double?) = value?.let { String.format(Locale.getDefault(), "%.2f", it) } ?: "unavailable"
                            Text("${number(delta.before)} → ${number(delta.after)} ${delta.unit}")
                            Text("Delta: ${delta.delta?.let { String.format(Locale.getDefault(), "%+.2f", it) } ?: "not compared"} ${delta.unit}")
                            Text(delta.note, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AgentModelSettings(vm: OptimizerViewModel) {
    val saved by vm.agentConfig.collectAsStateWithLifecycle()
    val hasKey by vm.agentHasKey.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var provider by remember(saved) { mutableStateOf(saved.provider) }
    var model by remember(saved) { mutableStateOf(saved.model) }
    var baseUrl by remember(saved) { mutableStateOf(saved.baseUrl) }
    // Never save API key to a Bundle, saved-state handle, journal or snapshot.
    var apiKey by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { apiKey = "" } }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Model settings", style = MaterialTheme.typography.titleMedium)
            Provider.entries.forEach { choice ->
                Row {
                    RadioButton(selected = provider == choice, onClick = {
                        provider = choice; model = choice.defaultModel; baseUrl = choice.defaultUrl; apiKey = ""
                    }, enabled = !busy)
                    Text(if (choice == Provider.ANTHROPIC) "Anthropic" else "OpenAI-compatible", Modifier.padding(top = 12.dp))
                }
            }
            OutlinedTextField(model, { model = it }, label = { Text("Model ID") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("HTTPS base URL (including /v1)") }, enabled = !busy, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(apiKey, { apiKey = it }, label = { Text(if (hasKey) "API key (blank keeps saved key)" else "API key") }, enabled = !busy, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            Text("Keys are encrypted with Android Keystore and excluded from reports. Changing provider or endpoint requires entering a key again. Only Analyze contacts the model.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { vm.saveAgentSettings(ModelConfig(provider, model.trim(), baseUrl.trim()), apiKey.trim()); apiKey = "" }, enabled = !busy) { Text("Save model settings") }
            if (hasKey) TextButton(onClick = { apiKey = ""; vm.forgetAgentKey() }, enabled = !busy) { Text("Forget saved API key") }
        }
    }
}
