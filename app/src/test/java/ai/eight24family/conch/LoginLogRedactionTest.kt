package ai.eight24family.conch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A LOGIN STREAM MUST NEVER REACH THE LOG WITH A SECRET IN IT.
 *
 * `claude setup-token` prints the long-lived token to stdout — the CLI masks it in
 * its own display and then prints it in the clear on the token line — and the app
 * logged every stdout line of the sign-in verbatim. A working `sk-ant-oat01-…` was
 * sitting in the device log, readable by anything with log access and outliving the
 * sign-in itself (measured on the user's phone, 2026-08-18).
 *
 * The redactor is private, so this pins the RULE against the real strings rather
 * than the function: whatever implements it, these must not survive.
 */
class LoginLogRedactionTest {

    /** The shapes the redactor must catch, as they actually appear. */
    private val secrets = listOf(
        // The exact form the user's phone logged.
        "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",
        "sk-ant-api03-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-AbCdEfGhIj",
        "sk-proj-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789abcdef",
        "AIzaSyAbCdEfGhIjKlMnOpQrStUvWxYz0123456789",
        "ya29.AbCdEfGhIjKlMnOpQrStUvWxYz0123456789",
    )

    private val rx = Regex(
        "sk-ant-[A-Za-z0-9_-]{20,}" +
            "|sk-proj-[A-Za-z0-9_-]{20,}" +
            "|sk-[A-Za-z0-9]{32,}" +
            "|AIza[A-Za-z0-9_-]{30,}" +
            "|ya29[.][A-Za-z0-9_-]{20,}",
    )

    @Test
    fun `every credential shape the login can print is matched`() {
        for (s in secrets) {
            assertTrue("not redacted: ${s.take(16)}…", rx.containsMatchIn(s))
        }
    }

    @Test
    fun `a redacted line keeps its shape and loses the value`() {
        val line = "Your OAuth token: " + secrets[0]
        val redacted = rx.replace(line) { m -> m.value.take(12) + "…<redacted ${m.value.length}B>" }
        // The evidence that the token line arrived survives…
        assertTrue(redacted.contains("sk-ant-oat01"))
        assertTrue(redacted.contains("108B"))
        // …the token does not.
        assertFalse(redacted.contains(secrets[0]))
        assertFalse(redacted.contains("zG464TSSPaXwccuZsuAK4Kuq"))
    }

    @Test
    fun `ordinary login chatter is left alone`() {
        // Over-redaction would blind the one stream we need for sign-in bugs.
        val lines = listOf(
            "This will guide you through long-lived (1-year) auth token setup",
            "https://claude.com/cai/oauth/authorize?code=true&client_id=9d1c250a-e61b-44d9-88ed-5944d1962f5e",
            "Checking subscription…",
            "✓ Long-lived authentication token created successfully!",
        )
        for (l in lines) assertFalse("over-redacted: $l", rx.containsMatchIn(l))
    }
}

/**
 * What the sign-in dialog is allowed to SHOW.
 *
 * The rule is shape-based: decoration is what is mostly not letters, so a reworded
 * real message from a future CLI still gets through.
 */
class LoginDialogNoiseTest {

    // Mirrors isNoiseLine in AgentPickerViewModelOAuth (private there).
    private fun noise(line: String): Boolean {
        val t = line.trim()
        if (t.length < 4) return true
        if (t.none { it.isLetter() }) return true
        if (t.any { it.code in 0x2580..0x259F || it.code in 0x2500..0x257F }) return true
        val letters = t.count { it.isLetter() }
        if (letters * 3 < t.length) return true
        if (t.startsWith("WelcometoClaudeCode") || t.startsWith("Welcome to Claude Code")) return true
        return false
    }

    @Test
    fun `decoration is dropped`() {
        val junk = listOf(
            "✢", "*", "✻", "·",
            "......................................................",
            "\u2588\u2588\u2588\u2588\u2591\u2591\u2591*",
            "WelcometoClaudeCodev2.1.234",
            "   ",
        )
        for (l in junk) assertTrue("kept: [$l]", noise(l))
    }

    @Test
    fun `everything the user needs is kept`() {
        val real = listOf(
            "Browser didn't open? Use the url below to sign in (c to copy)",
            "https://claude.com/cai/oauth/authorize?code=true&client_id=9d1c250a",
            "Paste code here if prompted >",
            "Checking subscription…",
            "✓ Long-lived authentication token created successfully!",
            "Your OAuth token (valid for 1 year):",
        )
        for (l in real) assertFalse("dropped: [$l]", noise(l))
    }
}
