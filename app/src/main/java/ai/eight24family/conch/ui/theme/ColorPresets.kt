package ai.eight24family.conch.ui.theme

/**
 * Curated colour presets for the custom theme — 9 backgrounds, 9 accents,
 * 9 text colours, all with real colour character (not nine shades of black
 * and nine shades of white).
 *
 * We deliberately DON'T promise that every one of the 729 (bg, accent, text)
 * triples is aesthetically perfect — the user explicitly traded that for
 * variety. What we DO keep is the one guard that matters: nothing is ever
 * physically unreadable.
 *
 *  - **Backgrounds** stay DARK (L* ≈ 6–16) but carry a real hue — deep
 *    jewel tones (navy, indigo, eggplant, pine, forest, wine, espresso,
 *    petrol) instead of flat near-blacks. They're kept dark on purpose: the
 *    custom theme is dark-baseline (surfaces, elevation, chrome all assume a
 *    dark backdrop), so a light background would break far more than text.
 *  - **Text** colours stay BRIGHT (L* ≈ 67–92) but are real colours —
 *    terminal green, amber, cyan, pink, lavender, orange, sky — plus the one
 *    white default.
 *  - **Accents** are vivid, spread around the wheel.
 *
 * Because backgrounds are always dark and text is always bright, every
 * text/bg pair clears ≈ 6:1 (above WCAG AA) — so any combination is legible.
 * Whether a given colour-on-colour pairing is *pretty* is on the user; that's
 * the freedom they asked for. Each list's first entry is the shipped default,
 * so its swatch highlights before anything is customised.
 *
 * Touching these: keep backgrounds dark and text bright (that's the
 * readability floor). Hue/saturation are free to roam.
 */
object ColorPresets {

    /**
     * Dark jewel-tone surfaces — each a real hue, all dark enough that any
     * bright text reads on them. First is the shipped near-neutral default.
     */
    val BACKGROUNDS: List<String> = listOf(
        "#0A0F12", // ink — cool near-black (shipped default)
        "#0C1B2E", // navy — deep ocean blue
        "#171436", // indigo — blue-violet
        "#221539", // eggplant — deep violet
        "#08231D", // pine — deep green-teal
        "#13250F", // forest — deep green
        "#2A1019", // wine — deep burgundy
        "#26180A", // espresso — dark amber-brown
        "#0E2429", // petrol — dark teal-cyan
    )

    /**
     * Vivid accents (Tailwind-400-weight siblings) spread evenly around the
     * wheel: cyan → teal → green → blue → violet → fuchsia → rose → orange →
     * amber. Bright enough to pop on any background; light enough that
     * near-black text reads on a filled accent.
     */
    val ACCENTS: List<String> = listOf(
        "#00E5FF", // cyan — signature brand accent (shipped default)
        "#2DD4BF", // teal
        "#34D399", // emerald
        "#60A5FA", // blue
        "#A78BFA", // violet
        "#E879F9", // fuchsia
        "#FB7185", // rose
        "#FB923C", // orange
        "#FBBF24", // amber
    )

    /**
     * Bright text colours with real character — a syntax-highlight-style
     * spread of hues, all light enough to read on every dark background. The
     * white default leads; the rest are actual colours (terminal green is the
     * classic green-on-black CRT homage).
     */
    val TEXTS: List<String> = listOf(
        "#E6EDF3", // mist — cool off-white (shipped default)
        "#F47C7C", // coral — soft red
        "#F39C6B", // orange — warm peach
        "#F2C14E", // amber — gold
        "#62C378", // phosphor — terminal green (green-on-black CRT homage)
        "#5BD7E8", // cyan — bright aqua
        "#8FD0F0", // sky — light blue
        "#B79CF0", // lavender — soft violet
        "#F58BB0", // pink — rose
    )
}
