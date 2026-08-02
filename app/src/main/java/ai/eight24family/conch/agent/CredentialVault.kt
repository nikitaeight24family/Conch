package ai.eight24family.conch.agent

import ai.eight24family.conch.util.SilentlyTry

/**
 * Multi-account credential juggling, server-side. Each agent has one "live"
 * credential; this vault keeps any number of **named slots** under
 * `~/.sshai-auth/<agent>/slots/<id>/` and makes the chosen slot live on demand.
 * Hold several accounts (ChatGPT / Google logins OR API keys) and flip the
 * active one when limits run out — without re-logging-in.
 *
 * Two credential shapes per agent:
 *  - **OAuth / file-based** (`oauth`, `chatgpt`): the live cred is a FILE
 *    (`~/.codex/auth.json`, `~/.gemini/oauth_creds.json`,
 *    `~/.claude/.credentials.json`). A slot saves a copy of that file.
 *  - **API key** (`api`): the live cred is an `export <VAR>=…` line in
 *    `~/.profile`. A slot saves that line + a server-computed MASK
 *    (`sk-proj…ab12`) so the UI can show it partially.
 *
 * **Credential VALUES never enter the app.** We only move files / rewrite the
 * profile line on the user's own server; for API keys only the MASK (first 6 +
 * last 4) is ever returned — the full key stays on the server.
 */
class CredentialVault(
    private val agent: Agent,
    private val exec: suspend (cmd: String) -> String?,
) {
    data class Slot(
        val id: String,
        val method: String,
        val label: String,
        val createdAt: Long,
        /** Masked key preview for API-key slots (`sk-proj…ab12`); null for
         *  OAuth slots. Computed server-side — never the full value. */
        val masked: String? = null,
    )

    /** Live cred FILE for OAuth/file-based methods (HOME-relative, quoted at use). */
    private val liveCred: String = when (agent) {
        Agent.CODEX -> "\$HOME/.codex/auth.json"
        Agent.GEMINI -> "\$HOME/.gemini/oauth_creds.json"
        Agent.CLAUDE -> "\$HOME/.claude/.credentials.json"
    }

    /** Env var the API key lives under (in ~/.profile). */
    private val envVar: String = when (agent) {
        Agent.CODEX -> "OPENAI_API_KEY"
        Agent.GEMINI -> "GEMINI_API_KEY"
        Agent.CLAUDE -> "ANTHROPIC_API_KEY"
    }

    /** SECOND live mechanism for Claude OAuth: a `claude setup-token` login
     * writes NO credentials file — the token lives as an `export
     * CLAUDE_CODE_OAUTH_TOKEN=…` line in ~/.profile (our login flow persists
     * it there; the CLI reads the same var). Every slot operation must treat
     * the Claude OAuth credential as (file AND/OR env line): ignoring the env
     * half left the server LOGGED IN after "remove account". */
    private val oauthEnvVar: String? = when (agent) {
        Agent.CLAUDE -> "CLAUDE_CODE_OAUTH_TOKEN"
        else -> null
    }

    private val slotsDir = "\$HOME/.sshai-auth/${agent.name.lowercase()}/slots"

    /** List saved accounts. Each prints as one TAB-joined line:
     *  `SLOT <id> method=.. label=.. created=.. masked=..`.
     *  Returns null when the exec itself failed (SSH hiccup, no connection) so
     *  the caller can keep showing the last-known accounts instead of blanking
     *  them; an empty list means the listing ran and there genuinely are none. */
    suspend fun listSlots(): List<Slot>? {
        val out = SilentlyTry.logged(TAG, "list slots") {
            exec(
                "bash -lc " + sh(
                    "for d in $slotsDir/*/; do " +
                        "[ -f \"\${d}meta\" ] && printf 'SLOT %s\\t%s\\n' \"\$(basename \"\$d\")\" \"\$(tr '\\n' '\\t' < \"\${d}meta\")\"; " +
                        "done 2>/dev/null"
                )
            )
        } ?: return null
        return out.lineSequence().mapNotNull { line ->
            if (!line.startsWith("SLOT ")) return@mapNotNull null
            val afterTag = line.removePrefix("SLOT ")
            val id = afterTag.substringBefore('\t', "").trim()
            if (id.isEmpty()) return@mapNotNull null
            val meta = afterTag.substringAfter('\t', "")
            val kv = meta.split('\t').mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i).trim() to it.substring(i + 1)
            }.toMap()
            Slot(
                id = id,
                method = kv["method"]?.trim().orEmpty(),
                label = kv["label"]?.trim()?.takeIf { it.isNotEmpty() } ?: id,
                createdAt = kv["created"]?.trim()?.toLongOrNull() ?: 0L,
                masked = kv["masked"]?.trim()?.takeIf { it.isNotEmpty() },
            )
        }.toList()
    }

    /** Snapshot the currently-live account into a new slot. For API-key slots
     *  we save the `export` line + a masked preview. Returns the slot id, or
     *  null if there's nothing live to capture. [slotId] is app-generated. */
    suspend fun captureLive(slotId: String, method: String, label: String): String? {
        val safeId = slotId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dir = "$slotsDir/$safeId"
        val script = if (method == "api") {
            // Pull the export line from the profile, save it, compute a mask.
            "LINE=\$(grep -hE \"^[[:space:]]*export[[:space:]]+$envVar=\" \$HOME/.profile \$HOME/.bashrc \$HOME/.bash_profile 2>/dev/null | tail -1); " +
                "[ -n \"\$LINE\" ] || { echo NOLIVE; exit 0; }; " +
                "KEY=\$(printf '%s' \"\$LINE\" | sed -E \"s/^[[:space:]]*export[[:space:]]+$envVar=//; s/^['\\\"]//; s/['\\\"]\$//\"); " +
                "if [ \${#KEY} -gt 10 ]; then MASK=\"\${KEY:0:6}…\${KEY: -4}\"; else MASK='••••'; fi; " +
                "mkdir -p \"$dir\" && printf '%s\\n' \"\$LINE\" > \"$dir/cred\" && " +
                "printf 'method=api\\nlabel=%s\\ncreated=%s\\nmasked=%s\\n' ${sh(label)} \"\$(date +%s)\" \"\$MASK\" > \"$dir/meta\" && echo OK"
        } else {
            // OAuth: the live credential may be a FILE, an ENV LINE (Claude
            // setup-token), or both. Capture whichever exists; NOLIVE only when
            // neither does. The file half is captured only when it actually
            // CARRIES a token (a dead file with empty values is not an account).
            val fileGate = if (agent == Agent.CLAUDE)
                "[ -f \"$liveCred\" ] && grep -qE '\"(access_?[Tt]oken|refresh_?[Tt]oken)\"[[:space:]]*:[[:space:]]*\"[^\"]+\"' \"$liveCred\" 2>/dev/null"
            else
                "[ -f \"$liveCred\" ]"
            val envCapture = oauthEnvVar?.let {
                "LINE=\$(grep -hE \"^[[:space:]]*export[[:space:]]+$it=\" \$HOME/.profile 2>/dev/null | tail -1); " +
                    "[ -n \"\$LINE\" ] && printf '%s\\n' \"\$LINE\" > \"$dir/credenv\" && HAVE=y; "
            } ?: ""
            "HAVE=n; mkdir -p \"$dir\"; " +
                "if $fileGate; then cp \"$liveCred\" \"$dir/cred\" && HAVE=y; fi; " +
                envCapture +
                "[ \"\$HAVE\" = y ] || { rm -rf \"$dir\"; echo NOLIVE; exit 0; }; " +
                "printf 'method=%s\\nlabel=%s\\ncreated=%s\\n' ${sh(method)} ${sh(label)} \"\$(date +%s)\" > \"$dir/meta\" && echo OK"
        }
        val ok = SilentlyTry.logged(TAG, "capture live -> slot") { exec("bash -lc " + sh(script)) }
        return if (ok?.contains("OK") == true) safeId else null
    }

    /** Make [slotId] the active account. OAuth: copy its file to the live
     *  path. API: rewrite the `export <VAR>=` line in ~/.profile. */
    suspend fun activate(slotId: String): Boolean {
        val safeId = slotId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dir = "$slotsDir/$safeId"
        val out = SilentlyTry.logged(TAG, "activate slot") {
            exec(
                "bash -lc " + sh(
                    "[ -f \"$dir/cred\" ] || [ -f \"$dir/credenv\" ] || { echo NOSLOT; exit 0; }; " +
                        "M=\$(sed -nE 's/^method=//p' \"$dir/meta\" 2>/dev/null | head -1); " +
                        "if [ \"\$M\" = api ]; then " +
                        "  touch \$HOME/.profile; sed -i.bak \"/^[[:space:]]*export[[:space:]]\\+$envVar=/d\" \$HOME/.profile 2>/dev/null; " +
                        "  cat \"$dir/cred\" >> \$HOME/.profile; " +
                        "else " +
                        // Make live state EXACTLY the slot's content, across BOTH
                        // mechanisms — a leftover env token would shadow the file
                        // for the CLI, silently keeping the previous account active.
                        (oauthEnvVar?.let {
                            "  touch \$HOME/.profile; sed -i.bak \"/^[[:space:]]*export[[:space:]]\\+$it=/d\" \$HOME/.profile 2>/dev/null; " +
                                "  [ -f \"$dir/credenv\" ] && cat \"$dir/credenv\" >> \$HOME/.profile; "
                        } ?: "") +
                        "  if [ -f \"$dir/cred\" ]; then mkdir -p \"\$(dirname \"$liveCred\")\" && cp \"$dir/cred\" \"$liveCred\"; else rm -f \"$liveCred\"; fi; " +
                        "fi; echo OK"
                )
            )
        }
        return out?.contains("OK") == true
    }

    /** Remove a saved account. When [clearLiveIfActive], also wipe the live
     *  credential (real log-out): the OAuth file, or the profile export line. */
    suspend fun remove(slotId: String, clearLiveIfActive: Boolean): Boolean {
        val safeId = slotId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dir = "$slotsDir/$safeId"
        val clear = if (clearLiveIfActive) {
            // Real log-out = wipe EVERY live mechanism this method can use. For
            // Claude OAuth that's the credentials file AND the setup-token env
            // line — leaving the env line kept the server logged in after the
            // last account was removed (probe then honestly said "ready" while
            // the sheet said "No accounts").
            "M=\$(sed -nE 's/^method=//p' \"$dir/meta\" 2>/dev/null | head -1); " +
                "if [ \"\$M\" = api ]; then sed -i.bak \"/^[[:space:]]*export[[:space:]]\\+$envVar=/d\" \$HOME/.profile 2>/dev/null; " +
                "else rm -f \"$liveCred\"; " +
                (oauthEnvVar?.let { "sed -i.bak \"/^[[:space:]]*export[[:space:]]\\+$it=/d\" \$HOME/.profile 2>/dev/null; " } ?: "") +
                "fi; "
        } else ""
        val out = SilentlyTry.logged(TAG, "remove slot") {
            exec("bash -lc " + sh(clear + "rm -rf \"$dir\"; echo OK"))
        }
        return out?.contains("OK") == true
    }

    /** Rename a slot — rewrite its label, preserving everything else. */
    suspend fun rename(slotId: String, method: String, createdAt: Long, label: String): Boolean {
        val safeId = slotId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dir = "$slotsDir/$safeId"
        val out = SilentlyTry.logged(TAG, "rename slot") {
            exec(
                "bash -lc " + sh(
                    "[ -d \"$dir\" ] || { echo NOSLOT; exit 0; }; " +
                        // keep the masked line (if any) as-is; only swap label.
                        "MASKED=\$(sed -nE 's/^masked=//p' \"$dir/meta\" 2>/dev/null | head -1); " +
                        "{ printf 'method=%s\\nlabel=%s\\ncreated=%s\\n' ${sh(method)} ${sh(label)} ${sh(createdAt.toString())}; " +
                        "[ -n \"\$MASKED\" ] && printf 'masked=%s\\n' \"\$MASKED\"; } > \"$dir/meta\" && echo OK"
                )
            )
        }
        return out?.contains("OK") == true
    }

    private fun sh(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    companion object {
        private const val TAG = "SshAi-Vault"

        /** OAuth (file) and API-key methods are both juggleable accounts now.
         *  Vertex / Bedrock / Bearer are ambient/proxy — not slotted. */
        fun isSlottable(methodKey: String?): Boolean =
            methodKey == "oauth" || methodKey == "chatgpt" || methodKey == "api"

        // Display cache of the last listed slots per (server, agent). The
        // account sheet seeds from this INSTANTLY on open so it never flashes
        // "no accounts" while a fresh listSlots round-trips over SSH (the user:
        // "saved once → don't re-detect and show it late every time"). Source
        // of truth stays server-side; refreshed on every open + every mutation.
        private val slotCache = java.util.concurrent.ConcurrentHashMap<String, List<Slot>>()
        private fun slotKey(serverId: String, agent: Agent) = "$serverId/${agent.name}"
        fun cachedSlots(serverId: String, agent: Agent): List<Slot>? = slotCache[slotKey(serverId, agent)]
        fun cacheSlots(serverId: String, agent: Agent, slots: List<Slot>) {
            slotCache[slotKey(serverId, agent)] = slots
        }
    }
}
