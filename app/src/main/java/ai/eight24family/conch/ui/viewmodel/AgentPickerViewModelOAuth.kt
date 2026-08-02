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
 *  - **Claude — `claude setup-token`.** OOB device-code prompt; the
 *    CLI prints the token directly to stdout as `sk-ant-oat01-…`.
 *    We capture it via regex and persist `export
 *    CLAUDE_CODE_OAUTH_TOKEN=…` to `~/.profile`. `setup-token`
 *    does NOT write any credentials file, so the poller looks at
 *    BOTH the file AND the env var to detect completion.
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

    /** Live stdin of the in-flight login process — used by
     *  [submitOAuthCode] to feed the pasted authorisation code back
     *  to the CLI. Null when no login is in progress. */
    private var loginProcStdin: java.io.OutputStream? = null

    /** SSH process command we used to start the login; needed to send
     *  Ctrl+C if the user cancels (since most login flows block on
     *  stdin waiting for the device-code redirect). */
    private var loginJob: Job? = null

    /**
     * Start the OAuth / device-code login flow for [agent] over the
     * pooled SSH client.
     *
     * Per-CLI subcommand:
     *   - **Claude:** `claude setup-token` — Anthropic's official
     *     headless OAuth path. The TUI `claude /login` does NOT work
     *     here because it (a) needs a real TTY and (b) opens a local
     *     browser + waits for a localhost callback that doesn't exist
     *     on a remote SSH host. `setup-token` is built specifically
     *     for headless: prints an auth URL to stdout, polls for
     *     completion server-side, writes the token to
     *     `~/.claude/.credentials.json` (or returns it via stdout for
     *     the user to drop into `ANTHROPIC_AUTH_TOKEN`).
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
    fun startOAuthLogin(agent: Agent) {
        loginJob?.cancel()
        loginJob = scope.launch(Dispatchers.IO) {
            val tag = "SshAi-AgentPicker"
            val pooled = ServiceLocator.sshConnectionPool.peek(serverId) ?: return@launch
            loginRequestMut.value = AgentPickerViewModel.LoginRequest(agent, null, null, "starting…")
            // Gemini diverges — it has no `gemini auth login` subcommand;
            // OAuth only works through the interactive TUI's `/auth`
            // command. We drive the TUI through PTY + capture the URL
            // via a BROWSER=script hack. See [handleGeminiLogin].
            if (agent == Agent.GEMINI) {
                handleGeminiLogin(pooled)
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
            // Claude setup-token success signal: the OAuth token was captured from
            // stdout. Declared out here so the completion gate (after the read
            // loop / session block) can see it.
            var tokenSaved = false
            try {
                val cmd = when (agent) {
                    // setup-token, NOT /login. /login is a TUI slash-command
                    // that needs claude to already be running interactively.
                    Agent.CLAUDE -> "claude setup-token"
                    // **moltbot pattern.** Default `codex login` (no
                    // `--device-auth`) listens on `localhost:1455` and
                    // prints an OAuth URL whose `redirect_uri` is
                    // `http://localhost:1455/auth/callback`. User opens
                    // the URL on their phone, signs in, OpenAI 302's
                    // them to that callback — phone browser shows
                    // "Connection refused" but the URL bar now contains
                    // the full callback URL with `?code=...&state=...`.
                    // User copies that URL back into our dialog;
                    // [submitCodexCallback] curl-fetches it on the
                    // SERVER via the pooled SSH (`curl 'http://
                    // localhost:1455/auth/callback?…'`). The CLI's
                    // listener finally gets the hit, exchanges the
                    // code for a token, writes `~/.codex/auth.json`.
                    // Poller closes the dialog.
                    //
                    // `BROWSER=true` suppresses any attempt to spawn
                    // xdg-open on the headless box (would fail anyway,
                    // but cleaner stdout).
                    //
                    // We don't use `--device-auth` because OpenAI's
                    // workspace admins can disable it (and have, per
                    // the user's actual experience). The localhost-
                    // callback flow doesn't depend on workspace policy.
                    Agent.CODEX -> "BROWSER=true codex login"
                    // Same moltbot pattern as Codex — `BROWSER=true`
                    // suppresses xdg-open, CLI listens on a localhost
                    // port (RANDOM, unlike Codex's fixed 1455), prints
                    // the OAuth URL to stdout. User opens URL on phone,
                    // signs in, Google 302's to `localhost:<port>/
                    // oauth2callback?code=…`, user pastes the callback
                    // URL back, we curl it on the server through the
                    // pooled SSH. CLI exchanges + writes
                    // `~/.gemini/oauth_creds.json`. Port is extracted
                    // from the user's pasted URL (not hardcoded).
                    //
                    // Command: `gemini auth login --oauth` — TUI-free
                    // headless OAuth bootstrap (per
                    // google-gemini.github.io/gemini-cli docs).
                    Agent.GEMINI -> "BROWSER=true gemini auth login --oauth"
                }
                val loginShellPrefix = "export PATH=\"\$HOME/.local/bin:/usr/local/bin:\$PATH\"; " +
                    "for nd in \$HOME/.nvm/versions/node/*/bin \$HOME/.local/node-*/bin; do " +
                    "[ -d \"\$nd\" ] && export PATH=\"\$nd:\$PATH\"; done; " +
                    "[ -s \"\$HOME/.nvm/nvm.sh\" ] && . \"\$HOME/.nvm/nvm.sh\" >/dev/null 2>&1; "
                val fullCmd = "bash -lc " + shellEscape(loginShellPrefix + cmd)
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
                    // Claude `setup-token` ends by printing the OAuth token
                    // to stdout in the format `sk-ant-oat01-<long-b64url>`.
                    // CRITICAL: setup-token does NOT write any credentials
                    // file — the token is ONLY in stdout, the user is
                    // expected to copy it and stuff it into
                    // CLAUDE_CODE_OAUTH_TOKEN themselves. We capture it
                    // here and persist `export CLAUDE_CODE_OAUTH_TOKEN=…`
                    // into ~/.profile so subsequent `bash -lc` (the
                    // probe shell) picks it up.
                    //
                    // Source: https://code.claude.com/docs/en/authentication
                    val claudeTokenRe = Regex("""sk-ant-oat01-[A-Za-z0-9_\-]+""")
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
                                }), tokenSaved=$tokenSaved awaitingPaste=${loginRequestMut.value?.awaitingPaste}",
                            )
                            break
                        }
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
                        android.util.Log.d(tag, "login($agent) stdout: $line")
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
                        // Claude OAuth token capture — this is the actual
                        // success signal for `claude setup-token`. The
                        // CLI prints the bare token, we persist it as
                        // CLAUDE_CODE_OAUTH_TOKEN in ~/.profile, then
                        // kill the CLI process so the read loop exits
                        // cleanly. No credentials.json is written by
                        // setup-token — DO NOT wait for the file.
                        if (agent == Agent.CLAUDE && !tokenSaved) {
                            val tok = claudeTokenRe.find(line)?.value
                            if (tok != null) {
                                tokenSaved = true
                                android.util.Log.d(tag, "login(CLAUDE) — token captured (${tok.length}B, head=${tok.take(20)}…)")
                                loginRequestMut.value = loginRequestMut.value?.let { cur ->
                                    cur.copy(
                                        // Append to the step trail (small log in the
                                        // dialog), don't replace it.
                                        rawTail = appendLoginStep(cur.rawTail, "Saving sign-in to server"),
                                        submitted = true,
                                        awaitingPaste = false,
                                    )
                                }
                                // Persist to ~/.profile. Single-quoted
                                // to survive any special chars in the
                                // token (there shouldn't be any —
                                // base64url is alphanumeric + `_-` —
                                // but be defensive).
                                SilentlyTry.fired("SshAi-AgentPicker", "write claude token to ~/.profile") {
                                    pooled.startSession().use { writeSess ->
                                        val safe = tok.replace("'", "'\\''")
                                        val saveCmd =
                                            "touch ~/.profile; " +
                                            "sed -i.bak \"/^export CLAUDE_CODE_OAUTH_TOKEN=/d\" ~/.profile 2>/dev/null || true; " +
                                            "echo \"export CLAUDE_CODE_OAUTH_TOKEN='$safe'\" >> ~/.profile"
                                        val writeProc = writeSess.exec("bash -lc " + shellEscape(saveCmd))
                                        writeProc.join(10, java.util.concurrent.TimeUnit.SECONDS)
                                        android.util.Log.d(tag, "login(CLAUDE) — token written to ~/.profile (exit=${writeProc.exitStatus})")
                                    }
                                }
                                SilentlyTry.fired("SshAi-AgentPicker", "close login proc after claude token") { proc.close() }
                                break
                            }
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
                        loginRequestMut.value = cur.copy(
                            url = nextUrl,
                            code = code ?: cur.code,
                            rawTail = recent.joinToString("\n"),
                            awaitingPaste = cur.awaitingPaste || pasteNow || callbackNow,
                            callbackMode = cur.callbackMode || callbackNow,
                        )
                    }
                    pollJob.cancel()
                }
                if (recovering) {
                    // Silent auto-fix: reinstall the broken CLI, then
                    // re-launch the OAuth flow. From the user's POV they
                    // never see an error — they just see the dialog say
                    // "Repairing…" for a bit and then come back with a
                    // working URL button.
                    android.util.Log.d(tag, "login($agent) — recovering: doInstall(forceLatest=true)")
                    doInstall(agent, true)
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
                // 2026-07-16). Success = a token was captured (Claude setup-token)
                // OR a FRESH credential file landed (post-login-start).
                val loggedInForReal = tokenSaved || checkAuthOnly(pooled, agent, loginSince)
                if (loggedInForReal) {
                    // Keep the login window UP in a clean "signing in" animation (NOT
                    // the raw server log) while onLoginSuccess captures the account
                    // and AWAITS the run-state (subscription) probe. Only after that
                    // returns does the finally close the window — so it vanishes
                    // straight into [ ready ]/[ no subscription ] with NO post-close
                    // refresh spinner and NO stale-"login" flicker (user, 2026-07-16:;
                    // and the parallel full refresh() that used to race it is gone).
                    loginRequestMut.value = (loginRequestMut.value
                        ?: AgentPickerViewModel.LoginRequest(agent, null, null, "")).copy(
                        awaitingPaste = false, submitted = true, fatalError = null,
                    )
                    onLoginSuccess(agent)
                } else {
                    loginFailed = true
                    android.util.Log.w(tag, "login($agent) — proc ended with NO fresh credential; NOT a login, not saving an account")
                    loginRequestMut.value = (loginRequestMut.value
                        ?: AgentPickerViewModel.LoginRequest(agent, null, null, "")).copy(
                        awaitingPaste = false,
                        submitted = false,
                        fatalError = "Sign-in didn't complete — nothing was saved. The login window closed before an account was signed in. Tap Cancel and try again.",
                    )
                }
            } catch (t: Throwable) {
                android.util.Log.w(tag, "login($agent) threw: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                if (!recovering) {
                    // Stdin is always torn down (the proc is gone either way).
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
    private suspend fun handleGeminiLogin(pooled: net.schmizz.sshj.SSHClient) {
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
                val proc = sess.exec("bash -lc " + shellEscape(setupCmd))
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
                val loginShellPrefix = "export PATH=\"\$HOME/.local/bin:/usr/local/bin:\$PATH\"; " +
                    "for nd in \$HOME/.nvm/versions/node/*/bin \$HOME/.local/node-*/bin; do " +
                    "[ -d \"\$nd\" ] && export PATH=\"\$nd:\$PATH\"; done; " +
                    "[ -s \"\$HOME/.nvm/nvm.sh\" ] && . \"\$HOME/.nvm/nvm.sh\" >/dev/null 2>&1; "
                // --skip-trust bypasses "Do you trust the files in this
                // folder?". Without it, Gemini parks on that prompt and
                // doesn't start the OAuth flow.
                val fullCmd = "bash -lc " + shellEscape(loginShellPrefix + "gemini --skip-trust")
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
                    android.util.Log.d(tag, "gemini stdout: ${line.take(200)}")
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
            SilentlyTry.fired("SshAi-AgentPicker", "close gemini login stdin") { loginProcStdin?.close() }
            loginProcStdin = null
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
                        "bash -lc " + shellEscape(
                            "curl -fsS -m 30 " + shellEscape(callbackUrl) + " >/dev/null 2>&1; " +
                            "echo \"curl_exit=\$?\""
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
    fun submitOAuthCode(code: String) {
        val tag = "SshAi-AgentPicker"
        android.util.Log.d(tag, "submitOAuthCode — entry, codeLen=${code.trim().length}, stdin=${if (loginProcStdin == null) "NULL" else "live"}")
        val stdin = loginProcStdin ?: run {
            val cur = loginRequestMut.value
            // A LATE second submit is harmless, not an error: the clipboard
            // auto-grab can fire on return-from-browser right after a manual
            // paste already delivered the code and the proc closed. If the login
            // already completed (dialog gone) or is mid-exchange (submitted),
            // just ignore it — do NOT flash "Login session ended" over a
            // succeeding sign-in.
            if (cur == null || cur.submitted) {
                android.util.Log.d(tag, "submitOAuthCode — stdin null but login already done/submitted; ignoring late duplicate")
                return
            }
            // Genuinely early death (proc gone before any submit) — surface it so
            // the user knows to restart instead of staring at a dead dialog.
            android.util.Log.w(tag, "submitOAuthCode — loginProcStdin is NULL, code not delivered")
            loginRequestMut.value = cur.copy(
                rawTail = "Login session ended — tap Cancel and sign in again.",
            )
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
            // claude setup-token writes no creds file (success is the token we
            // capture from stdout), so this only ever guards against a stale
            // pre-existing file — require a fresh (post-login-start) write.
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
        }
        return SilentlyTry.loggedOrElse("SshAi-AgentPicker", "checkAuthOnly probe", false) {
            client.startSession().use { sess ->
                val proc = sess.exec("bash -lc " + shellEscape(checks))
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
}
