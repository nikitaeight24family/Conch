package ai.eight24family.conch.agent

import ai.eight24family.conch.ssh.startStreamSession

import ai.eight24family.conch.agent.gemini.GeminiAcpEvents
import ai.eight24family.conch.agent.gemini.GeminiAcpWire
import ai.eight24family.conch.agent.gemini.GeminiAcpWire.str
import ai.eight24family.conch.agent.gemini.GeminiMessageParser
import ai.eight24family.conch.agent.gemini.GeminiSpec
import ai.eight24family.conch.agent.shellEscape
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * PERSISTENT **Gemini ACP** channel — Agent Client Protocol over a
 * long-lived `gemini --experimental-acp` process (the transport gemini's
 * own IDE integrations use). Brings Gemini chats the interactivity that
 * headless stream-json can NOT provide (a confirmation-needing tool there
 * just dies with CONFIRMATION_REQUIRED):
 *
 *  - `session/request_permission` → live [AgentMessage.PermissionRequest]
 *    cards; the answer echoes the agent-defined optionId (kind-driven
 *    allow_once / reject_once pick — never hardcoded ids);
 *  - streamed `agent_message_chunk` / `agent_thought_chunk` accumulate
 *    into in-place bubbles / a live thinking note;
 *  - tool_call / tool_call_update / plan map via [GeminiAcpEvents];
 *  - Stop is a real `session/cancel` (the in-flight `session/prompt`
 *    resolves with stopReason "cancelled"); per ACP contract pending
 *    permission requests are answered `{outcome:{outcome:"cancelled"}}`;
 *  - resume rides `session/load` when the agent advertises the
 *    loadSession capability (history replay updates are suppressed — the
 *    chat is already hydrated from the saved session file).
 *
 * Wire shapes in [GeminiAcpWire] — verified against the canonical ACP v1
 * JSON schema. Failure discipline mirrors the other persistent channels:
 * launch/handshake/session failure → [broken] → silent permanent fallback
 * to the proven one-shot `gemini --print` path; transport death mid-turn
 * → undelivered prompt + `Failed("disconnected")` → silent reconnect.
 */
internal class AgentSessionGeminiAcp(
    private val server: ai.eight24family.conch.domain.Server,
    private val scope: CoroutineScope,
    private val sshLifecycle: AgentSessionSshLifecycle,
    private val history: AgentSessionHistory,
    private val onStateChange: (SessionState) -> Unit,
    private val getState: () -> SessionState,
    private val getResumeId: () -> String?,
    private val setResumeId: (String) -> Unit,
    private val cwdSnapshot: () -> String?,
    private val getModelOverride: () -> String?,
    private val getApprovalMode: () -> ai.eight24family.conch.data.prefs.AgentApprovalMode,
    private val loginShell: (String) -> String,
    private val getAuthPrep: () -> String,
    private val onPromptUndelivered: (String) -> Unit,
) {
    private val tag = "Conch-GeminiAcp"

    /** Launch params whose change forces a process restart. ACP has no
     *  per-turn overrides — model/approval ride the LAUNCH flags. */
    private data class LaunchParams(
        val model: String?,
        val approval: ai.eight24family.conch.data.prefs.AgentApprovalMode,
        val authPrep: String,
        val cwd: String?,
    )

    @Volatile private var procSession: Session? = null
    @Volatile private var procCmd: Session.Command? = null
    @Volatile private var procAlive = false
    @Volatile private var launched: LaunchParams? = null
    @Volatile private var acpSessionId: String? = null
    /** True while session/load replays history — those updates must NOT
     *  reach the chat (it's already hydrated from the saved file). */
    @Volatile private var loadingReplay = false
    private var readerJob: Job? = null

    private val writeLock = Any()
    private val reqCounter = AtomicLong(0)
    private var turnSeq = 0

    private val pendingResponses =
        java.util.concurrent.ConcurrentHashMap<Long, CompletableDeferred<GeminiAcpWire.Incoming.Response?>>()

    private data class PendingPermission(
        val idElement: JsonElement,
        val options: List<GeminiAcpWire.PermissionOption>,
    )

    private val pendingPermissions =
        java.util.concurrent.ConcurrentHashMap<String, PendingPermission>()

    /** Streaming chunk accumulation: message key → builder. */
    private val chunkBuffers = HashMap<String, StringBuilder>()

    @Volatile var broken = false
        private set

    /** Execute one turn. False ONLY on launch-level failure ([broken]
     *  set, prompt undelivered) — caller reruns via the one-shot path. */
    suspend fun runTurn(text: String, imagePaths: List<String> = emptyList()): Boolean = withContext(Dispatchers.IO) {
        val client = sshLifecycle.liveClient()
        if (client == null || !client.isConnected) {
            android.util.Log.w(tag, "runTurn ABORT: transport down")
            onPromptUndelivered(text)
            onStateChange(SessionState.Failed("disconnected"))
            return@withContext true
        }
        onStateChange(SessionState.Working)
        // Re-arm at turn start: an idle Stop leaves userCancelled=true and the
        // stale flag makes THIS turn take the "user Stop — silent" branch on a
        // real failure (zombie chat). Same guard as Claude/Codex/one-shot.
        sshLifecycle.userCancelled = false
        try {
            if (!ensureReady()) return@withContext false
            val sid = acpSessionId ?: run { broken = true; return@withContext false }
            turnSeq++
            // Where this turn's output starts — the discriminator for "did the
            // agent take the prompt before it died" below.
            val historyAtTurnStart = history.history.value.size
            val promptId = reqCounter.incrementAndGet()
            val resp = rpc(
                promptId,
                GeminiAcpWire.encodePrompt(promptId, sid, text, imagePaths),
                // The prompt RESPONSE arrives at END of turn — this await
                // IS the turn (unlike codex, where turn/start acks fast).
                timeoutMs = TURN_TIMEOUT_MS,
            )
            when {
                // User pressed Stop — the cancel may resolve the prompt as
                // null/error; that's EXPECTED, never a user-facing error.
                sshLifecycle.userCancelled -> {
                    android.util.Log.d(tag, "turn ended after user Stop — silent")
                }
                resp == null && !procAlive -> {
                    // Same rule as the Claude/Codex channels: if anything came
                    // back for this turn, the agent HAD the prompt and only the
                    // answer died with the process — re-sending would re-run a
                    // paid turn on every reconnect (2026-08-16). Silence means
                    // the prompt may never have left, so it still goes back.
                    if (history.hasAssistantOutputSince(historyAtTurnStart)) {
                        android.util.Log.w(
                            tag,
                            "acp process died mid-turn after it took the prompt — reconnect only, NOT re-sending",
                        )
                    } else {
                        android.util.Log.w(tag, "acp process died mid-turn with no output — handing the prompt back")
                        onPromptUndelivered(text)
                    }
                    onStateChange(SessionState.Failed("disconnected"))
                    return@withContext true
                }
                resp == null -> {
                    android.util.Log.w(tag, "prompt timed out / failed — cancelling")
                    writeLine(GeminiAcpWire.encodeCancel(sid))
                    history.emitMsg(AgentMessage.Error(UUID.randomUUID().toString(), "gemini turn timed out"))
                }
                resp.error != null -> {
                    val msg = SilentlyTry.logged(tag, "prompt error message") {
                        resp.error.str("message")
                    } ?: "prompt failed"
                    history.emitMsg(AgentMessage.Error(UUID.randomUUID().toString(), msg))
                }
                else -> {
                    when (val stop = resp.result?.str("stopReason")) {
                        "end_turn", "cancelled", null -> Unit
                        "refusal" -> history.emitMsg(
                            AgentMessage.Error(UUID.randomUUID().toString(), "Gemini refused to continue")
                        )
                        else -> history.emitMsg(GeminiMessageParser.note(
                            "turn stopped · ${stop.replace('_', ' ')}",
                            tone = AgentMessage.EventNote.Tone.WARN,
                        ))
                    }
                }
            }
            true
        } finally {
            sshLifecycle.userCancelled = false
            synchronized(chunkBuffers) { chunkBuffers.clear() }
            history.flushStreamingBuffer()
            if (getState() == SessionState.Working) onStateChange(SessionState.Running)
        }
    }

    /** Process + initialize + session open. True when promptable. */
    private suspend fun ensureReady(): Boolean {
        val params = LaunchParams(
            model = getModelOverride()?.takeIf { it.isNotBlank() },
            approval = getApprovalMode(),
            authPrep = getAuthPrep(),
            cwd = cwdSnapshot(),
        )
        if (procAlive && launched == params && acpSessionId != null) return true
        if (procAlive) android.util.Log.d(tag, "launch params changed → restarting acp process")
        teardownProcess()

        val client = sshLifecycle.liveClient() ?: return false
        try {
            // autoExpand: the long-lived ACP channel is read continuously;
            // protect it from receive-window starvation under shared-transport
            // contention (see [startStreamSession]).
            val sess = client.startStreamSession()
            val modelArg = params.model?.let { " --model " + shellEscape(it) } ?: ""
            val approvalArg = when (params.approval) {
                // Gemini has no plan mode — the closest truth is "ask about everything".
                ai.eight24family.conch.data.prefs.AgentApprovalMode.PLAN,
                ai.eight24family.conch.data.prefs.AgentApprovalMode.SAFE -> " --approval-mode default"
                ai.eight24family.conch.data.prefs.AgentApprovalMode.AUTO -> " --approval-mode auto_edit"
                ai.eight24family.conch.data.prefs.AgentApprovalMode.YOLO -> " --approval-mode yolo"
            }
            // Same api-key preload as the one-shot path (key may hide
            // behind the rc interactive guard); stderr DROPPED — gemini
            // logs there and any line breaks the stdout JSONL framing.
            val inner = GeminiSpec.apiKeyPreload +
                "gemini --skip-trust --experimental-acp$approvalArg$modelArg 2>/dev/null"
            val cmd = sess.exec(loginShell(params.authPrep + inner))
            procSession = sess
            procCmd = cmd
            procAlive = true
            launched = params
            startReader(cmd)
        } catch (t: Throwable) {
            android.util.Log.w(tag, "acp launch failed: ${t.message} — falling back to one-shot", t)
            broken = true
            teardownProcess()
            return false
        }

        // initialize — also tells us whether session/load is supported.
        val initId = reqCounter.incrementAndGet()
        val init = rpc(initId, GeminiAcpWire.encodeInitialize(initId, appVersion()), timeoutMs = 30_000)
        val initResult = init?.result
        if (initResult == null) {
            android.util.Log.w(tag, "initialize failed — gemini too old for ACP? falling back to one-shot")
            broken = true
            teardownProcess()
            return false
        }
        val canLoad = SilentlyTry.loggedOrElse(tag, "read loadSession cap", false) {
            initResult["agentCapabilities"]?.jsonObject?.get("loadSession")
                ?.let { (it as? JsonPrimitive)?.contentOrNull == "true" } ?: false
        }

        // session/new | session/load. cwd is REQUIRED by the schema —
        // default to $HOME via shell expansion not possible here, so use
        // the snapshot or the conventional remote home path.
        val cwd = params.cwd?.takeIf { it.isNotBlank() } ?: guessHome()
        val rid = getResumeId()
        val openId = reqCounter.incrementAndGet()
        val resp: GeminiAcpWire.Incoming.Response?
        if (rid != null && canLoad) {
            // Replay suppression: the agent re-streams the whole history
            // via session/update before answering session/load.
            loadingReplay = true
            resp = try {
                rpc(openId, GeminiAcpWire.encodeSessionLoad(openId, rid, cwd), timeoutMs = 90_000)
            } finally {
                loadingReplay = false
            }
            if (resp?.result == null) {
                android.util.Log.w(tag, "session/load failed for rid=$rid — falling back to one-shot resume")
                broken = true
                teardownProcess()
                return false
            }
            acpSessionId = rid
        } else if (rid != null) {
            // Agent can't load sessions — the one-shot path CAN resume
            // (`--resume`), so don't silently fork a new thread here.
            android.util.Log.w(tag, "agent lacks loadSession — resumed chat stays on one-shot path")
            broken = true
            teardownProcess()
            return false
        } else {
            resp = rpc(openId, GeminiAcpWire.encodeSessionNew(openId, cwd), timeoutMs = 30_000)
            val sid = resp?.result?.str("sessionId")
            if (sid.isNullOrBlank()) {
                android.util.Log.w(tag, "session/new failed — falling back to one-shot")
                broken = true
                teardownProcess()
                return false
            }
            acpSessionId = sid
            setResumeId(sid)
        }
        android.util.Log.d(tag, "acp session ready id=$acpSessionId resumed=${rid != null}")
        return true
    }

    private suspend fun rpc(
        id: Long,
        line: String,
        timeoutMs: Long,
    ): GeminiAcpWire.Incoming.Response? {
        val deferred = CompletableDeferred<GeminiAcpWire.Incoming.Response?>()
        pendingResponses[id] = deferred
        if (!writeLine(line)) {
            pendingResponses.remove(id)
            return null
        }
        val resp = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pendingResponses.remove(id)
        return resp
    }

    private fun startReader(cmd: Session.Command) {
        readerJob = scope.launch {
            try {
                BufferedReader(InputStreamReader(cmd.inputStream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        when (val msg = GeminiAcpWire.parseLine(line)) {
                            is GeminiAcpWire.Incoming.Response ->
                                msg.id?.let { pendingResponses.remove(it)?.complete(msg) }
                            is GeminiAcpWire.Incoming.ServerReq -> handleServerRequest(msg)
                            is GeminiAcpWire.Incoming.Notification ->
                                if (msg.method == "session/update") handleUpdate(msg.params)
                                else android.util.Log.d(tag, "notification ${msg.method}: ${line.take(160)}")
                            null -> android.util.Log.d(tag, "non-rpc stdout: ${line.take(160)}")
                        }
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w(tag, "reader died: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                android.util.Log.w(tag, "reader EOF — acp process gone")
                procAlive = false
                acpSessionId = null
                pendingPermissions.keys.toList().forEach { retirePermission(it) }
                pendingResponses.values.forEach { it.complete(null) }
                pendingResponses.clear()
            }
        }
    }

    private fun handleUpdate(params: JsonObject) {
        // session/load replay — the chat is already hydrated from the
        // saved session file; re-rendering the replay would duplicate
        // every past turn with fresh ids.
        if (loadingReplay) return
        val update = SilentlyTry.logged(tag, "update obj") { params["update"]?.jsonObject } ?: return
        when (update.str("sessionUpdate")) {
            "agent_message_chunk" -> {
                val text = chunkText(update) ?: return
                val key = chunkKey(update, thought = false)
                val buf = synchronized(chunkBuffers) {
                    chunkBuffers.getOrPut(key) { StringBuilder() }.append(text)
                }
                history.emitMsg(AgentMessage.AssistantText(key, buf.toString()))
            }
            "agent_thought_chunk" -> {
                val text = chunkText(update) ?: return
                val key = chunkKey(update, thought = true)
                val buf = synchronized(chunkBuffers) {
                    chunkBuffers.getOrPut(key) { StringBuilder() }.append(text)
                }
                val full = buf.toString()
                // Live thinking note — upserts in place as chunks stream.
                history.emitMsg(GeminiMessageParser.note(
                    "thinking · ${full.take(120)}",
                    detail = full.takeIf { it.length > 120 },
                    id = key,
                ))
            }
            else -> {
                for (m in GeminiAcpEvents.mapUpdate(update, turnTag = "t$turnSeq")) {
                    history.emitMsg(m)
                }
            }
        }
    }

    private fun chunkText(update: JsonObject): String? =
        SilentlyTry.logged(tag, "chunk text") {
            update["content"]?.jsonObject?.str("text")
        }?.takeIf { it.isNotEmpty() }

    private fun chunkKey(update: JsonObject, thought: Boolean): String {
        val mid = update.str("messageId") ?: "m"
        return "gemacp_t${turnSeq}_${mid}${if (thought) "-think" else ""}"
    }

    private fun handleServerRequest(req: GeminiAcpWire.Incoming.ServerReq) {
        val key = (req.id as? JsonPrimitive)?.contentOrNull ?: req.id.toString()
        when (req.method) {
            "session/request_permission" -> {
                val options = GeminiAcpWire.parsePermissionOptions(req.params)
                if (options.isEmpty()) {
                    // Nothing to choose from — cancel per protocol rather
                    // than hanging the turn on an unanswerable request.
                    writeLine(GeminiAcpWire.encodePermissionCancelled(req.id))
                    return
                }
                pendingPermissions[key] = PendingPermission(req.id, options)
                val toolCall = SilentlyTry.logged(tag, "perm toolCall") { req.params["toolCall"]?.jsonObject }
                val title = toolCall?.str("title").orEmpty()
                history.emitMsg(
                    AgentMessage.PermissionRequest(
                        id = "perm-gemacp-$key",
                        requestId = key,
                        toolName = toolCall?.str("kind") ?: "tool",
                        description = title.ifBlank { "approve this action" },
                        input = listOfNotNull(
                            title.takeIf { it.isNotBlank() },
                            toolCall?.get("rawInput")?.toString(),
                        ).joinToString("\n"),
                        raw = req.params.toString(),
                        // Only offer "always allow" when the agent actually
                        // exposes an allow_always option for this request.
                        canAllowSession = options.any { it.kind == "allow_always" },
                    )
                )
            }
            // fs/* and terminal/* shouldn't flow (capabilities declared
            // false) — refuse explicitly; an unanswered request hangs.
            else -> writeLine(GeminiAcpWire.encodeErrorResponse(
                req.id, "Not supported by this client (${req.method})",
            ))
        }
    }

    private fun retirePermission(key: String) {
        pendingPermissions.remove(key) ?: return
        history.resolvePermission(key, AgentMessage.PermissionRequest.Resolution.DENIED)
    }

    /** Allow/deny a live permission card. IO-hopped (card taps = MAIN). */
    suspend fun respondPermission(requestId: String, decision: PermissionDecision): Boolean =
        withContext(Dispatchers.IO) {
            val pending = pendingPermissions.remove(requestId) ?: return@withContext false
            val optionId = GeminiAcpWire.pickOption(
                pending.options,
                allow = decision != PermissionDecision.DENY,
                preferAlways = decision == PermissionDecision.ALLOW_SESSION,
            )
            if (optionId == null) {
                writeLine(GeminiAcpWire.encodePermissionCancelled(pending.idElement))
            } else {
                writeLine(GeminiAcpWire.encodePermissionSelected(pending.idElement, optionId))
            }
            true
        }

    /**
     * Stop = `session/cancel` notification; the in-flight session/prompt
     * resolves with stopReason "cancelled". ACP contract: pending
     * permission requests MUST be answered with the cancelled outcome.
     * Escalates to a process kill if the prompt doesn't resolve.
     */
    fun cancelTurn() {
        sshLifecycle.userCancelled = true
        val sid = acpSessionId
        // ⚠ Fenced to the turn Stop was aimed at — see the same guard in
        // AgentSessionCodexAppServer.cancelTurn. Escalating on "the session is
        // Working" kills whatever is running four seconds later, and four
        // seconds is exactly long enough for the user to hit Stop and send a
        // correction, so the kill lands on their NEW turn.
        val target = turnSeq
        scope.launch {
            pendingPermissions.entries.toList().forEach { (key, pending) ->
                writeLine(GeminiAcpWire.encodePermissionCancelled(pending.idElement))
                retirePermission(key)
            }
            if (sid != null) writeLine(GeminiAcpWire.encodeCancel(sid))
            kotlinx.coroutines.delay(4_000)
            if (turnSeq != target) {
                android.util.Log.d(tag, "stop escalation skipped — a newer turn owns the process now")
                return@launch
            }
            if (getState() == SessionState.Working && procAlive) {
                android.util.Log.w(tag, "cancel not honored in 4s — killing acp process")
                teardownProcess()
                if (getState() == SessionState.Working) onStateChange(SessionState.Running)
            }
        }
    }

    private fun writeLine(line: String): Boolean = synchronized(writeLock) {
        val cmd = procCmd ?: run {
            android.util.Log.w(tag, "stdin write skipped: procCmd is null")
            return false
        }
        return try {
            cmd.outputStream.write((line + "\n").toByteArray(Charsets.UTF_8))
            cmd.outputStream.flush()
            true
        } catch (t: Throwable) {
            android.util.Log.w(
                tag,
                "stdin write failed: ${t.javaClass.name}: ${t.message} " +
                    "chanOpen=${procSession?.isOpen} connected=${sshLifecycle.liveClient()?.isConnected} alive=$procAlive",
                t,
            )
            procAlive = false
            false
        }
    }

    fun teardownProcess() {
        readerJob?.cancel()
        readerJob = null
        procCmd?.let { cmd ->
            SilentlyTry.fired(tag, "close acp stdin") { cmd.outputStream.close() }
        }
        procSession?.let { s ->
            SilentlyTry.fired(tag, "close acp channel") { s.close() }
        }
        procCmd = null
        procSession = null
        procAlive = false
        launched = null
        acpSessionId = null
        loadingReplay = false
        synchronized(chunkBuffers) { chunkBuffers.clear() }
        pendingPermissions.clear()
    }

    /** session/new requires an ABSOLUTE cwd; fresh chats have no snapshot
     *  yet. The remote user's home is the same default the one-shot path
     *  effectively runs in (login shell starts at $HOME). */
    private fun guessHome(): String {
        val user = server.username.ifBlank { "user" }
        return if (user == "root") "/root" else "/home/$user"
    }

    private fun appVersion(): String =
        SilentlyTry.logged(tag, "read app version") {
            ai.eight24family.conch.BuildConfig.VERSION_NAME
        } ?: "0"

    companion object {
        private const val TURN_TIMEOUT_MS = 15L * 60 * 1000
    }
}
