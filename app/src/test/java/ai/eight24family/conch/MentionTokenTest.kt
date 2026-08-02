package ai.eight24family.conch

import ai.eight24family.conch.util.MentionToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the @-mention token rules that trigger server-side file_suggestions. */
class MentionTokenTest {

    @Test
    fun `trailing token starting with at is the active query`() {
        assertEquals("src/ma", MentionToken.activeQuery("fix the bug in @src/ma"))
        assertEquals("", MentionToken.activeQuery("look at @"))
        assertEquals("build.gr", MentionToken.activeQuery("@build.gr"))
    }

    @Test
    fun `no mention when the token is not at a word boundary`() {
        // user@host, emails — an @ glued to preceding text is not a mention.
        assertNull(MentionToken.activeQuery("ssh user@example.com"))
        assertNull(MentionToken.activeQuery("mail me at user@example"))
    }

    @Test
    fun `mention ends once whitespace follows it`() {
        assertNull(MentionToken.activeQuery("read @app/build.gradle.kts and tell me"))
        assertNull(MentionToken.activeQuery("no mention here"))
        assertNull(MentionToken.activeQuery(""))
    }

    @Test
    fun `complete replaces the trailing token and appends a space`() {
        assertEquals(
            "fix @app/src/main/AndroidManifest.xml ",
            MentionToken.complete("fix @Andro", "app/src/main/AndroidManifest.xml"),
        )
        assertEquals("@a/b.kt ", MentionToken.complete("@b", "a/b.kt"))
        // No active mention → input untouched.
        assertEquals("plain text", MentionToken.complete("plain text", "x"))
    }
}
