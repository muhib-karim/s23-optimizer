package dev.muhib.s23optimizer.core

/** Exact packages only. Deliberately excludes platform, input, calls, security and companion frameworks. */
object BloatAllowlist {
    val entries = linkedMapOf(
        "com.samsung.android.bixby.agent" to "Bixby Voice: voice assistant and voice routines may stop working.",
        "com.samsung.android.app.spage" to "Samsung Free: removes the Samsung content feed.",
        "com.samsung.android.arzone" to "AR Zone: AR stickers and related shortcuts become unavailable.",
        "com.samsung.android.game.gamehome" to "Gaming Hub / Game Launcher (optional): loses its game library interface. Does not disable Game Optimizing Service.",
        "com.facebook.appmanager" to "Meta App Manager stub: background Meta app management unavailable.",
        "com.facebook.services" to "Meta Services stub: Meta integration may be affected.",
        "com.facebook.system" to "Meta App Installer stub: automatic Meta app installation unavailable."
    )
}

data class EligibilityResult(val allowed: Boolean, val reasons: List<String>)

object Eligibility {
    private val exactProtected = setOf(
        "android", "dev.muhib.s23optimizer", "moe.shizuku.privileged.api", "com.android.systemui",
        "com.sec.android.app.launcher", "com.samsung.android.honeyboard", "com.google.android.inputmethod.latin",
        "com.samsung.android.messaging", "com.google.android.apps.messaging", "com.android.phone",
        "com.samsung.android.dialer", "com.samsung.android.contacts", "com.sec.android.app.clockpackage",
        "com.sec.android.app.camera", "com.samsung.android.oneconnect", "com.samsung.android.app.watchmanager",
        "com.google.android.apps.maps", "com.waze", "com.samsung.android.game.gos"
    )
    private val sensitiveFragments = listOf("knox", "security", "keychain", "permissioncontroller", "wear", "watchmanager", "smartthings", "oneconnect", "bank", "wallet", "payment", "authenticator", "vpn", "navigation", "launcher", "keyboard", "inputmethod", "dialer", "camera", "alarm", "accessibility", "health")
    fun isAlwaysProtected(pkg: String): Boolean = pkg in exactProtected || sensitiveFragments.any { pkg.lowercase().contains(it) }
    fun check(app: AppProfile, kind: ChangeKind, now: Long, optIn: Boolean = false): EligibilityResult {
        val reasons = mutableListOf<String>()
        if (isAlwaysProtected(app.packageName)) reasons += "Protected package or sensitive app category."
        reasons += app.protectedReasons
        if (!app.enabled) reasons += "Already disabled."
        if (kind == ChangeKind.DISABLE) {
            if (app.packageName !in BloatAllowlist.entries) reasons += "Not on the exact documented disable allowlist."
            if (!optIn) reasons += "Explicit allowlist opt-in is required."
        } else if (app.system) reasons += "Background restrictions apply only to user-installed apps."
        val last = app.lastUsedMs
        if (last == null || last <= 0) reasons += "No reliable last-use evidence; missing usage is not proof of idleness."
        else if (last > now || now - last < 14 * DAY_MS) reasons += "Used within 14 days or timestamp is invalid."
        if (app.firstInstallMs <= 0 || app.firstInstallMs > now || now - app.firstInstallMs < 14 * DAY_MS) reasons += "Not verified installed for at least 14 days."
        return EligibilityResult(reasons.isEmpty(), reasons.distinct())
    }
}
