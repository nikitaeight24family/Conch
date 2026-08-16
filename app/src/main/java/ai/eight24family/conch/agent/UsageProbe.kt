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
    /** True for a per-model window (the "second layer": Opus/Sonnet/Fable
     *  weekly caps) vs an aggregate (5-hour / weekly all-models). Lets the sheet
     *  group the per-model rows under their own subheader. */
    val perModel: Boolean = false,
) {
    /** "Until reset" recomputed against [nowMs] from the absolute reset time, so
     *  it counts down without a refetch; falls back to the fetch-time
     *  [resetText] when no absolute anchor is available. */
    fun resetTextLive(nowMs: Long): String {
        val at = resetAtEpochMs ?: return resetText
        return usageCountdownText((at - nowMs) / 1000)
    }
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
        cache[key(serverId, agent)] = withPerModelCarryOver(serverId, agent, report)
        persistToDisk()
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
            Agent.GEMINI -> return null // no machine-readable quota
        }
        val out = execOnServer(serverId, cmd)?.takeIf { it.isNotBlank() } ?: return null
        val windows = when (agent) {
            Agent.CLAUDE -> parseClaude(out)
            Agent.CODEX -> parseCodex(out)
            Agent.GEMINI -> emptyList()
        }
        val raw = if (windows.isEmpty()) null else UsageReport(
            windows = windows,
            extraUsedUsd = if (agent == Agent.CLAUDE) parseClaudeExtra(out) else null,
        )
        // Merge BEFORE caching — a fallback-source report without the
        // per-model layer must not clobber the carried rows (see
        // [withPerModelCarryOver]); returned merged too, so the caller
        // displays exactly what the cache holds.
        val report = raw?.let { withPerModelCarryOver(serverId, agent, it) }
        if (report != null) {
            cache[key(serverId, agent)] = report // keep last good
            persistToDisk()                       // survive restarts → instant on next open
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
                val proc = sess.exec(RemoteEnv.portable("bash -lc " + shellEscape(cmd)))
                val out = ByteArrayOutputStream()
                proc.inputStream.copyTo(out)
                proc.join(timeoutSec, TimeUnit.SECONDS)
                String(out.toByteArray(), Charsets.UTF_8)
            } finally {
                SilentlyTry.fired("SshAi-Usage", "close usage session") { sess.close() }
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
    private fun parseCodex(out: String): List<UsageWindow> = buildList {
        codexWindow(out, "primary", "Usage limit", 5 * 3600L)?.let { add(it) }
        codexWindow(out, "secondary", "Secondary limit", 7 * 86_400L)?.let { add(it) }
    }

    /**
     * @param fallbackLabel used ONLY when the payload does not state the window
     *   size. It carries NO duration — inventing "5-hour" from a default is the
     *   bug above. Same rule as the topbar's effort: show the real value or
     *   nothing (NO-INVENTED-EFFORT-IN-THE-TOPBAR-1).
     * @param defaultWindowSec still needed for the reset projection, which has to
     *   pick some cadence; it never reaches the label.
     */
    private fun codexWindow(out: String, key: String, fallbackLabel: String, defaultWindowSec: Long): UsageWindow? {
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
        val windowSec = declaredSec ?: defaultWindowSec
        val label = declaredSec?.let(::durationWindowLabel) ?: fallbackLabel
        // Codex shows what's LEFT (like its /status), not what's used.
        val (rt, resetEpochMs) = codexReset(body, windowSec)
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
    // PATH comes from RemoteEnv (a hand-rolled subset here missed nvm, so a
    // claude installed via nvm read VER empty and the UA fell back to 2.0.0).
    private val CLAUDE_USAGE_CMD = RemoteEnv.PATH_PREAMBLE + """
        C=${'$'}HOME/.claude/.credentials.json
        [ -f "${'$'}C" ] || C=${'$'}HOME/.config/claude/.credentials.json
        TOK=${'$'}(sed -n -E 's/.*"access_?[Tt]oken"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1/p' "${'$'}C" 2>/dev/null | head -1)
        # A `claude setup-token` login has NO usable token in the file — it lives
        # in CLAUDE_CODE_OAUTH_TOKEN (~/.profile). Read the env token as fallback,
        # else the usage bar could never refresh and showed a stale ghost %.
        [ -z "${'$'}TOK" ] && TOK="${'$'}CLAUDE_CODE_OAUTH_TOKEN"
        [ -z "${'$'}TOK" ] && exit 0
        VER=${'$'}(claude --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
        UA="claude-code/${'$'}{VER:-2.0.0} (external, cli)"
        # 1) FULL-scope OAuth (browser login): the rich usage endpoint — five_hour,
        #    seven_day, per-model weeklies, extra_usage. Best data when available.
        J=${'$'}(curl -fsS -m 6 -H "Authorization: Bearer ${'$'}TOK" -H "anthropic-beta: oauth-2025-04-20" -H "User-Agent: ${'$'}UA" "https://api.anthropic.com/api/oauth/usage" 2>/dev/null)
        if printf '%s' "${'$'}J" | grep -q '"utilization"'; then printf '%s' "${'$'}J"; exit 0; fi
        # 2) INFERENCE-only token (claude setup-token, scope=user:inference) 403s on
        #    that endpoint — but the SAME live 5h/weekly limits ride the rate-limit
        #    response headers of a normal inference call, which this token CAN make.
        #    A max_tokens:1 message is ~free; the headers come back regardless of the
        #    body. Synthesize the same JSON shape the parser expects.
        # Verified live against a setup-token account (2026-07-16): the OAuth token
        # ONLY accepts a Claude-Code-shaped request — the "You are Claude Code…"
        # system prompt is mandatory (without it → 404). The unified headers then
        # come back on the 200. `utilization` is a FRACTION (0.55), so ×100 to match
        # the percent the endpoint/parser use.
        #
        # Model is picked DYNAMICALLY from the live GET /v1/models list — the
        # cheapest tier (haiku) if present, else the last-listed model — so a
        # monthly model rename NEVER breaks this (a hardcoded id like
        # claude-3-5-haiku-* already 404s). Only a last-ditch static fallback if the
        # list can't be fetched.
        IDS=${'$'}(curl -sS -m 6 -H "Authorization: Bearer ${'$'}TOK" -H "anthropic-beta: oauth-2025-04-20" -H "anthropic-version: 2023-06-01" -H "User-Agent: ${'$'}UA" "https://api.anthropic.com/v1/models?limit=100" 2>/dev/null | grep -oE '"id"[[:space:]]*:[[:space:]]*"[^"]+"' | sed -E 's/.*"([^"]+)"${'$'}/\1/')
        MODEL=${'$'}(printf '%s' "${'$'}IDS" | grep -i haiku | head -1)
        [ -z "${'$'}MODEL" ] && MODEL=${'$'}(printf '%s' "${'$'}IDS" | tail -1)
        [ -z "${'$'}MODEL" ] && MODEL="claude-haiku-4-5"
        SYS="You are Claude Code, Anthropic's official CLI for Claude."
        BODY="{\"model\":\"${'$'}MODEL\",\"max_tokens\":1,\"system\":\"${'$'}SYS\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"
        H=${'$'}(curl -sS -m 10 -D - -o /dev/null -X POST \
          -H "Authorization: Bearer ${'$'}TOK" -H "anthropic-beta: oauth-2025-04-20" \
          -H "anthropic-version: 2023-06-01" -H "User-Agent: ${'$'}UA" -H "content-type: application/json" \
          -d "${'$'}BODY" "https://api.anthropic.com/v1/messages" 2>/dev/null | tr -d '\r')
        pct(){ awk -v v="${'$'}1" 'BEGIN{ if(v=="") exit; printf "%.2f", v*100 }'; }
        iso(){ [ -n "${'$'}1" ] && { date -u -d "@${'$'}1" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -r "${'$'}1" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null; }; }
        # DYNAMIC — emit EVERY unified rate window the headers carry, not just
        # 5h/7d, so a subscribed account's per-model caps (7d-opus, 7d-fable, …)
        # ride through too. Header window name → the usage-endpoint JSON key the
        # dynamic parser labels: 5h→five_hour, 7d→seven_day, 5h-<m>→five_hour_<m>,
        # 7d-<m>→seven_day_<m>.
        norm(){ case "${'$'}1" in 5h) echo five_hour;; 7d) echo seven_day;; 5h-*) echo "five_hour_${'$'}{1#5h-}";; 7d-*) echo "seven_day_${'$'}{1#7d-}";; *) printf '%s' "${'$'}1" | tr - _;; esac; }
        WINS=${'$'}(printf '%s' "${'$'}H" | grep -ioE '^anthropic-ratelimit-unified-[a-z0-9-]+-utilization:' | sed -E 's/^anthropic-ratelimit-unified-(.*)-utilization:.*/\1/i')
        OUT=; SEP=
        for w in ${'$'}WINS; do
          uv=${'$'}(printf '%s' "${'$'}H" | grep -i "^anthropic-ratelimit-unified-${'$'}w-utilization:" | sed -E 's/^[^:]*:[[:space:]]*//' | head -1)
          up=${'$'}(pct "${'$'}uv"); [ -z "${'$'}up" ] && continue
          rv=${'$'}(printf '%s' "${'$'}H" | grep -i "^anthropic-ratelimit-unified-${'$'}w-reset:" | sed -E 's/^[^:]*:[[:space:]]*//' | head -1)
          k=${'$'}(norm "${'$'}w")
          OUT="${'$'}OUT${'$'}SEP\"${'$'}k\":{\"utilization\":${'$'}up,\"resets_at\":\"${'$'}(iso "${'$'}rv")\"}"
          SEP=,
        done
        [ -z "${'$'}OUT" ] && exit 0
        printf '{%s}' "${'$'}OUT"
    """.trimIndent()

    // Claude /context breakdown — run on a THROWAWAY COPY of the chat's session
    // jsonl so the REAL session is never polluted (verified on-device: copy →
    // rewrite the session_id to a fresh uuid → `claude -p /context` appends to
    // the COPY, which we delete; the real session + its token count stay
    // untouched, 0 model tokens since /context is <synthetic>). __RID__ is
    // replaced with the UUID-validated resume id by fetchContextBreakdown.
    private val CLAUDE_CONTEXT_CMD = RemoteEnv.PATH_PREAMBLE + RemoteEnv.TIMEOUT_FN + "\n" + """
        RID="__RID__"
        real=${'$'}(ls ${'$'}HOME/.claude/projects/*/${'$'}RID.jsonl 2>/dev/null | head -1)
        [ -z "${'$'}real" ] && exit 0
        dir=${'$'}(dirname "${'$'}real")
        newid=${'$'}(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen 2>/dev/null | tr 'A-Z' 'a-z')
        [ -z "${'$'}newid" ] && exit 0
        cp "${'$'}real" "${'$'}dir/${'$'}newid.jsonl"
        sed -i "s/${'$'}RID/${'$'}newid/g" "${'$'}dir/${'$'}newid.jsonl"
        echo "/context" | conch_timeout 50 claude -p --resume "${'$'}newid" --output-format json --verbose 2>/dev/null | jq -r ".[1].message.content[0].text" 2>/dev/null
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
    private val CODEX_LIVE_CMD = RemoteEnv.PATH_PREAMBLE + RemoteEnv.TIMEOUT_FN + "\n" + """
        if command -v codex >/dev/null 2>&1; then
          { printf '%s\n' '{"id":0,"method":"initialize","params":{"clientInfo":{"name":"sshai","title":"sshai","version":"1.0"}}}'
            sleep 0.4
            printf '%s\n' '{"id":1,"method":"account/rateLimits/read","params":{}}'
            sleep 0.7
          } | conch_timeout 6 codex app-server 2>/dev/null | grep -i 'ratelimit' | tail -1
        fi
        f=${'$'}(ls -t ${'$'}HOME/.codex/sessions/*/*/*/rollout-*.jsonl 2>/dev/null | head -1)
        [ -z "${'$'}f" ] && f=${'$'}(ls -t ${'$'}(find ${'$'}HOME/.codex/sessions -name 'rollout-*.jsonl' 2>/dev/null) 2>/dev/null | head -1)
        [ -n "${'$'}f" ] && grep '"rate_limits"' "${'$'}f" 2>/dev/null | tail -1
    """.trimIndent()
}
