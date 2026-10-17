package dev.muhib.s23optimizer.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class UndoDecision { RESTORE, ALREADY_RESTORED, CONFLICT, UNAVAILABLE }
object UndoPlanner {
    fun pending(entries: List<JournalEntry>): List<JournalEntry> = entries.filter { it.status != JournalStatus.UNDONE && it.kind != ChangeKind.FORCE_STOP }
        .sortedWith(compareByDescending<JournalEntry> { it.createdAt }.thenByDescending { it.id })
    fun decide(entry: JournalEntry, current: String?): UndoDecision = when (current) {
        null -> UndoDecision.UNAVAILABLE
        entry.before -> UndoDecision.ALREADY_RESTORED
        entry.expected -> UndoDecision.RESTORE
        // A crash between pm enable and pm default-state is still safely recoverable.
        "1" -> if (entry.kind == ChangeKind.DISABLE && entry.before == "0") UndoDecision.RESTORE else UndoDecision.CONFLICT
        else -> UndoDecision.CONFLICT
    }
}

data class ReviewedChange(val kind: ChangeKind, val target: String, val before: String, val expected: String) {
    val revertible: Boolean get() = kind != ChangeKind.FORCE_STOP
    val command: ShellCommand get() = Commands.apply(kind, target, expected)
    val undoCommands: List<ShellCommand> get() = Commands.undo(JournalEntry(kind = kind, target = target, before = before, expected = expected))
}

class ChangeEngine(
    private val shell: ShellRunner,
    private val journal: JournalStore,
    private val policy: suspend (ChangeKind, String) -> EligibilityResult
) {
    private val mutex = Mutex()
    private suspend fun <T> safely(block: suspend () -> T): Result<T> = try { Result.success(block()) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }

    suspend fun prepare(kind: ChangeKind, target: String, expected: String): Result<ReviewedChange> = safely {
        val eligible = policy(kind, target)
        require(eligible.allowed) { eligible.reasons.joinToString(" ") }
        Commands.apply(kind, target, expected)
        if (kind == ChangeKind.FORCE_STOP) return@safely ReviewedChange(kind, target, "non-revertible", expected)
        val before = Commands.parse(kind, shell.run(Commands.read(kind, target)))
        requireNotNull(before) { "Prior value is unavailable; no change permitted." }
        require(before != expected) { "Already at the requested value." }
        if (kind == ChangeKind.DISABLE) require(before in setOf("0", "1")) { "Package is not currently enabled." }
        ReviewedChange(kind, target, before, expected).also { it.undoCommands }
    }

    suspend fun apply(plan: ReviewedChange): Result<JournalEntry> = safely { mutex.withLock {
        val eligible = policy(plan.kind, plan.target)
        require(eligible.allowed) { eligible.reasons.joinToString(" ") }
        plan.command // Validate even a caller-constructed plan before journaling.
        if (plan.revertible) {
            val prior = Commands.parse(plan.kind, shell.run(Commands.read(plan.kind, plan.target)))
            require(prior == plan.before) { "State changed since review; refresh the recommendation." }
        } else require(plan.before == "non-revertible")
        val row = JournalEntry(kind = plan.kind, target = plan.target, before = plan.before, expected = plan.expected,
            detail = if (plan.revertible) "Prepared; the command may not have run. Undo can inspect and recover."
                else "Prepared force-stop; the command may not have run. Cannot undo; inspect current state if interrupted.")
        Commands.undo(row) // Validate the entire reverse plan before durable insertion or mutation.
        val saved = row.copy(id = journal.insert(row))
        // If the process dies after this line, PREPARED is retained for recovery.
        try {
            val output = shell.run(plan.command)
            if (!plan.revertible) {
                return@withLock saved.copy(
                    status = if (output.success) JournalStatus.APPLIED else JournalStatus.NEEDS_REVIEW,
                    completedAt = System.currentTimeMillis(),
                    detail = "Force-stop ${if (output.success) "command accepted" else "outcome uncertain (exit ${output.exitCode})"}. Non-revertible; no prior process state can be restored. Open the app yourself when needed."
                ).also { journal.update(it) }
            }
            val verified = Commands.parse(plan.kind, shell.run(Commands.read(plan.kind, plan.target)))
            val status = when {
                output.success && verified == plan.expected -> JournalStatus.APPLIED
                verified == plan.before -> JournalStatus.FAILED
                else -> JournalStatus.NEEDS_REVIEW
            }
            saved.copy(status = status, completedAt = System.currentTimeMillis(), detail =
                "Exit ${output.exitCode}; observed ${verified ?: "unavailable"}. ${output.stderr.take(1200)} ${output.stdout.take(1200)}")
                .also { journal.update(it) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            saved.copy(status = JournalStatus.NEEDS_REVIEW, detail = if (plan.revertible) "Interrupted: ${e.message}. Inspect or undo; state is uncertain."
                else "Interrupted force-stop; state is uncertain. Cannot undo. Inspect current state before deciding what to do next.")
                .also { journal.update(it) }
        }
    } }

    suspend fun undo(id: Long): Result<JournalEntry> = safely { mutex.withLock { undoLocked(id) } }
    private suspend fun undoLocked(id: Long): JournalEntry {
        val entry = journal.entries().first { it.id == id }
        require(entry.kind != ChangeKind.FORCE_STOP) { "Force-stop is non-revertible. Open the app yourself when needed." }
        if (entry.status == JournalStatus.UNDONE) return entry
        try {
            val current = Commands.parse(entry.kind, shell.run(Commands.read(entry.kind, entry.target)))
            val decision = UndoPlanner.decide(entry, current)
            if (decision == UndoDecision.CONFLICT || decision == UndoDecision.UNAVAILABLE) {
                return entry.copy(status = JournalStatus.UNDO_FAILED, detail = "Undo pending: $decision (current ${current ?: "unavailable"}). No value overwritten.").also { journal.update(it) }
            }
            if (decision == UndoDecision.RESTORE) {
                for (command in Commands.undo(entry)) {
                    val result = shell.run(command)
                    check(result.success) { "Undo command failed: ${result.stderr.take(1200)}" }
                }
            }
            val verified = Commands.parse(entry.kind, shell.run(Commands.read(entry.kind, entry.target)))
            val restored = verified == entry.before
            return entry.copy(status = if (restored) JournalStatus.UNDONE else JournalStatus.UNDO_FAILED,
                completedAt = System.currentTimeMillis(), detail = if (restored) "Prior value verified: ${entry.before}" else "Undo pending; observed ${verified ?: "unavailable"}.")
                .also { journal.update(it) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            return entry.copy(status = JournalStatus.UNDO_FAILED, detail = "Undo pending: ${e.message}").also { journal.update(it) }
        }
    }
    suspend fun undoAll(): List<Result<JournalEntry>> = mutex.withLock {
        UndoPlanner.pending(journal.entries()).map { entry -> safely { undoLocked(entry.id) } }
    }
}
