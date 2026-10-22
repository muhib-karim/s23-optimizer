package dev.muhib.s23optimizer.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessTest {
    private class Probe(
        var installed: Boolean = true,
        var running: Boolean = true,
        var granted: Boolean = true,
        var denied: Boolean = false,
        var uid: Int = 2000,
        var owner: Boolean = true,
        var crash: Boolean = false
    ) : ShizukuProbe {
        private fun <T> guard(value: T): T = value.also { check(!crash) { "binder died" } }
        override fun installed() = guard(installed)
        override fun running() = guard(running)
        override fun granted() = guard(granted)
        override fun deniedPermanently() = guard(denied)
        override fun serverUid() = guard(uid)
        override fun ownerProfile() = guard(owner)
    }

    @Test fun `shizuku app missing blocks changes and offers install`() {
        val access = AccessResolver.resolve(Probe(installed = false, running = false, granted = false))
        assertEquals(AccessState.NOT_INSTALLED, access.state)
        assertEquals(AccessAction.INSTALL_SHIZUKU, access.action)
        assertFalse(access.ready)
        assertTrue(access.message.contains("not installed"))
    }

    @Test fun `installed but stopped asks to start shizuku`() {
        val access = AccessResolver.resolve(Probe(running = false, granted = false))
        assertEquals(AccessState.NOT_RUNNING, access.state)
        assertEquals(AccessAction.OPEN_SHIZUKU, access.action)
        assertTrue(access.message.contains("reboot"))
        assertFalse(access.running)
    }

    @Test fun `running without permission offers the permission request`() {
        val access = AccessResolver.resolve(Probe(granted = false))
        assertEquals(AccessState.PERMISSION_NEEDED, access.state)
        assertEquals(AccessAction.REQUEST_PERMISSION, access.action)
        assertTrue(access.running)
        assertFalse(access.granted)
    }

    @Test fun `permanently denied permission points to shizuku instead of re-asking`() {
        val access = AccessResolver.resolve(Probe(granted = false, denied = true))
        assertEquals(AccessState.PERMISSION_DENIED, access.state)
        assertEquals(AccessAction.OPEN_SHIZUKU, access.action)
        assertTrue(access.message.contains("denied"))
        assertFalse(access.ready)
    }

    @Test fun `root mode or secondary profile is unsupported`() {
        assertEquals(AccessState.UNSUPPORTED_MODE, AccessResolver.resolve(Probe(uid = 0)).state)
        assertEquals(AccessState.UNSUPPORTED_MODE, AccessResolver.resolve(Probe(owner = false)).state)
        assertFalse(AccessResolver.resolve(Probe(uid = 0)).ready)
    }

    @Test fun `shell mode on the owner profile is ready`() {
        val access = AccessResolver.resolve(Probe())
        assertEquals(AccessState.READY, access.state)
        assertEquals(AccessAction.NONE, access.action)
        assertTrue(access.ready)
    }

    @Test fun `a throwing binder is reported as not running with the cause`() {
        val access = AccessResolver.resolve(Probe(crash = true))
        assertEquals(AccessState.NOT_RUNNING, access.state)
        assertTrue(access.message.contains("binder died"))
    }

    @Test fun `engine refuses review apply and undo without access and never touches shell or journal`() = runBlocking {
        val device = DeviceShell()
        val journal = MemoryJournal()
        var access = ShellAccess.Ready
        val engine = ChangeEngine(device, journal, { access }, allowAll)
        val plan = engine.prepare(ChangeKind.APP_OP, "org.example.reader", "ignore").getOrThrow()
        val row = engine.apply(plan).getOrThrow()
        device.log.clear()

        for (state in AccessState.entries - AccessState.READY) {
            access = AccessResolver.resolve(object : ShizukuProbe {
                override fun installed() = state != AccessState.NOT_INSTALLED
                override fun running() = state !in setOf(AccessState.NOT_INSTALLED, AccessState.NOT_RUNNING)
                override fun granted() = state == AccessState.UNSUPPORTED_MODE
                override fun deniedPermanently() = state == AccessState.PERMISSION_DENIED
                override fun serverUid() = if (state == AccessState.UNSUPPORTED_MODE) 0 else 2000
                override fun ownerProfile() = true
            })
            assertEquals(state, access.state)
            val reviewed = engine.prepare(ChangeKind.STANDBY, "org.example.reader", "45")
            assertEquals(access.message, reviewed.exceptionOrNull()?.message)
            assertTrue(engine.apply(plan).isFailure)
            assertTrue(engine.undo(row.id).isFailure)
            assertTrue(engine.undoAll().single().isFailure)
        }
        assertTrue(device.log.isEmpty())
        assertEquals(JournalStatus.APPLIED, journal.rows.single().status)
    }
}
