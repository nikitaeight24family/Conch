package ai.eight24family.conch.agent

import ai.eight24family.conch.ssh.startStreamSession

import ai.eight24family.conch.agent.spec.AgentSpecRegistry
import ai.eight24family.conch.agent.spec.ExecInput
import ai.eight24family.conch.domain.Server
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Per-turn CLI execution. Pulls a prompt off the queue, builds the
 * full SSH-channel command via the per-agent
 * [ai.eight24family.conch.agent.spec.AgentCliSpec], opens an exec
 * channel, parses the JSONL stream, and surfaces every message into
 * [AgentSessionHistory.emitMsg].
 *
 * ─── Invariants ────────────────────────────────────────────────
 *
 * 1. [codexTurnSeq] increments at the START of every [runOneShot]
 *    so the parser-side prefix `t<n>_` makes Codex's per-`exec`
 *    item ids globally unique across turns. Without it turn 2's
 *    `item_1` collides with turn 1's `item_1` and the older
 *    bubble gets overwritten in place.
 *
 * 2. `runOneShot`'s `finally` block calls
 *    [AgentSessionHistory.flushStreamingBuffer] — the FINAL text
 *    chunk has to land in `_history` without the 80 ms batching
 *    delay; otherwise the user sees the second-to-last chunk
 *    until the next emit kicks the timer.
 *
 * 3. `cancelCurrent` flips `userCancelled = true`; the catch
 *    block here checks it before emitting an Error bubble so
 *    sshj's EOF noise on a user-requested cancel doesn't look
 *    like a server crash.
 */
internal class AgentSessionRunOneShot(
    private val server: Server,
    private val scope: CoroutineScope,
    private val sshLifecycle: AgentSessionSshLifecycle,
    private val history: AgentSessionHistory,
    private val onStateChange: (SessionState) -> Unit,
    /** Called with the prompt text when a turn is ABORTED because the SSH
     * transport is dead (null client / not connected / broke mid-exec). The
     * prompt never reached the agent, so the ViewModel re-buffers it to
     * re-deliver after the silent reconnect — otherwise the user's message just
     * sits there with no reply. NOT called for user-cancel or genuine CLI errors
     * (those are terminal). */
    private val onPromptUndelivered: (String) -> Unit = {},
    private val getState: () -> SessionState,
    private val getResumeId: () -> String?,
    private val setResumeId: (String) -> Unit,
    private val cwdSnapshot: () -> String?,
    private val getModelOverride: () -> String?,
    private val getReasoningOverride: () -> String?,
    private val getApprovalMode: () -> ai.eight24family.conch.data.prefs.AgentApprovalMode,
    private val loginShell: (String) -> String,
    /** Shell prefix that forces the session's chosen auth method (see
     *  [ai.eight24family.conch.agent.AuthSelector]). Empty string = no method
     *  chosen = launch unchanged. Defaulted so older call sites are unaffected. */
    private val getAuthPrep: () -> String = { "" },
    /** Live reasoning-token feed — mirrors AgentSessionPersistentStream. */
    private val onThinkingTokens: (Long?) -> Unit = {},
) {
    private val thinkingTokensRx = Regex("\"estimated_tokens\"\\s*:\\s*(\\d+)")

    /** Codex equivalent of Claude's `thinking_tokens`: the
     *  `event_msg/token_count` payload carries cumulative
     *  `total_token_usage.reasoning_output_tokens`. First match on the
     *  line IS the cumulative one (`last_token_usage` repeats the key
     *  later in the same object). */
    private val codexReasoningTokensRx = Regex("\"reasoning_output_tokens\"\\s*:\\s*(\\d+)")
    /**
     * Monotonic per-session turn counter. Bumped at the start of
     * every `runOneShot`. Passed to the spec's parser as a
     * namespace prefix so message ids stay unique across turns —
     * codex specifically resets `item.id` per `codex exec` invocation,
     * which without this counter would let turn 2's `item_1`
     * collide with turn 1's `item_1` and `emitMsg` would overwrite
     * the older bubble in place, scrambling chat order.
     */
    private var codexTurnSeq: Int = 0

    suspend fun runOneShot(text: String) = runOneShotInternal(text, emptySet())

    /** [triedSlots] = credential slots already exhausted in THIS failover
     *  chain. On a rate-limit we silently activate the next OAuth account and
     *  re-issue "continue"; the set stops us looping over the same accounts. */
    private suspend fun runOneShotInternal(text: String, triedSlots: Set<String>): Unit = withContext(Dispatchers.IO) {
        val tag = "SshAi-Turn"
        // Set when a rate-limit failover should re-issue the turn AFTER this
        // one's SSH session closes (see the exit handler + the tail below).
        var failoverContinue: Set<String>? = null
        val t0 = System.currentTimeMillis()
        val client = sshLifecycle.sshClient ?: run {
            // Was a silent return — caller saw the message in history but no
            // working spinner, no error, no output. Now at least logcat tells
            // us the channel was gone before we ever tried to exec.
            android.util.Log.w(tag, "runOneShot ABORT: sshClient is null (state=${getState()})")
            // Silent — Failed("disconnected") drives the silent auto-reconnect
            // (scheduleReconnect → retry → device key). No "tap refresh" message
            // (app auto-fixes; user must not see SSH error lines in chat).
            // Hand the prompt back so the reconnect re-delivers it (don't drop it).
            onPromptUndelivered(text)
            onStateChange(SessionState.Failed("disconnected"))
            return@withContext
        }
        if (!client.isConnected) {
            android.util.Log.w(tag, "runOneShot ABORT: client.isConnected=false (state=${getState()})")
            // Silent — Failed("disconnected") drives the silent auto-reconnect.
            // No visible "SSH connection dropped" line (app auto-fixes silently).
            // Hand the prompt back so the reconnect re-delivers it (don't drop it).
            onPromptUndelivered(text)
            onStateChange(SessionState.Failed("disconnected"))
            return@withContext
        }
        onStateChange(SessionState.Working)
        // If we're resuming an existing session but don't have a cwd snapshot
        // yet (history not seeded with a system event from this app boot),
        // ask the server for the cwd. The script is per-CLI — see
        // [ai.eight24family.conch.agent.spec.AgentCliSpec.cwdBackfillScript].
        // Returns null for CLIs that aren't cwd-locked (Codex resumes by
        // global thread id regardless of cwd).
        val currentResumeId = getResumeId()
        if (cwdSnapshot() == null && currentResumeId != null) {
            val spec = AgentSpecRegistry[server.agent]
            val backfillScript = spec.cwdBackfillScript(currentResumeId)
            if (backfillScript != null) {
                val raw = sshLifecycle.execOnLive("bash -lc " + shellEscape(backfillScript))
                val cwdFromJsonl = raw?.let {
                    Regex("\"cwd\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.getOrNull(1)
                }
                if (!cwdFromJsonl.isNullOrBlank()) {
                    android.util.Log.d(tag, "  cwd backfilled for ${server.agent}: $cwdFromJsonl")
                    history.emitMsg(
                        AgentMessage.System(
                            id = UUID.randomUUID().toString(),
                            subtype = "cwd_backfill",
                            cwd = cwdFromJsonl,
                            sessionId = currentResumeId,
                            raw = "{\"backfilled\":true,\"cwd\":\"$cwdFromJsonl\"}",
                        )
                    )
                } else {
                    android.util.Log.w(tag, "  could not backfill cwd for sid=$currentResumeId agent=${server.agent} (raw=${raw?.take(120)})")
                }
            }
        }
        val cliCmd = buildCommand(server.agent, text)
        android.util.Log.d(tag, "runOneShot exec: agent=${server.agent} cwd=${cwdSnapshot() ?: "(default \$HOME)"} resumeId=${getResumeId()} cmdLen=${cliCmd.length}")
        val activityLogStart = System.currentTimeMillis()

        // startSession() opens a NEW channel; after a network change the transport
        // can be alive yet refuse channels (server CHANNEL_OPEN_FAILURE →
        // OpenFailException "open failed"). It used to be OUTSIDE the try, so that
        // failure escaped UNCAUGHT and left the session stuck at Working forever
        // (stop button, no reply, no spinner — the exact bug). Inside the try now,
        // and a transport failure flips us to Failed → silent auto-reconnect.
        var sess: net.schmizz.sshj.connection.channel.direct.Session? = null
        try {
            // autoExpand: this turn's stdout stream is read continuously; protect
            // it from receive-window starvation if a conch-bridge loopback churns
            // channels on the shared transport mid-turn (see [startStreamSession]).
            sess = client.startStreamSession()
            sshLifecycle.currentSshSession = sess
            val cmd = sess.exec(cliCmd)
            sshLifecycle.currentTurnCommand = cmd

            val spec = AgentSpecRegistry[server.agent]
            // Bump turn counter BEFORE parsing — each runOneShot is
            // exactly one turn. The tag is a short stable string that
            // becomes part of the AssistantText id in CodexMessageParser.
            val turnTag = "t${++codexTurnSeq}_"
            // Per-turn rolling tail of raw stdout (uncapped at first,
            // then trimmed to 8 KB) so the post-exit handler below can
            // pattern-match against what the CLI dumped without re-
            // reading from stdout. Used to detect known-fatal
            // signatures (Gemini Code Assist 500, OOM, etc.) and
            // replace the generic "<cli> exited with code N" with an
            // actionable single-line message.
            val stdoutTail = StringBuilder()
            val outJob = scope.launch {
                BufferedReader(InputStreamReader(cmd.inputStream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        if (line.contains("\"thinking_tokens\"")) {
                            thinkingTokensRx.find(line)?.groupValues?.get(1)?.toLongOrNull()
                                ?.let { onThinkingTokens(it) }
                        } else if (line.contains("\"token_count\"")) {
                            codexReasoningTokensRx.find(line)?.groupValues?.get(1)?.toLongOrNull()
                                ?.takeIf { it > 0 }?.let { onThinkingTokens(it) }
                        }
                        stdoutTail.append(line).append('\n')
                        if (stdoutTail.length > 16_384) {
                            stdoutTail.delete(0, stdoutTail.length - 8_192)
                        }
                        for (msg in spec.parseStreamLine(line, turnTag)) {
                            // Capture the agent's session id for subsequent --resume.
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
                            // Don't double-show user echoes — we already emitted on send().
                            if (msg is AgentMessage.UserText) continue
                            history.emitMsg(msg)
                        }
                    }
                }
            }
            val errJob = scope.launch {
                BufferedReader(InputStreamReader(cmd.errorStream, Charsets.UTF_8)).use { reader ->
                    // **stderr whitelist.** CLIs gleefully dump warnings,
                    // node deprecation notices, npm prefix complaints
                    // ("Your user's .npmrc file has a `globalconfig`…"),
                    // Gemini's stack traces, etc. — none of which the
                    // user can act on. We promote to chat bubbles only
                    // lines that look genuinely fatal; everything else
                    // goes to Log.d so it's still inspectable via
                    // `adb logcat` for debugging but doesn't pollute
                    // the chat thread.
                    //
                    // Per the auto-fix-errors invariant
                    // (feedback_auto_fix_errors.md): no scary noise in
                    // the user's face for things the app can either
                    // recover from or that aren't actionable.
                    val interestingRe = Regex(
                        "(?i)\\b(fatal|panic|EACCES|ENOSPC|ENOMEM|EPERM)\\b"
                    )
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        if (interestingRe.containsMatchIn(line)) {
                            history.emitMsg(AgentMessage.Raw(UUID.randomUUID().toString(), "stderr: $line"))
                        } else {
                            android.util.Log.d(tag, "stderr (suppressed): $line")
                        }
                    }
                }
            }
            cmd.join(15, TimeUnit.MINUTES)
            outJob.cancel()
            errJob.cancel()
            val exit = cmd.exitStatus
            android.util.Log.d(
                tag,
                "runOneShot done exit=$exit elapsed=${System.currentTimeMillis() - t0}ms " +
                    "sshConnected=${sshLifecycle.sshClient?.isConnected}"
            )
            ai.eight24family.conch.data.ServerActivityLog.append(
                server.id,
                ai.eight24family.conch.data.ServerActivityLog.Entry(
                    ts = activityLogStart,
                    category = "run",
                    command = cliCmd.take(600),
                    exitCode = exit ?: -1,
                    stdoutTail = stdoutTail.takeLast(200).toString(),
                    durationMs = System.currentTimeMillis() - activityLogStart,
                ),
            )
            if (exit != null && exit != 0 && !sshLifecycle.userCancelled) {
                val tail = stdoutTail.toString()
                // Rate-limit AUTO-FAILOVER: if this account hit a usage/rate
                // limit AND another OAuth account is configured, silently
                // switch to it and re-issue "continue" — the user never sees
                // the limit error (feedback_auto_fix_errors). Only when no
                // other account is left do we surface it.
                if (looksLikeRateLimit(tail)) {
                    val active = ai.eight24family.conch.di.ServiceLocator.authMethodStore
                        .activeSlot(server.id, server.agent)
                    val nextTried = triedSlots + listOfNotNull(active)
                    val switched = switchToNextOAuthSlot(nextTried)
                    if (switched != null) {
                        android.util.Log.i(tag, "rate limit on ${active ?: "?"} → auto-switched to $switched, re-issuing 'continue'")
                        failoverContinue = nextTried
                    }
                }
                if (failoverContinue == null) {
                    // **Smart replacement** — known-fatal patterns in the
                    // tail get a human-readable hint instead of the generic
                    // "<cli> exited with code N". Per feedback_auto_fix_errors:
                    // never show the raw error if we can do better.
                    val replacement = humanizeExitFailure(server.agent, exit, tail)
                    history.emitMsg(AgentMessage.Error(UUID.randomUUID().toString(), replacement))
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(tag, "runOneShot threw: ${e.javaClass.simpleName}: ${e.message}")
            // Transport died / can't open a channel (e.g. after Wi-Fi⇄cellular):
            // sshj throws OpenFailException / TransportException / ConnectionException
            // (all SSHException) or an EOF. Don't show a scary error AND don't leave
            // it stuck at Working — go Failed("disconnected"), which drives the
            // silent auto-reconnect (scheduleReconnect → retry → device key).
            val transportBroken = e is net.schmizz.sshj.common.SSHException ||
                e.message?.contains("open failed", ignoreCase = true) == true ||
                e.message?.contains("EOF", ignoreCase = true) == true ||
                e.message?.contains("Broken transport", ignoreCase = true) == true
            when {
                sshLifecycle.userCancelled ->
                    android.util.Log.d(tag, "  swallowed because userCancelled=true")
                transportBroken -> {
                    android.util.Log.d(tag, "  transport broken → Failed(disconnected) for silent reconnect")
                    // Turn never completed (startSession/exec/join threw) → the
                    // prompt got no reply. Re-deliver it after the reconnect.
                    onPromptUndelivered(text)
                    onStateChange(SessionState.Failed("disconnected"))
                }
                else -> history.emitMsg(AgentMessage.Error(
                    UUID.randomUUID().toString(),
                    ai.eight24family.conch.util.ErrorMessages.humanize(e, context = "send")
                ))
            }
        } finally {
            sess?.let { s -> SilentlyTry.fired("SshAi-AgentSession", "close turn ssh session") { s.close() } }
            sshLifecycle.currentSshSession = null
            sshLifecycle.currentTurnCommand = null
            sshLifecycle.userCancelled = false
            onThinkingTokens(null) // turn over → drop the live thinking row
            // Drain any final buffered streaming updates so the user
            // sees the complete reply the moment the turn is done —
            // without this they'd briefly see the second-to-last
            // chunk until the 80ms flush window elapses.
            history.flushStreamingBuffer()
            if (getState() == SessionState.Working) onStateChange(SessionState.Running)
        }
        // Session closed — re-issue under the freshly-activated account. Done
        // outside try/finally so we never recurse with the old session still
        // open. "continue" nudges the agent to resume the cut-off turn.
        val fc = failoverContinue
        if (fc != null) runOneShotInternal("continue", fc)
    }

    /** Known usage/rate-limit signatures across agents (researched 2026-05):
     *  Codex (`usage_limit_reached`, `HTTP 429`, `exceeded retry limit, last
     *  status: 429`), Claude (`5-hour limit reached`, `usage limit`, `rate
     *  limit`), Gemini (`RESOURCE_EXHAUSTED`, `quota`). Matched on the turn's
     *  failure output, so an assistant merely *mentioning* "rate limit" in a
     *  successful reply won't trip it (we only check the exit!=0 tail). */
    private fun looksLikeRateLimit(tail: String): Boolean {
        val t = tail.lowercase()
        return "usage_limit_reached" in t ||
            "usage limit" in t ||
            "5-hour limit" in t ||
            "rate limit" in t ||
            "rate_limit" in t ||
            "rate limited" in t ||
            "resource_exhausted" in t ||
            "quota" in t ||
            "exceeded retry limit" in t ||
            "too many requests" in t ||
            Regex("\\b429\\b").containsMatchIn(t)
    }

    /** Activate the next OAuth account slot not already tried, via the live
     *  pooled SSH. Returns the new slot id, or null if there's no other
     *  account to fall back to (then the caller surfaces the limit). */
    private suspend fun switchToNextOAuthSlot(tried: Set<String>): String? {
        val client = sshLifecycle.sshClient ?: return null
        val exec: suspend (String) -> String? = { cmd ->
            SilentlyTry.logged("SshAi-Turn", "failover vault exec") {
                val s = client.startSession()
                try {
                    val p = s.exec(cmd)
                    val o = java.io.ByteArrayOutputStream()
                    p.inputStream.copyTo(o)
                    p.join(20, TimeUnit.SECONDS)
                    String(o.toByteArray(), Charsets.UTF_8)
                } finally { SilentlyTry.fired("SshAi-Turn", "close failover session") { s.close() } }
            }
        }
        val vault = CredentialVault(server.agent, exec)
        val slots = vault.listSlots().orEmpty().filter { CredentialVault.isSlottable(it.method) }
        if (slots.size < 2) return null
        val candidate = slots.firstOrNull { it.id !in tried } ?: return null
        if (!vault.activate(candidate.id)) return null
        ai.eight24family.conch.di.ServiceLocator.authMethodStore
            .setActiveSlot(server.id, server.agent, candidate.id)
        return candidate.id
    }

    /**
     * Build the full SSH-channel command line for `runOneShot`. Delegates
     * to the per-agent [ai.eight24family.conch.agent.spec.AgentCliSpec]
     * to compose the inner CLI invocation (flags, resume mechanics,
     * stream-format). This file is intentionally ignorant of per-CLI
     * details so adding a new agent never requires changes here.
     *
     * The wrapping concerns we DO handle:
     *   - prefixing `cd <cwdSnapshot> && ` so the CLI runs in the project
     *     directory the session was created in (each CLI binds sessions
     *     to cwd in some way — see ClaudeSpec/GeminiSpec docstrings);
     *   - wrapping in `bash -lc` so login-shell PATH (nvm, asdf, …) is
     *     sourced and the CLI binary is actually found.
     */
    private fun buildCommand(agent: Agent, text: String): String {
        val spec = AgentSpecRegistry[agent]
        val cdPrefix = cwdSnapshot()?.takeIf { it.isNotBlank() }
            ?.let { "cd ${shellEscape(it)} && " }
            ?: ""
        val effectiveModel = getModelOverride()?.takeIf { it.isNotBlank() }
        val effectiveReasoning = getReasoningOverride()?.takeIf { it.isNotBlank() }
        // Explicit log so the user can verify in `adb logcat` that the
        // model and reasoning flags they picked in the topbar actually
        // land on the outgoing command. Codex hides its model identity
        // behind a stock "You are Codex" system prompt, so chat replies
        // look identical across models — without this line there's no
        // way to confirm the swap from the app side either.
        android.util.Log.d(
            "SshAi-Turn",
            "buildCommand agent=$agent resume=${getResumeId()} model=${effectiveModel ?: "<cli-default>"} reasoning=${effectiveReasoning ?: "<cli-default>"}",
        )
        val inner = spec.buildExecCommand(
            ExecInput(
                text = text,
                resumeId = getResumeId(),
                model = effectiveModel,
                approvalMode = getApprovalMode(),
                cwdSnapshot = cwdSnapshot(),
                reasoningEffort = effectiveReasoning,
            )
        )
        // Auth-method selector (export/unset) goes FIRST inside the login
        // shell, before the cd + CLI — forces the session's chosen method.
        // Empty when no method is chosen → command is byte-identical to before.
        return loginShell(getAuthPrep() + cdPrefix + inner)
    }

    /**
     * Find any process whose argv contains our `resumeId` AND mentions
     * one of `claude` / `codex` / `gemini` (so we don't INT a random
     * bash wrapper that happens to mention the id). Send SIGINT for a
     * graceful shutdown, then SIGTERM after 800 ms if it's still alive.
     */
    suspend fun killZombieRemoteTurn() {
        val sid = getResumeId() ?: return
        val q = shellEscape(sid)
        val pidScript = "pgrep -af $q 2>/dev/null | " +
            "awk '\$2 != \"bash\" && \$2 != \"sh\" && /(claude|codex|gemini)/ {print \$1}'"
        val pids = sshLifecycle.execOnLive("bash -lc " + shellEscape(pidScript))
            ?.lineSequence()
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?.toList()
            .orEmpty()
        if (pids.isEmpty()) return
        android.util.Log.d("SshAi-Turn", "killZombieRemoteTurn pids=$pids")
        // Polite SIGINT first.
        sshLifecycle.execOnLive("kill -INT ${pids.joinToString(" ")}")
        delay(800)
        // Force kill anything still alive.
        sshLifecycle.execOnLive("kill -TERM ${pids.joinToString(" ")} 2>/dev/null || true")
    }

    /**
     * Map a non-zero exit code + the collected stdout tail into a
     * one-line, actionable message instead of the noisy default
     * "<cli> exited with code N".
     *
     * Patterns currently recognised:
     *
     *  - **Gemini free-tier backend 500** (the `cloudcode-pa.
     *    googleapis.com` backend not provisioning for personal
     *    Google accounts — see GitHub issue #25167, marked as
     *    "not planned" by Google). The free-tier OAuth path
     *    fundamentally doesn't work for headless SSH, so the only
     *    fix from our side is to tell the user to switch to API
     *    key or a Google AI Pro subscription.
     *
     *  - **Claude / Codex** — generic for now; fall through to the
     *    default "exited with code N" until we accumulate real
     *    patterns.
     */
    private fun humanizeExitFailure(
        agent: Agent,
        exit: Int,
        stdoutTail: String,
    ): String {
        if (agent == Agent.GEMINI) {
            // CLASSIFY by signature — the old code dumped EVERY failure into one
            // OAuth-centric message, which lied for API-key setups ("OAuth login
            // missing" when the user is on an API key and the real fault is an
            // API/backend error). Check the SPECIFIC cause first, fall to generic.
            val t = stdoutTail
            fun has(vararg s: String) = s.any { it in t }
            return when {
                // Invalid / expired API key.
                has("API key not valid", "API_KEY_INVALID", "API key expired",
                    "API_KEY_SERVICE_BLOCKED") ->
                    "Gemini rejected the API key — it's invalid, expired, or restricted. " +
                    "Set a fresh key (GEMINI_API_KEY) from aistudio.google.com/apikey, " +
                    "or switch the auth method."
                // Quota / rate limit.
                has("RESOURCE_EXHAUSTED", "Quota exceeded", "quota", "429", "rate limit") ->
                    "Gemini hit a quota / rate limit — wait a bit, or raise quota at " +
                    "aistudio.google.com (the free tier is capped per minute & per day)."
                // OAuth / Code-Assist provisioning (NOT the API-key path).
                has("UNAUTHENTICATED", "invalid_grant", "Reauthentication", "Login Required",
                    "no valid credential", "not authenticated", "Could not load the default credentials",
                    "Please visit the following URL", "Enter the authorization code",
                    "We can't connect to Gemini Code Assist", "cloudcode-pa",
                    "Manual authorization is required", "non-interactive",
                    "run in an interactive terminal") ->
                    "Gemini's OAuth login on this server is missing, expired, or not provisioned " +
                    "for Code Assist. Tap [ log in ] to re-authorize, or switch to [ API key ] " +
                    "(aistudio.google.com/apikey) — an API key always works on a remote server."
                // Generic API / backend failure — the case the user hit on an
                // API-key server. Honest: it's an API call failure, not an auth
                // shape we can name; point at the report gemini already printed.
                has("Error when talking to Gemini API", "Error generating content",
                    "PERMISSION_DENIED", "backendError", "status: 500", "INTERNAL",
                    "503", "UNAVAILABLE", "500") ->
                    "Gemini's API call failed — see the full report gemini printed above for the " +
                    "exact status. Usual culprits: an invalid/expired API key, exhausted quota, " +
                    "the chosen model not available for your key, or a transient backend blip " +
                    "(resending may clear a blip)."
                else -> "${agent.cliCommand} exited with code $exit"
            }
        }
        if (agent == Agent.CLAUDE && looksLikeRateLimit(stdoutTail)) {
            // Preserve the CLI's OWN reset ("resets 8:30pm") in the message — it's
            // honest (not a bare "exited with code 1") AND the usage-bar watcher
            // reads the reset back out of this text (see RateLimitReset). Falls to
            // a plain limit line when the CLI didn't print a parseable time.
            val phrase = ai.eight24family.conch.agent.RateLimitReset.resetPhrase(stdoutTail)
            return "Usage limit reached" + (phrase?.let { " — $it" } ?: "") +
                ". Switch model or account, or wait."
        }
        // Default — generic exit message.
        return "${agent.cliCommand} exited with code $exit"
    }
}
