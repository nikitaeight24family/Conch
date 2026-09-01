package ai.eight24family.conch

import ai.eight24family.conch.util.MemoryPressure
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The trim registry is the app's answer to Google Play's memory-quality
 * requirement: [ai.eight24family.conch.ConchApp] fires it on every
 * onTrimMemory at TRIM_MEMORY_UI_HIDDEN and above. The contract pinned here:
 * every registered action runs, one failing action never blocks the rest,
 * and unregister actually severs the hook (a leaked hook pins its captured
 * ViewModel graph for the life of the process).
 */
class MemoryPressureTest {

    private val keys = listOf("test-a", "test-boom", "test-b")

    @After
    fun cleanup() {
        keys.forEach { MemoryPressure.unregister(it) }
    }

    @Test
    fun `trim runs every registered action and survives a failing one`() {
        var a = 0
        var b = 0
        MemoryPressure.register("test-a") { a++ }
        MemoryPressure.register("test-boom") { error("boom") }
        MemoryPressure.register("test-b") { b++ }

        MemoryPressure.trimAll("unit test")

        // Both healthy actions ran despite the one in the middle throwing.
        assertEquals(1, a)
        assertEquals(1, b)
    }

    @Test
    fun `unregister severs the hook and re-register replaces the action`() {
        var a = 0
        MemoryPressure.register("test-a", { a += 1 })
        MemoryPressure.register("test-a", { a += 100 }) // replaces, not stacks
        MemoryPressure.trimAll("unit test")
        assertEquals(100, a)

        MemoryPressure.unregister("test-a")
        MemoryPressure.trimAll("unit test")
        assertEquals(100, a) // unchanged — the hook is gone
    }
}
