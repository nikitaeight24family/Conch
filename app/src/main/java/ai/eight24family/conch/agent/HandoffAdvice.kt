package ai.eight24family.conch.agent

/**
 * WHAT THE RELAY JUST DID, AND HOW TO NEVER NEED IT AGAIN.
 *
 * Raised once, by the session, at the moment the app ended a copy of this
 * session running in a terminal on the server so the phone could carry it on
 * ([SessionHolder.relayedNote] is the one-line version that stays in the
 * transcript; this is the explanation behind it).
 *
 * ⛔ THIS IS A REPORT OF A FAILURE, NOT AN OFFER. The final shape the owner
 * asked for (2026-09-12): So by the time a human sees any of this, the app has
 * already tried everything it can do on its own — started the daemon, updated
 * codex through the installer, written the shell hook. A dialog means one of
 * those could not be done from here, and what is on screen is then the manual
 * step, not a button and not a suggestion.
 *
 * Everything here is MEASURED state of HIS server, not a template: the version
 * string is what that box answered, [sharedBrain] is whether its codex actually
 * carries the daemon/proxy/--remote trio ([codex.CodexDaemon]), and
 * [hookOutcome] is the CLI-level word for what writing the shell hook did.
 */
data class HandoffAdvice(
    /** `codex` / `claude` — which CLI's rules are being explained. */
    val cli: String,
    /** `/dev/pts/0`, or "a terminal" when the probe could not name it. */
    val where: String,
    /** The process that was ended. 0 when unknown. */
    val pid: Long,
    /** e.g. `0.80.0`; blank when the probe did not run. */
    val cliVersion: String = "",
    /**
     * True when this server's codex can host one daemon for both sides — the
     * clean path is then a flag on his terminal command. False means the clean
     * path is "update codex" (or, for Claude, which ships no such thing at all,
     * "hand the session over deliberately").
     */
    val sharedBrain: Boolean = false,
    /**
     * What the automatic shell-hook write answered: `added:<rc>`, `present`,
     * `unsupported:<shell>`, `failed`, or null when it was not attempted
     * (no shared brain to join yet, or the exec did not run).
     */
    val hookOutcome: String? = null,
) {
    /** The one state that needs no human: the server can share a session AND
     *  its terminal is already wired to join. Nothing is shown for this. */
    val perfect: Boolean
        get() = sharedBrain && (hookOutcome == "present" || hookOutcome?.startsWith("added") == true)

    /** The shell Conch could not write, when that is why this is on screen. */
    val unsupportedShell: String?
        get() = hookOutcome?.takeIf { it.startsWith("unsupported") }?.substringAfter(':')
}
