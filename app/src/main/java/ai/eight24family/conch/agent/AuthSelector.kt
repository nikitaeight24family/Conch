package ai.eight24family.conch.agent

/**
 * Builds the shell prefix that FORCES a chosen [AuthMethod] for one CLI
 * invocation — using credentials that already live on the server (on-disk
 * creds, login-shell env, ambient cloud identity). No secrets are stored by
 * the app: switching a method just sets/clears the right SELECTOR env so the
 * CLI walks the desired auth path. Each prefix is `export`/`unset` only and
 * is prepended inside the same `bash -lc` the launch already uses.
 *
 * **Opt-in & safe:** [prefix] returns "" for a null/unknown method, so a
 * session with no chosen method launches EXACTLY as before — zero behaviour
 * change unless the user explicitly switches.
 *
 * API-key methods assume the key is already in the login-shell env (that's
 * why the probe detected them); selecting one just clears the competing
 * selectors. Adding a brand-new key from the app is a separate feature.
 */
object AuthSelector {

    /**
     * Prefer FULL-SCOPE credentials over an inference-only long-lived token.
     *
     * `CLAUDE_CODE_OAUTH_TOKEN` (from `claude setup-token`) is inference-only by
     * design — the CLI's own binary says "Long-lived tokens … are limited to
     * inference-only for security reasons". It carries no `user:profile` scope,
     * so the CLI's startup Bootstrap (`GET /api/oauth/profile`) gets a 403 and
     * silently skips, and the account snapshot in ~/.claude.json never
     * refreshes. A stale snapshot makes the model picker reason about the wrong
     * account: on this user's server it froze a dead team-seat org, so the
     * picker demanded usage credits for a model their Max plan includes, while
     * inference itself worked fine (2026-07-28).
     *
     * The env var BEATS ~/.claude/.credentials.json in the CLI's precedence, and
     * unsetting it for interactive shells (`case "$-" in *i*`) does NOT cover
     * us: Conch launches `bash -lc`, a login but NON-interactive shell, so the
     * token survives and the app inherits the broken state.
     *
     * Hence: if full-scope credentials exist on the box, drop the inference-only
     * var for this invocation and let the CLI use them. With no credentials file
     * the token is the ONLY auth — keep it, or the session breaks outright.
     *
     * ⚠ Not a way around any gate: both credentials are the user's own, the
     * server decides every request, and we only pick the one that lets the CLI
     * see its own account.
     */
    private const val CLAUDE_PREFER_FULL_SCOPE =
        "if [ -f \"\$HOME/.claude/.credentials.json\" ]; then unset CLAUDE_CODE_OAUTH_TOKEN; fi; "

    /** Shared with the `/model` PTY probe, which is the picker's data source and
     *  must run under the same identity as the turns it describes. */
    fun claudeFullScopePrefix(): String = CLAUDE_PREFER_FULL_SCOPE

    fun prefix(agent: Agent, methodKey: String?): String =
        // Applies to EVERY Claude launch, not just a chosen method: by default
        // no method is picked (AuthMethod.of returns null) and the prefix would
        // be empty, which is exactly the case that was broken.
        (if (agent == Agent.CLAUDE) CLAUDE_PREFER_FULL_SCOPE else "") +
        when (AuthMethod.of(agent, methodKey)) {
            // ── Claude: env/flags pick the path; .credentials.json is the
            //    default when nothing overrides it. ──
            AuthMethod.CLAUDE_SUBSCRIPTION ->
                "unset ANTHROPIC_API_KEY ANTHROPIC_AUTH_TOKEN CLAUDE_CODE_USE_VERTEX CLAUDE_CODE_USE_BEDROCK; "
            AuthMethod.CLAUDE_API_KEY ->
                "unset ANTHROPIC_AUTH_TOKEN CLAUDE_CODE_USE_VERTEX CLAUDE_CODE_USE_BEDROCK; "
            AuthMethod.CLAUDE_BEARER ->
                "unset ANTHROPIC_API_KEY CLAUDE_CODE_USE_VERTEX CLAUDE_CODE_USE_BEDROCK; "
            AuthMethod.CLAUDE_VERTEX ->
                "unset ANTHROPIC_API_KEY ANTHROPIC_AUTH_TOKEN CLAUDE_CODE_USE_BEDROCK; export CLAUDE_CODE_USE_VERTEX=1; "
            AuthMethod.CLAUDE_BEDROCK ->
                "unset ANTHROPIC_API_KEY ANTHROPIC_AUTH_TOKEN CLAUDE_CODE_USE_VERTEX; export CLAUDE_CODE_USE_BEDROCK=1; "

            // ── Codex: auth.json holds the active credential; env OPENAI_API_KEY
            //    selects API auth. Clearing the env steers back to the ChatGPT
            //    tokens in auth.json. (Full ChatGPT<->API stash-swap, for the
            //    case where only one is configured, is the follow-up.) ──
            AuthMethod.CODEX_CHATGPT -> "unset OPENAI_API_KEY CODEX_API_KEY; "
            AuthMethod.CODEX_API_KEY -> "" // key already in env; nothing to force

            // ── Gemini: settings.json `security.auth.selectedType` DECIDES the
            //    method and WINS over any env key — a set GEMINI_API_KEY is
            //    ignored when selectedType=oauth-personal (gemini demands an
            //    OAuth login instead), and GEMINI_DEFAULT_AUTH_TYPE can't
            //    override a saved selection (verified on-server; gemini-cli
            //    #3144). So switching a Gemini method MUST write that field —
            //    unset/export of env selectors alone is a no-op. The env
            //    unset/export still steers Vertex and clears stale selectors. ──
            AuthMethod.GEMINI_OAUTH ->
                geminiSelectType("oauth-personal") +
                    "unset GEMINI_API_KEY GOOGLE_API_KEY GOOGLE_GENAI_USE_VERTEXAI GOOGLE_APPLICATION_CREDENTIALS; "
            AuthMethod.GEMINI_API_KEY ->
                geminiSelectType("gemini-api-key") + "unset GOOGLE_GENAI_USE_VERTEXAI; "
            AuthMethod.GEMINI_VERTEX ->
                geminiSelectType("vertex-ai") + "export GOOGLE_GENAI_USE_VERTEXAI=true; "

            null -> "" // no method chosen → launch unchanged (CLI default)
        }

    /** Bash snippet that writes gemini's `security.auth.selectedType` to [type]
     *  (one of `gemini-api-key` / `oauth-personal` / `vertex-ai`). Gemini honors
     *  this field over env, so it's the only reliable way to switch its method.
     *  node is always present (gemini-cli runs on it); the read-modify-write
     *  PRESERVES the user's other settings and turns a missing/corrupt file into
     *  a minimal valid one. Idempotent; runs in the same login shell as launch.
     *  No secrets touched — only the auth-method selector field. */
    private fun geminiSelectType(type: String): String =
        """command -v node >/dev/null 2>&1 && node -e 'const fs=require("fs"),os=require("os"),pa=require("path");const f=pa.join(os.homedir(),".gemini","settings.json");let d={};try{d=JSON.parse(fs.readFileSync(f,"utf8"))}catch(e){}d.security=d.security||{};d.security.auth=d.security.auth||{};d.security.auth.selectedType="$type";fs.mkdirSync(pa.dirname(f),{recursive:true});fs.writeFileSync(f,JSON.stringify(d,null,2))' 2>/dev/null; """
}
