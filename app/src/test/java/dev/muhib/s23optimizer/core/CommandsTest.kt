package dev.muhib.s23optimizer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Exact argv for every command the Shizuku shell may receive. A changed argument must fail here first. */
class CommandsTest {
    private val pkg = "org.example.reader"
    private val bixby = "com.samsung.android.bixby.agent"
    private fun entry(kind: ChangeKind, target: String, before: String, expected: String) =
        JournalEntry(kind = kind, target = target, before = before, expected = expected)

    @Test fun `read commands are fixed argv per action`() {
        assertEquals(listOf("cmd", "appops", "get", "--user", "0", pkg, "RUN_ANY_IN_BACKGROUND"), Commands.read(ChangeKind.APP_OP, pkg).args)
        assertEquals(listOf("am", "get-standby-bucket", "--user", "0", pkg), Commands.read(ChangeKind.STANDBY, pkg).args)
        assertEquals(listOf("dumpsys", "package", bixby), Commands.read(ChangeKind.DISABLE, bixby).args)
        for (key in Commands.animationKeys) {
            assertEquals(listOf("settings", "get", "global", key), Commands.read(ChangeKind.ANIMATION, key).args)
        }
    }

    @Test fun `apply commands are fixed argv per action`() {
        assertEquals(listOf("cmd", "appops", "set", "--user", "0", pkg, "RUN_ANY_IN_BACKGROUND", "ignore"), Commands.apply(ChangeKind.APP_OP, pkg, "ignore").args)
        assertEquals(listOf("am", "set-standby-bucket", "--user", "0", pkg, "rare"), Commands.apply(ChangeKind.STANDBY, pkg, "40").args)
        assertEquals(listOf("am", "set-standby-bucket", "--user", "0", pkg, "restricted"), Commands.apply(ChangeKind.STANDBY, pkg, "45").args)
        assertEquals(listOf("pm", "disable-user", "--user", "0", bixby), Commands.apply(ChangeKind.DISABLE, bixby, "3").args)
        assertEquals(listOf("settings", "put", "global", "animator_duration_scale", "0.5"), Commands.apply(ChangeKind.ANIMATION, "animator_duration_scale", "0.5").args)
    }

    @Test fun `every allowlisted package can be disabled and re-enabled`() {
        for (target in BloatAllowlist.entries.keys) {
            assertEquals(listOf("pm", "disable-user", "--user", "0", target), Commands.apply(ChangeKind.DISABLE, target, "3").args)
            assertEquals(listOf(listOf("pm", "enable", "--user", "0", target)), Commands.undo(entry(ChangeKind.DISABLE, target, "1", "3")).map { it.args })
        }
    }

    @Test fun `undo restores every accepted prior app op mode`() {
        for (mode in listOf("allow", "ignore", "deny", "default", "foreground")) {
            assertEquals(listOf("cmd", "appops", "set", "--user", "0", pkg, "RUN_ANY_IN_BACKGROUND", mode), Commands.undo(entry(ChangeKind.APP_OP, pkg, mode, "ignore")).single().args)
        }
    }

    @Test fun `undo restores every valid prior standby bucket by number`() {
        for (bucket in listOf("5", "10", "20", "30", "40", "45", "50")) {
            assertEquals(listOf("am", "set-standby-bucket", "--user", "0", pkg, bucket), Commands.undo(entry(ChangeKind.STANDBY, pkg, bucket, "45")).single().args)
        }
    }

    @Test fun `undo restores explicit animation values and deletes unset ones`() {
        assertEquals(listOf("settings", "put", "global", "transition_animation_scale", "1"), Commands.undo(entry(ChangeKind.ANIMATION, "transition_animation_scale", "1", "0.5")).single().args)
        assertEquals(listOf("settings", "put", "global", "transition_animation_scale", "1.5"), Commands.undo(entry(ChangeKind.ANIMATION, "transition_animation_scale", "1.5", "0.5")).single().args)
        assertEquals(listOf("settings", "delete", "global", "transition_animation_scale"), Commands.undo(entry(ChangeKind.ANIMATION, "transition_animation_scale", "null", "0.5")).single().args)
    }

    @Test fun `undo of a default-state package re-enables then resets to default`() {
        assertEquals(
            listOf(listOf("pm", "enable", "--user", "0", bixby), listOf("pm", "default-state", "--user", "0", bixby)),
            Commands.undo(entry(ChangeKind.DISABLE, bixby, "0", "3")).map { it.args }
        )
    }

    @Test fun `builder rejects shell metacharacters and malformed package names`() {
        for (bad in listOf("app;reboot", "org.example reader", "org.example\$(id)", "org.example|sh", "noDots", ".leading.dot", "org.example`id`", "")) {
            assertThrows(bad, IllegalArgumentException::class.java) { Commands.read(ChangeKind.APP_OP, bad) }
            assertThrows(bad, IllegalArgumentException::class.java) { Commands.read(ChangeKind.STANDBY, bad) }
        }
    }

    @Test fun `builder rejects unapproved targets and requested values`() {
        assertThrows(IllegalArgumentException::class.java) { Commands.apply(ChangeKind.STANDBY, pkg, "10") }
        assertThrows(IllegalArgumentException::class.java) { Commands.apply(ChangeKind.APP_OP, pkg, "allow") }
        assertThrows(IllegalArgumentException::class.java) { Commands.apply(ChangeKind.ANIMATION, "window_animation_scale", "0") }
        assertThrows(IllegalArgumentException::class.java) { Commands.apply(ChangeKind.DISABLE, bixby, "2") }
        assertThrows(IllegalArgumentException::class.java) { Commands.apply(ChangeKind.DISABLE, "com.samsung.android.bixby.agent.evil", "3") }
        assertThrows(IllegalArgumentException::class.java) { Commands.read(ChangeKind.ANIMATION, "peak_refresh_rate") }
    }

    @Test fun `undo refuses priors it cannot restore exactly`() {
        assertThrows(IllegalArgumentException::class.java) { Commands.undo(entry(ChangeKind.APP_OP, pkg, "errored", "ignore")) }
        assertThrows(IllegalArgumentException::class.java) { Commands.undo(entry(ChangeKind.STANDBY, pkg, "999", "45")) }
        assertThrows(IllegalArgumentException::class.java) { Commands.undo(entry(ChangeKind.DISABLE, bixby, "3", "3")) }
        assertThrows(IllegalArgumentException::class.java) { Commands.undo(entry(ChangeKind.ANIMATION, "window_animation_scale", "fast", "0.5")) }
    }

    @Test fun `parse treats failures and error text as unknown`() {
        assertNull(Commands.parse(ChangeKind.APP_OP, ShellResult(1, "RUN_ANY_IN_BACKGROUND: allow", "")))
        assertNull(Commands.parse(ChangeKind.APP_OP, ShellResult(0, "RUN_ANY_IN_BACKGROUND: allow", "java.lang.SecurityException")))
        assertNull(Commands.parse(ChangeKind.STANDBY, ShellResult(0, "Error: unknown package", "")))
        assertEquals("allow", Commands.parse(ChangeKind.APP_OP, ShellResult(0, "RUN_ANY_IN_BACKGROUND: allow", "")))
        assertEquals("0.5", Commands.parse(ChangeKind.ANIMATION, ShellResult(0, "0.5\n", "")))
    }

    @Test fun `display joins argv for previews`() {
        assertEquals("am get-standby-bucket --user 0 $pkg", Commands.read(ChangeKind.STANDBY, pkg).display)
    }
}
