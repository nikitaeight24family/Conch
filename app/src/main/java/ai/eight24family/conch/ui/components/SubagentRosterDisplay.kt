package ai.eight24family.conch.ui.components

import ai.eight24family.conch.agent.SubagentRun
import java.util.Locale

/**
 * What the live agent panel SHOWS, computed as pure data.
 *
 * Separated from the composable so the whole layout decision — which field is
 * worth a column, which one the header absorbs, what a finished agent still
 * gets to say — is pinned by `SubagentRosterDisplayTest` instead of living
 * inside a Compose tree nothing can assert on.
 *
 * ⚠ THE PANEL IS BUDGETED IN CHARACTERS, NOT IN FIELDS.
 *
 * `labelSmall` is 10 sp monospace, so a 412 dp phone fits ~60 characters per
 * line. The old panel spent that budget like this, per agent:
 *
 * ```
 * ○ general-purpose · opus  Translate terminal+common to zh   2m48s · 4 tools · 49,4k
 *   ↳ 99 keys written to /tmp/zh/out/1-terminal-common.json — exact parity with the …
 * ```
 *
 * **78 % noise**, five times over on a five-agent fan-out — (2026-08-26). Three rules
 * recover the line, and each one is enforced here
 *
 *  1. **A FIELD IS SHOWN PER ROW ONLY WHERE IT VARIES.** Five agents of the
 *     same type on the same model repeat `general-purpose · opus ` — 24
 *     characters × 5 — to say something one header line says better. Uniform
 *     type and model are hoisted into [RosterSummary]; they come back onto the
 *     rows the moment the fan-out is mixed, which is the only time they carry
 *     information.
 *  2. **A SUB-LINE IS STATE, NOT A RESULT.** The `↳` line was the agent's own
 *     return blob — markdown asterisks, absolute paths, truncated mid-sentence
 *     — and it was the LONGEST thing on screen. A finished agent's result
 *     belongs to whoever asked for it; the panel is a status panel. So `↳`
 *     survives only while the agent is alive (what it is running now) or when
 *     it died (why). The summary is still reachable — one tap on the row.
 *  3. **COST IS COMPARED, NOT ADDED UP BY HAND.** Every row carries
 *     [RosterRow.share] — its tokens against the biggest spender — which the
 *     row draws as a fill behind itself. Absolute numbers stay in the text; the
 *     picture answers "who is eating the turn" without arithmetic.
 */

/** "36.6k" / "246k" / "1.2M" — the CLI's own compact shorthand.
 *
 *  ⚠ [Locale.ROOT], always. The old formatter took the DEVICE locale, so a
 *  Russian phone rendered `245,9k` in an English-only UI (visible in the
 *  2026-08-26 screenshot). And past 100k the decimal is a claim to precision
 *  that costs two characters of a 60-character line: `246k`, not `245.9k`. */
internal fun compactTokens(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0)
    // ROUNDED, not truncated: 245 900 is 246k. Integer division would print
    // 245k and quietly under-report every large agent by up to a thousand
    // tokens, which is the opposite of what a cost panel is for.
    n >= 100_000 -> "${(n + 500) / 1_000}k"
    n >= 1_000 -> String.format(Locale.ROOT, "%.1fk", n / 1_000.0)
    else -> n.toString()
}

/** "48s" · "2m48s" · "1h04m". Hours matter: a backgrounded agent outlives its
 *  turn, and "83m12s" is a number the eye has to divide. */
internal fun elapsed(sec: Long?): String? = sec?.let {
    when {
        it >= 3_600 -> String.format(Locale.ROOT, "%dh%02dm", it / 3_600, (it % 3_600) / 60)
        it >= 60 -> "${it / 60}m${it % 60}s"
        else -> "${it}s"
    }
}

/**
 * Model label: the tail of a resolved id is enough to tell agents apart
 * (`claude-sonnet-5` → `sonnet`), and the full id would eat the whole line on
 * a phone. An alias the CLI never resolved ("sonnet", "inherit") is already
 * short and passes through.
 */
internal fun shortModel(model: String?): String? = model
    ?.takeIf { it.isNotBlank() }
    ?.removePrefix("claude-")
    ?.split('-')
    ?.firstOrNull { it.isNotBlank() }

/** How the row reads at a glance. Drives the glyph AND its colour — one
 *  decision, so a red glyph can never appear next to a "running" tint. */
internal enum class RowState { RUNNING, QUEUED, PAUSED, DONE, FAILED }

internal fun rowState(a: SubagentRun): RowState = when {
    a.status == "failed" || a.status == "cancelled" || a.status == "killed" -> RowState.FAILED
    a.status == "queued" -> RowState.QUEUED
    a.status == "paused" -> RowState.PAUSED
    a.done -> RowState.DONE
    else -> RowState.RUNNING
}

/**
 * Status glyph. ● running, ○ finished cleanly, ✕ failed, ⊘ killed, ◌ queued,
 * ◑ paused. An agent with no status event yet reads as running, which is the
 * safe direction: a spinner that outstays its welcome is visible, an agent
 * silently marked done while it still burns tokens is not.
 */
internal fun glyph(a: SubagentRun): String = when {
    a.status == "killed" -> "⊘"
    rowState(a) == RowState.FAILED -> "✕"
    rowState(a) == RowState.QUEUED -> "◌"
    rowState(a) == RowState.PAUSED -> "◑"
    a.done -> "○"
    else -> "●"
}

private val MD_NOISE = Regex("\\*{1,3}|`|~~")
private val WS_RUN = Regex("\\s+")
private val DEEP_PATH = Regex("(?:/[\\w.@+-]+){3,}")

/**
 * An agent's own words, made fit for one line.
 *
 * Agents return markdown addressed to the ORCHESTRATOR, not to a status panel:
 * `**124 keys written** to /home/user/../tmp/zh/out/4-onboarding-payment.json`.
 * Rendered raw that is 90 characters of asterisks and directory, ellipsized
 * before it reaches the number. Strip the emphasis, collapse a deep absolute
 * path to `…/<basename>` (the directory is never the news — the filename
 * sometimes is), and squeeze whitespace.
 */
internal fun cleanSummary(raw: String?): String? = raw
    ?.replace(MD_NOISE, "")
    ?.replace(DEEP_PATH) { m -> "…/" + m.value.substringAfterLast('/') }
    ?.replace(WS_RUN, " ")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

/**
 * The fan-out as one line — and the record of which per-row fields it has
 * absorbed (rule 1 above).
 */
internal data class RosterSummary(
    val agents: Int,
    val live: Int,
    val tokens: Long,
    /** Every agent shares this type → the rows drop it. Null when they differ. */
    val commonType: String?,
    /** Every agent shares this model → the rows drop it. Null when they differ. */
    val commonModel: String?,
    val backgroundTasks: Int,
    /** The longest agent wall-clock: agents run concurrently, so the slowest
     *  one IS how long the fan-out has been going. */
    val elapsedSeconds: Long?,
)

internal fun summarize(roster: List<SubagentRun>, backgroundTasks: Int): RosterSummary {
    // Hoist ONLY when EVERY agent reported the same value. An agent that has
    // not reported its model yet must not let the header speak for it: "the
    // ones that have spoken agree" is a different claim from "they all run on
    // opus", and the second is the one the header makes.
    val model = roster.firstNotNullOfOrNull { shortModel(it.model) }
        ?.takeIf { m -> roster.all { shortModel(it.model) == m } }
    val type = roster.firstNotNullOfOrNull { it.type?.takeIf { t -> t.isNotBlank() } }
        ?.takeIf { t -> roster.all { it.type == t } }
    return RosterSummary(
        agents = roster.size,
        live = roster.count { rowState(it) == RowState.RUNNING || rowState(it) == RowState.PAUSED },
        tokens = roster.sumOf { it.tokens },
        commonType = type,
        commonModel = model,
        backgroundTasks = backgroundTasks,
        elapsedSeconds = roster.mapNotNull { it.elapsedSeconds }.maxOrNull(),
    )
}

/**
 * The collapsed line — the ONLY thing on screen when the panel is shut, so it
 * has to carry the whole answer: how many, how many still alive, how long,
 * what it cost, and (when uniform) on what.
 *
 * The old line said `agents · 5 total · 245,9k` — no live count once they had
 * all finished, no clock, and a comma from the device locale.
 */
internal fun headline(s: RosterSummary, expanded: Boolean): String = buildString {
    append(if (expanded) "▾ " else "▸ ")
    append(s.agents)
    append(if (s.agents == 1) " agent" else " agents")
    // "2 live" while any are working; "done" once none are. Both are states —
    // neither is the silence the old line fell back to.
    append(if (s.live > 0) " · ${s.live} live" else " · done")
    elapsed(s.elapsedSeconds)?.let { append(" · $it") }
    if (s.tokens > 0) append(" · ${compactTokens(s.tokens)}")
    // Hoisted per-row fields (rule 1). Model before type: it is the one the
    // rows would otherwise repeat AND the one the user asks about.
    s.commonModel?.let { append(" · $it") }
    s.commonType?.let { append(" · $it") }
    if (s.backgroundTasks > 0) append(" · ${s.backgroundTasks} bg")
}

/** One agent, laid out. Every string here is final — the composable colours it
 *  and never edits it. */
internal data class RosterRow(
    val key: String,
    val glyph: String,
    val state: RowState,
    /** Left column: what the header did NOT absorb. Usually null on a uniform
     *  fan-out, `"haiku"` on a mixed-model one, `"Explore · haiku"` when both
     *  vary. */
    val identity: String?,
    val task: String,
    /** Right column: `"2m48s · 49.4k"`. Fixed shape so it scans as a column. */
    val metrics: String,
    /** Tokens against the BIGGEST spender in this fan-out, 0..1 — drawn as the
     *  fill behind the row.
     *
     *  ⚠ Normalised by the max, not by the total. Five near-equal agents each
     *  hold 20 % of the total, which draws five identical stubs and says
     *  nothing; against the max the same five read as "all about the same" and
     *  a runaway agent reads as a full bar beside four short ones. The absolute
     *  number is in [metrics]; the bar exists to COMPARE. */
    val share: Float,
    /** `↳` line — state only (rule 2): what a live agent is running, or why a
     *  dead one died. Null for a finished agent. */
    val sub: String?,
    /** Row tapped: everything the row itself had no budget for. */
    val detailMeta: String,
    /** Row tapped: the agent's own result / error, cleaned. */
    val detailText: String?,
)

internal fun layoutRoster(roster: List<SubagentRun>, s: RosterSummary): List<RosterRow> {
    val peak = roster.maxOfOrNull { it.tokens }?.takeIf { it > 0 } ?: 0L
    return roster.map { a ->
        val st = rowState(a)
        val model = shortModel(a.model)
        // Only what the header could not absorb.
        val identity = listOfNotNull(
            a.type?.takeIf { s.commonType == null && it.isNotBlank() },
            model?.takeIf { s.commonModel == null },
        ).joinToString(" · ").takeIf { it.isNotBlank() }
        RosterRow(
            key = a.key,
            glyph = glyph(a),
            state = st,
            identity = identity,
            task = a.task?.takeIf { it.isNotBlank() } ?: "agent",
            metrics = listOfNotNull(
                elapsed(a.elapsedSeconds),
                a.tokens.takeIf { it > 0 }?.let { compactTokens(it) },
            ).joinToString(" · "),
            share = if (peak > 0) (a.tokens.toFloat() / peak).coerceIn(0f, 1f) else 0f,
            sub = when (st) {
                // Why it died — always. This is the one thing a row must never
                // swallow.
                RowState.FAILED -> cleanSummary(a.error) ?: cleanSummary(a.summary) ?: "failed"
                // What it is doing RIGHT NOW, plus how much it has done. The
                // tool count rides here instead of on the row: it is progress,
                // and progress is only news while something still progresses.
                RowState.RUNNING, RowState.PAUSED -> listOfNotNull(
                    a.lastTool?.takeIf { it.isNotBlank() },
                    a.toolUses?.takeIf { it > 0 }?.let { "$it tool${if (it == 1) "" else "s"}" },
                    "backgrounded".takeIf { a.backgrounded },
                    "paused".takeIf { st == RowState.PAUSED },
                ).joinToString(" · ").takeIf { it.isNotBlank() }
                RowState.QUEUED -> "queued"
                // A finished agent says nothing here. Its result is one tap away.
                RowState.DONE -> null
            },
            detailMeta = listOfNotNull(
                a.type?.takeIf { it.isNotBlank() },
                model,
                a.toolUses?.takeIf { it > 0 }?.let { "$it tool${if (it == 1) "" else "s"}" },
                // Share of what the whole fan-out spent — answered per agent,
                // in the one place there is room to spell it out.
                a.tokens.takeIf { it > 0 && s.tokens > 0 }
                    ?.let { "${(it * 100 / s.tokens)}% of ${compactTokens(s.tokens)}" },
                a.status?.takeIf { it.isNotBlank() },
            ).joinToString(" · "),
            detailText = cleanSummary(a.error) ?: cleanSummary(a.summary),
        )
    }
}
