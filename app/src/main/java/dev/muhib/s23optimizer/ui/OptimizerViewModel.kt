package dev.muhib.s23optimizer.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.muhib.s23optimizer.OptimizerApplication
import dev.muhib.s23optimizer.core.*
import dev.muhib.s23optimizer.platform.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

class OptimizerViewModel(application: Application) : AndroidViewModel(application) {
    val graph = (application as OptimizerApplication).graph
    val snapshots = graph.repository.snapshots.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    /** Audits only; hourly battery SAMPLE rows feed the Measured panel, not History or the pickers. */
    val audits = snapshots.map { rows -> rows.filter { it.role != AuditCollector.SAMPLE } }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val journal = graph.journal.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val starts = MutableStateFlow(graph.startTimer.samples())
    val measured = combine(snapshots, journal, starts) { rows, entries, startList ->
        MeasuredValue.build(rows.mapNotNull(BatterySample::from), startList, MeasuredValue.window(entries))
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MeasuredValue.build(emptyList(), emptyList(), MeasureWindow(null, null)))
    val batterySamples = MutableStateFlow(graph.preferences.getBoolean(BatterySampleWorker.PREF, false))
    val access = graph.access.state
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val review = MutableStateFlow<List<ReviewedChange>>(emptyList())
    val selectedBefore = MutableStateFlow<Long?>(null)
    val selectedAfter = MutableStateFlow<Long?>(null)
    val daily = MutableStateFlow(graph.preferences.getBoolean("daily_audit", false))
    val share = MutableSharedFlow<Uri>(extraBufferCapacity = 1)
    private var exportPair: Pair<AuditSnapshot, AuditSnapshot>? = null

    private fun task(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { message.value = e.message ?: "Operation unavailable. No success is assumed." } finally { busy.value = false }
        }
    }
    fun audit(role: String) = task {
        message.value = "Collecting read-only diagnostics…"
        val snapshot = graph.audit.collect(role)
        val id = graph.repository.save(snapshot)
        if (role == "BEFORE") selectedBefore.value = id
        if (role == "AFTER") selectedAfter.value = id
        message.value = "$role snapshot saved. Unreadable fields are marked unavailable."
    }
    fun prepare(kind: ChangeKind, target: String, expected: String, allowlistOptIn: Boolean = false) = task {
        review.value = emptyList()
        if (kind == ChangeKind.DISABLE) require(allowlistOptIn) { "Explicit allowlist opt-in is required." }
        review.value = listOf(graph.engine.prepare(kind, target, expected).getOrThrow())
    }
    fun prepareAnimations() = task {
        review.value = emptyList()
        val plans = mutableListOf<ReviewedChange>()
        for (key in Commands.animationKeys) {
            val current = Commands.parse(ChangeKind.ANIMATION, graph.shell.run(Commands.read(ChangeKind.ANIMATION, key)))
            requireNotNull(current) { "Cannot read $key. No changes were made." }
            if (current != "0.5") plans += graph.engine.prepare(ChangeKind.ANIMATION, key, "0.5").getOrThrow()
        }
        require(plans.isNotEmpty()) { "All animation scales are already 0.5." }
        review.value = plans
    }
    fun applyReviewed() = task {
        val plans = review.value.toList()
        require(plans.isNotEmpty()) { "No reviewed commands." }
        review.value = emptyList()
        var applied = 0
        for (plan in plans) {
            val row = graph.engine.apply(plan).getOrThrow()
            if (row.status != JournalStatus.APPLIED) {
                message.value = "${row.status}: ${row.detail}. $applied earlier change(s) verified. Review History / Undo."
                return@task
            }
            applied++
        }
        message.value = "$applied change(s) applied and verified. Undo is in History. Take AFTER measurements later under comparable conditions."
    }
    fun undo(id: Long) = task {
        val row = graph.engine.undo(id).getOrThrow()
        message.value = "${row.status}: ${row.detail}"
    }
    /** One tap: every pending change back to its journaled value, newest first. */
    fun restoreDefaults() = task {
        val results = graph.engine.undoAll()
        if (results.isEmpty()) { message.value = "Nothing to restore: no pending changes."; return@task }
        val restored = results.count { it.getOrNull()?.status == JournalStatus.UNDONE }
        val blocked = results.firstNotNullOfOrNull { it.exceptionOrNull()?.message }
        message.value = "$restored of ${results.size} change(s) restored or already at their saved value. " +
            (blocked?.let { "Not restored: $it" } ?: "Conflicts and failures stay pending in History.")
    }
    fun restorePreview(): String = DryRun.render(DryRun.restore(journal.value))
    fun refreshStarts() { starts.value = graph.startTimer.samples() }
    fun sampleBattery() = task {
        graph.repository.saveSample(graph.audit.batterySample())
        message.value = "Battery sample saved."
    }
    fun setBatterySamples(value: Boolean) {
        graph.preferences.edit().putBoolean(BatterySampleWorker.PREF, value).apply()
        BatterySampleWorker.schedule(graph.app, value)
        batterySamples.value = value
        if (value) sampleBattery()
    }
    fun exportCsv(uri: Uri?) {
        if (uri == null) return
        task {
            val rows = snapshots.value.mapNotNull(BatterySample::from)
            val text = MeasuredValue.csv(MeasuredValue.build(rows, starts.value, MeasuredValue.window(journal.value)), rows, starts.value)
            withContext(Dispatchers.IO) {
                requireNotNull(graph.app.contentResolver.openOutputStream(uri, "wt")) { "Cannot open selected document." }.use { it.write(text.toByteArray()) }
            }
            message.value = "Measured CSV saved to the location you selected."
        }
    }
    fun pair(): Pair<AuditSnapshot, AuditSnapshot>? {
        val rows = audits.value
        val before = selectedBefore.value?.let { id -> rows.firstOrNull { it.id == id } } ?: rows.firstOrNull { it.role == "BEFORE" }
        val after = selectedAfter.value?.let { id -> rows.firstOrNull { it.id == id } } ?: rows.firstOrNull { it.role == "AFTER" }
        return if (before != null && after != null && after.takenAt > before.takenAt) before to after else null
    }
    fun prepareExport(): Boolean {
        exportPair = pair()
        if (exportPair == null) message.value = "Choose a BEFORE snapshot and a later AFTER snapshot."
        return exportPair != null
    }
    fun export(uri: Uri?) {
        val pair = exportPair
        exportPair = null
        if (uri == null) return
        if (pair == null) { message.value = "Export selection was lost; choose the two snapshots and export again."; return }
        task {
            withContext(Dispatchers.IO) {
                requireNotNull(graph.app.contentResolver.openOutputStream(uri, "wt")) { "Cannot open selected document." }.use { ReportPdf.write(pair.first, pair.second, it) }
            }
            message.value = "PDF saved to the location you selected."
        }
    }
    fun shareReport() = task {
        val pair = requireNotNull(pair()) { "Choose a BEFORE snapshot and a later AFTER snapshot." }
        val uri = withContext(Dispatchers.IO) {
            val folder = File(graph.app.cacheDir, "reports").apply { mkdirs() }
            // Distinct names keep an already shared file stable; age out old private cache copies.
            folder.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 7 * DAY_MS }?.forEach { it.delete() }
            val file = File(folder, "S23-Optimizer-report-${System.currentTimeMillis()}.pdf")
            file.outputStream().use { ReportPdf.write(pair.first, pair.second, it) }
            FileProvider.getUriForFile(graph.app, "${graph.app.packageName}.reports", file)
        }
        share.emit(uri)
    }
    fun setDaily(value: Boolean) {
        graph.preferences.edit().putBoolean("daily_audit", value).apply()
        DailyAuditWorker.schedule(graph.app, value)
        daily.value = value
    }
    fun finishOnboarding() { graph.preferences.edit().putBoolean("onboarded", true).apply() }
    fun openShizuku() {
        val launch = graph.app.packageManager.getLaunchIntentForPackage(BinderProbe.SHIZUKU_PACKAGE)
        if (launch == null) { message.value = "Shizuku is not installed or cannot be opened."; return }
        runCatching { graph.app.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { message.value = "Shizuku could not open: ${it.message}" }
    }
    fun installShizuku() {
        fun view(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { graph.app.startActivity(view("market://details?id=${BinderProbe.SHIZUKU_PACKAGE}")) }
            .recoverCatching { graph.app.startActivity(view("https://play.google.com/store/apps/details?id=${BinderProbe.SHIZUKU_PACKAGE}")) }
            .onFailure { message.value = "No app store or browser is available. Install Shizuku from Google Play." }
    }
    fun open(link: SettingsLink) { SettingsLinks.launch(graph.app, link)?.let { message.value = it } }
}
