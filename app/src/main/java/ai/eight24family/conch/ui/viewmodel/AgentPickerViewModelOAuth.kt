package ai.eight24family.conch.ui.viewmodel

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * OAuth login pipeline pulled out of [AgentPickerViewModel].
 *
 * Owns three CLI-specific OAuth flows:
 *
 *  - **Claude — the real `/login`, driven through the TUI.** We answer
 *    the wizard's stops (trust → Enter, composer → type "/login",
 *    method menu → Enter) and the user pastes the OOB code; the CLI
 *    writes the full-scope `~/.claude/.credentials.json` that terminal
 *    and app alike read. NOT `setup-token`: its inference-only env
 *    token left the server's own interactive claude saying "Not logged
 *    in" and broke /model + plan visibility (2026-08-18).
 *  - **Codex — moltbot pattern.** `BROWSER=true codex login` listens
 *    on `localhost:1455`; user pastes the post-redirect URL back into
 *    the dialog and [submitCodexCallback] curls it server-side.
 *  - **Gemini — OOB paste-code (Claude-like).** `gemini --skip-trust`
 *    with pre-configured `~/.gemini/settings.json` lands on Google's
 *    hosted `codeassist.google.com/authcode` page; user copies the
 *    bare code into the dialog and [submitOAuthCode] types it
 *    byte-by-byte into the CLI's stdin.
 *
 * **Invariants preserved here:**
 *
 *  - Claude code is typed **byte-by-byte with a 15 ms delay** plus
 *    `\r` to bypass the bracketed-paste detection that drops blob
 *    pastes (Anthropic GitHub #47745 "not planned").
 *  - PTY is mandatory with **1000 cols** — long OAuth URLs (400+ chars)
 *    wrap and break otherwise.
 *  - Known-broken-install patterns ("missing optional dependency",
 *    "cannot find module", etc.) trigger silent reinstall + re-launch.
 *    The user never sees the underlying error — just "Repairing…".
 */
/** Append one concrete step to the login dialog's `\n`-joined step trail (the
 *  small log shown under the "signing in" spinner). Dedups a repeat of the last
 *  step and keeps only the last few so it never grows into a wall of text.
 *  Shared with [AgentPickerViewModel.onLoginSuccess] (same package). */
internal fun appendLoginStep(trail: String, step: String): String {
    val lines = trail.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
    if (lines.lastOrNull() != step) lines.add(step)
    return lines.takeLast(4).joinToString("\n")
}

internal class AgentPickerViewModelOAuth(
    private val scope: CoroutineScope,
    private val serverId: String,
    private val loginRequestMut: MutableStateFlow<AgentPickerViewModel.LoginRequest?>,
    /** Triggers a status refresh after OAuth completes. */
    private val refresh: (Boolean) -> Job,
    /** Suspend-form install entry — used by the silent auto-recover
     *  branch when we see a broken-install signature in the CLI's
     *  stdout. */
    private val doInstall: suspend (Agent, Boolean) -> Unit,
    /** Shell-escape helper shared with the API-key path. */
    private val shellEscape: (String) -> String,
    /** Fired after a successful OAuth login. SUSPEND — it captures the account
     *  and AWAITS the run-state (subscription) probe, so the completion below can
     *  hold the "signing in" animation up until the row is ready before closing. */
    private val onLoginSuccess: suspend (Agent) -> Unit = {},
) {

    // ⛔ THE LOGIN PROCESS IS PROCESS-GLOBAL, LIKE THE DIALOG IT BACKS.
    // These live in the companion (see it below) for the same reason
    // [AgentPickerViewModel.activeLogin] does: there is only ever ONE login
    // proc, but there are MANY coordinator instances — the Agents overview
    // builds a picker VM (and with it one of these) PER SERVER, and the
    // full-screen picker adds another. When the handle was an instance field,
    // a submit routed through any instance other than the one that launched
    // the proc found `stdin=NULL` and the code went nowhere: pressing the
    // button did nothing, twice in a row, exactly as reported (measured on
    // the phone 2026-08-18: proc prompting at 21:24:10, `stdin=NULL` at
    // 21:24:29 — one attempt, one process, no race). Companion state makes
    // every instance's submit/cancel reach the one live proc.
    //
    // The GENERATION GUARD is still needed on top: every attempt's `finally`
    // used to null the handle unconditionally, so a previous attempt
    // finishing its teardown AFTER a new one had stored its own handle wiped
    // the live one. `startOAuthLogin` cancels the previous job, and the retry
    // button makes that ordinary. An attempt may only tear down the handle it
    // put there — and with the counter in the companion, generations stay
    // unique across instances too.

    /**
     * Start the OAuth / device-code login flow for [agent] over the
     * pooled SSH client.
     *
     * Per-CLI flow:
     *   - **Claude:** the interactive `/login` driven over our PTY (see
     *     the choreography in the read loop). The old claim that /login
     *     "needs a localhost callback" is outdated: 2.1.234's login is
     *     the same hosted-page paste-code dance as setup-token, just
     *     with full scopes and a credentials file at the end (verified
     *     live, 2026-08-18).
     *   - **Codex:** `codex login` — device-code, ChatGPT-Plus path.
     *   - **Gemini:** `gemini auth` — device-code-ish.
     *
     * **PTY is mandatory.** All three CLIs are TUIs and detect "no
     * terminal" by `isatty(stdout) == false`. Without a PTY they
     * just exit (or block) without printing anything — which is
     * exactly the "silent log" symptom we saw on the first try.
     * sshj's `allocateDefaultPTY()` provisions a 80×24 xterm before
     * `exec`; all subsequent reads pick up TUI output as if there
     * were a real terminal.
     *
     * The reader strips a few common ANSI escape sequences so the
     * URL/code regexes don't choke on color codes.
     *
     * Side-poller checks the credentials file every 3 s and closes
     * the dialog the moment it appears.
     */
    /**
     * ⛔ A LOGIN STREAM CARRIES SECRETS. NEVER LOG IT RAW.
     *
     * `claude setup-token` PRINTS the long-lived token to stdout — the CLI masks
     * it in its own interactive display, then prints it in the clear on the token
     * line — and this coordinator logged every stdout line verbatim. So a working
     * `sk-ant-oat01-…` ended up in the device log, where anything with log access
     * can read it, our own bridge can capture it, and it outlives the sign-in
     * (measured on the user's phone, 2026-08-18). API keys pasted into the key
     * path have the same shape of exposure.
     *
     * The stream still has to be logged — it is the only way to debug a sign-in —
     * so the SECRETS go and the structure stays: a redacted line keeps its length
     * and prefix so "the token line arrived, 108 bytes" is still visible, while
     * the value is not.
     */
    private fun redactSecrets(line: String): String =
        SECRET_RX.replace(line) { m ->
            val v = m.value
            v.take(SECRET_KEEP) + "…<redacted ${v.length}B>"
        }

    /**
     * Lines that are the CLI TALKING TO A TERMINAL, not to the user.
     *
     * The dialog was showing the raw stdout tail, so it rendered the Claude Code
     * ASCII logo, the spinner's individual frames, dotted rules, the version
     * banner and words with their spaces collapsed.
     *
     * Kept deliberately conservative in the other direction: a line has to look
     * like decoration to be dropped. If a future CLI release words a real message
     * differently it still gets through, because the test is "is this mostly not
     * letters", never a list of known-good strings. The LOG still receives every
     * line (redacted) — this is display only.
     */
    private fun isNoiseLine(line: String): Boolean {
        val t = line.trim()
        if (t.length < 4) return true
        // Spinner frames and rules: no letters at all.
        if (t.none { it.isLetter() }) return true
        // Box/block drawing and shaded cells — the logo.
        if (t.any { it.code in 0x2580..0x259F || it.code in 0x2500..0x257F }) return true
        // Mostly punctuation/dots with a stray glyph: a separator, not a sentence.
        val letters = t.count { it.isLetter() }
        if (letters * 3 < t.length) return true
        // The banner. Informative once, noise in a five-line window.
        if (t.startsWith("WelcometoClaudeCode") || t.startsWith("Welcome to Claude Code")) return true
        return false
    }

    /**
     * Lines that ANSWER something the user is waiting on, rather than narrate.
     *
     * Deliberately shape-based, not a list of exact strings: these come from the
     * CLI and change between releases, and the cost of guessing wrong here is one
     * extra line pinned in a dialog — while the cost of missing one is the user
     * watching a check happen and never learning its result.
     */
    private fun isVerdictLine(line: String): Boolean {
        val t = line.trim()
        if (t.length < 3) return false
        // Claude account with subscription · Pro, Max, Team or Enterprise"
        // carries the word «subscription» and got latched into the dialog as a
        // verdict. A numbered option (with or without the ❯ cursor) is never a
        // verdict.
        if (Regex("^[❯>]?\\s*\\d+\\.").containsMatchIn(t)) return false
        if (t.startsWith("✓") || t.startsWith("✗") || t.startsWith("×")) return true
        val l = t.lowercase()
        return l.contains("subscription") || l.contains("not eligible") ||
            l.contains("expired") || l.contains("already logged in") ||
            l.startsWith("error") || l.contains("failed")
    }

    fun startOAuthLogin(agent: Agent) = startOAuthLogin(agent, internalRetry = false)

    private fun startOAuthLogin(agent: Agent, internalRetry: Boolean) {
        loginJob?.cancel()
        // This attempt's identity. Everything below tears down only what IT owns.
        val myGen = ++loginGen
        // A verdict latched by a PREVIOUS login must not haunt this one's dialog.
        latchedVerdict = ""
        // A human pressing the button starts a fresh retry budget.
        if (!internalRetry) loginAutoRetries = 0
        loginJob = scope.launch(Dispatchers.IO) {
            val tag = "SshAi-AgentPicker"
            // ⚠ NEVER VANISH. This was a bare `return@launch` when the pool had no
            // live transport: the method picker has ALREADY closed by the time we
            // get here, so tapping [ OAuth ] shut the dialog and produced nothing
            // at all - no URL, no error, no hint. A sign-in cannot happen without
            // SSH, so ASK for SSH instead of giving up: try to come up silently
            // (the same path the chat uses on open), and if that cannot, SAY so on
            // the dialog the user just opened.
            var pooled = ServiceLocator.sshConnectionPool.peek(serverId)
            if (pooled == null) {
                loginRequestMut.value =
                    AgentPickerViewModel.LoginRequest(agent, serverId, null, null, "connecting to the server…")
                SilentlyTry.fired(tag, "silent connect before login") {
                    ServiceLocator.sshConnectionPool.connectAllPossibleSilently()
                }
                pooled = ServiceLocator.sshConnectionPool.peek(serverId)
            }
            if (pooled == null) {
                android.util.Log.w(tag, "startOAuthLogin: no transport for $serverId - telling the user")
                loginRequestMut.value = AgentPickerViewModel.LoginRequest(
                    agent, serverId, null, null,
                    "No connection to this server yet — connect it (tap the server), then retry.",
                    stalled = true,
                )
                return@launch
            }
            loginRequestMut.value = AgentPickerViewModel.LoginRequest(agent, serverId, null, null, "starting…")
            // Gemini diverges — it has no `gemini auth login` subcommand;
            // OAuth only works through the interactive TUI's `/auth`
            // command. We drive the TUI through PTY + capture the URL
            // via a BROWSER=script hack. See [handleGeminiLogin].
            if (agent == Agent.GEMINI) {
                handleGeminiLogin(pooled, myGen)
                return@launch
            }
            // Declared OUTSIDE the try{} so the finally can see it
            // (Kotlin scopes try/catch/finally independently). Flipped
            // true if we detect a known-broken-install pattern in the
            // CLI's stdout — the finally then skips its cleanup so the
            // dialog stays up during the silent reinstall + relaunch.
            var recovering = false
            // Set when the read loop ended with NO real login (channel died / no
            // credential) — the finally then KEEPS the error dialog up and never
            // calls onLoginSuccess, so a phantom account is never saved.
            var loginFailed = false
            // Set when this attempt died underneath the user and a fresh one is
            // being launched — the finally must not clear the dialog it hands over.
            var autoRetrying = false
            try {
                // Each CLI's sign-in command lives on its SPEC (with the
                // rationale for the exact form — moltbot callback vs device
                // code vs TUI wizard). A null means the CLI has no sign-in we
                // can drive on a machine with no browser: we say so instead of
                // launching something that would hang until the watchdog.
                val cmd = ai.eight24family.conch.agent.spec.AgentSpecRegistry[agent].oauthLoginCommand
                if (cmd == null) {
                    android.util.Log.w(tag, "startOAuthLogin: $agent has no headless sign-in — steering to API key")
                    loginRequestMut.value = AgentPickerViewModel.LoginRequest(
                        agent, serverId, null, null,
                        "${agent.displayName} has no sign-in that works without a browser on the server. " +
                            "Add an API key instead — long-press this row to switch method.",
                        stalled = true,
                    )
                    return@launch
                }
                // RemoteEnv owns the PATH story — a hand-rolled copy here had
                // drifted to a subset (no homebrew/volta/bun/asdf/pnpm/snap),
                // exactly the failure RemoteEnv's header warns about.
                val loginShellPrefix = ai.eight24family.conch.agent.RemoteEnv.PATH_PREAMBLE_INLINE + " "
                val fullCmd = ai.eight24family.conch.agent.RemoteEnv.portable(
                    "bash -lc " + shellEscape(loginShellPrefix + cmd),
                )
                android.util.Log.d(tag, "login($agent) — running with PTY: $fullCmd")
                // Baseline = the server's clock NOW. Two guards ride on it: (1) the
                // creds poller ignores a pre-existing (already-logged-in) file, and
                // (2) at completion we REFUSE to save an account unless a fresh
                // credential landed — so a proc that just DIES (channel dropped, no
                // URL shown) is never mistaken for a successful sign-in.
                val loginSince = SilentlyTry.loggedOrElse(tag, "auth baseline time", 0L) {
                    pooled.startSession().use { s ->
                        val p = s.exec("date +%s")
                        val out = p.inputStream.bufferedReader().readText().trim()
                        p.join(5, java.util.concurrent.TimeUnit.SECONDS)
                        out.toLongOrNull() ?: 0L
                    }
                }.takeIf { it > 0L } ?: (System.currentTimeMillis() / 1000)
                pooled.startSession().use { sess ->
                    // PTY is mandatory — without this every TUI just
                    // shuts up (isatty(stdout) == false → bail).
                    //
                    // Width = 1000. OAuth URLs at Claude (and OpenAI /
                    // Google) are 400+ chars with state / code_challenge /
                    // scope / redirect_uri. Default 80–120 cols wrap them
                    // across multiple "lines"; `readLine()` then gives us
                    // the prefix only. The user opens the truncated URL
                    // and Claude rejects it with "Missing redirect_uri
                    // parameter" (which is exactly what happened on the
                    // first try). 1000 cols guarantees any plausible
                    // OAuth URL fits on one line of the PTY.
                    runCatching {
                        sess.allocatePTY(
                            "xterm",
                            1000, 40, 0, 0,
                            java.util.Collections.emptyMap(),
                        )
                    }.onFailure {
                        android.util.Log.w(tag, "  allocatePTY failed: ${it.message}")
                    }
                    val proc = sess.exec("$fullCmd 2>&1")
                    // Expose stdin so [submitOAuthCode] can paste the
                    // user-supplied authorisation code back into the
                    // CLI when the OOB device-code prompt fires.
                    loginProcStdin = proc.outputStream
                    stdinGen = myGen
                    val reader = proc.inputStream.bufferedReader()
                    val recent = ArrayDeque<String>()
                    val fullBuf = StringBuilder()
                    // Conservative URL match (FALLBACK only — the primary source
                    // is the OSC-8 target, see osc8UrlRe below). Stops at
                    // whitespace, quote, angle bracket, backtick — AND at `]`,
                    // BEL, ESC so a leftover OSC-8 closer / escape can't glue onto
                    // the URL. Everything else (`&`, `=`, `?`, `%`) is fair game.
                    val urlRe = Regex("https?://[^\\s'\"<>`\\]]+")
                    val codeRe = Regex("""\b[A-Z0-9]{4}-[A-Z0-9]{4,8}\b""")
                    // OOB paste-prompt detection. Claude's `setup-token`
                    // prints "Paste code here if prompted:" once the
                    // browser-side dance is ready for the user to ship
                    // the code back. Codex's `--no-browser` and Gemini
                    // both use similar phrasing. Match conservatively
                    // on the key tokens so we don't fire on every line.
                    val pastePromptRe = Regex(
                        "(?i)(paste|enter).{0,40}(code|token|key|here)"
                    )
                    // Known-broken-install signatures. When we see one,
                    // we DO NOT show the user an error — instead the
                    // dialog stays up showing "fixing…" while we reinstall
                    // and then we transparently re-launch the OAuth flow.
                    //
                    // Triggers caught so far:
                    //   - "Missing optional dependency @openai/codex-…"
                    //     (the symptom from the user's broken Codex)
                    //   - "Cannot find module …"
                    //   - "Reinstall <cli>: npm install -g …"  (the CLI's
                    //     own bail-out hint)
                    //   - "command not found" / "no such file or directory"
                    val brokenInstallRe = Regex(
                        "(?i)(missing optional dependency|cannot find module|reinstall .*: npm install|command not found|no such file or directory)"
                    )
                    // ── /login TUI choreography (Claude only) ──
                    // The wizard's three stops before the URL, each answered
                    // once. Detection is on the ESCAPE-STRIPPED line with spaces
                    // removed: ink lays words out with cursor jumps, so the
                    // text arrives glued («Selectloginmethod»).
                    var ansTrust = false
                    var ansLoginTyped = false
                    var ansMethod = false
                    var ansTheme = false
                    suspend fun key(s: String, perByteMs: Long = 0L) {
                        runCatching {
                            for (b in s.toByteArray(Charsets.UTF_8)) {
                                proc.outputStream.write(byteArrayOf(b))
                                proc.outputStream.flush()
                                if (perByteMs > 0) delay(perByteMs)
                            }
                        }.onFailure {
                            android.util.Log.w(tag, "login($agent) key write failed: ${it.message}")
                        }
                    }
                    // COMPREHENSIVE terminal-escape stripper. Claude's
                    // `setup-token` renders via ink: SGR colours, cursor-motion
                    // CSI (it lays words out with `ESC[NG` column jumps INSTEAD of
                    // spaces), a Unicode spinner, AND OSC-8 hyperlinks
                    // (`ESC]8;id=…;<URL>BEL<visible>ESC]8;;BEL`). The old CSI-only
                    // regex left the `]8;…` and bare ESC as literal garbage in the
                    // dialog and let the URL match hoover up BOTH the target and
                    // the width-clipped visible copy (the "horrible log" the user
                    // saw). Alternation order = longest/most-specific first: OSC
                    // (BEL- or ST-terminated), CSI, charset designators, any other
                    // 2-char ESC, then stray control bytes (TAB/newline kept).
                    val esc = Char(27).toString()
                    val bel = Char(7).toString()
                    val termEscapeRe = Regex(
                        esc + "\\][^" + bel + esc + "]*(?:" + bel + "|" + esc + "\\\\)" +
                            "|" + esc + "\\[[0-?]*[ -/]*[@-~]" +
                            "|" + esc + "[()*+][0-9A-Za-z]" +
                            "|" + esc + "[0-~]" +
                            "|[\\x00-\\x08\\x0B-\\x1F\\x7F]"
                    )
                    // The OAuth URL lives, verbatim and COMPLETE, as the OSC-8
                    // hyperlink TARGET. Cleanest source - the on-screen visible text
                    // is only a width-clipped fragment that changes every frame.
                    val osc8UrlRe = Regex(esc + "\\]8;[^;]*;(https?://[^" + bel + esc + "]+)")
                    // Spinner-frame / decoration-only lines (a lone glyph, a bare
                    // `>` prompt caret, punctuation) — kept OUT of the rawTail we
                    // show, so the dialog's status line isn't a flickering glyph.
                    val noiseLineRe = Regex("^[^\\p{L}\\p{N}]{0,3}$")
                    // `loginSince` is captured above the session block (visible to
                    // both the poller here and the completion check after the loop).
                    val pollJob = launch {
                        while (kotlinx.coroutines.currentCoroutineContext()[Job]?.isActive == true) {
                            delay(3_000)
                            val authed = checkAuthOnly(pooled, agent, loginSince)
                            if (authed) {
                                android.util.Log.d(tag, "login($agent) — credentials detected, closing")
                                SilentlyTry.fired("SshAi-AgentPicker", "close login proc on auth") { proc.close() }
                                return@launch
                            }
                        }
                    }
                    // STALL WATCHDOG. readLine blocks forever on a half-open
                    // socket, so a transport that dies mid-login left the dialog
                    // spinning with no verdict for minutes (2026-08-22). Silence
                    // past the threshold (or a dead transport) force-closes the
                    // proc — the read loop unblocks into the failure path below,
                    // which auto-retries or says why. The clock EXCLUDES the
                    // paste wait: a user hunting for their code is not a stall.
                    val lastStdoutMs = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
                    val stallJob = launch {
                        while (kotlinx.coroutines.currentCoroutineContext()[Job]?.isActive == true) {
                            delay(5_000)
                            val awaitingUser = loginRequestMut.value?.awaitingPaste == true &&
                                loginRequestMut.value?.submitted != true
                            if (awaitingUser) { lastStdoutMs.set(System.currentTimeMillis()) ; continue }
                            val idle = System.currentTimeMillis() - lastStdoutMs.get()
                            val transportDead = runCatching { !pooled.isConnected }.getOrDefault(true)
                            if (transportDead || idle > LOGIN_STALL_MS) {
                                android.util.Log.w(tag, "login($agent) watchdog: transportDead=$transportDead idle=${idle}ms — closing proc")
                                SilentlyTry.fired("SshAi-AgentPicker", "close login proc on stall") { proc.close() }
                                return@launch
                            }
                        }
                    }
                    while (true) {
                        val rawLine = try { reader.readLine() } catch (e: Throwable) {
                            android.util.Log.w(tag, "login($agent) read threw: ${e.javaClass.simpleName}: ${e.message}"); null
                        }
                        if (rawLine == null) {
                            // The login CLI's stdout hit EOF — it EXITED (or the SSH
                            // exec channel died). For a paste-flow login (Claude
                            // setup-token) this happening BEFORE the user pastes is
                            // the "Login session ended / submit does nothing" bug.
                            // Log why so it isn't invisible: the CLI stays alive on a
                            // real PTY, so an early EOF means the channel/proc was
                            // killed under us (pooled-transport activity, not the CLI).
                            android.util.Log.w(
                                tag,
                                "login($agent) read loop EOF — proc exited (exitStatus=${
                                    runCatching { proc.exitStatus }.getOrNull()
                                }), awaitingPaste=${loginRequestMut.value?.awaitingPaste}",
                            )
                            break
                        }
                        lastStdoutMs.set(System.currentTimeMillis())
                        val line = termEscapeRe.replace(rawLine, "").trim()
                        if (line.isEmpty()) continue
                        // Spinner-frame / decoration-only lines stay OUT of the
                        // rawTail we SHOW (they'd flicker as a lone glyph), but they
                        // still flow through URL / paste / token detection below.
                        if (!noiseLineRe.matches(line)) {
                            recent.addLast(line)
                            if (recent.size > 20) recent.removeFirst()
                        }
                        // Accumulate buffer for URL search **with a \n
                        // separator between lines**. Without it, the URL
                        // regex `[^\s'"<>]+` ran past the URL boundary and
                        // hoovered up the next line ("Pastecodehereif
                        // prompted>") — every chr is non-whitespace once
                        // Claude's TUI strips its own spaces via cursor
                        // positioning. Result: user opens URL with
                        // garbage tacked onto `state=`, Anthropic
                        // silently accepts but the later code exchange
                        // fails. The \n separator forces a hard stop.
                        // Cap at 64KB so a stuck CLI can't OOM us.
                        fullBuf.append(line).append('\n')
                        if (fullBuf.length > 65_536) {
                            fullBuf.delete(0, fullBuf.length - 32_768)
                        }
                        android.util.Log.d(tag, "login($agent) stdout: ${redactSecrets(line)}")
                        // Known-broken-install detection — silently bail
                        // out of the read loop and trigger auto-recovery
                        // (reinstall + re-launch login) AFTER the loop.
                        // We keep the dialog up showing "Repairing…" so
                        // the user never sees the underlying ugly error.
                        if (brokenInstallRe.containsMatchIn(line) && !recovering) {
                            recovering = true
                            android.util.Log.w(tag, "broken install detected for $agent — auto-recover")
                            val cur = loginRequestMut.value
                            if (cur != null) {
                                loginRequestMut.value = cur.copy(
                                    url = null,
                                    code = null,
                                    awaitingPaste = false,
                                    rawTail = "Repairing installation…",
                                )
                            }
                            SilentlyTry.fired("SshAi-AgentPicker", "close login proc on broken-install") { proc.close() }
                            break
                        }
                        // ── /login wizard stops (Claude TUI). Each answered once;
                        // anything unrecognized just streams by. ──
                        if (agent == Agent.CLAUDE) {
                            val flat = line.replace(" ", "").lowercase()
                            when {
                                // Workspace-trust gate: "Yes, I trust this folder"
                                // is preselected — Enter. The chat itself runs in
                                // this same folder, so the trust is one the user's
                                // own use already implies.
                                !ansTrust && flat.contains("itrustthisfolder") -> {
                                    ansTrust = true
                                    delay(250); key("\r")
                                    android.util.Log.d(tag, "login(CLAUDE) — trust prompt → Enter")
                                }
                                // Virgin-install theme picker: accept the default.
                                !ansTheme && flat.contains("choosethetextstyle") -> {
                                    ansTheme = true
                                    delay(250); key("\r")
                                    android.util.Log.d(tag, "login(CLAUDE) — theme picker → Enter")
                                }
                                // Composer is up (footer hints render) → type the
                                // slash command. Slowly: ink's composer drops blob
                                // pastes, keystrokes pass (same bug family as the
                                // paste prompt, GitHub #47745).
                                !ansLoginTyped && (flat.contains("shift+tab") || flat.contains("forshortcuts")) -> {
                                    ansLoginTyped = true
                                    delay(400)
                                    key("/login", perByteMs = 120)
                                    delay(500); key("\r")
                                    android.util.Log.d(tag, "login(CLAUDE) — typed /login")
                                }
                                // Method menu: option 1 (subscription) is
                                // preselected — Enter.
                                !ansMethod && ansLoginTyped && flat.contains("selectloginmethod") -> {
                                    ansMethod = true
                                    delay(300); key("\r")
                                    android.util.Log.d(tag, "login(CLAUDE) — method menu → Enter (subscription)")
                                }
                            }
                        }
                        // Copilot's device flow can pause on a gh-style
                        // "Press Enter to open github.com/login/device…"
                        // gate. There is no browser here — answer Enter so
                        // the CLI proceeds to polling; the URL + code are
                        // already captured for the dialog.
                        if (agent == Agent.COPILOT &&
                            line.contains("press enter", ignoreCase = true)
                        ) {
                            delay(200); key("\r")
                            android.util.Log.d(tag, "login(COPILOT) — press-enter gate → Enter")
                        }
                        // URL extraction. PRIMARY source: the OSC-8 hyperlink
                        // TARGET on the RAW line (`ESC]8;id=…;<URL>BEL`) — that's
                        // the complete, verbatim URL. The on-screen visible text is
                        // only a width-clipped fragment (and duplicated per frame),
                        // so the buffer regex is a FALLBACK for CLIs that don't emit
                        // OSC-8 (take the longest match there).
                        val urlMatch = osc8UrlRe.find(rawLine)?.groupValues?.getOrNull(1)
                            ?: urlRe.findAll(fullBuf).maxByOrNull { it.value.length }?.value
                        val code = codeRe.find(line)?.value
                        // Once the CLI prints a paste-prompt we flip
                        // `awaitingPaste = true` permanently for this
                        // session — the prompt is one-shot but the UI
                        // must keep the input field up until the user
                        // submits.
                        //
                        // **Codex moltbot pattern:** we don't wait for
                        // a paste prompt at all — the moment we see the
                        // OAuth URL we KNOW the user will need to come
                        // back with the callback URL, so we open the
                        // paste field immediately. `callbackMode = true`
                        // tells the dialog to show "Paste callback URL"
                        // (not "Paste authorization code") and routes
                        // submit to `submitCodexCallback` instead of
                        // stdin-typing.
                        val pasteNow = pastePromptRe.containsMatchIn(line)
                        val cur = loginRequestMut.value ?: continue
                        val nextUrl = when {
                            urlMatch == null -> cur.url
                            cur.url == null -> urlMatch
                            urlMatch.length >= cur.url.length -> urlMatch
                            else -> cur.url
                        }
                        // Codex AND Gemini both use the moltbot pattern
                        // (localhost callback + we curl it server-side).
                        // Claude does NOT — it uses stdin typing.
                        val isCallbackAgent = agent == Agent.CODEX || agent == Agent.GEMINI
                        val callbackNow = isCallbackAgent && nextUrl != null
                        // ⚠ A VERDICT MUST NOT SCROLL AWAY. `recent` is a rolling
                        // window of the CLI's stdout, so the line the user is
                        // actually waiting on — "Checking subscription…" and
                        // whatever it resolved to — was pushed out by the next few
                        // lines of chatter and never seen.
                        //
                        // Verdict-shaped lines are LATCHED: kept at the top of the
                        // tail until the flow moves on. Everything else still
                        // rolls, so the dialog stays a live view and not a log.
                        // ⚠ CLI NARRATION IS NOT FOR THE USER. Even after the
                        // noise filter, the CLI's own storytelling leaked into
                        // the dialog with its spaces eaten by cursor-positioning
                        // («Thiswillguideyouthrough…», «·Openingbrowsertosignin…»
                        // — still there, 2026-08-18). Nothing in it is
                        // actionable: before the URL the honest state is
                        // "starting…", after it the dialog's own controls carry
                        // the instructions. Only VERDICT-shaped lines (the
                        // subscription check's outcome, an error) earn the
                        // screen; everything else stays in the redacted log.
                        val verdict = recent.filterNot { isNoiseLine(it) }
                            .lastOrNull { isVerdictLine(it) }
                            ?: latchedVerdict.takeIf { it.isNotBlank() }
                        if (verdict != null) latchedVerdict = verdict
                        // An error-shaped verdict is a STOP the user must act on
                        // — mark the request stalled so the dialog offers the
                        // retry affordance next to the message instead of a
                        // spinner over a corpse.
                        val verdictIsError = verdict != null && verdict.lowercase().let {
                            it.startsWith("✗") || it.startsWith("×") || it.contains("error") ||
                                it.contains("failed") || it.contains("400")
                        }
                        loginRequestMut.value = cur.copy(
                            url = nextUrl,
                            code = code ?: cur.code,
                            rawTail = verdict ?: cur.rawTail,
                            awaitingPaste = cur.awaitingPaste || pasteNow || callbackNow,
                            callbackMode = cur.callbackMode || callbackNow,
                            stalled = cur.stalled || verdictIsError,
                        )
                    }
                    pollJob.cancel()
                    stallJob.cancel()
                }
                if (recovering) {
                    // Silent auto-fix: reinstall the broken CLI, then
                    // re-launch the OAuth flow. From the user's POV they
                    // never see an error — they just see the dialog say
                    // "Repairing…" for a bit and then come back with a
                    // working URL button.
                    //
                    // ⛔ REPAIR, NOT UPGRADE — forceLatest is false here.
                    //
                    // This is the ONE path that installs a CLI without the user
                    // tapping anything, and it used to pass `true`, i.e. jump to
                    // whatever `@latest` was at that moment. That is precisely
                    // the hole raised in public on 2026-08-27: an unattended
                    // update can change what SAFE/AUTO/YOLO actually grant,
                    // because the mode→flag mapping does not consult the
                    // version. And it is not theoretical — the audit found
                    // codex 0.149.1 rejecting the SAFE and AUTO invocations
                    // while YOLO still worked.
                    //
                    // A repair should restore the version whose flags were
                    // verified (spec/CliContract's pin), never silently move the
                    // user forward. Moving forward stays an explicit tap.
                    android.util.Log.d(tag, "login($agent) — recovering: doInstall(pinned, forceLatest=false)")
                    doInstall(agent, false)
                    android.util.Log.d(tag, "login($agent) — recovering: relaunching startOAuthLogin")
                    // Re-launch through the public entry point. It
                    // cancels our `loginJob` slot and starts a fresh
                    // coroutine; our own try/finally below skips the
                    // _loginRequest reset because `recovering == true`.
                    startOAuthLogin(agent)
                    return@launch
                }
                // A real login, or NOTHING. The read loop also ends when the CLI
                // exited WITHOUT logging in (channel dropped, no URL shown, no cred
                // written). Treating THAT as success saved a phantom "not logged in"
                // account and jumped straight to "name this account" (user,
                // 2026-07-16). Success = a FRESH credential landed post-login-start.
                val loggedInForReal = checkAuthOnly(pooled, agent, loginSince)
                if (loggedInForReal) {
                    // The CLI's own /login warning, verbatim: a leftover
                    // CLAUDE_CODE_OAUTH_TOKEN in the shell profile OUTRANKS the
                    // fresh credentials file — new sessions would keep running as
                    // the OLD account. Scrub the env lines (setup-token era).
                    if (agent == Agent.CLAUDE) {
                        SilentlyTry.fired(tag, "scrub stale claude env token after /login") {
                            pooled.startSession().use { s ->
                                val scrub = "for f in ~/.profile ~/.bashrc ~/.bash_profile; do " +
                                    // ⛔ REMOVE THE BACKUP sed LEAVES BEHIND. `sed -i.bak` does not
                                    // edit in place: it renames the original to <file>.bak and writes
                                    // a cleaned copy — so scrubbing a token out of ~/.profile created
                                    // ~/.profile.bak with the token still in it, world-readable at the
                                    // original's permissions, and nothing ever deleted it (audit,
                                    // 2026-08-30).
                                    "[ -f \"\$f\" ] && { sed -i.bak \"/^[[:space:]]*export[[:space:]]\\+CLAUDE_CODE_OAUTH_TOKEN=/d\" \"\$f\" 2>/dev/null; rm -f \"\$f.bak\"; }; done; echo OK"
                                val p = s.exec(ai.eight24family.conch.agent.RemoteEnv.portable("bash -lc " + shellEscape(scrub)))
                                p.join(10, java.util.concurrent.TimeUnit.SECONDS)
                            }
                        }
                    }
                    // Keep the login window UP in a clean "signing in" animation (NOT
                    // the raw server log) while onLoginSuccess captures the account
                    // and AWAITS the run-state (subscription) probe. Only after that
                    // returns does the finally close the window — so it vanishes
                    // straight into [ ready ]/[ no subscription ] with NO post-close
                    // refresh spinner and NO stale-"login" flicker (user, 2026-07-16:;
                    // and the parallel full refresh() that used to race it is gone).
                    loginRequestMut.value = (loginRequestMut.value
                        ?: AgentPickerViewModel.LoginRequest(agent, serverId, null, null, "")).copy(
                        awaitingPaste = false, submitted = true, fatalError = null,
                    )
                    onLoginSuccess(agent)
                } else if (loginAutoRetries < LOGIN_MAX_AUTO_RETRIES) {
                    // The attempt died underneath the user (transport EOF, wedged
                    // CLI, watchdog kill). An error must not stop the human —
                    // relaunch the whole flow ourselves and SAY so; the retry
                    // budget stops a dead server from looping forever.
                    loginAutoRetries++
                    autoRetrying = true
                    android.util.Log.w(tag, "login($agent) — attempt died, auto-retry $loginAutoRetries/$LOGIN_MAX_AUTO_RETRIES")
                    loginRequestMut.value = (loginRequestMut.value
                        ?: AgentPickerViewModel.LoginRequest(agent, serverId, null, null, "")).copy(
                        url = null, code = null,
                        awaitingPaste = false, submitted = false, stalled = false,
                        rawTail = "Connection dropped — retrying sign-in ($loginAutoRetries/$LOGIN_MAX_AUTO_RETRIES)…",
                    )
                    startOAuthLogin(agent, internalRetry = true)
                    return@launch
                } else {
                    loginFailed = true
                    android.util.Log.w(tag, "login($agent) — proc ended with NO fresh credential; NOT a login, not saving an account")
                    loginRequestMut.value = (loginRequestMut.value
                        ?: AgentPickerViewModel.LoginRequest(agent, serverId, null, null, "")).copy(
                        awaitingPaste = false,
                        submitted = false,
                        fatalError = "Sign-in didn't complete after ${LOGIN_MAX_AUTO_RETRIES + 1} attempts — nothing was saved. " +
                            "Check the server's connection, then tap retry.",
                    )
                }
            } catch (t: Throwable) {
                android.util.Log.w(tag, "login($agent) threw: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                // Only the attempt that OWNS the handle may close it: a newer
                // attempt is already running on its own process, and closing its
                // stdin from here is what made the code undeliverable. An
                // auto-retry hands the dialog to its successor the same way a
                // recovery does.
                if (!recovering && !autoRetrying && stdinGen == myGen) {
                    SilentlyTry.fired("SshAi-AgentPicker", "close login stdin (finally)") { loginProcStdin?.close() }
                    loginProcStdin = null
                    // But keep the dialog UP on a real failure so the user sees why
                    // (fatalError + Cancel); only clear it on success.
                    if (!loginFailed) loginRequestMut.value = null
                }
            }
        }
    }

    /**
     * **Gemini OAuth — paste-code flow (Claude-like, NOT moltbot).**
     *
     * What logs actually showed (the previous attempts with /auth
     * keystrokes broke this — see commit history):
     *
     *   1. With `~/.gemini/settings.json: selectedAuthType=oauth-personal`
     *      and `--skip-trust`, Gemini **immediately** starts the OAuth
     *      flow on launch — no menu, no trust prompt.
     *   2. It prints to stdout:
     *        Please visit the following URL to authorize the application:
     *        https://accounts.google.com/o/oauth2/v2/auth?…&redirect_uri=
     *          https%3A%2F%2Fcodeassist.google.com%2Fauthcode&…
     *        Enter the authorization code:
     *   3. `redirect_uri` is **Google's hosted page** (codeassist.
     *      google.com/authcode), NOT localhost — so there is no
     *      callback to curl. User signs in → Google's page shows them
     *      the bare auth code → user copies + pastes back into the
     *      CLI's `Enter the authorization code:` prompt.
     *   4. CLI exchanges the code with Google, writes
     *      `~/.gemini/oauth_creds.json`.
     *
     * That's exactly Claude's OOB pattern, NOT Codex's moltbot. So:
     * just launch gemini, parse the URL from stdout, surface it with
     * the typed-code paste field (NOT callbackMode), and route the
     * pasted code through [submitOAuthCode] which types it byte-by-byte
     * into stdin.
     *
     * Source: actual server stdout from a real run.
     */
    /** @param myGen the attempt that owns the stdin handle — see [loginGen]. */
    private suspend fun handleGeminiLogin(pooled: net.schmizz.sshj.SSHClient, myGen: Int) {
        val tag = "SshAi-AgentPicker"
        // Pre-configure ~/.gemini/settings.json so Gemini skips the
        // /auth selector and immediately starts oauth-personal. We
        // set BOTH the old flat key and the new nested form; whichever
        // the current CLI version recognises wins.
        runCatching {
            pooled.startSession().use { sess ->
                val setupCmd =
                    "mkdir -p \$HOME/.gemini; " +
                    // Clear any STALE oauth_creds before the flow. A leftover
                    // file carrying a "refresh_token" (revoked, or from a
                    // half-finished attempt) makes the completion-poller fire
                    // "credentials detected" the instant the login starts —
                    // killing gemini BEFORE it prints the auth URL (the exact
                    // symptom: window opens "starting…" then closes, no link).
                    // With it gone, the poller only matches the FRESH creds this
                    // login writes after the code exchange. (Verified: gemini
                    // does NOT write a partial oauth_creds on OAuth *start*, so
                    // removing it can't lose an in-progress token.)
                    "rm -f \$HOME/.gemini/oauth_creds.json \$HOME/.config/gemini/oauth_creds.json; " +
                    "cat > \$HOME/.gemini/settings.json <<'SETTINGSEOF'\n" +
                    "{\n" +
                    "  \"selectedAuthType\": \"oauth-personal\",\n" +
                    "  \"security\": { \"auth\": { \"selectedType\": \"oauth-personal\" } }\n" +
                    "}\n" +
                    "SETTINGSEOF"
                val proc = sess.exec(
                    ai.eight24family.conch.agent.RemoteEnv.portable("bash -lc " + shellEscape(setupCmd)),
                )
                proc.join(10, java.util.concurrent.TimeUnit.SECONDS)
                android.util.Log.d(tag, "gemini setup: settings.json written (exit=${proc.exitStatus})")
            }
        }.onFailure {
            android.util.Log.w(tag, "gemini setup failed: ${it.message}")
        }
        // Declared outside try so the finally can see it: set when Google
        // refuses Code Assist — the dialog then stays up with the explanation
        // + "use API key" button instead of being cleared.
        var bailedFatal = false
        try {
            pooled.startSession().use { sess ->
                SilentlyTry.fired("SshAi-AgentPicker", "allocate gemini login PTY") {
                    sess.allocatePTY(
                        "xterm", 1000, 40, 0, 0,
                        java.util.Collections.emptyMap(),
                    )
                }
                // RemoteEnv owns the PATH story — this hand-rolled copy had
                // drifted to a subset (see the login() twin).
                val loginShellPrefix = ai.eight24family.conch.agent.RemoteEnv.PATH_PREAMBLE_INLINE + " "
                // --skip-trust bypasses "Do you trust the files in this
                // folder?". Without it, Gemini parks on that prompt and
                // doesn't start the OAuth flow.
                val fullCmd = ai.eight24family.conch.agent.RemoteEnv.portable(
                    "bash -lc " + shellEscape(loginShellPrefix + "gemini --skip-trust"),
                )
                android.util.Log.d(tag, "gemini login — running: $fullCmd")
                val proc = sess.exec("$fullCmd 2>&1")
                loginProcStdin = proc.outputStream
                val reader = proc.inputStream.bufferedReader()
                val urlRe = Regex("""https://accounts\.google\.com/o/oauth2/v2/auth\?[^\s'"<>`]+""")
                // Strip ANSI escapes (the same regex Claude uses).
                val ansiRe = Regex("""\x1B\[[0-?]*[ -/]*[@-~]""")
                // Poll for credentials in parallel.
                val pollJob = scope.launch(Dispatchers.IO) {
                    while (true) {
                        delay(3_000)
                        if (checkAuthOnly(pooled, Agent.GEMINI)) {
                            // oauth_creds.json is written — but gemini is NOT done.
                            // After the code exchange it still onboards the account
                            // with Gemini Code Assist (a network call) and writes
                            // its account cache; the auth token lands first. Closing
                            // the CLI the instant the creds file appears interrupts
                            // that finalisation → a half-login the next probe /
                            // live-auth rejects ("not logged in" — the exact
                            // symptom). Give it a grace window to settle; the read
                            // loop keeps logging gemini's stdout meanwhile.
                            android.util.Log.d(tag, "gemini: oauth creds written — grace 7s before close (let it finalize Code Assist)")
                            delay(7_000)
                            android.util.Log.d(tag, "gemini: closing after grace")
                            SilentlyTry.fired("SshAi-AgentPicker", "close gemini login proc on auth") { proc.close() }
                            break
                        }
                    }
                }
                // Provider-side dead-ends the user CAN'T fix by retrying — the
                // token exchange SUCCEEDS but Google then refuses to provision
                // the free "Gemini Code Assist for individuals" tier for this
                // account/region (verified on-device: "Failed to sign in.
                // Message: We can't connect to Gemini Code Assist for
                // individuals."). Surface it + steer to the API-key path instead
                // of silently reverting the badge to "log in".
                val fatalRe = Regex(
                    "(?i)(can't connect to gemini code assist|unsupported_location|ineligible|failed to sign in)"
                )
                val fullBuf = StringBuilder()
                while (true) {
                    val rawLine = try { reader.readLine() } catch (_: Throwable) { null } ?: break
                    val line = ansiRe.replace(rawLine, "").trim()
                    if (line.isEmpty()) continue
                    android.util.Log.d(tag, "gemini stdout: ${redactSecrets(line.take(200))}")
                    if (fatalRe.containsMatchIn(line)) {
                        android.util.Log.w(tag, "gemini login — provider refused Code Assist: $line")
                        bailedFatal = true
                        loginRequestMut.value = loginRequestMut.value?.copy(
                            url = null,
                            awaitingPaste = false,
                            submitted = false,
                            fatalError = "Google declined Gemini Code Assist sign-in for this " +
                                "account/region (“we can't connect to Gemini Code Assist for " +
                                "individuals”). Sign in with a Gemini API key instead.",
                        )
                        SilentlyTry.fired("SshAi-AgentPicker", "close gemini login proc on fatal") { proc.close() }
                        break
                    }
                    fullBuf.append(line).append('\n')
                    if (fullBuf.length > 65_536) {
                        fullBuf.delete(0, fullBuf.length - 32_768)
                    }
                    val urlMatch = urlRe.findAll(fullBuf).maxByOrNull { it.value.length }?.value
                    val cur = loginRequestMut.value ?: continue
                    if (urlMatch != null && cur.url != urlMatch) {
                        loginRequestMut.value = cur.copy(
                            url = urlMatch,
                            // Same paste field as Claude — typed code,
                            // not a callback URL. callbackMode stays
                            // false so [submitOAuthCode] (byte-by-byte
                            // stdin typer) handles the submit, not
                            // [submitCodexCallback].
                            awaitingPaste = true,
                            callbackMode = false,
                            rawTail = "Waiting for code paste…",
                        )
                    }
                }
                pollJob.cancel()
            }
            if (!bailedFatal) {
                refresh(false)
                onLoginSuccess(Agent.GEMINI)
            }
        } catch (t: Throwable) {
            android.util.Log.w(tag, "handleGeminiLogin threw: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            // Keep the dialog up when we bailed on a provider wall — it now
            // carries the explanation + the "use API key" button. Otherwise
            // clear it as usual.
            if (!bailedFatal) loginRequestMut.value = null
            if (stdinGen == myGen) {
                SilentlyTry.fired("SshAi-AgentPicker", "close gemini login stdin") { loginProcStdin?.close() }
                loginProcStdin = null
            }
        }
    }

    /**
     * **moltbot-pattern submit for Codex AND Gemini.** The user pasted
     * the URL their phone browser landed on after sign-in — something
     * like `http://localhost:1455/auth/callback?code=…&state=…` (Codex)
     * or `http://localhost:43219/oauth2callback?code=…&scope=…` (Gemini).
     *
     * The CLI's own listener on the *server's* localhost:<port> is what
     * needs to receive the hit to finish the exchange. We can't make
     * the phone browser reach the server's localhost, so we just curl
     * the callback URL **from the server** through the pooled SSH —
     * the listener sees the request, exchanges the code with the OAuth
     * provider, writes `~/.codex/auth.json` (or
     * `~/.gemini/oauth_creds.json`), and the credentials-file poller
     * in [startOAuthLogin] closes the dialog.
     *
     * Port and path are **extracted from the user's paste**, not
     * hardcoded — Gemini binds a random port each run, and Codex's
     * 1455 might change in a future version too. We default to
     * `localhost:1455/auth/callback` only if the paste has no
     * `localhost:N/path` portion (legacy convenience).
     *
     * Accepts:
     *   http://localhost:NNNN/path?code=…&state=…       ← preferred
     *   localhost:NNNN/path?code=…
     *   /path?code=…&state=…                           ← assumes 1455
     *   ?code=…&state=…                                ← assumes 1455 + /auth/callback
     *   code=…&state=…                                 ← same
     */
    fun submitCodexCallback(raw: String) {
        if (raw.isBlank()) return
        val tag = "SshAi-AgentPicker"
        scope.launch(Dispatchers.IO) {
            val pooled = ServiceLocator.sshConnectionPool.peek(serverId) ?: return@launch
            val cleaned = raw.replace(Regex("\\s+"), "")

            // Pull port + path out of `localhost:NNNN/path` chunk if present.
            val portPathRe = Regex("""localhost:(\d+)(/[^?]*)?""")
            val match = portPathRe.find(cleaned)
            val port = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1455
            val path = match?.groupValues?.getOrNull(2)?.takeIf { it.isNotEmpty() }
                ?: "/auth/callback"

            // Pull query string off the end.
            val query = cleaned.substringAfter('?', "").ifEmpty {
                if (cleaned.contains("code=")) cleaned else ""
            }
            if (query.isBlank()) {
                android.util.Log.w(tag, "submitCodexCallback — no query string found in paste")
                return@launch
            }

            val callbackUrl = "http://localhost:$port$path?$query"
            android.util.Log.d(tag, "submitOAuthCallback — curl'ing port=$port path=$path (query head=${query.take(40)}…)")
            loginRequestMut.value = loginRequestMut.value?.copy(
                awaitingPaste = false,
                submitted = true,
                rawTail = "Completing sign-in…",
            )
            // Fire curl in the background — the CLI's listener accepts
            // the request, exchanges with OAuth provider, then exits.
            // Timeout generous so a slow handshake doesn't tear the
            // curl down mid-way.
            runCatching {
                pooled.startSession().use { sess ->
                    val proc = sess.exec(
                        ai.eight24family.conch.agent.RemoteEnv.portable(
                            "bash -lc " + shellEscape(
                                "curl -fsS -m 30 " + shellEscape(callbackUrl) + " >/dev/null 2>&1; " +
                                "echo \"curl_exit=\$?\""
                            ),
                        )
                    )
                    val output = proc.inputStream.bufferedReader().readText()
                    proc.join(35, java.util.concurrent.TimeUnit.SECONDS)
                    android.util.Log.d(tag, "submitOAuthCallback — done (${output.trim()})")
                }
            }.onFailure {
                android.util.Log.w(tag, "submitOAuthCallback failed: ${it.message}")
            }
            // Poll loop in startOAuthLogin sees credentials file appear
            // and closes the dialog. No further action here.
        }
    }

    /**
     * Paste the OOB authorisation code (copied from the browser tab
     * Claude / OpenAI / Google opened during OAuth) into the running
     * CLI's stdin. Followed by `\n` so the CLI's `readline`-style
     * prompt accepts it. After that the CLI exchanges the code for a
     * token; our credentials-file poller picks up the resulting
     * `~/.claude/.credentials.json` (or peer file) and closes the
     * dialog. No-op if no login is in flight.
     */
    /**
     * @param manual TRUE when a HUMAN pressed the button, false when the
     *   clipboard auto-grab fired on return from the browser.
     *
     * ⚠ THE DISTINCTION IS THE WHOLE POINT. A tap that does nothing and says
     * nothing is the worst outcome this dialog can produce: the auto-grab had
     * already consumed a submit and set `submitted`, the login process was gone,
     * and the late-duplicate guard below then swallowed the human's press in
     * silence. The guard is right about ROBOT duplicates — it exists so a racing
     * auto-grab cannot flash "Login session ended" over a sign-in that is
     * succeeding — and wrong about people. A person pressing a button always gets
     * an answer.
     */
    fun submitOAuthCode(code: String, manual: Boolean = true) {
        val tag = "SshAi-AgentPicker"
        android.util.Log.d(tag, "submitOAuthCode — entry, manual=$manual, codeLen=${code.trim().length}, stdin=${if (loginProcStdin == null) "NULL" else "live"}")
        val stdin = loginProcStdin ?: run {
            val cur = loginRequestMut.value
            // A LATE second submit from the AUTO-GRAB is harmless, not an error:
            // it can fire on return-from-browser right after a manual paste
            // already delivered the code and the proc closed. If the login
            // already completed (dialog gone) or is mid-exchange (submitted),
            // ignore it — do NOT flash "Login session ended" over a succeeding
            // sign-in.
            if (!manual && (cur == null || cur.submitted)) {
                android.util.Log.d(tag, "submitOAuthCode — stdin null, auto-grab duplicate; ignoring")
                return
            }
            // A MANUAL press with no process to write to. Never silent: say what
            // happened and what to do, and keep the code on screen so it does not
            // have to be fetched from the browser again.
            if (cur != null && cur.submitted) {
                android.util.Log.w(tag, "submitOAuthCode — MANUAL press after the login process ended")
                loginRequestMut.value = cur.copy(
                    rawTail = "The sign-in on the server already ended, so this code had nowhere to go. " +
                        "Tap Cancel and start the sign-in again — your code is still valid for a few minutes.",
                )
                return
            }
            // Genuinely early death (proc gone before any submit) — surface it so
            // the user knows to restart instead of staring at a dead dialog.
            // `cur` can be null here only for a MANUAL press after the dialog was
            // dismissed; there is nothing left to write the message onto, and the
            // log line is the honest record.
            android.util.Log.w(tag, "submitOAuthCode — loginProcStdin is NULL, code not delivered")
            cur?.let {
                loginRequestMut.value = it.copy(
                    rawTail = "Login session ended — tap Cancel and sign in again.",
                )
            }
            return
        }
        if (code.isBlank()) return
        scope.launch(Dispatchers.IO) {
            // Strip all whitespace — OAuth codes are base64url/opaque,
            // never have legitimate whitespace, but copy/paste from
            // mobile browsers loves to inject \r\n. Also cut a trailing
            // copy-artifact URL: Claude's code page shows `<code>#<state>`
            // and users often over-select into the `https://claude.com/…`
            // link right after it (user, 2026-07-16 pasted exactly that).
            // A real code never contains a URL scheme, so this is safe.
            val clean = code.trim()
                .substringBefore("https://").substringBefore("http://")
                .replace(Regex("\\s+"), "")
            android.util.Log.d(tag, "submitOAuthCode — ${clean.length}B, head8=${clean.take(8)}")
            val cur = loginRequestMut.value
            if (cur != null) {
                loginRequestMut.value = cur.copy(
                    awaitingPaste = false,
                    submitted = true,
                    // Start the step trail fresh — first "signing in" step.
                    rawTail = "Exchanging code for token",
                )
            }
            // **Type byte-by-byte with a tiny delay between chars.**
            //
            // Claude Code's `setup-token` prompt reads stdin in RAW
            // mode with bracketed-paste detection. When the whole code
            // arrives in a single chunk (as it would from a real
            // browser "paste"), the CLI silently drops it — confirmed
            // bug, marked "not planned" by Anthropic in GitHub issue
            // #47745. The documented workaround there is: *type
            // manually*. Typed characters pass through fine.
            //
            // Mimicking a human typist by streaming one byte at a time
            // with 15 ms between presses makes the CLI treat our input
            // as keyboard typing instead of a paste blob — exactly the
            // path that the bug doesn't affect.
            //
            // Followed by **CR (\r)**, not LF: terminal "Enter" is
            // ASCII 0x0D in raw mode. LF doesn't trigger submission
            // when the CLI has set `ICRNL=0`.
            //
            // Followed by **stdin.close()** to send EOF — guarantees
            // the CLI's readline returns even if it's waiting for
            // more bytes after our \r. We don't need stdin again.
            runCatching {
                for (b in clean.toByteArray(Charsets.UTF_8)) {
                    stdin.write(byteArrayOf(b))
                    stdin.flush()
                    delay(15)
                }
                stdin.write("\r".toByteArray(Charsets.UTF_8))
                stdin.flush()
                android.util.Log.d(tag, "submitOAuthCode — ${clean.length} bytes typed + \\r + flushed")
                delay(500)
                // Belt-and-braces extra \n in case the CLI is on a
                // non-raw line discipline and wants LF instead.
                stdin.write("\n".toByteArray(Charsets.UTF_8))
                stdin.flush()
                android.util.Log.d(tag, "submitOAuthCode — extra \\n flushed")
            }.onFailure {
                android.util.Log.w(tag, "submitOAuthCode failed: ${it.message}")
            }
        }
    }

    private suspend fun checkAuthOnly(client: net.schmizz.sshj.SSHClient, agent: Agent, sinceEpoch: Long = 0L): Boolean {
        // This poller backs the OAUTH login flow ONLY, so success means the
        // OAUTH credential specifically appeared. It MUST NOT count a
        // pre-existing credential as "done" — doing so aborts the OAuth flow
        // before the user ever sees the auth URL. Two historical bugs from this:
        // (1) a Gemini GEMINI_API_KEY visible to the login shell fired ~8s in and
        // closed the CLI before it printed the URL; (2) a Claude/Codex server that
        // was ALREADY logged in (a live ~/.claude/.credentials.json — e.g. an
        // account with a dead subscription) made the very first poll succeed, so
        // the login spun briefly then jumped straight to "name this account"
        // without ever showing the auth link (user, 2026-07-16).
        // Fix: for the file-based agents, require the credential to be FRESHER than
        // [sinceEpoch] (login start), so a pre-existing file can never count.
        val checks = when (agent) {
            // The /login flow ends by writing ~/.claude/.credentials.json —
            // that fresh (post-login-start) write IS the success signal; a
            // pre-existing file must never count.
            Agent.CLAUDE ->
                "for f in ~/.claude/.credentials.json ~/.claude/credentials.json; do " +
                    "[ -f \"\$f\" ] || continue; " +
                    "m=\$(stat -c %Y \"\$f\" 2>/dev/null || stat -f %m \"\$f\" 2>/dev/null || echo 0); " +
                    "[ \"\$m\" -gt $sinceEpoch ] && exit 0; done; exit 1"
            Agent.CODEX ->
                "f=~/.codex/auth.json; [ -f \"\$f\" ] && " +
                    "m=\$(stat -c %Y \"\$f\" 2>/dev/null || stat -f %m \"\$f\" 2>/dev/null || echo 0) && " +
                    "[ \"\$m\" -gt $sinceEpoch ]"
            // Gemini: success only when oauth_creds.json carries a refresh_token
            // with a NON-EMPTY value (a bare `"refresh_token"` key — null/""
            // skeleton — would still race the CLI's write). NO api-key fallback
            // here — see above. Combined with the rm of any stale oauth_creds at
            // login start, this fires only on the real, freshly-written token.
            Agent.GEMINI -> "grep -qsE '\"refresh_token\"[[:space:]]*:[[:space:]]*\"[^\"]+\"' ~/.gemini/oauth_creds.json ~/.config/gemini/oauth_creds.json 2>/dev/null"
            // Grok: `grok login --device-code` writes ~/.grok/auth.json on
            // approval (hot-reloaded by the CLI). Freshness-gated like the
            // others so a pre-existing login can't count.
            Agent.GROK ->
                "f=~/.grok/auth.json; [ -s \"\$f\" ] && " +
                    "m=\$(stat -c %Y \"\$f\" 2>/dev/null || stat -f %m \"\$f\" 2>/dev/null || echo 0) && " +
                    "[ \"\$m\" -gt $sinceEpoch ]"
            // Copilot: on a keyring-less server the token lands in the plain
            // text settings store under ~/.copilot ("copilotToken" key).
            // Fresh mtime + the key both required — the CLI touches its
            // config on startup, so mtime alone would false-positive within
            // the first poll.
            // Qwen's own OAuth tier was discontinued, so this branch only ever
            // fires for a legacy credential — kept honest rather than dropped.
            Agent.QWEN ->
                "f=~/.qwen/oauth_creds.json; [ -f \"\$f\" ] && " +
                    "m=\$(stat -c %Y \"\$f\" 2>/dev/null || stat -f %m \"\$f\" 2>/dev/null || echo 0) && " +
                    "[ \"\$m\" -gt $sinceEpoch ]"
            // A finished Cursor login persists tokens; the poller must see a
            // FRESH write, so a server that was already signed in can't make
            // the dialog claim success before the user did anything.
            Agent.CURSOR ->
                "for f in ~/.cursor/auth.json ~/.cursor/cli-config.json; do " +
                    "[ -f \"\$f\" ] || continue; " +
                    "m=\$(stat -c %Y \"\$f\" 2>/dev/null || stat -f %m \"\$f\" 2>/dev/null || echo 0); " +
                    "[ \"\$m\" -gt $sinceEpoch ] && grep -qsE '\"(accessToken|authInfo)\"' \"\$f\" && exit 0; done; exit 1"
            // Neither has a sign-in we drive (oauthLoginCommand is null for
            // both: opencode runs on a free tier plus provider keys, Crush is
            // configured purely by environment), so these branches exist for
            // exhaustiveness and answer honestly rather than pretending.
            Agent.OPENCODE ->
                "f=~/.local/share/opencode/auth.json; [ -s \"\$f\" ] && " +
                    "m=\$(stat -c %Y \"\$f\" 2>/dev/null || stat -f %m \"\$f\" 2>/dev/null || echo 0) && " +
                    "[ \"\$m\" -gt $sinceEpoch ]"
            Agent.CRUSH -> "false"
            // Hub authentication was REMOVED from this CLI — `cn login` throws.
            // There is nothing to poll for.
            Agent.CONTINUE -> "false"
            Agent.COPILOT ->
                "for f in ~/.copilot/config.json ~/.copilot/settings.json; do " +
                    "[ -f \"\$f\" ] || continue; " +
                    "m=\$(stat -c %Y \"\$f\" 2>/dev/null || stat -f %m \"\$f\" 2>/dev/null || echo 0); " +
                    "[ \"\$m\" -gt $sinceEpoch ] && grep -qs '\"copilotToken\"' \"\$f\" && exit 0; done; exit 1"
        }
        return SilentlyTry.loggedOrElse("SshAi-AgentPicker", "checkAuthOnly probe", false) {
            client.startSession().use { sess ->
                val proc = sess.exec(
                    ai.eight24family.conch.agent.RemoteEnv.portable("bash -lc " + shellEscape(checks)),
                )
                proc.join(5, java.util.concurrent.TimeUnit.SECONDS)
                proc.exitStatus == 0
            }
        }
    }

    fun cancelLogin() {
        loginJob?.cancel()
        loginRequestMut.value = null
        SilentlyTry.fired("SshAi-AgentPicker", "close login stdin (cancelLogin)") { loginProcStdin?.close() }
        loginProcStdin = null
    }

    private companion object {
        /** Live stdin of the in-flight login process — used by
         *  [submitOAuthCode] to feed the pasted authorisation code back
         *  to the CLI. Null when no login is in progress. Process-global:
         *  see the ownership note at the top of the class. */
        @Volatile private var loginProcStdin: java.io.OutputStream? = null

        /** Which ATTEMPT owns [loginProcStdin], and which attempt this is —
         *  the generation guard described at the top of the class. */
        @Volatile private var loginGen = 0
        @Volatile private var stdinGen = 0

        /** The in-flight login coroutine; needed so a new attempt (from ANY
         *  coordinator instance) can cancel the previous one, and so cancel
         *  actually reaches the login regardless of which panel's button
         *  delivered it. */
        @Volatile private var loginJob: Job? = null

        /** Auto-retries consumed by the CURRENT user-initiated login. A login
         * that dies underneath the user (transport EOF, wedged CLI) restarts
         * itself up to [LOGIN_MAX_AUTO_RETRIES] times before it is allowed to
         * stop them with a message. Reset on every MANUAL start. */
        @Volatile private var loginAutoRetries = 0
        private const val LOGIN_MAX_AUTO_RETRIES = 2

        /** Watchdog: with the login incomplete, this long with NO stdout means
         * the CLI (or the transport under it) is wedged — readLine can block
         * forever on a half-open socket, which is exactly the the user hit
         * (2026-08-22, 15:14: transport broke mid-login, EOF surfaced only
         * minutes later). */
        private const val LOGIN_STALL_MS = 60_000L

        /** The last verdict-shaped line the CLI printed, kept so it cannot
         *  scroll out of the dialog while the flow is still running. */
        @Volatile private var latchedVerdict: String = ""

        /** Token/key shapes worth redacting: Anthropic (sk-ant-…, including the
         *  oat/admin variants), OpenAI (sk-…, sk-proj-…), Google AI (AIza…), and a
         *  generic long bearer blob. Deliberately broad — a false redaction costs
         *  a debugging line, a missed one costs the user's account. */
        private val SECRET_RX = Regex(
            "sk-ant-[A-Za-z0-9_-]{20,}" +
                "|sk-proj-[A-Za-z0-9_-]{20,}" +
                "|sk-[A-Za-z0-9]{32,}" +
                "|AIza[A-Za-z0-9_-]{30,}" +
                "|ya29[.][A-Za-z0-9_-]{20,}"
        )

        /** How much of a secret survives redaction — enough to tell WHICH kind of
         *  credential it was, never enough to use. */
        private const val SECRET_KEEP = 12
    }

}
