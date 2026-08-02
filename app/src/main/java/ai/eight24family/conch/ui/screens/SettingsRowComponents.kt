package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.data.prefs.ThemeMode
import ai.eight24family.conch.util.SilentlyTry

/**
 * A single Settings row: leading icon, title + subtitle, trailing
 * control. Optional click handler for navigation-style rows
 * (`onOpenKeychain`, "Review", "Delete all"); `danger = true` tints
 * the icon and title with the error colour for destructive actions.
 *
 * Trailing slot is a flexible Row — it can hold a Switch, a chevron,
 * a value label, or nothing. Pickers (PillPicker / ThemePicker /
 * NeonColorPicker) render BELOW the row, not inside its trailing
 * slot — they need full width and don't fit horizontally next to a
 * subtitle.
 */
@Composable
internal fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val tint = if (danger) MaterialTheme.colorScheme.error
               else MaterialTheme.colorScheme.onSurface
    val subtitleTint = if (danger) MaterialTheme.colorScheme.error.copy(alpha = 0.75f)
                       else MaterialTheme.colorScheme.onSurfaceVariant
    val rowMod = Modifier
        .fillMaxWidth()
        // A11Y-1: collapse the row (icon + title + subtitle + trailing control)
        // into ONE TalkBack node so it reads as a single labelled item ("Run
        // shell from server, on, switch") instead of three separate stops.
        .semantics(mergeDescendants = true) {}
        .let { m -> if (onClick != null) m.clickable(onClick = onClick) else m }
        // Scaled up to match the Sessions list row height — was vertical 4dp
        // + 20dp icon, too cramped next to the bigger session rows.
        .padding(vertical = 10.dp)
    Row(
        modifier = rowMod,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            // Accent for normal rows, error for destructive — gives
            // every settings row a visible "verb" tint instead of
            // graveyard-grey outline. Danger keeps the red signal
            // for delete-all and similar.
            tint = if (danger) MaterialTheme.colorScheme.error
                   else MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(end = 16.dp)
                .size(26.dp),
        )
        Column(modifier = Modifier.weight(1f, fill = true)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = tint,
                fontWeight = FontWeight.Medium,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = subtitleTint,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        trailing()
    }
}

/**
 * Three-pill picker for SYSTEM/LIGHT/DARK. Same `[ label ]` style as
 * the FAB-buttons elsewhere in the app — no Material chips, keeps the
 * cyberpunk-CLI vibe consistent.
 */
@Composable
internal fun ThemePicker(current: ThemeMode, onPick: (ThemeMode) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ThemeMode.entries.forEach { mode ->
            val selected = mode == current
            val cyan = MaterialTheme.colorScheme.primary
            val outline = MaterialTheme.colorScheme.outline
            // Icons instead of "[ label ]" text — at app-scale ≥ 105 %
            // the bracketed labels wrap to two lines inside the equal-
            // weight cells (user-reported regression). Icons keep the
            // cell a fixed visual mass regardless of scale.
            val (icon, desc) = when (mode) {
                ThemeMode.SYSTEM -> Icons.Default.SettingsBrightness to "system theme"
                ThemeMode.LIGHT -> Icons.Default.LightMode to "light theme"
                ThemeMode.DARK -> Icons.Default.DarkMode to "dark theme"
                ThemeMode.CUSTOM -> Icons.Default.Palette to "custom theme"
            }
            Surface(
                onClick = { onPick(mode) },
                shape = RectangleShape,
                color = if (selected) cyan.copy(alpha = 0.10f) else MaterialTheme.colorScheme.background,
                contentColor = if (selected) cyan else MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, if (selected) cyan else outline),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = desc,
                        tint = if (selected) cyan else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

/**
 * Generic three-pill (or N-pill) picker matching [ThemePicker]'s visual
 * language: equally-weighted `[ label ]` cells, cyan border + 10% fill on
 * the selected entry. Used for the Default-agent, Approval-mode and
 * SK-notification-visibility settings.
 */
@Composable
internal fun <T> PillPicker(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onPick: (T) -> Unit
) {
    // height(IntrinsicSize.Min) + fillMaxHeight on each cell makes every pill
    // the SAME height as the tallest — so a label that wraps to two lines (e.g.
    // "[ claude code ]") no longer leaves the single-line pills short and the
    // row ragged. Text is centred both axes inside the matched cell.
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val cyan = MaterialTheme.colorScheme.primary
            val outline = MaterialTheme.colorScheme.outline
            Surface(
                onClick = { onPick(option) },
                shape = RectangleShape,
                color = if (isSelected) cyan.copy(alpha = 0.10f) else MaterialTheme.colorScheme.background,
                contentColor = if (isSelected) cyan else MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, if (isSelected) cyan else outline),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                modifier = Modifier.weight(1f).fillMaxHeight()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 6.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "[ ${label(option)} ]",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

internal fun parseAccentHex(hex: String): androidx.compose.ui.graphics.Color = SilentlyTry.loggedOrElse("SshAi-Settings", "parse accent hex", androidx.compose.ui.graphics.Color(0xFF00E5FF)) {
    val cleaned = hex.trim().removePrefix("#")
    val v = cleaned.toLong(16) or 0xFF000000
    androidx.compose.ui.graphics.Color(v.toInt())
}
