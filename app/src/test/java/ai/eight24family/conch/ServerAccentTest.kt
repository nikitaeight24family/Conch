package ai.eight24family.conch

import ai.eight24family.conch.ui.theme.ServerAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accent generator's two promises: a random colour is READABLE (never sinks
 * into the cyber-black background or washes out on white), and two servers never
 * come out looking like the same colour. Both are easy to break with an
 * innocent-looking tweak to the HSL band, and neither shows up as a crash — it
 * shows up as an invisible server name, so they're pinned here.
 */
class ServerAccentTest {

    private fun luminance(c: androidx.compose.ui.graphics.Color): Float =
        0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

    @Test
    fun `parse accepts the forms we store and reject junk`() {
        assertEquals(ServerAccent.parse("#4FD1C5"), ServerAccent.parse("4fd1c5"))
        assertNotNull(ServerAccent.parse("#FF4FD1C5"))
        assertNull(ServerAccent.parse(null))
        assertNull(ServerAccent.parse(""))
        assertNull(ServerAccent.parse("#12345"))
        assertNull(ServerAccent.parse("#GGGGGG"))
        // A half-typed value must parse as nothing rather than as black — the
        // colour field commits on every keystroke.
        assertNull(ServerAccent.parse("#1"))
    }

    @Test
    fun `hex round-trips exactly`() {
        for (hex in listOf("#000000", "#FFFFFF", "#4FD1C5", "#7C3AED", "#01FE80")) {
            assertEquals(hex, ServerAccent.toHex(ServerAccent.parse(hex)!!))
        }
    }

    @Test
    fun `derived colour is stable per id and legible`() {
        val a = ServerAccent.derive("4ffd2276-1932-4580-b577-2446071c6489")
        val b = ServerAccent.derive("4ffd2276-1932-4580-b577-2446071c6489")
        assertEquals(ServerAccent.toHex(a), ServerAccent.toHex(b))
        val l = luminance(a)
        assertTrue("derived colour too dark for a black background: $l", l > 0.18f)
        assertTrue("derived colour too pale for a white background: $l", l < 0.92f)
    }

    @Test
    fun `random colours stay inside the readable band`() {
        repeat(400) {
            val c = ServerAccent.parse(ServerAccent.randomHex())!!
            val l = luminance(c)
            assertTrue("random colour too dark: ${ServerAccent.toHex(c)} (lum $l)", l > 0.18f)
            assertTrue("random colour too pale: ${ServerAccent.toHex(c)} (lum $l)", l < 0.92f)
        }
    }

    @Test
    fun `random colour avoids hues already in use`() {
        // One server taken: the next must not land within 40° of it, or the two
        // read as the same colour in a list.
        val taken = "#FF0000" // hue 0
        repeat(200) {
            val hue = ServerAccent.hueOf(ServerAccent.parse(ServerAccent.randomHex(listOf(taken)))!!)
            val dist = minOf(hue, 360f - hue)
            assertTrue("new hue $hue too close to the taken hue 0 (Δ$dist)", dist > 40f)
        }
    }

    @Test
    fun `three taken hues still leave a distinguishable gap`() {
        val taken = listOf("#FF0000", "#00FF00", "#0000FF") // 0, 120, 240
        repeat(200) {
            val hue = ServerAccent.hueOf(ServerAccent.parse(ServerAccent.randomHex(taken))!!)
            val dist = listOf(0f, 120f, 240f).minOf { t ->
                val d = kotlin.math.abs(hue - t)
                minOf(d, 360f - d)
            }
            assertTrue("new hue $hue collides with an existing one (Δ$dist)", dist > 25f)
        }
    }

    @Test
    fun `adapted colour clears 4-5 to 1 on both the dark and the light theme`() {
        val black = androidx.compose.ui.graphics.Color(0xFF05070A) // the cyber-black bg
        val white = androidx.compose.ui.graphics.Color(0xFFFAFAFA) // light-theme bg
        // Includes the two colours actually derived for this user's servers, the
        // yellow that broke the first attempt, and a hand-typed black.
        val cases = listOf("#FA40AA", "#70F172", "#FAF945", "#000000", "#FFFFFF", "#0000FF")
        for (hex in cases) {
            val c = ServerAccent.parse(hex)!!
            for (bg in listOf(black, white)) {
                val adapted = ServerAccent.adaptTo(c, bg)
                val ratio = ServerAccent.contrast(adapted, bg)
                assertTrue(
                    "$hex on ${ServerAccent.toHex(bg)} only reached ${"%.2f".format(ratio)}:1",
                    ratio >= 4.5f,
                )
            }
        }
    }

    @Test
    fun `adaptation keeps the hue so the server stays recognisable`() {
        val white = androidx.compose.ui.graphics.Color(0xFFFAFAFA)
        for (hex in listOf("#FA40AA", "#70F172", "#4FD1C5", "#7C3AED")) {
            val c = ServerAccent.parse(hex)!!
            val adapted = ServerAccent.adaptTo(c, white)
            val before = ServerAccent.hueOf(c)
            val after = ServerAccent.hueOf(adapted)
            val d = kotlin.math.abs(before - after).let { minOf(it, 360f - it) }
            assertTrue("$hex hue drifted $d° while adapting", d < 6f)
        }
    }

    @Test
    fun `a colour already readable is returned untouched`() {
        val black = androidx.compose.ui.graphics.Color(0xFF05070A)
        val c = ServerAccent.parse("#FA40AA")!!
        assertEquals(ServerAccent.toHex(c), ServerAccent.toHex(ServerAccent.adaptTo(c, black)))
    }

    @Test
    fun `unparseable stored hex does not poison the taken-hue search`() {
        // A hand-typed junk value must not make the generator throw or return junk.
        val hex = ServerAccent.randomHex(listOf(null, "", "not-a-colour", "#4FD1C5"))
        assertNotNull(ServerAccent.parse(hex))
    }
}
