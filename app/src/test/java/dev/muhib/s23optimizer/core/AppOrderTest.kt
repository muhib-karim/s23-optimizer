package dev.muhib.s23optimizer.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AppOrderTest {
    private fun app(pkg: String, mah: Double? = null, foreground: Long? = null) = AppProfile(
        packageName = pkg, label = pkg, uid = 10_000, system = false, enabled = true,
        firstInstallMs = 1, lastUsedMs = null, foregroundMs = foreground, protectedReasons = emptyList(), estimatedMah = mah
    )

    // Regression: Audit details crashed when some apps had foreground time and others had none.
    @Test fun `mixed known and unknown foreground time sorts without crashing`() {
        val sorted = AppOrder.byEvidence(listOf(app("a.none"), app("b.fg", foreground = 5_000), app("c.fg", foreground = 9_000), app("d.none")))
        assertEquals(listOf("c.fg", "b.fg", "a.none", "d.none"), sorted.map { it.packageName })
    }

    @Test fun `estimated drain ranks first and unknown drain sorts last`() {
        val sorted = AppOrder.byEvidence(listOf(app("a.fg", foreground = 99_000), app("b.mah", mah = 1.5), app("c.mah", mah = 12.0)))
        assertEquals(listOf("c.mah", "b.mah", "a.fg"), sorted.map { it.packageName })
    }
}
