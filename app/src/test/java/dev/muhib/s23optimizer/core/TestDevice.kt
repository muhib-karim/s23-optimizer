package dev.muhib.s23optimizer.core

/**
 * In-memory model of the four device settings the optimizer touches. It executes argv the way
 * Android's `cmd appops`, `am`, `pm`, `dumpsys package` and `settings` would, so tests assert on
 * device state rather than on which mock method was called.
 */
class DeviceShell : ShellRunner {
    val appOps = mutableMapOf<String, String>()
    val buckets = mutableMapOf<String, Int>()
    val enabled = mutableMapOf<String, String>()
    val global = mutableMapOf<String, String>()
    val log = mutableListOf<List<String>>()
    var failMutations = false

    val mutations: List<List<String>> get() = log.filter { it.isMutation() }
    private fun List<String>.isMutation() = when (first()) {
        "cmd" -> this[2] == "set"
        "am" -> this[1] == "set-standby-bucket"
        "pm" -> true
        "settings" -> this[1] != "get"
        else -> false
    }

    override suspend fun run(command: ShellCommand): ShellResult {
        val a = command.args
        log += a
        if (failMutations && a.isMutation()) return ShellResult(255, "", "java.lang.SecurityException: Shell does not have permission")
        return when {
            a.take(3) == listOf("cmd", "appops", "get") -> appOps[a[5]].let { mode ->
                if (mode == null || mode == "default") ShellResult(0, "No operations.\n", "") else ShellResult(0, "RUN_ANY_IN_BACKGROUND: $mode; time=+3d ago\n", "")
            }
            a.take(3) == listOf("cmd", "appops", "set") -> ok { appOps[a[5]] = a[7] }
            a.take(2) == listOf("am", "get-standby-bucket") -> ShellResult(0, "${buckets[a[4]] ?: 10}\n", "")
            a.take(2) == listOf("am", "set-standby-bucket") -> ok {
                buckets[a[4]] = when (a[5]) { "rare" -> 40; "restricted" -> 45; else -> a[5].toInt() }
            }
            a.take(2) == listOf("dumpsys", "package") ->
                ShellResult(0, "Packages:\n  Package [${a[2]}]\n    User 0: ceDataInode=1 installed=true hidden=false suspended=false enabled=${enabled[a[2]] ?: "0"} instant=false\n", "")
            a.take(2) == listOf("pm", "disable-user") -> ok { enabled[a[4]] = "3" }
            a.take(2) == listOf("pm", "enable") -> ok { enabled[a[4]] = "1" }
            a.take(2) == listOf("pm", "default-state") -> ok { enabled[a[4]] = "0" }
            a.take(3) == listOf("settings", "get", "global") -> ShellResult(0, "${global[a[3]] ?: "null"}\n", "")
            a.take(3) == listOf("settings", "put", "global") -> ok { global[a[3]] = a[4] }
            a.take(3) == listOf("settings", "delete", "global") -> ok { global.remove(a[3]) }
            else -> ShellResult(127, "", "unknown command ${command.display}")
        }
    }
    private inline fun ok(block: () -> Unit): ShellResult { block(); return ShellResult(0, "", "") }
}

class MemoryJournal : JournalStore {
    val rows = mutableListOf<JournalEntry>()
    private var clock = 1L
    override suspend fun insert(entry: JournalEntry): Long {
        val id = rows.size.toLong() + 1
        rows += entry.copy(id = id, createdAt = clock++)
        return id
    }
    override suspend fun update(entry: JournalEntry) { rows[rows.indexOfFirst { it.id == entry.id }] = entry }
    override suspend fun entries() = rows.toList()
}

val allowAll: suspend (ChangeKind, String) -> EligibilityResult = { _, _ -> EligibilityResult(true, emptyList()) }
