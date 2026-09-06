package ai.eight24family.conch.adb

/**
 * Every sentence the app is allowed to say about a missing phone shell — and
 * the one way to recognise one it has already said.
 *
 * ⛔ NO SENTENCE HERE TELLS ANYONE WHERE TO GO. They used to: "Set it up in
 * Settings > Phone bridge; Android needs it armed once per boot…" — three
 * clauses of directions, printed into a one-line row that clipped them at
 * "Phone bridge…", beside a [ retry ] button that could not possibly help
 * (owner, 2026-09-06). None of it is the user's business: not adb, not which
 * Android switch is involved, not that a switch exists.
 *
 * So each line here states ONE observed fact in a few words, and the screen
 * that prints it carries the door beside it — [PhoneBridgeSetup.ask], which
 * raises the wizard over whatever the user is looking at and walks them
 * through. Fact on the left, door on the right, never directions in between.
 *
 * [isShellProblem] is how a screen that receives a sentence from below (a chat
 * line, a humanised exception) knows to show that door. It matches this file's
 * own strings, so a sentence and its remedy cannot drift apart.
 */
object PhoneBridgeCopy {

    /** adbd completed a handshake and then refused our key: this phone has
     *  never been paired with Conch, or the pairing was revoked. */
    const val NOT_PAIRED = "This phone doesn't know Conch yet"

    /** Android refuses to hand out the shell without a Wi-Fi association, and
     *  nothing on the device can switch the radio back on. */
    const val WIFI_OFF = "Wi-Fi is off, and Android needs it to hand over the shell"

    /** Nothing answered on loopback: the switch is off, which is where every
     *  restart leaves it. */
    const val SHELL_OFF = "This phone's shell is off after the restart"

    /** The environment is installed and the shell that starts it is not there. */
    const val LINUX_ASLEEP = "This phone's Linux is asleep"

    private val ALL = listOf(NOT_PAIRED, WIFI_OFF, SHELL_OFF, LINUX_ASLEEP)

    /**
     * Is this sentence one of ours — i.e. does the wizard fix it?
     *
     * ⚠ Matched by CONTENT, not by a type: the sentence travels as plain text
     * through `ErrorMessages.humanize`, a chat line and a JSONL transcript
     * before a screen sees it again, and none of those carry one.
     */
    fun isShellProblem(text: String?): Boolean {
        val t = text ?: return false
        return ALL.any { t.contains(it, ignoreCase = true) }
    }
}
