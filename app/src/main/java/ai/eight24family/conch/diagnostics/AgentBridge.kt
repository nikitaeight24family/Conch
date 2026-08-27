package ai.eight24family.conch.diagnostics

import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.util.AudioRecorder
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Watches a per-server `~/.conch-bridge/inbox/` directory for JSON
 * request files written by the `conch-bridge` CLI on the user's
 * server, dispatches them to a handler (e.g. [LogCaptureCoordinator]),
 * and writes responses to `outbox/`.
 *
 * Communication piggy-backs the existing [SshConnectionPool] client
 * — every poll is a `bash -lc 'ls -1 ~/.conch-bridge/inbox/'` plus
 * `cat <file>` over a fresh channel on the live transport. Costs
 * ~2 SSH packets per tick, no auth round-trip, no extra touches for
 * security-key servers.
 *
 * **Lifecycle**: started in [start] when the user has any chat open
 * on a server (the foreground service is up anyway). Stopped in
 * [stop] when no chats remain. Polling tick is 2 s; that's the
 * upper bound on agent-side latency for "ask the phone for logs"
 * commands.
 *
 * **Wire protocol** matches CLAUDE.md §11.5. Files:
 *   ~/.conch-bridge/inbox/<uuid>.req.json
 *   ~/.conch-bridge/outbox/<uuid>.res.json
 *   ~/.conch-bridge/outbox/<uuid>.data        ← optional binary blob
 */
class AgentBridge(
    private val serverId: String,
    private val handler: BridgeHandler,
) {

    private val tag = "SshAi-Bridge"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private val seenIds = LinkedHashSet<String>()

    fun start() {
        if (pollJob?.isActive == true) return
        android.util.Log.d(tag, "start: polling ~/.conch-bridge/inbox/ for $serverId")
        pollJob = scope.launch {
            // We do NOT touch the server here. The bridge dirs + the
            // conch-bridge CLI are installed ONLY when the user explicitly taps
            // "Connect phone to server" in a chat (BridgeInstaller). Until then
            // this just polls — `ls` of a missing inbox is a harmless no-op
            // (2>/dev/null). No uninvited writes to anyone's server.
            while (isActive) {
                // The bridge contract is FOREGROUND-ONLY (CLAUDE.md §11.5:
                // "When ssh.ai is backgrounded, polling pauses") — but this
                // loop never actually paused: 1 800 SSH execs/hour/server
                // around the clock, radio wakeups included, whether or not
                // anyone could possibly issue a request. Honor the contract:
                // backgrounded → no exec, long sleep (the CLI's 30 s timeout
                // already tells the agent "phone may be backgrounded").
                if (!ai.eight24family.conch.util.AppForeground.isForeground) {
                    delay(POLL_INTERVAL_BACKGROUND_MS)
                    continue
                }
                runCatching { tick() }
                    .onFailure { android.util.Log.w(tag, "tick failed: ${it.message}") }
                // Data-saver bumps the inbox poll 2s → 10s. Most users
                // never invoke `conch-bridge` requests at all, so the
                // 5× longer round-trip cadence is invisible.
                val dataSaver = SilentlyTry.loggedOrElse("SshAi-AgentBridge", "read data saver pref", false) {
                    ai.eight24family.conch.di.ServiceLocator.preferences.dataSaverEnabled.first()
                }
                delay(if (dataSaver) POLL_INTERVAL_MS * 5 else POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        android.util.Log.d(tag, "stop")
        pollJob?.cancel()
        pollJob = null
        seenIds.clear()
        // Poller gone → drop the heartbeat so the 📱 glyph clears instead of
        // coasting on a stale "alive" stamp.
        BridgeHealth.clear(serverId)
    }

    private suspend fun tick() {
        val raw = execOnServer(
            // `-1` = one file per line. `2>/dev/null` swallows the
            // "no such file" error if `inbox/` was just deleted.
            "ls -1 \$HOME/.conch-bridge/inbox/ 2>/dev/null"
        )
        // Heartbeat = CHANNEL layer only: non-null `ls` ⇒ SSH up AND this poller
        // running ⇒ the phone is reachable for bridge requests. Shizuku (the
        // privileged-capability layer) is checked LIVE at glyph-render time, NOT
        // folded in here — the two are independent (Shizuku can be OOM-killed while
        // the channel keeps polling), and a live check flips the glyph in ~2s
        // instead of waiting out this heartbeat's window.
        if (raw != null) BridgeHealth.markAlive(serverId)
        val listing = raw.orEmpty()
        val files = listing.split('\n')
            .map { it.trim() }
            .filter { it.endsWith(".req.json") }
            // Reject anything that isn't a strict UUID-named req.json — defends against shell-meta-laced filenames.
            .filter { REQ_FILENAME_REGEX.matches(it) }
            .filter { it !in seenIds }
        for (file in files) {
            seenIds += file
            // Bound the seen-set so it doesn't grow unbounded across
            // a long session. 256 entries is plenty for human pace.
            if (seenIds.size > 256) {
                val it = seenIds.iterator(); it.next(); it.remove()
            }
            handleOne(file)
        }
    }

    private suspend fun handleOne(filename: String) {
        android.util.Log.d(tag, "handling $filename")
        // Filename already validated against REQ_FILENAME_REGEX in tick(); single-quote anyway as defense-in-depth.
        val raw = execOnServer(
            "cat \$HOME/.conch-bridge/inbox/'$filename'"
        ).orEmpty()
        val req = SilentlyTry.logged("SshAi-AgentBridge", "parse bridge request json") { JSON.parseToJsonElement(raw).jsonObject }
        if (req == null) {
            android.util.Log.w(tag, "  $filename: not JSON, deleting")
            execOnServer("rm -f \$HOME/.conch-bridge/inbox/'$filename'")
            return
        }
        // Reject id values that aren't strict UUIDs — they later flow into shell paths.
        val rawId = req["id"]?.jsonPrimitive?.contentOrNull
        val id = when {
            rawId != null && UUID_REGEX.matches(rawId) -> rawId
            else -> filename.removeSuffix(".req.json")
        }
        if (!UUID_REGEX.matches(id)) {
            android.util.Log.w(tag, "  $filename: id failed UUID validation, deleting")
            execOnServer("rm -f \$HOME/.conch-bridge/inbox/'$filename'")
            return
        }
        val command = req["command"]?.jsonPrimitive?.contentOrNull
        val args = req["args"]?.jsonObject ?: JsonObject(emptyMap())

        val response = runCatching {
            when (command) {
                "logs" -> handler.handleLogs(args)
                "ping" -> BridgeResponse.ok("pong")
                "screenshot" -> handler.handleScreenshot(args)
                // SEC-1 kill-switch: the bridge is an unauthenticated adb-level
                // channel — any code-exec as the SSH user can drive it. The user
                // can't stop same-uid abuse from the server, but they CAN deny the
                // most dangerous verb (`shell`) from the phone. Off → refuse shell
                // but keep logs/ping/screenshot working. Default on (autonomy).
                "shell" ->
                    if (ServiceLocator.preferences.bridgeShellAllowed.first()) {
                        handler.handleShell(args)
                    } else {
                        BridgeResponse.err(
                            "shell disabled on this phone — re-enable in Conch → " +
                                "Settings → Security → \"Run shell from server\". " +
                                "logs/ping/screenshot still work.",
                        )
                    }
                // MICROPHONE. Off by default, unlike every other verb: shell and
                // logs read a device the user handed over, a mic records the ROOM
                // and whoever is in it. The bridge is unauthenticated by design,
                // so this one waits for an explicit opt-in on the phone.
                "audio" ->
                    if (ServiceLocator.preferences.bridgeAudioAllowed.first()) {
                        handler.handleAudio(args)
                    } else {
                        BridgeResponse.err(
                            "audio disabled on this phone — enable it in Conch → " +
                                "Settings → Security → \"Record audio from server\". " +
                                "Off by default because this records the room, not the screen.",
                        )
                    }
                else -> BridgeResponse.err("unknown command: $command")
            }
        }.getOrElse { BridgeResponse.err(it.message ?: it.javaClass.simpleName) }

        writeResponse(id, response)
        // Atomic move to consume so we don't process twice on a flaky
        // poll.
        execOnServer("rm -f \$HOME/.conch-bridge/inbox/'$filename'")
    }

    private suspend fun writeResponse(id: String, resp: BridgeResponse) {
        // id is UUID-validated in handleOne(); single-quote the path for defense-in-depth.
        val outDir = "\$HOME/.conch-bridge/outbox"
        val resFinal = "$outDir/'$id.res.json'"
        val resPart = "$outDir/'$id.res.json.part'"
        val dataFinal = "$outDir/'$id.data'"
        val dataPart = "$outDir/'$id.data.part'"

        // Strategy: when the payload is small text, inline it as a
        // string field. When it's bigger or binary, write to .data
        // and reference its path.
        val inline = resp.text != null && resp.text.length < INLINE_LIMIT && resp.binary == null
        val resJson = buildString {
            append('{')
            append("\"id\":\"").append(escapeJson(id)).append('"')
            append(",\"ok\":").append(resp.ok)
            if (resp.error != null) {
                append(",\"error\":\"").append(escapeJson(resp.error)).append('"')
            }
            if (inline && resp.text != null) {
                append(",\"text\":\"").append(escapeJson(resp.text)).append('"')
            } else if (resp.text != null || resp.binary != null) {
                append(",\"data_path\":\"~/.conch-bridge/outbox/").append(id).append(".data\"")
            }
            if (resp.metadata.isNotEmpty()) {
                append(",\"metadata\":").append(JSON.encodeToString(JsonObject.serializer(), JsonObject(resp.metadata)))
            }
            append(",\"ts\":").append(System.currentTimeMillis())
            append('}')
        }

        // ATOMIC writes (temp file + rename). The server-side bridge polls the
        // outbox for `<id>.res.json` and `cat`s it the instant the name shows
        // up; a plain `cat > file` truncates-then-streams, so the poller can
        // catch a 0-byte file and `json.load` dies with "Expecting value: line
        // 1 column 1 (char 0)" (and `set -euo pipefail` then aborts the whole
        // ping). We write to a `.part` name the poller ignores, then `mv` —
        // rename(2) within one dir is atomic, so the final name only ever
        // appears fully written. Root-cause fix for the bridge ping race.
        //
        // Order matters: the DATA blob is written+renamed BEFORE res.json, so
        // by the time the poller sees res.json's `data_path` the data file is
        // already complete — no partial-payload read either.
        if (!inline && resp.text != null) {
            execOnServerWithStdin("cat > $dataPart && mv -f $dataPart $dataFinal", resp.text.toByteArray(Charsets.UTF_8))
        } else if (resp.binary != null) {
            execOnServerWithStdin("cat > $dataPart && mv -f $dataPart $dataFinal", resp.binary)
        }
        execOnServerWithStdin("cat > $resPart && mv -f $resPart $resFinal", resJson.toByteArray(Charsets.UTF_8))
    }

    /** Fresh channel on the pooled client, exec, return stdout. */
    private suspend fun execOnServer(cmd: String): String? {
        val client = ServiceLocator.sshConnectionPool.peek(serverId) ?: return null
        return SilentlyTry.logged("SshAi-AgentBridge", "exec on server") {
            val sess = client.startSession()
            try {
                val proc = sess.exec(cmd)
                val out = java.io.ByteArrayOutputStream()
                // Bounded read: the deadline wraps the READ, not the join after it.
                ai.eight24family.conch.ssh.BoundedExec.drain(
                    proc, out,
                    deadlineMs = ai.eight24family.conch.ssh.BoundedExec.Deadline.COMMAND_MS,
                    maxBytes = ai.eight24family.conch.ssh.BoundedExec.Cap.COMMAND,
                )
                proc.join(15, java.util.concurrent.TimeUnit.SECONDS)
                String(out.toByteArray(), Charsets.UTF_8)
            } finally { SilentlyTry.fired("SshAi-AgentBridge", "close exec session") { sess.close() } }
        }
    }

    /** Pipe [stdin] into [cmd]; useful for `cat > path` writes. */
    private suspend fun execOnServerWithStdin(cmd: String, stdin: ByteArray): Boolean {
        val client = ServiceLocator.sshConnectionPool.peek(serverId) ?: return false
        return SilentlyTry.loggedOrElse("SshAi-AgentBridge", "exec with stdin", false) {
            val sess = client.startSession()
            try {
                val proc = sess.exec(cmd)
                proc.outputStream.use { it.write(stdin); it.flush() }
                proc.inputStream.use { it.readBytes() }
                proc.join(15, java.util.concurrent.TimeUnit.SECONDS)
                (proc.exitStatus ?: -1) == 0
            } finally { SilentlyTry.fired("SshAi-AgentBridge", "close stdin-exec session") { sess.close() } }
        }
    }

    private fun escapeJson(s: String): String = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }

    companion object {
        /** Max inline text size before falling back to a separate .data file. */
        private const val INLINE_LIMIT = 8 * 1024
        private const val POLL_INTERVAL_MS = 2_000L
        /** Backgrounded: no SSH exec at all, just a slow liveness nap so the
         *  loop resumes ~promptly on foreground. */
        private const val POLL_INTERVAL_BACKGROUND_MS = 15_000L
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
        // Strict UUID-v4-shape patterns; reject anything else before it reaches the shell.
        private val UUID_REGEX = Regex("^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$")
        private val REQ_FILENAME_REGEX = Regex("^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.req\\.json$")
    }
}

/**
 * Pluggable handler for bridge commands. The top-level service
 * (foreground service / app singleton) provides one instance and
 * routes individual commands to the right subsystem.
 */
interface BridgeHandler {
    suspend fun handleLogs(args: JsonObject): BridgeResponse
    suspend fun handleScreenshot(args: JsonObject): BridgeResponse =
        BridgeResponse.err("screenshot not implemented yet")
    /**
     * Record the phone's microphone for `args.seconds` (default 10, capped at
     * [ai.eight24family.conch.util.AudioRecorder.MAX_SECONDS]) and return the
     * AAC/MP4 bytes. Gated by the phone-side switch at the call site.
     */
    suspend fun handleAudio(args: JsonObject): BridgeResponse =
        BridgeResponse.err("audio not implemented")

    /** Run an arbitrary shell command at shell UID (adb-shell equivalent)
     *  via Shizuku. `args.command` = the command string. */
    suspend fun handleShell(args: JsonObject): BridgeResponse =
        BridgeResponse.err("shell not implemented")
}

@Serializable
data class BridgeResponse(
    val ok: Boolean,
    val text: String? = null,
    val binary: ByteArray? = null,
    val error: String? = null,
    val metadata: Map<String, JsonElement> = emptyMap(),
) {
    companion object {
        fun ok(text: String, metadata: Map<String, JsonElement> = emptyMap()): BridgeResponse =
            BridgeResponse(ok = true, text = text, metadata = metadata)

        fun ok(binary: ByteArray, metadata: Map<String, JsonElement> = emptyMap()): BridgeResponse =
            BridgeResponse(ok = true, binary = binary, metadata = metadata)

        fun err(reason: String): BridgeResponse =
            BridgeResponse(ok = false, error = reason)
    }
}

/**
 * Default handler that wires bridge requests to
 * [LogCaptureCoordinator]. Drop-in for app code; tests can swap.
 */
class DefaultBridgeHandler(
    private val logs: LogCaptureCoordinator,
    private val appContext: android.content.Context = ServiceLocator.appContext,
) : BridgeHandler {

    /**
     * Record the microphone for a fixed span and hand back the AAC/MP4 bytes.
     *
     * Fixed duration because there is nobody on this end to press stop — the
     * caller is an agent on the far side of an SSH link. Capped by
     * [AudioRecorder.MAX_SECONDS] so a typo cannot leave the mic open.
     *
     * The phone-side switch is checked by the DISPATCHER, not here, so this stays
     * a plain capability and the policy lives in one place.
     */
    override suspend fun handleAudio(args: JsonObject): BridgeResponse {
        val seconds = args["seconds"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 10
        if (!AudioRecorder.micGranted(appContext)) {
            return BridgeResponse.err(
                "microphone not granted to Conch — open Conch and record one voice " +
                    "message, or grant it in Android Settings → Apps → Conch → Permissions.",
            )
        }
        val started = System.currentTimeMillis()
        val bytes = AudioRecorder.recordFor(appContext, seconds)
            ?: return BridgeResponse.err("recording produced nothing (mic busy, or another app holds it)")
        val meta = mapOf(
            "seconds" to JsonPrimitive(seconds.coerceIn(1, AudioRecorder.MAX_SECONDS)),
            "bytes" to JsonPrimitive(bytes.size),
            "duration_ms" to JsonPrimitive(System.currentTimeMillis() - started),
            "format" to JsonPrimitive("audio/mp4"),
        )
        return BridgeResponse.ok(bytes, meta)
    }

    override suspend fun handleLogs(args: JsonObject): BridgeResponse {
        val req = LogCaptureService.CaptureRequest(
            tagFilter = args["filter"]?.jsonPrimitive?.contentOrNull,
            minLevel = args["level"]?.jsonPrimitive?.contentOrNull?.let {
                SilentlyTry.logged("SshAi-AgentBridge", "parse log level") { LogCaptureService.CaptureRequest.Level.valueOf(it.lowercase().replaceFirstChar(Char::titlecase)) }
            } ?: LogCaptureService.CaptureRequest.Level.Verbose,
            maxLines = args["lines"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 2_000,
            sinceTime = args["since"]?.jsonPrimitive?.contentOrNull,
            tierOverride = args["tier"]?.jsonPrimitive?.contentOrNull?.let {
                SilentlyTry.logged("SshAi-AgentBridge", "parse tier override") {
                    LogCaptureService.Tier.valueOf(it.lowercase().replaceFirstChar(Char::titlecase))
                }
            },
        )
        val res = logs.capture(req)
        val meta = mapOf(
            "lines" to JsonPrimitive(res.lineCount),
            "duration_ms" to JsonPrimitive(res.durationMs),
            "tier" to JsonPrimitive(res.tier.name),
        )
        return BridgeResponse.ok(res.text, meta)
    }

    override suspend fun handleShell(args: JsonObject): BridgeResponse {
        val command = args["command"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return BridgeResponse.err("shell: missing 'command'")
        if (!ShizukuShell.available()) {
            return BridgeResponse.err(
                if (ShizukuShell.bound())
                    "Shizuku is running but Conch isn't granted — open Conch → Settings → Phone bridge → Enable."
                else
                    "Shizuku not available — install + start the Shizuku app, then grant Conch in Settings → Phone bridge."
            )
        }
        val r = ShizukuShell.exec(command)
        // stdout is the primary payload (clean for the agent to parse).
        // exit code, a stderr snippet, and flags ride in metadata — the
        // CLI prints metadata to its own stderr.
        val meta = mapOf(
            "exit" to JsonPrimitive(r.exitCode),
            "truncated" to JsonPrimitive(r.truncated),
            "timed_out" to JsonPrimitive(r.timedOut),
            "stderr" to JsonPrimitive(r.stderr.take(4000)),
        )
        return BridgeResponse.ok(r.stdout, meta)
    }
}
