package ai.eight24family.conch.agent

/**
 * A concrete authentication method for an agent CLI. Each agent supports
 * several; the user switches the ACTIVE one (long-press in the agent picker)
 * WITHOUT logging the others out, and each chat session is bound to the
 * method it was created under — a Codex ChatGPT-authed session won't answer
 * under API-key auth, and vice-versa (OpenAI binds the rollout to the
 * credential type).
 *
 * [key] is the agent-scoped short token the status probe emits in
 * `<agent>_methods` / `<agent>_active`. Resolve a (agent, key) pair back to
 * an [AuthMethod] via [of].
 *
 * Sources (researched 2026-05): Claude Code auth docs (subscription OAuth /
 * ANTHROPIC_API_KEY / ANTHROPIC_AUTH_TOKEN / CLAUDE_CODE_USE_VERTEX|BEDROCK),
 * Codex auth docs (ChatGPT sign-in vs API key in ~/.codex/auth.json), Gemini
 * CLI auth docs (oauth-personal / gemini-api-key / vertex-ai in
 * ~/.gemini/settings.json:selectedAuthType).
 */
enum class AuthMethod(
    val agent: Agent,
    val key: String,
    val label: String,
    /** One-liner shown under the label in the switcher sheet. */
    val hint: String,
    /** Whether selecting this method needs a user-supplied secret we inject
     *  via env (API key). OAuth/Vertex/Bedrock ride on-disk creds / ambient
     *  cloud identity, so they need nothing from us at switch time. */
    val needsApiKey: Boolean = false,
) {
    // ── Claude Code ──
    CLAUDE_SUBSCRIPTION(Agent.CLAUDE, "oauth", "Subscription", "claude.ai Pro / Max login"),
    CLAUDE_API_KEY(Agent.CLAUDE, "api", "API key", "ANTHROPIC_API_KEY · API pricing", needsApiKey = true),
    CLAUDE_BEARER(Agent.CLAUDE, "bearer", "Gateway token", "ANTHROPIC_AUTH_TOKEN · proxy", needsApiKey = true),
    CLAUDE_VERTEX(Agent.CLAUDE, "vertex", "Vertex AI", "Google Cloud (CLAUDE_CODE_USE_VERTEX)"),
    CLAUDE_BEDROCK(Agent.CLAUDE, "bedrock", "Bedrock", "AWS (CLAUDE_CODE_USE_BEDROCK)"),

    // ── OpenAI Codex ──
    CODEX_CHATGPT(Agent.CODEX, "chatgpt", "OAuth", "ChatGPT account · plan credits"),
    CODEX_API_KEY(Agent.CODEX, "api", "API key", "OPENAI_API_KEY · API pricing", needsApiKey = true),

    // ── Google Gemini ──
    GEMINI_OAUTH(Agent.GEMINI, "oauth", "Login with Google", "personal OAuth"),
    GEMINI_API_KEY(Agent.GEMINI, "api", "API key", "GEMINI_API_KEY · AI Studio", needsApiKey = true),
    GEMINI_VERTEX(Agent.GEMINI, "vertex", "Vertex AI", "GCP project / ADC / service account"),
    ;

    companion object {
        fun of(agent: Agent, key: String?): AuthMethod? =
            if (key.isNullOrBlank()) null
            else entries.firstOrNull { it.agent == agent && it.key == key }

        /** All methods this agent CAN use — the full menu shown in the
         *  switcher (each marked detected/active or not). */
        fun forAgent(agent: Agent): List<AuthMethod> = entries.filter { it.agent == agent }
    }
}
