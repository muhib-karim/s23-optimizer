package dev.muhib.s23optimizer.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class JournalEngineTest {
    private class MemoryJournal : JournalStore {
        val rows = mutableListOf<JournalEntry>()
        var rejectInsert = false
        override suspend fun insert(entry: JournalEntry): Long {
            check(!rejectInsert) { "Disk unavailable" }
            val id = rows.size.toLong() + 1
            rows += entry.copy(id = id)
            return id
        }
        override suspend fun update(entry: JournalEntry) { rows[rows.indexOfFirst { it.id == entry.id }] = entry }
        override suspend fun entries() = rows.toList()
    }
    private class FakeShell(private val journal: MemoryJournal) : ShellRunner {
        var state = "allow"
        var mutations = 0
        var fail = false
        override suspend fun run(command: ShellCommand): ShellResult {
            if ("get" in command.args) return ShellResult(0, "RUN_ANY_IN_BACKGROUND: $state", "")
            check(journal.rows.isNotEmpty()) { "Mutation ran before durable journal" }
            mutations++
            if (fail) return ShellResult(1, "", "Shell permission revoked")
            state = command.args.last()
            return ShellResult(0, "", "")
        }
    }
    private fun engine(store: MemoryJournal, shell: FakeShell) = ChangeEngine(shell, store) { _, _ -> EligibilityResult(true, emptyList()) }

    @Test fun `journal exists before mutation and undo restores once`() = runBlocking {
        val store = MemoryJournal(); val shell = FakeShell(store); val engine = engine(store, shell)
        val plan = engine.prepare(ChangeKind.APP_OP, "org.example.reader", "ignore").getOrThrow()
        val row = engine.apply(plan).getOrThrow()
        assertEquals(JournalStatus.APPLIED, row.status)
        assertEquals("allow", row.before)
        assertEquals("ignore", shell.state)
        engine.undo(row.id).getOrThrow()
        assertEquals("allow", shell.state)
        assertEquals(JournalStatus.UNDONE, store.rows.single().status)
        engine.undo(row.id).getOrThrow()
        assertEquals(2, shell.mutations)
    }

    @Test fun `stale confirmation and failed journal cannot mutate`() = runBlocking {
        val store = MemoryJournal(); val shell = FakeShell(store); val engine = engine(store, shell)
        val plan = engine.prepare(ChangeKind.APP_OP, "org.example.reader", "ignore").getOrThrow()
        shell.state = "deny"
        assertTrue(engine.apply(plan).isFailure)
        shell.state = "allow"; store.rejectInsert = true
        assertTrue(engine.apply(plan).isFailure)
        assertEquals(0, shell.mutations)
    }

    @Test fun `failed command is logged without claiming success`() = runBlocking {
        val store = MemoryJournal(); val shell = FakeShell(store); val engine = engine(store, shell)
        val plan = engine.prepare(ChangeKind.APP_OP, "org.example.reader", "ignore").getOrThrow()
        shell.fail = true
        val row = engine.apply(plan).getOrThrow()
        assertEquals(JournalStatus.FAILED, row.status)
        assertEquals("allow", shell.state)
        assertTrue(row.detail.contains("Shell permission revoked"))
    }

    @Test fun `crash prepared entry recovers but conflicts remain pending`() = runBlocking {
        val store = MemoryJournal(); val shell = FakeShell(store); val engine = engine(store, shell)
        val id = store.insert(JournalEntry(kind = ChangeKind.APP_OP, target = "org.example.reader", before = "allow", expected = "ignore"))
        shell.state = "deny"
        engine.undo(id).getOrThrow()
        assertEquals(JournalStatus.UNDO_FAILED, store.rows.single().status)
        assertEquals(0, shell.mutations)
        shell.state = "ignore"
        engine.undo(id).getOrThrow()
        assertEquals("allow", shell.state)
        assertEquals(JournalStatus.UNDONE, store.rows.single().status)
    }
}
