package ai.eight24family.conch

import ai.eight24family.conch.linux.store.DeviceProfile
import ai.eight24family.conch.linux.store.ModelRecords
import ai.eight24family.conch.linux.store.ShareMeasurements
import ai.eight24family.conch.linux.store.StoreCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The speed database: measured per-SoC rows, where they rank against a guess,
 * and what may leave the phone.
 */
class SpeedDatabaseTest {

    private fun seedText(): String = listOf(
        File("src/main/assets/llm/store-catalog.json"),
        File("app/src/main/assets/llm/store-catalog.json"),
    ).first { it.exists() }.readText()

    private fun phone(soc: String) = DeviceProfile.Profile(
        device = "CPH2671", soc = soc, ramTotalBytes = 16L * 1_073_741_824L,
        diskFreeBytes = 0, cores = 8, fp16 = true, dotprod = true, i8mm = true,
        sve = false, gpuFront = true,
    )

    // ── the key ──

    @Test
    fun `soc key drops the bin suffix so the same silicon matches`() {
        // Build.SOC_MODEL is "SM8750" on one phone and "SM8550-AC" on another;
        // a bin code must not cost a device its measured row.
        assertEquals("sm8750", DeviceProfile.socKey(phone("SM8750")))
        assertEquals("sm8750", DeviceProfile.socKey(phone("SM8750-AB")))
        assertEquals("sm8550", DeviceProfile.socKey(phone("SM8550-AC")))
        // Not a suffix — a whole different chip name must survive intact.
        assertEquals("tensor g5", DeviceProfile.socKey(phone("Tensor G5")))
        assertEquals("mt6991", DeviceProfile.socKey(phone("MT6991")))
    }

    // ── the resolution order ──

    /** A manifest with one measured row, so the order is tested against a
     *  fixture rather than against curated data that will change. */
    private fun withSocRow(gbps: Double, n: Int) = StoreCatalog.parse(
        """
        {"v":9,"capacityFraction":0.62,"defaultGbps":6,
         "bw":[{"match":["sm8"],"gbps":20}],
         "socs":[{"soc":"sm8750","gbps":$gbps,"n":$n}],
         "models":[{"id":"phi4-mini","family":"phi","cat":"everyday","file":"model.gguf",
           "url":"https://huggingface.co/x/y/resolve/main/model.gguf","bytes":2491874272,
           "quant":"Q4_K_M","kvPerTok":104857}]}
        """.trimIndent(),
    )

    @Test
    fun `a measured soc row beats the coarse bucket`() {
        val cat = withSocRow(28.5, 12)
        val (bw, src) = DeviceProfile.bwInfo(cat, phone("SM8750-AB"))
        assertEquals(28.5, bw, 0.01)
        assertTrue("expected a community row, got $src", src is DeviceProfile.BwSource.Community)
        assertEquals(12, (src as DeviceProfile.BwSource.Community).n)
        // A different chip in the same family does NOT borrow the row — that
        // is the whole difference between this table and the bucket.
        assertEquals(DeviceProfile.BwSource.SocClass, DeviceProfile.bwInfo(cat, phone("SM8450")).second)
    }

    @Test
    fun `the shipped table starts empty, and that is deliberate`() {
        // A fabricated row would be worse than no row: the number a published
        // row carries must come from the app's OWN probe, and the only phone
        // measured so far disagrees with itself across model shapes (21 GB/s
        // from a dense 507 MB model, 51 GB/s from a MoE whose activeBytes is a
        // curated estimate). See docs/model-speed-database.md.
        assertTrue(
            "if a row is added, it must be defensible per that doc's methodology",
            StoreCatalog.parse(seedText()).socs.isEmpty(),
        )
    }

    @Test
    fun `an unmeasured soc still falls back to its class, then to default`() {
        val cat = StoreCatalog.parse(seedText())
        // sm8450 is a Snapdragon 8 Gen 1 — no measured row, but the prefix
        // bucket knows the family.
        val (bw, src) = DeviceProfile.bwInfo(cat, phone("SM8450"))
        assertEquals(20.0, bw, 0.01)
        assertEquals(DeviceProfile.BwSource.SocClass, src)
        // Silicon nothing matches at all says so rather than inventing a class.
        val (dbw, dsrc) = DeviceProfile.bwInfo(cat, phone("exynos-mystery-9"))
        assertEquals(cat.defaultGbps.toDouble(), dbw, 0.01)
        assertEquals(DeviceProfile.BwSource.Default, dsrc)
    }

    @Test
    fun `the measured row moves the shelf estimate, not just a label`() {
        val cat = withSocRow(28.5, 3)
        val phi = cat.models.first { it.id == "phi4-mini" }
        val measured = DeviceProfile.estTokS(phi, cat, phone("SM8750"))
        val bucket = DeviceProfile.estTokS(phi, cat, phone("SM8450"))
        assertTrue(
            "a measured 28.5 GB/s must price higher than the 20 GB/s bucket " +
                "($measured vs $bucket)",
            measured > bucket,
        )
    }

    // ── manifest bounds: a remote file is an instruction ──

    @Test
    fun `nonsense soc rows cannot reprice the shelf`() {
        val cat = StoreCatalog.parse(
            """
            {"v":9,"capacityFraction":0.62,"defaultGbps":6,
             "bw":[{"match":["sm8"],"gbps":20}],
             "socs":[
               {"soc":"SM9999","gbps":999999,"n":-5},
               {"soc":"","gbps":10,"n":1},
               {"gbps":10,"n":1},
               {"soc":"sm7999","n":1}
             ],
             "models":[{"id":"gemma3-1b","family":"gemma","cat":"tiny","file":"model.gguf",
               "url":"https://huggingface.co/x/y/resolve/main/model.gguf","bytes":721918496,
               "quant":"Q4_0","kvPerTok":104857}]}
            """.trimIndent(),
        )
        // Four bad rows in, one clamped row out: the empty key, the missing
        // key and the missing gbps are dropped outright.
        assertEquals(1, cat.socs.size)
        val row = cat.socs.first()
        assertEquals("sm9999", row.soc)          // lowercased for matching
        assertEquals(2000.0, row.gbps, 0.01)     // clamped, not trusted
        assertEquals(1, row.n)                   // a negative count is 1, never 0
    }

    // ── what may leave the phone ──

    @Test
    fun `the shared payload carries speeds and nothing personal`() {
        val cat = StoreCatalog.parse(seedText())
        val recs = mapOf(
            "phi4-mini" to ModelRecords.Rec(
                ran = true, ranGpu = false, tokS = 12.34, tokSAtMs = 1_757_000_000_000L,
                rating = 5, reviewText = "PRIVATE-SENTINEL: a written review must not leave the phone",
            ),
            // No measurement: nothing to contribute, so it must not appear.
            "gemma3-1b" to ModelRecords.Rec(ran = true, rating = 3),
        )
        val samples = ShareMeasurements.samples(recs, cat)
        assertEquals(1, samples.size)
        assertEquals("phi4-mini", samples.first().id)
        assertEquals("Q4_K_M", samples.first().quant)

        val json = ShareMeasurements.render(
            p = phone("SM8750-AB"),
            gpu = DeviceProfile.Gpu("Adreno 830", null, false),
            samples = samples,
            bwGbps = 31.63,
            appVersion = "1.0.9",
        )
        // What it must say.
        assertTrue(json.contains("\"soc\":\"sm8750\""))
        assertTrue(json.contains("\"gbps\":31.6"))
        assertTrue(json.contains("\"tokS\":12.3"))
        assertTrue(json.contains("\"quant\":\"Q4_K_M\""))
        assertTrue(json.contains("\"day\":\"2025-09-04\""))
        assertTrue(json.contains("\"isa\":\"fp16+dotprod+i8mm\""))
        assertTrue(json.contains("\"app\":\"1.0.9\""))
        // What it must NEVER say. The review is the owner's own prose and the
        // rating is his private opinion; a serializer over `Rec` would have
        // shipped both.
        assertFalse("review text must never leave the phone", json.contains("PRIVATE-SENTINEL"))
        assertFalse("review text must never leave the phone", json.contains("review"))
        assertFalse("ratings are not part of a speed database", json.contains("rating"))
        assertFalse("no model paths", json.contains(".gguf"))
        assertFalse("a millisecond stamp fingerprints, a day does not", json.contains("1757000000000"))
        // A model with no measurement contributes nothing at all.
        assertFalse(json.contains("gemma3-1b"))
    }

    @Test
    fun `a nonsense local measurement is not contributed`() {
        val cat = StoreCatalog.parse(seedText())
        val recs = mapOf(
            "phi4-mini" to ModelRecords.Rec(tokS = 0.0, tokSAtMs = 1L),
            "gemma3-1b" to ModelRecords.Rec(tokS = 9_000.0, tokSAtMs = 1L),
        )
        assertTrue(ShareMeasurements.samples(recs, cat).isEmpty())
    }

    @Test
    fun `a measurement with no timestamp is shared as unknown, not as epoch`() {
        val cat = StoreCatalog.parse(seedText())
        val s = ShareMeasurements.samples(
            mapOf("phi4-mini" to ModelRecords.Rec(tokS = 20.0, tokSAtMs = 0L)), cat,
        )
        assertEquals("unknown", s.first().day)
    }
}
