package dev.muhib.s23optimizer.core.agent

import dev.muhib.s23optimizer.core.ChangeKind
import kotlinx.serialization.json.*

data class ToolDefinition(val name: String, val description: String, val write: Boolean, val inputSchema: JsonObject)
enum class StandbyBucket(val wire: String, val value: String) { RARE("rare", "40"), RESTRICTED("restricted", "45") }

sealed class AgentAction {
    abstract val pkg: String
    abstract val tool: String
    abstract val kind: ChangeKind
    abstract val expected: String
    val revertible: Boolean get() = kind != ChangeKind.FORCE_STOP
    data class Standby(override val pkg: String, val bucket: StandbyBucket) : AgentAction() {
        override val tool = "set_standby_bucket"
        override val kind = ChangeKind.STANDBY
        override val expected get() = bucket.value
    }
    data class RestrictBackground(override val pkg: String) : AgentAction() {
        override val tool = "restrict_background"
        override val kind = ChangeKind.APP_OP
        override val expected = "ignore"
    }
    data class ForceStop(override val pkg: String) : AgentAction() {
        override val tool = "force_stop"
        override val kind = ChangeKind.FORCE_STOP
        override val expected = "stopped"
    }
    fun arguments(): JsonObject = buildJsonObject {
        put("pkg", pkg)
        if (this@AgentAction is Standby) put("bucket", bucket.wire)
    }
}

internal fun objectSchema(properties: Map<String, JsonElement>): JsonObject = buildJsonObject {
    put("type", "object"); put("properties", JsonObject(properties))
    put("required", JsonArray(properties.keys.map(::JsonPrimitive)))
    put("additionalProperties", false)
}
internal fun stringSchema(vararg values: String): JsonObject = buildJsonObject {
    put("type", "string")
    if (values.isNotEmpty()) put("enum", JsonArray(values.map(::JsonPrimitive)))
}
internal fun JsonObject.exactKeys(vararg keys: String) { require(this.keys == keys.toSet()) { "Unexpected or missing fields." } }
internal fun JsonObject.text(key: String, max: Int = 2000): String {
    val value = this[key] as? JsonPrimitive
    require(value != null && value.isString && value.content.isNotBlank() && value.content.length <= max) { "Invalid $key." }
    return value.content
}

class ToolRegistry(private val installed: Set<String>) {
    val tools: List<ToolDefinition> get() = definitions
    fun validateRead(name: String, args: JsonObject) {
        require(definitions.any { it.name == name && !it.write }) { "Unknown read tool." }
        args.exactKeys()
    }
    fun validateWrite(name: String, args: JsonObject): AgentAction {
        require(definitions.any { it.name == name && it.write }) { "Unknown write tool; raw commands are never accepted." }
        if (name == "set_standby_bucket") args.exactKeys("pkg", "bucket") else args.exactKeys("pkg")
        val pkg = args.text("pkg", 255)
        require(packagePattern.matches(pkg) && pkg in installed) { "Package is not installed or its name is invalid." }
        return when (name) {
            "set_standby_bucket" -> AgentAction.Standby(pkg, StandbyBucket.entries.firstOrNull { it.wire == args.text("bucket") } ?: error("Bucket must be rare or restricted."))
            "restrict_background" -> AgentAction.RestrictBackground(pkg)
            else -> AgentAction.ForceStop(pkg)
        }
    }
    companion object {
        val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")
        private val packageSchema = buildJsonObject { put("type", "string"); put("pattern", "^${packagePattern.pattern}$"); put("maxLength", 255) }
        val definitions = listOf(
            ToolDefinition("battery_overview", "Battery level, OS health flag, temperature and since-charge screen drain. Missing is unknown.", false, objectSchema(emptyMap())),
            ToolDefinition("top_drainers", "Estimated mAh per UID since charge. Shared packages share one estimate.", false, objectSchema(emptyMap())),
            ToolDefinition("wakelocks", "Partial wakelock names, UIDs, held milliseconds and counts since charge.", false, objectSchema(emptyMap())),
            ToolDefinition("alarms_jobs", "Wakeup alarm and job run counts per UID since charge.", false, objectSchema(emptyMap())),
            ToolDefinition("standby_buckets", "Standby buckets for user-installed packages.", false, objectSchema(emptyMap())),
            ToolDefinition("background_restrictions", "RUN_ANY_IN_BACKGROUND appops for a bounded sample of user-installed apps.", false, objectSchema(emptyMap())),
            ToolDefinition("procstats", "Sampled PSS over 3 hours; background PSS is the largest sampled state average, not energy.", false, objectSchema(emptyMap())),
            ToolDefinition("set_standby_bucket", "Propose rare or restricted standby. May delay notifications and jobs. Requires owner confirmation.", true, objectSchema(mapOf("pkg" to packageSchema, "bucket" to stringSchema("rare", "restricted")))),
            ToolDefinition("restrict_background", "Propose restricting background work. May delay syncing and notifications. Requires owner confirmation.", true, objectSchema(mapOf("pkg" to packageSchema))),
            ToolDefinition("force_stop", "Propose stopping one app. Interrupts work and notifications until reopened. NON-REVERTIBLE; requires owner confirmation.", true, objectSchema(mapOf("pkg" to packageSchema)))
        )
    }
}
