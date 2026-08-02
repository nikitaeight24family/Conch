package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import android.os.Build
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.data.prefs.ThemeMode
import ai.eight24family.conch.ui.components.NeonColorPicker
import ai.eight24family.conch.ui.viewmodel.SettingsViewModel
import ai.eight24family.conch.util.SilentlyTry

@Composable
internal fun SettingsSectionAppearance(vm: SettingsViewModel) {
    val themeMode by vm.themeMode.collectAsState()
    val accentHex by vm.accentHex.collectAsState()
    // Both pickers start COLLAPSED on every entry into Settings.
    // Using `remember` (not `rememberSaveable`) so a previous-screen
    // expansion doesn't persist across navigations — user explicit
    // rule:.
    var accentExpanded by remember { mutableStateOf(false) }
    var bgExpanded by remember { mutableStateOf(false) }
    var textExpanded by remember { mutableStateOf(false) }
    var fontExpanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        // Accent FIRST — it's the appearance setting people reach for most.
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsRow(
                icon = Icons.Filled.ColorLens,
                title = "Accent color",
                subtitle = "Drives every accent-coloured highlight in the app",
                onClick = { accentExpanded = !accentExpanded },
            ) {
                Surface(
                    shape = RectangleShape,
                    color = parseAccentHex(accentHex),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.size(20.dp),
                ) {}
                Text(
                    " $accentHex",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Icon(
                    if (accentExpanded) Icons.Filled.KeyboardArrowDown
                    else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            if (accentExpanded) {
                NeonColorPicker(
                    initialHex = accentHex,
                    onHexChange = { vm.setAccentHex(it) },
                    // Curated vivid-not-neon accents — see ColorPresets for
                    // the 729-combination rationale.
                    presets = ai.eight24family.conch.ui.theme.ColorPresets.ACCENTS,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsRow(
                icon = Icons.Filled.Palette,
                title = "Theme",
                subtitle = "System / light / dark / custom (pick your own background)",
            ) {}
            ThemePicker(current = themeMode, onPick = { vm.setTheme(it) })
            // Custom-theme background picker — only visible when the
            // user picked `[ custom ]`. Same NeonColorPicker the
            // accent uses, so the controls feel consistent.
            if (themeMode == ThemeMode.CUSTOM) {
                val customBg by vm.customBgHex.collectAsState()
                SettingsRow(
                    icon = Icons.Filled.FormatColorFill,
                    title = "Background",
                    subtitle = "Color behind every screen. Accent stays as you picked below.",
                    onClick = { bgExpanded = !bgExpanded },
                ) {
                    Surface(
                        shape = RectangleShape,
                        color = parseAccentHex(customBg),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.size(20.dp),
                    ) {}
                    Text(
                        " $customBg",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    Icon(
                        if (bgExpanded) Icons.Filled.KeyboardArrowDown
                        else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                if (bgExpanded) {
                    NeonColorPicker(
                        initialHex = customBg,
                        onHexChange = { vm.setCustomBgHex(it) },
                        // Curated dark, low-chroma backgrounds (incl. true
                        // OLED black) — see ColorPresets for the
                        // 729-combination rationale.
                        presets = ai.eight24family.conch.ui.theme.ColorPresets.BACKGROUNDS,
                    )
                }
                // Text/foreground colour — same picker, sits right under
                // Background so the two custom-theme colours live together.
                val customText by vm.customTextHex.collectAsState()
                SettingsRow(
                    icon = Icons.Filled.FormatColorText,
                    title = "Text color",
                    subtitle = "Colour of text drawn on top of your background.",
                    onClick = { textExpanded = !textExpanded },
                ) {
                    Surface(
                        shape = RectangleShape,
                        color = parseAccentHex(customText),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.size(20.dp),
                    ) {}
                    Text(
                        " $customText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    Icon(
                        if (textExpanded) Icons.Filled.KeyboardArrowDown
                        else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                if (textExpanded) {
                    NeonColorPicker(
                        initialHex = customText,
                        onHexChange = { vm.setCustomTextHex(it) },
                        // Curated light, low-chroma text colours — see
                        // ColorPresets for the 729-combination rationale.
                        presets = ai.eight24family.conch.ui.theme.ColorPresets.TEXTS,
                    )
                }
                // Font — applies app-wide; picker lives with the custom theme.
                val fontId by vm.fontFamilyId.collectAsState()
                SettingsRow(
                    icon = Icons.Filled.TextFields,
                    title = "Font",
                    subtitle = "Coding typeface across the whole app.",
                    onClick = { fontExpanded = !fontExpanded },
                ) {
                    Text(
                        ai.eight24family.conch.ui.theme.CodingFont.byId(fontId).label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    Icon(
                        if (fontExpanded) Icons.Filled.KeyboardArrowDown
                        else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                if (fontExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ai.eight24family.conch.ui.theme.CodingFont.entries.forEach { f ->
                            val sel = f.id == fontId
                            val accent = MaterialTheme.colorScheme.primary
                            Surface(
                                onClick = { vm.setFontFamilyId(f.id) },
                                shape = RectangleShape,
                                color = if (sel) accent.copy(alpha = 0.10f) else MaterialTheme.colorScheme.background,
                                border = BorderStroke(1.dp, if (sel) accent else MaterialTheme.colorScheme.outline),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    (if (sel) "❯ " else "   ") + f.label + "   AaBbCc 0123 {}",
                                    fontFamily = f.family,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (sel) accent else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        val highRefreshRate by vm.highRefreshRateEnabled.collectAsState()
        // Live readback of what the display is actually doing right
        // now. If the user toggles On but this stays at ~60, the
        // device either doesn't support 120 Hz on this panel, or
        // system-wide settings (Samsung "Motion smoothness =
        // Standard", power saver, etc.) are forcing it down — our
        // app-side preferredRefreshRate request is then a no-op.
        // Surfacing the real number lets the user resolve "is this
        // working?" themselves instead of guessing.
        val context = androidx.compose.ui.platform.LocalContext.current
        val activity = remember(context) {
            var c: android.content.Context? = context
            while (c is android.content.ContextWrapper && c !is android.app.Activity) {
                c = c.baseContext
            }
            c as? android.app.Activity
        }
        // Recompose every ~500 ms while this screen is visible so a
        // toggle's effect shows up without manual re-entry.
        var tickN by remember { mutableIntStateOf(0) }
        LaunchedEffect(highRefreshRate) {
            // Reset & re-poll right after toggle so the user sees the
            // new value land. Refresh rate change goes through Android's
            // mode switcher which is usually a few hundred ms.
            tickN = 0
            repeat(8) {
                kotlinx.coroutines.delay(500)
                tickN++
            }
        }
        // ContextCompat.getDisplayOrDefault instead of the deprecated
        // windowManager.defaultDisplay: Play's SDK-35 edge-to-edge check
        // flags deprecated window/display API REFERENCES in the dex even
        // when they're behind a version gate — keep our own code clean.
        val currentHz: Float = remember(tickN, activity) {
            SilentlyTry.loggedOrElse("SshAi-Settings", "read current refresh rate", 0f) {
                activity?.let { androidx.core.content.ContextCompat.getDisplayOrDefault(it) }
                    ?.refreshRate ?: 0f
            }
        }
        val maxHz: Float = remember(activity) {
            SilentlyTry.loggedOrElse("SshAi-Settings", "read max refresh rate", 0f) {
                activity?.let { androidx.core.content.ContextCompat.getDisplayOrDefault(it) }
                    ?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 0f
            }
        }
        // Hide the toggle entirely on panels that don't support
        // anything above 60 Hz. There's nothing to choose between
        // and surfacing it just confuses the user ("why does this
        // setting do nothing on my phone?"). We re-check `maxHz`
        // each composition cycle — covers external displays in
        // theory but in practice maxHz is a one-shot read.
        val appScale by vm.appScale.collectAsState()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsRow(
                icon = Icons.Filled.ZoomIn,
                title = "App scale",
                subtitle = "${"%.0f".format(appScale * 100)} %  ·  every button, text, padding scales together. Useful if defaults are too small or too large for your hand / panel.",
            ) {}
            Slider(
                value = appScale,
                onValueChange = { vm.setAppScale(it) },
                valueRange = 0.75f..1.5f,
                // 0.75 → 1.50 in steps of 0.05 = 15 steps. Slider's
                // `steps` is internal divisions BETWEEN endpoints, so
                // 14 = 15 stop points.
                steps = 14,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        val hapticsOn by vm.hapticsEnabled.collectAsState()
        val haptic = ai.eight24family.conch.ui.haptic.LocalSshAiHaptics.current
        SettingsRow(
            icon = Icons.Filled.Vibration,
            title = "Haptic feedback",
            subtitle = "Tactile clicks on buttons, PIN keys, refresh release, file actions. Modern phones have great taptic engines — this makes the app feel alive",
        ) {
            androidx.compose.material3.Switch(
                checked = hapticsOn,
                onCheckedChange = { newValue ->
                    vm.setHapticsEnabled(newValue)
                    // Fire a preview pulse when turning on so the
                    // user can feel exactly what they just enabled.
                    // When turning off, no pulse (would be confusing).
                    if (newValue) {
                        haptic.perform(ai.eight24family.conch.ui.haptic.SshAiHaptic.Confirm)
                    }
                },
            )
        }
        if (maxHz > 60.5f) {
            val subtitle = "Current: ${"%.0f".format(currentHz)} Hz · max ${"%.0f".format(maxHz)} Hz. On = smoother, drains battery faster"
            SettingsRow(
                icon = Icons.Filled.Speed,
                title = "High refresh rate",
                subtitle = subtitle,
            ) {
                androidx.compose.material3.Switch(
                    checked = highRefreshRate,
                    onCheckedChange = { vm.setHighRefreshRateEnabled(it) },
                )
            }
        }
    }
}
