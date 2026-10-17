package dev.muhib.s23optimizer.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgentJournalTest {
    private class Store : JournalStore {
        val rows = mutableListOf<JournalEntry>()
        var failInsert = false
        override suspend fun insert(entry: JournalEntry): Long { check(!failInsert); rows += entry.copy(id = 1); return 1 }
        override suspend fun update(entry: JournalEntry) { rows[0] = entry }
        override suspend fun entries() = rows.toList()
    }

    @Test fun `force stop journals before execution is explicit non revertible and never restarts`() = runBlocking {
        val store = Store(); val commands = mutableListOf<List<String>>()
        val shell = object : ShellRunner {
            override suspend fun run(command: ShellCommand): ShellResult {
                commands += command.args
                check(store.rows.isNotEmpty())
                assertTrue(store.rows.single().detail.contains("Cannot undo"))
                return ShellResult(0, "", "")
            }
        }
        val engine = ChangeEngine(shell, store) { _, _ -> EligibilityResult(true, emptyList()) }
        val plan = engine.prepare(ChangeKind.FORCE_STOP, "org.example.sync", "stopped").getOrThrow()
        assertFalse(plan.revertible)
        assertTrue(plan.undoCommands.isEmpty())
        val row = engine.apply(plan).getOrThrow()
        assertEquals(JournalStatus.APPLIED, row.status)
        assertEquals(listOf(listOf("am", "force-stop", "--user", "0", "org.example.sync")), commands)
        assertTrue(engine.undo(row.id).isFailure)
        assertTrue(engine.undoAll().isEmpty())
        assertEquals(1, commands.size)
    }

    @Test fun `failed journal or revoked policy prevents force stop`() = runBlocking {
        val store = Store(); var allowed = true; var writes = 0
        val engine = ChangeEngine(object : ShellRunner {
            override suspend fun run(command: ShellCommand): ShellResult { writes++; return ShellResult(0, "", "") }
        }, store) { _, _ -> EligibilityResult(allowed, listOf("Not allowed")) }
        val plan = engine.prepare(ChangeKind.FORCE_STOP, "org.example.sync", "stopped").getOrThrow()
        store.failInsert = true
        assertTrue(engine.apply(plan).isFailure)
        store.failInsert = false; allowed = false
        assertTrue(engine.apply(plan).isFailure)
        assertEquals(0, writes)
    }

    @Test fun `interrupted force stop remains journaled uncertain without suggesting undo`() = runBlocking {
        val store = Store()
        val engine = ChangeEngine(object : ShellRunner {
            override suspend fun run(command: ShellCommand): ShellResult = error("Lost connection")
        }, store) { _, _ -> EligibilityResult(true, emptyList()) }
        val plan = engine.prepare(ChangeKind.FORCE_STOP, "org.example.sync", "stopped").getOrThrow()
        val row = engine.apply(plan).getOrThrow()
        assertEquals(JournalStatus.NEEDS_REVIEW, row.status)
        assertTrue(row.detail.contains("Cannot undo"))
        assertTrue(UndoPlanner.pending(store.rows).isEmpty())
    }
}
