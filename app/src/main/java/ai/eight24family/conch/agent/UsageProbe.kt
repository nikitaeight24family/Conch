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
) {
    /** "Until reset" recomputed against [nowMs] from the absolute reset time, so
     *  it counts down without a refetch; falls back to the fetch-time
     *  [resetText] when no absolute anchor is available. */
    fun resetTextLive(nowMs: Long): String {
        val at = resetAtEpochMs ?: return resetText
        val s = (at - nowMs) / 1000
        return when {
            s <= 0 -> "now"
            s < 3600 -> "${s / 60}m"
            // Hours with the minute remainder ("2h40m", not a floor to "2h") —
            // the CLI shows the absolute reset time, and a whole-hour floor made
            // the app look 40 minutes behind it (user, 2026-07-03). Exact-hour
            // boundaries still render clean ("3h").
            s < 86_400 -> {
                val m = (s % 3600) / 60
                if (m == 0L) "${s / 3600}h" else "${s / 3600}h${m}m"
            }
            else -> "${s / 86_400}d"
        }
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
) {
    val primary: UsageWindow? get() = windows.firstOrNull()
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
        ioScope.launch {
            runCatching {
                val f = diskFile() ?: return@launch
                if (!f.exists()) return@launch
                json.decodeFromString<Map<String, UsageReport>>(f.readText())
                    .forEach { (k, v) -> cache.putIfAbsent(k, v) }
            }
        }
    }

    private fun persistToDisk() {
        ioScope.launch {
            runCatching { diskFile()?.writeText(json.encodeToString(cache.toMap())) }
        }
    }

    /** Last known report (cache hit) — instant, no SSH. Null if never fetched. */
    fun cached(serverId: String, agent: Agent): UsageReport? = cache[key(serverId, agent)]

    /** [fast]=true uses the cheap source that paints immediately (Codex: the
     *  rollout snapshot; Claude: the last cached report, no network) so the bar
     *  is up within a few hundred ms; [fast]=false hits the live source (Codex:
     *  `codex app-server`; Claude: the usage curl) to refine. Both cache on
     *  success so the next chat open is instant. */
    suspend fun fetch(serverId: String, agent: Agent, fast: Boolean = false): UsageReport? {
        val cmd = when (agent) {
            Agent.CLAUDE -> if (fast) return cached(serverId, agent) else CLAUDE_USAGE_CMD
            Agent.CODEX -> if (fast) CODEX_FAST_CMD else CODEX_LIVE_CMD
            Agent.GEMINI -> return null // no machine-readable quota
        }
        val out = execOnServer(serverId, cmd)?.takeIf { it.isNotBlank() } ?: return null
        val windows = when (agent) {
            Agent.CLAUDE -> parseClaude(out)
            Agent.CODEX -> parseCodex(out)
            Agent.GEMINI -> emptyList()
        }
        val report = if (windows.isEmpty()) null else UsageReport(
            windows = windows,
            extraUsedUsd = if (agent == Agent.CLAUDE) parseClaudeExtra(out) else null,
        )
        if (report != null) {
            cache[key(serverId, agent)] = report // keep last good
            persistToDisk()                       // survive restarts → instant on next open
        }
        return report
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
        val out = execOnServer(serverId, CLAUDE_CONTEXT_CMD.replace("__RID__", resumeId), timeoutSec = 60)
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
        return SilentlyTry.logged("SshAi-Usage", "fetch usage") {
            val sess = client.startSession()
            try {
                val proc = sess.exec("bash -lc " + shellEscape(cmd))
                val out = ByteArrayOutputStream()
                proc.inputStream.copyTo(out)
                proc.join(timeoutSec, TimeUnit.SECONDS)
                String(out.toByteArray(), Charsets.UTF_8)
            } finally {
                SilentlyTry.fired("SshAi-Usage", "close usage session") { sess.close() }
            }
        }
    }

    // ---- Claude: api/oauth/usage → five_hour, seven_day, per-model weeklies ----
    private fun parseClaude(json: String): List<UsageWindow> = buildList {
        claudeWindow(json, "five_hour", "5-hour limit")?.let { add(it) }
        claudeWindow(json, "seven_day", "Weekly · all models")?.let { add(it) }
        claudeWindow(json, "seven_day_opus", "Opus only")?.let { add(it) }
        claudeWindow(json, "seven_day_sonnet", "Sonnet only")?.let { add(it) }
    }

    /** Claude's usage endpoint also carries an `extra_usage` block — the
     *  pay-as-you-go overage beyond the plan. Returns dollars spent
     *  (`used_credits`), or null when absent. */
    private fun parseClaudeExtra(json: String): Double? {
        val body = Regex("\"extra_usage\"\\s*:\\s*\\{([^{}]*)\\}").find(json)?.groupValues?.get(1) ?: return null
        return Regex("\"used_?credits\"\\s*:\\s*([0-9.]+)").find(body)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun claudeWindow(json: String, key: String, label: String): UsageWindow? {
        val body = Regex("\"$key\"\\s*:\\s*\\{([^{}]*)\\}").find(json)?.groupValues?.get(1) ?: return null
        val util = Regex("\"utilization\"\\s*:\\s*([0-9.]+)").find(body)
            ?.groupValues?.get(1)?.toFloatOrNull() ?: return null
        val resetsAt = Regex("\"resets_at\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
        val resetEpochMs = resetsAt?.let { isoToEpoch(it) }?.let { it * 1000 }
        return window(label, util, isoToDelta(resetsAt), resetAtEpochMs = resetEpochMs)
    }

    // ---- Codex: live `codex app-server` account/rateLimits/read (camelCase)
    //      with a fallback to the latest rollout's snake_case rate_limits.
    //      Codex windows are product-fixed: primary = 5h, secondary = weekly. ----
    private fun parseCodex(out: String): List<UsageWindow> = buildList {
        codexWindow(out, "primary", "5-hour limit", 5 * 3600L)?.let { add(it) }
        codexWindow(out, "secondary", "Weekly limit", 7 * 86_400L)?.let { add(it) }
    }

    private fun codexWindow(out: String, key: String, label: String, defaultWindowSec: Long): UsageWindow? {
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
        val windowSec = Regex("\"window[A-Za-z_]*[Mm]in[a-z]*s?\"\\s*:\\s*([0-9]+)").find(body)
            ?.groupValues?.get(1)?.toLongOrNull()?.let { it * 60 } ?: defaultWindowSec
        // Codex shows what's LEFT (like its /status), not what's used.
        val (rt, resetEpochMs) = codexReset(body, windowSec)
        return window(label, used, rt, remaining = true, resetAtEpochMs = resetEpochMs)
    }

    /** Reset text + absolute reset epoch (ms) for a Codex window.
     *  `resetsAt`/`resets_at` may be epoch seconds, epoch millis, or an ISO
     *  string. Rollout snapshots can be STALE (already past their reset → naive
     *  delta shows a bogus "now"), so we project forward by the fixed window
     *  cadence to the NEXT real reset, and return THAT as the live anchor. */
    private fun codexReset(body: String, windowSec: Long): Pair<String, Long?> {
        val now = Instant.now().epochSecond
        val epoch =
            Regex("\"resets_?[Aa]t\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)?.let { isoToEpoch(it) }
                ?: Regex("\"resets_?[Aa]t\"\\s*:\\s*([0-9]+)").find(body)?.groupValues?.get(1)?.toLongOrNull()
                    ?.let { if (it > 1_000_000_000_000L) it / 1000 else it }
        if (epoch != null) {
            val projected = projectForward(epoch, windowSec, now)
            return secsToText(projected - now) to (projected * 1000)
        }
        val inSec = Regex("\"resets_?[Ii]n_?[Ss]econds\"\\s*:\\s*([0-9]+)").find(body)
            ?.groupValues?.get(1)?.toLongOrNull()
        return if (inSec != null) secsToText(inSec) to ((now + inSec) * 1000) else "" to null
    }

    /** Smallest reset ≥ now, stepping by [window]. Fixed-cadence windows (5h /
     *  weekly) reset periodically, so even a past snapshot pins a real future
     *  reset rather than collapsing to "now". */
    private fun projectForward(reset: Long, window: Long, now: Long): Long {
        if (window <= 0L || reset >= now) return reset
        return reset + ((now - reset) / window + 1) * window
    }

    private fun isoToEpoch(iso: String): Long? =
        SilentlyTry.logged("SshAi-Usage", "parse codex resetsAt") {
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
        )
    }

    private fun isoToDelta(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        val epoch = SilentlyTry.logged("SshAi-Usage", "parse resets_at") {
            try {
                Instant.parse(iso).epochSecond
            } catch (_: Throwable) {
                OffsetDateTime.parse(iso).toInstant().epochSecond
            }
        } ?: return ""
        return secsToText(epoch - Instant.now().epochSecond)
    }

    private fun secsToText(s: Long): String = when {
        s <= 0 -> "now"
        s < 3600 -> "${s / 60}m"
        s < 86_400 -> "${s / 3600}h"
        else -> "${s / 86_400}d"
    }

    // The token is read and used entirely on the server; only the JSON
    // response (percentages + reset times) ever crosses the SSH channel.
    // User-Agent is load-bearing — Anthropic 429s requests without a
    // `claude-code/<ver>` UA — so we mirror the CLI's own header.
    private val CLAUDE_USAGE_CMD = """
        export PATH="${'$'}HOME/.local/bin:/usr/local/bin:${'$'}PATH"
        C=${'$'}HOME/.claude/.credentials.json
        [ -f "${'$'}C" ] || C=${'$'}HOME/.config/claude/.credentials.json
        TOK=${'$'}(sed -n -E 's/.*"access_?[Tt]oken"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1/p' "${'$'}C" 2>/dev/null | head -1)
        [ -z "${'$'}TOK" ] && exit 0
        VER=${'$'}(claude --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
        curl -fsS -m 6 -H "Authorization: Bearer ${'$'}TOK" -H "anthropic-beta: oauth-2025-04-20" -H "User-Agent: claude-code/${'$'}{VER:-2.0.0} (external, cli)" "https://api.anthropic.com/api/oauth/usage" 2>/dev/null
    """.trimIndent()

    // Claude /context breakdown — run on a THROWAWAY COPY of the chat's session
    // jsonl so the REAL session is never polluted (verified on-device: copy →
    // rewrite the session_id to a fresh uuid → `claude -p /context` appends to
    // the COPY, which we delete; the real session + its token count stay
    // untouched, 0 model tokens since /context is <synthetic>). __RID__ is
    // replaced with the UUID-validated resume id by fetchContextBreakdown.
    private val CLAUDE_CONTEXT_CMD = """
        export PATH="${'$'}HOME/.local/bin:/usr/local/bin:${'$'}PATH"
        RID="__RID__"
        real=${'$'}(ls ${'$'}HOME/.claude/projects/*/${'$'}RID.jsonl 2>/dev/null | head -1)
        [ -z "${'$'}real" ] && exit 0
        dir=${'$'}(dirname "${'$'}real")
        newid=${'$'}(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen 2>/dev/null | tr 'A-Z' 'a-z')
        [ -z "${'$'}newid" ] && exit 0
        cp "${'$'}real" "${'$'}dir/${'$'}newid.jsonl"
        sed -i "s/${'$'}RID/${'$'}newid/g" "${'$'}dir/${'$'}newid.jsonl"
        echo "/context" | timeout 50 claude -p --resume "${'$'}newid" --output-format json --verbose 2>/dev/null | jq -r ".[1].message.content[0].text" 2>/dev/null
        rm -f "${'$'}dir/${'$'}newid.jsonl"
    """.trimIndent()

    // FAST source: just the latest rollout snapshot line (instant grep). Paints
    // the bar the moment a chat opens; resets are projected forward so a stale
    // snapshot still shows a real countdown, not "now".
    private val CODEX_FAST_CMD = """
        export PATH="${'$'}HOME/.local/bin:/usr/local/bin:${'$'}PATH"
        f=${'$'}(ls -t ${'$'}HOME/.codex/sessions/*/*/*/rollout-*.jsonl 2>/dev/null | head -1)
        [ -z "${'$'}f" ] && f=${'$'}(ls -t ${'$'}(find ${'$'}HOME/.codex/sessions -name 'rollout-*.jsonl' 2>/dev/null) 2>/dev/null | head -1)
        [ -n "${'$'}f" ] && grep '"rate_limits"' "${'$'}f" 2>/dev/null | tail -1
    """.trimIndent()

    // LIVE source: drive `codex app-server` over stdio (initialize →
    // account/rateLimits/read) for the same fresh numbers Codex's own /status
    // shows, then ALSO emit the rollout line as fallback. parseCodex prefers the
    // live (camelCase) block and falls back to the rollout (snake_case) one, so
    // an older codex / unsupported app-server / cold session still shows a
    // (forward-projected) limit instead of nothing. Slower (~2-3s) — runs in the
    // background to refine the fast value. All server-side; token never crosses.
    private val CODEX_LIVE_CMD = """
        export PATH="${'$'}HOME/.local/bin:/usr/local/bin:${'$'}PATH"
        if command -v codex >/dev/null 2>&1; then
          { printf '%s\n' '{"id":0,"method":"initialize","params":{"clientInfo":{"name":"sshai","title":"sshai","version":"1.0"}}}'
            sleep 0.4
            printf '%s\n' '{"id":1,"method":"account/rateLimits/read","params":{}}'
            sleep 0.7
          } | timeout 6 codex app-server 2>/dev/null | grep -i 'ratelimit' | tail -1
        fi
        f=${'$'}(ls -t ${'$'}HOME/.codex/sessions/*/*/*/rollout-*.jsonl 2>/dev/null | head -1)
        [ -z "${'$'}f" ] && f=${'$'}(ls -t ${'$'}(find ${'$'}HOME/.codex/sessions -name 'rollout-*.jsonl' 2>/dev/null) 2>/dev/null | head -1)
        [ -n "${'$'}f" ] && grep '"rate_limits"' "${'$'}f" 2>/dev/null | tail -1
    """.trimIndent()
}
