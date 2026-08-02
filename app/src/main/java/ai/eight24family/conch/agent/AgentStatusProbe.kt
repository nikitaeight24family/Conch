package ai.eight24family.conch.agent

import ai.eight24family.conch.agent.spec.AgentSpecRegistry
import ai.eight24family.conch.domain.Server
import ai.eight24family.conch.domain.ServerSecrets
import ai.eight24family.conch.ssh.SshClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Per-agent install + auth probe. Runs a single SSH exec that emits two
 * lines per agent (`<agent>_inst=y|n`, `<agent>_auth=y|n`) and parses them
 * back into a map.
 *
 * The bash body is **assembled from each `AgentCliSpec.statusProbeLines`**
 * at call time, so adding a fourth agent doesn't require touching this
 * file — drop a new `XxxSpec` into the registry and the probe picks up its
 * lines automatically.
 *
 * Definitions:
 *  - **Installed** = the binary is on the login-shell PATH.
 *  - **Logged in** = a credential file matching the CLI's known auth path
 *    exists (or, for Gemini, an env var is set). A stale or expired
 *    credential reads as logged-in here — that's a known limitation. The
 *    actual CLI call later surfaces real auth failures.
 */
data class AgentStatus(
    val installed: Boolean,
    val loggedIn: Boolean,
    /** Currently-installed binary's reported version (e.g. `2.1.150`).
     *  Null when not installed or version parsing failed. */
    val installedVersion: String? = null,
    /** Latest published version of the CLI per its primary channel
     *  (Claude → claude.ai installer; Codex/Gemini → npm registry).
     *  Null when probe couldn't reach the channel. */
    val latestVersion: String? = null,
    /** Agent-scoped keys of EVERY auth method the probe found configured on
     *  the server (e.g. {"oauth","vertex"}). Resolve to [AuthMethod] via
     *  `AuthMethod.of(agent, key)`. Drives the long-press method switcher.
     *  `loggedIn` is just `methods.isNotEmpty()`. */
    val methods: Set<String> = emptySet(),
    /** The method the CLI will actually use right now (Gemini's
     *  settings.json selectedAuthType, Codex's auth.json shape, Claude's
     *  env/flag precedence), or null if undeterminable. */
    val activeMethod: String? = null,
    /** True between the fast (presence) probe and the LIVE auth verdict for an
     *  agent that has a live check + an on-disk OAuth cred. The UI shows this
     *  row as "checking" (NOT "ready"/"OAuth") so a present-but-revoked cred is
     *  never flashed as usable before [AgentStatusProbe.probeLiveAuth] confirms. */
    val liveAuthPending: Boolean = false,
    /** Claude ONLY: the account's Claude Code run-readiness (auth + subscription +
     *  usage), as the CLI itself models it — see [ClaudeRunState]. null = not
     *  checked / not applicable (Codex, Gemini, Claude api-key mode). A BLOCK state
     *  means the account is logged in but a turn WON'T run (no subscription, trial
     *  ended, payment due, login expired, rate limited, …) — and re-login won't fix
     *  most of them. Resolved server-side from `api/oauth/profile` (+ `usage`). */
    val claudeState: ClaudeRunState? = null,
    /** Small datum for a data-bearing [claudeState] (trial days-left, usage reset
     *  time). Folded into the display via [ClaudeRunState.lineWith]. */
    val claudeStateData: String? = null,
    /** Claude ONLY: the account's plan tier as a display label ("Max", "Pro",
     *  "Pro trial", "Free") for the limits-sheet header — from the 200 profile.
     *  null when unknowable (inference-only setup-token 403s the profile, Codex,
     *  Gemini, api-key mode). */
    val claudePlan: String? = null,
) {
    val ready: Boolean get() = installed && loggedIn && !updateAvailable && claudeState?.isBlocked != true

    /** True when both versions are known AND they differ. We treat
     *  "couldn't fetch latest" as "no update" so a network blip
     *  doesn't gate the user out of their session. */
    val updateAvailable: Boolean
        get() = installed &&
            !installedVersion.isNullOrBlank() &&
            !latestVersion.isNullOrBlank() &&
            isVersionLessThan(installedVersion, latestVersion)
}

/**
 * Semver-aware "is `a` strictly older than `b`?" — compares numeric
 * components left-to-right, with missing components treated as 0.
 * Used to gate the `[ update ]` button so we don't yell at the user
 * to "update" when their installed version is actually NEWER than
 * what the registry mirror reports (Claude's deprecated npm package
 * can lag the official installer by several releases).
 */
internal fun isVersionLessThan(a: String, b: String): Boolean {
    val pa = a.split('.').mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
    val pb = b.split('.').mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
    if (pa.isEmpty() || pb.isEmpty()) return false
    val n = maxOf(pa.size, pb.size)
    for (i in 0 until n) {
        val ai = pa.getOrElse(i) { 0 }
        val bi = pb.getOrElse(i) { 0 }
        if (ai != bi) return ai < bi
    }
    return false
}

class AgentStatusProbe(private val ssh: SshClient) {

    companion object {
        /** Newest published version seen per agent, across ALL servers. The
         *  registry "latest" is server-independent (it's the same npm/installer
         *  release everywhere), so a server whose own probe couldn't fetch it
         *  (npm not on PATH / network blip / registry hiccup) reuses a sibling
         *  server's value → it still shows "update" instead of a misleading
         *  "log in" for a CLI that's plainly behind. Process-scoped. */
        private val knownLatest = java.util.concurrent.ConcurrentHashMap<Agent, String>()
    }

    suspend fun probe(
        server: Server,
        secrets: ServerSecrets,
        skSigner: ai.eight24family.conch.ssh.securitykey.SkSigner? = null,
    ): Result<Map<Agent, AgentStatus>> =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val result = ssh.execute(server, secrets, script(), skSigner)
            ai.eight24family.conch.data.ServerActivityLog.append(
                server.id,
                ai.eight24family.conch.data.ServerActivityLog.Entry(
                    ts = start,
                    category = "probe",
                    command = "agent status probe (which / --version / npm view)",
                    exitCode = if (result.isSuccess) 0 else -1,
                    stdoutTail = (result.getOrNull() ?: "").takeLast(200),
                    durationMs = System.currentTimeMillis() - start,
                ),
            )
            result.map { parse(it) }
        }

    /**
     * Variant driven by a caller-provided exec lambda — used when there's
     * already an authenticated SSH channel sitting open
     * (`AgentSession::execOnLive` of an alive chat). Lets the agent picker
     * pull-to-refresh probe FIDO security-key servers without firing a
     * fresh handshake / second touch.
     */
    suspend fun probe(
        exec: suspend (cmd: String) -> String?,
    ): Result<Map<Agent, AgentStatus>> =
        withContext(Dispatchers.IO) {
            // No ServerActivityLog.append here — the caller's exec
            // lambda is AgentSession.execOnLive (or similar), which
            // already logs every command it dispatches. Adding a
            // second entry per call would produce duplicates in the UI.
            runCatching {
                val out = exec(script()) ?: error("exec returned null — SSH channel may have dropped")
                parse(out)
            }
        }

    /**
     * LIVE OAuth validation — runs each spec's [AgentCliSpec.liveAuthProbeLines]
     * (which actually INVOKE the CLI, the only way to know a token is usable vs
     * merely present). Slow (spawns CLIs), so callers run it ASYNC and merge the
     * `y`/`n` verdicts into the already-shown fast-probe status. Returns only
     * agents that emitted a verdict; absent ⇒ not checked ⇒ keep presence.
     */
    suspend fun probeLiveAuth(
        exec: suspend (cmd: String) -> String?,
    ): Map<Agent, Boolean> = withContext(Dispatchers.IO) {
        val cmd = liveAuthScript() ?: return@withContext emptyMap()
        val out = runCatching { exec(cmd) }.getOrNull().orEmpty()
        val kv = out.lineSequence().mapNotNull { line ->
            val eq = line.indexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
            line.substring(0, eq).trim() to line.substring(eq + 1).trim()
        }.toMap()
        AgentSpecRegistry.all.mapNotNull { spec ->
            when (kv["${spec.agent.name.lowercase()}_authok"]) {
                "y" -> spec.agent to true
                "n" -> spec.agent to false
                else -> null
            }
        }.toMap()
    }

    /** `bash -lc` wrapper running ONLY specs that define a live-auth check.
     *  Null when none do (nothing to run). */
    private fun liveAuthScript(): String? {
        val live = AgentSpecRegistry.all.joinToString("\n") { it.liveAuthProbeLines }.trim()
        if (live.isBlank()) return null
        val pathPrep = RemoteEnv.PATH_PREAMBLE.trimEnd()
        return "bash -lc " + shellEscape(pathPrep + "\n" + live)
    }

    private fun script(): String {
        // Concat every spec's statusProbeLines body. Wrapped in `bash -lc`
        // so login PATH (nvm, asdf, ~/.local/bin) is sourced — `command -v`
        // against the non-interactive PATH would lie about uninstalled CLIs.
        //
        // PATH preamble — explicitly prepend EVERY known install
        // location Conch's installer might have used. Debian's
        // default ~/.bashrc bails out for non-interactive shells, so
        // nvm.sh and ~/.local/bin additions sourced there are
        // invisible to a plain `bash -lc`. Adding them by hand here
        // means the probe sees what's actually installed without
        // requiring any rc-file patching server-side.
        val pathPrep = RemoteEnv.PATH_PREAMBLE.trimEnd()
        val body = pathPrep + "\n" + AgentSpecRegistry.all.joinToString("\n") { it.statusProbeLines }
        return "bash -lc " + shellEscape(body)
    }

    private fun parse(text: String): Map<Agent, AgentStatus> {
        val kv = text.lineSequence()
            .mapNotNull { line ->
                val eq = line.indexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
                line.substring(0, eq).trim() to line.substring(eq + 1).trim()
            }
            .toMap()
        fun y(key: String) = kv[key].equals("y", ignoreCase = true)
        // Each spec's statusProbeLines emits:
        //   <agent>_inst    = y/n
        //   <agent>_ver     = installed semver (e.g. "2.1.150") or empty
        //   <agent>_latest  = latest published semver or empty
        //   <agent>_methods = csv of detected auth-method keys (e.g. "oauth,vertex")
        //   <agent>_active  = the currently-active method key (or empty)
        // loggedIn is derived: any configured method == logged in. (The old
        // single `_auth=y/n` line missed Vertex/ADC for Gemini → false
        // "not logged in"; per-method detection fixes that.)
        return AgentSpecRegistry.all.associate { spec ->
            val tag = spec.agent.name.lowercase()
            var methods = (kv["${tag}_methods"] ?: "")
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            // LIVE auth result (when the spec ran the CLI for real): "n" = the
            // CLI demanded re-auth, so the on-disk OAuth cred is NOT usable —
            // drop the OAuth methods so the badge can't falsely claim "OAuth /
            // ready". "y" confirms; absent = not checked (fall back to presence).
            val authok = kv["${tag}_authok"]
            if (authok == "n") methods = methods - setOf("oauth", "chatgpt")
            // No live verdict yet for an agent that HAS a live check + an OAuth
            // cred → pending. The row renders "checking" (not "ready"/"OAuth")
            // so a present-but-revoked cred is never flashed as usable.
            val pendingLive = authok == null &&
                spec.liveAuthProbeLines.isNotBlank() &&
                methods.any { it == "oauth" || it == "chatgpt" }
            // The probe reports which method the CLI will ACTUALLY use (the
            // explicit selection: Gemini's settings.json selectedType, Claude's
            // env precedence, Codex's `login status`). A CLI honors that
            // selection over a stray credential it isn't configured to use —
            // e.g. Gemini with settings selectedType=oauth-personal but NO OAuth
            // credential ignores an API-key NAME sitting in .bashrc and demands
            // an OAuth login. So when an explicit selection exists and is NOT
            // among the usable methods, the agent is NOT ready: it must read
            // "log in", never a false "API ready" (the bug the user hit). For
            // Claude/Codex `selected ⊆ methods` always holds (active's condition
            // is a subset of the method's), so this is a no-op for them.
            val selected = kv["${tag}_active"]?.trim()?.takeIf { it.isNotEmpty() }
            val selectedUnusable = selected != null && selected !in methods
            // Latest version is REGISTRY-GLOBAL (server-independent). Remember the
            // newest value any server reported, and if THIS server's probe came
            // back blank (e.g. ethernetservers where `npm view @google/gemini-cli`
            // returned nothing), fall back to it — otherwise a plainly-behind CLI
            // shows "log in" instead of "update" just because one box couldn't run
            // `npm view`.
            val rawLatest = kv["${tag}_latest"]?.trim()?.takeIf { it.isNotEmpty() }
            if (rawLatest != null) {
                val prev = knownLatest[spec.agent]
                if (prev == null || isVersionLessThan(prev, rawLatest)) knownLatest[spec.agent] = rawLatest
            }
            val effectiveLatest = rawLatest ?: knownLatest[spec.agent]
            spec.agent to AgentStatus(
                installed = y("${tag}_inst"),
                loggedIn = methods.isNotEmpty() && !selectedUnusable,
                installedVersion = kv["${tag}_ver"]?.trim()?.takeIf { it.isNotEmpty() },
                latestVersion = effectiveLatest,
                methods = methods,
                // Active comes from the probe (settings.json / auth.json shape
                // / env precedence). If undeterminable but exactly ONE method
                // is configured, that one is implicitly active (cleaner ● in
                // the switcher). Must still be one of the (live-filtered) methods.
                activeMethod = selected?.takeIf { it in methods } ?: methods.singleOrNull(),
                liveAuthPending = pendingLive && !selectedUnusable,
                // Claude Code run-state (see AgentStatus.claudeState). The probe
                // emits `<agent>_run_state=<NAME>` (+ optional `<agent>_run_data`)
                // only for Claude in OAuth mode; absent ⇒ null (not applicable).
                claudeState = ClaudeRunState.fromToken(kv["${tag}_run_state"]),
                claudeStateData = kv["${tag}_run_data"]?.trim()?.takeIf { it.isNotEmpty() },
                claudePlan = kv["${tag}_plan"]?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    }
}
