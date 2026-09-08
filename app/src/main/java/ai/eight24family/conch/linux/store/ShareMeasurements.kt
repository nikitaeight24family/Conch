package ai.eight24family.conch.linux.store

import android.content.Context
import android.content.Intent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * The one way a speed measurement can leave this phone: the owner reads it and
 * hands it to an app of their choosing.
 *
 * ── WHY IT LOOKS LIKE THIS AND NOT LIKE TELEMETRY ──
 *
 * ⛔ THE APP NEVER POSTS. Telemetry was cut out of this app entirely and the
 * claim that it opens no connection except to the servers the user adds is
 * load-bearing in four places at once: the store listing, the landing page,
 * the About screen and the privacy policy. So there is no endpoint, no
 * backend, no queue and no retry here. [render] builds text; [share] passes it
 * to Android's chooser. The user picks the destination and can read every byte
 * first. That also leaves the Data Safety form untouched — the app still
 * collects and transmits nothing.
 *
 * ⛔ WHITELIST, NEVER A DUMP. Every field below is named explicitly, and the
 * fields deliberately absent are the point: no ratings, no review prose, no
 * model paths, no server list, no credentials, nothing about chats. Adding a
 * field here is a privacy decision, so it must be typed out by hand — a
 * `Rec`-to-JSON serializer would have carried the owner's written reviews out
 * the door the day someone added them to `Rec`. Pinned by
 * `ShareMeasurementsTest`.
 *
 * ⛔ DAY PRECISION, NOT MILLISECONDS. A measurement's date is enough to know
 * whether it is stale; a millisecond timestamp is a much better fingerprint
 * than it is a fact.
 *
 * What the numbers are FOR: `DeviceProfile.bwInfo` prices every shelf row from
 * one number, effective GB/s, and until a phone has measured itself that
 * number comes from a three-bucket prefix table where `sm8` covers every
 * Snapdragon 8 ever made. On the owner's SM8750 that bucket is wrong by 1.6×.
 * Real per-SoC rows fix the first screen a new user ever sees — see the `socs`
 * table in `StoreCatalog` and `docs/model-speed-database.md`.
 */
object ShareMeasurements {

    /** A model's measured speed, reduced to what the table needs. */
    data class Sample(val id: String, val quant: String?, val tokS: Double, val gpu: Boolean, val day: String)

    /** Day stamp (UTC) for a millisecond time, or null when there is none. */
    internal fun day(ms: Long): String? {
        if (ms <= 0L) return null
        val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return f.format(java.util.Date(ms))
    }

    /**
     * The measured rows worth sharing: a model that has a real speed on this
     * phone. A row with no `tokS` is nothing to contribute.
     */
    fun samples(
        recs: Map<String, ModelRecords.Rec> = ModelRecords.all(),
        cat: StoreCatalog.Catalog = StoreCatalog.catalog.value,
    ): List<Sample> = recs.mapNotNull { (id, r) ->
        val tokS = r.tokS ?: return@mapNotNull null
        if (tokS !in 0.1..500.0) return@mapNotNull null
        Sample(
            id = id,
            quant = cat.models.firstOrNull { it.id == id }?.quant,
            tokS = tokS,
            gpu = r.ranGpu,
            day = day(r.tokSAtMs) ?: "unknown",
        )
    }.sortedBy { it.id }

    /**
     * The exact text that would leave the phone — pure, so a test can read it
     * and the UI can show it before anything happens.
     *
     * [appVersion] rides along because the inference engine ships inside the
     * app: two speeds from different llama.cpp builds are not comparable, and
     * a row with no build is a row nobody can trust later.
     */
    fun render(
        p: DeviceProfile.Profile,
        gpu: DeviceProfile.Gpu,
        samples: List<Sample>,
        bwGbps: Double,
        appVersion: String,
    ): String = buildJsonObject {
        put("soc", JsonPrimitive(DeviceProfile.socKey(p)))
        put("device", JsonPrimitive(p.device))
        put("ramGb", JsonPrimitive(Math.round(p.ramTotalBytes / 1_073_741_824.0).toInt()))
        put("cores", JsonPrimitive(p.cores))
        put("isa", JsonPrimitive(
            listOfNotNull(
                "fp16".takeIf { p.fp16 }, "dotprod".takeIf { p.dotprod },
                "i8mm".takeIf { p.i8mm }, "sve".takeIf { p.sve },
            ).joinToString("+").ifEmpty { "none" },
        ))
        gpu.model?.let { put("gpu", JsonPrimitive(it)) }
        put("gbps", JsonPrimitive(Math.round(bwGbps * 10) / 10.0))
        put("app", JsonPrimitive(appVersion))
        put("models", buildJsonArray {
            samples.forEach { s ->
                add(buildJsonObject {
                    put("id", JsonPrimitive(s.id))
                    s.quant?.let { put("quant", JsonPrimitive(it)) }
                    put("tokS", JsonPrimitive(Math.round(s.tokS * 10) / 10.0))
                    put("gpu", JsonPrimitive(s.gpu))
                    put("day", JsonPrimitive(s.day))
                })
            }
        })
    }.toString()

    /**
     * Hand [text] to Android's share chooser. No destination is implied and
     * none is remembered: the user picks, every time.
     *
     * ⛔ NOT a URL with the payload in a query string, which is the other
     * obvious way to pre-fill an issue — device data does not belong in a
     * query parameter, and it would hard-code one repo as the recipient.
     */
    fun share(ctx: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Conch model speeds")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching {
            ctx.startActivity(
                Intent.createChooser(send, "Share these measurements")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
