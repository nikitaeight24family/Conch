package ai.eight24family.conch.agent

import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import net.schmizz.sshj.connection.channel.direct.Session
import java.util.concurrent.TimeUnit

/**
 * File-transfer + remote-file-probe helpers, extracted from
 * `AgentSession.kt`. These all ride the live pooled SSH client via
 * [AgentSessionSshLifecycle] but otherwise don't touch session state.
 */
internal class AgentSessionFileTransfer(
    private val sshLifecycle: AgentSessionSshLifecycle,
    private val state: StateFlow<SessionState>,
) {
    private companion object {
        /** How long a staged upload survives on the server. Long enough that a
         *  conversation resumed the same week still finds its attachment,
         *  short enough that a phone which sends photos daily cannot fill a
         *  small /tmp. The routine sweep runs with every upload; a failure that
         *  smells of a full disk sweeps harder (see [uploadStream]). */
        const val STAGING_TTL_DAYS = 7

        /** How long an upload waits for a transport that is being rebuilt before
         *  telling the user there is no connection. The watchdog's own rebuild
         *  measured ~16s on the owner's phone; anything shorter turns a
         *  self-healing blip into a failed attachment. */
        const val RECONNECT_GRACE_MS = 20_000

        /** Same channel-ceiling wait execOnLive does — an upload opens a channel
         *  too, and a refusal there is just as temporary. */
        const val CHANNEL_OPEN_TRIES = 4
        const val CHANNEL_OPEN_BACKOFF_MS = 150L
    }


    /**
     * Stream an attachment to the server through a fresh SSH exec channel
     * running `cat > <path>`, returning the absolute remote path on success.
     *
     * Why exec+cat instead of SFTP:
     *   - SFTP has a per-write ACK roundtrip (default ~32 KiB chunks). On a
     *     20–100 ms link that caps throughput at hundreds of KiB/s and a
     *     phone screenshot stalls visibly.
     *   - The SSH channel itself uses windowed flow control with much larger
     *     batches; `cat > file` just dumps bytes from stdin to the file.
     *   - On real networks this is 5–10× faster.
     *
     * @param onProgress called with progress in [0f..1f] as bytes flow.
     */
    suspend fun uploadFile(
        bytes: ByteArray,
        displayName: String,
        onProgress: (Float) -> Unit = {},
        onFailure: (String) -> Unit = {},
    ): String? = uploadStream(
        { java.io.ByteArrayInputStream(bytes) }, bytes.size.toLong(), displayName, onProgress, onFailure,
    )

    /**
     * Streaming upload — reads the source in 64 KiB chunks straight to the
     * server's `cat > file`, so a multi-hundred-MB file never has to live in
     * the phone's heap (the old [uploadFile] held the whole file as a
     * `ByteArray` → OutOfMemory on big attachments, user 2026-06-14). [open]
     * is called ONCE and closed here; [total] drives the progress bar (pass
     * the file size; <=0 disables progress %).
     */
    suspend fun uploadStream(
        open: () -> java.io.InputStream,
        total: Long,
        displayName: String,
        onProgress: (Float) -> Unit = {},
        onFailure: (String) -> Unit = {},
    ): String? = withContext(Dispatchers.IO) {
        val tag = "Conch-Upload"
        val t0 = System.currentTimeMillis()
        android.util.Log.d(tag, "begin name=$displayName size=${total}B sessionState=${state.value}")

        // Report the reason to the CALLER (it lands on the attachment chip),
        // never into the chat transcript. A failed upload is a composer event: it
        // belongs on the thing the user staged, next to the X that removes it —
        // not as a permanent turn in the conversation with the agent, which is
        // where two rows of "SSH not connected — pull-down to retry" came from
        // while the connection was fine and pull-to-refresh could not retry an
        // upload anyway.
        fun fail(reason: String): String? {
            android.util.Log.w(tag, "upload failed: $reason")
            onFailure(reason)
            return null
        }

        // liveClient(), never the captured field — see its KDoc. The pool rebuilds
        // transports underneath us, and the old code aborted against a corpse.
        //
        // ⛔ AND A MISSING TRANSPORT IS USUALLY A TRANSPORT MID-REBUILD. Giving up
        // on the first null is how a photo failed three seconds after the app had
        // dropped the connection itself, while the watchdog put it back sixteen
        // seconds later (device log, 2026-08-30 17:12:22 → 17:12:39). Nothing
        // about that is the user's problem to solve by tapping send again. Wait
        // for the rebuild — briefly, and only while there is reason to think one
        // is coming.
        val client = sshLifecycle.liveClient() ?: run {
            var waited = 0
            var recovered: net.schmizz.sshj.SSHClient? = null
            while (waited < RECONNECT_GRACE_MS && recovered == null) {
                delay(250); waited += 250
                recovered = sshLifecycle.liveClient()
            }
            android.util.Log.i(
                tag,
                if (recovered != null) "transport came back after ${waited}ms — carrying on"
                else "no transport after ${waited}ms of waiting",
            )
            recovered
        } ?: return@withContext fail("no SSH connection to the server")
        // Wait briefly until the session leaves Bootstrapping so we don't race
        // the agent startup channel. A stalled boot would otherwise translate
        // into a "stuck at 0%" upload bar.
        var spent = 0
        while (state.value is SessionState.Bootstrapping && spent < 10_000) {
            delay(200); spent += 200
        }
        android.util.Log.d(tag, "session ready after ${spent}ms, state=${state.value}")

        val safe = sanitizeFilename(displayName)
        val filename = "${System.currentTimeMillis()}_$safe"
        val remoteDir = stagingDir()
            ?: return@withContext fail("no writable place for uploads on the server")
        val remotePath = "$remoteDir/$filename"

        android.util.Log.d(tag, "staging dir: $remoteDir")

        // ⛔ AND THE SERVER'S OWN WORDS COME BACK WITH IT. `cat`'s stderr used to
        // go to the channel's stderr stream, which nothing here reads — so the
        // one sentence that explains the failure ("No space left on device",
        // "Disk quota exceeded", "Read-only file system") was thrown away and
        // the user was handed sshj's `Stream closed`, which describes our end of
        // a channel the server had already closed. Fold stderr into stdout: the
        // sentinel parsing is unchanged, and a dying `cat` now says why.
        val command = "{ cat > ${shellEscape(remotePath)} && echo CONCH_OK || echo CONCH_ERR_\$?; } 2>&1"

        // ONE TRANSPORT DYING IS NOT THE END OF THE FEATURE.
        //
        // The transfer owns its channel for as long as the bytes take, so it
        // cannot lean on execOnLive's recovery the way every command does: when
        // the transport is rebuilt underneath (network flip, keepalive loss, the
        // app's own process restart taking the pool with it) sshj answers the
        // next write with `ConnectionException: Stream closed` from
        // ChannelOutputStream.checkClose, and this used to be the end of it —
        // one throw, one dead attachment, while the chat around it kept working
        // because commands quietly reconnect. So: try the live transport, and if
        // the CHANNEL broke, rebuild and go again — once.
        //
        // A server REFUSAL is not retried. `cat` exiting non-zero (no space, no
        // permission, read-only /tmp) fails identically the second time; only
        // the reason needs to be honest, and [diagnose] makes it so.
        val first = attemptUpload(client, command, remotePath, open, total, onProgress, tag, t0)
        var outcome: Attempt = if (first !is Attempt.Broken) first else {
            android.util.Log.w(
                tag,
                "channel broke (${first.error.javaClass.simpleName}: ${first.error.message}) — " +
                    "rebuilding the transport for ONE retry",
            )
            onProgress(0f)
            sshLifecycle.onRebuiltTransport("upload channel died: ${first.error.message}") { fresh ->
                attemptUpload(
                    fresh, command, remotePath, open, total, onProgress, tag,
                    System.currentTimeMillis(),
                )
            } ?: first
        }
        // OUT OF SPACE IS A THING THE APP CAN FIX ITSELF.
        //
        // The staging dir is ours, so when the server has no room the first
        // thing to try is our own leftovers — not a message telling the user to
        // go ssh in and delete files. Sweep harder (yesterday's, not last
        // week's), re-measure, and only spend another transfer if the room is
        // actually there. If it isn't, say so with numbers.
        if (outcome !is Attempt.Done && freeBytesIn(remoteDir).let { it != null && it < total }) {
            android.util.Log.w(tag, "no room on the server — sweeping staging older than a day")
            sshLifecycle.execOnLive(
                "find $remoteDir -type f -mtime +1 -exec rm -f {} + 2>/dev/null; echo CONCH_SWEPT",
            )
            val nowFree = freeBytesIn(remoteDir)
            if (nowFree != null && nowFree >= total) {
                android.util.Log.i(tag, "swept: ${nowFree / (1024 * 1024)}MB free now — retrying")
                onProgress(0f)
                sshLifecycle.liveClient()?.let { live ->
                    outcome = attemptUpload(
                        live, command, remotePath, open, total, onProgress, tag,
                        System.currentTimeMillis(),
                    )
                }
            }
        }
        return@withContext when (val o = outcome) {
            is Attempt.Done -> {
                onProgress(1f)
                o.path
            }
            is Attempt.Refused -> fail(diagnose(o.reason, remoteDir, total))
            is Attempt.Broken -> fail(
                diagnose(o.error.message ?: o.error.javaClass.simpleName, remoteDir, total),
            )
        }
    }

    /** What one pass over one transport can end as. [Broken] is the only one
     *  worth a second try — see the call site. */
    private sealed interface Attempt {
        data class Done(val path: String) : Attempt
        /** The bytes arrived (or the channel closed cleanly) and the SERVER said no. */
        data class Refused(val reason: String) : Attempt
        /** The channel or transport died under us. */
        data class Broken(val error: Throwable) : Attempt
    }

    /**
     * Turn a transport-level message into something a person can act on.
     *
     * "Stream closed" is sshj telling us a channel was closed — true, useless,
     * and identical whether the phone changed network or the server's disk is
     * full. The chip is the only place the user learns anything, so when the
     * upload dies we ask the server the one question that separates those
     * cases: is there room in /tmp? One extra command, on the failure path
     * only, and it rides execOnLive so it works even when the upload's own
     * channel could not.
     */
    private suspend fun diagnose(raw: String, dir: String, needBytes: Long): String {
        val freeBytes = freeBytesIn(dir)
        if (freeBytes != null && needBytes > 0 && freeBytes < needBytes) {
            val freeMb = freeBytes / (1024 * 1024)
            val needMb = (needBytes / (1024 * 1024)).coerceAtLeast(1)
            return "the server is out of space in $dir — ${freeMb}MB free, ${needMb}MB needed"
        }
        return raw
    }

    /**
     * Where this server's uploads are staged — decided by asking the server,
     * once per session, then remembered.
     *
     * ⛔ `/tmp/conch_uploads` WAS A SHARED NAME IN A SHARED DIRECTORY, and that
     * is the whole bug. `/tmp` is world-writable and sticky, so the FIRST
     * account to upload owns `conch_uploads` and every other account on that box
     * is locked out of it. `mkdir -p` still returns 0 — the directory does
     * exist — which is why the old check passed and the failure surfaced far
     * downstream as sshj's `Stream closed`: `cat` died of Permission denied the
     * instant it tried to create the file, the server closed the channel, and
     * our next 64 KiB write hit a corpse. Measured on the owner's server,
     * 2026-08-30 17:17:55, once a probe byte was finally being written:
     *   `bash: /tmp/conch_uploads/.conch-write-probe: Permission denied`
     *
     * The name now carries the uid, which makes that collision impossible, and
     * the candidates are tried in order until one accepts a probe byte:
     *   1. `/tmp/conch_uploads_<uid>` — fast (often tmpfs), private per account
     *   2. `$HOME/.cache/conch_uploads` — for boxes with a locked-down /tmp
     *   3. `/tmp/conch_uploads` — the legacy path, still right where it is ours
     * One round trip decides, sweeps the expired files and names the winner.
     * Files already staged under the legacy path keep working: the dedupe cache
     * verifies a cached path still exists before reusing it.
     */
    @Volatile private var stagingDirCache: String? = null

    private suspend fun stagingDir(): String? {
        stagingDirCache?.let { return it }
        val script = "for d in \"/tmp/conch_uploads_\$(id -u)\" \"\$HOME/.cache/conch_uploads\" " +
            "\"/tmp/conch_uploads\"; do " +
            "mkdir -p \"\$d\" 2>/dev/null || continue; " +
            "find \"\$d\" -type f -mtime +$STAGING_TTL_DAYS -exec rm -f {} + 2>/dev/null; " +
            "if printf conch > \"\$d/.conch-probe\" 2>/dev/null; then " +
            "rm -f \"\$d/.conch-probe\"; echo \"CONCH_DIR=\$d\"; break; fi; done"
        val out = sshLifecycle.execOnLive(script)
        val dir = out?.lineSequence()
            ?.firstOrNull { it.contains("CONCH_DIR=") }
            ?.substringAfter("CONCH_DIR=")?.trim()
            ?.takeIf { it.isNotBlank() }
        if (dir == null) {
            android.util.Log.w(
                "Conch-Upload",
                "no writable staging dir — server said: ${out?.take(200) ?: "nothing (exec did not run)"}",
            )
        } else {
            android.util.Log.i("Conch-Upload", "staging dir for this server: $dir")
            stagingDirCache = dir
        }
        return dir
    }

    /**
     * Free bytes on the filesystem holding [dir], or null when the server would
     * not say.
     *
     * Parsed HERE, not by `awk` on the far side: a diagnostic that needs a tool
     * the box may not ship is a diagnostic that goes quiet exactly on the
     * minimal containers most likely to run out of space. `df -Pk` is POSIX, so
     * BusyBox and BSD answer it; the columns are 1K-blocks, used, AVAILABLE,
     * capacity, mount — and a long device name wraps the row, which is why the
     * scan takes the last numeric-looking row rather than "line 2, field 4".
     */
    private suspend fun freeBytesIn(dir: String): Long? =
        SilentlyTry.logged("Conch-Upload", "check upload space") {
            val out = sshLifecycle.execOnLive("df -Pk $dir 2>&1") ?: return@logged null
            out.lineSequence()
                .map { it.trim().split(Regex("\\s+")) }
                .filter { it.size >= 4 && it[1].toLongOrNull() != null }
                .lastOrNull()
                ?.get(3)?.toLongOrNull()
                ?.times(1024L)
        }

    /** The first line of server output that is an explanation rather than one of
     *  our sentinels — the sentence worth putting on the chip. */
    private fun serverSaid(raw: String?): String? = raw
        ?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.isNotBlank() && !it.startsWith("CONCH_") }
        ?.take(160)

    private suspend fun attemptUpload(
        client: net.schmizz.sshj.SSHClient,
        command: String,
        remotePath: String,
        open: () -> java.io.InputStream,
        total: Long,
        onProgress: (Float) -> Unit,
        tag: String,
        t0: Long,
    ): Attempt = withContext(Dispatchers.IO) {
        var sess: Session? = null
        var cmd: Session.Command? = null
        try {
            onProgress(0f)
            android.util.Log.d(tag, "opening exec channel")
            // A refused channel means the server is at its simultaneous-channel
            // ceiling right now, not that the upload cannot happen — wait it out
            // rather than burning the attempt (and, as it used to, the transport).
            var refusal: Throwable? = null
            repeat(CHANNEL_OPEN_TRIES) { attempt ->
                if (sess == null) {
                    try {
                        sess = client.startSession()
                    } catch (e: Exception) {
                        val refused = e is net.schmizz.sshj.connection.channel.OpenFailException ||
                            e.message?.contains("open failed", ignoreCase = true) == true
                        if (!refused || !client.isConnected) throw e
                        refusal = e
                        android.util.Log.d(tag, "channel refused (${attempt + 1}/$CHANNEL_OPEN_TRIES) — waiting")
                        delay(CHANNEL_OPEN_BACKOFF_MS * (attempt + 1))
                    }
                }
            }
            val channel = sess ?: throw (refusal ?: net.schmizz.sshj.common.SSHException("channel open refused"))
            android.util.Log.d(tag, "channel opened, sending exec")
            cmd = channel.exec(command)
            android.util.Log.d(tag, "exec sent, streaming bytes")

            val buf = ByteArray(64 * 1024)
            var sent = 0L
            var lastLogged = 0L
            open().use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    cmd.outputStream.write(buf, 0, n)
                    sent += n
                    if (total > 0) onProgress((sent.toFloat() / total).coerceIn(0f, 1f))
                    val now = System.currentTimeMillis()
                    if (now - lastLogged > 500) {
                        val pct = if (total > 0) sent * 100L / total else -1L
                        android.util.Log.d(tag, "  sent=$sent/$total ($pct%) elapsed=${now - t0}ms")
                        lastLogged = now
                    }
                }
            }
            android.util.Log.d(tag, "stdin write done in ${System.currentTimeMillis() - t0}ms, flushing+EOF")
            cmd.outputStream.flush()
            cmd.outputStream.close()

            android.util.Log.d(tag, "draining stdout")
            val out = java.io.ByteArrayOutputStream()
            try {
                val buf = ByteArray(4096)
                while (true) {
                    val n = cmd.inputStream.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
            } catch (e: Throwable) {
                android.util.Log.w(tag, "stdout read ended with: ${e.javaClass.simpleName}: ${e.message}")
            }
            cmd.join(2, TimeUnit.MINUTES)

            val outText = String(out.toByteArray(), Charsets.UTF_8)
            val exit = cmd.exitStatus
            android.util.Log.d(tag, "exit=$exit stdout=${outText.take(200)} elapsed=${System.currentTimeMillis() - t0}ms")
            if ((exit == null || exit == 0) && outText.contains("CONCH_OK")) {
                onProgress(1f)
                return@withContext Attempt.Done(remotePath)
            }
            // The command RAN and said no. A repeat gets the same answer — this
            // is a refusal, not a broken channel, so it must not be retried.
            val err = outText.lineSequence()
                .firstOrNull { it.startsWith("CONCH_ERR_") }
                ?.removePrefix("CONCH_ERR_")
                // The server's own sentence beats our exit code every time —
                // stderr is folded into this stream on purpose.
                ?.let { code -> serverSaid(outText) ?: "cat exited $code" }
                ?: serverSaid(outText) ?: ("exit=" + exit)
            return@withContext Attempt.Refused(err)
        } catch (t: Throwable) {
            android.util.Log.e(tag, "exception during upload", t)
            // ⛔ ASK THE DYING CHANNEL WHAT IT ALREADY SAID.
            //
            // When `cat` fails, the server writes its reason and closes — and
            // our next write then throws `Stream closed`, which is OUR end of a
            // channel the server had already given a verdict on. Those bytes are
            // usually sitting in the read buffer unread, so the reason we hand
            // the user was thrown away while the exception that replaced it
            // described nothing (2026-08-30). Read what is already buffered (no
            // blocking — the channel is gone), and if the server explained
            // itself, that is a REFUSAL with a real sentence, not a mystery.
            val parting = SilentlyTry.logged(tag, "read the server's parting words") {
                val ins = cmd?.inputStream ?: return@logged null
                val n = ins.available()
                if (n <= 0) null else ByteArray(minOf(n, 4096))
                    .also { ins.read(it, 0, it.size) }
                    .toString(Charsets.UTF_8)
            }
            val said = serverSaid(parting)
            if (said != null) {
                android.util.Log.w(tag, "the server explained itself before closing: $said")
                return@withContext Attempt.Refused(said)
            }
            return@withContext Attempt.Broken(t)
        } finally {
            SilentlyTry.fired("Conch-AgentSession", "close upload ssh session") { sess?.close() }
        }
    }

    private fun sanitizeFilename(name: String): String {
        // Strip path separators and odd characters; keep alphanum, dot, dash, underscore.
        val base = name.substringAfterLast('/').substringAfterLast('\\').take(80)
        val cleaned = base.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return cleaned.ifBlank { "file" }
    }

    /**
     * Stream a remote file's bytes to [sink] over a fresh `cat` exec
     * channel, mirroring [uploadFile] in reverse. Caller owns [sink] and
     * is responsible for closing it.
     *
     * Pre-flight: we run `stat -c %s <path>` to (a) confirm the file
     * exists and is readable AND (b) discover the size for progress.
     * If the stat fails the channel is never opened, so we never write a
     * partial file or a zero-byte placeholder into Downloads.
     *
     * @param onProgress invoked as `(bytesRead, totalBytesOrMinusOne)`.
     */
    suspend fun downloadFile(
        remotePath: String,
        sink: java.io.OutputStream,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): AgentSession.DownloadOutcome = withContext(Dispatchers.IO) {
        val tag = "Conch-Download"
        // Same stale-capture bug as the upload path had; ask for a live transport.
        val client = sshLifecycle.liveClient()
            ?: return@withContext AgentSession.DownloadOutcome.Failed("no SSH connection")
        // Wait briefly for bootstrap, same logic as upload.
        var spent = 0
        while (state.value is SessionState.Bootstrapping && spent < 10_000) {
            delay(200); spent += 200
        }

        val escaped = shellEscapeRemotePath(remotePath)
        val total: Long = run {
            val out = sshLifecycle.execOnLive(
                // GNU stat, then BSD/macOS stat, then POSIX wc — Play users
                // bring macOS/BSD servers too (2026-08-17).
                "if [ -f $escaped ] && [ -r $escaped ]; then stat -c %s $escaped 2>/dev/null || " +
                    "stat -f %z $escaped 2>/dev/null || wc -c < $escaped; else echo CONCH_NOFILE; fi"
            ) ?: return@withContext AgentSession.DownloadOutcome.Failed("SSH exec failed")
            val trimmed = out.trim()
            if (trimmed.contains("CONCH_NOFILE")) {
                return@withContext AgentSession.DownloadOutcome.Failed("file not found or not readable")
            }
            trimmed.lines().firstNotNullOfOrNull { it.trim().toLongOrNull() } ?: -1L
        }
        android.util.Log.d(tag, "begin path=$remotePath size=$total")
        onProgress(0, total)

        var sess: Session? = null
        return@withContext try {
            sess = client.startSession()
            val cmd = sess.exec("cat -- $escaped")
            val buf = ByteArray(64 * 1024)
            var got = 0L
            var lastLogged = 0L
            while (true) {
                val n = cmd.inputStream.read(buf)
                if (n <= 0) break
                sink.write(buf, 0, n)
                got += n
                onProgress(got, total)
                val now = System.currentTimeMillis()
                if (now - lastLogged > 500) {
                    val pct = if (total > 0) (got * 100L / total) else -1L
                    android.util.Log.d(tag, "  got=$got/$total ($pct%)")
                    lastLogged = now
                }
            }
            cmd.join(2, TimeUnit.MINUTES)
            sink.flush()
            val exit = cmd.exitStatus
            android.util.Log.d(tag, "done exit=$exit bytes=$got")
            if (exit == null || exit == 0) AgentSession.DownloadOutcome.Done(got)
            else AgentSession.DownloadOutcome.Failed("cat exited with $exit")
        } catch (t: Throwable) {
            android.util.Log.e(tag, "exception during download", t)
            AgentSession.DownloadOutcome.Failed(t.message ?: t.javaClass.simpleName)
        } finally {
            SilentlyTry.fired("Conch-AgentSession", "close download ssh session") { sess?.close() }
        }
    }

    /**
     * Cheap probe: does the file at the given absolute path exist on the
     * server right now? Used by the upload-dedupe path to confirm a cached
     * remote path is still live before skipping the actual upload.
     */
    suspend fun checkRemoteFileExists(remotePath: String): Boolean = withContext(Dispatchers.IO) {
        val out = sshLifecycle.execOnLive(
            "[ -f ${shellEscapeRemotePath(remotePath)} ] && echo CONCH_EXISTS || echo CONCH_GONE"
        )
        out?.contains("CONCH_EXISTS") == true
    }

    /**
     * One-shot existence + size + SHA-256 probe. Single SSH round
     * trip — issues `stat` (Linux + BSD fallback) and `sha256sum`,
     * prefixed with sentinels so we can tell "file gone" from
     * "stat said 0 bytes". Lets the disk icon label sizes BEFORE
     * tap and lets the cache layer match by content hash instead
     * of basename.
     *
     * Returns null when the file doesn't exist. On hosts without
     * `sha256sum` in PATH falls back to `shasum -a 256` (macOS)
     * then `openssl dgst -sha256` — one of those is on every
     * server we care about.
     */
    suspend fun statRemoteFile(remotePath: String): AgentSession.RemoteFileProbe = withContext(Dispatchers.IO) {
        val escaped = shellEscapeRemotePath(remotePath)
        val cmd = "if [ -f $escaped ]; then " +
            "s=\$(stat -c %s $escaped 2>/dev/null || stat -f %z $escaped 2>/dev/null); " +
            "h=\$(sha256sum $escaped 2>/dev/null | awk '{print \$1}'); " +
            "[ -z \"\$h\" ] && h=\$(shasum -a 256 $escaped 2>/dev/null | awk '{print \$1}'); " +
            "[ -z \"\$h\" ] && h=\$(openssl dgst -sha256 $escaped 2>/dev/null | awk '{print \$NF}'); " +
            "echo \"CONCH_SIZE=\$s\"; echo \"CONCH_HASH=\$h\"; " +
            "else echo CONCH_GONE; fi"
        val out = sshLifecycle.execOnLive(cmd) ?: return@withContext AgentSession.RemoteFileProbe.ProbeError
        if ("CONCH_GONE" in out) return@withContext AgentSession.RemoteFileProbe.NotFound
        val size = Regex("CONCH_SIZE=(\\d+)").find(out)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: return@withContext AgentSession.RemoteFileProbe.ProbeError
        val hash = Regex("CONCH_HASH=([0-9a-fA-F]{64})").find(out)?.groupValues?.getOrNull(1)
            ?: ""  // host has none of the three hashers — fall back to size-only match
        AgentSession.RemoteFileProbe.Exists(AgentSession.RemoteFileInfo(size, hash))
    }

    /** Legacy size-only variant kept for back-compat with callers
     *  that don't care about the hash. Returns null on NotFound or
     *  ProbeError indiscriminately — modern callers should use
     *  [statRemoteFile] directly. */
    suspend fun statRemoteFileSize(remotePath: String): Long? =
        (statRemoteFile(remotePath) as? AgentSession.RemoteFileProbe.Exists)?.info?.sizeBytes
}
