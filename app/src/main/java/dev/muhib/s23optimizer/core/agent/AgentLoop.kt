package dev.muhib.s23optimizer.core.agent

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

data class AgentSnapshot(val takenAt: Long, val tools: Map<String, JsonElement>) {
    /** A conservative UTF-8 byte cap, including every tool's truncation marker. No raw dumps. */
    fun modelJson(): String = buildJsonObject {
        put("takenAt", takenAt)
        put("tools", buildJsonObject {
            tools.entries.take(7).forEach { (name, value) ->
                var shortened = value
                while (shortened.toString().toByteArray(Charsets.UTF_8).size > 650) {
                    val next = shrink(shortened)
                    shortened = if (next == shortened) JsonNull else next
                }
                put(name.take(40), buildJsonObject { put("data", shortened); put("truncated", shortened != value) })
            }
        })
    }.toString()

    private fun shrink(value: JsonElement): JsonElement = when (value) {
        is JsonArray -> if (value.size > 1) JsonArray(value.take(value.size / 2)) else JsonArray(value.map(::shrink))
        is JsonObject -> JsonObject(value.mapValues { shrink(it.value) })
        is JsonPrimitive -> if (value.isString && value.content.length > 80) JsonPrimitive(value.content.take(80)) else value
    }
}

data class Proposal(val action: AgentAction, val reason: String)
data class AgentAnalysis(val findings: List<String> = emptyList(), val proposals: List<Proposal> = emptyList(), val error: String? = null)
data class ModelRequest(val snapshot: String, val tools: List<ToolDefinition>, val responseSchema: JsonObject)
interface ModelClient { suspend fun analyze(value: ModelRequest): String }

object AnalysisSchema {
    val schema = objectSchema(mapOf(
        "findings" to buildJsonObject {
            put("type", "array"); put("maxItems", 12)
            put("items", buildJsonObject { put("type", "string"); put("minLength", 1); put("maxLength", 2000) })
        },
        "actions" to buildJsonObject {
            put("type", "array"); put("maxItems", 12)
            put("items", buildJsonObject {
                put("oneOf", JsonArray(ToolRegistry.definitions.filter { it.write }.map { tool ->
                    objectSchema(mapOf("tool" to stringSchema(tool.name), "arguments" to tool.inputSchema,
                        "reason" to buildJsonObject { put("type", "string"); put("minLength", 1); put("maxLength", 2000) }))
                }))
            })
        }
    ))
}

/** No shell, ChangeEngine, journal or apply callback is available to the model loop. */
class AgentLoop(private val registry: ToolRegistry, private val client: ModelClient) {
    suspend fun analyze(snapshot: AgentSnapshot): AgentAnalysis {
        val output = try { client.analyze(ModelRequest(snapshot.modelJson(), registry.tools, AnalysisSchema.schema)) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { return AgentAnalysis(error = "Model request failed. Check provider, model, endpoint, key and connection, then retry.") }
        return try {
            val root = strictJson(output) as? JsonObject ?: error("Expected an object.")
            root.exactKeys("findings", "actions")
            val findings = root["findings"] as? JsonArray ?: error("Findings must be an array.")
            val actions = root["actions"] as? JsonArray ?: error("Actions must be an array.")
            require(findings.size <= 12 && actions.size <= 12)
            val words = findings.map { item ->
                val value = item as? JsonPrimitive
                require(value != null && value.isString && value.content.isNotBlank() && value.content.length <= 2000)
                value.content
            }
            val proposals = actions.map { item ->
                val row = item as? JsonObject ?: error("Invalid action.")
                row.exactKeys("tool", "arguments", "reason")
                Proposal(registry.validateWrite(row.text("tool"), row["arguments"] as? JsonObject ?: error("Invalid arguments.")), row.text("reason"))
            }
            require(proposals.map { it.action }.distinct().size == proposals.size)
            AgentAnalysis(words, proposals)
        } catch (_: Exception) {
            AgentAnalysis(error = "Invalid model response: expected strict JSON findings and allow-listed actions with installed packages and valid arguments. No actions were accepted; retry analysis.")
        }
    }
}

/** Bound untrusted JSON before recursive parsing. Quoted braces do not count toward depth. */
internal fun strictJson(text: String): JsonElement {
    require(text.length <= 65_536)
    var depth = 0; var quoted = false; var escaped = false
    text.forEach { char ->
        if (quoted) {
            if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
        } else when (char) {
            '"' -> quoted = true
            '{', '[' -> { depth++; require(depth <= 20) }
            '}', ']' -> { depth--; require(depth >= 0) }
        }
    }
    require(depth == 0 && !quoted)
    return Json.parseToJsonElement(text)
}
