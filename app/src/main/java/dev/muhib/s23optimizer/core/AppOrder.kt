package dev.muhib.s23optimizer.core

object AppOrder {
    /** Estimated UID drain first, then foreground time; unknown values sort last, never as zero evidence. */
    fun byEvidence(apps: List<AppProfile>): List<AppProfile> =
        apps.sortedWith(compareByDescending<AppProfile> { it.estimatedMah ?: -1.0 }.thenByDescending { it.foregroundMs ?: -1L })
}
