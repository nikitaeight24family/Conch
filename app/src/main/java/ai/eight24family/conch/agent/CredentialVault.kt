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
        /** ── Account passport, extracted SERVER-SIDE from the slot's own
         *  credential at first listing (see the enrich block in [listSlots]).
         *  Facts only — email, plan, dates — never token values. All fields
         *  are as-of-capture: a slot is a frozen copy of the credential. ── */
        /** OAuth shape: "file" (a credentials JSON) or "token" (an env-line
         *  long-lived token, Claude `setup-token`). Null for API keys. */
        val kind: String? = null,
        /** Account email, when the credential carries an OIDC id_token
         *  (Codex, Gemini) that names it. */
        val email: String? = null,
        /** Subscription plan: Codex `chatgpt_plan_type` ("team", "plus"…),
         *  Claude full-oauth `subscriptionType` ("max", "pro"…). */
        val plan: String? = null,
        /** Codex: `chatgpt_subscription_active_until`, ISO-8601. */
        val planUntil: String? = null,
        /** Codex: `last_refresh` of the session file, ISO-8601. */
        val lastRefresh: String? = null,
        /** Claude full-oauth: `expiresAt` of the ACCESS token, epoch ms.
         *  Refreshable — NOT an account expiry; kept for diagnostics. */
        val expiresMs: Long? = null,
    )

    /** Live cred FILE for OAuth/file-based methods (HOME-relative, quoted at use). */
    private val liveCred: String = when (agent) {
        Agent.CODEX -> "\$HOME/.codex/auth.json"
        Agent.GEMINI -> "\$HOME/.gemini/oauth_creds.json"
        Agent.CLAUDE -> "\$HOME/.claude/.credentials.json"
        Agent.GROK -> "\$HOME/.grok/auth.json"
        Agent.QWEN -> "\$HOME/.qwen/oauth_creds.json"
        // A completed login (and an API-key exchange, which mints tokens from
        // the key) persists into `~/.cursor/auth.json`; `cli-config.json`
        // holds the non-secret config beside it. Never read — presence and
        // copying only.
        Agent.CURSOR -> "\$HOME/.cursor/auth.json"
        // opencode stores provider credentials in its own data dir; Crush has
        // no credential file at all (providers come from the environment).
        Agent.OPENCODE -> "\$HOME/.local/share/opencode/auth.json"
        Agent.CRUSH -> "\$HOME/.config/crush/crush.json"
        // Continue has no credential file of its own — a provider key lives in
        // its config.yaml or the environment.
        Agent.CONTINUE -> "\$HOME/.continue/config.yaml"
        // Keyring-less servers (the typical VPS): `copilot login` falls back
        // to "a plain text config file under ~/.copilot/" — the settings
        // store carries the copilotToken secret. Best-known location; a
        // keyring-backed desktop login has no file for slots to copy.
        Agent.COPILOT -> "\$HOME/.copilot/config.json"
    }

    /** Env var the API key lives under (in ~/.profile). */
    private val envVar: String = when (agent) {
        Agent.CODEX -> "OPENAI_API_KEY"
        Agent.GEMINI -> "GEMINI_API_KEY"
        Agent.CLAUDE -> "ANTHROPIC_API_KEY"
        Agent.GROK -> "XAI_API_KEY"
        Agent.COPILOT -> "COPILOT_GITHUB_TOKEN"
        // Qwen talks to OpenAI-compatible endpoints; OPENAI_API_KEY (with
        // OPENAI_BASE_URL) is its documented headless setup.
        Agent.QWEN -> "OPENAI_API_KEY"
        Agent.CURSOR -> "CURSOR_API_KEY"
        // Both are provider-agnostic: the key is whichever provider the user
        // picked. ANTHROPIC_API_KEY is the most common default for each.
        Agent.OPENCODE -> "ANTHROPIC_API_KEY"
        Agent.CRUSH -> "ANTHROPIC_API_KEY"
        Agent.CONTINUE -> "ANTHROPIC_API_KEY"
    }

    /** SECOND live mechanism for Claude OAuth: a `claude setup-token` login
     * writes NO credentials file — the token lives as an `export
     * CLAUDE_CODE_OAUTH_TOKEN=…` line in ~/.profile (our login flow persists
     * it there; the CLI reads the same var). Every slot operation must treat
     * the Claude OAuth credential as (file AND/OR env line): ignoring the env
     * half left the server LOGGED IN after "remove account". */
    private val oauthEnvVar: String? = when (agent) {
        Agent.CLAUDE -> "CLAUDE_CODE_OAUTH_TOKEN"
        Agent.CODEX, Agent.GEMINI, Agent.GROK, Agent.COPILOT,
        Agent.QWEN, Agent.CURSOR, Agent.OPENCODE, Agent.CRUSH, Agent.CONTINUE -> null
    }

    private val slotsDir = "\$HOME/.conch-auth/${agent.name.lowercase()}/slots"

    /** One-time server-side move of the whole vault off the dead brand so saved
     *  accounts survive the rename. A MERGE (copy-then-remove), not a bare mv,
     *  so it's safe even if `.conch-auth` was already created by another op —
     *  old slots still fold in; `rm` only after a clean copy; idempotent once
     *  `.sshai-auth` is gone. POSIX (the 2026-08-17 dash/ash sweep). Runs on
     *  the vault's entry point (listSlots), which the account sheet always hits
     *  before any save. */
    private val legacyMigrate =
        "[ -d \"\$HOME/.sshai-auth\" ] && { mkdir -p \"\$HOME/.conch-auth\" && " +
            "cp -r \"\$HOME/.sshai-auth/.\" \"\$HOME/.conch-auth/\" 2>/dev/null && " +
            "rm -rf \"\$HOME/.sshai-auth\"; }; "

    /** List saved accounts. Each prints as one TAB-joined line:
     *  `SLOT <id> method=.. label=.. created=.. masked=..`.
     *  Returns null when the exec itself failed (SSH hiccup, no connection) so
     *  the caller can keep showing the last-known accounts instead of blanking
     *  them; an empty list means the listing ran and there genuinely are none. */
    suspend fun listSlots(): List<Slot>? {
        // Self-healing passport: slots created before the detail fields existed
        // (or by an older app) get their meta enriched IN PLACE on first list —
        // facts are pulled out of the slot's own credential copy, server-side,
        // so no migration step and no re-login is ever needed. `enriched=1`
        // makes it once-per-slot. Extraction is POSIX (the 2026-08-17 sweep:
        // portable() may land on dash/ash): grep/sed JSON field pulls + a
        // base64 JWT payload decode; every field is optional, a parse miss
        // just leaves the line out.
        val enrich =
            "jstr() { printf %s \"\$2\" | grep -oE \"\\\"\$1\\\"[[:space:]]*:[[:space:]]*\\\"[^\\\"]*\\\"\" | head -1 | sed -E 's/^[^:]*:[[:space:]]*\"//; s/\"\$//'; }; " +
            "jnum() { printf %s \"\$2\" | grep -oE \"\\\"\$1\\\"[[:space:]]*:[[:space:]]*[0-9]+\" | head -1 | grep -oE '[0-9]+\$'; }; " +
            "enrich_slot() { " +
                "m=\"\$1/meta\"; grep -q '^enriched=' \"\$m\" 2>/dev/null && return 0; " +
                "KIND=''; EMAIL=''; PLAN=''; PUNTIL=''; LREF=''; EXPMS=''; " +
                "if [ -f \"\$1/cred\" ] && grep -q '^method=api\$' \"\$m\" 2>/dev/null; then KIND=''; " +
                "elif [ -f \"\$1/cred\" ]; then " +
                    "KIND=file; C=\$(tr -d '\\n' < \"\$1/cred\"); " +
                    // OIDC id_token (Codex, Gemini): payload names the account.
                    "JWT=\$(jstr id_token \"\$C\"); " +
                    "if [ -n \"\$JWT\" ]; then " +
                        "JP=\$(printf %s \"\$JWT\" | cut -d. -f2 | tr '_-' '/+'); " +
                        "case \$((\${#JP} % 4)) in 2) JP=\"\$JP==\";; 3) JP=\"\$JP=\";; esac; " +
                        "PAY=\$(printf %s \"\$JP\" | base64 -d 2>/dev/null | tr -d '\\n'); " +
                        "EMAIL=\$(jstr email \"\$PAY\"); " +
                        "PLAN=\$(jstr chatgpt_plan_type \"\$PAY\"); " +
                        "PUNTIL=\$(jstr chatgpt_subscription_active_until \"\$PAY\"); " +
                    "fi; " +
                    "LREF=\$(jstr last_refresh \"\$C\"); " +
                    // Claude full-oauth credentials file.
                    "[ -n \"\$PLAN\" ] || PLAN=\$(jstr subscriptionType \"\$C\"); " +
                    "EXPMS=\$(jnum expiresAt \"\$C\"); " +
                "elif [ -f \"\$1/credenv\" ]; then KIND=token; fi; " +
                "{ [ -n \"\$KIND\" ] && printf 'kind=%s\\n' \"\$KIND\"; " +
                "[ -n \"\$EMAIL\" ] && printf 'email=%s\\n' \"\$EMAIL\"; " +
                "[ -n \"\$PLAN\" ] && printf 'plan=%s\\n' \"\$PLAN\"; " +
                "[ -n \"\$PUNTIL\" ] && printf 'planUntil=%s\\n' \"\$PUNTIL\"; " +
                "[ -n \"\$LREF\" ] && printf 'lastRefresh=%s\\n' \"\$LREF\"; " +
                "[ -n \"\$EXPMS\" ] && printf 'expiresMs=%s\\n' \"\$EXPMS\"; " +
                "printf 'enriched=1\\n'; } >> \"\$m\"; " +
            "}; "
        val out = SilentlyTry.logged(TAG, "list slots") {
            exec(
                "bash -lc " + sh(
                    legacyMigrate + enrich +
                        "for d in $slotsDir/*/; do " +
                        "[ -f \"\${d}meta\" ] || continue; " +
                        "enrich_slot \"\${d%/}\"; " +
                        "printf 'SLOT %s\\t%s\\n' \"\$(basename \"\$d\")\" \"\$(tr '\\n' '\\t' < \"\${d}meta\")\"; " +
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
                kind = kv["kind"]?.trim()?.takeIf { it.isNotEmpty() },
                email = kv["email"]?.trim()?.takeIf { it.isNotEmpty() },
                plan = kv["plan"]?.trim()?.takeIf { it.isNotEmpty() },
                planUntil = kv["planUntil"]?.trim()?.takeIf { it.isNotEmpty() },
                lastRefresh = kv["lastRefresh"]?.trim()?.takeIf { it.isNotEmpty() },
                expiresMs = kv["expiresMs"]?.trim()?.toLongOrNull(),
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
                // POSIX mask — `${KEY:0:6}`/`${KEY: -4}` are bash substring
                // expansion, the one true bashism the 2026-08-17 sweep found:
                // under portable()'s `sh -lc` fallback (dash/ash) it is a hard
                // parse error that kills the whole capture script.
                "if [ \${#KEY} -gt 10 ]; then " +
                "MASK=\"\$(printf '%s' \"\$KEY\" | cut -c1-6)…\$(printf '%s' \"\$KEY\" | awk '{print substr(\$0, length(\$0)-3)}')\"; " +
                "else MASK='••••'; fi; " +
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
                        "  touch \$HOME/.profile; sed -i.bak \"/^[[:space:]]*export[[:space:]]\\+$envVar=/d\" \$HOME/.profile 2>/dev/null; rm -f \$HOME/.profile.bak; " +
                        "  cat \"$dir/cred\" >> \$HOME/.profile; " +
                        "else " +
                        // Make live state EXACTLY the slot's content, across BOTH
                        // mechanisms — a leftover env token would shadow the file
                        // for the CLI, silently keeping the previous account active.
                        (oauthEnvVar?.let {
                            "  touch \$HOME/.profile; sed -i.bak \"/^[[:space:]]*export[[:space:]]\\+$it=/d\" \$HOME/.profile 2>/dev/null; rm -f \$HOME/.profile.bak; " +
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
    // ⛔ EVERY `sed -i.bak` HERE IS FOLLOWED BY `rm -f $HOME/.profile.bak`, and
    // must stay that way. `sed -i.bak` does not edit in place: it RENAMES the
    // original and writes a cleaned copy, so the line this code exists to erase
    // survives in ~/.profile.bak — at the original file's permissions, usually
    // world-readable, deleted by nothing. The comment above says "real log-out =
    // wipe EVERY live mechanism"; without the rm, the last line of that log-out
    // made a copy of the token instead (audit, 2026-08-30). The same applies on
    // every account SWITCH, so the discarded key was always one file away.
    suspend fun remove(slotId: String, clearLiveIfActive: Boolean): Boolean {
        val safeId = slotId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dir = "$slotsDir/$safeId"
        val clear = if (clearLiveIfActive) {
            // Real log-out = wipe EVERY live mechanism this method can use. For
            // Claude OAuth that's the credentials file AND the setup-token env
            // line — leaving the env line kept the server logged in after the
            // last account was removed (probe then honestly said "ready" while
            // the sheet said "No accounts").
            //
            // For Claude, credentials alone are NOT the whole login. Two more
            // things keep the account alive on the server: - IDENTITY RESIDUE:
            // ~/.claude.json carries oauthAccount (email, org — the CLI's banner
            // greets from it) and the account's cachedUsageUtilization. python3
            // first, jq fallback, both absent → residue stays (log- only concern,
            // never a broken JSON). - RUNNING PROCESSES: headless stream-json
            // turns launched before the logout hold the token in their env and
            // keep burning the account (measured: a 4h20m zombie turn survived
            // the logout). A logged-out account has no business computing — kill
            // them. The pattern matches only headless `--output-format
            // stream-json` launches, never a human's interactive `claude` TUI.
            val claudeResidue = if (agent == Agent.CLAUDE)
                "python3 -c 'import json,os;p=os.path.expanduser(\"~/.claude.json\");d=json.load(open(p));[d.pop(k,None) for k in (\"oauthAccount\",\"cachedUsageUtilization\")];json.dump(d,open(p,\"w\"),indent=2)' 2>/dev/null " +
                    "|| { command -v jq >/dev/null 2>&1 && jq 'del(.oauthAccount,.cachedUsageUtilization)' \"\$HOME/.claude.json\" > \"\$HOME/.claude.json.cln\" 2>/dev/null && mv \"\$HOME/.claude.json.cln\" \"\$HOME/.claude.json\"; }; " +
                    // ⚠ [c]laude, not claude: pkill -f matches FULL command
                    // lines, and this very script's own `bash -lc '…'` line
                    // contains the pattern — a plain match killed the shell
                    // running the removal before its `echo OK` («Couldn't
                    // remove — the server didn't confirm», 2026-08-18). The
                    // bracket keeps the regex matching real processes while no
                    // longer matching its own text.
                    "pkill -f -- '[c]laude --output-format stream-json' 2>/dev/null; "
            else ""
            "M=\$(sed -nE 's/^method=//p' \"$dir/meta\" 2>/dev/null | head -1); " +
                "if [ \"\$M\" = api ]; then sed -i.bak \"/^[[:space:]]*export[[:space:]]\\+$envVar=/d\" \$HOME/.profile 2>/dev/null; rm -f \$HOME/.profile.bak; " +
                "else rm -f \"$liveCred\"; " +
                (oauthEnvVar?.let { "sed -i.bak \"/^[[:space:]]*export[[:space:]]\\+$it=/d\" \$HOME/.profile 2>/dev/null; rm -f \$HOME/.profile.bak; " } ?: "") +
                claudeResidue +
                "fi; "
        } else ""
        val out = SilentlyTry.logged(TAG, "remove slot") {
            exec("bash -lc " + sh(clear + "rm -rf \"$dir\"; echo OK"))
        }
        return out?.contains("OK") == true
    }

    /** Rename a slot — swap ONLY the label line, preserving every other meta
     *  line (method/created/masked and the enriched account passport). The old
     *  full-rewrite lost whatever it didn't know to carry over. */
    suspend fun rename(slotId: String, method: String, createdAt: Long, label: String): Boolean {
        val safeId = slotId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dir = "$slotsDir/$safeId"
        val out = SilentlyTry.logged(TAG, "rename slot") {
            exec(
                "bash -lc " + sh(
                    "[ -f \"$dir/meta\" ] || { echo NOSLOT; exit 0; }; " +
                        "grep -v '^label=' \"$dir/meta\" > \"$dir/meta.tmp\" 2>/dev/null; " +
                        "printf 'label=%s\\n' ${sh(label)} >> \"$dir/meta.tmp\" && " +
                        "mv \"$dir/meta.tmp\" \"$dir/meta\" && echo OK"
                )
            )
        }
        return out?.contains("OK") == true
    }

    private fun sh(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    companion object {
        private const val TAG = "Conch-Vault"

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
