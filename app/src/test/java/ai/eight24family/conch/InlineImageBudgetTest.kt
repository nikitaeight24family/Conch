package ai.eight24family.conch

import ai.eight24family.conch.ui.viewmodel.ChatViewModelDownloads
import ai.eight24family.conch.ui.viewmodel.ChatViewModelDownloads.InlineImage
import ai.eight24family.conch.util.MemoryPressure
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decoded inline chat images are the app's biggest bitmaps (≤1600 px, up to
 * ~10 MB each). Google Play's memory-quality requirement tracks bitmap
 * memory as its own vital, so the map that holds them has a hard budget
 * ([MAX_READY_IMAGES]) plus a memory-pressure hook — pinned here.
 */
class InlineImageBudgetTest {

    /** Pure-JVM stand-in — InlineImage.Ready only needs the interface. */
    private class FakeImage : ImageBitmap {
        override val width = 1
        override val height = 1
        override val config = ImageBitmapConfig.Argb8888
        override val colorSpace: ColorSpace = ColorSpaces.Srgb
        override val hasAlpha = false
        override fun prepareToDraw() {}
        override fun readPixels(
            buffer: IntArray, startX: Int, startY: Int, width: Int,
            height: Int, bufferOffset: Int, stride: Int,
        ) {}
    }

    private fun coordinator() = ChatViewModelDownloads(
        scope = CoroutineScope(Dispatchers.Unconfined),
        serverId = "srv",
        currentLocalSessionId = { null },
        activeSessionFor = { null },
    )

    private fun ready() = InlineImage.Ready(FakeImage())

    @Test
    fun `ready entries beyond the budget evict oldest-decoded first`() {
        val c = coordinator()
        try {
            repeat(10) { i -> c.putInlineImage("/img/$i", ready()) }
            val keys = c.inlineImages.value.keys.toList()
            assertEquals((2..9).map { "/img/$it" }, keys)
        } finally {
            c.close()
        }
    }

    @Test
    fun `loading and failed entries are exempt from the budget and survive a trim`() {
        val c = coordinator()
        try {
            c.putInlineImage("/loading", InlineImage.Loading)
            c.putInlineImage("/failed", InlineImage.Failed("x"))
            repeat(9) { i -> c.putInlineImage("/img/$i", ready()) }
            assertTrue(c.inlineImages.value.containsKey("/loading"))
            assertTrue(c.inlineImages.value.containsKey("/failed"))

            c.trimDecodedImages()
            // Every bitmap dropped; the non-bitmap states stay (a Failed entry
            // must not silently retry just because memory got tight).
            assertEquals(setOf("/loading", "/failed"), c.inlineImages.value.keys)
        } finally {
            c.close()
        }
    }

    @Test
    fun `memory pressure drops decoded bitmaps through the registry`() {
        val c = coordinator()
        try {
            c.putInlineImage("/img", ready())
            MemoryPressure.trimAll("unit test")
            assertFalse(c.inlineImages.value.containsKey("/img"))
        } finally {
            c.close()
        }
    }

    @Test
    fun `close unhooks the coordinator - trims no longer reach it`() {
        val c = coordinator()
        c.putInlineImage("/img", ready())
        c.close()
        MemoryPressure.trimAll("unit test")
        assertTrue(
            "a closed coordinator must be unreachable from the registry",
            c.inlineImages.value.containsKey("/img"),
        )
    }
}
