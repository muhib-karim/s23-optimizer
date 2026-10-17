package dev.muhib.s23optimizer.ui

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.muhib.s23optimizer.OptimizerApplication
import dev.muhib.s23optimizer.core.*
import dev.muhib.s23optimizer.core.agent.*
import dev.muhib.s23optimizer.platform.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

class OptimizerViewModel(application: Application) : AndroidViewModel(application) {
    val graph = (application as OptimizerApplication).graph
    val snapshots = graph.repository.snapshots.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val journal = graph.journal.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val access = graph.access.state
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val review = MutableStateFlow<List<ReviewedChange>>(emptyList())
    val selectedBefore = MutableStateFlow<Long?>(null)
    val selectedAfter = MutableStateFlow<Long?>(null)
    val daily = MutableStateFlow(graph.preferences.getBoolean("daily_audit", false))
    val share = MutableSharedFlow<Uri>(extraBufferCapacity = 1)
    private var exportPair: Pair<AuditSnapshot, AuditSnapshot>? = null
    val agentAnalysis = MutableStateFlow<AgentAnalysis?>(null)
    val agentProgress = MutableStateFlow("")
    val agentBefore = MutableStateFlow<AgentSnapshot?>(null)
    val agentAfter = MutableStateFlow<AgentSnapshot?>(null)
    val agentDecisions = MutableStateFlow<Map<Int, String>>(emptyMap())
    val agentConfig = MutableStateFlow(graph.agentSettings.config())
    val agentHasKey = MutableStateFlow(graph.agentSettings.hasKey())
    private var reviewedProposal: Int? = null

    private fun task(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = e.message ?: "Operation unavailable. No success is assumed." }
            finally { busy.value = false }
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
        reviewedProposal = null
        review.value = emptyList()
        if (kind == ChangeKind.DISABLE) require(allowlistOptIn) { "Explicit allowlist opt-in is required." }
        review.value = listOf(graph.engine.prepare(kind, target, expected).getOrThrow())
    }
    fun prepareAnimations() = task {
        reviewedProposal = null
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
        val proposalIndex = reviewedProposal
        reviewedProposal = null
        var applied = 0
        for (plan in plans) {
            val row = graph.engine.apply(plan).getOrThrow()
            if (proposalIndex != null) agentDecisions.value = agentDecisions.value + (proposalIndex to if (row.status == JournalStatus.APPLIED) "Applied · see History" else "${row.status} · see History")
            if (row.status != JournalStatus.APPLIED) {
                message.value = "${row.status}: ${row.detail}. $applied earlier change(s) verified. Review History / Undo."
                return@task
            }
            applied++
        }
        message.value = if (plans.any { !it.revertible }) "Force-stop command accepted. It cannot be undone; open the app yourself when needed. See History for the record."
            else "$applied change(s) applied and verified. Undo is in History. Take AFTER measurements later under comparable conditions."
    }
    fun undo(id: Long) = task {
        val row = graph.engine.undo(id).getOrThrow()
        message.value = "${row.status}: ${row.detail}"
    }
    fun undoAll() = task {
        val results = graph.engine.undoAll()
        val restored = results.count { it.getOrNull()?.status == JournalStatus.UNDONE }
        message.value = "$restored of ${results.size} records restored or already at their saved value. Conflicts and failures remain pending in History."
    }
    fun pair(): Pair<AuditSnapshot, AuditSnapshot>? {
        val rows = snapshots.value
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
    fun open(link: SettingsLink) { SettingsLinks.launch(graph.app, link)?.let { message.value = it } }

    fun analyzeAgent() = task {
        require(graph.access.state.value.ready) { "Start Shizuku and grant permission first." }
        val client = withContext(Dispatchers.IO) { graph.agentSettings.client() }
        review.value = emptyList(); reviewedProposal = null
        agentAnalysis.value = null; agentAfter.value = null; agentDecisions.value = emptyMap()
        agentProgress.value = "Loading installed apps…"
        try {
            val apps = graph.inventory.load().apps
            val snapshot = graph.agentReads.collect(apps) { agentProgress.value = it }
            agentBefore.value = snapshot
            agentProgress.value = "Asking the model to explain measurements and propose actions…"
            agentAnalysis.value = AgentLoop(ToolRegistry(apps.map { it.packageName }.toSet()), client).analyze(snapshot)
        } finally { agentProgress.value = "" }
    }

    fun prepareAgent(index: Int) = task {
        require(index !in agentDecisions.value) { "This proposal has already been handled." }
        review.value = emptyList(); reviewedProposal = null
        val proposal = requireNotNull(agentAnalysis.value?.proposals?.getOrNull(index)) { "Analyze again before reviewing a proposal." }
        val registry = ToolRegistry(graph.inventory.load().apps.map { it.packageName }.toSet())
        val action = registry.validateWrite(proposal.action.tool, proposal.action.arguments())
        review.value = listOf(graph.engine.prepare(action.kind, action.pkg, action.expected).getOrThrow())
        reviewedProposal = index
    }

    fun skipAgent(index: Int) {
        if (!busy.value && index !in agentDecisions.value) agentDecisions.value = agentDecisions.value + (index to "Skipped")
    }

    fun remeasureAgent() = task {
        requireNotNull(agentBefore.value) { "Analyze first to establish a baseline." }
        require(graph.access.state.value.ready) { "Start Shizuku and grant permission first." }
        try {
            agentAfter.value = graph.agentReads.collect(graph.inventory.load().apps) { agentProgress.value = it }
        } finally { agentProgress.value = "" }
    }

    fun saveAgentSettings(config: ModelConfig, newKey: String) = task {
        withContext(Dispatchers.IO) { graph.agentSettings.save(config, newKey) }
        agentConfig.value = graph.agentSettings.config(); agentHasKey.value = graph.agentSettings.hasKey()
        message.value = "Agent settings saved. API key encrypted with Android Keystore."
    }

    fun forgetAgentKey() = task {
        withContext(Dispatchers.IO) { graph.agentSettings.clearKey() }
        agentHasKey.value = false
        message.value = "Saved API key removed."
    }
}
