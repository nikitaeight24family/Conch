package ai.eight24family.conch.ui.viewmodel

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.AgentSession
import ai.eight24family.conch.agent.SessionState
import ai.eight24family.conch.agent.spec.AgentSpecRegistry
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

/**
 * Tail-poll loop for a remote JSONL session file.
 *
 * Catches up the local cache to the server's view, then polls every few seconds for
 * external growth (e.g. the user typed on their PC). Growth that happens during OUR
 * own turn (state == Working) is silently consumed — those bytes are streaming in via
 * runOneShot's stdout collector and re-parsing them would duplicate every assistant /
 * tool message.
 *
 * Owns:
 *  - [remoteActive] — unix-millis of last EXTERNAL growth (drives the "● remote session
 *    active" banner).
 *  - [remoteFileOpen] — whether anything on the server still has the JSONL open for
 *    writing (drives the working-spinner; flips off cleanly the moment the process dies).
 *  - [tailBackgrounded] — whether the chat is currently backgrounded (drives the
 *    exponential back-off in [pickPollInterval]).
 *
 * Stateless helpers:
 *  - [parseJsonl] (ByteBuffer + ByteArray overloads), [parseJsonlText] — JSONL → AgentMessage list.
 *  - [appendDeduped] — defensive merge into the AgentSession's history.
 *  - [pickPollInterval] — adaptive cadence (working/foreground/background).
 *  - [statSize], [statSizeAndAgentAlive], [fetchTail] — SSH probes via `execOnLive`.
 *
 * The orchestrating `tailPoll(...)` runs as a single launched coroutine per session
 * — the ChatViewModel kicks one off from `pollerJobs[localId]`.
 *
 * See ChatViewModel.kt prior to extraction for the original inline comments.
 */
internal class ChatViewModelTailPoll(
    /** Read-only accessor — true while the chat is backgrounded; null
     *  while foreground.  Used to drive [pickPollInterval]. */
    private val backgroundedSince: () -> Long?,
) {
    /**
     * Unix-millis timestamp of the most recent EXTERNAL growth seen on the remote
     * session's JSONL file.
     */
    private val _remoteActive = MutableStateFlow<Long?>(null)
    val remoteActive: StateFlow<Long?> = _remoteActive.asStateFlow()

    /**
     * True when *something on the server* still has the remote session JSONL open for
     * writing — typically the `claude --print` process for an in-flight turn.
     */
    private val _remoteFileOpen = MutableStateFlow(false)
    val remoteFileOpen: StateFlow<Boolean> = _remoteFileOpen.asStateFlow()

    fun setRemoteFileOpen(value: Boolean) {
        _remoteFileOpen.value = value
    }

    /**
     * Catch up on bytes added to the remote session file since [initialOffset], then
     * poll every few seconds for further growth.
     */
    suspend fun tailPoll(
        s: AgentSession,
        agent: Agent,
        sessionId: String,
        path: String,
        initialOffset: Long,
    ) {
        val cache = ServiceLocator.historyCache
        var lastOffset = initialOffset

        // Wait for SSH to be Running so execOnLive doesn't fall back to a fresh handshake.
        var waited = 0
        while (s.state.value !is SessionState.Running &&
            s.state.value !is SessionState.Working &&
            waited < 15_000
        ) {
            delay(200); waited += 200
        }
        if (s.state.value is SessionState.Failed || s.state.value is SessionState.Closed) return

        // Immediate alive-check before the first poll-loop delay.
        run {
            val (_, alive0) = statSizeAndAgentAlive(s, path, sessionId)
            _remoteFileOpen.value = alive0
        }

        // Truncate detection + merge-not-wipe.
        val (preSize, _) = statSizeAndAgentAlive(s, path, sessionId)
        if (preSize != null && preSize < lastOffset) {
            android.util.Log.w(
                "SshAi-Tail",
                "compact detected sid=${sessionId.take(8)} cachedOffset=$lastOffset serverSize=$preSize — merging cache (NOT touching live history)",
            )
            val serverFull = fetchTail(s, path, 0L) ?: ByteArray(0)
            val merged = cache.mergeServer(sessionId, serverFull)
            if (merged != null) cache.save(sessionId, merged) // null = local too large to merge; keep file
            lastOffset = preSize.toLong()
            android.util.Log.i(
                "SshAi-Tail",
                "compact merged sid=${sessionId.take(8)} mergedBytes=${merged?.size ?: -1} newOffset=$lastOffset",
            )
        }

        // ── Catch-up pass ──
        val tailBytes = fetchTail(s, path, lastOffset) ?: ByteArray(0)
        android.util.Log.i(
            "SshAi-Tail",
            "catch-up sid=${sessionId.take(8)} lastOffset=$lastOffset tailBytes=${tailBytes.size} state=${s.state.value::class.simpleName}",
        )
        if (tailBytes.isNotEmpty()) {
            val safe = trimToLastNewline(tailBytes)
            if (safe.isNotEmpty()) {
                val parsed = parseJsonl(safe, agent)
                val added: Int
                if (lastOffset == 0L && s.history.value.isEmpty()) {
                    s.loadHistory(parsed)
                    cache.save(sessionId, safe)
                    added = parsed.size
                } else {
                    added = appendDeduped(s, parsed)
                    cache.append(sessionId, safe)
                }
                lastOffset += safe.size.toLong()
                android.util.Log.i(
                    "SshAi-Tail",
                    "catch-up parsed=${parsed.size} added=$added newOffset=$lastOffset history=${s.history.value.size}",
                )
            }
        } else if (tailBytes.isEmpty() && lastOffset > 0) {
            val (serverSize, _) = statSizeAndAgentAlive(s, path, sessionId)
            android.util.Log.w(
                "SshAi-Tail",
                "catch-up EMPTY sid=${sessionId.take(8)} lastOffset=$lastOffset serverSize=$serverSize " +
                    (if (serverSize != null && serverSize > lastOffset)
                        "STALE: server has ${serverSize - lastOffset}B unread (fetchTail failed?)"
                    else
                        "cache up-to-date (or server smaller)")
            )
        }

        // ── Poll loop ──
        var lastSeenWorking = false
        var idleTicks = 0
        while (true) {
            val bgSince = backgroundedSince()
            val bgFor = if (bgSince != null) System.currentTimeMillis() - bgSince else 0L
            val interval = pickPollInterval(
                isWorking = lastSeenWorking,
                bgForMs = bgFor,
                idleTicks = idleTicks,
            )
            delay(interval)
            val curState = s.state.value
            if (curState is SessionState.Failed || curState is SessionState.Closed) return
            val curWorking = curState is SessionState.Working
            val (size, agentAlive) = statSizeAndAgentAlive(s, path, sessionId)
            _remoteFileOpen.value = agentAlive || curWorking
            if (size == null) {
                idleTicks++
                lastSeenWorking = curWorking
                continue
            }
            // Mid-poll compact detection — same merge-not-wipe policy as the catch-up pass.
            if (size < lastOffset) {
                android.util.Log.w(
                    "SshAi-Tail",
                    "compact mid-poll sid=${sessionId.take(8)} cachedOffset=$lastOffset serverSize=$size — merging cache only",
                )
                val serverFull = fetchTail(s, path, 0L) ?: ByteArray(0)
                val merged = cache.mergeServer(sessionId, serverFull)
                if (merged != null) cache.save(sessionId, merged) // null = local too large to merge; keep file
                lastOffset = size
                idleTicks = 0
                lastSeenWorking = curWorking
                continue
            }
            if (size > lastOffset) {
                val bytes = fetchTail(s, path, lastOffset)
                if (bytes != null && bytes.isNotEmpty()) {
                    val safe = trimToLastNewline(bytes)
                    if (safe.isNotEmpty()) {
                        val parsed = parseJsonl(safe, agent)
                        val isOurs = curWorking || lastSeenWorking
                        if (!isOurs) {
                            val added = appendDeduped(s, parsed)
                            if (added > 0) {
                                _remoteActive.value = System.currentTimeMillis()
                            }
                        }
                        if (parsed.any { it is AgentMessage.Result }) {
                            _remoteFileOpen.value = false
                        }
                        cache.append(sessionId, safe)
                        lastOffset += safe.size.toLong()
                    }
                }
                // File grew — reset back-off.
                idleTicks = 0
            } else {
                idleTicks++
            }
            lastSeenWorking = curWorking
        }
    }

    /**
     * Compute the next poll delay based on what's actually happening:
     *   • turn in flight (`isWorking`) → fast 5 s
     *   • backgrounded ≥ 5 min        → 60 s
     *   • backgrounded < 5 min        → 30 s
     *   • foreground + idle ticks     → exponential back-off 5 → 10 → 30 s
     */
    fun pickPollInterval(isWorking: Boolean, bgForMs: Long, idleTicks: Int): Long {
        val dataSaver = SilentlyTry.loggedOrElse("SshAi-Chat", "read data saver pref", false) {
            runBlocking { ServiceLocator.preferences.dataSaverEnabled.first() }
        }
        val k = if (dataSaver) 6L else 1L
        return when {
            isWorking -> POLL_INTERVAL_MS * (if (dataSaver) 3L else 1L)
            bgForMs >= BG_DEEP_AFTER_MS -> POLL_INTERVAL_BG_DEEP_MS * k
            bgForMs > 0 -> POLL_INTERVAL_BACKGROUND_MS * k
            idleTicks >= 6 -> POLL_INTERVAL_MS * 6 * k
            idleTicks >= 3 -> POLL_INTERVAL_MS * 2 * k
            else -> POLL_INTERVAL_MS * k
        }
    }

    /**
     * Append [incoming] to the session's history, skipping any whose id is already
     * there. Returns the count actually added.
     */
    fun appendDeduped(s: AgentSession, incoming: List<AgentMessage>): Int {
        if (incoming.isEmpty()) return 0
        val hist = s.history.value
        val existing = hist.asSequence().map { it.id }.toHashSet()
        // Trimmed bodies of user prompts ALREADY shown (the optimistic copy added
        // in send()). Drop a JSONL echo of one of them even if the 60s
        // wasRecentlySent window has lapsed — that window expires during a slow
        // reconnect (key prompt + delay), which is exactly when showed up TWICE.
        // Legit repeats stay correct: every send() adds its OWN optimistic copy
        // first, so the on-screen count always matches the number of sends.
        val shownUserTexts = hist.asSequence()
            .filterIsInstance<AgentMessage.UserText>()
            .map { it.text.trim() }
            .toHashSet()
        val fresh = incoming.filter {
            it.id !in existing &&
                !(it is AgentMessage.UserText &&
                    (s.wasRecentlySent(it.text) || it.text.trim() in shownUserTexts))
        }
        if (fresh.isNotEmpty()) s.appendMessages(fresh)
        return fresh.size
    }

    suspend fun statSize(s: AgentSession, path: String): Long? {
        val inner = "stat -c %s ${shQuote(path)} 2>/dev/null || stat -f %z ${shQuote(path)} 2>/dev/null"
        val out = s.execOnLive("bash -lc " + shQuote(inner)) ?: return null
        return out.trim().lineSequence().firstOrNull { it.isNotBlank() }?.toLongOrNull()
    }

    /**
     * Returns (file size, agent-process-alive-for-this-session). One SSH exec per
     * tick — combined `stat` for size + `pgrep` for liveness.
     */
    suspend fun statSizeAndAgentAlive(
        s: AgentSession,
        path: String,
        sessionId: String,
    ): Pair<Long?, Boolean> {
        val q = shQuote(path)
        val sid = shQuote(sessionId)
        val inner = "stat -c %s $q 2>/dev/null || stat -f %z $q 2>/dev/null; echo ---; " +
            "pgrep -af $sid 2>/dev/null | " +
            "awk '\$2 != \"bash\" && \$2 != \"sh\" && /(claude|codex|gemini)/' | head -3"
        val out = s.execOnLive("bash -lc " + shQuote(inner)) ?: return null to false
        val (sizePart, alivePart) = out.split("---", limit = 2).let {
            it[0] to (it.getOrNull(1).orEmpty())
        }
        val size = sizePart.trim().lineSequence()
            .firstOrNull { it.isNotBlank() }?.toLongOrNull()
        val matchedLines = alivePart.lines().filter { it.isNotBlank() }
        val alive = matchedLines.isNotEmpty()
        android.util.Log.d(
            "SshAi-Tail",
            "statSize=$size alive=$alive sid=${sessionId.take(8)} match=${matchedLines.firstOrNull()?.take(80)}"
        )
        return size to alive
    }

    suspend fun fetchTail(s: AgentSession, path: String, fromOffset: Long): ByteArray? {
        val inner = if (fromOffset <= 0L) {
            "cat ${shQuote(path)}"
        } else {
            "tail -c +${fromOffset + 1} ${shQuote(path)}"
        }
        val out = s.execOnLive("bash -lc " + shQuote(inner)) ?: return null
        return out.toByteArray(Charsets.UTF_8)
    }

    fun trimToLastNewline(bytes: ByteArray): ByteArray =
        ai.eight24family.conch.util.JsonlUtils.trimToLastNewline(bytes)

    /** Buffer-flavoured overload — streams the (mmap) `ByteBuffer`
     *  line-by-line through UTF-8 without ever materialising the whole
     *  session as one `String`. */
    fun parseJsonl(buffer: java.nio.ByteBuffer, agent: Agent): List<AgentMessage> {
        if (!buffer.hasRemaining()) return emptyList()
        // Per-line decode, NOT a whole-buffer Charset.decode(). The latter
        // allocates a CharBuffer sized to the entire session (UTF-8 → up to
        // 1 char/byte → 2 bytes/char), so a ~28 MB chat forced a ~57 MB
        // transient that OOM-killed the app the instant such a session was
        // opened — once "load all" started caching big sessions (crash at
        // this line, 2026-05-29). forEachLine bounds the allocation to one
        // line.
        val spec = AgentSpecRegistry[agent]
        val out = mutableListOf<AgentMessage>()
        var turnSeq = 0
        ai.eight24family.conch.util.JsonlUtils.forEachLine(
            buffer,
            onOversize = { head, total ->
                // A single >16 MB line (e.g. a 475 MB runaway tool dump) can't
                // be parsed/rendered whole — show it as a truncated tool output
                // in its place so the session stays complete and nothing
                // silently vanishes. Universal across agents.
                out += AgentMessage.ToolResult(
                    id = "oversized-$total-${out.size}",
                    toolUseId = "",
                    output = ai.eight24family.conch.util.JsonlUtils.oversizedPreview(head, total),
                    isError = false,
                )
            },
        ) { line ->
            if (line.isNotBlank()) {
                if (line.contains("\"type\":\"turn.started\"") ||
                    line.contains("\"type\":\"thread.started\"")
                ) {
                    turnSeq++
                }
                val turnTag = if (turnSeq == 0) "" else "t${turnSeq}_"
                spec.parseStreamLine(line, turnTag).forEach { out += it }
            }
        }
        return out.distinctBy { it.id }
    }

    fun parseJsonl(bytes: ByteArray, agent: Agent): List<AgentMessage> {
        if (bytes.isEmpty()) return emptyList()
        return parseJsonlText(String(bytes, Charsets.UTF_8), agent)
    }

    /**
     * Shared parse path. `distinctBy { it.id }` defends against Claude's JSONL legitimately
     * repeating `toolu_xxx` ids (LazyColumn crashes on duplicate keys). Turn-tag bump on
     * `turn.started` / `thread.started` keeps codex's per-turn item ids unique.
     */
    fun parseJsonlText(text: String, agent: Agent): List<AgentMessage> {
        if (text.isEmpty()) return emptyList()
        val spec = AgentSpecRegistry[agent]
        val out = mutableListOf<AgentMessage>()
        var turnSeq = 0
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            if (line.contains("\"type\":\"turn.started\"") ||
                line.contains("\"type\":\"thread.started\"")
            ) {
                turnSeq++
            }
            val turnTag = if (turnSeq == 0) "" else "t${turnSeq}_"
            spec.parseStreamLine(line, turnTag).forEach { out += it }
        }
        return out.distinctBy { it.id }
    }

    private fun shQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    companion object {
        /** Cadence of the remote-tail poller. 5 s feels alive without churning data. */
        const val POLL_INTERVAL_MS: Long = 5_000L
        /** Background poll cadence — chat-might-come-back-soon. */
        const val POLL_INTERVAL_BACKGROUND_MS: Long = 30_000L
        /** Deep-background poll cadence — chat-isn't-coming-back-soon. */
        const val POLL_INTERVAL_BG_DEEP_MS: Long = 60_000L
        const val BG_DEEP_AFTER_MS: Long = 5 * 60_000L
    }
}
