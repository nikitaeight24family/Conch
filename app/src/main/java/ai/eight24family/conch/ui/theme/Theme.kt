package ai.eight24family.conch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import ai.eight24family.conch.data.prefs.ThemeMode
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.util.SilentlyTry

/**
 * Dark scheme — the original cyberpunk-CLI vibe. The user picks the neon
 * accent (`#00E5FF` cyan by default) and it becomes `primary`.
 */
private fun darkScheme(accent: Color) = darkColorScheme(
    primary = accent,
    onPrimary = Bg,
    primaryContainer = darkenForContainer(accent),
    onPrimaryContainer = accent,
    secondary = RoleSecondary,
    onSecondary = RoleOnSecondary,
    secondaryContainer = RoleSecondaryContainer,
    onSecondaryContainer = RoleOnSecondaryContainer,
    tertiary = RoleTertiary,
    onTertiary = RoleOnTertiary,
    tertiaryContainer = RoleTertiaryContainer,
    onTertiaryContainer = RoleOnTertiaryContainer,
    error = RoleError,
    onError = RoleOnError,
    errorContainer = RoleErrorContainer,
    onErrorContainer = RoleOnErrorContainer,
    background = Bg,
    onBackground = Fg,
    surface = Surface,
    onSurface = Fg,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = FgDim,
    outline = Outline,
    outlineVariant = OutlineDim,
)

/**
 * Light scheme — same shapes, paper-and-graphite palette. The user's
 * neon accent gets darkened toward black for legibility on warm-white
 * (raw `#00E5FF` on `#F5F1E8` is unreadable garbage), but kept saturated
 * enough to still register as the same colour-identity — that way
 * tapping accent picker swaps both themes consistently.
 */
private fun lightScheme(accent: Color) = lightColorScheme(
    primary = darkenForLight(accent),
    onPrimary = Color.White,
    primaryContainer = lightenForContainer(accent),
    onPrimaryContainer = darkenForLight(accent),
    secondary = darkenForLight(RoleSecondary),
    onSecondary = Color.White,
    secondaryContainer = lightenForContainer(RoleSecondary),
    onSecondaryContainer = darkenForLight(RoleSecondary),
    tertiary = darkenForLight(RoleTertiary),
    onTertiary = Color.White,
    tertiaryContainer = lightenForContainer(RoleTertiary),
    onTertiaryContainer = darkenForLight(RoleTertiary),
    error = darkenForLight(RoleError),
    onError = Color.White,
    errorContainer = lightenForContainer(RoleError),
    onErrorContainer = darkenForLight(RoleError),
    background = LightBg,
    onBackground = LightFg,
    surface = LightSurface,
    onSurface = LightFg,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightFgDim,
    outline = LightOutline,
    outlineVariant = LightOutlineDim,
)

/** Lerp `accent` toward black — for quiet "container" tints in dark mode. */
private fun darkenForContainer(c: Color): Color {
    val f = 0.18f
    return Color(c.red * f, c.green * f, c.blue * f, 1f)
}

/** Lerp `accent` toward black for legibility on light backgrounds (~45% darker). */
private fun darkenForLight(c: Color): Color {
    val f = 0.55f
    return Color(c.red * f, c.green * f, c.blue * f, 1f)
}

/** Lerp `accent` toward white for soft container tints on light backgrounds. */
private fun lightenForContainer(c: Color): Color {
    val f = 0.85f
    return Color(
        red = c.red + (1f - c.red) * f,
        green = c.green + (1f - c.green) * f,
        blue = c.blue + (1f - c.blue) * f,
        alpha = 1f,
    )
}

@Composable
fun SshAiTheme(
    @Suppress("UNUSED_PARAMETER") dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val accentHex by ServiceLocator.preferences.accentHex.collectAsState(initial = "#00E5FF")
    val themeMode by ServiceLocator.preferences.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val customBgHex by ServiceLocator.preferences.customBgHex.collectAsState(initial = "#0A0F12")
    val customTextHex by ServiceLocator.preferences.customTextHex.collectAsState(initial = "#E6EDF3")
    val fontFamilyId by ServiceLocator.preferences.fontFamilyId.collectAsState(initial = "system")
    val systemDark = isSystemInDarkTheme()
    // CUSTOM: treat as dark-baseline (so foreground / surface contrast
    // stays usable) but swap in the user-picked background. Falls
    // through to dark-system behaviour for everything else.
    val useDark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.CUSTOM -> true
    }
    val accent = parseHex(accentHex)
    val baseScheme = if (useDark) darkScheme(accent) else lightScheme(accent)
    // Apply the custom background when the user picked CUSTOM. Other
    // surface colours come from the dark scheme above — only the
    // outermost background changes, which is what the user actually
    // controls in the picker.
    val scheme = if (themeMode == ThemeMode.CUSTOM) {
        // User controls BOTH the background and the text/foreground colour in
        // custom mode; accent (primary) stays whatever they picked.
        val bg = parseHex(customBgHex)
        val txt = parseHex(customTextHex)
        // Tie the WHOLE surface family to the custom background. Without this
        // only `background` changed, so the TopAppBar — and the edge-to-edge,
        // transparent system status bar showing through it — plus dialogs and
        // menus kept the dark default `surface` and read as a mismatched dark
        // band over the chosen colour. `surface` + the low containers == bg so
        // the top bar / status bar are seamless; higher containers + variant
        // lift a hair toward white so menus and subtle fills keep a touch of
        // depth in the chosen hue. `surfaceTint == bg` kills accent-tinted
        // elevation creep.
        fun lift(f: Float) = Color(
            red = bg.red + (1f - bg.red) * f,
            green = bg.green + (1f - bg.green) * f,
            blue = bg.blue + (1f - bg.blue) * f,
            alpha = 1f,
        )
        baseScheme.copy(
            background = bg,
            surface = bg,
            surfaceVariant = lift(0.12f),
            surfaceContainerLowest = bg,
            surfaceContainerLow = bg,
            surfaceContainer = bg,
            surfaceContainerHigh = lift(0.08f),
            surfaceContainerHighest = lift(0.12f),
            surfaceBright = lift(0.10f),
            surfaceDim = bg,
            surfaceTint = bg,
            onBackground = txt,
            onSurface = txt,
            onSurfaceVariant = txt.copy(alpha = 0.7f),
        )
    } else baseScheme

    // Sync the system status-bar icon appearance to the *app* theme, not
    // the OS theme. Without this, a user running their phone in dark
    // mode sees white system icons (clock / battery / signal), and when
    // our app runs in light mode they get washed out into the white
    // background. Setting `isAppearanceLightStatusBars = !useDark`
    // tells the system: "app background is light, draw the status bar
    // icons dark" — and vice versa.
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            val controller = androidx.core.view.WindowCompat
                .getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !useDark
            controller.isAppearanceLightNavigationBars = !useDark
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = appTypography(CodingFont.byId(fontFamilyId).family),
        content = content
    )
}

private fun parseHex(hex: String): Color = SilentlyTry.loggedOrElse("SshAi-Theme", "parse hex color", Cyan) {
    val cleaned = hex.trim().removePrefix("#")
    val v = cleaned.toLong(16) or 0xFF000000
    Color(v.toInt())
}
