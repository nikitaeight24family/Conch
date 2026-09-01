package ai.eight24family.conch.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.ui.window.handCursor

/**
 * The management pages' shared section grammar — `// label` headers, hairline
 * dividers, label/value rows, tappable action rows. Extracted verbatim from
 * ServerDetailScreen when the phone's local-models section started needing
 * the same rows on a second screen; the look is THE look of every `//` page.
 */

@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.tertiary,
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

@Composable
fun SectionDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

@Composable
fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    /** Optional control on the right. When present it REPLACES the chevron:
     *  a row that carries a switch is not also a navigation. */
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val dim = MaterialTheme.colorScheme.outline
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .handCursor()
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(end = 14.dp).size(22.dp))
        Column(modifier = Modifier.weight(1f, fill = true)) {
            Text(title, color = tint, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, color = dim, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (trailing != null) trailing() else Text("›", color = dim, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.outline,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 1.dp).weight(0.5f, fill = true),
        )
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = true),
        )
    }
}
