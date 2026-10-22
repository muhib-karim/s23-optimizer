package dev.muhib.s23optimizer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasuredTest {
    private val hour = 3_600_000L
    private val t0 = 1_800_000_000_000L
    private fun discharging(h: Double, level: Double) = BatterySample(t0 + (h * hour).toLong(), level, true)
    private fun charging(h: Double, level: Double) = BatterySample(t0 + (h * hour).toLong(), level, false)

    @Test fun `steady discharge gives percent per hour`() {
        val rate = DrainMeter.rate(listOf(discharging(0.0, 80.0), discharging(10.0, 70.0)))!!
        assertEquals(1.0, rate.pctPerHour, 1e-9)
        assertEquals(10.0, rate.hours, 1e-9)
        assertEquals(1, rate.windows)
    }

    @Test fun `a level rise between samples means a hidden charge and drops that window`() {
        val rate = DrainMeter.rate(listOf(discharging(0.0, 80.0), discharging(2.0, 90.0), discharging(4.0, 85.0)))!!
        assertEquals(2.5, rate.pctPerHour, 1e-9)
        assertEquals(1, rate.windows)
    }

    @Test fun `windows touching a charging sample are not drain`() {
        assertNull(DrainMeter.rate(listOf(discharging(0.0, 80.0), charging(1.0, 79.0), discharging(10.0, 70.0))))
    }

    @Test fun `gaps longer than twelve hours are ignored`() {
        assertNull(DrainMeter.rate(listOf(discharging(0.0, 80.0), discharging(20.0, 50.0))))
    }

    @Test fun `less than one hour of valid windows is not enough data`() {
        assertNull(DrainMeter.rate(listOf(discharging(0.0, 80.0), discharging(0.5, 79.0))))
        assertNull(DrainMeter.rate(emptyList()))
        assertNull(DrainMeter.rate(listOf(discharging(0.0, 80.0))))
    }

    @Test fun `samples closer than five minutes do not form a window`() {
        assertNull(DrainMeter.rate(listOf(discharging(0.0, 80.0), discharging(0.05, 80.0), discharging(0.06, 79.0))))
    }

    @Test fun `unsorted samples are ordered by time`() {
        val rate = DrainMeter.rate(listOf(discharging(4.0, 76.0), discharging(0.0, 80.0)))!!
        assertEquals(1.0, rate.pctPerHour, 1e-9)
    }

    @Test fun `window straddling the first tweak counts on neither side`() {
        val samples = listOf(discharging(0.0, 80.0), discharging(5.0, 75.0), discharging(10.0, 70.0))
        val panel = MeasuredValue.build(samples, emptyList(), MeasureWindow(t0 + 6 * hour, null))
        assertEquals(1.0, panel.before.drain!!.pctPerHour, 1e-9)
        assertEquals(5.0, panel.before.drain!!.hours, 1e-9)
        assertNull(panel.after.drain)
        assertNull(panel.drainDelta)
    }

    @Test fun `before and after deltas use only measured phases`() {
        val samples = listOf(discharging(0.0, 90.0), discharging(4.0, 82.0), discharging(10.0, 80.0), discharging(14.0, 76.0))
        val starts = listOf(StartSample(t0 + hour, 500), StartSample(t0 + 2 * hour, 300), StartSample(t0 + 3 * hour, 400), StartSample(t0 + 11 * hour, 350), StartSample(t0 + 12 * hour, 250))
        val panel = MeasuredValue.build(samples, starts, MeasureWindow(t0 + 9 * hour, null))
        assertEquals(2.0, panel.before.drain!!.pctPerHour, 1e-9)
        assertEquals(1.0, panel.after.drain!!.pctPerHour, 1e-9)
        assertEquals(-1.0, panel.drainDelta!!, 1e-9)
        assertEquals(400L, panel.before.startMedianMs)
        assertEquals(3, panel.before.starts)
        assertEquals(300L, panel.after.startMedianMs)
        assertEquals(-100L, panel.startDeltaMs)
    }

    @Test fun `without an applied tweak everything is baseline`() {
        val panel = MeasuredValue.build(listOf(discharging(0.0, 80.0), discharging(2.0, 78.0)), listOf(StartSample(t0, 420)), MeasureWindow(null, null))
        assertEquals(1.0, panel.before.drain!!.pctPerHour, 1e-9)
        assertEquals(420L, panel.before.startMedianMs)
        assertNull(panel.after.drain)
        assertNull(panel.after.startMedianMs)
        assertEquals(0, panel.after.starts)
        assertNull(panel.startDeltaMs)
    }

    private fun row(id: Long, at: Long, status: JournalStatus, done: Long? = null) = JournalEntry(id = id, createdAt = at, kind = ChangeKind.APP_OP,
        target = "org.example.reader", before = "allow", expected = "ignore", status = status, completedAt = done)

    @Test fun `window starts at the first change that took effect and stays open while one is in effect`() {
        assertEquals(MeasureWindow(10, null), MeasuredValue.window(listOf(row(1, 10, JournalStatus.UNDONE, 50), row(2, 20, JournalStatus.FAILED), row(3, 30, JournalStatus.APPLIED))))
        assertEquals(MeasureWindow(10, null), MeasuredValue.window(listOf(row(1, 10, JournalStatus.UNDONE, 50), row(2, 30, JournalStatus.UNDO_FAILED))))
        assertEquals(MeasureWindow(30, null), MeasuredValue.window(listOf(row(1, 20, JournalStatus.FAILED), row(2, 30, JournalStatus.NEEDS_REVIEW))))
    }

    @Test fun `restoring everything closes the window at the last restore instead of erasing it`() {
        assertEquals(MeasureWindow(10, 70), MeasuredValue.window(listOf(row(1, 10, JournalStatus.UNDONE, 50), row(2, 30, JournalStatus.UNDONE, 70))))
    }

    @Test fun `no change that took effect means no window`() {
        assertEquals(MeasureWindow(null, null), MeasuredValue.window(listOf(row(1, 10, JournalStatus.FAILED), row(2, 20, JournalStatus.PREPARED))))
        assertEquals(MeasureWindow(null, null), MeasuredValue.window(emptyList()))
    }

    @Test fun `samples after a restore count as neither before nor after`() {
        val samples = listOf(discharging(0.0, 90.0), discharging(2.0, 88.0), discharging(3.0, 86.0), discharging(5.0, 82.0), discharging(6.0, 81.0), discharging(9.0, 60.0))
        val panel = MeasuredValue.build(samples, listOf(StartSample(t0 + 8 * hour, 999)), MeasureWindow(t0 + (2.5 * hour).toLong(), t0 + (6.5 * hour).toLong()))
        assertEquals(1.0, panel.before.drain!!.pctPerHour, 1e-9)
        assertEquals(5.0 / 3.0, panel.after.drain!!.pctPerHour, 1e-9)
        assertEquals(0, panel.after.starts)
        val csv = MeasuredValue.csv(panel, samples, listOf(StartSample(t0 + 8 * hour, 999))).lines()
        assertTrue(csv.contains("battery,restored,${t0 + 9 * hour},60.00,%,discharging"))
        assertTrue(csv.contains("start,restored,${t0 + 8 * hour},999,ms,cold start"))
    }

    @Test fun `battery sample comes from a snapshot only when level is measured`() {
        fun snap(level: Double?, state: String?) = AuditSnapshot(takenAt = t0, role = "SAMPLE", metrics = listOfNotNull(
            Metric("battery.level_pct", "Battery level", level, "%", "ACTION_BATTERY_CHANGED"),
            state?.let { Metric("battery.charging", "Charging state", if (it == "Charging") 1.0 else 0.0, "boolean", "ACTION_BATTERY_CHANGED", display = it) }
        ))
        assertEquals(BatterySample(t0, 64.0, true), BatterySample.from(snap(64.0, "Discharging")))
        assertEquals(false, BatterySample.from(snap(64.0, "Not charging"))!!.discharging)
        assertEquals(false, BatterySample.from(snap(64.0, null))!!.discharging)
        assertNull(BatterySample.from(snap(null, "Discharging")))
    }

    @Test fun `csv export has a header summary rows and every raw sample`() {
        val samples = listOf(discharging(0.0, 80.0), discharging(10.0, 70.0))
        val starts = listOf(StartSample(t0 + 11 * hour, 410))
        val panel = MeasuredValue.build(samples, starts, MeasureWindow(t0 + (10.5 * hour).toLong(), null))
        val lines = MeasuredValue.csv(panel, samples, starts).lines()
        assertEquals("kind,phase,time_ms,value,unit,detail", lines.first())
        assertTrue(lines.contains("summary,before,,1.00,%/h,discharge over 10.0 h in 1 window(s)"))
        assertTrue(lines.contains("summary,after,,,%/h,not enough data"))
        assertTrue(lines.contains("summary,after,,410,ms,median cold start of 1 launch(es)"))
        assertTrue(lines.contains("battery,before,$t0,80.00,%,discharging"))
        assertTrue(lines.contains("start,after,${t0 + 11 * hour},410,ms,cold start"))
        assertEquals(1 + 4 + samples.size + starts.size, lines.size)
    }
}

class StartLogTest {
    @Test fun `round trips and keeps only the newest entries`() {
        var text = ""
        for (i in 1..60) text = StartLog.append(text, StartSample(i.toLong(), 300L + i))
        val samples = StartLog.decode(text)
        assertEquals(StartLog.MAX, samples.size)
        assertEquals(StartSample(11, 311), samples.first())
        assertEquals(StartSample(60, 360), samples.last())
    }

    @Test fun `corrupt or implausible entries are skipped not zeroed`() {
        assertEquals(listOf(StartSample(5, 420)), StartLog.decode("x,1;5,420;7;8,-3;9,999999;;"))
        assertTrue(StartLog.decode("").isEmpty())
    }
}
