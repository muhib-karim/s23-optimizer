package dev.muhib.s23optimizer.core

object Commands {
    val animationKeys = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
    private fun target(kind: ChangeKind, value: String) {
        if (kind == ChangeKind.ANIMATION) require(value in animationKeys) { "Unsupported animation setting" }
        else require(value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+"))) { "Invalid package name" }
    }
    fun read(kind: ChangeKind, value: String): ShellCommand {
        target(kind, value)
        return ShellCommand(when (kind) {
            ChangeKind.APP_OP -> listOf("cmd", "appops", "get", "--user", "0", value, "RUN_ANY_IN_BACKGROUND")
            ChangeKind.STANDBY -> listOf("am", "get-standby-bucket", "--user", "0", value)
            ChangeKind.DISABLE -> listOf("dumpsys", "package", value)
            ChangeKind.ANIMATION -> listOf("settings", "get", "global", value)
            ChangeKind.FORCE_STOP -> error("Force-stop has no reversible value to read.")
        })
    }
    fun parse(kind: ChangeKind, result: ShellResult): String? {
        if (!result.success || result.stderr.contains("Exception", true) || result.stdout.contains("Error:", true)) return null
        return when (kind) {
            ChangeKind.APP_OP -> ShellParsers.appOp(result.stdout)
            ChangeKind.STANDBY -> ShellParsers.bucket(result.stdout)
            ChangeKind.DISABLE -> ShellParsers.enabled(result.stdout)
            ChangeKind.ANIMATION -> ShellParsers.animation(result.stdout)
            ChangeKind.FORCE_STOP -> null
        }
    }
    fun apply(kind: ChangeKind, value: String, expected: String): ShellCommand {
        target(kind, value)
        return ShellCommand(when (kind) {
            ChangeKind.APP_OP -> { require(expected == "ignore"); listOf("cmd", "appops", "set", "--user", "0", value, "RUN_ANY_IN_BACKGROUND", "ignore") }
            ChangeKind.STANDBY -> { require(expected in listOf("40", "45")); listOf("am", "set-standby-bucket", "--user", "0", value, if (expected == "45") "restricted" else "rare") }
            ChangeKind.DISABLE -> { require(value in BloatAllowlist.entries && expected == "3"); listOf("pm", "disable-user", "--user", "0", value) }
            ChangeKind.ANIMATION -> { require(expected == "0.5"); listOf("settings", "put", "global", value, "0.5") }
            ChangeKind.FORCE_STOP -> { require(expected == "stopped"); listOf("am", "force-stop", "--user", "0", value) }
        })
    }
    fun undo(entry: JournalEntry): List<ShellCommand> {
        target(entry.kind, entry.target)
        val value = entry.target
        val before = entry.before
        val commands = when (entry.kind) {
            ChangeKind.APP_OP -> { require(before in setOf("allow", "ignore", "deny", "default", "foreground")); listOf(listOf("cmd", "appops", "set", "--user", "0", value, "RUN_ANY_IN_BACKGROUND", before)) }
            ChangeKind.STANDBY -> { require(ShellParsers.bucket(before) != null); listOf(listOf("am", "set-standby-bucket", "--user", "0", value, before)) }
            ChangeKind.DISABLE -> {
                require(value in BloatAllowlist.entries && before in setOf("0", "1"))
                listOf(listOf("pm", "enable", "--user", "0", value)) + if (before == "0") listOf(listOf("pm", "default-state", "--user", "0", value)) else emptyList()
            }
            ChangeKind.ANIMATION -> {
                require(ShellParsers.animation(before) != null)
                listOf(if (before == "null") listOf("settings", "delete", "global", value) else listOf("settings", "put", "global", value, before))
            }
            ChangeKind.FORCE_STOP -> emptyList()
        }
        return commands.map(::ShellCommand)
    }
}
