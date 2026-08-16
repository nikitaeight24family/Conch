package ai.eight24family.conch.agent

import ai.eight24family.conch.agent.claude.ClaudeControlWire
import ai.eight24family.conch.agent.spec.AgentSpecRegistry
import ai.eight24family.conch.agent.spec.ExecInput
import ai.eight24family.conch.agent.spec.ParserHelpers
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.contentOrNull
import net.schmizz.sshj.connection.channel.direct.Session
import ai.eight24family.conch.ssh.startStreamSession
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * PERSISTENT bidirectional stream-json channel to the agent CLI — the
 * transport the official Agent SDK uses (`--input-format stream-json
 * --output-format stream-json`, no `--print`). One long-lived process
 * per chat instead of one process per turn:
 *
 *  - user turns are JSON lines written to the live stdin;
 *  - the CLI's `control_request`s come back on stdout interleaved with
 *    the usual stream-json events: `can_use_tool` (live permission
 *    prompts in SAFE/AUTO mode + AskUserQuestion option picking) is
 *    surfaced as chat cards and answered with `control_response`s;
 *  - Stop sends a real `interrupt` control request instead of killing
 *    the process;
 *  - mid-chat model/effort/approval changes go over the wire LIVE
 *    (`set_model` / `set_max_thinking_tokens` / `set_permission_mode`)
 *    with no restart; only picks the wire can't express (an unknown
 *    effort level, a refused bypass) fall back to the old
 *    restart-with-`--resume` path via the LaunchParams comparison;
 *  - the `initialize` handshake's response is parsed ([onInitState]) —
 *    the CLI's own model catalog / commands / subagents / account —
 *    and `get_usage` / `get_context_usage` / `file_suggestions` /
 *    `rename_session` ride the same channel (no curl with a raw OAuth
 *    token, no second `claude -p /context` process, no TUI scraping).
 *
 * This is what makes the app interactive-capable at parity with the
 * CLI's own TUI. Wire shapes live in [ClaudeControlWire]; verified
 * against CLI 2.1.170's embedded schemas + the Agent SDK sources.
 *
 * Failure discipline (mirrors [AgentSessionRunOneShot]):
 *  - transport dead before/during a turn → prompt handed back via
 *    [onPromptUndelivered] + `Failed("disconnected")` → the existing
 *    silent auto-reconnect machinery takes over;
 *  - the persistent LAUNCH itself failing (ancient CLI without
 *    `--input-format`, etc.) → [broken] = true and the caller falls
 *    back to the proven one-shot path for the rest of the session —
 *    silently (auto-fix invariant).
 */
internal class AgentSessionPersistentStream(
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
    private val getReasoningOverride: () -> String?,
    private val getApprovalMode: () -> ai.eight24family.conch.data.prefs.AgentApprovalMode,
    private val loginShell: (String) -> String,
    private val getAuthPrep: () -> String,
    /** True while this chat still has to FORK the session it resumes. */
    private val getForkOnce: () -> Boolean = { false },
    private val onPromptUndelivered: (String) -> Unit,
    /** Live reasoning-token feed (`system/thinking_tokens` →
     *  estimated_tokens). null clears the row at turn end. */
    private val onThinkingTokens: (Long?) -> Unit = {},
    /** Parsed `initialize` handshake response — model catalog, slash
     *  commands, subagents, account — republished on every process launch.
     *  The CLI's own registry; replaces the /model TUI scrape as the
     *  catalog source for live chats. */
    private val onInitState: (ai.eight24family.conch.agent.claude.ClaudeInitState) -> Unit = {},
    /** Raw input of a `ScheduleWakeup` call seen on the live stream — the CLI
     *  arming (or ending) a `/loop`. Read by [LoopWatch]; live-only, because a
     *  pending wakeup dies with the process it was armed in. */
    private val onLoopWakeup: (String?) -> Unit = {},
    /** Raw input of a `CronCreate` call — an INTERVAL `/loop`. */
    private val onLoopCron: (String) -> Unit = {},
    /** A turn that was dispatched as `/loop …` ended without scheduling
     *  anything. The user asked for a loop and does not have one; say so. */
    private val onLoopNotArmed: () -> Unit = {},
) {
    private val tag = "SshAi-Persist"

    /** Everything that requires a process RESTART when changed. Captured
     *  at launch; compared at every turn start. */
    private data class LaunchParams(
        val model: String?,
        val reasoning: String?,
        val approval: ai.eight24family.conch.data.prefs.AgentApprovalMode,
        val authPrep: String,
        val cwd: String?,
    )

    @Volatile private var procSession: Session? = null
    @Volatile private var procCmd: Session.Command? = null
    @Volatile private var procAlive = false

    /** Is the CLI process for this chat running RIGHT NOW — i.e. does it still
     *  hold the conversation in memory. Distinct from "the transport is up":
     *  the transport can be rebuilt under us while the process it carried is
     *  long dead, and it is the PROCESS that decides whether the next turn
     *  re-reads (and re-bills) the whole session file. */
    val processAlive: Boolean get() = procAlive
    @Volatile private var launched: LaunchParams? = null
    private var readerJob: Job? = null

    /** Wall-clock of the last stdout line the reader saw. Drives the
     *  INACTIVITY turn timeout: a research turn that is actively streaming
     *  (Agent/Task/Workflow subagents) must NEVER be killed on a wall-clock
     *  deadline — only a genuinely silent channel (wedged process) times out. */
    @Volatile private var lastReaderActivityMs = System.currentTimeMillis()

    /** Serialises every stdin write (turns, control responses). */
    private val writeLock = Any()

    /** Completed when the CURRENT turn's `result` event lands. */
    @Volatile private var turnDone: CompletableDeferred<Boolean>? = null

    /** Did THIS turn schedule anything (either loop flavour)? Read at turn end
     *  against [loopRequestedThisTurn]. */
    @Volatile private var armedThisTurn = false
    /** Was this turn the dispatch of a `/loop …` line? */
    @Volatile private var loopRequestedThisTurn = false

    /** True after a launch-level failure — the session permanently falls
     *  back to the one-shot path (checked by AgentSession's router). */
    @Volatile var broken = false
        private set

    /** request_id → the pending CLI→client control request (can_use_tool).
     *  Needed to echo the ORIGINAL tool input back on allow. */
    private val pendingControls =
        java.util.concurrent.ConcurrentHashMap<String, ClaudeControlWire.ControlRequest>()

    /** request_id → awaiter for a control_response to a request WE sent
     *  (initialize / set_model / get_usage / …). Ids are unique per request,
     *  so a late response from a torn-down process can only ever complete its
     *  own stale deferred — never a newer request's. Cleared (completed null)
     *  on teardown so callers never hang on a dead process. */
    private val pendingClientRequests =
        java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<ClaudeControlWire.ControlResponse?>>()

    /** True iff a CLI→client control_request (AskUserQuestion / permission) is live
     *  and blocked on the user. The tail-poll's turn-state detector reads this for
     *  WAITING-FOR-USER — control_requests are intercepted before the parser and
     *  NEVER written to the JSONL, so the mirrored file alone can't see a pending
     *  question. AUTHORITATIVE, no timeout. */
    fun hasPendingControl(): Boolean = pendingControls.isNotEmpty()

    private val reqCounter = AtomicInteger(0)
    // @Volatile: written by whichever coroutine calls ensureProcess() (a new
    // runTurn, possibly on a different IO thread than an OLDER reader that is
    // still winding down), read by that older reader's own coroutine in
    // startReader's finally. Plain-var writes are not guaranteed visible
    // across threads without a memory barrier.
    @Volatile private var turnSeq = 0

    /**
     * Execute one turn over the persistent channel.
     * @return false ONLY on a launch-level failure with the prompt
     *  undelivered and [broken] set — the caller should re-run the text
     *  through the one-shot path. Every other outcome (success, turn
     *  error, transport death mid-turn) returns true having already done
     *  the same state/history bookkeeping the one-shot runner does.
     */
    suspend fun runTurn(text: String): Boolean = withContext(Dispatchers.IO) {
        val client = sshLifecycle.liveClient()
        if (client == null || !client.isConnected) {
            android.util.Log.w(tag, "runTurn ABORT: transport down (client=${client != null})")
            onPromptUndelivered(text)
            onStateChange(SessionState.Failed("disconnected"))
            return@withContext true
        }
        onStateChange(SessionState.Working)
        // Re-arm the cancel flag at turn start. An idle Stop (no turn running)
        // sets it and nothing else clears it — the stale `true` then made THIS
        // turn treat a mid-turn process death as "user cancelled": no error, no
        // redelivery, zombie chat. The flag means "the user cancelled THIS
        // turn" — it must not outlive the turn it was aimed at (same rule as
        // the run-one-shot and Codex paths).
        sshLifecycle.userCancelled = false
        armedThisTurn = false
        loopRequestedThisTurn = text.trimStart().startsWith("/loop")
        try {
            backfillCwdIfNeeded()
            if (!ensureProcess()) {
                // Launch failed — hand the prompt back for the one-shot
                // fallback. broken is already set.
                return@withContext false
            }
            val done = CompletableDeferred<Boolean>()
            lastReaderActivityMs = System.currentTimeMillis()
            // Where this turn's output starts, so the silence backstop can tell
            // "produced nothing" from "the reader wedged but the file mirror
            // already delivered the reply".
            val historyAtTurnStart = history.history.value.size
            val line = AgentSpecRegistry[server.agent].encodeUserTurn(text)
            // The live-turn token flips ATOMICALLY with the stdin write, under the
            // same lock the async interrupt writer takes. An interrupt minted for
            // the PREVIOUS turn either wins this lock — and is written while it
            // still owns the channel — or loses it and finds a token it does not
            // own, so it is dropped instead of aborting this turn. Monitors are
            // reentrant, so writeLine's own `synchronized(writeLock)` is fine.
            val sent = synchronized(writeLock) {
                turnDone = done
                writeLine(line)
            }
            if (!sent) {
                // stdin write failed → the process died between ensureProcess
                // and the write. WHICH corpse matters: a dead TRANSPORT wants
                // the silent-reconnect path, but a CLI that died at birth on a
                // HEALTHY link (expired login, bad flag) explains itself on
                // stderr — and calling that "disconnected" looped silent
                // reconnects forever while the chat spun with zero tokens and
                // the user never learned the one thing that would fix it
                // (the dev server 2026-08-17: "Not logged in · Please run /login").
                teardownProcess()
                val why = cliFailureDiagnosis()
                if (sshLifecycle.liveClient()?.isConnected == true && why != null) {
                    android.util.Log.w(tag, "runTurn: CLI died at launch on a live transport — surfacing: $why")
                    history.emitMsg(
                        AgentMessage.Error(
                            UUID.randomUUID().toString(),
                            "${server.agent.cliCommand}: $why",
                        )
                    )
                    onStateChange(SessionState.Running)
                    return@withContext true
                }
                android.util.Log.w(tag, "runTurn: stdin write failed — marking disconnected")
                onPromptUndelivered(text)
                onStateChange(SessionState.Failed("disconnected"))
                return@withContext true
            }
            android.util.Log.d(tag, "turn sent (${text.length}B) resume=${getResumeId()}")
            // SEND-ACK WATCHDOG. A half-open transport (Wi-Fi blinked, TCP
            // never noticed) swallows the stdin write SILENTLY: the CLI never
            // sees the prompt, no stream event ever comes, and the chat sits
            // on a spinner forever while the user re-reads their own message.
            // The keepalive kills such a transport in ~90 s, but nothing
            // REDELIVERED the prompt. So: if the reader hears NOTHING after
            // the send for SEND_ACK_TIMEOUT_MS and the file mirror hasn't
            // delivered output either, probe the transport with a cheap
            // channel exec; dead (or hung — same thing) → tear down and
            // complete(false), which routes into the existing undelivered →
            // silent-reconnect → redeliver pipeline. A merely SLOW CLI passes
            // the probe and keeps its time.
            val sentAtMs = System.currentTimeMillis()
            val ackWatch = scope.launch {
                kotlinx.coroutines.delay(SEND_ACK_TIMEOUT_MS)
                if (done.isCompleted) return@launch
                if (lastReaderActivityMs >= sentAtMs) return@launch
                if (history.hasAssistantOutputSince(historyAtTurnStart)) return@launch
                val alive = withTimeoutOrNull(8_000) {
                    kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
                        transportAnswers()
                    }
                } ?: false
                if (alive) {
                    android.util.Log.d(tag, "send unacked but transport answers — CLI is just slow, waiting on")
                    return@launch
                }
                android.util.Log.w(
                    tag,
                    "send unacked ${SEND_ACK_TIMEOUT_MS / 1000}s + transport dead — tearing down for redelivery",
                )
                teardownProcess()
                done.complete(false)
            }
            // INACTIVITY wait, NOT a wall-clock deadline. A research turn
            // (Agent/Task/Workflow subagents) legitimately runs for tens of
            // minutes with the MAIN stream quiet; the old fixed 15-min cap fired
            // mid-research, interrupted Claude, errored the turn, and — with no
            // teardown — left an orphaned reader that poisoned every later turn.
            // Now we keep waiting as long as the reader keeps seeing stdout;
            // only TRUE silence (a wedged process — a dead transport already
            // EOFs the reader within a couple of 30s keepalives) trips the
            // backstop.
            var completed: Boolean? = null
            while (true) {
                completed = withTimeoutOrNull(INACTIVITY_CHECK_MS) { done.await() }
                if (completed != null) break
                if (System.currentTimeMillis() - lastReaderActivityMs >= INACTIVITY_TIMEOUT_MS) break
            }
            ackWatch.cancel()
            if (completed == null) {
                android.util.Log.w(tag, "turn silent ${INACTIVITY_TIMEOUT_MS / 60000} min — interrupting + tearing down")
                interrupt()
                // Only call it a FAILURE if nothing came back. When the reader
                // wedges but the answer still reached the chat — the tail-poll
                // mirror paints it from the session file, which is exactly what
                // happens on a conch-bridge loopback turn — a red "timed out"
                // row underneath a complete, correct reply is simply a lie. The
                // wedge still needs the teardown below; it does not need an
                // error the user has to reason about (2026-07-29).
                if (!history.hasAssistantOutputSince(historyAtTurnStart)) {
                    history.emitMsg(
                        AgentMessage.Error(
                            UUID.randomUUID().toString(),
                            "${server.agent.cliCommand} turn timed out (no output)",
                        )
                    )
                } else {
                    android.util.Log.i(tag, "wedged reader, but the reply landed via the file mirror — no error row")
                }
                // Force a clean process + reader restart on the next send so the
                // wedged turn's reader can never satisfy (and swallow) a later
                // turn's completion — that was the "new messages get no reply"
                // half of the bug. ensureProcess() will relaunch with --resume.
                teardownProcess()
            } else if (!completed && !sshLifecycle.userCancelled) {
                // Reader hit EOF mid-turn — the process/transport died without a
                // result. Whether the PROMPT is lost is a different question
                // from whether the ANSWER is, and answering it wrong costs the
                // user real money.
                //
                // If the reader heard ANYTHING after the write — a stream event,
                // an assistant token, anything — the CLI took the prompt: it is
                // in its rollout, and `--resume` brings it back. What died is the
                // ANSWER. Handing the prompt back here re-ran the whole turn on
                // every reconnect, and on a flapping radio that is a loop:
                // connect → re-send → die → connect → re-send, each iteration a
                // full turn on the session's whole context.
                //
                // Only genuine silence keeps the redelivery: nothing came back at
                // all, so the write may well have died in a local buffer — that
                // is the half-open-transport case the send-ack watchdog exists
                // for, and dropping it silently is the older bug. CLI died on a
                // LIVE transport with an explanation on stderr → that explanation
                // IS the answer to this turn. Not a disconnect (nothing to
                // reconnect), not a redelivery (the CLI will just die again until
                // the user fixes what stderr names).
                val whyDead = cliFailureDiagnosis()
                if (sshLifecycle.liveClient()?.isConnected == true && whyDead != null &&
                    !history.hasAssistantOutputSince(historyAtTurnStart)
                ) {
                    android.util.Log.w(tag, "process died mid-turn on a live transport — surfacing: $whyDead")
                    history.emitMsg(
                        AgentMessage.Error(
                            UUID.randomUUID().toString(),
                            "${server.agent.cliCommand}: $whyDead",
                        )
                    )
                    onStateChange(SessionState.Running)
                    return@withContext true
                }
                val cliTookIt = lastReaderActivityMs >= sentAtMs ||
                    history.hasAssistantOutputSince(historyAtTurnStart)
                if (cliTookIt) {
                    android.util.Log.w(
                        tag,
                        "process died mid-turn, but the CLI had already taken the prompt — " +
                            "reconnect only, NOT re-sending (the answer is lost, the turn is not)",
                    )
                } else {
                    android.util.Log.w(tag, "process died mid-turn with no ack — prompt never landed, handing it back")
                    onPromptUndelivered(text)
                }
                onStateChange(SessionState.Failed("disconnected"))
                return@withContext true
            }
            true
        } finally {
            // A `/loop …` that scheduled nothing is not a loop. The model runs
            // the task first and arms the next run as the LAST action of the
            // turn — when it simply doesn't, the chat looks identical to a
            // healthy loop and the user walks away believing work will carry on
            // through the night.
            if (loopRequestedThisTurn && !armedThisTurn) onLoopNotArmed()
            loopRequestedThisTurn = false
            turnDone = null
            sshLifecycle.userCancelled = false
            onThinkingTokens(null) // turn over → drop the live thinking row
            history.flushStreamingBuffer()
            if (getState() == SessionState.Working) onStateChange(SessionState.Running)
        }
    }

    /** Same cwd backfill the one-shot runner performs — a resumed chat
     *  must `cd` into the directory its session was created in. */
    private suspend fun backfillCwdIfNeeded() {
        val rid = getResumeId() ?: return
        if (cwdSnapshot() != null) return
        val spec = AgentSpecRegistry[server.agent]
        val script = spec.cwdBackfillScript(rid) ?: return
        val raw = sshLifecycle.execOnLive("bash -lc " + shellEscape(script))
        val cwd = raw?.let {
            Regex("\"cwd\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.getOrNull(1)
        }
        if (!cwd.isNullOrBlank()) {
            history.emitMsg(
                AgentMessage.System(
                    id = UUID.randomUUID().toString(),
                    subtype = "cwd_backfill",
                    cwd = cwd,
                    sessionId = rid,
                    raw = "{\"backfilled\":true,\"cwd\":\"$cwd\"}",
                )
            )
        }
    }

    /** Start (or reuse) the persistent process. Restarts when launch
     *  params changed (model/effort/approval pick mid-chat). */
    private fun ensureProcess(): Boolean {
        val params = LaunchParams(
            model = getModelOverride()?.takeIf { it.isNotBlank() },
            reasoning = getReasoningOverride()?.takeIf { it.isNotBlank() },
            approval = getApprovalMode(),
            authPrep = getAuthPrep(),
            cwd = cwdSnapshot(),
        )
        if (procAlive && launched == params) return true
        if (procAlive) {
            // ⚠ A RESTART MUST NEVER START A NEW SESSION. Relaunching without
            // `--resume` makes the CLI mint a fresh <uuid>.jsonl, which shows up
            // as a duplicate row carrying the same auto-title (user, 2026-07-27).
            // `claude --resume <id>` appends to the SAME file and reports the
            // SAME id — only --fork-session forks, and we never pass it — so the
            // only way to fork by accident is losing the id. If it's gone, keep
            // the process we have and retry the switch on the next turn.
            if (getResumeId() == null) {
                android.util.Log.w(
                    tag,
                    "launch params changed but resumeId is NULL — keeping the current " +
                        "process rather than starting a second session",
                )
                return true
            }
            android.util.Log.d(tag, "launch params changed → restarting persistent process")
        }
        teardownProcess()

        val client = sshLifecycle.liveClient() ?: return false
        val spec = AgentSpecRegistry[server.agent]
        val inner = spec.buildPersistentCommand(
            ExecInput(
                text = "",
                resumeId = getResumeId(),
                model = params.model,
                approvalMode = params.approval,
                cwdSnapshot = params.cwd,
                reasoningEffort = params.reasoning,
                forkSession = getForkOnce(),
            )
        ) ?: run {
            broken = true
            return false
        }
        val cdPrefix = params.cwd?.takeIf { it.isNotBlank() }
            ?.let { "cd ${shellEscape(it)} && " } ?: ""
        val full = loginShell(params.authPrep + cdPrefix + inner)
        return try {
            // autoExpand channel: this turn stream is read continuously and must
            // survive reader-thread contention from the conch-bridge loopback
            // without its receive-window starving to a silent stall (see
            // [startStreamSession]; TURN-STUCK-RECONCILE-1).
            val sess = client.startStreamSession()
            val cmd = sess.exec(full)
            procSession = sess
            procCmd = cmd
            procAlive = true
            launched = params
            turnSeq++
            synchronized(errTail) { errTail.clear() }
            startReader(cmd)
            startErrDrain(cmd)
            android.util.Log.d(
                tag,
                "persistent process started resume=${getResumeId()} " +
                    "model=${params.model ?: "<default>"} approval=${params.approval} " +
                    "resume=${getResumeId() ?: "<NONE — NEW SESSION>"}",
            )
            // initialize handshake — the Agent SDK ALWAYS sends this first.
            // It registers the session and (critically) declares which
            // dialog kinds we render; some control flows degrade / fail
            // closed without it. The RESPONSE carries the CLI's own model
            // catalog / commands / account — captured async and republished
            // via [onInitState] (the reader routes it by request id).
            val initId = "init-${reqCounter.incrementAndGet()}-${UUID.randomUUID().toString().take(8)}"
            val initDone = CompletableDeferred<ClaudeControlWire.ControlResponse?>()
            pendingClientRequests[initId] = initDone
            scope.launch {
                val resp = withTimeoutOrNull(INIT_RESPONSE_TIMEOUT_MS) { initDone.await() }
                pendingClientRequests.remove(initId)
                val payload = resp?.takeIf { it.ok }?.payload ?: run {
                    if (resp != null) android.util.Log.w(tag, "initialize error: ${resp.error}")
                    return@launch
                }
                SilentlyTry.fired(tag, "publish init state") {
                    val st = ai.eight24family.conch.agent.claude.ClaudeInitState.parse(payload)
                    android.util.Log.d(
                        tag,
                        "initialize: models=${st.models.map { it.value }} " +
                            "commands=${st.commands.size} agents=${st.agents.size} " +
                            "account=${st.account?.subscriptionType ?: "?"}",
                    )
                    onInitState(st)
                }
            }
            writeLine(ClaudeControlWire.encodeInitialize(initId))
            true
        } catch (t: Throwable) {
            android.util.Log.w(tag, "persistent launch failed: ${t.message} — falling back to one-shot", t)
            broken = true
            teardownProcess()
            false
        }
    }

    /** Rolling tail of the last raw stdout lines — dumped on EOF so a
     *  mid-turn death (the "stdin write failed" + EOF loop, 2026-06-12)
     *  is diagnosable from `adb logcat -s SshAi-Persist` without guessing. */
    private val rawTail = ArrayDeque<String>()

    /** Rolling tail of the process's STDERR. A CLI that dies at birth — an
     *  expired login, a bad flag — explains itself HERE, and nothing read it:
     *  the reader saw stdout EOF, the write path said "disconnected", and the
     *  app silently reconnect-looped over a perfectly healthy transport while
     *  the chat spun a thinking indicator with zero tokens (the dev server,
     *  2026-08-17: claude logged out — the LIST preview even showed "Not
     *  logged in · Please run /login" while the open chat showed nothing). */
    private val errTail = ArrayDeque<String>()

    private fun startErrDrain(cmd: Session.Command) {
        scope.launch(Dispatchers.IO) {
            SilentlyTry.fired(tag, "drain stderr") {
                BufferedReader(InputStreamReader(cmd.errorStream, Charsets.UTF_8)).use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        if (line.isBlank()) continue
                        synchronized(errTail) {
                            errTail.addLast(line.take(400))
                            while (errTail.size > 8) errTail.removeFirst()
                        }
                    }
                }
            }
        }
    }

    /** Why the CLI process died, in the CLI's own words — stderr first, then
     *  any non-JSON stdout. Null when there is nothing quotable, which keeps
     *  the caller on the ordinary disconnected path. */
    private fun cliFailureDiagnosis(): String? {
        val err = synchronized(errTail) { errTail.joinToString("\n") }.trim()
        if (err.isNotEmpty()) return err.take(300)
        val raw = synchronized(rawTail) {
            rawTail.filter { !it.trimStart().startsWith("{") }.joinToString("\n")
        }.trim()
        return raw.take(300).takeIf { raw.isNotEmpty() }
    }

    // ── Live local-history cache ──────────────────────────────────────
    // Persist the chat to the on-device HistoryCache AS IT STREAMS, so a
    // brand-new session is readable offline immediately and the
    // background prefetch's `size==0` gate SKIPS re-downloading it from
    // the server on every reconnect.
    @Volatile private var liveCacheRid: String? = null
    @Volatile private var liveCacheActive = false
    private val preIdLineBuffer = ArrayDeque<String>()

    private fun cacheRawLine(line: String) {
        // Skip partial-message deltas — they'd balloon the file; the final
        // full `assistant` event is appended like any other line.
        if (line.contains("\"stream_event\"")) return
        val rid = getResumeId()
        if (liveCacheRid == null && rid != null) {
            liveCacheRid = rid
            liveCacheActive = SilentlyTry.loggedOrElse(tag, "live-cache size gate", false) {
                ai.eight24family.conch.di.ServiceLocator.historyCache.size(rid) == 0L
            }
            if (liveCacheActive) {
                val flush = synchronized(preIdLineBuffer) { preIdLineBuffer.toList().also { preIdLineBuffer.clear() } }
                if (flush.isNotEmpty()) {
                    SilentlyTry.fired(tag, "flush pre-id live cache") {
                        ai.eight24family.conch.di.ServiceLocator.historyCache.append(
                            rid, (flush.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8),
                        )
                    }
                }
            } else {
                synchronized(preIdLineBuffer) { preIdLineBuffer.clear() }
            }
        }
        if (rid == null) {
            // Buffer until the id arrives (system/init), bounded.
            synchronized(preIdLineBuffer) {
                preIdLineBuffer.addLast(line)
                while (preIdLineBuffer.size > 400) preIdLineBuffer.removeFirst()
            }
            return
        }
        if (liveCacheActive) {
            SilentlyTry.fired(tag, "live-cache append line") {
                ai.eight24family.conch.di.ServiceLocator.historyCache.append(
                    rid, (line + "\n").toByteArray(Charsets.UTF_8),
                )
            }
        }
    }

    private fun startReader(cmd: Session.Command) {
        // Both the log tag and the ownership fence for this reader's whole
        // lifetime — see the finally block below and the terminal-line branch.
        val myGen = turnSeq
        val myTag = "p${myGen}_"
        readerJob = scope.launch {
            val spec = AgentSpecRegistry[server.agent]
            try {
                BufferedReader(InputStreamReader(cmd.inputStream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        lastReaderActivityMs = System.currentTimeMillis()
                        if (line.isBlank()) continue
                        synchronized(rawTail) {
                            rawTail.addLast(line.take(1200))
                            while (rawTail.size > 12) rawTail.removeFirst()
                        }
                        // Live reasoning counter — transient UI state, never a
                        // chat row. Gen-gated like turnDone below: an orphaned
                        // reader still draining a torn-down process's buffered
                        // stdout must not paint "thinking" over a NEW turn that
                        // has already answered.
                        if (myGen == turnSeq && line.contains("\"thinking_tokens\"")) {
                            THINKING_TOKENS_RX.find(line)?.groupValues?.get(1)?.toLongOrNull()
                                ?.let { onThinkingTokens(it) }
                        }
                        // Control traffic FIRST — it must never reach the
                        // history parser.
                        if (ClaudeControlWire.isControlLine(line)) {
                            handleControlRequest(line)
                            continue
                        }
                        ClaudeControlWire.parseCancelRequest(line)?.let { cancelled ->
                            retireControl(cancelled)
                            continue
                        }
                        if (line.startsWith("{") && line.contains("\"control_response\"")) {
                            // Response to a request WE sent — route to its awaiter
                            // (set_model ack, get_usage payload, initialize state).
                            // Unmatched ids (e.g. an interrupt ack after its turn
                            // ended) are just logged.
                            val resp = ClaudeControlWire.parseControlResponse(line)
                            val awaiter = resp?.let { pendingClientRequests.remove(it.requestId) }
                            if (awaiter != null) {
                                awaiter.complete(resp)
                            } else {
                                android.util.Log.d(tag, "control ack: ${line.take(160)}")
                            }
                            continue
                        }
                        val parsedMsgs = spec.parseStreamLine(line, myTag)
                        // A `/loop` arming (or ending) itself. Gen-gated like
                        // everything else here: a torn-down process still
                        // draining stdout must not light a countdown over a
                        // process that no longer exists.
                        if (myGen == turnSeq) {
                            for (tu in parsedMsgs.filterIsInstance<AgentMessage.ToolUse>()) {
                                when (tu.toolName) {
                                    // Self-paced: the model names its own delay.
                                    LoopWatch.TOOL -> { armedThisTurn = true; onLoopWakeup(tu.input) }
                                    // Interval: `/loop 30m …` schedules a cron job
                                    // instead, and never touches ScheduleWakeup.
                                    LoopWatch.CRON_TOOL -> { armedThisTurn = true; onLoopCron(tu.input) }
                                    LoopWatch.CRON_STOP_TOOL -> onLoopWakeup(null)
                                }
                            }
                        }
                        // Background-task ledger: the parser upserts one row per
                        // task ("sysevt-task-<id>", label "task · <status> · …").
                        // Track which are alive in THIS process so teardown can
                        // retire them honestly (see retireBackgroundTasks).
                        for (msg in parsedMsgs) {
                            if (msg is AgentMessage.EventNote && msg.id.startsWith("sysevt-task-")) {
                                val taskId = msg.id.removePrefix("sysevt-task-")
                                val terminalStatus = TASK_TERMINAL_RX.containsMatchIn(msg.label)
                                if (terminalStatus) liveBackgroundTasks.remove(taskId)
                                else liveBackgroundTasks.add(taskId)
                            }
                        }
                        for (msg in parsedMsgs) {
                            // Adopt the id the CLI reports on EVERY launch, not just the first.
                            // Adopting once meant that if the CLI ever answered with a
                            // different session_id we kept resuming the OLD one forever
                            // while the CLI wrote a file we never tracked — an orphan
                            // session row (user, 2026-07-27). Tracking whatever file it
                            // actually writes makes that impossible by construction.
                            if (msg is AgentMessage.System && msg.sessionId != null &&
                                msg.sessionId != getResumeId()
                            ) {
                                setResumeId(msg.sessionId)
                            }
                            if (!rendersOnLiveStream(msg)) continue
                            history.emitMsg(msg)
                        }
                        // Persist to the on-device cache as we stream (gated to
                        // brand-new sessions — see cacheRawLine). Runs AFTER the
                        // parse so setResumeId has populated the id this turn.
                        cacheRawLine(line)
                        // TURN-END: ONE authority — what the parser recognised.
                        //
                        // It used to be a second, weaker extraction: a substring
                        // scan for `"type":"` over the raw line, deciding
                        // independently of the parser whether the same line was
                        // terminal. The two disagreed on Claude's `result`
                        // envelope and the app rendered a finished answer with the
                        // spinner still running on top of it, nothing in the log
                        // saying why (2026-07-29). The parser already did the full
                        // parse and already knows; nothing downstream should
                        // re-derive it.
                        val terminal = parsedMsgs.filterIsInstance<AgentMessage.TurnEnd>().firstOrNull()
                        if (terminal != null) {
                            ai.eight24family.conch.util.Logx.d(tag) {
                                "turn-terminal ${terminal.reason}: ${line.take(400)}"
                            }
                            // The turn is OVER, so the CLI cannot still be blocked
                            // on a control it raised inside it. Leaving them here
                            // keeps hasPendingControl() true forever, which pins
                            // "waiting for your answer" and leaves a tappable card
                            // writing into a void (user, 2026-07-29). Retiring only
                            // on EOF/teardown was never enough: a Stop whose
                            // interrupt IS honoured ends the turn with the channel
                            // still alive.
                            pendingControls.keys.toList().forEach { retireControl(it) }
                            // Only if THIS reader's process is still the current one.
                            // A torn-down process's stdout can keep draining buffered
                            // bytes after a newer process has already been launched
                            // (teardownProcess()/readerJob.cancel() cannot interrupt a
                            // blocking readLine()) — completing turnDone here would
                            // resolve the NEW turn's deferred with THIS old line's
                            // verdict.
                            if (myGen == turnSeq) turnDone?.complete(true)
                        } else if (line.contains("\"total_cost_usd\"") || line.contains("\"num_turns\"")) {
                            // Turn-final ACCOUNTING that nothing called terminal.
                            // Only an envelope that ends a turn carries these. Log
                            // the WHOLE line, once: the 200-char cut is what made
                            // this undecidable for a day.
                            android.util.Log.w(
                                tag,
                                "turn-terminal MISSED len=${line.length} line=$line",
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w(tag, "reader died: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                val tail = synchronized(rawTail) { rawTail.joinToString("  ⏎  ") }
                android.util.Log.w(tag, "reader EOF — process gone. last stdout: $tail")
                // ⚠ EVERYTHING in this block only applies if THIS reader still
                // owns the CURRENT process. `readerJob?.cancel()` (teardownProcess)
                // cannot interrupt a thread blocked in `BufferedReader.readLine()`
                // on a live socket — it has to wait for the transport to actually
                // notice the channel is gone, which measured 13s after a Stop-
                // escalation kill on 2026-07-30. If the user has already re-sent in
                // that window, ensureProcess() has ALREADY launched a new process
                // with a new turnSeq and a new turnDone for the NEW turn — an
                // orphaned reader reaching this finally must not touch ANY of that
                // turn's state (user, 2026-07-31, second time: a duplicated prompt
                // appended after an answer already existed, and the thinking
                // indicator stuck on after a real answer had landed — both explained
                // by an old reader's `turnDone?.complete(false)` falsely failing a
                // turn its own real reader was still in the middle of answering).
                if (myGen == turnSeq) {
                    procAlive = false
                    // Any cards still waiting for an answer are dead with the
                    // process — freeze them so the user isn't tapping a void.
                    pendingControls.keys.toList().forEach { retireControl(it) }
                    turnDone?.complete(false)
                } else {
                    android.util.Log.d(tag, "orphaned reader EOF ignored — turnSeq moved on ($myGen -> $turnSeq)")
                }
            }
        }
    }

    private fun handleControlRequest(line: String) {
        val req = ClaudeControlWire.parseControlRequest(line) ?: return
        android.util.Log.d(tag, "control_request ${req.subtype} tool=${req.toolName} id=${req.requestId}")
        when (req.subtype) {
            "can_use_tool" -> {
                pendingControls[req.requestId] = req
                if (req.toolName == "AskUserQuestion") {
                    val questions = req.inputJson
                        ?.let { ClaudeControlWire.parseAskQuestions(it) }
                        .orEmpty()
                    if (questions.isEmpty()) {
                        // Malformed — allow with original input so the turn
                        // doesn't hang on a card we can't render.
                        respondRaw(
                            req.requestId,
                            ClaudeControlWire.encodeAllow(
                                req.requestId,
                                req.inputJson ?: kotlinx.serialization.json.buildJsonObject { },
                            ),
                        )
                        return
                    }
                    history.emitMsg(
                        AgentMessage.AskUserQuestion(
                            id = "ask-${req.requestId}",
                            requestId = req.requestId,
                            questions = questions,
                        )
                    )
                } else {
                    history.emitMsg(
                        AgentMessage.PermissionRequest(
                            id = "perm-${req.requestId}",
                            requestId = req.requestId,
                            toolName = req.toolName.orEmpty(),
                            description = req.toolName.orEmpty(),
                            input = req.inputJson?.toString().orEmpty(),
                            raw = req.raw,
                        )
                    )
                }
            }
            // Dialog kinds we don't render — answer "cancelled" instead of
            // hanging the turn (protocol contract for unrecognized kinds).
            "request_user_dialog", "elicitation" -> respondRaw(
                req.requestId, ClaudeControlWire.encodeDialogCancelled(req.requestId),
            )
            else -> android.util.Log.d(tag, "ignoring control subtype ${req.subtype}")
        }
    }

    /** A pending card's request was cancelled (turn aborted / process
     *  died) — freeze the UI so taps aren't writes into a void. */
    private fun retireControl(requestId: String) {
        val req = pendingControls.remove(requestId) ?: return
        if (req.toolName == "AskUserQuestion") {
            history.resolveQuestion(requestId, emptyMap())
        } else {
            history.resolvePermission(requestId, AgentMessage.PermissionRequest.Resolution.DENIED)
        }
    }

    /**
     * Allow/deny a live `can_use_tool`. Returns true when this stream
     * owned the request (else the caller's legacy path may apply).
     *
     * MUST hop to IO: the card's tap handler arrives on the MAIN thread,
     * and a synchronous SSH-socket write there throws
     * NetworkOnMainThreadException (message=null — the invisible
     * "stdin write failed: null" that broke the transport and looped the
     * question card, 2026-06-12).
     */
    suspend fun respondPermission(requestId: String, decision: PermissionDecision): Boolean =
        withContext(Dispatchers.IO) {
            val req = pendingControls.remove(requestId) ?: return@withContext false
            // Claude's can_use_tool allow is per-call — no session scope — so
            // ALLOW_SESSION behaves like ALLOW_ONCE (the card never offers the
            // session button for Claude anyway, canAllowSession=false).
            val line = if (decision != PermissionDecision.DENY) {
                ClaudeControlWire.encodeAllow(
                    requestId,
                    req.inputJson ?: kotlinx.serialization.json.buildJsonObject { },
                )
            } else {
                ClaudeControlWire.encodeDeny(requestId, ClaudeControlWire.DENY_PERMISSION_REASON)
            }
            respondRaw(requestId, line)
            true
        }

    /** Answer an AskUserQuestion card. IO-hopped — see [respondPermission]. */
    suspend fun respondQuestion(requestId: String, answers: Map<Int, List<String>>): Boolean =
        withContext(Dispatchers.IO) {
            val req = pendingControls.remove(requestId) ?: return@withContext false
            val input = req.inputJson ?: return@withContext false
            respondRaw(requestId, ClaudeControlWire.encodeAskAnswers(requestId, input, answers))
            true
        }

    /**
     * The user typed a NEW message instead of answering a live AskUserQuestion.
     * DENY the pending tool control so the CLI's current turn ends cleanly (the
     * agent treats it as "user declined" and continues) and mark the turn
     * user-cancelled so the wind-down never surfaces as a turn-fail Error — the
     * agent just goes on to answer the new message (user, 2026-06-26). No-op when
     * nothing is pending (e.g. a read-only MIRRORED question has no control to
     * answer — its card is dismissed separately in history).
     */
    suspend fun cancelPendingQuestions(): Unit = withContext(Dispatchers.IO) {
        val ids = pendingControls.entries
            .filter { it.value.toolName == "AskUserQuestion" }
            .map { it.key }
        if (ids.isEmpty()) return@withContext
        sshLifecycle.userCancelled = true
        for (rid in ids) {
            pendingControls.remove(rid)
            respondRaw(rid, ClaudeControlWire.encodeDeny(rid, ClaudeControlWire.DENY_KEPT_GOING_REASON))
        }
    }

    private fun respondRaw(requestId: String, line: String) {
        if (!writeLine(line)) {
            android.util.Log.w(tag, "control response write failed for $requestId")
        }
    }

    /** Real mid-turn interrupt — the CLI aborts the turn and still emits
     *  a result, so the normal turn bookkeeping completes. Launched on
     *  the session's IO scope: callers include the MAIN-thread Stop
     *  button, and socket writes on main throw NetworkOnMainThread. */
    fun interrupt(target: CompletableDeferred<Boolean>? = turnDone) {
        val id = "int-${reqCounter.incrementAndGet()}-${UUID.randomUUID().toString().take(8)}"
        scope.launch {
            synchronized(writeLock) {
                // The wire has no turn addressing: the CLI aborts whatever is in
                // flight when it READS the line. So the only thing that can stop an
                // interrupt from killing the user's NEXT prompt is checking, while
                // holding the write lock, that the turn it was minted for still owns
                // the channel (user, 2026-07-31: stop -> re-send -> the re-send came
                // back as `result:stopped` with 0 output tokens).
                if (turnDone !== target) {
                    android.util.Log.d(tag, "interrupt $id dropped — its turn already ended")
                } else {
                    writeLine(ClaudeControlWire.encodeInterrupt(id))
                }
            }
        }
    }

    /**
     * Cancel a `/loop` that is sleeping between ticks — by ending the process
     * that holds its timers.
     *
     * ⚠ AN INTERRUPT DOES NOT DO THIS. The CLI does drop every pending wakeup
     * on abort, but only on the REPL's own abort path; the stream-json
     * `interrupt` control aborts the in-flight turn and the queued prompts and
     * nothing else (binary 2.1.219). Shipping the interrupt as "stop" gave 0.3.1
     * a stop button that LIED: pressed at 18:48 on the user's own box, the
     * wakeup still fired at 19:18:00 and ran a whole turn. The loop ended there
     * only because the model itself chose to stop that tick.
     *
     * The wakeups are in-process timers, so ending the process is what ends
     * them. It costs the session nothing: nothing is running (that is what
     * "idle" means), and the next send relaunches with `--resume` over the same
     * prefix the prompt cache already covers.
     */
    fun cancelIdleLoop() {
        scope.launch { teardownProcess() }
    }

    /**
     * Stop = interrupt + escalation. Marks the turn user-cancelled (so
     * EOF noise is swallowed), sends the protocol interrupt, and if the
     * turn is STILL running after a grace window, kills the whole
     * process — it restarts with `--resume` on the next send.
     */
    fun cancelTurn(force: Boolean = false) {
        sshLifecycle.userCancelled = true
        // The turn Stop is aimed at — IF one has started writing to the process
        // yet. `turnDone` is assigned inside runTurn only after
        // ensureProcess()/backfillCwdIfNeeded() return, which can legitimately
        // take seconds (spawning `claude --resume` against a large session file
        // is not instant). Stop pressed in that window used to fall through to
        // `val target = turnDone?: return` and do ABSOLUTELY NOTHING — no
        // interrupt, no escalation armed, state stuck on Working for the rest of
        // the turn. That was worse than the bug this fencing replaced: the OLD,
        // pre-fencing code always armed a session-level escalation, so Stop was
        // guaranteed to un-stick the UI within 4s even in this exact window.
        // Restore that guarantee as the fallback, while keeping the turn-fencing
        // for the common case where a turn HAS started (so Stop still can't kill
        // an unrelated later turn — that was yesterday's bug, see the 2026-07-31
        // note two paragraphs up in git history).
        val target = turnDone
        // FORCE with no tracked turn: the app OWNS this process and the file
        // says a turn is running, but our turn tracking lost it (reopened
        // mid-turn — procAlive true, state desynced off Working). Send the
        // interrupt anyway so the CLI aborts on read; the escalation below then
        // tears our own process down if it ignores it.
        if (target != null) interrupt(target) else if (force) interrupt(null)
        scope.launch {
            kotlinx.coroutines.delay(4_000)
            val escalate = when {
                // FORCE the kill on procAlive alone. Stop was routed here BECAUSE
                // we own a live process and the file says it's mid-turn; the normal
                // Working gate would skip (state desynced), Stop would no-op, and
                // the external pgrep kill took over — which, because it never set
                // userCancelled, let the send-ack watchdog read the death as a drop
                // and REDELIVER the prompt: (user, 2026-08-17). userCancelled is
                // set above, so tearing OUR process down is clean and is never
                // redelivered. clearQueue (in cancelCurrent) means no queued prompt
                // can start inside the grace.
                force -> procAlive
                target != null -> shouldEscalateKill(
                    sameTurn = turnDone === target,
                    victimDone = target.isCompleted,
                    working = getState() == SessionState.Working,
                    alive = procAlive,
                )
                // Nothing was captured to fence on. Fall back to the plain
                // session-level check: still Working and the process still up
                // means Stop's target — whatever eventually started — has not
                // finished, so kill it. A turn that legitimately started AND
                // finished inside these 4s is left alone either way, since
                // getState() would already be back to Running by then.
                else -> getState() == SessionState.Working && procAlive
            }
            if (!escalate) {
                android.util.Log.d(tag, "stop escalation skipped — nothing to kill")
                return@launch
            }
            android.util.Log.w(tag, "interrupt not honored in 4s — killing persistent process")
            teardownProcess()
            // The stopped turn, never "whatever is current" — null when Stop was
            // pressed before any turn had a token to complete.
            target?.complete(true)
            if (getState() == SessionState.Working) onStateChange(SessionState.Running)
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
            // Full exception identity — the bare `${t.message}` was null and
            // hid the real cause of the answer-write failure (2026-06-12).
            android.util.Log.w(
                tag,
                "stdin write failed: ${t.javaClass.name}: ${t.message} " +
                    "chanOpen=${procSession?.isOpen} connected=${sshLifecycle.liveClient()?.isConnected} " +
                    "alive=$procAlive",
                t,
            )
            procAlive = false
            false
        }
    }

    // ── Client-initiated control requests over the live channel ──────────
    // (set_model / set_max_thinking_tokens / set_permission_mode /
    //  get_context_usage / get_usage / file_suggestions / rename_session —
    //  binary-verified against the 2.1.219 stdin dispatcher.)

    /** Write one request line and await its control_response. null on a dead
     *  channel / write failure / timeout / teardown. IO-hopped: callers
     *  include MAIN-thread pickers. */
    private suspend fun sendControlRequest(
        encode: (requestId: String) -> String,
        timeoutMs: Long = CONTROL_RESPONSE_TIMEOUT_MS,
    ): ClaudeControlWire.ControlResponse? = withContext(Dispatchers.IO) {
        if (!procAlive) return@withContext null
        val id = "req-${reqCounter.incrementAndGet()}-${UUID.randomUUID().toString().take(8)}"
        val done = CompletableDeferred<ClaudeControlWire.ControlResponse?>()
        pendingClientRequests[id] = done
        if (!writeLine(encode(id))) {
            pendingClientRequests.remove(id)
            return@withContext null
        }
        val resp = withTimeoutOrNull(timeoutMs) { done.await() }
        pendingClientRequests.remove(id)
        resp
    }

    /**
     * LIVE model switch — no process restart, no session re-read, no cache
     * bust beyond the model change itself. On success [launched] is updated
     * so the next turn's LaunchParams comparison does NOT restart the
     * process. Returns false when the channel is down or the CLI refused
     * (unknown/restricted model) — the caller's modelOverride then takes the
     * old restart-with---resume path on the next turn, unchanged.
     */
    suspend fun trySetModel(model: String?): Boolean {
        val resp = sendControlRequest({ id -> ClaudeControlWire.encodeSetModel(id, model) })
        if (resp?.ok != true) {
            if (resp?.error != null) android.util.Log.w(tag, "set_model refused: ${resp.error}")
            return false
        }
        launched = launched?.copy(model = model?.takeIf { it.isNotBlank() })
        android.util.Log.i(tag, "set_model applied LIVE: ${model ?: "<default>"}")
        return true
    }

    /**
     * LIVE thinking-budget switch. Only effort levels with a fixed budget
     * mapping can go over the wire (the CLI's set_max_thinking_tokens takes
     * an integer); levels we pass as `--effort` (xhigh/ultracode/unknown
     * future ones) still need the restart path — return false for those.
     * null = clear back to the CLI's adaptive default.
     */
    suspend fun trySetReasoning(effort: String?, budget: Int?): Boolean {
        // A level we can't express as a budget (xhigh/ultracode) can't be
        // applied live — only cleared or budget-mapped levels can.
        if (effort != null && budget == null) return false
        val resp = sendControlRequest({ id ->
            ClaudeControlWire.encodeSetMaxThinkingTokens(id, budget)
        })
        if (resp?.ok != true) {
            if (resp?.error != null) android.util.Log.w(tag, "set_max_thinking_tokens refused: ${resp.error}")
            return false
        }
        launched = launched?.copy(reasoning = effort?.takeIf { it.isNotBlank() })
        android.util.Log.i(tag, "set_max_thinking_tokens applied LIVE: effort=$effort budget=$budget")
        return true
    }

    /**
     * LIVE permission-mode switch. bypassPermissions may be REFUSED by a
     * session not launched with the bypass flag — false then falls back to
     * the restart path, which relaunches with the right `--permission-mode`.
     */
    suspend fun trySetPermissionMode(
        mode: ai.eight24family.conch.data.prefs.AgentApprovalMode,
    ): Boolean {
        val wire = when (mode) {
            ai.eight24family.conch.data.prefs.AgentApprovalMode.PLAN -> "plan"
            ai.eight24family.conch.data.prefs.AgentApprovalMode.SAFE -> "default"
            ai.eight24family.conch.data.prefs.AgentApprovalMode.AUTO -> "acceptEdits"
            ai.eight24family.conch.data.prefs.AgentApprovalMode.YOLO -> "bypassPermissions"
        }
        val resp = sendControlRequest({ id ->
            ClaudeControlWire.encodeSetPermissionMode(id, wire)
        })
        if (resp?.ok != true) {
            if (resp?.error != null) android.util.Log.w(tag, "set_permission_mode refused: ${resp.error}")
            return false
        }
        launched = launched?.copy(approval = mode)
        android.util.Log.i(tag, "set_permission_mode applied LIVE: $wire")
        return true
    }

    /** `/context` numbers for THIS live session — the same data the CLI's own
     *  /context grid draws. No second process, no session copy. */
    suspend fun getContextUsage(): kotlinx.serialization.json.JsonObject? =
        sendControlRequest(
            { id -> ClaudeControlWire.encodeGetContextUsage(id) },
            timeoutMs = HEAVY_RESPONSE_TIMEOUT_MS,
        )?.takeIf { it.ok }?.payload

    /** Plan-limit windows from the CLI's own cache (rate_limits + session
     *  cost + subscription_type). No curl, no raw token handling. */
    suspend fun getUsage(): kotlinx.serialization.json.JsonObject? =
        sendControlRequest(
            { id -> ClaudeControlWire.encodeGetUsage(id) },
            timeoutMs = HEAVY_RESPONSE_TIMEOUT_MS,
        )?.takeIf { it.ok }?.payload

    /** Server-side fuzzy file search for @-mentions. null = channel down /
     *  refused (callers show nothing rather than a stale list). */
    suspend fun fileSuggestions(query: String): List<String>? {
        val payload = sendControlRequest({ id ->
            ClaudeControlWire.encodeFileSuggestions(id, query)
        })?.takeIf { it.ok }?.payload ?: return null
        return SilentlyTry.logged(tag, "parse file suggestions") {
            (payload["suggestions"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { s ->
                ((s as? kotlinx.serialization.json.JsonObject)
                    ?.get("path") as? kotlinx.serialization.json.JsonPrimitive)?.content
            }
        }
    }

    /** Rename this session's title (persists to the transcript; shows in
     *  `claude --resume`). */
    suspend fun renameSession(title: String): Boolean =
        sendControlRequest({ id ->
            ClaudeControlWire.encodeRenameSession(id, title)
        })?.ok == true

    /** Rewind the conversation to just before [targetMessageUuid]. */
    suspend fun rewindConversation(targetMessageUuid: String): RewindResult {
        val resp = sendControlRequest({ id ->
            ClaudeControlWire.encodeRewindConversation(id, targetMessageUuid, true)
        }, timeoutMs = HEAVY_RESPONSE_TIMEOUT_MS)
            ?: return RewindResult(false, error = "no live session")
        if (!resp.ok) return RewindResult(false, error = resp.error)
        val p = resp.payload
        fun str(k: String) = (p?.get(k) as? kotlinx.serialization.json.JsonPrimitive)
            ?.contentOrNull
        val rewound = (p?.get("rewound") as? kotlinx.serialization.json.JsonPrimitive)
            ?.content == "true"
        if (!rewound) return RewindResult(false, error = str("error") ?: "rewind refused")
        return RewindResult(
            ok = true,
            targetMessageUuid = str("targetMessageUuid") ?: targetMessageUuid,
            prefillText = str("prefillText"),
        )
    }

    /** Restore files to their state before [userMessageId]'s turn.
     *  [dryRun] = report only; nothing on disk is touched. */
    suspend fun rewindFiles(userMessageId: String, dryRun: Boolean): FileRewindResult {
        val resp = sendControlRequest({ id ->
            ClaudeControlWire.encodeRewindFiles(id, userMessageId, dryRun)
        }, timeoutMs = HEAVY_RESPONSE_TIMEOUT_MS)
            ?: return FileRewindResult(false, error = "no live session")
        if (!resp.ok) return FileRewindResult(false, error = resp.error)
        val p = resp.payload
        val can = (p?.get("canRewind") as? kotlinx.serialization.json.JsonPrimitive)
            ?.content == "true"
        val err = (p?.get("error") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        if (!can) return FileRewindResult(false, error = err)
        val files = (p?.get("filesChanged") as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            .orEmpty()
        fun num(k: String) = (p?.get(k) as? kotlinx.serialization.json.JsonPrimitive)
            ?.content?.toIntOrNull() ?: 0
        return FileRewindResult(true, files, num("insertions"), num("deletions"))
    }

    /** The tail-poll found our live turn STUCK: the session FILE shows the turn
     *  ended (a terminal `stop_reason`) and has been frozen past a grace, yet the
     *  state is still Working — the reader wedged (e.g. a `conch-bridge` loopback
     *  tool that re-enters this same app stalled stdout delivery) and never saw
     *  the `result` event, so [turnDone] never fired. Complete the turn as SUCCESS
     *  (the file's terminal stop_reason PROVES it finished — not a timeout guess)
     *  BEFORE tearing the wedged reader down, so runTurn takes the clean
     *  Working→Running path, not the disconnected branch. Tearing down forces the
     *  NEXT turn to relaunch a fresh reader so the wedge can't poison it. No-op if
     *  no turn is in flight. */
    fun reconcileStuckTurn() {
        val done = turnDone ?: return
        android.util.Log.w(tag, "live turn stuck — completing from file's terminal stop_reason + teardown")
        done.complete(true)
        teardownProcess()
    }

    /** Close stdin (graceful CLI exit: flush session file, then quit),
     *  then the channel. Safe to call repeatedly. */
    fun teardownProcess() {
        // Background agents (Agent tool async children) LIVE INSIDE the CLI
        // process — they die with it, silently, and their chat rows kept
        // saying "running · in 0 · out 0" forever. Rewrite each live task row
        // to an honest "interrupted" + say it out loud once.
        retireBackgroundTasks()
        // Pending `/loop` wakeups live in the process, so they die with it.
        // Clearing here (rather than only on an explicit stop) is what keeps
        // the chip from outliving the loop it describes.
        onLoopWakeup(null)
        readerJob?.cancel()
        readerJob = null
        procCmd?.let { cmd ->
            SilentlyTry.fired(tag, "close persistent stdin") { cmd.outputStream.close() }
        }
        procSession?.let { s ->
            SilentlyTry.fired(tag, "close persistent channel") { s.close() }
        }
        procCmd = null
        procSession = null
        procAlive = false
        launched = null
        pendingControls.clear()
        // Callers awaiting a control_response from THIS process must not hang
        // until their timeout — the process is gone, the answer is "no".
        val awaiting = pendingClientRequests.keys.toList()
        for (id in awaiting) pendingClientRequests.remove(id)?.complete(null)
    }

    /** Task ids of background agents the CURRENT process has reported alive
     *  (task_started/progress without a terminal status). They run inside the
     *  CLI process and die with it — tracked so teardown can retire their
     *  rows honestly instead of leaving an eternal "running". */
    private val liveBackgroundTasks = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Rewrite every live background-task row to "interrupted" (same stable
     *  id → in-place upsert over the lying "running") and announce the loss
     *  once. Idempotent: the set is drained on first call. */
    private fun retireBackgroundTasks() {
        val victims = liveBackgroundTasks.toList()
        if (victims.isEmpty()) return
        liveBackgroundTasks.clear()
        for (id in victims) {
            history.emitMsg(
                AgentMessage.EventNote(
                    id = "sysevt-task-$id",
                    label = "task · interrupted — died with the CLI process",
                    detail = "Background agents run inside the CLI process; this one was killed when " +
                        "the process ended (SSH drop / restart). Its transcript survives on the " +
                        "server — ask the agent to resume it.",
                    tone = AgentMessage.EventNote.Tone.WARN,
                )
            )
        }
        history.emitMsg(
            AgentMessage.EventNote(
                id = "sysevt-bgtask-loss-${System.currentTimeMillis()}",
                label = "⚠ ${victims.size} background agent(s) interrupted with the CLI process",
                detail = "Transcripts survive on the server — ask the agent to resume them.",
                tone = AgentMessage.EventNote.Tone.WARN,
            )
        )
    }

    /** Cheap LIVE-channel liveness probe for the send-ack watchdog: open a
     *  fresh channel on the EXISTING transport and run `true`. Never dials a
     *  new connection (that's the reconnect pipeline's job, and a fresh
     *  handshake here would feed fail2ban). A hang counts as dead — the
     *  caller wraps this in a timeout and treats null as false. */
    private fun transportAnswers(): Boolean = runCatching {
        val client = sshLifecycle.liveClient() ?: return false
        if (!client.isConnected) return false
        val sess = client.startSession()
        try {
            val cmd = sess.exec("true")
            cmd.join(5, java.util.concurrent.TimeUnit.SECONDS)
            true
        } finally {
            runCatching { sess.close() }
        }
    }.getOrDefault(false)

    companion object {
        /**
         * What may be RENDERED from the LIVE persistent stream.
         *
         * Extracted pure so it can be tested. The read-only AskUserQuestion is the
         * MIRROR shape the file parser produces (`readOnly`, empty requestId), and
         * on this channel the SAME question always arrives about one line later as
         * a `can_use_tool` control_request — verified against a real CLI run in
         * default, bypassPermissions and allowedTools modes. Rendering the
         * file-shaped copy here is what showed the card twice, once answerable and
         * once "answer this in your CLI session" (user, 2026-07-29). The parser
         * keeps producing it for MIRRORED sessions, which never come through this
         * reader.
         */
        internal fun rendersOnLiveStream(msg: AgentMessage): Boolean =
            msg !is AgentMessage.UserText &&
                msg !is AgentMessage.TurnEnd &&
                !(msg is AgentMessage.AskUserQuestion && msg.readOnly)

        /**
         * May Stop's 4-second escalation kill the CLI process?
         *
         * Pure so the four-way truth table can be tested, because getting it wrong
         * is expensive in exactly one direction: killing the process takes the
         * user's CURRENT prompt down with it and nothing respawns it.
         *
         * [sameTurn] is the whole guard — the stopped turn must still be the live
         * one. [victimDone] catches the honoured interrupt whose `finally` has not
         * run yet, so a Stop that WORKED never escalates. [working] and [alive] are
         * the original session-level conditions, kept because a killed or finished
         * process needs no killing; they are necessary but nowhere near sufficient,
         * which is what the 2026-07-31 incident proved.
         */
        internal fun shouldEscalateKill(
            sameTurn: Boolean,
            victimDone: Boolean,
            working: Boolean,
            alive: Boolean,
        ): Boolean = sameTurn && !victimDone && working && alive

        /** Poll cadence while awaiting a turn's completion (inactivity check). */
        private const val INACTIVITY_CHECK_MS = 60L * 1000

        /** How long a freshly-sent prompt may go with ZERO reader activity and
         * no file-mirror output before the transport is probed — the
         * half-open-socket protection (see the ack watchdog in runTurn). The
         * CLI acks a prompt with stream events within a second or two
         * normally; */
        private const val SEND_ACK_TIMEOUT_MS = 35_000L

        /** A task row label carrying a TERMINAL status — matches the labels
         *  the parser builds. `stopped` + «No completion record» included so a
         *  stopped/orphaned background task retires from the ledger instead of
         *  counting as alive forever. */
        private val TASK_TERMINAL_RX =
            Regex("^task · (completed|failed|killed|interrupted|stopped)\\b|No completion record")
        /** A turn is abandoned only after this much stdout SILENCE (channel
         *  alive but zero output — a wedged process). Deliberately generous:
         *  long research turns must survive, and a truly dead transport surfaces
         *  via reader EOF + the 30s SSH keepalive long before this fires. */
        private const val INACTIVITY_TIMEOUT_MS = 20L * 60 * 1000

        /** Exact-key match: `"estimated_tokens":N` (NOT the
         *  `_delta` sibling, whose key string differs). */
        private val THINKING_TOKENS_RX = Regex("\"estimated_tokens\"\\s*:\\s*(\\d+)")

        /** Ack wait for cheap control requests (set_model & co) — the CLI
         *  answers these inline off its message loop, typically <100 ms. */
        private const val CONTROL_RESPONSE_TIMEOUT_MS = 10_000L
        /** get_context_usage / get_usage do real work (token counting over
         *  the whole conversation; a network fetch on a cold usage cache). */
        private const val HEAVY_RESPONSE_TIMEOUT_MS = 30_000L
        /** initialize response — arrives right after launch; generous for
         *  node cold start on a small VPS. */
        private const val INIT_RESPONSE_TIMEOUT_MS = 45_000L
    }
}
