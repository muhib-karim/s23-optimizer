package dev.muhib.s23optimizer.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.muhib.s23optimizer.BuildConfig
import dev.muhib.s23optimizer.core.*
import dev.muhib.s23optimizer.platform.*
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private enum class Screen(val title: String) {
    ONBOARDING("Get started"), DASHBOARD("Dashboard"), AUDIT("Audit details"), OPTIMISE("Optimise"),
    SETTINGS("Samsung settings"), HISTORY("History / Restore"), REPORT("Before / After report"), ABOUT("About / limits"),
    MEASURED("Measured value")
}
internal fun date(ms: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
internal fun number(value: Double) = String.format(Locale.getDefault(), "%.2f", value)
private fun metricValue(metric: Metric?) = metric?.display ?: metric?.value?.let { "${number(it)} ${metric.unit}" } ?: "unavailable"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptimizerApp(vm: OptimizerViewModel, export: () -> Unit, exportCsv: () -> Unit = {}, initialScreen: String? = null) {
    var screenName by rememberSaveable {
        mutableStateOf(Screen.entries.firstOrNull { it.name == initialScreen }?.name
            ?: if (vm.graph.preferences.getBoolean("onboarded", false)) Screen.DASHBOARD.name else Screen.ONBOARDING.name)
    }
    val screen = Screen.valueOf(screenName)
    fun go(value: Screen) { screenName = value.name }
    val snapshots by vm.audits.collectAsStateWithLifecycle()
    val journal by vm.journal.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val access by vm.access.collectAsStateWithLifecycle()
    val plans by vm.review.collectAsStateWithLifecycle()
    BackHandler(enabled = screen != Screen.DASHBOARD || plans.isNotEmpty()) {
        if (plans.isNotEmpty() && !busy) vm.review.value = emptyList() else go(Screen.DASHBOARD)
    }
    Scaffold(
        topBar = { TopAppBar(title = { Column { Text("S23 Optimizer", style = MaterialTheme.typography.titleLarge); Text(screen.title, style = MaterialTheme.typography.labelMedium) } },
            actions = { if (screen != Screen.DASHBOARD) TextButton(onClick = { go(Screen.DASHBOARD) }) { Text("Home") } }) },
        bottomBar = {
            NavigationBar {
                listOf(Screen.DASHBOARD to "◉", Screen.OPTIMISE to "◇", Screen.MEASURED to "▤", Screen.HISTORY to "↶", Screen.SETTINGS to "⚙").forEach { (item, icon) ->
                    NavigationBarItem(selected = screen == item, onClick = { go(item) }, icon = { Text(icon) }, label = { Text(when (item) { Screen.SETTINGS -> "Settings"; Screen.HISTORY -> "History"; Screen.MEASURED -> "Measured"; else -> item.title }) })
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Working" })
            message?.let { text ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(text, style = MaterialTheme.typography.bodySmall)
                        if (!busy) TextButton(onClick = { vm.message.value = null }) { Text("Dismiss") }
                    }
                }
            }
            when (screen) {
                Screen.ONBOARDING -> Onboarding(vm, access) { vm.finishOnboarding(); go(Screen.DASHBOARD) }
                Screen.DASHBOARD -> Dashboard(vm, snapshots.firstOrNull(), busy, access, { go(Screen.AUDIT) }, { go(Screen.REPORT) }, { go(Screen.ONBOARDING) })
                Screen.AUDIT -> AuditDetails(vm, snapshots.firstOrNull())
                Screen.OPTIMISE -> Optimise(vm, snapshots.firstOrNull(), busy, access)
                Screen.SETTINGS -> SamsungSettings(vm) { go(Screen.ABOUT) }
                Screen.HISTORY -> History(vm, snapshots, journal, busy) { go(Screen.REPORT) }
                Screen.REPORT -> Report(vm, snapshots, busy, export)
                Screen.ABOUT -> About()
                Screen.MEASURED -> MeasuredScreen(vm, busy, exportCsv)
            }
        }
    }
    if (plans.isNotEmpty()) ReviewDialog(vm, plans, busy)
}

@Composable
internal fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}
@Composable
internal fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}
@Composable
internal fun Note(text: String) { Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable
internal fun Command(text: String) { SelectionContainer { Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }

@Composable
private fun Onboarding(vm: OptimizerViewModel, access: ShellAccess, done: () -> Unit) {
    Page {
        Text("Understand first. Change deliberately.", style = MaterialTheme.typography.headlineMedium)
        Text("Read-only diagnostics work immediately. Optional Shizuku access enables more measurements and reviewed, reversible shell changes. No root or PC is required.")
        Panel("1 · Pair on this phone") {
            Text("Install the official Shizuku app, enable Developer options, and turn on Wireless debugging while connected to Wi-Fi. In Shizuku choose Pairing. In Wireless debugging choose Pair device with pairing code and enter that code in Shizuku's pairing notification.")
            Note("Split screen can help keep both apps visible. Pairing does not grant this app permission yet.")
        }
        Panel("2 · Start Shizuku") { Text("Return to Shizuku and tap Start under Start via Wireless debugging. Wait until it reports running. Repeat Start after every reboot; wireless debugging may need enabling or pairing again.") }
        Text("3 · Grant and verify", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        AccessPanel(vm, access)
        Note("Shell mode and the owner profile are required. One UI may deny individual commands even when permission is granted. This app never kills apps, clears app data, or promises battery savings.")
        Button(onClick = done, modifier = Modifier.fillMaxWidth()) { Text("Continue to diagnostics") }
    }
}

@Composable
private fun Dashboard(vm: OptimizerViewModel, snapshot: AuditSnapshot?, busy: Boolean, access: ShellAccess, details: () -> Unit, report: () -> Unit, onboard: () -> Unit) {
    Page {
        Text("Your phone, measured", style = MaterialTheme.typography.headlineMedium)
        if (snapshot == null) {
            Text("Save a BEFORE audit to establish a baseline. Make only the changes you choose, then save an AFTER audit later—ideally after a comparable day of use.")
        } else {
            ScoreCard(Scoring.calculate(snapshot.metrics))
            Note("${snapshot.role} · ${date(snapshot.takenAt)} · stored snapshot, not a live reading")
            Panel("Key measurements") {
                listOf("battery.level_pct", "battery.temp_c", "thermal.status", "storage.free_gib", "ram.available_mib", "display.refresh_hz").forEach { id ->
                    snapshot.metrics.firstOrNull { it.id == id }?.let { m -> Text("${m.label}: ${metricValue(m)}"); Note("Source: ${m.source}") }
                }
            }
        }
        Button(onClick = { vm.audit("BEFORE") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Save BEFORE audit") }
        OutlinedButton(onClick = { vm.audit("AFTER") }, enabled = !busy && snapshot != null, modifier = Modifier.fillMaxWidth()) { Text("Save AFTER audit") }
        OutlinedButton(onClick = details, enabled = snapshot != null, modifier = Modifier.fillMaxWidth()) { Text("Audit details & app observations") }
        OutlinedButton(onClick = report, modifier = Modifier.fillMaxWidth()) { Text("Before / After report") }
        AccessPanel(vm, access)
        TextButton(onClick = onboard) { Text("Pairing walkthrough") }
    }
}

@Composable
private fun ScoreCard(score: Score) {
    Panel("Measured condition score") {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(180.dp).semantics { contentDescription = "Condition score ${score.total ?: "unavailable"} out of 100. Measured weight ${score.measuredWeight} out of 100." }, contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { (score.total ?: 0) / 100f }, modifier = Modifier.size(164.dp), strokeWidth = 11.dp, trackColor = MaterialTheme.colorScheme.surfaceVariant)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(score.total?.toString() ?: "—", style = MaterialTheme.typography.displayMedium)
                    Text("out of 100", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Text("Measured weight: ${score.measuredWeight}/100")
        Note("A transparent condition indicator, not a performance benchmark or predicted battery life. Measured weights are renormalised; unknown criteria are excluded.")
        score.criteria.forEach { c ->
            Text("${c.label} · weight ${c.weight} · ${c.points?.let { "${number(it)}/100" } ?: "excluded"}", style = MaterialTheme.typography.bodyMedium)
            Note("Raw: ${c.raw?.let(::number) ?: "unavailable"} ${c.unit} · Source: ${c.source}. ${c.formula}")
        }
    }
}

@Composable
private fun AuditDetails(vm: OptimizerViewModel, snapshot: AuditSnapshot?) {
    if (snapshot == null) { Page { Text("Save an audit from Dashboard first.") }; return }
    var search by rememberSaveable { mutableStateOf("") }
    var limit by rememberSaveable(snapshot.id) { mutableIntStateOf(20) }
    Page {
        Text("${snapshot.role} · ${date(snapshot.takenAt)}", style = MaterialTheme.typography.titleLarge)
        Panel("Collection notes") { snapshot.notes.forEach { Note(it) } }
        snapshot.metrics.filterNot { it.id.startsWith("app.") || it.id.startsWith("uid.") }.forEach { m ->
            Panel(m.label) { Text(metricValue(m)); Note("Source: ${m.source}"); if (m.note.isNotEmpty()) Note(m.note) }
        }
        Text("App observations", style = MaterialTheme.typography.headlineSmall)
        Note("Ordered by estimated UID drain where available, then foreground time. Estimates may repeat across packages sharing a UID. Foreground time is not energy. Missing data is never zero.")
        OutlinedTextField(value = search, onValueChange = { search = it; limit = 20 }, label = { Text("Search installed apps") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        val apps = snapshot.apps.filter { it.label.contains(search, true) || it.packageName.contains(search, true) }
            .sortedWith(compareByDescending<AppProfile> { it.estimatedMah ?: -1.0 }.thenByDescending { it.foregroundMs ?: -1 })
        apps.take(limit).forEach { app ->
            Panel(app.label) {
                SelectionContainer { Note("${app.packageName} · UID ${app.uid}") }
                Note("${if (app.system) "System" else "User-installed"} · ${if (app.enabled) "Enabled" else "Disabled"} · Source: PackageManager")
                Text("Estimated drain: ${app.estimatedMah?.let(::number) ?: "unavailable"} mAh")
                Text("Partial wakelocks: ${app.partialWakeMs?.toString() ?: "unavailable"} ms · Wakeup alarms: ${app.wakeupAlarms?.toString() ?: "unavailable"}")
                Note("Source: ${app.drainSource}; aggregate since last charge where readable.")
                Text("Foreground time: ${app.foregroundMs?.let { number(it / 60_000.0) } ?: "unavailable"} min")
                Note("Last used: ${app.lastUsedMs?.let(::date) ?: "unavailable"} · Source: UsageStatsManager (requested 30 days; actual retention varies)")
                Text("Standby bucket: ${app.standbyBucket ?: "unavailable"}"); Note("Source: ${app.standbySource}")
                Note("Startup receiver candidate: ${app.startupCandidate ?: "unavailable"} · Source: PackageManager BOOT_COMPLETED / PACKAGE_ADDED receiver query. Heuristic, not proof of startup activity.")
                if (app.protectedReasons.isNotEmpty()) Note("Protected: ${app.protectedReasons.joinToString()}")
                TextButton(onClick = { vm.open(SettingsLinks.appDetails(vm.graph.app, app.packageName)) }) { Text("Open app settings") }
            }
        }
        if (apps.size > limit) OutlinedButton(onClick = { limit += 40 }) { Text("Show more (${apps.size - limit} remaining)") }
    }
}

@Composable
private fun Optimise(vm: OptimizerViewModel, snapshot: AuditSnapshot?, busy: Boolean, access: ShellAccess) {
    var optIn by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var showBlocked by rememberSaveable { mutableStateOf(false) }
    var limit by rememberSaveable { mutableIntStateOf(20) }
    Page {
        Text("Review one change at a time", style = MaterialTheme.typography.headlineMedium)
        if (!access.ready) AccessPanel(vm, access, "Changes are disabled until Shizuku is ready. Nothing will run, and nothing is journaled.")
        Note("Eligibility requires known last use at least 14 days ago and installation at least 14 days ago. Core and sensitive apps are excluded. Eligibility is rechecked against current usage immediately before applying.")
        Panel("Shorter animations") {
            Text("Set the three standard animation scales to 0.5×. This shortens transitions; it does not make the CPU faster or prove a battery saving.")
            Button(onClick = vm::prepareAnimations, enabled = !busy && access.ready) { Text("Review animation changes") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = optIn, onCheckedChange = { optIn = it })
            Text("Opt in to reviewing documented Samsung / Meta packages", modifier = Modifier.weight(1f))
        }
        Note("Allowlisted system apps are an explicit exception to the user-installed rule. They still require idle evidence. No carrier packages are included until individually documented and reviewed.")
        OutlinedButton(onClick = { vm.audit("CHECK") }, enabled = !busy) { Text("Refresh recommendations with audit") }
        if (snapshot == null) Text("Run an audit to see app recommendations.")
        else {
            Note("Candidate list from ${date(snapshot.takenAt)}. Missing Usage Access or last-use evidence can leave this list empty.")
            OutlinedTextField(value = search, onValueChange = { search = it; limit = 20 }, label = { Text("Filter packages") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = showBlocked, onCheckedChange = { showBlocked = it; limit = 20 }); Text("Show exclusions", Modifier.weight(1f)) }
            val now = System.currentTimeMillis()
            val rows = snapshot.apps.filter { it.label.contains(search, true) || it.packageName.contains(search, true) }.filter { app ->
                showBlocked || Eligibility.check(app, ChangeKind.APP_OP, now).allowed || Eligibility.check(app, ChangeKind.DISABLE, now, optIn).allowed
            }
            if (rows.isEmpty()) Text("No verified eligible apps. Nothing will be restricted automatically. Review Samsung settings for manual options.")
            rows.take(limit).forEach { app ->
                val background = Eligibility.check(app, ChangeKind.APP_OP, now)
                val disable = Eligibility.check(app, ChangeKind.DISABLE, now, optIn)
                Panel(app.label) {
                    Note(app.packageName)
                    Note("Last used: ${app.lastUsedMs?.let(::date) ?: "unavailable"} · UsageStatsManager")
                    if (background.allowed) {
                        Text("Restricting background work may delay notifications, syncing, location and scheduled jobs.")
                        OutlinedButton(onClick = { vm.prepare(ChangeKind.APP_OP, app.packageName, "ignore") }, enabled = !busy && access.ready) { Text("Review background restriction") }
                        OutlinedButton(onClick = { vm.prepare(ChangeKind.STANDBY, app.packageName, "40") }, enabled = !busy && access.ready) { Text("Review rare bucket") }
                        OutlinedButton(onClick = { vm.prepare(ChangeKind.STANDBY, app.packageName, "45") }, enabled = !busy && access.ready) { Text("Review restricted bucket") }
                    } else Note("Background restriction blocked: ${background.reasons.joinToString()}")
                    if (app.packageName in BloatAllowlist.entries) {
                        Text(requireNotNull(BloatAllowlist.entries[app.packageName]))
                        OutlinedButton(onClick = { vm.prepare(ChangeKind.DISABLE, app.packageName, "3", optIn) }, enabled = !busy && access.ready && disable.allowed) { Text("Review disable package") }
                        if (!disable.allowed) Note(disable.reasons.joinToString())
                    }
                }
            }
            if (rows.size > limit) OutlinedButton(onClick = { limit += 40 }) { Text("Show more") }
        }
    }
}

@Composable
private fun ReviewDialog(vm: OptimizerViewModel, plans: List<ReviewedChange>, busy: Boolean) {
    var acknowledged by remember(plans) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) vm.review.value = emptyList() },
        title = { Text("Confirm exact changes") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Prior values are readable now and will be checked again. Each record is saved before its mutation; every result is verified. Partial failure may leave earlier changes applied; use History to undo.")
                plans.forEach { plan ->
                    Text(plan.target, fontWeight = FontWeight.SemiBold)
                    Text("Prior: ${plan.before} → Requested: ${plan.expected}")
                    Note("Read / verify:"); Command(Commands.read(plan.kind, plan.target).display)
                    Note("Apply:"); Command(plan.command.display)
                    Note("Undo:"); plan.undoCommands.forEach { Command(it.display) }
                    if (plan.kind == ChangeKind.DISABLE) Text(requireNotNull(BloatAllowlist.entries[plan.target]))
                }
                val clipboard = LocalClipboardManager.current
                Note("Dry run: these commands are listed, not executed. Nothing runs until you tap Apply.")
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(DryRun.render(DryRun.apply(plans)))) }) { Text("Copy dry run") }
                Text(if (plans.any { it.kind != ChangeKind.ANIMATION }) "Confirm this app is not needed for banking, navigation, alarms, timely notifications, accessibility, VPN, or a connected device. Package-name heuristics cannot identify every sensitive app." else "Shorter animations may feel abrupt. This change does not prove a speed or battery-life gain.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                    Text("I reviewed the effects and opt in to these commands.", Modifier.weight(1f))
                }
            }
        },
        confirmButton = { Button(onClick = vm::applyReviewed, enabled = acknowledged && !busy) { Text("Apply reviewed changes") } },
        dismissButton = { TextButton(onClick = { vm.review.value = emptyList() }, enabled = !busy) { Text("Cancel") } }
    )
}

@Composable
private fun SamsungSettings(vm: OptimizerViewModel, about: () -> Unit) {
    val context = LocalContext.current
    val links = remember(context) { SettingsLinks.all(context) }
    val daily by vm.daily.collectAsStateWithLifecycle()
    Page {
        Text("Samsung controls, explained", style = MaterialTheme.typography.headlineMedium)
        Note("Shortcuts are resolved before being offered. One UI varies; follow the manual path if only generic Settings opens. No undocumented refresh-rate keys are written.")
        links.forEach { link ->
            Panel(link.title) {
                Text(link.howTo)
                OutlinedButton(onClick = { vm.open(link) }, enabled = link.intent != null) { Text(if (link.generic) "Open generic Settings" else "Open settings shortcut") }
                if (link.intent == null) Note("No resolvable Settings activity; use the manual path above.")
            }
        }
        Panel("Optional daily diagnostics") {
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = daily, onCheckedChange = vm::setDaily); Text("Save a daily audit", Modifier.padding(start = 12.dp).weight(1f)) }
            Note("WorkManager runs approximately daily when the battery is not low. Diagnostics only; no automatic restrictions. If Shizuku is stopped, unavailable shell metrics are skipped. Android may defer work.")
        }
        TextButton(onClick = about) { Text("About, limits & privacy") }
    }
}

@Composable
private fun History(vm: OptimizerViewModel, snapshots: List<AuditSnapshot>, journal: List<JournalEntry>, busy: Boolean, report: () -> Unit) {
    var preview by remember { mutableStateOf<String?>(null) }
    val pending = UndoPlanner.pending(journal)
    Page {
        Text("A record of every change", style = MaterialTheme.typography.headlineMedium)
        Button(onClick = vm::restoreDefaults, enabled = !busy && pending.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Restore defaults") }
        Note(if (pending.isEmpty()) "Nothing to restore: no pending changes." else "${pending.size} change(s) pending. One tap writes back every saved value, newest first. A value you changed by hand since is left untouched and stays pending.")
        OutlinedButton(onClick = { preview = vm.restorePreview() }, enabled = pending.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Preview restore commands") }
        if (journal.isEmpty()) Text("No changes have been journaled.")
        journal.forEach { row ->
            Panel("${row.kind} · ${row.status}") {
                Text(row.target); Note(date(row.createdAt)); Text("${row.before} → ${row.expected}"); Note(row.detail)
                Note("Original command:"); Command(runCatching { Commands.apply(row.kind, row.target, row.expected).display }.getOrDefault("unavailable for this record"))
                Note("Restore:"); runCatching { Commands.undo(row) }.getOrDefault(emptyList()).forEach { Command(it.display) }
                Button(onClick = { vm.undo(row.id) }, enabled = !busy && row.status != JournalStatus.UNDONE) { Text("Undo / retry this change") }
            }
        }
        Text("Saved snapshots", style = MaterialTheme.typography.headlineSmall)
        snapshots.forEach { row ->
            Panel("${row.role} · ${date(row.takenAt)}") {
                Note("Score: ${Scoring.calculate(row.metrics).total ?: "unavailable"} · Snapshot ${row.id}")
                OutlinedButton(onClick = { vm.selectedBefore.value = row.id; report() }) { Text("Use as BEFORE") }
                OutlinedButton(onClick = { vm.selectedAfter.value = row.id; report() }) { Text("Use as AFTER") }
            }
        }
    }
    preview?.let { text ->
        AlertDialog(onDismissRequest = { preview = null }, title = { Text("Restore dry run") },
            text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) { Command(text) } },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("Close") } })
    }
}

@Composable
private fun SnapshotPicker(label: String, rows: List<AuditSnapshot>, selected: AuditSnapshot?, choose: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = rows.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("$label: ${selected?.let { "#${it.id} · ${date(it.takenAt)}" } ?: "Choose snapshot"}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            rows.forEach { row -> DropdownMenuItem(text = { Text("#${row.id} ${row.role} · ${date(row.takenAt)}") }, onClick = { choose(row.id); expanded = false }) }
        }
    }
}

@Composable
private fun Report(vm: OptimizerViewModel, snapshots: List<AuditSnapshot>, busy: Boolean, export: () -> Unit) {
    val beforeId by vm.selectedBefore.collectAsStateWithLifecycle()
    val afterId by vm.selectedAfter.collectAsStateWithLifecycle()
    val before = snapshots.firstOrNull { it.id == beforeId } ?: snapshots.firstOrNull { it.role == "BEFORE" }
    val after = snapshots.firstOrNull { it.id == afterId } ?: snapshots.firstOrNull { it.role == "AFTER" }
    var filter by rememberSaveable { mutableStateOf("") }
    var limit by rememberSaveable(before?.id, after?.id) { mutableIntStateOf(30) }
    Page {
        Text("Observe changes over time", style = MaterialTheme.typography.headlineMedium)
        SnapshotPicker("BEFORE", snapshots, before) { vm.selectedBefore.value = it }
        SnapshotPicker("AFTER", snapshots, after) { vm.selectedAfter.value = it }
        if (before == null || after == null || after.takenAt <= before.takenAt) {
            Text("Choose a BEFORE snapshot and a later AFTER snapshot. Take the second audit after normal use, ideally a comparable day—not immediately as proof of a battery saving.")
        } else {
            val diff = remember(before, after) { ReportDiff.compare(before, after) }
            Text("${number((after.takenAt - before.takenAt) / 3_600_000.0)} hours between observations")
            Text(diff.caveat)
            Panel("Comparable condition score") {
                Text("${diff.beforeScore.total ?: "unavailable"} → ${diff.afterScore.total ?: "unavailable"}", style = MaterialTheme.typography.headlineMedium)
                Note("Uses only common measured criteria with matching sources: ${diff.commonScoreCriteria.joinToString().ifEmpty { "none" }}. Weight ${diff.beforeScore.measuredWeight}/100.")
                diff.beforeScore.criteria.forEach { c ->
                    val next = diff.afterScore.criteria.first { it.id == c.id }
                    Note("${c.label} · weight ${c.weight} · raw ${c.raw ?: "unavailable"} → ${next.raw ?: "unavailable"} ${c.unit} · ${c.source}${if (c.points == null) " · excluded" else ""}")
                }
            }
            Button(onClick = export, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Export PDF to Files") }
            OutlinedButton(onClick = vm::shareReport, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Share PDF") }
            Note("Reports include package names and usage evidence. The share sheet sends the file only to the app you choose. This app has no network permission.")
            OutlinedTextField(value = filter, onValueChange = { filter = it; limit = 30 }, label = { Text("Filter all report metrics") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            val metrics = diff.metrics.filter { it.label.contains(filter, true) || it.id.contains(filter, true) }
            metrics.take(limit).forEach { m ->
                Panel(m.label) {
                    Text("${metricValue(m.before)} → ${metricValue(m.after)}")
                    Text("Delta: ${m.delta?.let { String.format(Locale.getDefault(), "%+.2f", it) } ?: "not compared"} ${m.after?.unit.orEmpty()}")
                    Note(m.reason)
                    Note("BEFORE source: ${m.before?.source ?: "unavailable"}\nAFTER source: ${m.after?.source ?: "unavailable"}")
                }
            }
            if (metrics.size > limit) OutlinedButton(onClick = { limit += 40 }) { Text("Show more (${metrics.size - limit} remaining)") }
            Note("The PDF includes every metric and app observation, including unavailable fields.")
        }
    }
}

@Composable
private fun About() {
    Page {
        Text("S23 Optimizer ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineMedium)
        Text("Designed for Galaxy S23 Ultra, Android 14/15 and One UI 6/7. Minimum Android 10. Hardware and shell access vary across builds.")
        Panel("What this app can measure") { Text("Battery signals, storage, memory, thermal state, CPU frequency samples, display modes, animation values and app inventory. Shizuku may expose aggregate per-UID drain estimates, wakelocks, alarms and standby buckets. Usage Access provides foreground time, never exact energy.") }
        Panel("What this app cannot promise") { Text("No guaranteed runtime or speed improvement; no battery capacity health percentage, private app storage totals, generic task killing, force-stop sweep, RAM cleaning, cache deletion or undocumented refresh-rate writes. The condition score reflects three measured conditions only and is not a benchmark.") }
        Panel("Reversible, with practical limits") { Text("Changes are opt-in and journaled before execution. Undo checks live state and preserves conflicts for review. Reinstalling / clearing this app's data removes its journal while OS settings can remain changed. Undo before uninstalling. Android itself may later change standby buckets; the app does not silently fight those changes.") }
        Panel("Privacy") { Text("No INTERNET permission, analytics or remote service. Snapshots and journal stay in local Room storage; Android backup is disabled. PDF export and sharing are explicit. Reports contain installed packages and usage evidence. Shizuku's pairing is managed by Shizuku and Android.") }
        Note("Apache License 2.0. AndroidX, Kotlin, coroutines and Shizuku are used under their respective open-source licenses. The complete project license and documented allowlist are in the repository.")
    }
}

private fun AccessState.label() = when (this) {
    AccessState.NOT_INSTALLED -> "not installed"
    AccessState.NOT_RUNNING -> "not running"
    AccessState.PERMISSION_NEEDED -> "permission needed"
    AccessState.PERMISSION_DENIED -> "permission denied"
    AccessState.UNSUPPORTED_MODE -> "unsupported mode"
    AccessState.READY -> "ready"
}

@Composable
internal fun AccessPanel(vm: OptimizerViewModel, access: ShellAccess, note: String? = null) {
    Panel("Shizuku · ${access.state.label()}") {
        Text(access.message)
        note?.let { Note(it) }
        when (access.action) {
            AccessAction.INSTALL_SHIZUKU -> Button(onClick = vm::installShizuku) { Text("Get Shizuku") }
            AccessAction.OPEN_SHIZUKU -> Button(onClick = vm::openShizuku) { Text("Open Shizuku") }
            AccessAction.REQUEST_PERMISSION -> Button(onClick = { vm.graph.access.requestPermission() }) { Text("Grant Shizuku permission") }
            AccessAction.NONE -> Unit
        }
        OutlinedButton(onClick = { vm.graph.access.refresh() }) { Text("Check again") }
    }
}
