package ai.eight24family.conch.ui.viewmodel

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * API key / OAuth picker + API key submit pulled out of
 * [AgentPickerViewModel].
 *
 * Owns the chooser sheet state (`apiKey / OAuth?`), the API-key input
 * dialog state, and the `sed`-based `~/.profile` writeback that flips
 * the agent badge to `[ ready ]` on the next refresh.
 *
 * The OAuth branch from the chooser is delegated back to the
 * orchestrator via the [startOAuthLogin] lambda so the OAuth helper
 * doesn't need a direct reference here.
 */
internal class AgentPickerViewModelApiKey(
    private val scope: CoroutineScope,
    private val serverId: String,
    private val loginPickerMut: MutableStateFlow<Agent?>,
    private val apiKeyEntryMut: MutableStateFlow<Agent?>,
    /** Triggers a status refresh after a key lands in ~/.profile. */
    private val refresh: (Boolean) -> Job,
    /** Called when the user picks OAuth in the chooser sheet. */
    private val startOAuthLogin: (Agent) -> Unit,
    /** Shell-escape helper shared with the OAuth path. */
    private val shellEscape: (String) -> String,
    /** Fired after a key is written. SUSPEND — captures the account and awaits
     *  the run-state probe (same fast, no-post-spinner path as OAuth). */
    private val onLoginSuccess: suspend (Agent) -> Unit = {},
    /** Where a failed write is reported. Silence used to be the only outcome
     *  for both ways this can fail — see [submitApiKey]. */
    private val errorMut: MutableStateFlow<String?>? = null,
) {

    /**
     * Entry point for "Log in" button taps. Opens the chooser sheet —
     * user picks API key OR OAuth. Each path then drives its own
     * flow ([submitApiKey] / [startOAuthLogin]).
     */
    fun startLogin(agent: Agent) {
        loginPickerMut.value = agent
    }

    fun cancelLoginPicker() { loginPickerMut.value = null }

    /** Picker chose "API key" — close picker, open API key input. */
    fun chooseApiKey() {
        val agent = loginPickerMut.value ?: return
        loginPickerMut.value = null
        apiKeyEntryMut.value = agent
    }

    fun cancelApiKeyEntry() { apiKeyEntryMut.value = null }

    /** Open the API-key input directly for [agent], skipping the method picker.
     *  Used when an OAuth attempt dead-ends on a provider-side wall (e.g. Google
     *  declining Gemini Code Assist) and we steer the user to the key path. */
    fun openEntry(agent: Agent) { apiKeyEntryMut.value = agent }

    /** Picker chose "OAuth" — kick off the device-code flow over the
     *  pooled SSH (URL+code stream in via the OAuth helper). */
    fun chooseOAuth() {
        val agent = loginPickerMut.value ?: return
        loginPickerMut.value = null
        startOAuthLogin(agent)
    }

    /** Map agent → its primary API-key env var. */
    private fun envVarFor(agent: Agent): String = when (agent) {
        Agent.CLAUDE -> "ANTHROPIC_API_KEY"
        Agent.CODEX -> "OPENAI_API_KEY"
        Agent.GEMINI -> "GEMINI_API_KEY"
        Agent.GROK -> "XAI_API_KEY"
        // Copilot's "key" is a GitHub token (fine-grained PAT with the
        // "Copilot Requests" permission) — its highest-precedence env var.
        Agent.COPILOT -> "COPILOT_GITHUB_TOKEN"
        Agent.QWEN -> "OPENAI_API_KEY"
        Agent.CURSOR -> "CURSOR_API_KEY"
        Agent.OPENCODE -> "ANTHROPIC_API_KEY"
        Agent.CRUSH -> "ANTHROPIC_API_KEY"
        Agent.CONTINUE -> "ANTHROPIC_API_KEY"
    }

    /**
     * Persist the pasted API key as an `export <VAR>=<key>` line in
     * `~/.profile` on the server. Every future `bash -lc` shell (and
     * our subsequent status probe) picks it up automatically. The
     * probe's auth check now ORs in the env var presence — so this
     * flips the badge to `[ ready ]` on the next refresh.
     */
    fun submitApiKey(agent: Agent, key: String) {
        if (key.isBlank()) return
        scope.launch(Dispatchers.IO) {
            val tag = "Conch-AgentPicker"
            val pooled = ServiceLocator.sshConnectionPool.peek(serverId)
            if (pooled == null) {
                // ⛔ NOT SILENCE. This used to `return@launch` here: the dialog
                // stayed open, no message appeared, and pressing the button did
                // visibly nothing (audit, 2026-08-30).
                errorMut?.value = "Not connected to this server — connect and try again."
                return@launch
            }
            val envVar = envVarFor(agent)

            // ⛔ THE KEY NEVER TOUCHES THE COMMAND LINE. It used to be pasted
            // into the script that was handed to `sess.exec`, and sshd runs that
            // as `$SHELL -c '<the whole string>'` — so for as long as the command
            // lived, the key sat in /proc/<pid>/cmdline, readable by `ps aux` for
            // every user and every monitoring agent on the host. It arrives on
            // STDIN instead, the way AgentBridge already passes data.
            //
            // And no `sed -i.bak`: that does not edit in place, it RENAMES the
            // original to ~/.profile.bak and writes a cleaned copy — leaving the
            // previous key in a file nothing ever deletes. The rewrite goes
            // through a 0600 temp file instead.
            val script = """
                set -e
                touch "${'$'}HOME/.profile"
                chmod 600 "${'$'}HOME/.profile" 2>/dev/null || true
                tmp=${'$'}(mktemp) || exit 1
                grep -v "^[[:space:]]*export[[:space:]]\\+$envVar=" "${'$'}HOME/.profile" > "${'$'}tmp" || true
                cat >> "${'$'}tmp"
                mv "${'$'}tmp" "${'$'}HOME/.profile"
                chmod 600 "${'$'}HOME/.profile" 2>/dev/null || true
                rm -f "${'$'}HOME/.profile.bak"
                echo CONCH_KEY_OK
            """.trimIndent()

            val exportLine = "export $envVar='" + key.trim().replace("'", "'\\''") + "'\n"

            val ok = SilentlyTry.loggedOrElse(tag, "write api key to ~/.profile", false) {
                pooled.startSession().use { sess ->
                    val proc = sess.exec(
                        ai.eight24family.conch.agent.RemoteEnv.portable("bash -lc " + shellEscape(script)),
                    )
                    proc.outputStream.use { it.write(exportLine.toByteArray()); it.flush() }
                    val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
                    proc.join(15, java.util.concurrent.TimeUnit.SECONDS)
                    val status = proc.exitStatus ?: -1
                    android.util.Log.d(tag, "submitApiKey($agent) exit=$status")
                    status == 0 && out.contains("CONCH_KEY_OK")
                }
            }

            if (ok != true) {
                // ⛔ AND NO FALSE SUCCESS. The old path closed the dialog and
                // announced a login even when the write had failed — a full disk,
                // a read-only ${'$'}HOME, wrong permissions all reported as "logged in".
                errorMut?.value =
                    "Could not save the key on the server. ~/.profile was not changed."
                return@launch
            }

            apiKeyEntryMut.value = null
            refresh(false)
            onLoginSuccess(agent)
        }
    }

}
