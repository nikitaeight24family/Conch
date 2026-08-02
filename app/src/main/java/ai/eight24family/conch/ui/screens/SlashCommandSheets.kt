package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.ui.viewmodel.MemoryDocs
import ai.eight24family.conch.ui.viewmodel.MemoryScope

// ───────────────────────── Memory editor ─────────────────────────
//
// Triggered by the memory icon in the chat topbar (and as a fallback by
// typing `/memory`). The other ex-slash sheets (/help, /cost, /agents,
// /mcp) were deleted: cost moved into the ServerStatsSheet, the rest were
// niche viewers that don't make sense on a phone.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemorySheet(
    docs: MemoryDocs,
    onSave: (MemoryScope, String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    var scope by remember { mutableStateOf(MemoryScope.GLOBAL) }
    // Track edits per-scope so flipping the tab doesn't lose the buffer
    // for the other side.
    var globalText by remember(docs) { mutableStateOf(docs.global) }
    var projectText by remember(docs) { mutableStateOf(docs.project) }
    val text = if (scope == MemoryScope.GLOBAL) globalText else projectText
    val cyan = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val outline = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── Header + explanation ──
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = cyan, fontWeight = FontWeight.Bold)) { append("memory") }
                    withStyle(SpanStyle(color = outline)) { append(" ▌ ") }
                    withStyle(SpanStyle(color = onSurface)) { append(docs.filename) }
                },
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                "Anything you write here is loaded into every conversation's " +
                    "context with this agent. Use it for facts and preferences " +
                    "you don't want to repeat: stack choices, conventions, " +
                    "do-this-not-that rules.",
                color = outline,
                style = MaterialTheme.typography.bodySmall
            )

            // ── Scope picker ──
            val scopePath = when (scope) {
                MemoryScope.GLOBAL -> docs.globalDisplay
                MemoryScope.PROJECT -> docs.projectPath.ifBlank { "(open a chat with a cwd first)" }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScopePill(
                    label = "global",
                    selected = scope == MemoryScope.GLOBAL,
                    onClick = { scope = MemoryScope.GLOBAL }
                )
                ScopePill(
                    label = "project",
                    selected = scope == MemoryScope.PROJECT,
                    enabled = docs.projectPath.isNotBlank(),
                    onClick = { if (docs.projectPath.isNotBlank()) scope = MemoryScope.PROJECT }
                )
            }
            Text(
                "// $scopePath",
                color = tertiary,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace
            )

            // ── Editor with empty-state hint ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 380.dp)
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = {
                        if (scope == MemoryScope.GLOBAL) globalText = it else projectText = it
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState()),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = onSurface,
                        fontFamily = FontFamily.Monospace
                    ),
                    cursorBrush = SolidColor(cyan),
                    decorationBox = { inner ->
                        if (text.isBlank()) {
                            // Inline placeholder with concrete examples — so
                            // the user immediately sees what kind of content
                            // belongs in here.
                            Column {
                                Text(
                                    "// nothing here yet — try adding rules like:",
                                    color = outline,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    "// - I prefer pnpm over npm",
                                    color = outline.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    "// - Use TypeScript strict mode everywhere",
                                    color = outline.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    "// - Don't add comments unless asked",
                                    color = outline.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                        inner()
                    }
                )
            }

            // ── Action row ──
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onRefresh() }) {
                    Text("[ reload ]", color = outline, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text("[ cancel ]", color = outline, fontWeight = FontWeight.Bold)
                }
                TextButton(
                    onClick = { onSave(scope, text); onDismiss() },
                    enabled = scope == MemoryScope.GLOBAL ||
                        (scope == MemoryScope.PROJECT && docs.projectPath.isNotBlank())
                ) {
                    Text("[ save ]", color = cyan, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ScopePill(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val cyan = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val borderColor = when {
        !enabled -> outline.copy(alpha = 0.3f)
        selected -> cyan
        else -> outline
    }
    val textColor = when {
        !enabled -> outline.copy(alpha = 0.4f)
        selected -> cyan
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .background(
                if (selected) cyan.copy(alpha = 0.10f) else MaterialTheme.colorScheme.background
            )
            .border(1.dp, borderColor)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            "[ $label ]",
            color = textColor,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

// ───────────────────────── shared notice dialog ─────────────────────────

@Composable
internal fun SimpleNotice(title: String, body: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
    )
}
