package ai.eight24family.conch.agent

import ai.eight24family.conch.agent.claude.renderClaudeTerminal
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import ai.eight24family.conch.ssh.startStreamSession
import net.schmizz.sshj.connection.channel.direct.Session
import java.util.concurrent.TimeUnit

/**
 * Drive an interactive `claude` over a PTY on [client] and capture the
 * `/model` menu and the `/effort` slider as RENDERED SCREEN SNAPSHOTS.
 *
 * Standalone (not tied to AgentSession) so BOTH callers share one driver:
 *  - the startup catalog warm-up (`ModelCatalogPrefetcher` riding the
 *    pooled client the moment a server connects), and
 *  - the in-chat refresh (`AgentSessionFileTransfer.probeModelMenu`).
 *
 * Returns plain screen text (snapshots joined with blank lines), NOT raw
 * bytes: the TUI paints with cursor-motion diffs and ERASES dialogs when
 * they close, so only screen-state snapshots taken while a dialog is
 * verified-open are trustworthy. The CLI has no non-interactive way to
 * dump either list, so PTY drive is the only honest path to "what does
 * the server's claude actually offer".
 *
 * The driver VERIFIES every step instead of fire-and-forget typing
 * (2026-06-10 lesson: claude loads its slash-command registry
 * asynchronously — typing `/model` + Enter before it's ready submits the
 * literal text as a chat prompt and no menu ever opens):
 *  1. type the command, wait, check the rendered screen shows the
 *     slash-autocomplete for it (UI ready); not there → wipe the
 *     composer (Ctrl+U), wait, retype — up to 3 attempts;
 *  2. press CR, check the dialog actually rendered (its header is on
 *     screen); not there → Esc, wipe, retry;
 *  3. snapshot the screen WHILE the dialog is open, then Esc.
 */
suspend fun probeClaudeMenuScreens(client: SSHClient): String? = withContext(Dispatchers.IO) {
    if (!client.isConnected) return@withContext null
    // PER-RUN cwd. It used to be a shared constant, and there are two independent
    // callers — the connect sweep (ModelCatalogPrefetcher) and chat open
    // (AgentSessionFileTransfer). The freshness gate only suppresses the second
    // AFTER a successful probe, so on a host where the probe keeps failing the two
    // overlap on every connect-then-open and each one's `finally` rm -rf's the
    // other's cwd mid-run — turning a recoverable failure into a permanent one.
    val probeCwd = "$PROBE_CWD_PREFIX${java.util.UUID.randomUUID()}"
    var sess: Session? = null
    try {
        // STREAM session, not a plain one: this probe holds its channel open
        // reading for many seconds, which is exactly the profile that starves on
        // a shared transport without autoExpand — the same contention that wedges
        // the turn reader (see [startStreamSession]; TURN-STREAM-AUTOEXPAND-1).
        sess = client.startStreamSession()
        // WIDE pty (not the 80×24 default): the menu's right column —
        // the resolved model names — gets cut off at 80 cols, which
        // starved the parser of half the rows' data (verified from the
        // raw frame log). 220 cols renders every row in full.
        sess.allocatePTY("xterm", 220, 50, 0, 0, emptyMap())
        // Launch in a THROWAWAY cwd. claude auto-saves an interactive session to
        // ~/.claude/projects/<cwd>/ — in the user's real cwd that left a junk
        // "/effort"-titled session in `claude --resume` on EVERY probe (user
        // 2026-06-25, furious: resume picker drowning in /effort entries). A
        // disposable /tmp cwd puts those in a project dir the user's resume never
        // shows, and the finally block deletes it. Model/effort lists are global
        // (creds in ~/.claude), so cwd doesn't change what the menus return.
        // Same full-scope preference as the turn commands — this probe IS the
        // picker's data source, so running it under the inference-only token is
        // exactly what made the menu demand credits for an included model.
        val authPrefix = AuthSelector.claudeFullScopePrefix()
        // ⚠ `${'$'}{authPrefix}` here used to emit the LITERAL TEXT `${authPrefix}`
        // (Kotlin's escape for a dollar sign), so the auth prefix was never
        // applied — the probe ran under whatever token the environment had,
        // which is exactly the inference-only setup-token case this prefix
        // exists to avoid. And `claude` was launched WITHOUT the PATH preamble
        // that every other probe carries, so on a host where npm installed it
        // under ~/.local/bin the command simply wasn't found: blank screen,
        // "autocomplete not ready" ×3, `extracted CLAUDE models: {}`, and a
        // picker stuck on whatever it had cached (user, 2026-07-29).
        val launch = RemoteEnv.PATH_PREAMBLE_INLINE + authPrefix +
            "mkdir -p $probeCwd 2>/dev/null; cd $probeCwd; exec claude"
        val cmd = sess.exec("bash -lc " + shQuoteProbe(launch))

        val sb = StringBuilder()
        val buf = ByteArray(8192)
        val tag = "SshAi-Models"

        /**
         * Read until the stream goes quiet for [quietMs], or [maxMs] elapses.
         *
         * ⚠ "Quiet" means IT STOPPED TALKING — never IT HASN'T STARTED YET. The
         * timer used to be armed at entry (`var lastByteAt = now()`), so a CLI
         * that had printed nothing yet satisfied the quiet condition on the first
         * pass. Phase 1 allowed 1.2 s of quiet; a node cold start plus claude's
         * OAuth bootstrap comfortably exceeds that, so the probe declared the
         * screen settled before a single byte arrived, raced through all three
         * `/model` attempts in ~4 s, and closed the channel. Downstream that is
         * indistinguishable from "this CLI has no models": blank screen →
         * `{}` → the picker silently keeps serving its old cached list (user,
         * 2026-07-29). [firstByteMs] therefore holds the quiet timer OFF until
         * something actually arrives.
         */
        suspend fun readUntilQuiet(maxMs: Long, quietMs: Long, firstByteMs: Long = 0L) {
            val start = System.currentTimeMillis()
            val deadline = start + maxMs + firstByteMs
            // MAX_VALUE ⇒ "not quiet" until the first byte lands.
            var lastByteAt = if (firstByteMs > 0L) Long.MAX_VALUE else start
            while (System.currentTimeMillis() < deadline) {
                if (cmd.inputStream.available() > 0) {
                    val n = cmd.inputStream.read(buf)
                    if (n > 0) {
                        sb.append(String(buf, 0, n, Charsets.UTF_8))
                        lastByteAt = System.currentTimeMillis()
                    }
                } else {
                    if (lastByteAt != Long.MAX_VALUE &&
                        System.currentTimeMillis() - lastByteAt > quietMs
                    ) return
                    delay(50)
                }
            }
        }

        fun screen(): String = renderClaudeTerminal(sb.toString())

        fun type(text: String) {
            cmd.outputStream.write(text.toByteArray())
            cmd.outputStream.flush()
        }

        fun key(vararg bytes: Byte) {
            cmd.outputStream.write(bytes)
            cmd.outputStream.flush()
        }

        fun pendingConfirm(): Boolean {
            val tail = screen().takeLast(1500).lowercase()
            // ONLY strings that appear in real MODAL prompts. "bypass
            // permissions" is the PERMANENT status line of the main UI in
            // yolo mode and must NOT count (it used to burn 15s/probe).
            return tail.contains("trust this folder") ||
                tail.contains("safety check") ||
                tail.contains("press enter") ||
                tail.contains("enter to confirm")
        }

        /**
         * Open a slash-command dialog, verified at every step.
         * @return true when [headerMarker] is on screen (dialog open).
         */
        /**
         * WAIT for the slash-autocomplete to list [command] on its own line
         * (composer + popup ⇒ ≥2 lines mention it). Before the registry loads
         * there is no popup, and CR would submit the text as a chat prompt.
         *
         * A WAIT, not a single check: the old code read for one quiet window
         * (~400 ms — the PTY's echo of the typed characters arms the timer
         * instantly) and then tested once. All three retries therefore landed
         * inside ~3 s and sampled the same instant of an ASYNCHRONOUS registry
         * load, which is precisely the `autocomplete not ready (attempt 0/1/2)`
         * triple in the field report.
         */
        suspend fun waitReady(command: String, budgetMs: Long): Boolean {
            val deadline = System.currentTimeMillis() + budgetMs
            while (System.currentTimeMillis() < deadline) {
                if (screen().lineSequence().count { it.contains(command) } >= 2) return true
                readUntilQuiet(maxMs = 1_200, quietMs = 250)
            }
            return false
        }

        suspend fun openDialog(command: String, headerMarker: String): Boolean {
            for (attempt in 0 until 3) {
                type(command)
                readUntilQuiet(maxMs = 2_000, quietMs = 400)
                // Budget is generous on purpose: this runs on the connect sweep
                // and on chat open, never on the send path.
                if (!waitReady(command, budgetMs = 10_000)) {
                    android.util.Log.d(
                        tag,
                        "probe: $command autocomplete not ready (attempt $attempt, screen=${screen().length}B)",
                    )
                    key(0x15) // Ctrl+U — wipe the composer
                    readUntilQuiet(maxMs = 2_500, quietMs = 600)
                    continue
                }
                key(0x0D) // CR — LF would insert a newline instead (2.1.x composer)
                readUntilQuiet(maxMs = 5_000, quietMs = 700)
                if (screen().contains(headerMarker)) return true
                android.util.Log.d(tag, "probe: $command dialog did not open (attempt $attempt)")
                key(0x1B) // Esc — abort whatever happened instead
                readUntilQuiet(maxMs = 1_500, quietMs = 400)
                key(0x15)
                readUntilQuiet(maxMs = 1_000, quietMs = 400)
            }
            return false
        }

        // Phase 1: let claude start and settle. The first-byte budget is
        // deliberately generous — node cold start on a small VPS, plus claude's
        // startup Bootstrap call, routinely runs past 10 s. Costs nothing when
        // the CLI is warm: the wait ends the moment output begins.
        readUntilQuiet(maxMs = 6_000, quietMs = 1_200, firstByteMs = 25_000)

        // DID IT EVEN START? A probe that never got a byte, or got only a shell
        // error, is INDISTINGUISHABLE downstream from "this CLI offers no
        // models" — every later step just reports "not ready" and the caller
        // records an empty catalog. Say it out loud, with the raw bytes, so the
        // next failure is diagnosable instead of silent (user, 2026-07-29:
        // `extracted CLAUDE models: {}` with a blank rendered screen, and no way
        // to tell a missing binary from a changed menu format).
        if (sb.isBlank()) {
            android.util.Log.w(tag, "probe: claude produced NO OUTPUT — not started (PATH? auth? channel?)")
            return@withContext null
        }
        val startupTail = screen().trim()
        if (startupTail.isBlank() || NOT_STARTED_MARKERS.any { startupTail.contains(it, ignoreCase = true) }) {
            android.util.Log.w(
                tag,
                "probe: claude did not reach its UI — raw=${sb.length}B first200=" +
                    sb.toString().take(200).replace("\n", "\\n"),
            )
            return@withContext null
        }

        // Phase 1.5: dismiss real modal prompts (trust folder etc).
        var loops = 0
        while (pendingConfirm() && loops < 3) {
            android.util.Log.d(tag, "confirm-prompt loop $loops, sending Enter")
            val before = sb.length
            key(0x0D)
            readUntilQuiet(maxMs = 3_000, quietMs = 800)
            if (sb.length == before) break
            loops++
        }

        // Whether THIS CLI exposes `/effort` — decided from the MAIN
        // screen now (status line "● high · /effort"), not from a later
        // snapshot. The /model menu footer says "High effort" WITHOUT
        // the slash, so gating on the menu snapshot missed it and the
        // slider never opened → reasoning levels stayed on the
        // hardcoded ladder (2026-06-10).
        val hasEffortCmd = screen().contains("/effort")
        android.util.Log.d(tag, "probe: hasEffortCmd=$hasEffortCmd")

        val snapshots = StringBuilder()

        // Phase 2: /model menu — snapshot WHILE it's open.
        val modelOpen = openDialog("/model", "Select model")
        android.util.Log.d(tag, "probe: model menu open=$modelOpen")
        val modelScreen = screen()
        // DIAG (2026-06-13): dump the rendered /model menu so we can see
        // exactly how the CLI marks an unavailable model (Fable 5 suspended)
        // and grey it out in our picker to match the CLI. Gated by Logx —
        // ships silent unless built with -PverboseLogs.
        ai.eight24family.conch.util.Logx.d(tag) {
            "probe: /model rendered ↓↓↓\n" +
                modelScreen.lineSequence().joinToString("\n") { "MENU| $it" }
        }
        snapshots.append(modelScreen)
        key(0x1B) // close the menu
        readUntilQuiet(maxMs = 1_500, quietMs = 400)

        // Phase 3: /effort slider — open it whenever the main screen
        // advertised the command. openDialog verifies + bails safely,
        // so a CLI that lost the command can't get the text submitted.
        if (hasEffortCmd) {
            val effortOpen = openDialog("/effort", "Effort")
            android.util.Log.d(tag, "probe: effort slider open=$effortOpen")
            if (effortOpen) {
                snapshots.append("\n\n").append(screen())
                key(0x1B)
                readUntilQuiet(maxMs = 1_000, quietMs = 400)
            }
        }

        // Teardown: Ctrl+D / Ctrl+C.
        try {
            key(0x04, 0x03)
        } catch (_: Throwable) { /* best effort */ }
        snapshots.toString()
    } catch (t: Throwable) {
        android.util.Log.w("SshAi-Models", "PTY probe failed: ${t.message}", t)
        null
    } finally {
        SilentlyTry.fired("SshAi-AgentSession", "close PTY probe ssh session") { sess?.close() }
        // Delete the throwaway cwd + any claude session/project dir it produced,
        // so the probe never litters `claude --resume`. Glob matches whatever
        // claude encoded the cwd into (slashes/dots → dashes); the token is
        // specific enough to never touch a real project.
        SilentlyTry.fired("SshAi-Models", "purge probe session") {
            if (client.isConnected) client.startSession().use { cs ->
                // Our own cwd by exact path, plus a wildcard sweep so strays from a
                // killed run still get cleaned. The wildcard never touches a live
                // run's cwd because claude only writes the project dir on exit.
                cs.exec("bash -lc 'rm -rf $probeCwd ~/.claude/projects/*conch-modelprobe* 2>/dev/null'")
                    .join(8, TimeUnit.SECONDS)
            }
        }
    }
}

/** Throwaway cwd for the menu probe — keeps claude's auto-saved probe session
 *  out of the user's real project's `--resume` picker (deleted after each run). */
private const val PROBE_CWD_PREFIX = "/tmp/.conch-modelprobe-"

private fun shQuoteProbe(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/** Shell/CLI failures that mean the TUI never came up. Distinguishing these from
 *  "the menu format changed" is the whole point — the first is our bug, the
 *  second is the parser's. */
private val NOT_STARTED_MARKERS = listOf(
    "command not found",
    "No such file or directory",
    "Permission denied",
    "unbound variable",
    "cannot execute",
)
