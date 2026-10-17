package dev.muhib.s23optimizer.core

import dev.muhib.s23optimizer.core.agent.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentContractTest {
    private val registry = ToolRegistry(setOf("org.example.sync"))
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private val valid = """{"findings":["Sync held a partial wakelock."],"actions":[{"tool":"set_standby_bucket","arguments":{"pkg":"org.example.sync","bucket":"rare"},"reason":"May delay background uploads."}]}"""

    @Test fun `only registered typed installed package actions pass validation`() {
        assertEquals(AgentAction.Standby("org.example.sync", StandbyBucket.RARE), registry.validateWrite("set_standby_bucket", json("""{"pkg":"org.example.sync","bucket":"rare"}""")))
        for ((tool, args) in listOf(
            "shell" to """{"command":"reboot"}""",
            "force_stop" to """{"pkg":"org.example.missing"}""",
            "force_stop" to """{"pkg":"org.example.sync;reboot"}""",
            "restrict_background" to """{"pkg":"org.example.sync","command":"reboot"}""",
            "set_standby_bucket" to """{"pkg":"org.example.sync","bucket":"active"}""",
            "force_stop" to """{"pkg":123}"""
        )) assertTrue(runCatching { registry.validateWrite(tool, json(args)) }.isFailure)
        assertEquals(10, registry.tools.size)
        assertTrue(registry.tools.all { it.inputSchema["additionalProperties"] == JsonPrimitive(false) })
    }

    @Test fun `analysis returns proposals without exposing a mutation capability`() = runBlocking {
        var request: ModelRequest? = null
        val client = object : ModelClient {
            override suspend fun analyze(value: ModelRequest): String { request = value; return valid }
        }
        val result = AgentLoop(registry, client).analyze(AgentSnapshot(1L, mapOf("battery_overview" to json("""{"levelPct":73}"""))))
        assertNull(result.error)
        assertEquals(1, result.proposals.size)
        assertEquals(ChangeKind.STANDBY, result.proposals.single().action.kind)
        assertEquals("40", result.proposals.single().action.expected)
        assertTrue(request!!.snapshot.contains("73"))
    }

    @Test fun `malformed schema extra properties and raw commands produce clear all or nothing error`() = runBlocking {
        for (output in listOf("```json\n$valid\n```", "{}", valid.replace("rare", "active"), valid.replace("\"reason\":", "\"shell\":\"reboot\",\"reason\":"), valid.replace("[\"Sync held a partial wakelock.\"]", "[123]"))) {
            val result = AgentLoop(registry, object : ModelClient { override suspend fun analyze(value: ModelRequest) = output }).analyze(AgentSnapshot(1L, emptyMap()))
            assertTrue(result.proposals.isEmpty())
            assertTrue(result.error!!.contains("Invalid model response"))
        }
    }

    @Test fun `snapshot remains valid bounded JSON even with huge tool results`() {
        val rows = JsonArray((0..5000).map { JsonPrimitive("🔥".repeat(200)) })
        val snapshot = AgentSnapshot(1L, registry.tools.filter { !it.write }.associate { it.name to rows })
        val bounded = snapshot.modelJson()
        assertTrue(bounded.toByteArray(Charsets.UTF_8).size <= 6000)
        assertEquals(7, Json.parseToJsonElement(bounded).jsonObject["tools"]!!.jsonObject.size)
        assertTrue(bounded.contains("truncated"))
    }

    @Test fun `provider errors are sanitized and cancellation is preserved`() = runBlocking {
        val broken = object : ModelClient { override suspend fun analyze(value: ModelRequest): String = error("credential must never be shown") }
        val result = AgentLoop(registry, broken).analyze(AgentSnapshot(1L, emptyMap()))
        assertFalse(result.error!!.contains("credential"))
        val cancelled = object : ModelClient { override suspend fun analyze(value: ModelRequest): String = throw kotlinx.coroutines.CancellationException() }
        assertTrue(runCatching { AgentLoop(registry, cancelled).analyze(AgentSnapshot(1L, emptyMap())) }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }

    @Test fun `wire protocol uses native tools and refuses truncated or arbitrary tool output`() {
        val request = ModelRequest("{}", registry.tools, AnalysisSchema.schema)
        for (provider in Provider.entries) {
            val config = ModelConfig(provider, "model-test", "https://example.org/v1")
            val body = ModelWire.request(config, request)
            assertTrue(body["tools"]!!.jsonArray.isNotEmpty())
            assertTrue(body.toString().contains("submit_analysis"))
        }
        assertEquals(valid, ModelWire.response(Provider.OPENAI, json("""{"choices":[{"finish_reason":"tool_calls","message":{"tool_calls":[{"type":"function","function":{"name":"submit_analysis","arguments":${JsonPrimitive(valid)}}}]}}]}""")))
        assertEquals(Json.parseToJsonElement(valid).toString(), ModelWire.response(Provider.ANTHROPIC, json("""{"stop_reason":"tool_use","content":[{"type":"tool_use","name":"submit_analysis","input":$valid}]}""")))
        assertTrue(runCatching { ModelWire.response(Provider.OPENAI, json("""{"choices":[{"finish_reason":"length","message":{}}]}""")) }.isFailure)
        assertTrue(runCatching { ModelConfig(Provider.OPENAI, "m", "http://example.org/v1").validate() }.isFailure)
    }
}
