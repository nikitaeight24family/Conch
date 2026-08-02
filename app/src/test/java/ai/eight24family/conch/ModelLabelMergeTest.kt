package ai.eight24family.conch

import ai.eight24family.conch.data.ModelLabelMerge
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 */
class ModelLabelMergeTest {

    @Test
    fun `a newer version replaces the cached one`() {
        val out = ModelLabelMerge.merge(mapOf("opus" to "Opus 4.8"), mapOf("opus" to "Opus 5"))
        assertEquals("Opus 5", out["opus"])
    }

    @Test
    fun `an older version NEVER replaces the cached one`() {
        val out = ModelLabelMerge.merge(mapOf("opus" to "Opus 5"), mapOf("opus" to "Opus 4.8"))
        assertEquals("Opus 5", out["opus"])
    }

    @Test
    fun `minor versions compare numerically, not as text`() {
        // 4.10 > 4.8 numerically, though "4.10" < "4.8" as a string.
        assertEquals("Opus 4.10", ModelLabelMerge.merge(mapOf("o" to "Opus 4.8"), mapOf("o" to "Opus 4.10"))["o"])
        assertEquals("Opus 4.10", ModelLabelMerge.merge(mapOf("o" to "Opus 4.10"), mapOf("o" to "Opus 4.8"))["o"])
    }

    @Test
    fun `a different family is not a regression - it just replaces`() {
        val out = ModelLabelMerge.merge(mapOf("x" to "Opus 5"), mapOf("x" to "Sonnet 4.6"))
        assertEquals("Sonnet 4.6", out["x"])
    }

    @Test
    fun `a short probe result does not delete known aliases`() {
        val cached = mapOf("opus" to "Opus 5", "sonnet" to "Sonnet 5", "haiku" to "Haiku 4.5")
        val out = ModelLabelMerge.merge(cached, mapOf("opus" to "Opus 5"))
        assertEquals(3, out.size)
        assertEquals("Haiku 4.5", out["haiku"])
    }

    @Test
    fun `an empty probe leaves the cache untouched`() {
        val cached = mapOf("opus" to "Opus 5")
        assertEquals(cached, ModelLabelMerge.merge(cached, emptyMap()))
    }

    @Test
    fun `a brand-new family is added`() {
        val out = ModelLabelMerge.merge(mapOf("opus" to "Opus 5"), mapOf("fable" to "Fable 5"))
        assertEquals("Fable 5", out["fable"])
        assertEquals("Opus 5", out["opus"])
    }

    @Test
    fun `unversioned labels are taken as-is`() {
        val out = ModelLabelMerge.merge(mapOf("x" to "Opus 5"), mapOf("x" to "Default"))
        assertEquals("Default", out["x"])
    }
}
