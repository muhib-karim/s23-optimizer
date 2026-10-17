package dev.muhib.s23optimizer.core

import dev.muhib.s23optimizer.core.power.*
import org.junit.Assert.*
import org.junit.Test

class PowerParserTest {
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/power/$name.txt")).readText()
    private val packages = mapOf(10123 to listOf("org.example.sync", "org.example.shared"), 10124 to listOf("org.example.music"))

    @Test fun `overview uses measured units and never infers capacity health`() {
        val value = PowerParsers.batteryOverview(fixture("battery"), fixture("batterystats-charged"))
        assertEquals(73.0, value.levelPct!!, 0.001)
        assertEquals("good", value.health)
        assertEquals(32.4, value.temperatureC!!, 0.001)
        assertEquals(100.0, value.screenOnDrainMah!!, 0.001)
        assertEquals(210.0, value.screenOffDrainMah!!, 0.001)
        assertEquals(11_525_120L, value.onBatteryMs)
        assertEquals("2026-10-17-08-00-00", value.startClock)
    }

    @Test fun `drainers decode work profile and shared UIDs without double counting`() {
        val rows = PowerParsers.topDrainers(fixture("batterystats-charged"), packages)
        assertEquals(listOf(10123, 10124, 1000, 1010125), rows.map { it.uid })
        assertEquals(123.45, rows.first().estimatedMah, 0.001)
        assertEquals(listOf("org.example.sync", "org.example.shared"), rows.first().packages)
        assertTrue(rows.last().packages.isEmpty())
        assertEquals(42.0, PowerParsers.topDrainers(fixture("batterystats-oneui"), packages).single().estimatedMah, 0.001)
    }

    @Test fun `partial locks retain name duration count and stop at unrelated sections`() {
        val rows = PowerParsers.wakelocks(fixture("batterystats-charged"))
        assertEquals(4, rows.size)
        assertEquals("*job*/org.example.sync/.Upload", rows.first().name)
        assertEquals(10123, rows.first().uid)
        assertEquals(3_723_004L, rows.first().totalTimeMs)
        assertEquals(12L, rows.first().count)
        val samsung = PowerParsers.wakelocks(fixture("batterystats-oneui"))
        assertEquals(1, samsung.size)
        assertEquals(123_000L, samsung.single().totalTimeMs)
    }

    @Test fun `alarm and job counts aggregate independently without missing becoming zero`() {
        val rows = PowerParsers.alarmsJobs(fixture("alarms-jobs"))
        assertEquals(14L, rows.first { it.uid == 10123 }.alarmCount)
        assertEquals(8L, rows.first { it.uid == 10123 }.jobCount)
        assertEquals(0L, rows.first { it.uid == 10124 }.alarmCount)
        assertNull(rows.first { it.uid == 10124 }.jobCount)
    }

    @Test fun `shell variants distinguish default unknown and restriction`() {
        assertEquals(mapOf("org.example.sync" to 40, "org.example.music" to 20, "org.example.reader" to 45), PowerParsers.standbyBuckets(fixture("standby")))
        assertEquals(45, PowerParsers.standbyBucket(fixture("standby-single")))
        assertEquals(40, PowerParsers.standbyBucket("org.example.sync: bucket=40"))
        assertEquals(20, PowerParsers.standbyBucket("org.example.sync: 20"))
        assertNull(PowerParsers.standbyBucket("Permission Denial: bucket=40"))
        assertNull(PowerParsers.standbyBucket("garbage bucket=40"))
        assertEquals("ignore", PowerParsers.backgroundRestriction(fixture("appops")))
        assertEquals("default", PowerParsers.backgroundRestriction(fixture("appops-default")))
        assertEquals("deny", PowerParsers.backgroundRestriction("Uid mode: RUN_ANY_IN_BACKGROUND: deny"))
        assertNull(PowerParsers.backgroundRestriction("RUN_ANY_IN_BACKGROUND: unexpected"))
    }

    @Test fun `procstats reports sampled average PSS with separate background observations`() {
        val rows = PowerParsers.procstats(fixture("procstats"))
        assertEquals(2, rows.size)
        assertEquals("org.example.sync", rows.first().process)
        assertEquals(184320.0, rows.first().averagePssKb!!, 0.001)
        assertEquals(143360.0, rows.first().backgroundPssKb!!, 0.001)
        assertTrue(PowerParsers.procstats("  * org.example.sync / u0a123 / v42:\n    TOTAL: 80% (100MB-1e999MB-240MB/70MB-100MB-160MB)\n").isEmpty())
    }

    @Test fun `bad missing denied and overflow input never throws or fabricates data`() {
        for (text in listOf("", fixture("denied"), "garbage", "u999999999999a99:\nWake lock x: -2h partial (3 times)")) {
            assertNull(PowerParsers.batteryOverview(text, text).levelPct)
            assertTrue(PowerParsers.topDrainers(text, packages).isEmpty())
            assertTrue(PowerParsers.wakelocks(text).isEmpty())
            assertTrue(PowerParsers.alarmsJobs(text).isEmpty())
            assertTrue(PowerParsers.procstats(text).isEmpty())
            assertTrue(PowerParsers.standbyBuckets(text).isEmpty())
            assertNull(PowerParsers.backgroundRestriction(text))
        }
    }
}
