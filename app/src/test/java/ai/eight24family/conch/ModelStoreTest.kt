package ai.eight24family.conch

import ai.eight24family.conch.linux.LocalLlm
import ai.eight24family.conch.linux.LocalLlmEngine
import ai.eight24family.conch.linux.store.DeviceProfile
import ai.eight24family.conch.linux.store.HfStats
import ai.eight24family.conch.linux.store.StoreCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The model store's honesty is arithmetic: the shelf is remote data feeding a
 * downloader and a fits verdict, so the parser's sanitizer and the ram math
 * are the two things that must never lie. Both are pure — tested as such.
 */
class ModelStoreTest {

    private fun seedText(): String {
        val candidates = listOf(
            File("src/main/assets/llm/store-catalog.json"),
            File("app/src/main/assets/llm/store-catalog.json"),
        )
        return candidates.first { it.exists() }.readText()
    }

    @Test
    fun `the bundled shelf parses and every entry is sane`() {
        val cat = StoreCatalog.parse(seedText())
        assertTrue("shelf too thin: ${cat.models.size}", cat.models.size >= 10)
        // Builtins on the shelf resolve to real builtin models.
        cat.models.filter { it.builtin }.forEach { e ->
            assertTrue("builtin ${e.id} unknown to LocalLlm", LocalLlm.BUILTIN.any { it.id == e.id })
        }
        // Downloadable entries: pinned host, real sizes, a usable Model.
        cat.models.filterNot { it.builtin }.forEach { e ->
            assertTrue("${e.id}: url must be pinned to huggingface", e.url!!.startsWith("https://huggingface.co/"))
            assertTrue("${e.id}: bytes must be real", e.bytes > 100_000_000L)
            assertTrue("${e.id}: kvPerTok expected for store models", e.kvPerTok > 0L)
            val m = StoreCatalog.toModel(e)!!
            assertEquals(e.bytes, m.bytes)
            assertEquals(e.kvPerTok, m.kvPerTok)
        }
        // "verified" is EARNED by on-device proof, not assumed for a family:
        // only the 4B was actually seen firing tools here.
        assertEquals(
            setOf("qwen3_5-4b"),
            cat.models.filter { it.tier == "verified" }.map { it.id }.toSet(),
        )
        // Every verified model is also an agent; agents are a researched
        // subset, never the whole shelf (chat/vision models exist and matter).
        assertTrue(cat.models.filter { it.tier == "verified" }.all { it.agent })
        val agents = cat.models.count { it.agent }
        assertTrue("some models must be agents", agents in 1 until cat.models.size)
        assertTrue("some models must be chat-only", cat.models.any { !it.agent })
        // Google is represented (owner asked where the Google models were).
        assertTrue(cat.models.any { it.family == "gemma" })
    }

    @Test
    fun `a poisoned manifest cannot reach the downloader`() {
        fun entry(url: String, id: String = "evil-model", file: String = "m.gguf") = """
            {"v":1,"models":[
              {"id":"$id","label":"x","family":"x","cat":"tiny","file":"$file",
               "url":"$url","bytes":1000000000,"kvPerTok":1}
            ]}
        """.trimIndent()
        // Wrong scheme, wrong host, path traversal, malformed id: all dropped —
        // and a manifest with nothing valid left throws (previous shelf stays).
        assertTrue(runCatching { StoreCatalog.parse(entry("http://huggingface.co/x.gguf")) }.isFailure)
        assertTrue(runCatching { StoreCatalog.parse(entry("https://evil.example/x.gguf")) }.isFailure)
        assertTrue(
            runCatching {
                StoreCatalog.parse(entry("https://huggingface.co/x.gguf", file = "../../../etc/x.gguf"))
            }.isFailure,
        )
        assertTrue(
            runCatching {
                StoreCatalog.parse(entry("https://huggingface.co/x.gguf", id = "Evil/../Id"))
            }.isFailure,
        )
        // One bad entry among good ones is dropped, not fatal.
        val mixed = """
            {"v":1,"models":[
              {"id":"bad","label":"x","family":"x","cat":"tiny","file":"m.gguf",
               "url":"https://evil.example/m.gguf","bytes":1000000000},
              {"id":"good-model","label":"ok","family":"x","cat":"tiny","file":"okmodel-Q4_0.gguf",
               "url":"https://huggingface.co/r/resolve/main/okmodel-Q4_0.gguf","bytes":1000000000,"kvPerTok":2}
            ]}
        """.trimIndent()
        val cat = StoreCatalog.parse(mixed)
        assertEquals(listOf("good-model"), cat.models.map { it.id })
    }

    @Test
    fun `ram need is computed from the architecture for store models`() {
        val m = LocalLlm.Model(
            id = "x", label = "x", file = "x.gguf", url = "https://huggingface.co/x",
            bytes = 2_000_000_000L, blurb = "", family = "llama", kvPerTok = 114_688L,
        )
        assertEquals(
            2_000_000_000L + 114_688L * LocalLlmEngine.CTX_TOKENS + StoreCatalog.COMPUTE_BYTES,
            LocalLlm.ramNeeded(m),
        )
        // Builtins keep the tuned flat overhead — their verdicts must not move.
        val builtin = LocalLlm.BUILTIN.first()
        assertEquals(builtin.bytes + LocalLlm.RAM_OVERHEAD_BYTES, LocalLlm.ramNeeded(builtin))
    }

    @Test
    fun `capacity gate sorts the shelf by what a phone can hold`() {
        val cat = StoreCatalog.parse(seedText())
        fun phone(ramGb: Long) = DeviceProfile.Profile(
            device = "test", soc = "SM8750", ramTotalBytes = ramGb * 1_073_741_824L,
            diskFreeBytes = 100L * 1_073_741_824L, cores = 8,
            fp16 = true, dotprod = true, i8mm = true, sve = false, gpuFront = true,
        )
        val gptOss = cat.models.first { it.id == "gpt-oss-20b" }
        val tiny = cat.models.first { it.id == "llama3_2-1b" }
        assertFalse("a 20B MoE must not pass a 16G phone", DeviceProfile.runsOnThisPhone(gptOss, cat, phone(16)))
        assertTrue("a 20B MoE runs on a 24G phone", DeviceProfile.runsOnThisPhone(gptOss, cat, phone(24)))
        assertTrue("a 1B must pass a 6G phone", DeviceProfile.runsOnThisPhone(tiny, cat, phone(6)))
        // The frontier coder is beyond every current phone — and says so.
        val coder = cat.models.first { it.id == "qwen3-coder-30b-a3b" }
        assertFalse(DeviceProfile.runsOnThisPhone(coder, cat, phone(24)))
    }

    @Test
    fun `speed estimate follows bytes actually read per token`() {
        val cat = StoreCatalog.parse(seedText())
        val flagship = DeviceProfile.Profile(
            device = "t", soc = "SM8750-AB", ramTotalBytes = 16L * 1_073_741_824L,
            diskFreeBytes = 0, cores = 8, fp16 = true, dotprod = true, i8mm = true,
            sve = false, gpuFront = true,
        )
        // SoC class table puts sm8 at 20 GB/s (no measurements in a JVM test).
        val (bw, measured) = DeviceProfile.bwInfo(cat, flagship)
        assertEquals(20.0, bw, 0.01)
        assertFalse(measured)
        // MoE beats a dense model of the same file size, because activeBytes.
        val hTiny = cat.models.first { it.id == "granite4-h-tiny" }     // 4.0G file, ~0.85G active
        val lfm8b = cat.models.first { it.id == "lfm2-8b-a1b" }         // 4.7G file, ~1.2G active
        val phi = cat.models.first { it.id == "phi4-mini" }             // 2.5G file, dense
        assertTrue(DeviceProfile.estTokS(hTiny, cat, flagship) > DeviceProfile.estTokS(phi, cat, flagship))
        assertTrue(DeviceProfile.estTokS(lfm8b, cat, flagship) > DeviceProfile.estTokS(phi, cat, flagship))
    }

    @Test
    fun `pull counts read like a store`() {
        assertEquals("18.5M", HfStats.fmt(18_529_497))
        assertEquals("4.3M", HfStats.fmt(4_332_501))
        assertEquals("275k", HfStats.fmt(274_514))
        assertEquals("1.2k", HfStats.fmt(1_240))
        assertEquals("938", HfStats.fmt(938))
    }

    @Test
    fun `store models keep the codex loopback routing of the builtins`() {
        // The whole point of merging into CATALOG: a store model is a local
        // model to every consumer. byId over the merged catalog is the hinge.
        assertNull(LocalLlm.byId("never-added-model"))
        val builtin = LocalLlm.BUILTIN.first()
        assertEquals(builtin, LocalLlm.byId(builtin.id))
    }
}
