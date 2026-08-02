package ai.eight24family.conch

import ai.eight24family.conch.data.ModelProvenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catalog entries get PROVENANCE instead of deletion: the catalog is shared by
 * every server and grows monotonically (MODEL-CATALOG-MONOTONIC-1), so a key
 * the CLI no longer offers stays for label resolution but must not be offered
 * as a choice.
 */
class ModelProvenanceTest {

    // The real post-migration shape from the device: registry keys plus
    // scraper-era leftovers the CLI's registry never mentioned.
    private val catalog = setOf("sonnet", "fable", "opus", "haiku", "opus[1m]", "claude-fable-5[1m]")
    private val registry = setOf("opus[1m]", "claude-fable-5[1m]", "sonnet", "haiku")

    @Test
    fun `unconfirmed leftovers are hidden, registry keys are kept`() {
        val hidden = ModelProvenance.hidden(catalog, registry)
        assertEquals(setOf("fable", "opus"), hidden)
        registry.forEach { assertTrue("$it must stay pickable", it !in hidden) }
    }

    @Test
    fun `no confirmations at all hides nothing`() {
        // Cold start / an agent whose catalog isn't authoritative: an empty
        // picker would be far worse than a stale row.
        assertEquals(emptySet<String>(), ModelProvenance.hidden(catalog, emptySet()))
    }

    @Test
    fun `a pinned or running model is never hidden`() {
        // A chat genuinely running on an old alias must still see it in the
        // picker, or the list contradicts the chip above it.
        val hidden = ModelProvenance.hidden(catalog, registry, keepVisible = setOf("opus"))
        assertEquals(setOf("fable"), hidden)
    }

    @Test
    fun `a registry that confirms everything hides nothing`() {
        assertEquals(emptySet<String>(), ModelProvenance.hidden(catalog, catalog))
    }

    @Test
    fun `confirmations from another server never hide this server's models`() {
        // The union is what protects a multi-server user: server B's registry
        // confirming only its own models must not hide server A's.
        val serverA = setOf("opus[1m]", "sonnet")
        val serverB = setOf("haiku", "claude-fable-5[1m]")
        val hidden = ModelProvenance.hidden(catalog, serverA + serverB)
        assertEquals(setOf("fable", "opus"), hidden)
    }
}
