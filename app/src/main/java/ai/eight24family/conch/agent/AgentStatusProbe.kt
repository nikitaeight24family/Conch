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
    /**
     * Whether a third-party agent guard running on the SERVER is currently
     * protecting THIS CLI. Read-only: Conch never installs, launches or
     * consults one — those tools hook each CLI through the CLI's own hooks, so
     * they already cover our turns the moment the user installs one.
     *
     *   null  — no guard on this server, or it does not know this CLI
     *   true  — guard is on and manages this CLI's harness
     *   false — guard is installed and knows this CLI, but is not managing it
     *
     * Deliberately three states: a flat Boolean would have to call "no guard
     * installed" and "guard installed but this CLI is uncovered" the same
     * thing, and only one of those is worth a word on screen.
     */
    val guardProtecting: Boolean? = null,
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

/** What kind of machine answered the OS pre-probe. */
enum class ServerOs { UNIX, WINDOWS }

class AgentStatusProbe(private val ssh: SshClient) {

    companion object {
        /** Newest published version seen per agent, across ALL servers. The
         *  registry "latest" is server-independent (it's the same npm/installer
         *  release everywhere), so a server whose own probe couldn't fetch it
         *  (npm not on PATH / network blip / registry hiccup) reuses a sibling
         *  server's value → it still shows "update" instead of a misleading
         *  "log in" for a CLI that's plainly behind. Process-scoped. */
        private val knownLatest = java.util.concurrent.ConcurrentHashMap<Agent, String>()

    /** Internal (not private) so the k=v → status fold — including the guard
     *  block's three states — is testable without an SSH server. */
    internal fun parse(text: String): Map<Agent, AgentStatus> {
        val kv = text.lineSequence()
            .mapNotNull { line ->
                val eq = line.indexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
                line.substring(0, eq).trim() to line.substring(eq + 1).trim()
            }
            .toMap()
        fun y(key: String) = kv[key].equals("y", ignoreCase = true)
        // Server-wide guard state (see GUARD_PROBE_LINES), folded per-agent below.
        val guardPresent = y("guard_present")
        val guardOn = y("guard_on")
        val guardManaged = (kv["guard_managed"] ?: "")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
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
                // Only a CLI the guard actually knows can be reported on; the rest
                // stay null and say nothing (see AgentCliSpec.guardHarnessId).
                guardProtecting = spec.guardHarnessId
                    ?.takeIf { guardPresent }
                    ?.let { guardOn && it in guardManaged },
            )
        }
    }

        /**
         * OS pre-probe. Deliberately NOT `bash -lc`, NOT [RemoteEnv.portable]:
         * on a Windows OpenSSH server the default shell is cmd.exe (sometimes
         * PowerShell), where any sh wrapper is itself the failure. The plain
         * command works on every shell:
         *  - unix sh: `uname -s` prints Linux/Darwin/…;
         *  - cmd.exe: `uname` is unknown, `2>/dev/null` is a bad path — either
         *    way the command fails and cmd's own `||` echoes the sentinel;
         *  - PowerShell 5.1: `||` is a parse error whose message the classifier
         *    recognises by shape.
         */
        const val OS_PROBE_CMD = "uname -s 2>/dev/null || echo CONCH_NO_UNAME"

        /** Windows-shell error shapes, matched on the COMBINED stdout+stderr
         *  a probe hands back. Kept short and specific — a unix uname output
         *  can never contain these. */
        private val WINDOWS_SHAPES = listOf(
            "CONCH_NO_UNAME",
            "is not recognized",           // cmd.exe unknown-command wording
            "CommandNotFoundException",     // PowerShell unknown command
            "is not a valid statement separator", // PowerShell 5.1 on `||`
            "cmdlet",                       // PowerShell suggestions text
            "The system cannot find",       // cmd.exe bad redirect path
        )

        /**
         * Classify the pre-probe output. null in → null out (transport failed
         * — claim nothing). Windows shapes → [ServerOs.WINDOWS]. Anything
         * non-blank else (uname printed SOMETHING) → [ServerOs.UNIX]. A ran-
         * but-blank result stays null: PowerShell's parse error lands on
         * stderr and some transports drop it, but "no evidence" must never
         * become a Windows verdict that hides real agents on a unix box.
         */
        fun classifyOsProbe(out: String?): ServerOs? {
            if (out == null) return null
            if (WINDOWS_SHAPES.any { out.contains(it, ignoreCase = true) }) return ServerOs.WINDOWS
            return if (out.isBlank()) null else ServerOs.UNIX
        }
    }

    // NOTE: no OS pre-probe on THIS overload on purpose — it opens a fresh
    // handshake per exec, so a pre-probe would double the handshakes (and
    // demand a second FIDO touch on SK servers). Windows detection rides the
    // exec-lambda overload, which every post-connect probe uses — a Windows
    // server is identified the moment its pooled transport first comes up.
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
        /** Invoked with the OS pre-probe's verdict when it produced one.
         *  Callers that persist statuses should persist this too
         *  (AgentStatusCache.saveServerOs) so the picker can say "Windows
         *  OpenSSH server" instead of a misleading "not installed". */
        onOs: (suspend (ServerOs) -> Unit)? = null,
    ): Result<Map<Agent, AgentStatus>> =
        withContext(Dispatchers.IO) {
            // No ServerActivityLog.append here — the caller's exec
            // lambda is AgentSession.execOnLive (or similar), which
            // already logs every command it dispatches. Adding a
            // second entry per call would produce duplicates in the UI.
            runCatching {
                // OS pre-probe FIRST: on a Windows OpenSSH server the sh
                // status script below is pure noise (every agent would read
                // "not installed" for the wrong reason). One tiny exec; the
                // verdict is handed to the caller for persistence.
                val osOut = exec(OS_PROBE_CMD)
                val os = classifyOsProbe(osOut)
                if (os != null) onOs?.invoke(os)
                if (os == ServerOs.WINDOWS) {
                    android.util.Log.i(
                        "Conch-Probe",
                        "OS pre-probe says WINDOWS (${osOut?.trim()?.take(60)}) — skipping agent script",
                    )
                    return@runCatching Agent.entries.associateWith {
                        AgentStatus(installed = false, loggedIn = false)
                    }
                }
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
        val liveSpecs = AgentSpecRegistry.all.filter { it.liveAuthProbeLines.isNotBlank() }
        if (liveSpecs.isEmpty()) return null
        // TIMEOUT_FN: the spec lines guard their CLI invocations with
        // `conch_timeout` (macOS ships no `timeout` binary at all).
        // Parallel per spec, same shape as [script]: these actually SPAWN the
        // CLIs, the slowest single one should be the whole wait.
        val pathPrep = RemoteEnv.PATH_PREAMBLE.trimEnd() + "\n" + RemoteEnv.TIMEOUT_FN
        val body = pathPrep + "\nCPD=\$(mktemp -d 2>/dev/null || echo /tmp/conch-live.\$\$)\nmkdir -p \"\$CPD\"\n" +
            liveSpecs.mapIndexed { i, spec ->
                "( {\n" + spec.liveAuthProbeLines + "\n} > \"\$CPD/$i.out\" ) &"
            }.joinToString("\n") +
            "\nwait\ncat \"\$CPD\"/*.out 2>/dev/null\nrm -rf \"\$CPD\""
        return RemoteEnv.portable("bash -lc " + shellEscape(body))
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
        // Cache helpers for the two expensive lookups every spec makes. On a
        // busy 1-vCPU box a single node CLI start costs seconds (measured
        // 2026-08-18: `gemini --version` 3.2-7.4s under REACH load) and an
        // `npm view` is a registry round-trip — both answers barely ever
        // change. A CLI's version changes only when its binary does → keyed by
        // the binary's mtime (-L: npm swaps symlinks on update). The registry's
        // latest moves a few times a week → 6h TTL. Cache lives server-side in
        // ~/.cache/conch; a miss falls through to the real command, so a wiped
        // cache costs one slow probe, never a wrong answer.
        val cacheHelpers =
            "conch_ver() { " +
                "b=\$(command -v \"\$2\" 2>/dev/null) || { echo ''; return; }; " +
                "m=\$(stat -Lc %Y \"\$b\" 2>/dev/null || stat -L -f %m \"\$b\" 2>/dev/null || echo 0); " +
                "cf=\"\$HOME/.cache/conch/ver-\$1\"; " +
                "if [ -f \"\$cf\" ]; then read cm cv < \"\$cf\" 2>/dev/null; " +
                "if [ \"\$cm\" = \"\$m\" ] && [ -n \"\$cv\" ]; then echo \"\$cv\"; return; fi; fi; " +
                "v=\$(\"\$2\" --version 2>/dev/null | grep -oE '[0-9]+\\.[0-9]+\\.[0-9]+' | head -1); " +
                "mkdir -p \"\$HOME/.cache/conch\" 2>/dev/null; " +
                "[ -n \"\$v\" ] && printf '%s %s\\n' \"\$m\" \"\$v\" > \"\$cf\" 2>/dev/null; " +
                "echo \"\$v\"; }\n" +
            "conch_latest() { " +
                "cf=\"\$HOME/.cache/conch/latest-\$1\"; now=\$(date +%s); " +
                "if [ -f \"\$cf\" ]; then read ts lv < \"\$cf\" 2>/dev/null; " +
                "if [ -n \"\$lv\" ] && [ \$(( now - \${ts:-0} )) -lt 21600 ] 2>/dev/null; then echo \"\$lv\"; return; fi; fi; " +
                "lv=\$(command -v npm >/dev/null 2>&1 && npm view \"\$2\" version 2>/dev/null | tr -d '\\r\\n '); " +
                "mkdir -p \"\$HOME/.cache/conch\" 2>/dev/null; " +
                "[ -n \"\$lv\" ] && printf '%s %s\\n' \"\$now\" \"\$lv\" > \"\$cf\" 2>/dev/null; " +
                "echo \"\$lv\"; }\n"
        val pathPrep = RemoteEnv.PATH_PREAMBLE.trimEnd() + "\n" + RemoteEnv.TIMEOUT_FN + "\n" + cacheHelpers
        // The specs run IN PARALLEL, each in its own subshell with its own
        // output file. Serial, the probe paid the SUM of every slow piece —
        // measured on the dev server 2026-08-18: `gemini --version` alone 3.2s,
        // plus three `npm view` round-trips and the Claude run-state curls, so
        // the picker spun for 4-20s. Parallel, the wall time is the slowest
        // single spec. Per-block files keep the k=v lines unmangled (the parser
        // reads an unordered map); subshells also isolate the specs' helper
        // functions from each other.
        val body = pathPrep + "\nCPD=\$(mktemp -d 2>/dev/null || echo /tmp/conch-probe.\$\$)\nmkdir -p \"\$CPD\"\n" +
            (AgentSpecRegistry.all.mapIndexed { i, spec ->
                "( {\n" + spec.statusProbeLines + "\n} > \"\$CPD/$i.out\" ) &"
            } + listOf("( {\n" + GUARD_PROBE_LINES + "\n} > \"\$CPD/guard.out\" ) &"))
                .joinToString("\n") +
            "\nwait\ncat \"\$CPD\"/*.out 2>/dev/null\nrm -rf \"\$CPD\""
        return RemoteEnv.portable("bash -lc " + shellEscape(body))
    }

    /**
     * Reads a third-party agent guard's state off the server. ONE block for all
     * agents (the guard is per-machine, its coverage is per-CLI), running in the
     * same parallel fan-out as the specs, so it costs wall time only when it is
     * the slowest branch.
     *
     * CACHED, because it is expensive: `hol-guard status --json` measured 6.65 s
     * on a warm machine — it walks every harness's artifacts — which is more
     * than the whole rest of the probe. Keyed on the mtime of `~/.hol-guard`,
     * exactly the way `conch_ver` keys on a binary's mtime: that directory
     * changes when a harness is installed, repaired or disconnected, which is
     * precisely when this answer changes.
     *
     * Parsed with awk rather than python. Guard IS a Python package so an
     * interpreter is certainly present, but a heredoc inside this doubly-escaped
     * body is a trap for whoever edits it next. The pretty-printed JSON always
     * puts `"harness": "<id>"` above that object's `"managed": true`, so keeping
     * the last id seen is enough. Should a future guard emit compact JSON the
     * match finds nothing and the app shows NO guard state at all: it can
     * under-report, never over-report. An unearned "protected" is the single
     * outcome that would actually matter.
     */
    private val GUARD_PROBE_LINES: String =
        "if command -v hol-guard >/dev/null 2>&1; then\n" +
        "  gm=\$(stat -Lc %Y \"\$HOME/.hol-guard\" 2>/dev/null || stat -L -f %m \"\$HOME/.hol-guard\" 2>/dev/null || echo 0)\n" +
        "  gcf=\"\$HOME/.cache/conch/guard\"\n" +
        "  gk=\$(head -1 \"\$gcf\" 2>/dev/null)\n" +
        // mtime alone is not quite enough: it catches an entry added or removed
        // in ~/.hol-guard, but a guard that rewrites state inside a SUBdirectory
        // touches the subdirectory, not the parent — and a cache that can never
        // expire would keep answering with yesterday's coverage. A 6 h ceiling
        // bounds that to one stale window at a cost of four probes a day.
        "  gage=\$(( \$(date +%s) - \$(stat -Lc %Y \"\$gcf\" 2>/dev/null || stat -L -f %m \"\$gcf\" 2>/dev/null || echo 0) ))\n" +
        "  if [ -f \"\$gcf\" ] && [ \"\$gk\" = \"\$gm\" ] && [ \"\$gage\" -lt 21600 ] 2>/dev/null; then\n" +
        "    tail -n +2 \"\$gcf\" 2>/dev/null\n" +
        "  else\n" +
        "    gj=\$(mktemp 2>/dev/null || echo /tmp/conch-guard.\$\$)\n" +
        "    conch_timeout 25 hol-guard status --json > \"\$gj\" 2>/dev/null\n" +
        "    if [ -s \"\$gj\" ]; then\n" +
        "      gon=n; grep -q '\"protection_off\": *false' \"\$gj\" && gon=y\n" +
        "      gmg=\$(awk -F'\"' '/\"harness\": *\"/ {h=\$4} /\"managed\": *true/ {if (h != \"\") print h}' \"\$gj\" | tr '\n' ',' | sed 's/,\$//')\n" +
        "      mkdir -p \"\$HOME/.cache/conch\" 2>/dev/null\n" +
        "      { printf '%s\n' \"\$gm\"; printf 'guard_present=y\n'; printf 'guard_on=%s\n' \"\$gon\"; printf 'guard_managed=%s\n' \"\$gmg\"; } > \"\$gcf\" 2>/dev/null\n" +
        "      printf 'guard_present=y\n'; printf 'guard_on=%s\n' \"\$gon\"; printf 'guard_managed=%s\n' \"\$gmg\"\n" +
        "    else\n" +
        "      printf 'guard_present=y\n'\n" +
        "    fi\n" +
        "    rm -f \"\$gj\"\n" +
        "  fi\n" +
        "else\n" +
        "  printf 'guard_present=n\n'\n" +
        "fi"

}
