package ai.eight24family.conch.agent

import ai.eight24family.conch.agent.spec.CliContracts
import ai.eight24family.conch.data.prefs.AgentApprovalMode
import ai.eight24family.conch.util.SilentlyTry

/**
 * Check that the flags behind SAFE / AUTO / YOLO are still accepted by the CLI
 * that is actually installed — by RUNNING them, not by reading documentation.
 *
 * ⛔ GREPPING `--help` DOES NOT WORK. Measured on 2026-08-27:
 *
 *  - `claude --permission-prompt-tool` is absent from `--help` at 2.1.247 and
 *    still perfectly accepted. A help-grep would have raised a false alarm and
 *    disabled a mode that works.
 *  - `codex exec --ask-for-approval untrusted` greps clean against the
 *    TOP-LEVEL help (the flag exists there) while `codex exec` rejects it as an
 *    unexpected argument, and `untrusted` had been retired as a value. A
 *    help-grep would have missed a mode that was completely broken.
 *
 * So the audit replays the mode's real arguments and appends `--help`: the CLI
 * parses the whole command line, then exits before contacting any model. An
 * unknown flag, a wrong position, or a retired value all fail at parse time
 * with a non-zero exit — the same way they fail in a real turn — and it costs
 * nothing: no tokens, no side effects, no files touched.
 *
 * This is the mechanism that found the Codex breakage in the first place, and
 * it runs after every install or update so the next drift is caught by the app
 * instead of by the user.
 */
object CliFlagAudit {

    private const val TAG = "SshAi-FlagAudit"

    /** One mode's verdict. [error] is the CLI's own first complaint. */
    data class ModeVerdict(
        val mode: AgentApprovalMode,
        val accepted: Boolean,
        val error: String? = null,
    )

    data class Report(
        val agent: Agent,
        /** Version reported by the binary right now. */
        val installedVersion: String?,
        /** Version the mapping was verified against, null when unrecorded. */
        val testedVersion: String?,
        val modes: List<ModeVerdict>,
    ) {
        val rejected: List<AgentApprovalMode> get() = modes.filter { !it.accepted }.map { it.mode }
        val allAccepted: Boolean get() = modes.isNotEmpty() && modes.all { it.accepted }
        /** Running something newer than what was checked. Not an error — a fact
         *  worth showing, because THIS is the window drift lives in. */
        val untestedVersion: Boolean
            get() = installedVersion != null && testedVersion != null &&
                installedVersion != testedVersion

        /** One line for the mode sheet. Never claims more than was checked. */
        fun summary(): String = when {
            testedVersion == null -> "modes not audited for this agent"
            installedVersion == null -> "tested against $testedVersion · installed version unknown"
            rejected.isNotEmpty() ->
                "⚠ ${rejected.joinToString("/") { it.name }} rejected by $installedVersion " +
                    "(tested against $testedVersion)"
            untestedVersion ->
                "tested against $testedVersion · you're on $installedVersion · flags still accepted"
            else -> "verified against $installedVersion"
        }
    }

    /**
     * Run the audit over [exec], which must run a command on the server and
     * return its combined output with an `EXIT:<code>` trailer — see
     * [auditCommand]. One SSH command for all modes, so this is cheap enough to
     * run on every install.
     */
    suspend fun run(
        agent: Agent,
        exec: suspend (cmd: String) -> String?,
    ): Report? {
        val contract = CliContracts[agent] ?: return null
        val cli = agent.cliCommand
        val out = exec(auditCommand(agent)) ?: return null
        val version = Regex("""^VERSION:(.*)$""", RegexOption.MULTILINE)
            .find(out)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        val verdicts = contract.modes.map { m ->
            val block = Regex(
                """^MODE:${m.mode.name}:(\d+)(.*?)(?=^MODE:|\z)""",
                setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL),
            ).find(out)
            if (block == null) {
                ModeVerdict(m.mode, accepted = false, error = "no result")
            } else {
                val code = block.groupValues[1].toIntOrNull() ?: -1
                val body = block.groupValues[2]
                ModeVerdict(
                    mode = m.mode,
                    accepted = code == 0,
                    error = if (code == 0) null else firstComplaint(body),
                )
            }
        }
        val report = Report(agent, version, contract.testedVersion, verdicts)
        android.util.Log.i(
            TAG,
            "$cli audit: installed=$version tested=${contract.testedVersion} " +
                "rejected=${report.rejected.joinToString(",")}",
        )
        return report
    }

    /**
     * The shell the audit runs. Emits `VERSION:<v>` then, per mode,
     * `MODE:<NAME>:<exit>` followed by whatever the CLI said.
     *
     * `--help` is appended to every probe on purpose: the CLI parses the flags
     * first and exits before any network call, so a rejected flag is reported
     * without spending a turn. stderr is folded in because that is where clap
     * and commander write their complaints.
     */
    fun auditCommand(agent: Agent): String {
        val contract = CliContracts[agent] ?: return "true"
        val cli = agent.cliCommand
        val sb = StringBuilder()
        sb.append("export PATH=\"\$HOME/.local/bin:\$PATH\"; ")
        // ⛔ THE AUDIT MUST NOT MOVE THE THING IT IS AUDITING.
        //
        // Grok's CLI self-updates on invocation unless this is set (GrokSpec
        // exports it on every real call). Without it, the one routine whose
        // whole job is to guarantee the PINNED version's flags could quietly
        // upgrade the binary off that pin — the audit would be the drift.
        // Harmless for the other CLIs, so it is exported unconditionally rather
        // than special-cased and forgotten.
        //
        // ⚠ And every probe below passes ARGUMENTS. A bare `grok` opens a blind
        // TUI and blocks forever on browser OAuth; `grok <args> --help` is safe.
        // Never build a probe that invokes a CLI with no arguments.
        sb.append("export GROK_DISABLE_AUTOUPDATER=1; ")
        sb.append("command -v $cli >/dev/null 2>&1 || { echo 'VERSION:'; exit 0; }; ")
        sb.append("echo \"VERSION:\$($cli --version 2>&1 | head -1)\"; ")
        for (m in contract.modes) {
            // The exec-path invocation shape per agent: codex's flags belong to
            // the `exec` subcommand, the others take them at top level.
            val invocation = if (agent == Agent.CODEX) "$cli exec" else cli
            val args = m.args.joinToString(" ")
            sb.append("out=\$($invocation $args --help 2>&1); rc=\$?; ")
            sb.append("echo \"MODE:${m.mode.name}:\$rc\"; ")
            // Only the complaint matters; a successful --help dump is noise and
            // would push a real error out of a bounded read.
            sb.append("[ \$rc -ne 0 ] && printf '%s\\n' \"\$out\" | head -5; ")
        }
        return sb.toString()
    }

    /** The CLI's first line that reads like an error. */
    private fun firstComplaint(body: String): String? =
        body.lineSequence()
            .map { it.trim() }
            .firstOrNull {
                it.isNotEmpty() && Regex(
                    "error|unexpected|invalid|unknown|unrecognized|possible values",
                    RegexOption.IGNORE_CASE,
                ).containsMatchIn(it)
            }
            ?.take(180)

    /** Convenience: audit over a pooled/live exec and swallow transport noise. */
    suspend fun runQuietly(
        agent: Agent,
        exec: suspend (cmd: String) -> String?,
    ): Report? = SilentlyTry.loggedOrElse(TAG, "audit ${agent.name} flags", null) { run(agent, exec) }
}
