package ai.eight24family.conch

import ai.eight24family.conch.agent.SubagentRun
import ai.eight24family.conch.ui.components.RowState
import ai.eight24family.conch.ui.components.cleanSummary
import ai.eight24family.conch.ui.components.compactTokens
import ai.eight24family.conch.ui.components.elapsed
import ai.eight24family.conch.ui.components.headline
import ai.eight24family.conch.ui.components.layoutRoster
import ai.eight24family.conch.ui.components.summarize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The agent panel's LAYOUT RULES, pinned.
 *
 * The panel is budgeted in characters (see `SubagentRosterDisplay.kt`), and
 * every rule here exists because breaking it is what made the old one
 * unreadable on a five-agent fan-out. A regression in any of them looks like a
 * cosmetic diff and reads like noise on a phone.
 */
class SubagentRosterDisplayTest {

    private fun agent(
        key: String,
        type: String? = "general-purpose",
        task: String? = "Translate terminal+common to zh",
        model: String? = "claude-opus-5",
        tokens: Long = 49_400,
        elapsed: Long? = 168,
        done: Boolean = false,
        status: String? = null,
        summary: String? = null,
        error: String? = null,
        lastTool: String? = null,
        toolUses: Int? = null,
        backgrounded: Boolean = false,
    ) = SubagentRun(
        key = key, type = type, task = task, tokens = tokens, elapsedSeconds = elapsed,
        done = done, model = model, toolUses = toolUses, lastTool = lastTool,
        status = status, summary = summary, error = error, backgrounded = backgrounded,
    )

    // ── formatting ──

    @Test
    fun `token counts never take the device locale`() {
        val was = Locale.getDefault()
        try {
            // A Russian phone formatted "%.1fk" as "49,4k" — a comma in an
            // English-only UI, straight off the 2026-08-26 screenshot.
            Locale.setDefault(Locale.forLanguageTag("ru-RU"))
            assertEquals("49.4k", compactTokens(49_400))
            assertEquals("1.2M", compactTokens(1_200_000))
        } finally {
            Locale.setDefault(was)
        }
    }

    @Test
    fun `past 100k the decimal is dropped`() {
        // Two characters of a 60-character line, spent on a digit that claims a
        // precision total_tokens does not have.
        assertEquals("246k", compactTokens(245_900))
        assertEquals("99.9k", compactTokens(99_900))
    }

    @Test
    fun `elapsed grows an hours form`() {
        assertEquals("48s", elapsed(48))
        assertEquals("2m48s", elapsed(168))
        // A backgrounded agent outlives its turn; "83m12s" is a number the eye
        // has to divide.
        assertEquals("1h23m", elapsed(4_992))
        assertNull(elapsed(null))
    }

    // ── rule 1: a field is shown per row only where it varies ──

    @Test
    fun `a uniform fan-out states type and model once, in the header`() {
        val roster = (1..5).map { agent("k$it") }
        val s = summarize(roster, backgroundTasks = 0)
        assertEquals("opus", s.commonModel)
        assertEquals("general-purpose", s.commonType)
        assertTrue(headline(s, expanded = true).contains("opus"))
        // …and the rows are free of it — 24 characters × 5 recovered for the
        // task, which is the only per-row field that differs.
        assertTrue(layoutRoster(roster, s).all { it.identity == null })
    }

    @Test
    fun `a mixed fan-out puts the differing field back on every row`() {
        val roster = listOf(
            agent("a", model = "claude-opus-5"),
            agent("b", model = "claude-haiku-4-5"),
        )
        val s = summarize(roster, backgroundTasks = 0)
        assertNull(s.commonModel)
        // The type is still uniform, so only the model comes back.
        assertEquals("general-purpose", s.commonType)
        assertEquals(listOf("opus", "haiku"), layoutRoster(roster, s).map { it.identity })
    }

    @Test
    fun `an agent that has not reported its model yet blocks the hoist`() {
        // "The ones that have spoken agree" is a different claim from "they all
        // run on opus", and the header makes the second one.
        val roster = listOf(agent("a", model = "claude-opus-5"), agent("b", model = null))
        val s = summarize(roster, backgroundTasks = 0)
        assertNull(s.commonModel)
        assertEquals("opus", layoutRoster(roster, s)[0].identity)
    }

    // ── rule 2: a sub-line is state, not a result ──

    @Test
    fun `a finished agent's return blob never reaches the row`() {
        // 120 characters of markdown and absolute path, ellipsized mid-sentence
        // — the longest thing on the old panel and the least useful.
        val done = agent(
            "a", done = true,
            summary = "**99 keys written** to /tmp/zh/out/1-terminal-common.json — exact parity",
        )
        val s = summarize(listOf(done), 0)
        val row = layoutRoster(listOf(done), s).single()
        assertNull(row.sub)
        // Still reachable — one tap.
        assertNotNull(row.detailText)
    }

    @Test
    fun `a live agent's sub-line says what it is running right now`() {
        val live = agent("a", lastTool = "Grep", toolUses = 4)
        val row = layoutRoster(listOf(live), summarize(listOf(live), 0)).single()
        assertEquals("↳ Grep · 4 tools", "↳ " + row.sub)
        // The tool count rides in the sub-line, not on the row: progress is
        // only news while something still progresses.
        assertTrue(!row.metrics.contains("tool"))
    }

    @Test
    fun `a dead agent always says why, even with no error text`() {
        val dead = agent("a", status = "failed", error = null, summary = null)
        val row = layoutRoster(listOf(dead), summarize(listOf(dead), 0)).single()
        assertEquals(RowState.FAILED, row.state)
        assertEquals("failed", row.sub)
    }

    @Test
    fun `cleanSummary strips the markdown and the directory, keeps the news`() {
        val raw = "**124 keys written** to `/home/user/tmp/zh/out/4-onboarding-payment.json`"
        assertEquals("124 keys written to …/4-onboarding-payment.json", cleanSummary(raw))
        assertNull(cleanSummary("   "))
        assertNull(cleanSummary(null))
    }

    // ── rule 3: cost is compared, not added up by hand ──

    @Test
    fun `the fill is normalised by the biggest spender, not by the total`() {
        val roster = listOf(
            agent("a", tokens = 200_000),
            agent("b", tokens = 50_000),
            agent("c", tokens = 0),
        )
        val rows = layoutRoster(roster, summarize(roster, 0))
        // Against the max, a runaway agent reads as a full bar beside a short
        // one. Against the total these would be 0.80 / 0.20 / 0 — the same
        // ORDER but compressed into the left fifth of the row, which is the
        // shape that made five near-equal agents draw five identical stubs.
        assertEquals(1.0f, rows[0].share, 0.001f)
        assertEquals(0.25f, rows[1].share, 0.001f)
        assertEquals(0f, rows[2].share, 0.001f)
    }

    @Test
    fun `a zero-token fan-out draws no fill at all`() {
        val roster = listOf(agent("a", tokens = 0), agent("b", tokens = 0))
        assertTrue(layoutRoster(roster, summarize(roster, 0)).all { it.share == 0f })
    }

    @Test
    fun `the detail line spells out this agent's share of the fan-out`() {
        val roster = listOf(agent("a", tokens = 60_000), agent("b", tokens = 40_000))
        val s = summarize(roster, 0)
        assertEquals(100_000L, s.tokens)
        assertTrue(layoutRoster(roster, s)[0].detailMeta.contains("60% of 100k"))
    }

    // ── the collapsed line ──

    @Test
    fun `the collapsed line carries the whole answer`() {
        val roster = listOf(
            agent("a", tokens = 100_000, elapsed = 168),
            agent("b", tokens = 146_000, elapsed = 197, done = true),
        )
        val line = headline(summarize(roster, backgroundTasks = 0), expanded = false)
        assertEquals("▸ 2 agents · 1 live · 3m17s · 246k · opus · general-purpose", line)
    }

    @Test
    fun `a finished fan-out says done instead of falling silent`() {
        // The old line dropped the live count entirely once nothing was
        // running, so "agents · 5 total" was all the user got.
        val roster = (1..5).map { agent("k$it", done = true) }
        assertTrue(headline(summarize(roster, 0), expanded = false).startsWith("▸ 5 agents · done"))
    }

    @Test
    fun `the fan-out clock is the slowest agent, because agents run concurrently`() {
        val roster = listOf(agent("a", elapsed = 60), agent("b", elapsed = 197))
        assertEquals(197L, summarize(roster, 0).elapsedSeconds)
    }

    @Test
    fun `background commands stay a count`() {
        val roster = listOf(agent("a"))
        assertTrue(headline(summarize(roster, backgroundTasks = 3), expanded = false).endsWith("3 bg"))
    }

    @Test
    fun `an agent with no task still gets a row`() {
        val bare = agent("a", task = null, type = null, model = null, tokens = 0, elapsed = null)
        val row = layoutRoster(listOf(bare), summarize(listOf(bare), 0)).single()
        assertEquals("agent", row.task)
        assertEquals("", row.metrics)
    }
}
