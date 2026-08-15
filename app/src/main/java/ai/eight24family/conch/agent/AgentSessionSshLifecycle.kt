package ai.eight24family.conch.agent

import ai.eight24family.conch.domain.Server
import ai.eight24family.conch.domain.ServerSecrets
import ai.eight24family.conch.ssh.SshClient
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.direct.Signal
import java.util.concurrent.TimeUnit

/**
 * Owns the live SSH client + the in-flight exec channel, and the
 * stop/cancel signal ladder.
 *
 * ─── Invariants ─────────────────────────────────────────────
 *
 * 1. `sshClient` is acquired via the shared `SshConnectionPool` —
 *    never `new SSHClient()`. Releases match acquires 1:1.
 *
 * 2. [cancelCurrent] uses the **signal ladder** (INT → 2 s →
 *    TERM → 2 s → KILL). It **MUST NOT** force-close
 *    [currentSshSession] mid-flight — that races sshj's
 *    blocking `cmd.join` with channel teardown and on some
 *    sshd builds the resulting CHANNEL_CLOSE storm tripped
 *    the transport itself into `ConnectionException: Broken
 *    transport; encountered EOF`. The pool then marked the
 *    client dead and the user got kicked back to the servers
 *    list. Stay in-channel.
 *
 * 3. [isAlive] checks `scope.isActive` AND `client.isConnected`.
 *    State value is intentionally NOT checked — auto-reconnect
 *    flips state to Bootstrapping briefly and we don't want a
 *    zombie-reaper to interpret that as "dead".
 */
internal class AgentSessionSshLifecycle(
    private val server: Server,
    private val secrets: ServerSecrets,
    private val ssh: SshClient,
    private val scope: CoroutineScope,
) {
    /** ⚠ PRIVATE ON PURPOSE — read the transport through [liveClient] only.
     *  This field is the captured client and goes stale whenever the pool
     *  rebuilds the transport; every consumer that read it kept talking to a
     *  corpse. Making it private is what stops that from coming back. */
    @Volatile private var sshClient: SSHClient? = null

    /**
     * Live SSH `Session` (channel) that the in-flight `runOneShot` is
     * blocking on. Held here so [cancelCurrent] can close it
     * out-of-band — `currentMessageJob.cancel()` only flips the coroutine's
     * cancellation flag, but `cmd.join(15 min)` is sshj's blocking
     * `Channel.waitForChannelExit` which is NOT coroutine-aware. Closing
     * the SSH session sends EOF/SIGHUP to the remote `claude --print`,
     * the channel returns, the coroutine unblocks and runs its `finally`.
     */
    @Volatile var currentSshSession: Session? = null

    /**
     * Live exec command of the in-flight turn. Held so [cancelCurrent]
     * can `signal(INT)` it — actually kills the remote `claude --print`
     * (or `codex` / `gemini`) process instead of just closing our local
     * stream and letting the agent merrily keep working on the server.
     */
    @Volatile var currentTurnCommand: Session.Command? = null

    /**
     * Sticky flag — set true by [cancelCurrent], reset at the top of every
     * `runOneShot`. Used in the catch block to swallow the EOFException /
     * "Premature EOF" / "channel closed" noise that sshj throws when WE
     * deliberately closed the channel out from under cmd.join(). Without
     * this the user taps Stop and the next thing they see in chat is
     * "Premature EOF" or "channel #0 EOF" looking like a server crash.
     */
    @Volatile var userCancelled = false

    /** Epoch ms of the last FAILED fresh-handshake fallback in [execOnLive];
     *  0 when the last one succeeded. Rate-limits the fallback to one failed
     *  handshake per minute — see the comment at the call site. */
    @Volatile private var lastFallbackFailMs = 0L

    /**
     * Set by the chat-open path right BEFORE we first call
     * [openSshClient] for an SK-authenticated server. Holds the
     * already-discovered USB / NFC token so the `publickey` exchange
     * can produce a signature without prompting the user again here.
     *
     * Stays non-null across a session — every reconnect within the
     * same `AgentSession` calls `signer.sign(...)` synchronously, and
     * the user sees the touch indicator on whatever transport they
     * chose. If the token has been physically removed by the time a
     * reconnect fires, `sign()` throws and we surface the error.
     */
    @Volatile var skSigner: ai.eight24family.conch.ssh.securitykey.SkSigner? = null

    /**
     * True iff the SSH transport is genuinely connected AND the coroutine
     * scope is still active. State is intentionally NOT checked — during a
     * legitimate auto-reconnect the session goes Bootstrapping for a few
     * seconds, and an earlier version of `isAlive()` keyed on
     * `state.value is Running/Working` would mark such a session "dead",
     * `reapDeadSessions()` would `close()` it (which `scope.cancel()`s the
     * coroutine scope), and then a moment later `start()` would push state
     * back to `Running` — leaving us with a zombie session whose StateFlow
     * says "running" but whose `scope.launch { runOneShot(...) }` returns
     * an already-cancelled Job, so taps on send produced absolutely
     * nothing. Add `scope.isActive` to make zombies impossible to mark
     * alive.
     */
    fun isAlive(): Boolean {
        if (!scope.isActive) return false
        // [liveClient], not the captured field: the pool rebuilding the transport
        // under us is a RECOVERY, not a death. Reading the corpse here made the
        // reaper close sessions whose server was connected and working.
        return liveClient()?.isConnected == true
    }

    /**
     * The transport to actually USE right now — our captured client if it is
     * still up, otherwise whatever live client the pool currently holds for this
     * server.
     *
     * ⚠ [sshClient] is captured ONCE in `start()` and never re-bound, while the
     * pool legitimately throws transports away and builds new ones underneath
     * us: `evictPoisoned` on a MaxSessions failure, `acquire` rebuilding a dead
     * cached client, and the service watchdog silently re-connecting every
     * held-but-down server after a network change. Every consumer that read the
     * captured field kept talking to a corpse while the pool held a working
     * connection — that is how a file upload could report "SSH not connected"
     * seconds after the app had silently reconnected.
     *
     * `peek` ONLY — deliberately no [openSshClient] here. That would `acquire`
     * a second reference against the single `release` in [close] and leak the
     * pool's refcount, so this never resurrects a server the user has hung up
     * on; it only notices a transport that already exists.
     *
     * ⚠ THIS IS THE ONLY WAY TO READ THE TRANSPORT. The rule was written for
     * upload/download on 2026-07-30 and applied to those two call sites only —
     * the SEND path kept reading the field, so on a server whose transport had
     * been rebuilt every turn aborted with "transport down", every `execOnLive`
     * fell through to a fresh handshake (impossible without a FIDO tap → null),
     * and the chat looked dead while the server list showed it connected. The
     * user's Home server logged 347 such fallbacks against 0 on the other box
     * (2026-08-04). Nothing may read [sshClient] except this function and the
     * refcount bookkeeping in [openSshClient] / [close].
     *
     * Adopting the pool's client into the field is refcount-NEUTRAL: the pool
     * counts references per serverId, not per client object, and we only adopt
     * when we already hold one (`sshClient != null`), so [close]'s single
     * `release` still balances the single `acquire`. Without adopting, every
     * later `isAlive`/log/read would keep reporting the corpse.
     */
    fun liveClient(): SSHClient? {
        val captured = sshClient
        if (captured != null && captured.isConnected) return captured
        val pooled = ai.eight24family.conch.di.ServiceLocator.sshConnectionPool.peek(server.id)
        if (pooled != null && captured != null && pooled !== captured) {
            sshClient = pooled
            android.util.Log.i(
                "SshAi-AgentSession",
                "transport rebound to the pool's live client for ${server.name} " +
                    "(captured one was ${if (captured.isConnected) "replaced" else "dead"})",
            )
        }
        return pooled
    }

    /**
     * Acquire an authenticated [SSHClient] for this AgentSession via
     * the shared [ai.eight24family.conch.ssh.SshConnectionPool]. First
     * acquirer on a given server pays the auth cost (one NFC touch on
     * SK servers); every subsequent acquirer rides the same connection
     * — sshj happily multiplexes many parallel channels on one transport.
     *
     * **Refcount lifecycle:** every [openSshClient] is matched by
     * exactly one `pool.release(server.id)` in [close]. The last
     * release disconnects the underlying client and removes it from
     * the pool.
     */
    fun openSshClient(): SSHClient {
        val client = ai.eight24family.conch.di.ServiceLocator.sshConnectionPool
            .acquire(server, secrets, skSigner)
        sshClient = client
        return client
    }

    /**
     * Release our reference to the pooled client + cancel the
     * supervisor scope. The pool closes the underlying connection only
     * when refcount hits zero (= no other AgentSessions on this server
     * are alive).
     *
     * CRITICAL: never call `sshClient.disconnect()` directly — that
     * would yank the transport from under sibling sessions sharing
     * the same connection.
     */
    fun close(drainerJob: Job?) {
        drainerJob?.cancel()
        if (sshClient != null) {
            SilentlyTry.fired("SshAi-AgentSession", "release pooled client") {
                ai.eight24family.conch.di.ServiceLocator.sshConnectionPool.release(server.id)
            }
            sshClient = null
        }
        scope.cancel()
    }

    /**
     * Cancel the in-flight turn. Three-step escalation, designed so the
     * remote agent ACTUALLY STOPS WORKING — not just stops streaming us
     * its output:
     *
     *   1. **SIGINT** the remote command via SSH `signal` — same effect
     *      as Ctrl+C in a terminal-attached `claude`. The agent gets a
     *      chance to flush a graceful `result` event and exit. This is
     *      what fixes the "I tapped stop and the agent kept editing
     *      files on my server" bug.
     *   2. After 2 s grace, **SIGTERM**.
     *   3. After another 2 s, **SIGKILL**.
     *
     * **Why no more force-close of the SSH session.** The old code did
     * `signal(INT)` then after 800 ms called `currentSshSession.close()`.
     * Closing the exec channel mid-flight made sshj's blocking `cmd.join`
     * race with the transport's own teardown of the channel — and on some
     * sshd builds the resulting CHANNEL_CLOSE storm tripped the transport
     * itself into `ConnectionException: Broken transport; encountered
     * EOF`. The pool then marked the client dead, the chat watchdog
     * popped the user back to servers list, and the next tap required a
     * fresh SK touch. User complaint verbatim:.
     *
     * New approach: ladder of POSIX signals via sshj's exec-signal —
     * which is in-channel, doesn't touch the transport — escalating
     * from INT → TERM → KILL. Once the remote process exits, its
     * stdout EOFs naturally, `cmd.join` returns, `runOneShot`'s
     * finally closes the session cleanly. Pool transport stays alive.
     */
    fun cancelCurrent(killZombieRemoteTurn: suspend () -> Unit) {
        // Mark this turn as user-cancelled so the catch block in
        // runOneShot doesn't emit the EOFException sshj is about to
        // throw when we yank its channel.
        userCancelled = true

        val cmd = currentTurnCommand
        if (cmd != null) {
            // Local turn we ourselves started.
            SilentlyTry.fired("SshAi-AgentSession", "signal INT to cmd") { cmd.signal(Signal.INT) }
            scope.launch {
                delay(2_000)
                if (cmd.exitStatus == null) {
                    SilentlyTry.fired("SshAi-AgentSession", "signal TERM to cmd") {
                        cmd.signal(Signal.TERM)
                    }
                }
                delay(2_000)
                if (cmd.exitStatus == null) {
                    SilentlyTry.fired("SshAi-AgentSession", "signal KILL to cmd") {
                        cmd.signal(Signal.KILL)
                    }
                }
                // Don't null out currentSshSession / currentTurnCommand
                // here either — runOneShot's finally owns that.
            }
        } else {
            // Zombie turn — the agent process is alive on the server but
            // we don't own its exec channel (typically because the app
            // was force-stopped mid-turn earlier and the user came back
            // to a still-running claude). Find it by `pgrep -f <resumeId>`
            // and `kill -INT` whatever PID matches our agent CLI.
            scope.launch { killZombieRemoteTurn() }
        }
    }

    /**
     * Execute a one-shot command using the already-authenticated SSH client.
     * One channel open + exec + read = ~2× RTT. Falls back to a fresh
     * connection only if the live one is gone.
     *
     * Public so callers (e.g. server-stats probe) can avoid the cost of a
     * brand-new SSH handshake — fresh connect is ~10 RTT and on a 250 ms
     * link that's ~3 s, which utterly skews any latency reading.
     */
    suspend fun execOnLive(command: String): String? = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        // Tag the activity-log category by sniffing the command:
        // `cat > … <stdin`-ish, `cat …`, `sha256sum`, `stat`, `pgrep`
        // are file/probe ops. Anything else falls under "diag" so the
        // log still surfaces it. We keep this off the hot logging
        // path — just a single take(20) string match.
        val category = when {
            command.startsWith("stat ") || command.startsWith("pgrep") ||
                command.contains("which ") || command.contains("--version") ||
                command.contains("npm view ") -> "probe"
            command.startsWith("cat ") || command.startsWith("cat>") ||
                command.contains("sha256sum") || command.contains("mkdir") ||
                command.startsWith("rm ") -> "file"
            else -> "diag"
        }
        val client = liveClient()
        if (client != null && client.isConnected) {
            try {
                val sess = client.startSession()
                try {
                    val cmd = sess.exec(command)
                    val out = java.io.ByteArrayOutputStream()
                    cmd.inputStream.copyTo(out)
                    cmd.join(15, TimeUnit.SECONDS)
                    val str = String(out.toByteArray(), Charsets.UTF_8)
                    ai.eight24family.conch.data.ServerActivityLog.append(
                        server.id,
                        ai.eight24family.conch.data.ServerActivityLog.Entry(
                            ts = start,
                            category = category,
                            command = command.take(600),
                            exitCode = cmd.exitStatus ?: -1,
                            stdoutTail = str.takeLast(200),
                            durationMs = System.currentTimeMillis() - start,
                        ),
                    )
                    return@withContext str
                } finally { SilentlyTry.fired("SshAi-AgentSession", "close execOnLive ssh session") { sess.close() } }
            } catch (e: Exception) {
                // Surface WHY the live channel refused the command — this was
                // swallowed, which hid the real cause of "Couldn't prepare the
                // upload" (execOnLive → null). Common: sshd MaxSessions reached
                // (too many concurrent channels on the one pooled transport → sshj
                // ConnectionException "channel open failure: administratively
                // prohibited"), or a transport error. Logged loudly + tagged with
                // the command so the failing op is unambiguous.
                android.util.Log.w(
                    "SshAi-AgentSession",
                    "execOnLive LIVE channel failed (${e.javaClass.simpleName}: ${e.message}) for cmd=${command.take(60)} — falling back to fresh handshake",
                    e,
                )
                // An OPEN failure means the server refused the channel — almost
                // always MaxSessions exhaustion on this one transport. Retrying
                // into it never recovers: every subsystem keeps failing while the
                // socket stays up, so the app looks connected and does nothing.
                // on a seamless-enrolled server that reconnect needs NO FIDO tap,
                // so this is invisible rather than something the user must fix by
                // hand.
                if (e is net.schmizz.sshj.connection.channel.OpenFailException ||
                    (e.message?.contains("open failed", ignoreCase = true) == true)
                ) {
                    SilentlyTry.fired("SshAi-AgentSession", "evict poisoned transport") {
                        ai.eight24family.conch.di.ServiceLocator.sshConnectionPool
                            .evictPoisoned(server.id, "channel open refused (MaxSessions?)")
                    }
                }
            }
        }
        // Fallback to a fresh ssh.execute when the persistent channel
        // is gone. CRITICAL: pass `skSigner` along — for FIDO-keyed
        // servers a fresh handshake without a signer auth-fails with
        // "Exhausted available authentication methods", returns null,
        // and the caller silently sees an empty result. Pull-to-refresh
        // on sessions list then renders "no sessions" even though the
        // server has plenty. If the cached signer's tag has been lifted
        // the fresh attempt still fails — but it fails LOUDLY now (the
        // caller sees `null`) instead of the symptom looking like an
        // empty server.
        //
        // RATE-LIMITED: when the transport is poisoned, EVERY subsystem's
        // execOnLive fails at once (~20 call sites) and each used to launch
        // its own fresh handshake — on an SK server with the tag lifted
        // that's a burst of guaranteed auth failures in the server's log
        // (fail2ban counts those). One failed fallback handshake per minute
        // per session is diagnosis enough; a success clears the limiter.
        val now = System.currentTimeMillis()
        if (now - lastFallbackFailMs < 60_000L) {
            android.util.Log.d(
                "SshAi-AgentSession",
                "execOnLive fallback suppressed (last fresh-handshake failure ${(now - lastFallbackFailMs) / 1000}s ago)",
            )
            return@withContext null
        }
        val fallback = ssh.execute(server, secrets, command, skSigner).getOrNull()
        lastFallbackFailMs = if (fallback == null) now else 0L
        ai.eight24family.conch.data.ServerActivityLog.append(
            server.id,
            ai.eight24family.conch.data.ServerActivityLog.Entry(
                ts = start,
                category = category,
                command = "[fallback handshake] " + command.take(580),
                exitCode = if (fallback != null) 0 else -1,
                stdoutTail = (fallback ?: "").takeLast(200),
                durationMs = System.currentTimeMillis() - start,
            ),
        )
        fallback
    }

    /**
     * Run a command, feeding [stdin] to its standard input. Returns the
     * exit status (true if exit 0). Used by the memory editor to push file
     * contents through `cat > path` without bloating the command line.
     */
    suspend fun execOnLiveWithStdin(command: String, stdin: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val client = liveClient() ?: return@withContext false
        if (!client.isConnected) return@withContext false
        var sess: Session? = null
        try {
            sess = client.startSession()
            val cmd = sess.exec(command)
            cmd.outputStream.use { it.write(stdin); it.flush() }
            cmd.inputStream.use { it.readBytes() }  // drain
            cmd.join(30, TimeUnit.SECONDS)
            (cmd.exitStatus ?: -1) == 0
        } catch (_: Throwable) {
            false
        } finally {
            SilentlyTry.fired("SshAi-AgentSession", "close stdin-write ssh session") { sess?.close() }
        }
    }
}
