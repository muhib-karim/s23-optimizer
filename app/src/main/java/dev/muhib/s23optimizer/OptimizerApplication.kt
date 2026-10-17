package dev.muhib.s23optimizer

import android.app.Application
import androidx.room.Room
import dev.muhib.s23optimizer.core.*
import dev.muhib.s23optimizer.core.agent.AgentReadTools
import dev.muhib.s23optimizer.data.*
import dev.muhib.s23optimizer.platform.*

class OptimizerApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}

class AppGraph(val app: Application) {
    val database = Room.databaseBuilder(app, OptimizerDatabase::class.java, "optimizer.db").build()
    val repository = AuditRepository(database.dao())
    val journal = RoomJournal(database.dao())
    val access = ShizukuAccess()
    val shell = ShizukuShell(access)
    val inventory = AppInventory(app)
    val audit = AuditCollector(app, access, shell, inventory)
    val preferences = app.getSharedPreferences("preferences", Application.MODE_PRIVATE)
    val agentSettings = AgentSettings(app)
    val agentReads = AgentReadTools(shell)
    val engine = ChangeEngine(shell, journal) { kind, target ->
        if (kind == ChangeKind.ANIMATION) EligibilityResult(true, emptyList())
        else {
            val current = inventory.load().apps.firstOrNull { it.packageName == target }
            if (current == null) EligibilityResult(false, listOf("Package no longer installed or visible."))
            else Eligibility.check(current, kind, System.currentTimeMillis(), optIn = true)
        }
    }
}
