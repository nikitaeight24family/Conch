package ai.eight24family.conch

import ai.eight24family.conch.agent.claude.ClaudeInitState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the parse of the CLI's `initialize` control_response — the handshake
 * that replaced the /model TUI scrape as the model-catalog source (payload
 * shape verified against the 2.1.219 binary's `cbl` builder).
 *
 * Carries forward the spirit of the deleted ClaudeCheckedRowWinsTest /
 * ClaudeModelMenuParseTest: the CLI default must resolve to a CONCRETE model
 * (never the word "Default"), the picker map must skip the "default" row, and
 * an unknown future model family must ride through with zero code changes.
 */
class ClaudeInitStateTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun payload(modelsJson: String): ClaudeInitState {
        val raw = """
            {"commands":[
                {"name":"compact","description":"Compact conversation","argumentHint":"[instructions]"},
                {"name":"usage","description":"Show plan usage","aliases":["cost","stats"]}
             ],
             "agents":[{"name":"Explore","description":"Read-only search agent"}],
             "output_style":"normal",
             "available_output_styles":["normal"],
             "models":$modelsJson,
             "account":{"email":"u@example.com","organization":"Personal","subscriptionType":"max","tokenSource":"claude.ai","apiProvider":"firstParty"},
             "pid":4242}
        """.trimIndent()
        return ClaudeInitState.parse(json.parseToJsonElement(raw).jsonObject)
    }

    private val realModels = """
        [{"value":"default","resolvedModel":"claude-sonnet-5","displayName":"Default (recommended)","description":"Use the default model"},
         {"value":"sonnet","resolvedModel":"claude-sonnet-5","displayName":"Sonnet 5","description":"Efficient for routine tasks","supportsEffort":true,"supportedEffortLevels":["low","medium","high","max"]},
         {"value":"fable","resolvedModel":"claude-fable-5","displayName":"Fable 5","description":"Most capable","supportsEffort":true,"supportedEffortLevels":["low","medium","high","xhigh","max"]},
         {"value":"opus","resolvedModel":"claude-opus-5","displayName":"Opus 5","description":"Best for complex tasks"},
         {"value":"haiku","resolvedModel":"claude-haiku-4-5","displayName":"Haiku 4.5","description":"Fastest","disabled":true}]
    """.trimIndent()

    @Test
    fun `models commands agents and account all parse`() {
        val st = payload(realModels)
        assertEquals(5, st.models.size)
        assertEquals(2, st.commands.size)
        assertEquals("compact", st.commands[0].name)
        assertEquals("[instructions]", st.commands[0].argumentHint)
        assertEquals(listOf("cost", "stats"), st.commands[1].aliases)
        assertEquals("Explore", st.agents.single().name)
        assertEquals("max", st.account?.subscriptionType)
        assertEquals("u@example.com", st.account?.email)
        assertEquals("normal", st.outputStyle)
    }

    @Test
    fun `picker map skips the default row and keeps menu order`() {
        val map = ClaudeInitState.toPickerMap(payload(realModels))
        assertEquals(listOf("sonnet", "fable", "opus", "haiku"), map.keys.toList())
        assertEquals("Sonnet 5", map["sonnet"])
        assertTrue("default" !in map)
    }

    @Test
    fun `cli default resolves to a concrete model and its picker key`() {
        val (label, key) = ClaudeInitState.defaultModel(payload(realModels))
        // The default row resolves to claude-sonnet-5, whose picker twin is the
        // "sonnet" row — label and WIRE KEY must both come from that twin
        // (SHOWN-MODEL-IS-SENT-MODEL-1), and the word "Default" must not leak.
        assertEquals("Sonnet 5", label)
        assertEquals("sonnet", key)
    }

    @Test
    fun `default without a twin row falls back to the resolved id`() {
        val st = payload(
            """[{"value":"default","resolvedModel":"claude-opus-5","displayName":"Default (recommended)","description":""},
                {"value":"sonnet","resolvedModel":"claude-sonnet-5","displayName":"Sonnet 5","description":""}]"""
        )
        val (label, key) = ClaudeInitState.defaultModel(st)
        // No picker row resolves to claude-opus-5 → the id itself is the wire
        // value (a documented --model form) and the label is derived, never
        // "Default (recommended)".
        assertEquals("Opus 5", label)
        assertEquals("claude-opus-5", key)
    }

    @Test
    fun `a brand-new model family rides through with no code change`() {
        val st = payload(
            """[{"value":"default","resolvedModel":"claude-mythos-6","displayName":"Default (recommended)","description":""},
                {"value":"mythos","resolvedModel":"claude-mythos-6","displayName":"Mythos 6","description":"The next thing","supportedEffortLevels":["low","galactic"]}]"""
        )
        val map = ClaudeInitState.toPickerMap(st)
        assertEquals(mapOf("mythos" to "Mythos 6"), map)
        val (label, key) = ClaudeInitState.defaultModel(st)
        assertEquals("Mythos 6", label)
        assertEquals("mythos", key)
        // Unknown effort tokens are carried verbatim, never filtered by a
        // hardcoded ladder.
        assertEquals(listOf("low", "galactic"), ClaudeInitState.effortLevels(st))
    }

    @Test
    fun `disabled rows feed the unavailable set`() {
        assertEquals(setOf("Haiku 4.5"), ClaudeInitState.unavailableLabels(payload(realModels)))
    }

    @Test
    fun `effort ladder is the union across models in cli order`() {
        assertEquals(
            listOf("low", "medium", "high", "max", "xhigh"),
            ClaudeInitState.effortLevels(payload(realModels)),
        )
    }

    @Test
    fun `short alias displayName gains the version from the resolved id`() {
        // REAL sample from a live 2.1.202 box (2026-08-02): the handshake's
        // displayName is the short alias label ("Opus"), the version lives in
        // resolvedModel. The picker must show "Opus 4.8 1M", never a bare
        // "Opus" that hides which version the user is on.
        val st = payload(
            """[{"value":"default","resolvedModel":"claude-opus-4-8[1m]","displayName":"Default (recommended)","description":"Opus 4.8 with 1M context · Best for everyday, complex tasks"},
                {"value":"opus[1m]","resolvedModel":"claude-opus-4-8[1m]","displayName":"Opus","description":"Opus 4.8 with 1M context · Best"},
                {"value":"claude-fable-5[1m]","resolvedModel":"claude-fable-5[1m]","displayName":"Fable","description":"Fable 5"}]"""
        )
        val map = ClaudeInitState.toPickerMap(st)
        assertEquals("Opus 4.8 1M", map["opus[1m]"])
        assertEquals("Fable 5 1M", map["claude-fable-5[1m]"])
        val (label, key) = ClaudeInitState.defaultModel(st)
        assertEquals("Opus 4.8 1M", label)
        assertEquals("opus[1m]", key)
    }

    @Test
    fun `empty account object parses as null`() {
        val st = ClaudeInitState.parse(
            json.parseToJsonElement("""{"models":[],"commands":[],"account":{}}""").jsonObject
        )
        assertNull(st.account)
        assertTrue(st.models.isEmpty())
    }
}
