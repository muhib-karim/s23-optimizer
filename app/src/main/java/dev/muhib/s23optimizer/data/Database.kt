package dev.muhib.s23optimizer.data

import androidx.room.*
import dev.muhib.s23optimizer.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "snapshots")
data class SnapshotEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val takenAt: Long, val role: String, val payload: String)

@Entity(tableName = "journal")
data class JournalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val kind: String,
    val target: String,
    val beforeValue: String,
    val expectedValue: String,
    val status: String,
    val detail: String,
    val completedAt: Long?
)

@Dao
interface OptimizerDao {
    @Insert suspend fun insertSnapshot(snapshot: SnapshotEntity): Long
    @Query("SELECT * FROM snapshots ORDER BY takenAt DESC, id DESC") fun observeSnapshots(): Flow<List<SnapshotEntity>>
    @Query("SELECT * FROM snapshots ORDER BY takenAt DESC, id DESC LIMIT 1") suspend fun latest(): SnapshotEntity?
    @Insert suspend fun insertJournal(entry: JournalEntity): Long
    @Update suspend fun updateJournal(entry: JournalEntity)
    @Query("SELECT * FROM journal ORDER BY createdAt DESC, id DESC") suspend fun journal(): List<JournalEntity>
    @Query("SELECT * FROM journal ORDER BY createdAt DESC, id DESC") fun observeJournal(): Flow<List<JournalEntity>>
}

@Database(entities = [SnapshotEntity::class, JournalEntity::class], version = 1, exportSchema = true)
abstract class OptimizerDatabase : RoomDatabase() { abstract fun dao(): OptimizerDao }

class AuditRepository(private val dao: OptimizerDao) {
    val snapshots = dao.observeSnapshots().map { rows -> rows.map { SnapshotCodec.decode(it) } }
    suspend fun save(snapshot: AuditSnapshot): Long = dao.insertSnapshot(SnapshotEntity(takenAt = snapshot.takenAt, role = snapshot.role, payload = SnapshotCodec.encode(snapshot)))
}

class RoomJournal(private val dao: OptimizerDao) : JournalStore {
    val flow = dao.observeJournal().map { rows -> rows.map { it.domain() } }
    override suspend fun insert(entry: JournalEntry): Long = dao.insertJournal(entry.entity())
    override suspend fun update(entry: JournalEntry) = dao.updateJournal(entry.entity())
    override suspend fun entries(): List<JournalEntry> = dao.journal().map { it.domain() }
    private fun JournalEntry.entity() = JournalEntity(id, createdAt, kind.name, target, before, expected, status.name, detail, completedAt)
    private fun JournalEntity.domain() = JournalEntry(id, createdAt, ChangeKind.valueOf(kind), target, beforeValue, expectedValue, JournalStatus.valueOf(status), detail, completedAt)
}
