package ai.eight24family.conch.agent

import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import net.schmizz.sshj.connection.channel.direct.Session
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * File-transfer + remote-file-probe helpers, extracted from
 * `AgentSession.kt`. These all ride the live pooled SSH client via
 * [AgentSessionSshLifecycle] but otherwise don't touch session state.
 */
internal class AgentSessionFileTransfer(
    private val sshLifecycle: AgentSessionSshLifecycle,
    private val history: AgentSessionHistory,
    private val state: StateFlow<SessionState>,
) {

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
        onProgress: (Float) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {
        val tag = "SshAi-Upload"
        val t0 = System.currentTimeMillis()
        android.util.Log.d(tag, "begin name=$displayName size=${bytes.size}B sessionState=${state.value}")

        val client = sshLifecycle.sshClient
        if (client == null) {
            android.util.Log.w(tag, "abort: sshClient is null")
            return@withContext null
        }
        if (!client.isConnected) {
            android.util.Log.w(tag, "abort: client not connected")
            history.emitMsg(AgentMessage.Error(UUID.randomUUID().toString(), "SSH not connected — pull-down to retry"))
            return@withContext null
        }
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
        val remoteDir = "/tmp/sshai_uploads"
        val remotePath = "$remoteDir/$filename"

        android.util.Log.d(tag, "mkdir $remoteDir")
        val mkOk = sshLifecycle.execOnLive("mkdir -p $remoteDir")
        if (mkOk == null) {
            android.util.Log.w(tag, "mkdir failed: execOnLive returned null")
            history.emitMsg(AgentMessage.Error(UUID.randomUUID().toString(), "Could not create $remoteDir on the server"))
            return@withContext null
        }
        android.util.Log.d(tag, "mkdir ok: ${mkOk.take(80)}")

        val command = "cat > ${shellEscape(remotePath)} && echo SSHAI_OK || echo SSHAI_ERR_\$?"

        var sess: Session? = null
        try {
            onProgress(0f)
            android.util.Log.d(tag, "opening exec channel")
            sess = client.startSession()
            android.util.Log.d(tag, "channel opened, sending exec")
            val cmd = sess.exec(command)
            android.util.Log.d(tag, "exec sent, streaming bytes")

            val total = bytes.size
            val chunkSize = 64 * 1024
            var sent = 0
            var lastLogged = 0L
            while (sent < total) {
                val len = minOf(chunkSize, total - sent)
                cmd.outputStream.write(bytes, sent, len)
                sent += len
                if (total > 0) onProgress(sent.toFloat() / total)
                val now = System.currentTimeMillis()
                if (now - lastLogged > 500) {
                    android.util.Log.d(tag, "  sent=$sent/$total (${(sent * 100L / total)}%) elapsed=${now - t0}ms")
                    lastLogged = now
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
            if ((exit == null || exit == 0) && outText.contains("SSHAI_OK")) {
                onProgress(1f)
                return@withContext remotePath
            }
            val err = outText.lineSequence()
                .firstOrNull { it.startsWith("SSHAI_ERR_") }
                ?.removePrefix("SSHAI_ERR_")
                ?: ("exit=" + exit)
            android.util.Log.w(tag, "upload failed: $err")
            history.emitMsg(AgentMessage.Error(UUID.randomUUID().toString(), "Upload failed: $err"))
            return@withContext null
        } catch (t: Throwable) {
            android.util.Log.e(tag, "exception during upload", t)
            history.emitMsg(AgentMessage.Error(
                UUID.randomUUID().toString(),
                "Upload failed: ${t.message ?: t.javaClass.simpleName}"
            ))
            return@withContext null
        } finally {
            SilentlyTry.fired("SshAi-AgentSession", "close upload ssh session") { sess?.close() }
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
        val tag = "SshAi-Download"
        val client = sshLifecycle.sshClient
        if (client == null || !client.isConnected) {
            return@withContext AgentSession.DownloadOutcome.Failed("SSH not connected")
        }
        // Wait briefly for bootstrap, same logic as upload.
        var spent = 0
        while (state.value is SessionState.Bootstrapping && spent < 10_000) {
            delay(200); spent += 200
        }

        val escaped = shellEscapeRemotePath(remotePath)
        val total: Long = run {
            val out = sshLifecycle.execOnLive(
                "if [ -f $escaped ] && [ -r $escaped ]; then stat -c %s $escaped; else echo SSHAI_NOFILE; fi"
            ) ?: return@withContext AgentSession.DownloadOutcome.Failed("SSH exec failed")
            val trimmed = out.trim()
            if (trimmed.contains("SSHAI_NOFILE")) {
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
            SilentlyTry.fired("SshAi-AgentSession", "close download ssh session") { sess?.close() }
        }
    }

    /**
     * Allocate a PTY, exec `claude` interactively, type `/model\n`, read the
     * rendered menu for ~3 seconds, then kill the session. Returns the raw
     * output (includes ANSI escapes — caller strips). The CLI has no
     * non-interactive way to dump the model list, so PTY drive is the only
     * honest path to "what does /model actually say right now".
     */
    suspend fun probeModelMenu(): String? = withContext(Dispatchers.IO) {
        val client = sshLifecycle.sshClient ?: return@withContext null
        if (!client.isConnected) return@withContext null
        var sess: Session? = null
        try {
            sess = client.startSession()
            sess.allocateDefaultPTY()
            val cmd = sess.exec("bash -lc 'claude'")

            val sb = StringBuilder()
            val buf = ByteArray(8192)

            suspend fun readUntilQuiet(maxMs: Long, quietMs: Long) {
                val deadline = System.currentTimeMillis() + maxMs
                var lastByteAt = System.currentTimeMillis()
                while (System.currentTimeMillis() < deadline) {
                    if (cmd.inputStream.available() > 0) {
                        val n = cmd.inputStream.read(buf)
                        if (n > 0) {
                            sb.append(String(buf, 0, n, Charsets.UTF_8))
                            lastByteAt = System.currentTimeMillis()
                        }
                    } else {
                        if (System.currentTimeMillis() - lastByteAt > quietMs) return
                        delay(50)
                    }
                }
            }

            fun pendingConfirm(): Boolean {
                val tail = sb.toString().takeLast(1500).lowercase()
                return tail.contains("trust this folder") ||
                    tail.contains("safety check") ||
                    tail.contains("bypass permissions") ||
                    tail.contains("press enter") ||
                    tail.contains("enter to confirm") ||
                    tail.contains("yes,") ||
                    tail.contains("yes, ")
            }

            // Phase 1: let claude start and print whatever opening pages it has.
            readUntilQuiet(maxMs = 5_000, quietMs = 1_200)

            // Phase 1.5: dismiss any confirmation prompts (trust folder,
            // bypass-permissions warning, one-time release notes, etc).
            // Keep tapping Enter while the trailing output looks like a
            // prompt and new output is being generated. Bail after 5 loops.
            var loops = 0
            while (pendingConfirm() && loops < 5) {
                val before = sb.length
                android.util.Log.d("SshAi-Models", "confirm-prompt loop $loops, sending Enter")
                cmd.outputStream.write("\r".toByteArray())
                cmd.outputStream.flush()
                readUntilQuiet(maxMs = 3_000, quietMs = 800)
                if (sb.length == before) break  // no progress -> stop
                loops++
            }

            // Phase 2: send `/model\n`, then read the menu for up to 4 seconds.
            cmd.outputStream.write("/model\n".toByteArray())
            cmd.outputStream.flush()

            readUntilQuiet(maxMs = 4_000, quietMs = 700)

            // Phase 3: tear down — Esc to dismiss menu, then Ctrl+D / Ctrl+C.
            try {
                cmd.outputStream.write(byteArrayOf(0x1B))
                cmd.outputStream.flush()
                delay(150)
                cmd.outputStream.write(byteArrayOf(4, 3))
                cmd.outputStream.flush()
            } catch (_: Throwable) { /* best effort */ }
            sb.toString()
        } catch (t: Throwable) {
            android.util.Log.w("SshAi-Models", "PTY probe failed: ${t.message}", t)
            null
        } finally {
            SilentlyTry.fired("SshAi-AgentSession", "close PTY probe ssh session") { sess?.close() }
        }
    }

    /**
     * Cheap probe: does the file at the given absolute path exist on the
     * server right now? Used by the upload-dedupe path to confirm a cached
     * remote path is still live before skipping the actual upload.
     */
    suspend fun checkRemoteFileExists(remotePath: String): Boolean = withContext(Dispatchers.IO) {
        val out = sshLifecycle.execOnLive(
            "[ -f ${shellEscapeRemotePath(remotePath)} ] && echo SSHAI_EXISTS || echo SSHAI_GONE"
        )
        out?.contains("SSHAI_EXISTS") == true
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
            "echo \"SSHAI_SIZE=\$s\"; echo \"SSHAI_HASH=\$h\"; " +
            "else echo SSHAI_GONE; fi"
        val out = sshLifecycle.execOnLive(cmd) ?: return@withContext AgentSession.RemoteFileProbe.ProbeError
        if ("SSHAI_GONE" in out) return@withContext AgentSession.RemoteFileProbe.NotFound
        val size = Regex("SSHAI_SIZE=(\\d+)").find(out)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: return@withContext AgentSession.RemoteFileProbe.ProbeError
        val hash = Regex("SSHAI_HASH=([0-9a-fA-F]{64})").find(out)?.groupValues?.getOrNull(1)
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
