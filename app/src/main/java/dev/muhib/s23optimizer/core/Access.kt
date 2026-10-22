package dev.muhib.s23optimizer.core

enum class AccessState { NOT_INSTALLED, NOT_RUNNING, PERMISSION_NEEDED, PERMISSION_DENIED, UNSUPPORTED_MODE, READY }
enum class AccessAction { INSTALL_SHIZUKU, OPEN_SHIZUKU, REQUEST_PERMISSION, NONE }

data class ShellAccess(
    val state: AccessState = AccessState.NOT_RUNNING,
    val message: String = "Shizuku is not running.",
    val action: AccessAction = AccessAction.OPEN_SHIZUKU
) {
    val ready: Boolean get() = state == AccessState.READY
    val running: Boolean get() = state !in setOf(AccessState.NOT_INSTALLED, AccessState.NOT_RUNNING)
    val granted: Boolean get() = state == AccessState.UNSUPPORTED_MODE || state == AccessState.READY
    companion object {
        val Ready = ShellAccess(AccessState.READY, "Shizuku connected • shell permission granted", AccessAction.NONE)
    }
}

/** The facts about Shizuku the app can observe. Production wraps the Shizuku binder; tests fake it. */
interface ShizukuProbe {
    fun installed(): Boolean
    fun running(): Boolean
    fun granted(): Boolean
    /** Shizuku's "deny and don't ask again": a new request shows no dialog. */
    fun deniedPermanently(): Boolean
    fun serverUid(): Int
    fun ownerProfile(): Boolean
}

object AccessResolver {
    private const val SHELL_UID = 2000
    fun resolve(probe: ShizukuProbe): ShellAccess = runCatching {
        when {
            !probe.running() && !probe.installed() -> ShellAccess(AccessState.NOT_INSTALLED,
                "Shizuku is not installed. The read-only audit still works; changes stay disabled. Install Shizuku from Google Play, then pair it.", AccessAction.INSTALL_SHIZUKU)
            !probe.running() -> ShellAccess(AccessState.NOT_RUNNING,
                "Shizuku is installed but not running. Open Shizuku and tap Start (Wireless debugging); this is needed again after every reboot.", AccessAction.OPEN_SHIZUKU)
            !probe.granted() && probe.deniedPermanently() -> ShellAccess(AccessState.PERMISSION_DENIED,
                "Shizuku permission was denied. Allow S23 Optimizer in Shizuku → Authorized applications; changes stay disabled until then.", AccessAction.OPEN_SHIZUKU)
            !probe.granted() -> ShellAccess(AccessState.PERMISSION_NEEDED,
                "Shizuku is running; S23 Optimizer needs its permission before it can read or change settings.", AccessAction.REQUEST_PERMISSION)
            probe.serverUid() != SHELL_UID || !probe.ownerProfile() -> ShellAccess(AccessState.UNSUPPORTED_MODE,
                "Use Shizuku in wireless-debugging shell mode on the owner profile. Root mode and secondary profiles are not supported.", AccessAction.OPEN_SHIZUKU)
            else -> ShellAccess.Ready
        }
    }.getOrElse { ShellAccess(AccessState.NOT_RUNNING, "Shizuku unavailable: ${it.message}", AccessAction.OPEN_SHIZUKU) }
}
