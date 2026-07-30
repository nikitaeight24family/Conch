package ai.eight24family.conch

import ai.eight24family.conch.ui.screens.GAP_DP
import ai.eight24family.conch.ui.screens.H_PADDING_DP
import ai.eight24family.conch.ui.screens.gridColumns
import ai.eight24family.conch.ui.screens.gridMaxHeightDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The attachment grid's layout maths.
 *
 * Worth pinning down because the two numbers are coupled and the coupling was
 * invisible: the old `340.dp` height cap was silently a FOUR-column value
 * (3.5 × tile + 3 gaps at 411dp), so changing the column count alone would have
 * quietly shrunk the visible grid from 3.5 rows to 2.6 — a layout regression no
 * compiler catches and nobody would think to look for.
 */
class AttachGridLayoutTest {

    @Test
    fun `phone gets three columns and a wide window gets five`() {
        assertEquals(3, gridColumns(mediumOrWider = false))
        assertEquals(5, gridColumns(mediumOrWider = true))
    }

    /** Three fat tiles need MORE height than five thin ones at the same width. */
    @Test
    fun `height follows the tile size, not a constant`() {
        val width = 411f
        val three = gridMaxHeightDp(width, 3)
        val five = gridMaxHeightDp(width, 5)
        assertTrue("3 columns ($three) must be taller than 5 ($five)", three > five)
    }

    /** The cap is 3.5 rows plus the three gaps between them — the half row is
     *  what tells the eye the grid scrolls. */
    @Test
    fun `height is three and a half rows of the real tile`() {
        val width = 411f
        val columns = 3
        val tile = (width - H_PADDING_DP * 2 - GAP_DP * (columns - 1)) / columns
        assertEquals(tile * 3.5f + GAP_DP * 3, gridMaxHeightDp(width, columns), 0.01f)
    }

    /** A pathologically narrow window must not produce a zero or negative cap —
     *  a grid with no height renders as nothing at all. */
    @Test
    fun `absurdly narrow windows still get a usable height`() {
        for (w in listOf(0f, 1f, 40f, 120f)) {
            val h = gridMaxHeightDp(w, 5)
            assertTrue("width=$w produced height=$h", h >= 48f * 3.5f)
        }
    }
}
