package ai.eight24family.conch.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * PER-SERVER ACCENT COLOUR. Every server carries a random hex, editable in its
 * settings, and its NAME is drawn in that colour everywhere in the app — the
 * fastest possible answer to "which machine am I looking at" on a screen that
 * mixes hosts (the sessions home does, row after row).
 *
 * Two rules the generator has to respect, or the feature works against itself:
 *
 *  1. **Readable on both themes.** A literally-uniform random RGB produces
 *     `#0b0d12` as happily as `#e0e6ff`, and near-black on the cyber-black
 *     background is an invisible server name. So the randomness lives in the
 *     HUE (all 360° of it) while saturation/lightness stay in a band that
 *     passes on dark AND light surfaces. It is still a random hex, still fully
 *     overridable by hand — the user can type `#000000` if they want it.
 *  2. **Distinguishable from the other servers.** Two hosts a few degrees apart
 *     are the same colour to a human, which defeats the point. [randomHex]
 *     takes the hues already in use and picks from the widest gap left.
 */
object ServerAccent {

    /** Saturation/lightness band that stays legible on both themes: vivid
     *  enough to read as a colour, light enough not to sink into the black
     *  background, dark enough not to wash out on white. */
    private const val SAT_MIN = 0.55f
    private const val SAT_MAX = 0.95f
    private const val LIGHT_MIN = 0.58f
    private const val LIGHT_MAX = 0.72f

    /**
     * HSL lightness is NOT brightness: at the same L=0.7 a blue is dark and a
     * yellow is nearly white. Picking inside the L band alone produced
     * `#FAF945` — invisible on the light theme (contrast 1.08:1 against white).
     * So the band that actually gets enforced is on perceived luminance, and
     * the hue survives while the lightness is bent to fit it.
     *
     * This band keeps a STORED colour sane on the dark theme (the app's home
     * ground). It is deliberately NOT the whole readability story: no single
     * colour can clear 4.5:1 against both black and white — the two required
     * ranges barely touch — so the actual contrast work happens per-theme in
     * [adaptTo], which keeps the hue and re-picks the lightness for whatever
     * surface it is being drawn on.
     */
    private const val LUM_MIN = 0.22f
    private const val LUM_MAX = 0.80f

    /** WCAG target for small text. A server name is `labelSmall` in places. */
    private const val MIN_CONTRAST = 4.5f

    /** WCAG relative luminance — gamma-expanded, unlike a raw channel average.
     *  The difference is not academic: it decides whether a mid-green counts as
     *  light or dark, and therefore which way [adaptTo] pushes it. */
    fun relativeLuminance(c: Color): Float {
        fun ch(v: Float) = if (v <= 0.03928f) v / 12.92f
        else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        return 0.2126f * ch(c.red) + 0.7152f * ch(c.green) + 0.0722f * ch(c.blue)
    }

    fun contrast(a: Color, b: Color): Float {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05f) / (lo + 0.05f)
    }

    /**
     * The server's colour AS DRAWN on [surface]: same hue and saturation, with
     * the lightness moved to whichever end gives the best contrast, stopping as
     * soon as [MIN_CONTRAST] is met. On the cyber-black theme this is a no-op
     * for nearly every colour; on the light theme it darkens the same hue so the
     * name stays readable and still recognisably "that server's colour".
     *
     * A hand-typed `#000000` on a dark theme is honoured to the extent
     * physics allows — we lighten it rather than refuse it, because the user
     * asked for that hue and an unreadable name is not a preference we should
     * preserve.
     */
    fun adaptTo(color: Color, surface: Color): Color {
        if (contrast(color, surface) >= MIN_CONTRAST) return color
        val hue = hueOf(color)
        val sat = satOf(color)
        // Push AWAY from the surface: darker on a light background, lighter on a
        // dark one. Walk the lightness axis and keep the best candidate, so even
        // a hue that can never reach 4.5:1 (saturated yellow on white) still
        // lands on its most readable form instead of staying at its worst.
        val towardDark = relativeLuminance(surface) > 0.5f
        var best = color
        var bestC = contrast(color, surface)
        var step = 0
        while (step <= 20) {
            val l = if (towardDark) 0.5f - step * 0.025f else 0.5f + step * 0.025f
            if (l < 0f || l > 1f) break
            val cand = hslColor(hue, sat, l)
            val c = contrast(cand, surface)
            if (c > bestC) { best = cand; bestC = c }
            if (c >= MIN_CONTRAST) return cand
            step++
        }
        return best
    }

    /** HSL saturation of a colour — needed to rebuild it at another lightness. */
    internal fun satOf(c: Color): Float {
        val max = maxOf(c.red, c.green, c.blue)
        val min = minOf(c.red, c.green, c.blue)
        val d = max - min
        if (d < 1e-4f) return 0f
        val l = (max + min) / 2f
        return d / (1f - abs(2f * l - 1f)).coerceAtLeast(1e-4f)
    }

    private fun luminance(c: Color): Float =
        0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

    /** Nudge lightness (hue/saturation untouched) until the colour's luminance
     *  sits inside [LUM_MIN]..[LUM_MAX]. Bisection: 12 steps is well past the
     *  precision of an 8-bit channel. */
    private fun inBand(hue: Float, sat: Float, light: Float): Color {
        var c = hslColor(hue, sat, light)
        var lum = luminance(c)
        if (lum in LUM_MIN..LUM_MAX) return c
        val target = if (lum > LUM_MAX) LUM_MAX else LUM_MIN
        var lo = if (lum > LUM_MAX) 0f else light
        var hi = if (lum > LUM_MAX) light else 1f
        repeat(12) {
            val mid = (lo + hi) / 2f
            c = hslColor(hue, sat, mid)
            lum = luminance(c)
            if (lum > target) hi = mid else lo = mid
        }
        // Land strictly inside the band — the loop converges ON the edge, and a
        // value exactly at the boundary reads as out-of-band to any check.
        return hslColor(hue, sat, (lo + hi) / 2f).let {
            if (luminance(it) > LUM_MAX) hslColor(hue, sat, lo)
            else if (luminance(it) < LUM_MIN) hslColor(hue, sat, hi)
            else it
        }
    }

    /** Parse `#RRGGBB` / `RRGGBB` (and `#AARRGGBB`). Null when unparseable —
     *  callers fall back to the derived colour, never to a crash. */
    fun parse(hex: String?): Color? {
        val h = hex?.trim()?.removePrefix("#") ?: return null
        if (h.length != 6 && h.length != 8) return null
        val v = h.toLongOrNull(16) ?: return null
        return if (h.length == 6) Color(0xFF000000L or v) else Color(v)
    }

    fun toHex(c: Color): String {
        val r = (c.red * 255f + 0.5f).toInt().coerceIn(0, 255)
        val g = (c.green * 255f + 0.5f).toInt().coerceIn(0, 255)
        val b = (c.blue * 255f + 0.5f).toInt().coerceIn(0, 255)
        return "#%02X%02X%02X".format(r, g, b)
    }

    /**
     * The colour a server shows when it has no explicit hex yet — derived from
     * its id, so every server the user ALREADY had (and every one added before
     * this feature) gets a stable, distinct colour with no migration write and
     * no "all my servers are grey until I edit them" phase. Same id ⇒ same
     * colour forever, across reinstalls and devices.
     */
    fun derive(serverId: String): Color {
        // Golden-angle stride over a 32-bit hash: neighbouring ids land far
        // apart on the wheel instead of clustering like `hash % 360` does.
        val h = abs(serverId.hashCode())
        val hue = ((h % 997) * 137.508f) % 360f
        val sat = SAT_MIN + (h / 997 % 7) / 6f * (SAT_MAX - SAT_MIN)
        val light = LIGHT_MIN + (h / 7979 % 5) / 4f * (LIGHT_MAX - LIGHT_MIN)
        return inBand(hue, sat, light)
    }

    /**
     * A fresh random hex for a NEW server, biased away from [takenHexes] — the
     * hue is drawn from the widest unoccupied arc of the wheel, so the second
     * server never lands next to the first. Saturation/lightness are random
     * inside the readable band.
     */
    fun randomHex(takenHexes: Collection<String?> = emptyList()): String {
        val rnd = java.util.Random()
        val taken = takenHexes.mapNotNull { parse(it) }.map { hueOf(it) }.sorted()
        val hue = when {
            taken.isEmpty() -> rnd.nextFloat() * 360f
            else -> {
                // Widest gap between consecutive taken hues (wrapping around),
                // then jitter within its middle third so repeated dice rolls
                // don't all return the exact same value.
                var bestStart = taken.last()
                var bestSize = 360f - taken.last() + taken.first()
                for (i in 0 until taken.size - 1) {
                    val size = taken[i + 1] - taken[i]
                    if (size > bestSize) {
                        bestSize = size
                        bestStart = taken[i]
                    }
                }
                (bestStart + bestSize * (0.33f + rnd.nextFloat() * 0.34f)) % 360f
            }
        }
        val sat = SAT_MIN + rnd.nextFloat() * (SAT_MAX - SAT_MIN)
        val light = LIGHT_MIN + rnd.nextFloat() * (LIGHT_MAX - LIGHT_MIN)
        return toHex(inBand(hue, sat, light))
    }

    /** Hue in degrees, for the gap search in [randomHex]. */
    internal fun hueOf(c: Color): Float {
        val r = c.red; val g = c.green; val b = c.blue
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        val d = max - min
        if (d < 1e-4f) return 0f
        val h = when (max) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return (h + 360f) % 360f
    }

    internal fun hslColor(hue: Float, sat: Float, light: Float): Color {
        val c = (1f - abs(2f * light - 1f)) * sat
        val hp = ((hue % 360f) + 360f) % 360f / 60f
        val x = c * (1f - abs(hp % 2f - 1f))
        val (r1, g1, b1) = when (hp.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = light - c / 2f
        return Color(
            (r1 + m).coerceIn(0f, 1f),
            (g1 + m).coerceIn(0f, 1f),
            (b1 + m).coerceIn(0f, 1f),
        )
    }
}

/**
 * Resolved accent per server, published to the whole UI tree so the ~17 places
 * that draw a server name don't each have to collect the repository. Keyed by
 * id (the reliable path) AND by name, because a handful of leaf composables
 * (the terminal screen, the stats sheet, search breadcrumbs) are given only the
 * display name by design and threading an id through every nav route to tint
 * one string would be the worse trade.
 */
class ServerAccents(
    private val byId: Map<String, String?> = emptyMap(),
    private val namesToIds: Map<String, List<String>> = emptyMap(),
) {
    /** Explicit hex if set, else the id-derived colour. */
    fun of(serverId: String?): Color? {
        val id = serverId ?: return null
        return ServerAccent.parse(byId[id]) ?: if (id in byId) ServerAccent.derive(id) else null
    }

    /** Name lookup — null when the name is unknown OR ambiguous (two servers
     *  share it): tinting by a coin flip would be worse than not tinting. */
    fun ofName(name: String?): Color? {
        val n = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val ids = namesToIds[n] ?: return null
        return if (ids.size == 1) of(ids[0]) else null
    }

    companion object {
        val Empty = ServerAccents()

        fun from(servers: List<ai.eight24family.conch.domain.Server>): ServerAccents =
            ServerAccents(
                byId = servers.associate { it.id to it.colorHex },
                namesToIds = servers.groupBy({ it.name.trim() }, { it.id }),
            )
    }
}

val LocalServerAccents = compositionLocalOf { ServerAccents.Empty }

/**
 * The colour to draw a server's name in: its accent when known, otherwise
 * [fallback] (so a screen keeps its existing look when the server can't be
 * resolved — nothing ever renders invisible or uncoloured-by-accident).
 */
@Composable
fun serverNameColor(
    serverId: String? = null,
    serverName: String? = null,
    fallback: Color,
): Color {
    val accents = LocalServerAccents.current
    val raw = accents.of(serverId) ?: accents.ofName(serverName) ?: return fallback
    // Adapt to the surface we are actually painting on — the app has a light
    // theme, and one stored colour cannot clear 4.5:1 against both black and
    // white (measured: the derived green for "Home" is 1.3:1 on white).
    return ServerAccent.adaptTo(raw, androidx.compose.material3.MaterialTheme.colorScheme.background)
}

/** Collect the server list once, near the root, and publish the accents. */
@Composable
fun rememberServerAccents(): ServerAccents {
    val servers by ai.eight24family.conch.di.ServiceLocator.serverRepository
        .observeServers()
        .collectAsState(initial = emptyList())
    return remember(servers) { ServerAccents.from(servers) }
}
