package dev.muhib.s23optimizer.core

import dev.muhib.s23optimizer.core.agent.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentReadToolsTest {
    private fun app(pkg: String, uid: Int) = AppProfile(pkg, pkg, uid, false, true, 1, null, null, emptyList())
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/power/$name.txt")).readText()

    @Test fun `collection uses fixed reads shares one batterystats dump and bounds appops sample`() = runBlocking {
        val calls = mutableListOf<List<String>>()
        val shell = object : ShellRunner {
            override suspend fun run(command: ShellCommand): ShellResult {
                calls += command.args
                val output = when {
                    "batterystats" in command.args -> fixture("batterystats-charged")
                    "battery" in command.args -> fixture("battery")
                    "procstats" in command.args -> fixture("procstats")
                    "get-standby-bucket" in command.args -> fixture("standby")
                    "appops" in command.args -> fixture("appops")
                    else -> error("Not a read tool")
                }
                return ShellResult(0, output, "")
            }
        }
        val apps = listOf(app("org.example.sync", 10123)) + (1..80).map { app("org.example.app$it", 11000 + it) }
        val result = AgentReadTools(shell).collect(apps)
        assertEquals(7, result.tools.size)
        assertEquals(1, calls.count { "batterystats" in it })
        assertEquals(30, calls.count { "appops" in it })
        assertTrue(result.modelJson().toByteArray().size <= 6000)
        assertTrue(result.tools.getValue("background_restrictions").jsonObject["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test fun `denied reads stay unknown while other tools can finish`() = runBlocking {
        val shell = object : ShellRunner { override suspend fun run(command: ShellCommand) = ShellResult(1, "", "Denied") }
        val result = AgentReadTools(shell).collect(listOf(app("org.example.sync", 10123)))
        assertEquals(JsonNull, result.tools.getValue("battery_overview").jsonObject["levelPct"])
        assertEquals(7, result.tools.size)
    }

    @Test fun `measurement deltas require matching charge window and nondecreasing counters`() {
        fun snapshot(time: Long, clock: String, drain: Double) = AgentSnapshot(time, mapOf("battery_overview" to buildJsonObject {
            put("startClock", clock); put("screenOffDrainMah", drain); put("levelPct", 73.0)
        }))
        val before = snapshot(1, "2026-10-17-08-00-00", 100.0)
        assertEquals(20.0, AgentMeasurements.compare(before, snapshot(2, "2026-10-17-08-00-00", 120.0)).first { it.key == "screenOffDrainMah" }.delta!!, 0.001)
        assertNull(AgentMeasurements.compare(before, snapshot(2, "new-window", 120.0)).first { it.key == "screenOffDrainMah" }.delta)
        assertNull(AgentMeasurements.compare(before, snapshot(2, "2026-10-17-08-00-00", 90.0)).first { it.key == "screenOffDrainMah" }.delta)
        assertTrue(AgentMeasurements.compare(before, before).all { it.delta == null })
    }
}
