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

    fun prefix(agent: Agent, methodKey: String?): String =
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
