package dev.muhib.s23optimizer.core

const val DAY_MS = 86_400_000L

data class Metric(
    val id: String,
    val label: String,
    val value: Double?,
    val unit: String,
    val source: String,
    val note: String = "",
    val cumulative: Boolean = false,
    val display: String? = null
)

data class AppProfile(
    val packageName: String,
    val label: String,
    val uid: Int,
    val system: Boolean,
    val enabled: Boolean,
    val firstInstallMs: Long,
    val lastUsedMs: Long?,
    val foregroundMs: Long?,
    val protectedReasons: List<String>,
    val estimatedMah: Double? = null,
    val partialWakeMs: Long? = null,
    val wakeupAlarms: Long? = null,
    val standbyBucket: Int? = null,
    val startupCandidate: Boolean? = null,
    val standbySource: String = "unavailable",
    val drainSource: String = "unavailable"
)

data class AuditSnapshot(
    val id: Long = 0,
    val takenAt: Long,
    val role: String,
    val metrics: List<Metric>,
    val apps: List<AppProfile> = emptyList(),
    val notes: List<String> = emptyList()
)

enum class ChangeKind { DISABLE, APP_OP, STANDBY, ANIMATION }
enum class JournalStatus { PREPARED, APPLIED, FAILED, NEEDS_REVIEW, UNDONE, UNDO_FAILED }

data class JournalEntry(
    val id: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val kind: ChangeKind,
    val target: String,
    val before: String,
    val expected: String,
    val status: JournalStatus = JournalStatus.PREPARED,
    val detail: String = "Prepared; the command may not have run. Undo can inspect and recover.",
    val completedAt: Long? = null
)

data class ShellCommand(val args: List<String>) {
    val display: String get() = args.joinToString(" ")
}
data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val success: Boolean get() = exitCode == 0
}
interface ShellRunner { suspend fun run(command: ShellCommand): ShellResult }
interface JournalStore {
    suspend fun insert(entry: JournalEntry): Long
    suspend fun update(entry: JournalEntry)
    suspend fun entries(): List<JournalEntry>
}
