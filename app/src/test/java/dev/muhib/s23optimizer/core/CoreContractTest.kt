package dev.muhib.s23optimizer.core

import org.junit.Assert.*
import org.junit.Test

/** Written before production code. Fixtures are synthetic, never captured device data. */
class CoreContractTest {
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/$name")).readText()
    private val now = 1_800_000_000_000L
    private fun idle(pkg: String = "org.example.reader", system: Boolean = false) = AppProfile(
        packageName = pkg, label = pkg, uid = 10123, system = system, enabled = true,
        firstInstallMs = now - 60 * DAY_MS, lastUsedMs = now - 20 * DAY_MS,
        foregroundMs = 1000, protectedReasons = emptyList()
    )

    @Test fun `power estimates map Android UIDs and preserve missing values`() {
        val result = BatteryStatsParser.parse(fixture("batterystats.txt"))
        assertEquals(12.34, result.apps.getValue(10123).estimatedMah!!, 0.001)
        assertEquals(0.42, result.apps.getValue(10124).estimatedMah!!, 0.001)
        assertEquals(1.50, result.apps.getValue(1010125).estimatedMah!!, 0.001)
        assertEquals(62_300L, result.apps.getValue(10123).partialWakeMs)
        assertEquals(4L, result.apps.getValue(10123).wakeupAlarms)
        assertNull(result.apps.getValue(10124).wakeupAlarms)
        assertFalse(result.apps.containsKey(10999))
        assertTrue(result.window.contains("last charge"))
    }

    @Test fun `denial or unfamiliar vendor output is unknown never zero`() {
        for (name in listOf("batterystats-unreadable.txt", "batterystats-unknown.txt")) {
            val parsed = BatteryStatsParser.parse(fixture(name))
            assertTrue(parsed.apps.values.all { it.estimatedMah == null && it.partialWakeMs == null && it.wakeupAlarms == null })
            assertTrue(parsed.warnings.isNotEmpty())
        }
    }

    @Test fun `duration parser rejects incomplete or negative data`() {
        assertEquals(3_723_004L, ShellParsers.durationMs("1h 2m 3s 4ms"))
        assertEquals(500L, ShellParsers.durationMs("500ms"))
        assertNull(ShellParsers.durationMs("-1s"))
        assertNull(ShellParsers.durationMs("unknown"))
        assertNull(ShellParsers.durationMs("1m nonsense"))
    }

    @Test fun `shell state parsers do not invent prior values`() {
        assertEquals("allow", ShellParsers.appOp("RUN_ANY_IN_BACKGROUND: allow; time=+1h2m ago"))
        assertEquals("default", ShellParsers.appOp("No operations."))
        assertNull(ShellParsers.appOp("Error: package not found"))
        assertNull(ShellParsers.appOp("RUN_ANY_IN_BACKGROUND: unexpected"))
        assertEquals("40", ShellParsers.bucket("40"))
        assertEquals("45", ShellParsers.bucket("com.example.idle: bucket=45"))
        assertNull(ShellParsers.bucket("Permission Denial: uid 2000"))
        assertNull(ShellParsers.bucket("999"))
        assertEquals("0", ShellParsers.enabled("User 0: installed=true hidden=false suspended=false enabled=0"))
        assertNull(ShellParsers.enabled("User 10: enabled=0"))
        assertNull(ShellParsers.enabled("User 0: installed=false enabled=0"))
        assertNull(ShellParsers.animation("NaN"))
        assertEquals("null", ShellParsers.animation("null"))
    }

    @Test fun `only verified idle user apps qualify for background restriction`() {
        assertTrue(Eligibility.check(idle(), ChangeKind.APP_OP, now).allowed)
        assertFalse(Eligibility.check(idle().copy(lastUsedMs = null), ChangeKind.APP_OP, now).allowed)
        assertFalse(Eligibility.check(idle().copy(lastUsedMs = now - DAY_MS), ChangeKind.APP_OP, now).allowed)
        assertFalse(Eligibility.check(idle().copy(firstInstallMs = now - DAY_MS), ChangeKind.APP_OP, now).allowed)
        assertFalse(Eligibility.check(idle().copy(lastUsedMs = now + DAY_MS), ChangeKind.APP_OP, now).allowed)
        assertFalse(Eligibility.check(idle(system = true), ChangeKind.APP_OP, now).allowed)
        assertFalse(Eligibility.check(idle().copy(protectedReasons = listOf("Default keyboard")), ChangeKind.APP_OP, now).allowed)
        assertFalse(Eligibility.check(idle().copy(enabled = false), ChangeKind.STANDBY, now).allowed)
    }

    @Test fun `disabling requires exact curated name and explicit opt in`() {
        val bixby = idle("com.samsung.android.bixby.agent", true)
        assertFalse(Eligibility.check(bixby, ChangeKind.DISABLE, now).allowed)
        assertTrue(Eligibility.check(bixby, ChangeKind.DISABLE, now, true).allowed)
        assertFalse(Eligibility.check(idle("com.samsung.android.bixby.agent.evil", true), ChangeKind.DISABLE, now, true).allowed)
        assertFalse(Eligibility.check(idle("com.unknown.vendor.app", true), ChangeKind.DISABLE, now, true).allowed)
    }

    @Test fun `critical apps cannot be eligible even when user installed or opted in`() {
        for (pkg in listOf("android", "com.android.systemui", "com.sec.android.app.launcher", "com.samsung.android.honeyboard",
            "com.samsung.android.messaging", "com.samsung.android.oneconnect", "com.samsung.android.app.watchmanager",
            "com.sec.android.app.camera", "com.samsung.android.knox.containercore", "com.example.bank", "com.google.android.apps.maps")) {
            assertFalse(pkg, Eligibility.check(idle(pkg), ChangeKind.APP_OP, now, true).allowed)
            assertFalse(pkg, Eligibility.check(idle(pkg), ChangeKind.DISABLE, now, true).allowed)
        }
        assertTrue(BloatAllowlist.entries.keys.none { Eligibility.isAlwaysProtected(it) })
    }

    @Test fun `undo is reverse ordered crash safe and respects intervening changes`() {
        val oldest = JournalEntry(id = 1, createdAt = 1, kind = ChangeKind.APP_OP, target = "org.example.reader", before = "allow", expected = "ignore", status = JournalStatus.APPLIED)
        val newest = oldest.copy(id = 2, createdAt = 2, kind = ChangeKind.STANDBY, before = "20", expected = "45", status = JournalStatus.PREPARED)
        assertEquals(listOf(2L, 1L), UndoPlanner.pending(listOf(oldest, newest)).map { it.id })
        assertEquals(UndoDecision.RESTORE, UndoPlanner.decide(oldest, "ignore"))
        assertEquals(UndoDecision.ALREADY_RESTORED, UndoPlanner.decide(oldest, "allow"))
        assertEquals(UndoDecision.CONFLICT, UndoPlanner.decide(oldest, "deny"))
        assertEquals(UndoDecision.UNAVAILABLE, UndoPlanner.decide(oldest, null))
        assertTrue(UndoPlanner.pending(listOf(oldest.copy(status = JournalStatus.UNDONE))).isEmpty())
        assertEquals(listOf(1L), UndoPlanner.pending(listOf(oldest.copy(status = JournalStatus.UNDO_FAILED))).map { it.id })
    }

    @Test fun `restoring default animation deletes override and default enabled state is preserved`() {
        val animation = JournalEntry(kind = ChangeKind.ANIMATION, target = "window_animation_scale", before = "null", expected = "0.5")
        assertEquals(listOf("settings", "delete", "global", "window_animation_scale"), Commands.undo(animation).single().args)
        val disable = animation.copy(kind = ChangeKind.DISABLE, target = "com.samsung.android.bixby.agent", before = "0", expected = "3")
        assertEquals(listOf("pm", "enable", "--user", "0", disable.target), Commands.undo(disable).first().args)
        assertEquals("default-state", Commands.undo(disable).last().args[1])
    }

    @Test fun `command construction rejects injection and unapproved targets`() {
        assertThrows(IllegalArgumentException::class.java) { Commands.read(ChangeKind.APP_OP, "app;reboot") }
        assertThrows(IllegalArgumentException::class.java) { Commands.apply(ChangeKind.DISABLE, "com.android.systemui", "3") }
        assertThrows(IllegalArgumentException::class.java) { Commands.apply(ChangeKind.APP_OP, "org.example.reader", "deny") }
        assertThrows(IllegalArgumentException::class.java) { Commands.apply(ChangeKind.ANIMATION, "undocumented_refresh_key", "0.5") }
    }

    @Test fun `score excludes unknowns renormalizes weights and exposes evidence`() {
        val metrics = listOf(Metric("storage.free_pct", "Free storage", 15.0, "%", "StatFs"), Metric("battery.temp_c", "Battery temperature", null, "°C", "unavailable"))
        val score = Scoring.calculate(metrics)
        assertEquals(100, score.total)
        assertEquals(35, score.measuredWeight)
        assertEquals(15.0, score.criteria.single { it.id == "storage.free_pct" }.raw!!, 0.0)
        assertEquals(2, score.excluded.size)
        assertNull(Scoring.calculate(emptyList()).total)
        assertNull(Scoring.calculate(listOf(metrics[0].copy(value = Double.NaN))).total)
        assertEquals(0, Scoring.calculate(listOf(metrics[0].copy(value = 0.0))).total)
    }

    @Test fun `diff compares measured same source metrics and explains omissions`() {
        val before = AuditSnapshot(takenAt = 100, role = "BEFORE", metrics = listOf(
            Metric("storage.free_pct", "Free storage", 10.0, "%", "StatFs"),
            Metric("battery.temp_c", "Battery temperature", 30.0, "°C", "BatteryManager"),
            Metric("drain", "Drain", 10.0, "mAh", "batterystats", cumulative = true)
        ))
        val after = before.copy(takenAt = 200, role = "AFTER", metrics = listOf(
            before.metrics[0].copy(value = 12.0), before.metrics[1].copy(value = 29.0, source = "vendor"), before.metrics[2].copy(value = 25.0),
            Metric("ram.available_mb", "RAM", 1000.0, "MiB", "ActivityManager")
        ))
        val diff = ReportDiff.compare(before, after)
        assertEquals(2.0, diff.metrics.single { it.id == "storage.free_pct" }.delta!!, 0.001)
        assertNull(diff.metrics.single { it.id == "battery.temp_c" }.delta)
        assertNull(diff.metrics.single { it.id == "drain" }.delta)
        assertNull(diff.metrics.single { it.id == "ram.available_mb" }.delta)
        assertEquals(listOf("storage.free_pct"), diff.commonScoreCriteria)
        assertTrue(diff.caveat.contains("caus"))
    }
}
