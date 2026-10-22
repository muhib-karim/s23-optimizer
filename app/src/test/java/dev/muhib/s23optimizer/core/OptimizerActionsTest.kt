package dev.muhib.s23optimizer.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each optimizer action end to end against a modelled device: review, apply, verify, undo. */
class OptimizerActionsTest {
    private val pkg = "org.example.reader"
    private val bixby = "com.samsung.android.bixby.agent"
    private val device = DeviceShell()
    private val journal = MemoryJournal()
    private val engine = ChangeEngine(device, journal, allowAll)

    private fun roundTrip(kind: ChangeKind, target: String, expected: String, observe: () -> String?): Pair<String?, String?> = runBlocking {
        val original = observe()
        val plan = engine.prepare(kind, target, expected).getOrThrow()
        val applied = engine.apply(plan).getOrThrow()
        assertEquals(applied.detail, JournalStatus.APPLIED, applied.status)
        val during = observe()
        val undone = engine.undo(applied.id).getOrThrow()
        assertEquals(undone.detail, JournalStatus.UNDONE, undone.status)
        assertEquals(original, observe())
        during to observe()
    }

    @Test fun `background restriction applies ignore and undo restores allow`() {
        device.appOps[pkg] = "allow"
        val (during, after) = roundTrip(ChangeKind.APP_OP, pkg, "ignore") { device.appOps[pkg] }
        assertEquals("ignore", during)
        assertEquals("allow", after)
    }

    @Test fun `background restriction from no recorded op restores default`() = runBlocking {
        val plan = engine.prepare(ChangeKind.APP_OP, pkg, "ignore").getOrThrow()
        assertEquals("default", plan.before)
        val row = engine.apply(plan).getOrThrow()
        engine.undo(row.id).getOrThrow()
        assertEquals("default", device.appOps[pkg])
    }

    @Test fun `rare standby bucket applies 40 and undo restores the prior bucket`() {
        device.buckets[pkg] = 20
        val (during, after) = roundTrip(ChangeKind.STANDBY, pkg, "40") { device.buckets[pkg]?.toString() }
        assertEquals("40", during)
        assertEquals("20", after)
    }

    @Test fun `restricted standby bucket applies 45 and undo restores the prior bucket`() {
        device.buckets[pkg] = 30
        val (during, after) = roundTrip(ChangeKind.STANDBY, pkg, "45") { device.buckets[pkg]?.toString() }
        assertEquals("45", during)
        assertEquals("30", after)
    }

    @Test fun `allowlisted disable sets state 3 and undo returns to default state`() {
        val (during, after) = roundTrip(ChangeKind.DISABLE, bixby, "3") { device.enabled[bixby] ?: "0" }
        assertEquals("3", during)
        assertEquals("0", after)
        assertEquals(listOf("pm", "default-state", "--user", "0", bixby), device.mutations.last())
    }

    @Test fun `allowlisted disable of an explicitly enabled package undoes with enable only`() {
        device.enabled[bixby] = "1"
        roundTrip(ChangeKind.DISABLE, bixby, "3") { device.enabled[bixby] }
        assertEquals(listOf("pm", "enable", "--user", "0", bixby), device.mutations.last())
    }

    @Test fun `animation scale applies 0_5 and undo deletes an unset override`() {
        val (during, after) = roundTrip(ChangeKind.ANIMATION, "window_animation_scale", "0.5") { device.global["window_animation_scale"] }
        assertEquals("0.5", during)
        assertNull(after)
    }

    @Test fun `animation scale undo writes back an explicit prior value`() {
        device.global["animator_duration_scale"] = "1.0"
        val (_, after) = roundTrip(ChangeKind.ANIMATION, "animator_duration_scale", "0.5") { device.global["animator_duration_scale"]?.let { ShellParsers.animation(it) } }
        assertEquals("1", after)
    }

    @Test fun `already applied value is refused at review`() = runBlocking {
        device.appOps[pkg] = "ignore"
        assertTrue(engine.prepare(ChangeKind.APP_OP, pkg, "ignore").isFailure)
        device.global["window_animation_scale"] = "0.5"
        assertTrue(engine.prepare(ChangeKind.ANIMATION, "window_animation_scale", "0.5").isFailure)
        assertTrue(device.mutations.isEmpty())
    }

    @Test fun `disabled or hidden package cannot be reviewed for disable`() = runBlocking {
        device.enabled[bixby] = "2"
        assertTrue(engine.prepare(ChangeKind.DISABLE, bixby, "3").isFailure)
        assertTrue(device.mutations.isEmpty())
    }

    @Test fun `ineligible target never reaches the shell`() = runBlocking {
        val strict = ChangeEngine(device, journal) { _, _ -> EligibilityResult(false, listOf("Used within 14 days or timestamp is invalid.")) }
        val result = strict.prepare(ChangeKind.APP_OP, pkg, "ignore")
        assertTrue(result.exceptionOrNull()!!.message!!.contains("14 days"))
        assertTrue(device.log.isEmpty())
    }

    @Test fun `manual change after apply is a conflict for every action and is never overwritten`() = runBlocking {
        val cases = listOf(
            Triple(ChangeKind.APP_OP, pkg, "ignore") to { device.appOps[pkg] = "deny" },
            Triple(ChangeKind.STANDBY, pkg, "45") to { device.buckets[pkg] = 50 },
            Triple(ChangeKind.DISABLE, bixby, "3") to { device.enabled[bixby] = "2" },
            Triple(ChangeKind.ANIMATION, "window_animation_scale", "0.5") to { device.global["window_animation_scale"] = "2" }
        )
        for ((change, tamper) in cases) {
            val row = engine.apply(engine.prepare(change.first, change.second, change.third).getOrThrow()).getOrThrow()
            tamper()
            val before = device.mutations.size
            val result = engine.undo(row.id).getOrThrow()
            assertEquals(change.first.name, JournalStatus.UNDO_FAILED, result.status)
            assertTrue(result.detail.contains("CONFLICT"))
            assertEquals(before, device.mutations.size)
        }
    }

    @Test fun `restore defaults reverts all four actions newest first`() = runBlocking {
        device.appOps[pkg] = "allow"; device.buckets["org.example.other"] = 10; device.global["transition_animation_scale"] = "1"
        engine.apply(engine.prepare(ChangeKind.APP_OP, pkg, "ignore").getOrThrow()).getOrThrow()
        engine.apply(engine.prepare(ChangeKind.STANDBY, "org.example.other", "40").getOrThrow()).getOrThrow()
        engine.apply(engine.prepare(ChangeKind.DISABLE, bixby, "3").getOrThrow()).getOrThrow()
        engine.apply(engine.prepare(ChangeKind.ANIMATION, "transition_animation_scale", "0.5").getOrThrow()).getOrThrow()
        device.log.clear()

        val results = engine.undoAll()

        assertTrue(results.all { it.getOrThrow().status == JournalStatus.UNDONE })
        assertEquals(listOf("settings", "pm", "am", "cmd"), device.mutations.map { it.first() }.distinct())
        assertEquals("allow", device.appOps[pkg])
        assertEquals(10, device.buckets["org.example.other"])
        assertEquals("0", device.enabled[bixby])
        assertEquals("1", device.global["transition_animation_scale"])
        assertTrue(journal.rows.all { it.status == JournalStatus.UNDONE })
        assertTrue(UndoPlanner.pending(journal.rows).isEmpty())
    }

    @Test fun `shell refusal during apply is recorded as failed without claiming success`() = runBlocking {
        val plan = engine.prepare(ChangeKind.STANDBY, pkg, "45").getOrThrow()
        device.failMutations = true
        val row = engine.apply(plan).getOrThrow()
        assertEquals(JournalStatus.FAILED, row.status)
        assertTrue(row.detail.contains("SecurityException"))
        assertFalse(device.buckets.containsKey(pkg))
    }
}
