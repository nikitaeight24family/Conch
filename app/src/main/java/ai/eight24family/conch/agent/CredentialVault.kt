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
            "[ -f \"$liveCred\" ] || { echo NOLIVE; exit 0; }; " +
                "mkdir -p \"$dir\" && cp \"$liveCred\" \"$dir/cred\" && " +
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
                    "[ -f \"$dir/cred\" ] || { echo NOSLOT; exit 0; }; " +
                        "M=\$(sed -nE 's/^method=//p' \"$dir/meta\" 2>/dev/null | head -1); " +
                        "if [ \"\$M\" = api ]; then " +
                        "  touch \$HOME/.profile; sed -i.bak \"/^[[:space:]]*export[[:space:]]\\+$envVar=/d\" \$HOME/.profile 2>/dev/null; " +
                        "  cat \"$dir/cred\" >> \$HOME/.profile; " +
                        "else " +
                        "  mkdir -p \"\$(dirname \"$liveCred\")\" && cp \"$dir/cred\" \"$liveCred\"; " +
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
            "M=\$(sed -nE 's/^method=//p' \"$dir/meta\" 2>/dev/null | head -1); " +
                "if [ \"\$M\" = api ]; then sed -i.bak \"/^[[:space:]]*export[[:space:]]\\+$envVar=/d\" \$HOME/.profile 2>/dev/null; " +
                "else rm -f \"$liveCred\"; fi; "
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
