package ai.eight24family.conch.agent

import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.util.SilentlyTry
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** One plan rate-limit window, normalized across agents. */
@Serializable
data class UsageWindow(
    val label: String,         // "5h" / "Weekly" / "Weekly · Opus"
    val fraction: Float,       // 0f..1f — what the bar DRAWS (used, or remaining for Codex)
    val percent: Int,          // 0..100 — the number shown
    val resetText: String,     // "3h" / "45m" / "2d" / "" — fetch-time fallback
    val usedFraction: Float = 0f, // 0f..1f actually consumed — drives the warning colour
    /** Absolute reset time (epoch ms) when the provider gives one. Lets the UI
     *  tick the countdown LIVE instead of freezing the fetch-time string — user
     *  2026-06-14: app showed "49m" frozen while the desktop ticked to 14m,
     *  because resetText was computed once at fetch and only refreshed on
     *  chat-open / turn-finish. */
    val resetAtEpochMs: Long? = null,
    /** True for a per-model window (the "second layer": Opus/Sonnet/Fable
     *  weekly caps) vs an aggregate (5-hour / weekly all-models). Lets the sheet
     *  group the per-model rows under their own subheader. */
    val perModel: Boolean = false,
) {
    /** "Until reset" recomputed against [nowMs] from the absolute reset time, so
     *  it counts down without a refetch; falls back to the fetch-time
     *  [resetText] when no absolute anchor is available. */
    /**
     * ⛔ WHAT THE SERVER LAST SAID, NOT WHAT OUR CLOCK MAKES OF IT.
     *
     * The remaining time is the PROVIDER's answer, refreshed by asking again —
     * an open chat re-reads usage every 8 s ([ChatViewModel.USAGE_POLL_
     * FOREGROUND_MS]) — not a number the phone counts down between answers.
     * They are not the same claim: only the provider knows when a window rolls,
     * when credits are topped up, or when a reset moves, and a local countdown
     * keeps confidently subtracting through all three.
     *
     * [resetTextLive] remains for the case this cannot cover: no fresh answer
     * (backgrounded, offline, a probe that could not run), where a stale string
     * would freeze on screen. That was the 2026-06-14 report — "49m" frozen
     * while the desktop read 14m — and it happened because the text was only
     * recomputed on chat-open and turn-finish. With a live 8 s poll the fresh
     * answer is the better one; without one, the clock is all there is.
     */
    fun resetTextServer(): String = resetText

    fun resetTextLive(nowMs: Long): String {
        val at = resetAtEpochMs ?: return resetText
        return usageCountdownText((at - nowMs) / 1000)
    }

    /** The model family a per-model window is scoped to, read back off the label
     *  every producer builds it with — `"Fable · weekly"` / `"Opus · 5-hour"` /
     *  the bare `"Fable"` of `model_scoped` all yield the family. Null for an
     *  aggregate, which is scoped to no single model. */
    val modelFamily: String? get() =
        if (perModel) label.substringBefore(" · ").trim().takeIf { it.isNotEmpty() } else null

    /** Compact name for the collapsed bar, used only when the drawn window is
     *  NOT the working one and therefore has to say which wall it describes:
     *  "Weekly · all models" → "Weekly", "Fable · weekly" → "Fable weekly". */
    val shortName: String get() = label
        .removeSuffix(" · all models")
        .removeSuffix(" limit")
        .replace(" · ", " ")
}

/** The reset moment as an absolute clock time IN THE DEVICE'S OWN TIMEZONE
 * ("10:30 AM"). The CLI reports the reset in its server's zone ("resets 8:30pm
 * (America/Los_Angeles)"), which reads as a wrong time to a user in another
 * zone. We hold the absolute epoch, so we render it in the user's local zone and
 * the confusion goes away. */
fun usageResetClock(
    epochMs: Long,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    locale: java.util.Locale = java.util.Locale.US,
): String {
    val reset = java.time.Instant.ofEpochMilli(epochMs).atZone(zone)
    val today = java.time.ZonedDateTime.now(zone).toLocalDate()
    // A weekly reset lands DAYS away, so a bare "12:00 AM" is ambiguous. Name
    // the weekday when it isn't today: "Sat 12:00 AM". Same-day (5-hour)
    // stays clean.
    val pattern = if (reset.toLocalDate() == today) "h:mm a" else "EEE h:mm a"
    return java.time.format.DateTimeFormatter.ofPattern(pattern, locale).format(reset)
}

/** Parse an ISO-8601 instant (the usage endpoint's `resets_at`, or the CLI's
 *  own timestamp) to epoch millis; null if it isn't a parseable instant. */
fun parseIsoInstant(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return runCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.ZonedDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
}

/** Human "time until" for a countdown of [secs] seconds: "45m", "2h40m", "3d".
 * EMPTY once the moment is here/past — the bar then shows the bare percent
 * instead of a lying "resets now". A stale reset that never ticks past is the
 * common rate-limited case: the account stays at 100%, no turn finishes to
 * trigger a refetch, and the probe can't read a fresh reset for an
 * inference-only token anyway. Shared by [UsageWindow.resetTextLive] and the
 * usage bar's CLI-reset path. */
fun usageCountdownText(secs: Long): String = when {
    secs <= 0 -> ""
    secs < 3600 -> "${secs / 60}m"
    // Hours with the minute remainder ("2h40m", not a floor to "2h") — the CLI
    // shows the absolute reset time, and a whole-hour floor made the app look
    // 40 minutes behind it (user, 2026-07-03). Exact-hour boundaries still
    // render clean ("3h").
    secs < 86_400 -> {
        val m = (secs % 3600) / 60
        if (m == 0L) "${secs / 3600}h" else "${secs / 3600}h${m}m"
    }
    // Days with the hour remainder ("2d16h", not a floor to "2d") — same reason
    // as the hours branch above. A weekly reset renders its WEEKDAY next to the
    // countdown ("Sun 5:00 AM (2d)"), and on Thursday a floored "2d" reads as a
    // contradiction — the calendar says Sunday is three sleeps away. Exact-day
    // boundaries still render clean ("3d").
    else -> {
        val h = (secs % 86_400) / 3600
        if (h == 0L) "${secs / 86_400}d" else "${secs / 86_400}d${h}h"
    }
}

/** All plan windows for an account. [windows] is ordered with the nearest /
 *  primary window first — that's what the compact bar shows; the tap-to-open
 *  sheet shows the whole list. */
@Serializable
data class UsageReport(
    val windows: List<UsageWindow>,
    /** Claude's `extra_usage` overage spend in USD (beyond the plan), if the
     *  account reports it. Null for Codex/Gemini and API-key mode. */
    val extraUsedUsd: Double? = null,
    /** Plan tier label ("Max"/"Pro") for the limits-sheet header, read from the
     *  same 200 profile as the usage — so it's exactly as fresh as the windows
     *  the user is looking at (a separate status probe may not have run yet in
     *  this chat). Null for an inference-only setup-token (profile 403s),
     *  Codex/Gemini, API-key mode. */
    val plan: String? = null,
    /** When the DATA in this report was fetched from Anthropic — set ONLY for
     *  reports read from the CLI's on-disk cache (`cachedUsageUtilization.
     *  fetchedAtMs` in ~/.claude.json). Live-channel and curl reports leave it
     *  null: they are fresh by construction. Consumers use it two ways: the
     *  probe rejects a cache older than the CLI's own 1-hour trust window, and
     *  the rate-limited banner refuses to be cleared by a report that carries
     *  ANY age at all (a 40-min-old 82% must not un-declare a limit the CLI
     *  hit 10 minutes ago). */
    val fetchedAtEpochMs: Long? = null,
) {
    /**
     * The window that actually CONSTRAINS the account right now — the most-USED
     * one, not the first in display order.
     *
     * This used to be `windows.firstOrNull()`, i.e. always the 5-hour window,
     * and that made the collapsed bar lie in exactly the case where it matters:
     * on 2026-08-19 the CLI refused turns ("You've hit your session limit ·
     * resets 2:40pm") while the bar read a comfortable **15%** — the 5-hour
     * number — because the window that was pegged at 100% was a different one
     * (weekly, or the third-party-OAuth-app bucket that our own access path
     * lives in). A limit indicator that shows a window you are NOT blocked on
     * is worse than no indicator.
     *
     * Ranking key is [UsageWindow.usedFraction] — genuinely consumed — never
     * `fraction`/`percent`, which Codex inverts to "remaining". Ties go to the
     * aggregate window over a per-model one (it describes the whole account),
     * then to the one that resets soonest.
     */
    val primary: UsageWindow? get() = windows.maxWithOrNull(BINDING_ORDER)

    /** The windows that can constrain a session running [inForceModel] — every
     *  aggregate, plus only the per-model caps of that model's own family. */
    fun windowsInForce(inForceModel: String?): List<UsageWindow> =
        windows.filter { it.appliesTo(inForceModel) }

    /**
     * What the COLLAPSED bar shows for a session running [inForceModel] — and
     * whether that is the working window or an escalation.
     *
     * [primary] alone is the wrong answer here, and the user caught it on
     * 2026-08-20: an **Opus 5** chat showed a flat `100%` bar because the
     * account's **Fable weekly** cap was spent. Fable's cap cannot block an Opus
     * turn, so the bar was reporting a wall that was not in front of him — the
     * mirror image of the 2026-08-19 bug that [primary] was introduced to fix.
     *
     * Both failures die under one rule: show the window this session is
     * ACTUALLY subject to.
     *  - anchor = the aggregate window that rolls over soonest (the 5-hour on
     *    both Claude and Codex). That is the number the user watches, so it is
     *    where the bar rests.
     *  - escalate to the most-used eligible window once it is genuinely near the
     *    wall ([USAGE_ESCALATE_FRACTION] — the bar's own red tier): that is the
     *    08-19 case, and [UsageBarPick.escalated] tells the UI to NAME it,
     *    because a bare "100%" that actually means "weekly" reads as "all of it
     *    is gone".
     *  - per-model caps of other families never enter either choice.
     *
     * Unknown model (null/blank) keeps every window eligible: with nothing to
     * attribute against, a hidden block is worse than a named surprise.
     */
    fun barPick(inForceModel: String? = null): UsageBarPick? {
        val eligible = windowsInForce(inForceModel)
            .ifEmpty { return primary?.let { UsageBarPick(it, escalated = true) } }
        val worst = eligible.maxWithOrNull(BINDING_ORDER) ?: return null
        // Nearest reset = the shortest cadence, without having to recognise any
        // label ("5-hour · all models" on Claude, "5-hour limit" on Codex). Ties
        // and missing resets keep list order, where the 5-hour window is first.
        val anchor = eligible.filterNot { it.perModel }
            .minByOrNull { it.resetAtEpochMs ?: Long.MAX_VALUE }
            ?: return UsageBarPick(worst, escalated = true)
        return if (worst !== anchor && worst.usedFraction >= USAGE_ESCALATE_FRACTION &&
            worst.usedFraction > anchor.usedFraction
        ) {
            UsageBarPick(worst, escalated = true)
        } else {
            UsageBarPick(anchor, escalated = false)
        }
    }
}

/**
 * May a usage reading stamped [incomingAt] replace the one stamped [currentAt]?
 *
 * A reading with NO stamp was fetched live, just now, by construction — so it
 * outranks every stamped one and ties with another live one (a later live
 * reading refines an earlier one). A stamped reading comes from a store that is
 * trusted up to an hour old, and may only replace something at least as old.
 *
 * Without this rule the bar walked backwards every cycle: the refresh ladder
 * paints from several sources in turn, and the CLI's persisted state landed
 * between two live readings, so the same window rendered 87 → 86 → 87 → 86 on a
 * loop.
 */
internal fun usageReadingSupersedes(currentAt: Long?, incomingAt: Long?): Boolean =
    (incomingAt ?: Long.MAX_VALUE) >= (currentAt ?: Long.MAX_VALUE)

/** [UsageReport.barPick] result: the window to draw, plus whether it has to be
 *  NAMED because it is not the working window the bar normally rests on. */
data class UsageBarPick(val window: UsageWindow, val escalated: Boolean)

/** Ranking used to find the window that binds: most genuinely CONSUMED first —
 *  never `fraction`/`percent`, which Codex inverts to "remaining". Ties go to
 *  the aggregate over a per-model window (it describes the whole account), then
 *  to the one that resets soonest. */
private val BINDING_ORDER = compareBy<UsageWindow> { it.usedFraction }
    .thenBy { if (it.perModel) 0 else 1 }
    .thenByDescending { it.resetAtEpochMs ?: Long.MAX_VALUE }

/** Where a window stops being background detail and takes the collapsed bar
 *  over from the 5-hour anchor. The same 0.90 the bar already turns red at, so
 *  the number and the colour escalate together. */
const val USAGE_ESCALATE_FRACTION = 0.90f

/** Family words in a model id / alias / display label: `claude-opus-5`,
 *  `Opus 5 1M` and `opus` all reduce to `{opus}`. Vendor prefix and version
 *  noise drop out, so any two spellings of one model compare equal. */
internal fun modelFamilyTokens(s: String): Set<String> =
    Regex("[a-z]{3,}").findAll(s.lowercase())
        .map { it.value }
        .filterNot { it in NON_FAMILY_WORDS }
        .toSet()

private val NON_FAMILY_WORDS = setOf("claude", "latest", "weekly", "hour", "all", "models")

/** Does this window's cap apply to a session running [inForceModel]?
 *  Aggregates always do. A per-model cap applies to its own family only —
 *  Fable's weekly says nothing about an Opus turn. */
fun UsageWindow.appliesTo(inForceModel: String?): Boolean {
    if (!perModel) return true
    val running = inForceModel?.takeIf { it.isNotBlank() } ?: return true
    val family = modelFamilyTokens(modelFamily ?: return true)
    if (family.isEmpty()) return true
    val runningTokens = modelFamilyTokens(running)
    return family.any { it in runningTokens }
}

/** One row of Claude's `/context` breakdown (System prompt / System tools /
 *  Skills / Messages / Free space …) — label + token string + percent. */
@Serializable
data class ContextSegment(val label: String, val tokens: String, val percent: Float)

/**
 * Reads each provider's *plan* rate-limit windows — the "5-hour 14% · resets
 * 3h" + "Weekly 33%" data — over the pooled SSH, **server-side**, so the
 * credential never reaches the app (the user's hard rule: creds live on the
 * server, we never handle their values). For Claude we curl Anthropic's usage
 * endpoint ON the server with the token from `~/.claude/.credentials.json` and
 * read back only the percentages; for Codex we parse the session rollout's
 * `token_count.rate_limits` (same numbers Codex's own `/status` shows — it
 * carries BOTH `primary` (5h) and `secondary` (weekly)). Returns null when
 * there's no machine-readable plan limit (API-key mode, Gemini, no live link)
 * — the caller then shows $ spend / tokens instead.
 *
 * Undocumented/experimental surfaces, so we parse defensively and degrade to
 * null on any miss — a failure shows no limit rather than wrong data.
 */
object UsageProbe {

    // Last good report per (server, agent), so re-entering a chat shows the
    // bar INSTANTLY (no empty-then-pop) and a failed refresh never wipes it.
    // Kept warm by [fetch] from anywhere there's a connection (chat open, post-
    // turn, sessions-list prefetch).
    private val cache = ConcurrentHashMap<String, UsageReport>()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var loaded = false

    private fun key(serverId: String, agent: Agent) = "$serverId/${agent.name}"

    /** The cache key, for consumers that want to filter [updates]. */
    fun keyOf(serverId: String, agent: Agent) = key(serverId, agent)

    /** Emits the cache key whenever a remembered report is replaced, so an open
     *  chat repaints the INSTANT a fresher number lands — including one the
     *  provider pushed at us unasked. Without this a pushed answer would sit in
     *  the cache until the next poll came round to notice it, which is the
     *  waiting this whole path exists to delete. */
    private val _updates = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val updates: SharedFlow<String> = _updates.asSharedFlow()

    private fun diskFile(): File? = runCatching {
        File(ServiceLocator.appContext.filesDir, "usage-cache.json")
    }.getOrNull()

    /** Load persisted reports into memory once, early at app start, so the bar
     *  shows the LAST-KNOWN limit the INSTANT a chat opens — even for SK servers
     *  where we can't fetch until the user taps-to-connect (by which point
     *  they're already opening the chat). The live fetch then refreshes it in
     *  the background. Call from Application.onCreate. */
    fun preload() {
        if (loaded) return
        loaded = true
        // ⛔ READ IT HERE, NOT ON A COROUTINE. This used to be dispatched to
        // the IO pool, which on a cold start is already carrying the session
        // parse — so a chat could open, ask for the remembered limits and be
        // told there are none, purely because the file had not been read yet.
        // It is a few KB of JSON at process start; the pool it was waiting for
        // is the expensive thing.
        run {
            runCatching {
                val f = diskFile() ?: return@run
                if (!f.exists()) return@run
                val text = f.readText()
                // Current format carries the stamp. A file written by an older
                // build is a bare map — adopt it, but with NO stamp, so it can
                // still be shown as a last-known value and is never mistaken
                // for a fresh one.
                val stamped = runCatching {
                    json.decodeFromString<Map<String, Stamped>>(text)
                }.getOrNull()
                if (stamped != null) {
                    stamped.forEach { (k, v) ->
                        if (cache.putIfAbsent(k, v.report) == null) fetchedAt[k] = v.atMs
                    }
                } else {
                    json.decodeFromString<Map<String, UsageReport>>(text)
                        .forEach { (k, v) -> cache.putIfAbsent(k, v) }
                }
            }
        }
    }

    /** A remembered report plus WHEN WE ASKED FOR IT.
     *
     * ⛔ THE STAMP HAS TO BE ON DISK OR IT DOES NOT EXIST. It lived in a
     * memory-only map, so every cold start came up holding reports and no
     * times for them — [cachedFresh] rejected every one and the bar had
     * nothing to paint until a probe answered seconds later.
     *
     *  Deliberately NOT [UsageReport.fetchedAtEpochMs]: that field means "this
     *  DATA came out of the CLI's own on-disk cache and therefore carries
     *  age", and the rate-limited banner refuses to be cleared by any report
     *  carrying it. Overloading it would make every cached report look like a
     *  stale CLI reading. */
    @Serializable
    private data class Stamped(val report: UsageReport, val atMs: Long)

    private fun persistToDisk() {
        ioScope.launch {
            runCatching {
                val snapshot = cache.toMap().mapValues { (k, v) ->
                    Stamped(v, fetchedAt[k] ?: 0L)
                }
                diskFile()?.writeText(json.encodeToString(snapshot))
            }
        }
    }

    /** One way in: cache it, stamp it, persist it. Both writers did this by
     *  hand and the fetch path never stamped at all — so the value it saved
     *  sat on disk unjudgeable, which is the other half of the empty bar. */
    private fun store(k: String, report: UsageReport) {
        cache[k] = report
        fetchedAt[k] = System.currentTimeMillis()
        persistToDisk()
        _updates.tryEmit(k)
    }

    /**
     * Adopt the rate-limit body the Codex app-server PUSHED at us.
     *
     * ⛔ THE PROVIDER SENDS THIS UNASKED AND WE USED TO BIN IT. Codex emits
     * `account/rateLimits/updated` down the channel the chat already holds
     * whenever the windows move. It sat in that handler's discard bucket while
     * the limit bar spawned a SECOND `codex app-server` over a fresh ssh
     * channel, with eleven seconds of hard-coded sleeps, to ask for the very
     * numbers already being handed to us for free.
     */
    fun rememberCodexPush(serverId: String, paramsJson: String) {
        val text =
            if (paramsJson.contains("\"rateLimits\"")) paramsJson
            else "{" + "\"rateLimits\"" + ":" + paramsJson + "}"
        reportFromCodex(text)?.let { remember(serverId, Agent.CODEX, it) }
    }

    /** Last known report (cache hit) — instant, no SSH. Null if never fetched. */
    fun cached(serverId: String, agent: Agent): UsageReport? = cache[key(serverId, agent)]

    /** Printed by the server-side commands when there is NO credential to ask
     * It splits "the SSH blinked" (keep last good, warm-state rule) from
     * "logged out" (there is no truth to show) — without it both looked like an
     * empty output, and the bar kept showing a deleted account's limits. */
    internal const val NOAUTH_MARKER = "CONCH_NOAUTH"

    /** Drop everything remembered for (server, agent): the account behind the
     *  numbers is gone (logged out / switched). Memory + disk + the CLI-cache
     *  memo, so no path can resurrect a dead account's percentages. */
    fun forget(serverId: String, agent: Agent) {
        cache.remove(key(serverId, agent))
        if (agent == Agent.CLAUDE) cliCacheMemo.remove(serverId)
        persistToDisk()
    }

    /** Adopt a report obtained elsewhere (the live control channel) into the
     *  same cache the fetch path warms, so chat re-opens stay instant.
     *
     * PER-MODEL CARRY-OVER: the per-model "second layer" (seven_day_fable, …)
     * comes back only on the full-oauth path — the fallback sources (CLI cache,
     * setup-token) return just the aggregates, and adopting such a report verbatim
     * made the Fable row FLAP in and out of the limits sheet. When the fresh
     * report has no per-model windows but the cached one does, carry the cached
     * ones over — they stay valid until their own reset passes (utilization only
     * grows between resets; slightly stale beats vanishing). */
    fun remember(serverId: String, agent: Agent, report: UsageReport) {
        store(key(serverId, agent), withPerModelCarryOver(serverId, agent, report))
        // ⛔ STAMP IT, OR IT CANNOT BE JUDGED LATER. A remembered report is
        // perfectly good for a minute and a lie after an hour, and without a
        // time on it the bar cannot tell those apart — which is how a "0%" from
        // a previous run got painted onto a window that had gone back to 74%.
    }

    private val fetchedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Below this age a reading is simply current — the warm loop re-asks every
     *  60-120 s, so anything younger than this came from the last poll or two
     *  and needs no further argument. Older than it, [cachedFresh] falls back
     *  to asking the WINDOW whether it is still the same window. */
    const val CACHE_TRUST_MS = 3 * 60_000L

    /**
     * The remembered report, but only while it still describes the present.
     *
     * ⛔ A FLAT TTL WAS THE WRONG QUESTION AND IT EMPTIED THE BAR. Three
     * minutes after the last successful poll the bar went blank on open, so a
     * phone that had been in a pocket for half an hour showed nothing —
     * while the CLI on the same account showed its numbers immediately.
     *
     * The provider already tells us the only thing that actually invalidates a
     * reading: when its window rolls. Inside that window a percentage can only
     * grow — usage is not given back — so a reading taken earlier in the SAME
     * window is a floor, not a guess, and showing it is honest. Once
     * `resetAtEpochMs` has passed, the window it describes no longer exists and
     * the number means nothing; that is the case that produced a stale "0%"
     * painted over a window that had gone back to 74%, and it is the case this
     * refuses.
     *
     * So: young enough to be current, OR still inside the window it measured.
     * A report with no reset stamp at all has nothing to check itself against
     * and gets the age rule alone.
     */
    fun cachedFresh(serverId: String, agent: Agent): UsageReport? {
        val k = key(serverId, agent)
        val rep = cache[k] ?: return null
        val at = fetchedAt[k] ?: return null
        val now = System.currentTimeMillis()
        if (now - at <= CACHE_TRUST_MS) return rep
        val resetAt = rep.windows.mapNotNull { it.resetAtEpochMs }.minOrNull() ?: return null
        return rep.takeIf { now < resetAt }
    }

    /** See [remember]. Public-ish so the fetch path applies the same rule. */
    internal fun withPerModelCarryOver(serverId: String, agent: Agent, fresh: UsageReport): UsageReport {
        // PLAN carry-over first, same reasoning as the windows: only the
        // full-oauth profile reports subscription_type; a CLI-cache or
        // setup-token refresh has none, and adopting its null verbatim made
        // the "Max" chip in the limits sheet vanish until the next full probe.
        val cachedRep = cache[key(serverId, agent)]
        val withPlan = if (fresh.plan == null && cachedRep?.plan != null) {
            fresh.copy(plan = cachedRep.plan)
        } else fresh
        if (withPlan.windows.any { it.perModel }) return withPlan
        val now = System.currentTimeMillis()
        val carried = cachedRep?.windows
            ?.filter { it.perModel && (it.resetAtEpochMs ?: 0L) > now }
            .orEmpty()
        return if (carried.isEmpty()) withPlan else withPlan.copy(windows = withPlan.windows + carried)
    }

    /**
     * Build a [UsageReport] from the CLI's `get_usage` control_response
     * payload — `{session:{total_cost_usd,…}, subscription_type,
     * rate_limits:{five_hour:{utilization,resets_at}, seven_day, …,
     * model_scoped:[{display_name,utilization,resets_at}]}|null, …}`.
     * The windows are the SAME shape the oauth/usage endpoint returns (the
     * CLI caches that endpoint), so the dynamic window parser is reused;
     * `resets_at` additionally arrives as EPOCH SECONDS here (the endpoint
     * sends ISO), which [parseClaude] now accepts. Null when the payload
     * carries no windows (API-key mode, inference-only token) — the caller
     * then falls back to the legacy probe.
     */
    fun reportFromControlPayload(payload: String): UsageReport? {
        val windows = parseClaude(payload) + parseModelScoped(payload)
        if (windows.isEmpty()) return null
        val plan = Regex("\"subscription_type\"\\s*:\\s*\"([a-z]+)\"").find(payload)
            ?.groupValues?.get(1)
            ?.replaceFirstChar { it.uppercase() }
        return UsageReport(
            windows = windows,
            extraUsedUsd = parseClaudeExtra(payload),
            plan = plan,
        )
    }

    /** The `model_scoped` array — per-model windows keyed by display name
     *  instead of a JSON key: `[{display_name,utilization,resets_at}]`.
     *  utilization may be null (window exists but idle) — skipped. */
    internal fun parseModelScoped(json: String): List<UsageWindow> {
        val arr = Regex("\"model_scoped\"\\s*:\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL)
            .find(json)?.groupValues?.get(1) ?: return emptyList()
        return Regex("\\{([^{}]*)\\}").findAll(arr).mapNotNull { m ->
            val body = m.groupValues[1]
            val name = Regex("\"display_name\"\\s*:\\s*\"([^\"]+)\"").find(body)
                ?.groupValues?.get(1) ?: return@mapNotNull null
            val util = Regex("\"utilization\"\\s*:\\s*([0-9.]+)").find(body)
                ?.groupValues?.get(1)?.toFloatOrNull() ?: return@mapNotNull null
            val resetEpochMs = windowResetEpochSec(body)?.let { it * 1000 }
            window(
                name, util,
                resetEpochMs?.let { secsToText((it - System.currentTimeMillis()) / 1000) }.orEmpty(),
                resetAtEpochMs = resetEpochMs, perModel = true,
            )
        }.toList()
    }

    /** `resets_at` as epoch seconds, whether the source wrote an ISO string
     *  (endpoint) or a raw number (CLI cache). */
    private fun windowResetEpochSec(body: String): Long? {
        Regex("\"resets_at\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            ?.let { return isoToEpoch(it) }
        return Regex("\"resets_at\"\\s*:\\s*([0-9]+)").find(body)?.groupValues?.get(1)
            ?.toLongOrNull()
            ?.let { if (it > 1_000_000_000_000L) it / 1000 else it }
    }

    /**
     * Map the CLI's `get_context_usage` payload to the panel's segments —
     * `{categories:[{name,tokens}], totalTokens, maxTokens, percentage}` →
     * the same rows the old markdown-table parse produced ("Context window"
     * first, then per-category). Empty on shape miss (caller falls back).
     */
    fun contextFromControlPayload(payload: kotlinx.serialization.json.JsonObject): List<ContextSegment> {
        fun num(key: String): Long? =
            (payload[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()
        val total = num("totalTokens") ?: return emptyList()
        val max = num("maxTokens") ?: return emptyList()
        if (max <= 0L) return emptyList()
        val pct = num("percentage")?.toFloat()
            ?: (total.toFloat() / max * 100f)
        val segs = mutableListOf(
            ContextSegment("Context window", "${kFmt(total)} / ${kFmt(max)}", pct),
        )
        val cats = payload["categories"] as? kotlinx.serialization.json.JsonArray ?: return segs
        for (c in cats) {
            val o = c as? kotlinx.serialization.json.JsonObject ?: continue
            val name = (o["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: continue
            val tokens = (o["tokens"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content?.toDoubleOrNull()?.toLong() ?: continue
            if (tokens <= 0L) continue
            // Round at the SOURCE too, not just in the renderer: the model
            // should carry a percentage, not a float artefact.
            segs += ContextSegment(
                name, kFmt(tokens),
                Math.round(tokens.toFloat() / max * 1000f) / 10f,
            )
        }
        return segs
    }

    /** Cache a control-channel context read under the chat's resume id so
     *  re-expanding the panel stays instant (same cache the probe warms). */
    fun rememberContext(resumeId: String, segments: List<ContextSegment>) {
        if (segments.isNotEmpty()) ctxCache[resumeId] = segments
    }

    /** 104728 → "104.7k"; 1000000 → "1.0M"; 900 → "900". Mirrors the CLI's
     *  own /context number style so both surfaces read the same. */
    internal fun kFmt(n: Long): String = when {
        n >= 1_000_000L -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0)
        n >= 1_000L -> String.format(java.util.Locale.US, "%.1fk", n / 1_000.0)
        else -> n.toString()
    }

    /** [fast]=true uses the cheap source that paints immediately (Codex: the
     *  rollout snapshot; Claude: the last cached report, no network) so the bar
     *  is up within a few hundred ms; [fast]=false hits the live source (Codex:
     *  `codex app-server`; Claude: the usage curl) to refine. Both cache on
     *  success so the next chat open is instant. */
    suspend fun fetch(serverId: String, agent: Agent, fast: Boolean = false): UsageReport? {
        val cmd = when (agent) {
            Agent.CLAUDE -> if (fast) return cached(serverId, agent) else CLAUDE_USAGE_CMD
            Agent.CODEX -> if (fast) CODEX_FAST_CMD else CODEX_LIVE_CMD
            Agent.GEMINI -> GEMINI_USAGE_CMD
            Agent.COPILOT -> COPILOT_USAGE_CMD
            // Grok bills in grok.com credits (weekly/monthly windows live
            // behind its billing endpoint / ACP x.ai/session/usage — a
            // follow-up); Copilot bills in AI credits, surfaced per-turn from
            // the stream's assistant.usage events. Neither has a plan-window
            // probe yet → the panel shows this chat's spend instead.
            // Qwen bills through whatever OpenAI-compatible endpoint the user
            // configured (its own spend ledgers live in ~/.qwen/usage/, which
            // is spend, not a plan window); Cursor's quota is behind an
            // auth-gated surface with no machine-readable form.
            // opencode and Crush bill through whichever provider the user
            // configured, so there is no plan window to read; their per-turn
            // spend rides the stream instead.
            Agent.GROK, Agent.QWEN, Agent.CURSOR,
            Agent.OPENCODE, Agent.CRUSH, Agent.CONTINUE -> return null
        }
        val out = execOnServer(serverId, cmd)?.takeIf { it.isNotBlank() } ?: return null
        // The server answered and said "no credentials": this is a real logout,
        // not a hiccup — purge the remembered report so the bar goes honest
        // instead of showing the dead account forever.
        if (out.contains(NOAUTH_MARKER)) {
            forget(serverId, agent)
            return null
        }
        val raw: UsageReport? = when (agent) {
            // Claude now returns a `get_usage` control_response line, not the
            // raw endpoint JSON — the CLI itself produced it (see
            // [CLAUDE_USAGE_CMD]). [reportFromControlPayload] reads its windows,
            // model_scoped rows, subscription_type and extra_usage in one pass —
            // exactly the parse the live-channel path already uses.
            Agent.CLAUDE -> reportFromControlPayload(out)
            Agent.CODEX -> parseCodex(out).takeIf { it.isNotEmpty() }
                ?.let { UsageReport(windows = it) }
            Agent.GEMINI -> reportFromGemini(out)
            Agent.COPILOT -> reportFromCopilot(out)
            Agent.GROK,
            Agent.QWEN, Agent.CURSOR, Agent.OPENCODE, Agent.CRUSH,
            Agent.CONTINUE -> null
        }
        // Merge BEFORE caching — a fallback-source report without the
        // per-model layer must not clobber the carried rows (see
        // [withPerModelCarryOver]); returned merged too, so the caller
        // displays exactly what the cache holds.
        val report = raw?.let { withPerModelCarryOver(serverId, agent, it) }
        if (report != null) {
            store(key(serverId, agent), report) // last good, stamped, on disk
        }
        return report
    }

    // ---- Claude: the CLI's OWN on-disk usage cache (~/.claude.json). ----
    //
    // Measured 2026-08-17 (claude 2.1.220/2.1.233 binary + the dev server): every
    // Anthropic API response carries `anthropic-ratelimit-unified-*` headers, and
    // the CLI persists them into ~/.claude.json as cachedUsageUtilization: {
    // fetchedAtMs, accountUuid, utilization: { five_hour:{utilization,resets_at},
    // seven_day:{…}, …,
    // limits:[{kind,group,percent,resets_at,scope:{model:{display_name}}}],
    // extra_usage:{…}, spend:{…} } } throttled to ONE write per 5 minutes and
    // trusted by the CLI itself for 1 hour — its own `get_usage` falls back to
    // this exact field with source:"persisted". Reading the file is therefore the
    // SAME truth the control channel returns, minus the need for a live process:
    // the fix for an idle chat's stale bar. Cleared on logout; absent until the
    // first API call.
    //
    // Wire cost, measured on the dev server: the read is mtime-gated, so an
    // unchanged file costs ~60 B per poll; a changed 68 KB file ships as
    // ~24 KB of base64'd gzip (plain-cat fallback for BusyBox hosts). Files
    // over the size cap are skipped outright — .claude.json also accumulates
    // project history and can bloat; the bar then falls back to the curl path.

    /** One trust window, same as the CLI's own (LZg=3600000 in the binary): a
     *  persisted reading older than this is not shown as current. */
    internal const val CLI_CACHE_TRUST_MS = 3_600_000L

    /** Don't pull a bloated ~/.claude.json down a phone link for a status bar. */
    private const val CLI_CACHE_MAX_BYTES = 2_097_152L

    /** mtime → parsed report, per server — the "unchanged file costs ~60 B"
     *  half of the bargain. */
    private data class CliCacheMemo(val mtime: String, val report: UsageReport?)
    private val cliCacheMemo = ConcurrentHashMap<String, CliCacheMemo>()

    /** The mtime-gated read: prints `CONCH_UMT:<mtime>,<size>` always, the
     *  (compressed) body only when the file changed AND fits the cap. */
    internal fun cliCacheCmd(knownMtime: String): String =
        // Credential gate first: after OUR logout (vault remove) the CLI's
        // persisted usage block survives in ~/.claude.json for up to its 1-h
        // trust window — reading it would resurrect the deleted account's
        // percentages. No token → say so and stop.
        "C=\"\$HOME/.claude/.credentials.json\"; [ -f \"\$C\" ] || C=\"\$HOME/.config/claude/.credentials.json\"; " +
            "TOK=\$(sed -n -E 's/.*\"access_?[Tt]oken\"[[:space:]]*:[[:space:]]*\"([^\"]*)\".*/\\1/p' \"\$C\" 2>/dev/null | head -1); " +
            "[ -z \"\$TOK\" ] && TOK=\"\$CLAUDE_CODE_OAUTH_TOKEN\"; " +
            "[ -z \"\$TOK\" ] && { echo CONCH_NOAUTH; exit 0; }; " +
            "f=\"\$HOME/.claude.json\"; " +
            "m=\$(stat -c %Y \"\$f\" 2>/dev/null || stat -f %m \"\$f\" 2>/dev/null); " +
            "s=\$(stat -c %s \"\$f\" 2>/dev/null || stat -f %z \"\$f\" 2>/dev/null || wc -c < \"\$f\" 2>/dev/null); " +
            "echo \"CONCH_UMT:\${m:-none},\${s:-0}\"; " +
            "if [ -n \"\$m\" ] && [ \"\$m\" != \"" + knownMtime + "\" ] && [ \"\${s:-0}\" -le $CLI_CACHE_MAX_BYTES ]; then " +
            "gzip -c \"\$f\" 2>/dev/null | base64 2>/dev/null || cat \"\$f\"; fi"

    /**
     * Read the CLI's persisted usage state over the pooled SSH. Returns a
     * report ONLY when the reading is within [CLI_CACHE_TRUST_MS] of now —
     * beyond that the caller falls to the curl path, exactly like the CLI
     * itself stops trusting its persisted value. Null on: no pooled client,
     * no file, no `cachedUsageUtilization` yet, over-cap file, stale reading.
     */
    suspend fun fetchClaudeCliCache(serverId: String): UsageReport? {
        val last = cliCacheMemo[serverId]
        val out = execOnServer(serverId, cliCacheCmd(last?.mtime ?: "none"))
            ?.takeIf { it.isNotBlank() } ?: return null
        if (out.contains(NOAUTH_MARKER)) {
            forget(serverId, Agent.CLAUDE)
            return null
        }
        // Digits-or-none ONLY: the mtime is echoed back into the NEXT read's
        // shell command, so the accepted alphabet is the injection guard.
        val marker = Regex("CONCH_UMT:([0-9]+|none),(\\d+)").find(out) ?: return null
        val mtime = marker.groupValues[1]
        if (mtime == "none") return null
        val report: UsageReport?
        if (last != null && last.mtime == mtime) {
            report = last.report
        } else {
            val body = out.substring(marker.range.last + 1).trim()
            val json = decodeMaybeGzipBase64(body) ?: return null
            report = reportFromCliCacheJson(json)
            cliCacheMemo[serverId] = CliCacheMemo(mtime, report)
        }
        val fetchedAt = report?.fetchedAtEpochMs ?: return null
        return report.takeIf { System.currentTimeMillis() - fetchedAt in 0..CLI_CACHE_TRUST_MS }
    }

    /** The body is either base64'd gzip (GNU/BSD hosts) or plain JSON (the
     *  cat fallback). Whitespace inside base64 is fine — no -w0 dependency. */
    private fun decodeMaybeGzipBase64(body: String): String? {
        if (body.isEmpty()) return null
        if (body.startsWith("{")) return body
        return runCatching {
            val raw = java.util.Base64.getMimeDecoder().decode(body)
            java.util.zip.GZIPInputStream(raw.inputStream()).use {
                String(it.readBytes(), Charsets.UTF_8)
            }
        }.getOrNull()
    }

    /** Parse the `cachedUsageUtilization` block out of a raw ~/.claude.json.
     *  Exposed internal for the fixture test (a REAL capture, 2026-08-17). */
    internal fun reportFromCliCacheJson(fileJson: String): UsageReport? {
        val block = braceBlockAfter(fileJson, "\"cachedUsageUtilization\"") ?: return null
        val fetchedAt = Regex("\"fetchedAtMs\"\\s*:\\s*([0-9]+)").find(block)
            ?.groupValues?.get(1)?.toLongOrNull() ?: return null
        // The aggregate windows parse exactly like the oauth endpoint payload
        // (same shape — the CLI caches that endpoint); the per-model layer here
        // lives in `limits[]` as scoped entries instead of `model_scoped`.
        val windows = parseClaude(block) + parseScopedLimits(block)
        if (windows.isEmpty()) return null
        return UsageReport(
            windows = windows,
            extraUsedUsd = parseClaudeExtra(block),
            fetchedAtEpochMs = fetchedAt,
        )
    }

    /**
     * `limits[]` → per-model windows. Only entries scoped to a MODEL become
     * rows (`scope.model.display_name`, e.g. kind=weekly_scoped for Fable) —
     * the unscoped session/weekly_all entries duplicate `five_hour`/
     * `seven_day`, which [parseClaude] already produced. `group` names the
     * cadence ("session"/"weekly"), matching the label style of the endpoint's
     * per-model window keys (five_hour_opus, seven_day_fable, …).
     */
    internal fun parseScopedLimits(block: String): List<UsageWindow> {
        val arr = bracketBlockAfter(block, "\"limits\"") ?: return emptyList()
        return jsonObjectsOf(arr).mapNotNull { obj ->
            val model = braceBlockAfter(obj, "\"model\"") ?: return@mapNotNull null
            val name = Regex("\"display_name\"\\s*:\\s*\"([^\"]+)\"").find(model)
                ?.groupValues?.get(1) ?: return@mapNotNull null
            val pct = Regex("\"percent\"\\s*:\\s*([0-9.]+)").find(obj)
                ?.groupValues?.get(1)?.toFloatOrNull() ?: return@mapNotNull null
            val group = Regex("\"group\"\\s*:\\s*\"([a-z_]+)\"").find(obj)?.groupValues?.get(1)
            val resetEpochMs = windowResetEpochSec(obj)?.let { it * 1000 }
            val cadence = when (group) {
                "weekly" -> " · weekly"
                "session" -> " · 5-hour"
                else -> ""
            }
            window(
                "$name$cadence", pct,
                resetEpochMs?.let { secsToText((it - System.currentTimeMillis()) / 1000) }.orEmpty(),
                resetAtEpochMs = resetEpochMs, perModel = true,
            )
        }.toList()
    }

    /** `{…}` block that follows [key], brace-depth matched, string-aware (a
     *  quoted `{` inside a disclaimer must not unbalance the scan). Null when
     *  the key or its object is absent/truncated — a torn mid-write read then
     *  degrades to "no report" instead of a garbage parse. */
    private fun braceBlockAfter(json: String, key: String): String? =
        delimitedBlockAfter(json, key, '{', '}')

    /** `[…]` sibling of [braceBlockAfter]. */
    private fun bracketBlockAfter(json: String, key: String): String? =
        delimitedBlockAfter(json, key, '[', ']')

    private fun delimitedBlockAfter(json: String, key: String, open: Char, close: Char): String? {
        val at = json.indexOf(key).takeIf { it >= 0 } ?: return null
        val start = json.indexOf(open, at + key.length).takeIf { it >= 0 } ?: return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until json.length) {
            val c = json[i]
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == open -> depth++
                !inString && c == close -> {
                    depth--
                    if (depth == 0) return json.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** Top-level `{…}` objects of a JSON array body (depth-1 split). */
    private fun jsonObjectsOf(arrayBlock: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < arrayBlock.length) {
            if (arrayBlock[i] == '{') {
                val obj = delimitedBlockAfter(arrayBlock.substring(i), "", '{', '}') ?: break
                out += obj
                i += obj.length
            } else i++
        }
        return out
    }

    // In-memory cache of the last context breakdown per chat, so re-expanding
    // the panel is instant (the fetch is slow — spawns a CLI).
    private val ctxCache = ConcurrentHashMap<String, List<ContextSegment>>()
    fun cachedContext(resumeId: String): List<ContextSegment>? = ctxCache[resumeId]

    /** Claude `/context` breakdown for one chat. Runs on a THROWAWAY COPY of
     *  the session jsonl — verified safe: copy → rewrite id → `claude -p
     *  /context` leaves the REAL session untouched and costs 0 tokens
     *  (`<synthetic>`). Claude-only; null otherwise / on any miss. Slow
     *  (~15-30s, spawns the CLI), so callers should show a spinner. */
    suspend fun fetchContextBreakdown(serverId: String, resumeId: String): List<ContextSegment>? {
        // resumeId is injected into a shell command — accept only UUID shape.
        if (!Regex("^[a-fA-F0-9-]{16,40}$").matches(resumeId)) return null
        val out = execOnServer(serverId, contextProbeScript(resumeId), timeoutSec = 60)
            ?.takeIf { it.isNotBlank() } ?: return null
        val segs = parseClaudeContext(out).ifEmpty { return null }
        ctxCache[resumeId] = segs
        return segs
    }

    /** Parse the markdown `/context` output: the "**Tokens:** X / Y (Z%)" total
     *  (prepended as a "Context window" row so the window shows from the probe
     *  itself, not the flaky costStats) + the "Estimated usage by category"
     *  table rows (`| Label | 16k | 1.6% |`). */
    private fun parseClaudeContext(md: String): List<ContextSegment> {
        val segs = mutableListOf<ContextSegment>()
        Regex("Tokens:\\**\\s*([0-9.]+[kKmMgG]?)\\s*/\\s*([0-9.]+[kKmMgG]?)\\s*\\(([0-9.]+)\\s*%\\)")
            .find(md)?.let { m ->
                segs += ContextSegment("Context window", "${m.groupValues[1]} / ${m.groupValues[2]}", m.groupValues[3].toFloatOrNull() ?: 0f)
            }
        val section = md.substringAfter("Estimated usage by category", "").substringBefore("\n###", "")
        val row = Regex("^\\|\\s*([A-Za-z][^|]*?)\\s*\\|\\s*([^|]+?)\\s*\\|\\s*([0-9.]+)\\s*%\\s*\\|\\s*$")
        section.lineSequence().forEach { line ->
            val m = row.find(line.trim()) ?: return@forEach
            val label = m.groupValues[1].trim()
            if (label.equals("Category", ignoreCase = true)) return@forEach
            segs += ContextSegment(label, m.groupValues[2].trim(), m.groupValues[3].toFloatOrNull() ?: 0f)
        }
        return segs
    }

    /** Fresh channel on the pooled client, exec, return stdout (or null).
     *  [timeoutSec] bounds the wait — usage curls are quick (8s); the Claude
     *  /context probe spawns a CLI and needs much longer. */
    private fun execOnServer(serverId: String, cmd: String, timeoutSec: Long = 8): String? {
        val client = ServiceLocator.sshConnectionPool.peek(serverId) ?: return null
        return SilentlyTry.logged("Conch-Usage", "fetch usage") {
            val sess = client.startSession()
            try {
                val proc = sess.exec(RemoteEnv.portable("bash -lc " + shellEscape(cmd)))
                val out = ByteArrayOutputStream()
                // Bounded read: the deadline wraps the READ, not the join after it.
                ai.eight24family.conch.ssh.BoundedExec.drain(
                    proc, out,
                    deadlineMs = ai.eight24family.conch.ssh.BoundedExec.Deadline.INTERACTIVE_MS,
                    maxBytes = ai.eight24family.conch.ssh.BoundedExec.Cap.INTERACTIVE,
                )
                proc.join(timeoutSec, TimeUnit.SECONDS)
                String(out.toByteArray(), Charsets.UTF_8)
            } finally {
                SilentlyTry.fired("Conch-Usage", "close usage session") { sess.close() }
            }
        }
    }

    // ---- Claude: api/oauth/usage — DYNAMIC. Anthropic returns a flat map of
    //      rate windows: five_hour, seven_day, and a per-model "second layer"
    //      (seven_day_opus, seven_day_sonnet, and — as new models ship —
    //      seven_day_fable, five_hour_opus, …). We parse EVERY window object the
    //      endpoint hands back instead of a hardcoded four, so a new model's cap
    //      (Fable 5 today, whatever ships next) surfaces on its own instead of
    //      being silently dropped (user 2026-07-16). ----
    internal fun parseClaude(json: String): List<UsageWindow> {
        // Any FLAT object carrying "utilization" is a rate window, wherever it
        // sits (top-level or nested under a per-model container) — [^{}] keeps
        // the match to a single leaf object. `extra_usage` (used_credits, no
        // utilization) and non-window objects are skipped by that check.
        val re = Regex("\"([a-z][a-z0-9_]*)\"\\s*:\\s*\\{([^{}]*?\"utilization\"[^{}]*?)\\}")
        val seen = HashSet<String>()
        return re.findAll(json).mapNotNull { m ->
            val key = m.groupValues[1]
            if (key == "extra_usage" || !seen.add(key)) return@mapNotNull null
            val body = m.groupValues[2]
            val util = Regex("\"utilization\"\\s*:\\s*([0-9.]+)").find(body)
                ?.groupValues?.get(1)?.toFloatOrNull() ?: return@mapNotNull null
            // resets_at is an ISO string from the oauth endpoint, but EPOCH
            // SECONDS in the CLI's get_usage cache — accept both.
            val resetEpochSec = windowResetEpochSec(body)
            val resetEpochMs = resetEpochSec?.let { it * 1000 }
            // An UNRECOGNISED window sitting at exactly 0 % with no reset time
            // carries no information: it is a bucket this account has never
            // touched. Claude's payload ships internal codenames in that state —
            // `nimbus_quill` turned up on the user's panel as "Nimbus quill · 0%"
            // — and printing them turns a usage panel into a changelog of
            // unreleased models. The canonical windows are never hidden (a real 0
            // % on the weekly is real news), and any unknown bucket that has
            // usage OR a live reset still shows up on its own, so a genuinely new
            // model appears the moment it costs something. That was the point of
            // parsing every window.
            val known = key == "five_hour" || key == "seven_day" ||
                key.startsWith("five_hour_") || key.startsWith("seven_day_")
            if (!known && util <= 0f && resetEpochSec == null) return@mapNotNull null
            val perModel = key != "five_hour" && key != "seven_day" &&
                (key.startsWith("five_hour_") || key.startsWith("seven_day_"))
            key to window(
                claudeWindowLabel(key), util,
                resetEpochSec?.let { secsToText(it - Instant.now().epochSecond) }.orEmpty(),
                resetAtEpochMs = resetEpochMs, perModel = perModel,
            )
        }.sortedBy { claudeWindowOrder(it.first) }.map { it.second }.toList()
    }

    /** Human label for a usage-window key. Known windows get a curated name;
     *  any `five_hour_<model>` / `seven_day_<model>` (opus/sonnet/fable/…) is
     *  derived generically, so an unseen model still reads cleanly. */
    private fun claudeWindowLabel(key: String): String = when {
        key == "five_hour" -> "5-hour · all models"
        key == "seven_day" -> "Weekly · all models"
        key.startsWith("seven_day_") -> "${modelLabel(key.removePrefix("seven_day_"))} · weekly"
        key.startsWith("five_hour_") -> "${modelLabel(key.removePrefix("five_hour_"))} · 5-hour"
        else -> key.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** "opus" → "Opus", "claude_fable" → "Claude Fable". */
    private fun modelLabel(m: String): String =
        m.split('_').joinToString(" ") { p -> p.replaceFirstChar { it.uppercase() } }

    /** Display order: the two aggregate windows first (5-hour, then weekly),
     *  then the per-model "second layer" grouped after. */
    private fun claudeWindowOrder(key: String): Int = when {
        key == "five_hour" -> 0
        key == "seven_day" -> 1
        key.startsWith("five_hour_") -> 2
        key.startsWith("seven_day_") -> 3
        else -> 4
    }

    /** Claude's usage endpoint also carries an `extra_usage` block — the
     *  pay-as-you-go overage beyond the plan. Returns dollars spent
     *  (`used_credits`), or null when absent. */
    private fun parseClaudeExtra(json: String): Double? {
        val body = Regex("\"extra_usage\"\\s*:\\s*\\{([^{}]*)\\}").find(json)?.groupValues?.get(1) ?: return null
        return Regex("\"used_?credits\"\\s*:\\s*([0-9.]+)").find(body)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    // ---- Codex: live `codex app-server` account/rateLimits/read (camelCase)
    //      with a fallback to the latest rollout's snake_case rate_limits. ----
    //
    // ⚠ `primary` / `secondary` ARE NOT "5-hour" AND "weekly". Those are Claude's
    // window sizes, and the labels were hard-coded to the KEY, so a bar could read
    // "5-hour limit · resets Sun 9:03 PM (3d8h)" — a claim its own reset time
    // disproves (user, 2026-08-06). The payload states the size and this code was
    // already reading it for the reset maths: on that account
    // `"primary":{"used_percent":47.0,"window_minutes":10080,…}` — 10080 minutes,
    // a SEVEN-DAY window sitting under the key we called five-hourly. The label
    // is derived from that number now.
    /** Test seam: the same windows the bar draws, from a raw Codex payload.
     *  Pinned by CodexCreditsAndResetTest against real provider answers. */
    internal fun reportFromCodex(out: String): UsageReport? =
        parseCodex(out).takeIf { it.isNotEmpty() }?.let { UsageReport(windows = it) }

    /**
     * Is this payload a LIVE answer, or a snapshot read off a session file?
     *
     * ⛔ ONLY A LIVE ANSWER MAY SUPPLY A RESET TIME. The rollout snapshot is a
     * record of a past moment: measured 2026-09-12, its `resets_at` said 17:24
     * while the CLI's own panel said 20:40 — three and a quarter hours out,
     * because the window had rolled since the line was written. A percentage
     * that is a little behind is a small lie; a reset time that is a little
     * behind is a countdown to nothing. So the snapshot keeps the percentages
     * and loses the clock, and a failed live probe shows no countdown rather
     * than a confident wrong one.
     */
    private fun codexPayloadIsLive(out: String): Boolean = out.contains("\"rateLimits\"")

    private fun parseCodex(out: String): List<UsageWindow> = buildList {
        // The provider's windows, exactly as it reports them — the same two rows
        // Codex's own panel shows, with the same numbers and the same times.
        //
        // ⛔ NOTHING INVENTED ALONGSIDE THEM. A "Credits" row was added here to
        // explain a blocked account and it was wrong twice over: it first
        // REPLACED these windows, so the sheet showed one synthetic row where
        // the CLI shows the user's real limits, and even beside them it is a row
        // the native app does not have. Codex states the credit block as a
        // SENTENCE under the windows, not as a window; a window is a percentage
        // with a clock, and credits are neither.
        // ⛔ A SNAPSHOT IS NOT AN ANSWER. The rollout's rate_limits line records
        // a past moment, and on 2026-09-12 it was wrong on BOTH axes at once:
        // it said 1% left resetting at 17:24 while the CLI's own panel said 84%
        // left resetting at 20:40. Every "why doesn't it match the CLI" that
        // day traced back to it. So it is not a fallback any more — with no live
        // answer the app reports nothing and shows nothing, which is a state the
        // user can read, unlike a confident wrong number.
        if (!codexPayloadIsLive(out)) return@buildList
        codexWindow(out, "primary", "Usage limit", live = true)?.let { add(it) }
        codexWindow(out, "secondary", "Secondary limit", live = true)?.let { add(it) }
    }

    /** `has_credits:false` / `hasCredits:false`, or any `rate_limit_reached_type`
     *  naming credits. Matches the rollout's snake_case and the app-server's
     *  camelCase, because the probe may answer in either. */
    internal fun codexCreditsDepleted(out: String): Boolean =
        Regex("""has_?[Cc]redits"\s*:\s*false""").containsMatchIn(out) ||
            Regex("""rate_?[Ll]imit_?[Rr]eached_?[Tt]ype"\s*:\s*"[^"]*credits[^"]*"""")
                .containsMatchIn(out)

    /**
     * @param fallbackLabel used ONLY when the payload does not state the window
     *   size. It carries NO duration — inventing "5-hour" from a default is the
     *   bug above. Same rule as the topbar's effort: show the real value or
     *   nothing (NO-INVENTED-EFFORT-IN-THE-TOPBAR-1).
     * @param defaultWindowSec still needed for the reset projection, which has to
     *   pick some cadence; it never reaches the label.
     */
    /**
     * Gemini's quota buckets, read the way its own CLI reads them.
     *
     * `Config.refreshUserQuota()` iterates `quota.buckets`, skips any entry
     * missing `modelId` or `remainingFraction`, and keeps
     * {remaining, limit, resetTime} per model. We do the same and no more: the
     * fraction is the provider's, the reset stamp is the provider's, and a
     * bucket that carries neither draws nothing.
     *
     * One row per model, shown as REMAINING, because that is the direction
     * `remainingFraction` is stated in and the direction the CLI reports.
     *
     * NOT built from `availableCredits` / `getG1CreditBalance` — that is a
     * wallet balance with no denominator and no reset, and a percentage bar
     * drawn from it would be invented. Same rule that killed the fabricated
     * Codex "Credits" row.
     */
    internal fun reportFromGemini(out: String): UsageReport? {
        if (!out.contains("buckets")) return null
        val now = System.currentTimeMillis()
        val rows = Regex("\\{([^{}]*)\\}").findAll(out).mapNotNull { m ->
            val b = m.groupValues[1]
            val model = Regex("\"modelId\"\\s*:\\s*\"([^\"]+)\"").find(b)
                ?.groupValues?.get(1) ?: return@mapNotNull null
            val frac = Regex("\"remainingFraction\"\\s*:\\s*([0-9.eE+-]+)").find(b)
                ?.groupValues?.get(1)?.toFloatOrNull() ?: return@mapNotNull null
            val at = parseIsoInstant(
                Regex("\"resetTime\"\\s*:\\s*\"([^\"]+)\"").find(b)?.groupValues?.get(1),
            )?.takeIf { it > now }
            window(
                label = model,
                raw = (1f - frac.coerceIn(0f, 1f)) * 100f,
                resetText = at?.let { secsToText((it - now) / 1000) } ?: "",
                remaining = true,
                resetAtEpochMs = at,
            )
        }.toList()
        return rows.takeIf { it.isNotEmpty() }?.let { UsageReport(windows = it) }
    }

    /** The quotas Copilot reports, in the order they matter to someone using
     *  it through Conch: the premium pool runs out first and is what blocks a
     *  turn. Labels are ours; the numbers are entirely the provider's. */
    private val COPILOT_QUOTAS = listOf(
        "premium_interactions" to "Premium",
        "chat" to "Chat",
        "completions" to "Completions",
    )

    /**
     * Copilot's quota snapshots from `account.getCurrentAuth`.
     *
     * Percentage: `percent_remaining` per snapshot, shown as remaining, which
     * is how the provider states it. An `unlimited` snapshot is skipped rather
     * than drawn as 100% — an unlimited pool is not a full one, and a bar that
     * can never move is noise.
     *
     * Reset: `quota_reset_date_utc`, and ONLY that. `account.getQuota` also
     * returns a `resetDate`, and it is a decoy — the CLI assigns it
     * `timestamp_utc`, the moment the snapshot was taken, so it tracks the wall
     * clock and a countdown built on it would never count down.
     */
    internal fun reportFromCopilot(out: String): UsageReport? {
        if (!out.contains("quota_snapshots")) return null
        val now = System.currentTimeMillis()
        val at = parseIsoInstant(
            Regex("\"quota_reset_date_utc\"\\s*:\\s*\"([^\"]+)\"").find(out)?.groupValues?.get(1),
        )?.takeIf { it > now }
        val rt = at?.let { secsToText((it - now) / 1000) } ?: ""
        val rows = buildList {
            for ((key, label) in COPILOT_QUOTAS) {
                val b = Regex("\"" + key + "\"\\s*:\\s*\\{([^{}]*)\\}")
                    .find(out)?.groupValues?.get(1) ?: continue
                if (Regex("\"unlimited\"\\s*:\\s*true").containsMatchIn(b)) continue
                val rem = Regex("\"percent_remaining\"\\s*:\\s*([0-9.]+)").find(b)
                    ?.groupValues?.get(1)?.toFloatOrNull() ?: continue
                add(
                    window(
                        label = label,
                        raw = 100f - rem.coerceIn(0f, 100f),
                        resetText = rt,
                        remaining = true,
                        resetAtEpochMs = at,
                    ),
                )
            }
        }
        return rows.takeIf { it.isNotEmpty() }?.let { UsageReport(windows = it) }
    }

    private fun codexWindow(out: String, key: String, fallbackLabel: String, live: Boolean): UsageWindow? {
        // First flat "{...}" block under this key. With BOTH the live (camelCase)
        // line and the rollout (snake_case) line present, `find` takes the live
        // one first; if that block is nested/garbage the regex skips it and
        // lands on the rollout block — so live wins when available, rollout
        // covers the rest. `used_?[Pp]ercent` / `resets_?[Aa]t` match both cases.
        val body = Regex("\"$key\"\\s*:\\s*\\{([^{}]*)\\}").find(out)?.groupValues?.get(1) ?: return null
        val used = Regex("\"used_?[Pp]ercent\"\\s*:\\s*([0-9.]+)").find(body)
            ?.groupValues?.get(1)?.toFloatOrNull() ?: return null
        // Live app-server calls it "windowDurationMins"; the rollout uses
        // "window_minutes" — match both (and "windowMinutes" for good measure).
        val declaredSec = Regex("\"window[A-Za-z_]*[Mm]in[a-z]*s?\"\\s*:\\s*([0-9]+)").find(body)
            ?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0L }?.let { it * 60 }
        val label = declaredSec?.let(::durationWindowLabel) ?: fallbackLabel
        // Codex shows what's LEFT (like its /status), not what's used.
        // The clock comes only from a live answer — see codexPayloadIsLive.
        val (rt, resetEpochMs) = if (live) codexReset(body) else "" to null
        return window(label, used, rt, remaining = true, resetAtEpochMs = resetEpochMs)
    }

    /** Name a rate-limit window by the duration the provider actually reported.
     *  Weeks before days before hours, so 10080 min reads "Weekly limit" rather
     *  than "7-day limit", and an unfamiliar cadence still reads honestly
     *  ("36-hour limit") instead of borrowing another product's vocabulary. */
    internal fun durationWindowLabel(sec: Long): String = when {
        sec <= 0L -> "Usage limit"
        sec % (7 * 86_400L) == 0L ->
            (sec / (7 * 86_400L)).let { if (it == 1L) "Weekly limit" else "$it-week limit" }
        sec % 86_400L == 0L ->
            (sec / 86_400L).let { if (it == 1L) "Daily limit" else "$it-day limit" }
        sec % 3_600L == 0L -> "${sec / 3_600L}-hour limit"
        sec % 60L == 0L -> "${sec / 60L}-minute limit"
        else -> "Usage limit"
    }

    /** Reset text + absolute reset epoch (ms) for a Codex window.
     *  `resetsAt`/`resets_at` may be epoch seconds, epoch millis, or an ISO
     *  string. Rollout snapshots can be STALE (already past their reset → naive
     *  delta shows a bogus "now"), so we project forward by the fixed window
     *  cadence to the NEXT real reset, and return THAT as the live anchor. */
    private fun codexReset(body: String): Pair<String, Long?> {
        val now = Instant.now().epochSecond
        val epoch =
            Regex("\"resets_?[Aa]t\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)?.let { isoToEpoch(it) }
                ?: Regex("\"resets_?[Aa]t\"\\s*:\\s*([0-9]+)").find(body)?.groupValues?.get(1)?.toLongOrNull()
                    ?.let { if (it > 1_000_000_000_000L) it / 1000 else it }
        if (epoch != null) {
            // ⛔ THE RESET IS THE PROVIDER'S NUMBER OR IT IS NOTHING. This used
            // to step a past reset forward by the window cadence and present
            // the result as fact. That is an app-side guess wearing the
            // provider's clothes: the owner asked for (2026-09-12), and a
            // countdown he cannot act on is worse than no countdown. A snapshot
            // whose reset has already passed says nothing about the next one —
            // the live probe will bring a real number within the minute.
            if (epoch <= now) return "" to null
            return secsToText(epoch - now) to (epoch * 1000)
        }
        val inSec = Regex("\"resets_?[Ii]n_?[Ss]econds\"\\s*:\\s*([0-9]+)").find(body)
            ?.groupValues?.get(1)?.toLongOrNull()
        return if (inSec != null) secsToText(inSec) to ((now + inSec) * 1000) else "" to null
    }

    private fun isoToEpoch(iso: String): Long? =
        SilentlyTry.logged("Conch-Usage", "parse codex resetsAt") {
            try { Instant.parse(iso).epochSecond }
            catch (_: Throwable) { OffsetDateTime.parse(iso).toInstant().epochSecond }
        }

    /** utilization / used_percent is a 0..100 percent — confirmed live on the
     *  dev server (Claude five_hour.utilization=3.0; Codex primary.used_percent
     *  =1.0). Treat the number as a percent directly. [remaining]=true flips it
     *  to what's LEFT (100−used): Codex's bar counts DOWN from 100% like its own
     *  /status, draining as you spend (Claude keeps showing utilization). */
    private fun window(
        label: String,
        raw: Float,
        resetText: String,
        remaining: Boolean = false,
        resetAtEpochMs: Long? = null,
        perModel: Boolean = false,
    ): UsageWindow {
        val used = raw.coerceIn(0f, 100f)
        val shown = if (remaining) 100f - used else used
        return UsageWindow(
            label = label,
            fraction = (shown / 100f).coerceIn(0f, 1f),
            percent = shown.roundToInt(),
            resetText = resetText,
            usedFraction = (used / 100f).coerceIn(0f, 1f),
            resetAtEpochMs = resetAtEpochMs,
            perModel = perModel,
        )
    }

    /**
     * ⛔ HOURS *AND* MINUTES. This floored to the hour, so a server answer of
     * 1h31m appeared as "1h" — and now that this text is what the bar and the
     * auto-continue row actually display (the provider's number, re-asked every
     * 8 s, instead of a local countdown), that floor became a 31-minute lie on
     * screen. [usageCountdownText] already had the right shape for exactly this
     * reason (2026-07-03: the app read 40 minutes behind the CLI); a second,
     * coarser formatter had no reason to exist. "now" is kept — a reset that has
     * already passed is a statement, not a duration.
     */
    private fun secsToText(s: Long): String =
        if (s <= 0) "now" else usageCountdownText(s)

    // The token is read and used entirely on the server; only the JSON
    // response (percentages + reset times) ever crosses the SSH channel.
    // User-Agent is load-bearing — Anthropic 429s requests without a
    // `claude-code/<ver>` UA — so we mirror the CLI's own header.
    // PATH comes from RemoteEnv (a hand-rolled subset here missed nvm, so a
    // claude installed via nvm read VER empty and the UA fell back to 2.0.0).
    // Claude plan-limit windows, read by DRIVING THE REAL CLI — the same way
    // [CODEX_LIVE_CMD] drives `codex app-server`. We launch `claude` in its
    // stream-json control mode, send `initialize` (which makes the CLI itself
    // refresh usage from the network — MEASURED 2026-09-12 on the dev server: a
    // 31-hour-stale ~/.claude.json cache went 7 s fresh the moment init ran)
    // then `get_usage`, and read back the single control_response line. The
    // number is the CLI's OWN — its token, its request, its User-Agent — so
    // nothing here impersonates the first-party client and no inference turn is
    // spent (measured total_cost_usd=0, and no session .jsonl is created).
    // Replaces the old direct api.anthropic.com probe that wore a
    // `claude-code/<ver> (external, cli)` UA and POSTed a max_tokens:1
    // /v1/messages to scrape rate-limit headers. [reportFromControlPayload]
    // reads the returned line. Credential gate FIRST: no token → CONCH_NOAUTH
    // (drives the logged-out purge) and the CLI is never launched.
    private val CLAUDE_USAGE_CMD = RemoteEnv.PATH_PREAMBLE + RemoteEnv.TIMEOUT_FN + "\n" + """
        C=${'$'}HOME/.claude/.credentials.json
        [ -f "${'$'}C" ] || C=${'$'}HOME/.config/claude/.credentials.json
        TOK=${'$'}(sed -n -E 's/.*"access_?[Tt]oken"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1/p' "${'$'}C" 2>/dev/null | head -1)
        [ -z "${'$'}TOK" ] && TOK="${'$'}CLAUDE_CODE_OAUTH_TOKEN"
        [ -z "${'$'}TOK" ] && { echo CONCH_NOAUTH; exit 0; }
        command -v claude >/dev/null 2>&1 || exit 0
        { printf '%s\n' '{"type":"control_request","request_id":"init-1","request":{"subtype":"initialize","supportedDialogKinds":["can_use_tool","ask_user_question"]}}'
          sleep 2
          printf '%s\n' '{"type":"control_request","request_id":"u-1","request":{"subtype":"get_usage"}}'
          sleep 2
        } | conch_timeout 20 claude --output-format stream-json --input-format stream-json --verbose 2>/dev/null | grep -m1 '"u-1"'
    """.trimIndent()

    // Claude /context breakdown — run on a THROWAWAY COPY of the chat's session
    // jsonl so the REAL session is never polluted (copy → rewrite the
    // session_id to a fresh uuid → `claude -p /context` appends to the COPY;
    // the real session + its token count stay untouched, 0 model tokens since
    // /context is <synthetic>). __RID__ is replaced with the UUID-validated
    // resume id by fetchContextBreakdown.
    //
    // ⛔ THE COPY LIVES IN ITS OWN PROJECT DIRECTORY, NEVER NEXT TO THE REAL
    // SESSION, AND IT IS REMOVED ON EVERY EXIT. It used to be written into the real
    // session's project dir under a fresh uuid, and for the 15-50 s that `claude -p
    // --resume` takes to load a big session the 30 s listing sweep saw a second
    // <uuid>.jsonl carrying the SAME title and preview — and listed it as a second
    // session. Then the copy was deleted and the row pointed at nothing:
    // SessionsCache carried the id through its recent-activity window, the owner
    // sidecar kept it for good, and a tap opened an EMPTY chat. That was every "the
    // CLI announced a fresh id on resume" phantom (2026-07-27, 08-03, 08-31, 09-04
    // — b0609ef4/41efb593 on the dev server: listed with mtimes 32 s and 3 min
    // after the real session went idle, no body, no file). The CLI never renames a
    // resumed session — proven on 2.1.220/.258/.260, print and stream-json, right
    // and wrong cwd, even with a live twin process; this probe was the only thing
    // on the box writing <uuid>.jsonl files. And since the chat prefetches the
    // breakdown on EVERY open, opening an old session was exactly when the phantom
    // appeared.
    //
    // So: the copy goes under the project slug of `$HOME/.conch/ctx-probe`,
    // which ClaudeSpec.listSessionsScript skips by name
    // (RemoteEnv.CTX_PROBE_SLUG_MARK), and the CLI runs FROM that directory so
    // every version finds the copy there (before 2.1.223 the lookup stopped at
    // the current project's dir). The trap removes the copy even when the exec
    // is cut mid-run — a timeout used to orphan a full-size copy on the server.
    private val CLAUDE_CONTEXT_CMD = RemoteEnv.PATH_PREAMBLE + RemoteEnv.TIMEOUT_FN + "\n" + """
        RID="__RID__"
        real=${'$'}(ls ${'$'}HOME/.claude/projects/*/${'$'}RID.jsonl 2>/dev/null | head -1)
        [ -z "${'$'}real" ] && exit 0
        pdir="${'$'}HOME/${RemoteEnv.CTX_PROBE_DIR_REL}"
        mkdir -p "${'$'}pdir" 2>/dev/null || exit 0
        slug=${'$'}(printf '%s' "${'$'}pdir" | sed 's/[^A-Za-z0-9]/-/g')
        ddir="${'$'}HOME/.claude/projects/${'$'}slug"
        mkdir -p "${'$'}ddir" 2>/dev/null || exit 0
        newid=${'$'}(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen 2>/dev/null | tr 'A-Z' 'a-z')
        [ -z "${'$'}newid" ] && exit 0
        copy="${'$'}ddir/${'$'}newid.jsonl"
        trap 'rm -f "${'$'}copy"' EXIT
        trap 'rm -f "${'$'}copy"; exit 1' HUP INT TERM
        cp "${'$'}real" "${'$'}copy" 2>/dev/null || exit 0
        sed -i "s/${'$'}RID/${'$'}newid/g" "${'$'}copy"
        cd "${'$'}pdir" || exit 0
        echo "/context" | conch_timeout 50 claude -p --resume "${'$'}newid" --output-format json --verbose 2>/dev/null | jq -r ".[1].message.content[0].text" 2>/dev/null
    """.trimIndent()

    /** The exact server-side script the `/context` probe runs for [resumeId].
     *  Exposed for ContextProbeIsolationTest, which pins where the throwaway
     *  copy lives and that it is always removed. */
    internal fun contextProbeScript(resumeId: String): String =
        CLAUDE_CONTEXT_CMD.replace("__RID__", resumeId)

    // FAST source: just the latest rollout snapshot line (instant grep). Paints
    // the bar the moment a chat opens; resets are projected forward so a stale
    // snapshot still shows a real countdown, not "now".
    private val CODEX_FAST_CMD = """
        export PATH="${'$'}HOME/.local/bin:/usr/local/bin:${'$'}PATH"
        [ -f "${'$'}HOME/.codex/auth.json" ] || { echo CONCH_NOAUTH; exit 0; }
        f=${'$'}(ls -t ${'$'}HOME/.codex/sessions/*/*/*/rollout-*.jsonl 2>/dev/null | head -1)
        [ -z "${'$'}f" ] && f=${'$'}(ls -t ${'$'}(find ${'$'}HOME/.codex/sessions -name 'rollout-*.jsonl' 2>/dev/null) 2>/dev/null | head -1)
        # ⛔ THE LAST rate_limits LINE IS NOT THE LAST LINE WITH NUMBERS.
        # Codex writes a second bucket (limit_id "premium") whose primary and
        # secondary are BOTH null, and on the owner's server that null block was
        # the final rate_limits line in the newest rollout. `tail -1` handed the
        # parser a snapshot with nothing in it, parseCodex returned empty, and the
        # cache kept its previous value — so the bar read 4% while the real window
        # was 100% spent (measured 2026-09-12, app-server said usedPercent:100).
        # Take the last line that actually carries a number.
        [ -n "${'$'}f" ] && grep '"rate_limits"' "${'$'}f" 2>/dev/null |
          grep -E '"used_percent"[[:space:]]*:[[:space:]]*[0-9]' | tail -1
    """.trimIndent()

    // LIVE source: drive `codex app-server` over stdio (initialize →
    // account/rateLimits/read) for the same fresh numbers Codex's own /status
    // shows, then ALSO emit the rollout line as fallback. parseCodex prefers the
    // live (camelCase) block and falls back to the rollout (snake_case) one, so
    // an older codex / unsupported app-server / cold session still shows a
    // (forward-projected) limit instead of nothing. Slower (~2-3s) — runs in the
    // background to refine the fast value. All server-side; token never crosses.
    private val CODEX_LIVE_CMD = RemoteEnv.PATH_PREAMBLE + RemoteEnv.TIMEOUT_FN + "\n" + """
        [ -f "${'$'}HOME/.codex/auth.json" ] || { echo CONCH_NOAUTH; exit 0; }
        if command -v codex >/dev/null 2>&1; then
          # ⛔ NO FIXED SLEEPS — THEY *WERE* THE DELAY. This used to `sleep 2`
          # then `sleep 9`, because stdin EOF shuts the app-server down and the
          # sleeps were its whole time budget. The cost of that: the command
          # ALWAYS took eleven seconds, even when the answer had landed in one.
          # Now a fifo holds stdin open, the request goes the moment
          # `initialize` is ACKED rather than after a guessed pause, and the
          # command returns the instant the answer appears. Every number below
          # is a CEILING, never a wait.
          #
          # And this is only the FALLBACK. A chat holding a live app-server
          # channel reads its windows over that channel in one round trip and
          # never runs this at all — see
          # AgentSessionCodexAppServer.fetchRateLimitsLive.
          d=${'$'}(mktemp -d 2>/dev/null) || d=${'$'}HOME/.conch-rl.${'$'}${'$'}
          mkdir -p "${'$'}d" && rm -f "${'$'}d/p" && mkfifo "${'$'}d/p" 2>/dev/null
          { printf '%s\n' '{"id":0,"method":"initialize","params":{"clientInfo":{"name":"conch","title":"conch","version":"1.0"}}}'
            j=0
            while [ ${'$'}j -lt 200 ]; do
              grep -q '"id"[[:space:]]*:[[:space:]]*0' "${'$'}d/o" 2>/dev/null && break
              sleep 0.05; j=${'$'}((j+1))
            done
            printf '%s\n' '{"id":1,"method":"account/rateLimits/read","params":{}}'
            sleep 20
          } > "${'$'}d/p" &
          wp=${'$'}!
          conch_timeout 25 codex app-server < "${'$'}d/p" > "${'$'}d/o" 2>/dev/null &
          cp=${'$'}!
          i=0
          while [ ${'$'}i -lt 250 ]; do
            grep -q '"rateLimits"' "${'$'}d/o" 2>/dev/null && break
            kill -0 ${'$'}cp 2>/dev/null || break
            sleep 0.1; i=${'$'}((i+1))
          done
          kill ${'$'}wp ${'$'}cp 2>/dev/null
          grep '"rateLimits"' "${'$'}d/o" 2>/dev/null | tail -1
          rm -rf "${'$'}d"
        fi
        f=${'$'}(ls -t ${'$'}HOME/.codex/sessions/*/*/*/rollout-*.jsonl 2>/dev/null | head -1)
        [ -z "${'$'}f" ] && f=${'$'}(ls -t ${'$'}(find ${'$'}HOME/.codex/sessions -name 'rollout-*.jsonl' 2>/dev/null) 2>/dev/null | head -1)
        # ⛔ THE LAST rate_limits LINE IS NOT THE LAST LINE WITH NUMBERS.
        # Codex writes a second bucket (limit_id "premium") whose primary and
        # secondary are BOTH null, and on the owner's server that null block was
        # the final rate_limits line in the newest rollout. `tail -1` handed the
        # parser a snapshot with nothing in it, parseCodex returned empty, and the
        # cache kept its previous value — so the bar read 4% while the real window
        # was 100% spent (measured 2026-09-12, app-server said usedPercent:100).
        # Take the last line that actually carries a number.
        [ -n "${'$'}f" ] && grep '"rate_limits"' "${'$'}f" 2>/dev/null |
          grep -E '"used_percent"[[:space:]]*:[[:space:]]*[0-9]' | tail -1
    """.trimIndent()

    // -- Gemini plan windows ---------------------------------------------
    //
    // The comment that used to sit here said "no machine-readable quota" and it
    // had gone stale. Read out of the shipped gemini-cli 0.57.0 bundle, not the
    // docs: `CodeAssistServer.retrieveUserQuota()` POSTs to
    // cloudcode-pa.googleapis.com/v1internal:retrieveUserQuota and answers with
    // `buckets[]`, each carrying {modelId, remainingFraction, resetTime} -- a
    // provider fraction AND a provider reset stamp, exactly the pair a window
    // needs. The CLI's own loop over it is `Config.refreshUserQuota()`;
    // [reportFromGemini] mirrors that loop rather than inventing a reading.
    //
    // NOT an ACP call. The entire ACP method inventory in that bundle is
    // session/*, fs/* and terminal/* -- nothing usage-shaped -- and the quota
    // the CLI does fetch is emitted as `CoreEvent.QuotaChanged`, whose only
    // subscriber is its interactive TUI. So this is a shell round trip on the
    // connection we already hold, like Claude's.
    //
    // Credential gate FIRST, the same rule as everywhere: an API-key or Vertex
    // user has no quota surface at all (the CLI itself bails on
    // `!codeAssistServer.projectId`), so we say CONCH_NOAUTH and draw nothing
    // rather than a zero. The project id is resolved once and cached on the
    // server, so the steady state is one POST.
    private val GEMINI_USAGE_CMD = RemoteEnv.PATH_PREAMBLE + RemoteEnv.TIMEOUT_FN + "\n" + """
        G=${'$'}HOME/.gemini/oauth_creds.json
        [ -f "${'$'}G" ] || { echo CONCH_NOAUTH; exit 0; }
        TOK=${'$'}(sed -n -E 's/.*"access_token"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1/p' "${'$'}G" | head -1)
        [ -z "${'$'}TOK" ] && { echo CONCH_NOAUTH; exit 0; }
        EXP=${'$'}(sed -n -E 's/.*"expiry_date"[[:space:]]*:[[:space:]]*([0-9]+).*/\1/p' "${'$'}G" | head -1)
        NOW=${'$'}(( ${'$'}(date +%s) * 1000 ))
        if [ -n "${'$'}EXP" ] && [ "${'$'}EXP" -lt ${'$'}((NOW + 60000)) ]; then
          # The CLI refreshes its own token exactly this way. Doing it here stops
          # a long-idle server from reporting a logout on a perfectly good login.
          B=${'$'}(dirname ${'$'}(dirname ${'$'}(readlink -f ${'$'}(command -v gemini) 2>/dev/null) 2>/dev/null) 2>/dev/null)/bundle
          RT=${'$'}(sed -n -E 's/.*"refresh_token"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1/p' "${'$'}G" | head -1)
          CI=${'$'}(grep -roh 'OAUTH_CLIENT_ID = "[^"]*"' "${'$'}B" 2>/dev/null | head -1 | sed -E 's/.*"([^"]*)"/\1/')
          CS=${'$'}(grep -roh 'OAUTH_CLIENT_SECRET = "[^"]*"' "${'$'}B" 2>/dev/null | head -1 | sed -E 's/.*"([^"]*)"/\1/')
          if [ -n "${'$'}RT" ] && [ -n "${'$'}CI" ]; then
            TOK=${'$'}(curl -s -m 5 -X POST https://oauth2.googleapis.com/token -d client_id="${'$'}CI" -d client_secret="${'$'}CS" -d refresh_token="${'$'}RT" -d grant_type=refresh_token 2>/dev/null | sed -n -E 's/.*"access_token"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1/p' | head -1)
          fi
          [ -z "${'$'}TOK" ] && { echo CONCH_NOAUTH; exit 0; }
        fi
        P="${'$'}GOOGLE_CLOUD_PROJECT"
        [ -z "${'$'}P" ] && P="${'$'}GCLOUD_PROJECT"
        PF=${'$'}HOME/.conch/gemini-project
        [ -z "${'$'}P" ] && [ -f "${'$'}PF" ] && P=${'$'}(cat "${'$'}PF" 2>/dev/null)
        if [ -z "${'$'}P" ]; then
          P=${'$'}(curl -s -m 5 -X POST "https://cloudcode-pa.googleapis.com/v1internal:loadCodeAssist" -H "Authorization: Bearer ${'$'}TOK" -H "Content-Type: application/json" -d '{"metadata":{"ideType":"IDE_UNSPECIFIED","platform":"PLATFORM_UNSPECIFIED","pluginType":"GEMINI"}}' 2>/dev/null | sed -n -E 's/.*"cloudaicompanionProject"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1/p' | head -1)
          [ -n "${'$'}P" ] && { mkdir -p "${'$'}HOME/.conch" 2>/dev/null; printf '%s' "${'$'}P" > "${'$'}PF" 2>/dev/null; }
        fi
        [ -z "${'$'}P" ] && exit 0
        curl -s -m 5 -X POST "https://cloudcode-pa.googleapis.com/v1internal:retrieveUserQuota" -H "Authorization: Bearer ${'$'}TOK" -H "Content-Type: application/json" -d "{\\"project\\":\\"${'$'}P\\"}" | tr -d '\n'
    """.trimIndent()

    // -- Copilot plan windows --------------------------------------------
    //
    // `copilot --server --stdio` is a hidden headless JSON-RPC mode -- the flags
    // are `.hideHelp()` in the CLI's own app.js, but real. `connect` then
    // `account.getCurrentAuth` returns `copilotUser` carrying
    // `quota_snapshots.{chat,completions,premium_interactions}` with
    // `percent_remaining`, plus `quota_reset_date_utc`: provider percentage and
    // provider reset in ONE call.
    //
    // TWO TRAPS, both verified against the shipped 1.0.80 package:
    //  - The framing is vscode-jsonrpc, "Content-Length: N" + CRLF + CRLF +
    //    body. A plain NDJSON line is silently ignored, which looks exactly
    //    like a server that never answers.
    //  - `account.getQuota`'s `resetDate` is NOT a reset. The CLI assigns it
    //    `timestamp_utc` -- the snapshot time -- and it advances with the wall
    //    clock. The reset is `copilotUser.quota_reset_date_utc` and nothing
    //    else; the other one would give a countdown that never counts down.
    //
    // Unavailable under `--acp` (the wire entry is scope:"server", so an ACP
    // peer answers -32601), hence a spawn rather than a live-channel round
    // trip. It is the slow tier by nature; the stamped cache is what makes the
    // NEXT open instant.
    private val COPILOT_USAGE_CMD = RemoteEnv.PATH_PREAMBLE + RemoteEnv.TIMEOUT_FN + "\n" + """
        command -v copilot >/dev/null 2>&1 || exit 0
        d=${'$'}(mktemp -d 2>/dev/null) || d=${'$'}HOME/.conch-cq.${'$'}${'$'}
        mkdir -p "${'$'}d" && rm -f "${'$'}d/p" && mkfifo "${'$'}d/p" 2>/dev/null
        {
          A='{"jsonrpc":"2.0","id":1,"method":"connect","params":{"clientInfo":{"name":"conch","version":"0"}}}'
          printf 'Content-Length: %s\r\n\r\n%s' "${'$'}{#A}" "${'$'}A"
          j=0
          while [ ${'$'}j -lt 200 ]; do
            grep -q '"protocolVersion"' "${'$'}d/o" 2>/dev/null && break
            sleep 0.05; j=${'$'}((j+1))
          done
          B='{"jsonrpc":"2.0","id":2,"method":"account.getCurrentAuth","params":{}}'
          printf 'Content-Length: %s\r\n\r\n%s' "${'$'}{#B}" "${'$'}B"
          sleep 15
        } > "${'$'}d/p" &
        wp=${'$'}!
        conch_timeout 20 copilot --server --stdio --no-auto-update < "${'$'}d/p" > "${'$'}d/o" 2>/dev/null &
        cp=${'$'}!
        i=0
        while [ ${'$'}i -lt 200 ]; do
          grep -q 'quota_snapshots' "${'$'}d/o" 2>/dev/null && break
          kill -0 ${'$'}cp 2>/dev/null || break
          sleep 0.1; i=${'$'}((i+1))
        done
        kill ${'$'}wp ${'$'}cp 2>/dev/null
        if grep -q 'quota_snapshots' "${'$'}d/o" 2>/dev/null; then
          grep 'quota_snapshots' "${'$'}d/o" 2>/dev/null | tail -1
        elif grep -q '"protocolVersion"' "${'$'}d/o" 2>/dev/null; then
          # The server answered and holds no account: a real logout, not a
          # hiccup, so the remembered numbers have to go.
          echo CONCH_NOAUTH
        fi
        rm -rf "${'$'}d"
    """.trimIndent()
}
