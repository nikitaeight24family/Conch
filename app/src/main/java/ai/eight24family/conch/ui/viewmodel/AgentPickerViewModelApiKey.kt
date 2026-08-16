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
            val tag = "SshAi-AgentPicker"
            val pooled = ServiceLocator.sshConnectionPool.peek(serverId) ?: return@launch
            val envVar = envVarFor(agent)
            // Single-quote the key, escaping any internal single quotes
            // so a key with `'` doesn't break the shell expression.
            val safeKey = key.trim().replace("'", "'\\''")
            val cmd = """
                touch ~/.profile
                # Remove any prior export of this var so we don't stack
                # duplicate lines on each call.
                sed -i.bak "/^export $envVar=/d" ~/.profile 2>/dev/null || true
                echo "export $envVar='$safeKey'" >> ~/.profile
            """.trimIndent()
            SilentlyTry.fired("SshAi-AgentPicker", "write api key to ~/.profile") {
                pooled.startSession().use { sess ->
                    val proc = sess.exec(
                        ai.eight24family.conch.agent.RemoteEnv.portable("bash -lc " + shellEscape(cmd)),
                    )
                    proc.join(10, java.util.concurrent.TimeUnit.SECONDS)
                    android.util.Log.d(tag, "submitApiKey($agent) exit=${proc.exitStatus}")
                }
            }
            apiKeyEntryMut.value = null
            refresh(false)
            onLoginSuccess(agent)
        }
    }
}
