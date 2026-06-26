package ai.eight24family.conch

import ai.eight24family.conch.agent.claude.ClaudeSpec
import ai.eight24family.conch.agent.claude.claudeLabelFromId
import ai.eight24family.conch.agent.claude.parseClaudeEffortScreen
import ai.eight24family.conch.agent.claude.parseClaudeModelMenu
import ai.eight24family.conch.agent.spec.ModelReasoningInfo
import ai.eight24family.conch.agent.spec.ReasoningLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the `/model` menu parser. Failure history this pins down:
 *  1. Hardcoded Opus/Sonnet/Haiku regexes silently dropped the new Fable
 *     family (2026-06) — auto-pickup regressed by construction.
 *  2. Name-grep over the buffer scraped STARTUP-SCREEN junk when the menu
 *     never opened ("Fable 5 · Claude Team" banner → phantom entries) and
 *     lost rows whose resolved column was cut by the 80-col PTY.
 * The parser is now structural and header-gated; the primary fixtures are
 * the VERBATIM menu frame and startup screen from the user's server
 * (claude 2.1.170).
 */
class ClaudeModelMenuParseTest {

    private val esc = "\u001B"

    /** The real 2.1.170 menu frame, as pasted by the user from the server. */
    private val realFrame = buildString {
        append("  Select model\n")
        append("  Switch between Claude models. Your pick becomes the default for new sessions. For other/previous model names,\n")
        append("  specify with --model.\n")
        append("\n")
        append("    1. Default (recommended)  Opus 4.8 with 1M context · Best for everyday, complex tasks\n")
        append("  ❯ 2. Fable ✔                Fable 5 · Most capable for your hardest and longest-running tasks · Uses your limits ~2×\n")
        append("                              faster than Opus\n")
        append("    3. Sonnet                 Sonnet 4.6 · Efficient for routine tasks\n")
        append("    4. Sonnet (1M context)    Sonnet 4.6 with 1M context · Draws from usage credits · \$3/\$15 per Mtok\n")
        append("    5. Haiku                  Haiku 4.5 · Fastest for quick answers\n")
    }

    /** The real startup screen (from the raw frame logcat) — menu NOT open. */
    private val realStartup = buildString {
        append(" ▐▛███▜▌Claude Codev2.1.170\n")
        append("▝▜█████▛▘  Fable 5 · Claude Team\n")
        append("  ▘▘ ▝▝    /home/user\n")
        append("❯ Try \"create a util logging.py that...\"\n")
        append(" ⏵⏵ bypass permissions on (shift+tab to cycle) · ← for agents\n")
        append(" ▎ Using Fable 5 (from .claude/settings.json) · /model\n")
        append("   +1 more · /status\n")
    }

    @Test
    fun `real frame parses all concrete models with cli aliases`() {
        val map = parseClaudeModelMenu("> /model\n" + realFrame)
        // NO "default" key — the picker shows only concrete models. The
        // "Default (recommended)" row's resolved model survives as a
        // concrete entry.
        assertEquals(
            listOf("claude-opus-4-8[1m]", "fable", "sonnet", "sonnet[1m]", "haiku"),
            map.keys.toList(),
        )
        assertFalse(map.containsKey("default"))
        assertEquals("Opus 4.8 with 1M context", map["claude-opus-4-8[1m]"])
        assertEquals("Fable 5", map["fable"])
        assertEquals("Sonnet 4.6", map["sonnet"])
        assertEquals("Sonnet 4.6 with 1M context", map["sonnet[1m]"])
        assertEquals("Haiku 4.5", map["haiku"])
    }

    @Test
    fun `startup screen without menu yields nothing - the pin is ignored`() {
        // No menu header → no model list at all; the settings.json pin is
        // never surfaced (no "default"). The topbar uses the session model.
        val map = parseClaudeModelMenu(realStartup)
        assertTrue(map.isEmpty())
    }

    @Test
    fun `menu parses to concrete models with no default key`() {
        val map = parseClaudeModelMenu(realStartup + "\n" + realFrame)
        assertFalse(map.containsKey("default"))
        assertEquals(
            listOf("claude-opus-4-8[1m]", "fable", "sonnet", "sonnet[1m]", "haiku"),
            map.keys.toList(),
        )
    }

    @Test
    fun `ansi paint does not break row parsing`() {
        val raw = buildString {
            append("$esc[2J$esc[1mSelect model$esc[0m\n")
            append("    1. $esc[1mDefault (recommended)$esc[0m  Opus 4.8 with 1M context · Best for tasks\n")
            append("  $esc[36m❯ 2. Fable ✔$esc[0m                Fable 5 · Most capable\n")
            append("    3. Sonnet                 Sonnet 4.6 · Efficient\n")
        }
        val map = parseClaudeModelMenu(raw)
        assertEquals(
            listOf("claude-opus-4-8[1m]", "fable", "sonnet"),
            map.keys.toList(),
        )
        assertEquals("Fable 5", map["fable"])
    }

    @Test
    fun `cursor motion gaps render as spaces not glued text`() {
        // ink paints column gaps with cursor-forward ops instead of literal
        // spaces; DELETING them glued "1. Default" into "1.Default" and
        // broke row parsing (the real 2026-06-10 capture). The terminal
        // renderer must turn them back into spacing.
        val raw = buildString {
            append("Select model\r\n")
            append("    1.$esc[1CDefault (recommended)$esc[2COpus 4.8 with 1M context · Best\r\n")
            append("    3. Sonnet$esc[17CSonnet 4.6 · Efficient\r\n")
        }
        val map = parseClaudeModelMenu(raw)
        assertEquals("Opus 4.8 with 1M context", map["claude-opus-4-8[1m]"])
        assertEquals("Sonnet 4.6", map["sonnet"])
    }

    @Test
    fun `diff repaint over an existing row keeps the full screen text`() {
        // A later frame rewrites only some cells via absolute positioning —
        // untouched cells must survive from the earlier paint ("Sonnet"
        // must not decay into "onnt" like the stripped capture did).
        val raw = buildString {
            append("Select model\r\n")
            append("    3. Sonnet                 Sonnet 4.6 · Efficient for routine tasks\r\n")
            append("$esc[2;31H") // row 2, col 31 — start of the right column
            append("Sonnet 4.6")
        }
        val map = parseClaudeModelMenu(raw)
        assertEquals("Sonnet 4.6", map["sonnet"])
    }

    @Test
    fun `erase line and rewrite keeps the last paint`() {
        val raw = buildString {
            append("Select model\r\n")
            append("    2. Fable                 Fable 4 · old\r\n")
            append("$esc[1A\r$esc[2K") // cursor up to the fable row, clear it
            append("    2. Fable                 Fable 5 · new")
        }
        val map = parseClaudeModelMenu(raw)
        assertEquals("Fable 5", map["fable"])
    }

    @Test
    fun `narrow pty truncating the resolved column keeps every row`() {
        val raw = buildString {
            append("Select model\n")
            append("    1. Default (recommended)\n")
            append("  ❯ 2. Fable ✔\n")
            append("    3. Sonnet\n")
            append("    4. Sonnet (1M context)\n")
            append("    5. Haiku\n")
        }
        val map = parseClaudeModelMenu(raw)
        // Default row unresolved → no chip metadata, no phantom entry;
        // every concrete row survives with its label as the display.
        assertEquals(
            listOf("fable", "sonnet", "sonnet[1m]", "haiku"),
            map.keys.toList(),
        )
        assertEquals("Fable", map["fable"])
        assertEquals("Sonnet (1M context)", map["sonnet[1m]"])
    }

    @Test
    fun `repaints keep the richer capture`() {
        val truncated = "Select model\n    1. Default (recommended)\n    2. Fable\n"
        val map = parseClaudeModelMenu(truncated + realFrame)
        assertFalse(map.containsKey("default"))
        assertEquals("Fable 5", map["fable"])
        assertTrue(map.containsKey("claude-opus-4-8[1m]"))
        assertTrue(map.containsKey("sonnet[1m]"))
    }

    @Test
    fun `multi word labels collapse to cli alias`() {
        val raw = buildString {
            append("Select model\n")
            append("    1. Default (recommended)  Opus 4.8 · Best\n")
            append("    2. Opus Plan              Opus 4.8 + Sonnet 4.6 · Plan with Opus\n")
        }
        val map = parseClaudeModelMenu(raw)
        assertTrue(map.containsKey("opusplan"))
        assertFalse(map.containsKey("default"))
        // The Default-synth Opus shares the "Opus 4.8" label with the
        // explicit "Opus Plan" row → the synth twin is dropped, the explicit
        // row survives.
        assertEquals("Opus 4.8", map["opusplan"])
        assertFalse(map.containsKey("claude-opus-4-8"))
    }

    @Test
    fun `label that is itself a model name becomes its public id`() {
        val raw = "Select model\n    6. Opus 4.5                Opus 4.5 · Legacy\n"
        val map = parseClaudeModelMenu(raw)
        assertEquals("Opus 4.5", map["claude-opus-4-5"])
    }

    @Test
    fun `legacy menu without numbered rows falls back to name scan`() {
        val raw = buildString {
            append("Select model\n")
            append(" ❯ Default (recommended) — Opus 4.6 with 1M context NEW · Most capable for complex work\n")
            append("   Sonnet 4.6 — Most efficient for everyday tasks\n")
            append("   Haiku 4.5 — Fastest for quick answers\n")
        }
        val map = parseClaudeModelMenu(raw)
        assertFalse(map.containsKey("default"))
        assertEquals("Opus 4.6 with 1M context", map["claude-opus-4-6[1m]"])
        assertEquals("Sonnet 4.6", map["claude-sonnet-4-6"])
        assertEquals("Haiku 4.5", map["claude-haiku-4-5"])
    }

    @Test
    fun `confirm prompts and banner mentions without header yield nothing`() {
        val raw = buildString {
            append("Do you trust the files in this folder?\n")
            append("  1. Yes, proceed\n")
            append("  2. No, exit\n")
            append("Opus 4.7 mentioned somewhere\n")
        }
        assertEquals(emptyMap<String, String>(), parseClaudeModelMenu(raw))
    }

    @Test
    fun `blank or model free buffers return empty`() {
        assertEquals(emptyMap<String, String>(), parseClaudeModelMenu(""))
        assertEquals(emptyMap<String, String>(), parseClaudeModelMenu("   \n  "))
        assertEquals(
            emptyMap<String, String>(),
            parseClaudeModelMenu("Press Enter to continue\nTrust this folder?\n"),
        )
    }

    @Test
    fun `effort slider parses levels and current marker from columns`() {
        // Verbatim structure of the 2.1.170 /effort slider (user capture):
        // ▲ on the ruler marks the active level by column; sub-label
        // annotates ultracode.
        val screen = buildString {
            append("Effort\n")
            append("\n")
            append("  Faster                                          Smarter\n")
            append("  ────────────────────▲───────────────┊──────────\n")
            append("  low     medium     high     xhigh     max     ultracode\n")
            append("                                                 xhigh + workflows\n")
            append("\n")
            append(" ←/→ to adjust · Enter to confirm · Esc to cancel\n")
        }
        val parsed = parseClaudeEffortScreen(screen)
        assertEquals("high", parsed.current)
        val info = parsed.info!!
        assertEquals("high", info.defaultEffort)
        assertEquals(
            listOf("low", "medium", "high", "xhigh", "max", "ultracode"),
            info.levels.map { it.effort },
        )
        assertEquals("xhigh + workflows", info.levels.last().description)
        assertEquals("Fast responses, lighter reasoning", info.levels.first().description)
    }

    @Test
    fun `effort current falls back to model menu footer and status line`() {
        val footer = parseClaudeEffortScreen("  ● High effort (default)  ←/→ to adjust\n")
        assertEquals("high", footer.current)
        assertNull(footer.info)

        val status = parseClaudeEffortScreen(
            "  ⏵⏵ bypass permissions on (shift+tab to cycle) · ← for agents    ● xhigh · /effort\n",
        )
        assertEquals("xhigh", status.current)
        assertNull(status.info)

        val nothing = parseClaudeEffortScreen("just a chat screen\n")
        assertNull(nothing.current)
        assertNull(nothing.info)
    }

    @Test
    fun `reasoning catalog survives the persistence roundtrip`() {
        val info = ModelReasoningInfo(
            defaultEffort = "high",
            levels = listOf(
                ReasoningLevel("low", "Low", "Fast responses, lighter reasoning"),
                ReasoningLevel("xhigh", "Xhigh", ""),
                ReasoningLevel("ultracode", "Ultracode", "xhigh + workflows"),
            ),
        )
        val raw = ClaudeSpec.serializeReasoningCatalog(mapOf("fable" to info))!!
        val restored = ClaudeSpec.deserializeReasoningCatalog(raw, listOf("default", "fable", "haiku"))
        assertEquals(setOf("default", "fable", "haiku"), restored.keys)
        val r = restored.getValue("fable")
        assertEquals("high", r.defaultEffort)
        assertEquals(listOf("low", "xhigh", "ultracode"), r.levels.map { it.effort })
        assertEquals("xhigh + workflows", r.levels.last().description)
        assertEquals("Ultracode", r.levels.last().displayName)
    }

    @Test
    fun `label from id reverses the synthesis including dated ids`() {
        assertEquals("Fable 5", claudeLabelFromId("claude-fable-5"))
        assertEquals("Opus 4.8", claudeLabelFromId("claude-opus-4-8"))
        assertEquals("Opus 4.8 1M", claudeLabelFromId("claude-opus-4-8[1m]"))
        assertEquals("Sonnet 4.5", claudeLabelFromId("claude-sonnet-4-5-20250929"))
        assertNull(claudeLabelFromId("sonnet"))
        assertNull(claudeLabelFromId("gpt-5.4"))
    }
}
