package dev.muhib.s23optimizer.core

/** One command a change or restore would run, listed without executing anything. */
data class DryRunStep(val phase: String, val target: String, val command: ShellCommand, val note: String = "")

object DryRun {
    /** read → apply → verify (same read) → undo, per reviewed change, in the order they would run. */
    fun apply(plans: List<ReviewedChange>): List<DryRunStep> = plans.flatMap { plan ->
        val read = Commands.read(plan.kind, plan.target)
        listOf(
            DryRunStep("read", plan.target, read, "Prior value must still be ${plan.before}."),
            DryRunStep("apply", plan.target, plan.command, "Runs only after the journal row is saved."),
            DryRunStep("verify", plan.target, read, "Expected ${plan.expected}.")
        ) + plan.undoCommands.map { DryRunStep("undo", plan.target, it, "Restores ${plan.before}.") }
    }

    /** What Restore defaults would run: pending records newest first, each read before it is restored. */
    fun restore(entries: List<JournalEntry>): List<DryRunStep> = UndoPlanner.pending(entries).flatMap { entry ->
        try {
            listOf(DryRunStep("read", entry.target, Commands.read(entry.kind, entry.target), "Skipped if already ${entry.before}; left untouched if neither ${entry.before} nor ${entry.expected}.")) +
                Commands.undo(entry).map { DryRunStep("restore", entry.target, it, "Restores ${entry.before}.") }
        } catch (e: IllegalArgumentException) {
            listOf(DryRunStep("blocked", entry.target, ShellCommand(emptyList()), "Record ${entry.id} cannot be restored automatically: ${e.message}"))
        }
    }

    fun render(steps: List<DryRunStep>): String = buildString {
        appendLine("# S23 Optimizer dry run: nothing has been executed")
        if (steps.isEmpty()) appendLine("# Nothing to run.")
        steps.forEach { step ->
            if (step.command.args.isEmpty()) appendLine("${step.phase}\t${step.target}\t# ${step.note}")
            else appendLine("${step.phase}\t${step.target}\t${step.command.display}")
        }
    }.trimEnd()
}
