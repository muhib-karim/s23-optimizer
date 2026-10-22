package dev.muhib.s23optimizer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DryRunTest {
    private val pkg = "org.example.reader"
    private val bixby = "com.samsung.android.bixby.agent"

    @Test fun `apply preview lists read apply verify undo for each reviewed change`() {
        val plans = listOf(
            ReviewedChange(ChangeKind.APP_OP, pkg, "allow", "ignore"),
            ReviewedChange(ChangeKind.DISABLE, bixby, "0", "3")
        )
        val steps = DryRun.apply(plans)
        assertEquals(listOf("read", "apply", "verify", "undo", "read", "apply", "verify", "undo", "undo"), steps.map { it.phase })
        assertEquals("cmd appops set --user 0 $pkg RUN_ANY_IN_BACKGROUND ignore", steps[1].command.display)
        assertEquals(steps[0].command, steps[2].command)
        assertEquals("cmd appops set --user 0 $pkg RUN_ANY_IN_BACKGROUND allow", steps[3].command.display)
        assertEquals(listOf("pm enable --user 0 $bixby", "pm default-state --user 0 $bixby"), steps.takeLast(2).map { it.command.display })
        assertTrue(steps.all { it.target == pkg || it.target == bixby })
    }

    @Test fun `restore preview covers only pending records newest first`() {
        val rows = listOf(
            JournalEntry(id = 1, createdAt = 10, kind = ChangeKind.APP_OP, target = pkg, before = "allow", expected = "ignore", status = JournalStatus.APPLIED),
            JournalEntry(id = 2, createdAt = 20, kind = ChangeKind.ANIMATION, target = "window_animation_scale", before = "null", expected = "0.5", status = JournalStatus.UNDONE),
            JournalEntry(id = 3, createdAt = 30, kind = ChangeKind.STANDBY, target = pkg, before = "10", expected = "45", status = JournalStatus.UNDO_FAILED)
        )
        val steps = DryRun.restore(rows)
        assertEquals(
            listOf(
                "read" to "am get-standby-bucket --user 0 $pkg",
                "restore" to "am set-standby-bucket --user 0 $pkg 10",
                "read" to "cmd appops get --user 0 $pkg RUN_ANY_IN_BACKGROUND",
                "restore" to "cmd appops set --user 0 $pkg RUN_ANY_IN_BACKGROUND allow"
            ),
            steps.map { it.phase to it.command.display }
        )
    }

    @Test fun `restore preview reports a corrupt record instead of crashing`() {
        val bad = JournalEntry(id = 9, kind = ChangeKind.APP_OP, target = pkg, before = "errored", expected = "ignore", status = JournalStatus.APPLIED)
        val step = DryRun.restore(listOf(bad)).single()
        assertEquals("blocked", step.phase)
        assertTrue(step.command.args.isEmpty())
        assertTrue(step.note.isNotBlank())
    }

    @Test fun `nothing pending gives an empty preview`() {
        assertTrue(DryRun.restore(emptyList()).isEmpty())
        assertTrue(DryRun.render(emptyList()).contains("Nothing to run"))
    }

    @Test fun `rendered text states nothing was executed and keeps one command per line`() {
        val text = DryRun.render(DryRun.apply(listOf(ReviewedChange(ChangeKind.ANIMATION, "window_animation_scale", "1", "0.5"))))
        val lines = text.lines()
        assertTrue(lines.first().contains("nothing has been executed"))
        assertTrue(lines.any { it.endsWith("settings put global window_animation_scale 0.5") && it.startsWith("apply") })
        assertTrue(lines.any { it.endsWith("settings put global window_animation_scale 1") && it.startsWith("undo") })
    }
}
