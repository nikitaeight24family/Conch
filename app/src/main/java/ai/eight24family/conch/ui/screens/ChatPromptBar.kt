package ai.eight24family.conch.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.key
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import ai.eight24family.conch.ui.haptic.LocalSshAiHaptics
import ai.eight24family.conch.ui.haptic.SshAiHaptic
import ai.eight24family.conch.agent.UsageReport
import ai.eight24family.conch.ui.viewmodel.CostStats
import kotlin.math.roundToInt
import ai.eight24family.conch.ui.viewmodel.UsageBarState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import ai.eight24family.conch.ui.keyboard.shortcuts
import ai.eight24family.conch.ui.window.handCursor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.RemoteSession
import ai.eight24family.conch.agent.SessionState
import ai.eight24family.conch.agent.SlashCommand
import ai.eight24family.conch.agent.SlashCommands
import ai.eight24family.conch.agent.spec.AgentSpecRegistry
import ai.eight24family.conch.agent.spec.ModelMenuItem
import ai.eight24family.conch.agent.spec.TopbarModelState
import ai.eight24family.conch.data.prefs.AgentApprovalMode
import ai.eight24family.conch.ui.components.CopyableCodeBlock
import ai.eight24family.conch.ui.viewmodel.ChatModal
import ai.eight24family.conch.ui.viewmodel.ChatViewModel
import ai.eight24family.conch.util.PathDetector
import ai.eight24family.conch.util.SilentlyTry
import ai.eight24family.conch.ui.viewmodel.MemoryDocs
import ai.eight24family.conch.ui.viewmodel.MemoryScope
import ai.eight24family.conch.ui.viewmodel.StagedAttachment
import ai.eight24family.conch.ui.viewmodel.UploadStatus
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun SlashAutocomplete(items: List<SlashCommand>, onPick: (SlashCommand) -> Unit) {
    val cyan = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 4.dp)
    ) {
        items.forEach { cmd ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(cmd) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "/${cmd.name}",
                    color = cyan,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(end = 10.dp)
                )
                Text(
                    cmd.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ───────────────────────── Empty-state greeting ─────────────────────────

@Composable
internal fun EmptyChatGreeting(agentName: String, host: String, ready: Boolean) {
    val cyan = MaterialTheme.colorScheme.primary
    val magenta = MaterialTheme.colorScheme.secondary
    val dim = MaterialTheme.colorScheme.outline
    val fg = MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = magenta, fontWeight = FontWeight.Bold)) { append("Conch") }
                withStyle(SpanStyle(color = dim)) { append(" ▌ ") }
                withStyle(SpanStyle(color = cyan, fontWeight = FontWeight.Bold)) { append(agentName) }
                if (host.isNotBlank()) {
                    withStyle(SpanStyle(color = dim)) { append(" @ ") }
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(host) }
                }
            },
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            "// ${if (ready) "ready" else "warming up the link…"}",
            color = dim,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "// type a message — or attach files via the paperclip.",
            color = dim,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = cyan, fontWeight = FontWeight.Bold)) { append("❯ ") }
                withStyle(SpanStyle(color = fg)) { append("waiting for your first prompt") }
            },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

// ───────────────────────── Prompt bar ─────────────────────────

/**
 * The bar directly above the input row. Collapsed: a thin accent fill bar with
 * "14% · 3h" (nearest window) — or "$0.42" spend, or a plain divider when
 * there's nothing. TAP → it expands UPWARD **in place**, lifting the chat, to
 * show ALL windows (5h, weekly, per-model) + this chat's spend. NOT an overlay
 * sheet — the panel lives in the layout, so the weight(1) message list above
 * simply shrinks.
 */
/** Shown in place of the usage bar when the current agent is in a BLOCK Claude
 *  run-state — the honest, SPECIFIC "this can't run + why" signal above the input,
 *  matching the agent-picker badge and the session-list marker. */
@Composable
private fun CodeBlockedBanner(text: String) {
    val amber = MaterialTheme.colorScheme.tertiary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(amber.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = amber,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = amber,
            maxLines = 3,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun UsageBar(
    usage: UsageBarState,
    report: UsageReport?,
    cost: CostStats,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    contextBreakdown: List<ai.eight24family.conch.agent.ContextSegment>? = null,
    contextLoading: Boolean = false,
) {
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outline
    val expandable = (report?.windows?.isNotEmpty() == true) ||
        cost.totalCostUsd > 0.0 || (cost.inputTokens + cost.outputTokens) > 0L

    Column(Modifier.fillMaxWidth()) {
        // Detail panel — FIRST child, so it grows ABOVE the thin bar and pushes
        // the message list up. In the layout flow, not a ModalBottomSheet.
        AnimatedVisibility(
            visible = expanded && expandable,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            UsagePanel(report, cost, contextBreakdown, contextLoading)
        }

        // Collapsed bar — ALWAYS rendered at a CONSTANT height, so it reserves
        // its space from the moment the chat opens. It never pops in late and
        // shoves/covers the chat: data just fills in (blank label + empty track
        // → percent + accent fill animate in). A space placeholder keeps the
        // label row's height identical before and after data arrives.
        val fill by animateFloatAsState(
            targetValue = usage.fill.coerceIn(0f, 1f),
            animationSpec = tween(600, easing = FastOutSlowInEasing),
            label = "usageFill",
        )
        // Colour keys off how much is CONSUMED (severity), never the drawn
        // fill — Codex draws remaining, so a near-full "99% left" bar must read
        // as healthy accent, and only go amber/red as the limit is actually
        // burned down.
        val barColor = when {
            usage.filled && usage.severity >= 0.90f -> MaterialTheme.colorScheme.error
            usage.filled && usage.severity >= 0.75f -> MaterialTheme.colorScheme.tertiary
            else -> accent
        }
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (expandable) Modifier.clickable { onExpandedChange(!expanded) } else Modifier),
        ) {
            // Label row: usage label at the END. The phone glyph that used to sit
            // at the START here MOVED to the chat title strip (ChatScreenTopBarHost)
            // so the chat shows it in the same place the session list does (user,
            // 2026-06-28). A 1dp spacer keeps the label right-aligned in this
            // SpaceBetween row.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.size(1.dp))
                Text(
                    text = if (usage.label.isEmpty()) " "
                    else usage.label + if (expandable) (if (expanded) "  ⌄" else "  ⌃") else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (usage.filled) barColor else track,
                    maxLines = 1,
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(track.copy(alpha = 0.35f)),
            ) {
                if (usage.filled && fill > 0f) {
                    Box(
                        Modifier
                            .fillMaxWidth(fill)
                            .fillMaxHeight()
                            .background(barColor),
                    )
                }
            }
        }
    }
}

/** Inline detail panel shown when the bar is expanded: every plan window
 *  (5h / weekly / per-model) as a labelled mini-bar + this chat's token/$
 *  spend. Styled to match the app's ServerStatsSheet — lowercase labels,
 *  `//` comment headers, dim-label / bright-value rows. */
@Composable
private fun UsagePanel(
    report: UsageReport?,
    cost: CostStats,
    contextBreakdown: List<ai.eight24family.conch.agent.ContextSegment>? = null,
    contextLoading: Boolean = false,
) {
    val accent = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val windows = report?.windows.orEmpty()
    val hasContext = cost.contextMax > 0L && cost.contextTokens > 0L
    val hasCost = cost.totalCostUsd > 0.0 || (cost.inputTokens + cost.outputTokens) > 0L

    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = outline.copy(alpha = 0.3f))
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {

            // Context window — the live `/context` summary (segs[0]) is a FIXED
            // toggle row. Tapping it unfolds the per-category breakdown
            // (segs[1.]: System prompt / System tools / Skills / Messages /
            // Free space …) UPWARD, ABOVE this row, so the summary row and the
            // plan-limit rows below it NEVER move. Only the details animate
            // (their own AnimatedVisibility) — the rest of the panel stays put,
            // no whole-panel re-slide. Details start collapsed on every open.
            // No "computing…" placeholder — the row just appears when the (~2s)
            // probe lands.
            val ctxSegs = contextBreakdown?.takeIf { it.isNotEmpty() }
            val ctxSummary = ctxSegs?.firstOrNull()
            val ctxDetails = ctxSegs?.drop(1).orEmpty()
            // Reserve the row whenever a Claude probe is in flight too, so the
            // "Context window" row is present from the INSTANT the panel opens —
            // stable panel height, the chat lifts ABOVE it, and the real value
            // just fills in without the row popping in late and covering text.
            val showedContext = ctxSummary != null || hasContext || contextLoading
            var contextDetailsExpanded by remember { mutableStateOf(false) }
            if (ctxSummary != null) {
                // Details FIRST, so they sit ABOVE the summary row and grow
                // upward into the chat while the summary + limits hold still.
                AnimatedVisibility(
                    visible = contextDetailsExpanded && ctxDetails.isNotEmpty(),
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        ctxDetails.forEach { seg ->
                            UsageMeterRow(
                                label = seg.label,
                                labelColor = dim,
                                value = "${seg.tokens} · ${seg.percent}%",
                                valueColor = dim,
                                trailing = null,
                                fraction = (seg.percent / 100f).coerceIn(0f, 1f),
                                fill = accent,
                                track = outline,
                            )
                        }
                    }
                }
                // The summary row — also the collapse/expand toggle. "⌃" = will
                // unfold upward; "⌄" = expanded, tap to fold back down.
                UsageMeterRow(
                    label = ctxSummary.label,
                    labelColor = dim,
                    value = "${ctxSummary.tokens} · ${ctxSummary.percent}%",
                    valueColor = dim,
                    trailing = if (ctxDetails.isNotEmpty()) (if (contextDetailsExpanded) "⌄" else "⌃") else null,
                    fraction = (ctxSummary.percent / 100f).coerceIn(0f, 1f),
                    fill = accent,
                    track = outline,
                    onClick = if (ctxDetails.isNotEmpty()) ({ contextDetailsExpanded = !contextDetailsExpanded }) else null,
                )
            } else if (hasContext) {
                // No live breakdown (non-Claude, or probe miss): fall back to
                // the cost-derived footprint — no chevron, nothing to disclose.
                val frac = (cost.contextTokens.toFloat() / cost.contextMax.toFloat()).coerceIn(0f, 1f)
                UsageMeterRow(
                    label = "Context window",
                    labelColor = dim,
                    value = "${fmtTok(cost.contextTokens)} / ${fmtTok(cost.contextMax)} (${(frac * 100f).roundToInt()}%)",
                    valueColor = dim,
                    trailing = null,
                    fraction = frac,
                    fill = accent,
                    track = outline,
                )
            } else if (contextLoading) {
                // Probe in flight, no value yet: render a reserved placeholder
                // row (same height) so the panel is full-height immediately and
                // the real value drops in later without growing/covering.
                UsageMeterRow(
                    label = "Context window",
                    labelColor = dim,
                    value = "…",
                    valueColor = dim,
                    trailing = null,
                    fraction = 0f,
                    fill = accent,
                    track = outline,
                )
            }

            // Plan windows — one meter each (5-hour / weekly / per-model). No
            // "Plan usage" header: two words for a whole row of wasted space,
            // and the row labels already say what they are.
            if (windows.isNotEmpty()) {
                if (showedContext) Spacer(Modifier.height(10.dp))
                windows.forEach { w ->
                    val wReset = w.resetTextLive(System.currentTimeMillis())
                    UsageMeterRow(
                        label = w.label,
                        labelColor = onSurface,
                        value = "${w.percent}%" + if (wReset.isNotEmpty()) " · resets $wReset" else "",
                        valueColor = dim,
                        trailing = null,
                        fraction = w.fraction,
                        fill = accent,
                        track = outline,
                    )
                }
            }

            // Extra usage — Claude's pay-as-you-go overage spend ($), reported
            // alongside the plan windows. Shown even at $0.00 (the account
            // exposes it and the user wants it visible).
            report?.extraUsedUsd?.let { extra ->
                if (windows.isNotEmpty() || showedContext) Spacer(Modifier.height(10.dp))
                // Dim, matching the other rows — not the bold/bright usageStatRow.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Extra usage",
                        style = MaterialTheme.typography.bodyMedium,
                        color = dim,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "$" + String.format(java.util.Locale.US, "%.2f", extra) + " spent",
                        style = MaterialTheme.typography.bodyMedium,
                        color = dim,
                    )
                }
            }

            // API-key mode (no plan windows) → show this chat's spend instead.
            if (windows.isEmpty() && hasCost) {
                if (showedContext) Spacer(Modifier.height(16.dp))
                Text("This chat", style = MaterialTheme.typography.bodyMedium, color = dim)
                Spacer(Modifier.height(6.dp))
                if (cost.totalCostUsd > 0.0) {
                    usageStatRow("Cost", "$" + String.format(java.util.Locale.US, "%.2f", cost.totalCostUsd))
                }
                usageStatRow("Input", fmtTok(cost.inputTokens))
                usageStatRow("Output", fmtTok(cost.outputTokens))
                if (cost.cacheReadTokens > 0L) usageStatRow("Cache read", fmtTok(cost.cacheReadTokens))
                usageStatRow("Turns", cost.turns.toString())
            }

            if (!showedContext && windows.isEmpty() && !hasCost) {
                Text("No usage data yet.", style = MaterialTheme.typography.bodySmall, color = dim)
            }
        }
    }
}

/** One reference-style meter: label + value on a line, full-width thin bar
 *  directly below it (matches Claude's /usage panel layout). */
@Composable
private fun UsageMeterRow(
    label: String,
    labelColor: Color,
    value: String,
    valueColor: Color,
    trailing: String?,
    fraction: Float,
    fill: Color,
    track: Color,
    onClick: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor, maxLines = 1)
            if (trailing != null) {
                Spacer(Modifier.width(6.dp))
                Text(trailing, style = MaterialTheme.typography.bodyMedium, color = valueColor)
            }
        }
        Spacer(Modifier.height(7.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(track.copy(alpha = 0.3f)),
        ) {
            if (fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(fill),
                )
            }
        }
    }
}

@Composable
private fun usageStatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun fmtTok(n: Long): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", n / 1_000.0)
    else -> n.toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PromptBar(
    input: String,
    onInputChange: (String) -> Unit,
    canSend: Boolean,
    working: Boolean,
    /** Current agent in a BLOCK Claude run-state. Shows [codeBlockText] as a
     *  warning banner in place of the (stale, meaningless) usage bar; send is
     *  already gated off via [canSend]. */
    codeBlocked: Boolean = false,
    /** The specific reason a turn won't run (no subscription / trial ended / rate
     *  limited / login expired …) — the banner text. */
    codeBlockText: String? = null,
    usage: UsageBarState,
    usageReport: UsageReport?,
    usageCost: CostStats,
    usageExpanded: Boolean,
    onUsageExpandedChange: (Boolean) -> Unit,
    contextBreakdown: List<ai.eight24family.conch.agent.ContextSegment>? = null,
    contextLoading: Boolean = false,
    uploading: Boolean,
    statusHint: String?,
    enterSends: Boolean,
    attachments: List<StagedAttachment>,
    canAttachMore: Boolean,
    onAddAttachment: (bytes: ByteArray, displayName: String, mimeType: String?) -> Unit,
    onAddFileAttachment: (file: java.io.File, displayName: String, mimeType: String?, sizeBytes: Long) -> Unit = { _, _, _, _ -> },
    onRemoveAttachment: (id: String) -> Unit,
    onConnectPhone: () -> Unit,
    onStop: () -> Unit,
    onSend: () -> Unit
) {
    val ctx = LocalContext.current
    var sheetOpen by remember { mutableStateOf(false) }

    val photoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris ->
        uris.forEach { uri ->
            ingestUri(ctx, uri, onAddAttachment, onAddFileAttachment)
        }
    }
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            ingestUri(ctx, uri, onAddAttachment, onAddFileAttachment)
        }
    }

    val cyan = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline

    Column {
        // Usage/limit bar — replaces the old plain divider. Shows the
        // nearest plan limit (accent fill + "14% · 3h"), or API spend, or
        // degrades to a 1.dp divider when there's nothing to report. Tap →
        // full breakdown (all windows + this chat's spend).
        //
        // When the subscription has NO Claude Code, the usage bar is a LIE (a
        // stale "12% · resets now" from when the plan was live), so replace it
        // with an honest warning — the dead subscription must read as dead here
        // too, not just on the agent-picker row.
        if (codeBlocked) CodeBlockedBanner(codeBlockText ?: "This account can't run Claude Code right now.")
        else UsageBar(usage, usageReport, usageCost, usageExpanded, onUsageExpandedChange, contextBreakdown, contextLoading)

        // Staged attachments strip
        if (attachments.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(attachments, key = { it.id }) { att ->
                    AttachmentChip(att = att, onRemove = { onRemoveAttachment(att.id) })
                }
            }
            HorizontalDivider(thickness = 1.dp, color = outline.copy(alpha = 0.4f))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { sheetOpen = true },
                enabled = canAttachMore
            ) {
                Icon(
                    Icons.Default.AttachFile,
                    contentDescription = "attach files",
                    tint = if (canAttachMore) cyan else outline
                )
            }
            Text(
                "❯",
                color = cyan,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(end = 6.dp)
            )
            BasicTextField(
                value = input,
                onValueChange = onInputChange,
                // No `enabled = ...` — the user can type AND queue
                // attachments while the SSH session is still bootstrapping.
                // Only the send button is gated by [canSend]; the prompt
                // they were drafting doesn't get held hostage by the
                // handshake.
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { ev ->
                        // Two send chords:
                        //   Ctrl+Enter (or Cmd+Enter on Apple keyboards) — ALWAYS
                        //     sends, regardless of the "Enter sends" setting.
                        //     The discoverable power-user shortcut for DeX +
                        //     Bluetooth-keyboard daily use.
                        //   Bare Enter — sends only when "Enter sends" is
                        //     enabled in Settings; otherwise inserts a newline
                        //     (the on-screen IME never delivers this anyway,
                        //     it uses imeAction.Send wired below).
                        val isCtrlEnter = ev.type == KeyEventType.KeyDown &&
                            (ev.key == Key.Enter || ev.key == Key.NumPadEnter) &&
                            (ev.isCtrlPressed || ev.isMetaPressed) &&
                            !ev.isShiftPressed && !ev.isAltPressed
                        val isPlainEnter = enterSends && ev.type == KeyEventType.KeyDown &&
                            ev.key == Key.Enter && !ev.isShiftPressed &&
                            !ev.isCtrlPressed && !ev.isMetaPressed
                        if (isCtrlEnter || isPlainEnter) {
                            if (canSend) { onSend(); true } else true
                        } else false
                    },
                textStyle = LocalTextStyle.current.merge(
                    MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
                ),
                cursorBrush = SolidColor(cyan),
                keyboardOptions = KeyboardOptions(
                    imeAction = if (enterSends) ImeAction.Send else ImeAction.Default
                ),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                maxLines = 6,
                decorationBox = { inner ->
                    if (input.isEmpty()) {
                        Text(
                            if (attachments.isNotEmpty()) "add a comment for the file(s)…"
                            else "tell the agent what to do…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    inner()
                }
            )
            // Send / stop action — borderless icon button (paper-plane
            // for send, filled square for stop). Bracket pill is gone —
            // the user said the small `↵` inside it looked off.
            //
            // While a turn is working, the button defaults to Stop. The
            // moment the user starts drafting (text or attachments), it
            // flips back to Send so they can mid-turn-queue a new prompt
            // without first having to dismiss something. AgentSession's
            // FIFO queue handles serialising the new send behind the
            // in-flight turn. Empty draft → Stop reappears.
            val hasDraft = input.isNotBlank() || attachments.isNotEmpty()
            val showStop = working && !hasDraft
            val sendEnabled = canSend && hasDraft
            val tint = when {
                showStop -> MaterialTheme.colorScheme.error
                sendEnabled -> cyan
                else -> outline
            }
            val sendHaptic = ai.eight24family.conch.ui.haptic.LocalSshAiHaptics.current
            IconButton(
                onClick = {
                    // Tap on send, Heavy on stop (stop is consequential
                    // — killing a long-running agent turn — and deserves
                    // the more emphatic feedback).
                    sendHaptic.perform(
                        if (showStop)
                            ai.eight24family.conch.ui.haptic.SshAiHaptic.Heavy
                        else
                            ai.eight24family.conch.ui.haptic.SshAiHaptic.Tap
                    )
                    if (showStop) onStop() else onSend()
                },
                enabled = showStop || sendEnabled,
                // Phase 7 DeX polish: hand cursor over the send button.
                // No-op on touch — only kicks in when a real mouse cursor
                // is present (DeX, Chromebook).
                modifier = Modifier.padding(start = 4.dp).handCursor(),
            ) {
                if (showStop) {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = "stop",
                        tint = tint,
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "send",
                        tint = tint,
                    )
                }
            }
        }

        // Status sub-line: explains in one comment-line what the session is
        // doing right now. Empty when there's nothing meaningful to say
        // (idle Running session + attachments + typed text = ready to send,
        // no commentary needed). Otherwise the user is no longer staring at
        // a silent disabled button wondering whether the app crashed.
        if (statusHint != null) {
            Text(
                statusHint,
                color = outline,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
            )
        }
    }

    if (sheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            containerColor = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    "// attach (${attachments.size}/${ChatViewModel.MAX_ATTACHMENTS})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp)
                )
                ListItem(
                    leadingContent = {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = "attach photo", tint = cyan)
                    },
                    headlineContent = { Text("Photos & videos") },
                    supportingContent = { Text("up to 10 at once") },
                    modifier = Modifier.clickable {
                        sheetOpen = false
                        photoLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    }
                )
                ListItem(
                    leadingContent = {
                        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = "attach file", tint = cyan)
                    },
                    headlineContent = { Text("Files") },
                    supportingContent = { Text("any document — uploaded via SFTP") },
                    modifier = Modifier.clickable {
                        sheetOpen = false
                        fileLauncher.launch(arrayOf("*/*"))
                    }
                )
                ListItem(
                    leadingContent = {
                        Icon(Icons.Default.PhoneAndroid, contentDescription = "connect phone", tint = cyan)
                    },
                    headlineContent = { Text("Connect phone to server") },
                    supportingContent = { Text("let the agent run commands & read logs on this phone (Shizuku)") },
                    modifier = Modifier.clickable {
                        sheetOpen = false
                        onConnectPhone()
                    }
                )
                if (clipboardHasImage(ctx)) {
                    ListItem(
                        leadingContent = {
                            Icon(Icons.Default.ContentPaste, contentDescription = "paste", tint = cyan)
                        },
                        headlineContent = { Text("Paste image from clipboard") },
                        modifier = Modifier.clickable {
                            sheetOpen = false
                            pasteImageFromClipboard(ctx, onAddAttachment)
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun AttachmentChip(att: StagedAttachment, onRemove: () -> Unit) {
    val cyan = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val errorColor = MaterialTheme.colorScheme.error
    val isFailed = att.status is UploadStatus.Failed
    val borderColor = when {
        isFailed -> errorColor
        att.status is UploadStatus.Ready -> cyan
        else -> outline
    }
    Box(
        modifier = Modifier.size(64.dp)
    ) {
        // Body — image preview or file tile
        if (att.isImage) {
            val bitmap: ImageBitmap? = remember(att.id) {
                SilentlyTry.logged("SshAi-ChatPrompt", "decode attachment bitmap") {
                    BitmapFactory.decodeByteArray(att.bytes, 0, att.bytes.size)?.asImageBitmap()
                }
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = att.displayName,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RectangleShape)
                        .border(1.dp, borderColor, RectangleShape)
                )
            } else {
                FileTile(att, cyan, borderColor)
            }
        } else {
            FileTile(att, cyan, borderColor)
        }

        // Upload progress / status overlay
        when (val st = att.status) {
            is UploadStatus.Uploading -> {
                // Dim + linear bar at the bottom showing progress.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RectangleShape)
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.45f))
                )
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { st.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.BottomCenter),
                    color = cyan,
                    trackColor = outline.copy(alpha = 0.3f)
                )
                Text(
                    "${(st.progress * 100).toInt()}%",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
            is UploadStatus.Ready -> {
                // Small ✓ in bottom-left to confirm "ready to send".
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    shape = CircleShape,
                    border = androidx.compose.foundation.BorderStroke(1.dp, cyan),
                    modifier = Modifier
                        .size(18.dp)
                        .align(Alignment.BottomStart)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "ready",
                            tint = cyan,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
            is UploadStatus.Failed -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RectangleShape)
                        .background(errorColor.copy(alpha = 0.20f))
                )
                Text(
                    "!",
                    color = errorColor,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        // Remove (X) badge — always visible, top-right
        Surface(
            color = MaterialTheme.colorScheme.background,
            shape = CircleShape,
            border = androidx.compose.foundation.BorderStroke(1.dp, outline),
            modifier = Modifier
                .size(20.dp)
                .align(Alignment.TopEnd)
                .clickable(onClick = onRemove)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "remove",
                    tint = errorColor,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
internal fun FileTile(att: StagedAttachment, cyan: androidx.compose.ui.graphics.Color, outline: androidx.compose.ui.graphics.Color) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, outline, RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            tint = cyan,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = att.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ───────────────────────── Attachment helpers ─────────────────────────

/** Largest file we read fully into RAM (for an inline image preview). Anything
 *  bigger — or any non-image — is streamed to a temp file instead, so a huge
 *  attachment never has to fit in the phone's heap (user 2026-06-14). */
private const val MAX_INMEM_ATTACHMENT_BYTES = 25L * 1024 * 1024

private fun ingestUri(
    ctx: Context,
    uri: Uri,
    onAddAttachment: (bytes: ByteArray, displayName: String, mimeType: String?) -> Unit,
    onAddFileAttachment: (file: java.io.File, displayName: String, mimeType: String?, sizeBytes: Long) -> Unit,
) {
    val name = queryDisplayName(ctx, uri)
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "file_${System.currentTimeMillis()}"
    val mime = ctx.contentResolver.getType(uri)
    val size = querySize(ctx, uri)
    val isImage = mime?.startsWith("image/") == true ||
        name.substringAfterLast('.', "").lowercase() in INGEST_IMAGE_EXTS
    // Small image → keep in RAM so the chip + inline preview can decode it.
    // Everything else (large image, any non-image, unknown-but-non-image) →
    // stream to a temp file in cacheDir, never materialising it in the heap.
    val keepInMemory = isImage && size in 0..MAX_INMEM_ATTACHMENT_BYTES
    if (keepInMemory) {
        val bytes = SilentlyTry.logged("SshAi-ChatPrompt", "read attachment bytes") {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } ?: return
        onAddAttachment(bytes, name, mime)
        return
    }
    val dir = java.io.File(ctx.cacheDir, "conch_uploads").apply { mkdirs() }
    val tmp = java.io.File(dir, "${System.currentTimeMillis()}_${name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)}")
    val ok = SilentlyTry.logged("SshAi-ChatPrompt", "stream attachment to temp") {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
        } != null
    } == true
    if (!ok || !tmp.exists()) { SilentlyTry.fired("SshAi-ChatPrompt", "delete failed temp") { tmp.delete() }; return }
    onAddFileAttachment(tmp, name, mime, tmp.length())
}

private val INGEST_IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")

private fun querySize(ctx: Context, uri: Uri): Long = SilentlyTry.loggedOrElse("SshAi-ChatPrompt", "query attachment size", -1L) {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
    } ?: -1L
}

private fun queryDisplayName(ctx: Context, uri: Uri): String? = SilentlyTry.logged("SshAi-ChatPrompt", "query attachment display name") {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}

private fun clipboardHasImage(ctx: Context): Boolean {
    val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
    val clip = cb.primaryClip ?: return false
    for (i in 0 until clip.itemCount) {
        val item = clip.getItemAt(i)
        val uri = item.uri ?: continue
        val mime = ctx.contentResolver.getType(uri) ?: continue
        if (mime.startsWith("image/")) return true
    }
    val desc = clip.description ?: return false
    return (0 until desc.mimeTypeCount).any { desc.getMimeType(it).startsWith("image/") }
}

private fun pasteImageFromClipboard(
    ctx: Context,
    onAddAttachment: (bytes: ByteArray, displayName: String, mimeType: String?) -> Unit
) {
    val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = cb.primaryClip ?: return
    for (i in 0 until clip.itemCount) {
        val item = clip.getItemAt(i)
        val uri = item.uri ?: continue
        val mime = ctx.contentResolver.getType(uri) ?: continue
        if (!mime.startsWith("image/")) continue
        // Clipboard images are small → in-memory path; never the streamed one.
        ingestUri(ctx, uri, onAddAttachment, onAddFileAttachment = { f, _, _, _ -> f.delete() })
    }
}
