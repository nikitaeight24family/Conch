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
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.RestartAlt
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.material3.CenterAlignedTopAppBar
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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import ai.eight24family.conch.ui.haptic.LocalSshAiHaptics
import ai.eight24family.conch.ui.haptic.SshAiHaptic
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import kotlin.math.roundToInt
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
import ai.eight24family.conch.ui.components.ConnectionDot
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

// ───────────────────────── TopBar ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TerminalTopBar(
    title: String,
    agent: Agent,
    serverName: String,
    /** Id of [serverName]'s server when it has resolved — the reliable key for
     *  the per-server accent colour; the name is the fallback key. */
    serverId: String? = null,
    /** Live transport state for [serverName]'s server — drives the ●/○ dot next
     *  to the name so the user can see online/offline at a glance. */
    connected: Boolean,
    selectedModel: String?,
    observedModel: String?,
    availableModels: Map<String, String>,
    /** Keys resolvable but NOT choosable — see TopbarModelState.hiddenModels. */
    hiddenModels: Set<String> = emptySet(),
    unavailableModels: Set<String> = emptySet(),
    observationNewerThanPick: Boolean = true,
    /** See TopbarModelState.reasoningPickIsNewer — a stale effort pick must not
     *  outrank what the session reports. */
    reasoningPickIsNewer: Boolean = false,
    modelsProbing: Boolean,
    modelsStale: Boolean = false,
    defaultModel: String?,
    sessionInitialModel: String?,
    selectedReasoning: String?,
    reasoningCatalog: Map<String, ai.eight24family.conch.agent.spec.ModelReasoningInfo>,
    defaultReasoning: String?,
    sessionInitialReasoning: String?,
    observedReasoning: String?,
    modelMenuOpen: Boolean,
    onToggleModelMenu: () -> Unit,
    onSelectModel: (String?) -> Unit,
    onSelectModelAndReasoning: (model: String?, effort: String?) -> Unit,
    onOpenServerStats: () -> Unit,
    customCommands: List<SlashCommand>,
    commandMenuOpen: Boolean,
    onToggleCommandMenu: () -> Unit,
    onPickCommand: (SlashCommand) -> Unit,
    onOpenMemory: () -> Unit,
    onOpenAgents: () -> Unit,
    showSubagentsIcon: Boolean,
    showMemoryIcon: Boolean,
    /** Shown for agents whose control channel supports rename_session
     *  (Claude). Opens the rename dialog owned by the host. */
    showRenameItem: Boolean = false,
    onRenameSession: () -> Unit = {},
    /** Branch this conversation into a new chat. Null-op unless the session
     *  actually has an id to inherit from. */
    onForkChat: () -> Unit = {},
    showForkItem: Boolean = false,
    /** Manual compaction — shown where the CLI offers `/compact`. */
    showCompactItem: Boolean = false,
    onCompact: () -> Unit = {},
    onRestartCli: () -> Unit = {},
    approvalMode: AgentApprovalMode,
    approvalMenuOpen: Boolean,
    onToggleApprovalMenu: () -> Unit,
    onSelectApproval: (AgentApprovalMode) -> Unit,
    onAskAgentToRelaxApprovals: () -> Unit,
    /** When false, the approval shield icon is hidden from the top bar
     *  (Settings toggle — "I granted my level once, stop showing it"). */
    showApprovalIcon: Boolean,
    /** True iff a turn is currently in flight (local Working state OR
     *  the remote agent process is alive). Drives the breathing ✦
     *  rendered to the right of the model picker. */
    showWorkingHint: Boolean,
    onOpenSettings: () -> Unit,
    /** True iff in-chat search is open. Drives the title-slot
     *  transformation (title fades out, search field expands from the
     *  loupe-icon edge) and flips the actions-slot icon between
     *  Search ↔ Close. */
    searchActive: Boolean,
    /** Current search query when active. Caller owns the state. */
    searchQuery: String,
    /** Match count shown right-aligned inside the expanded search bar. */
    searchMatchCount: Int,
    /** Edits to the query field. */
    onSearchQueryChange: (String) -> Unit,
    /** Tap on the loupe — opens the search bar (and steals title). */
    onOpenSearch: () -> Unit,
    /** Tap on the close (×) icon while search is open. */
    onCloseSearch: () -> Unit,
    onBack: () -> Unit,
) {
    // All per-agent UI decisions for the topbar live on the spec
    // (`AgentTopbarUi`). This composable is intentionally agent-agnostic
    // — no `if (isClaude)` / `if (isCodex)` branches survive here.
    // Adding a fourth CLI is one new TopbarUi alongside the new spec,
    // zero edits to this file.
    val spec = AgentSpecRegistry[agent]
    val topbarUi = spec.topbarUi
    val topbarState = TopbarModelState(
        agentDisplayName = spec.displayName,
        selectedModel = selectedModel,
        sessionInitialModel = sessionInitialModel,
        observedModel = observedModel,
        defaultModel = defaultModel,
        availableModels = availableModels,
        hiddenModels = hiddenModels,
        unavailableModels = unavailableModels,
        observationNewerThanPick = observationNewerThanPick,
        reasoningPickIsNewer = reasoningPickIsNewer,
        modelsProbing = modelsProbing,
        selectedReasoning = selectedReasoning,
        reasoningCatalog = reasoningCatalog,
        defaultReasoning = defaultReasoning,
        sessionInitialReasoning = sessionInitialReasoning,
        observedReasoning = observedReasoning,
    )
    val displayLabel = topbarUi.displayLabel(topbarState)
    val reasoningSubLabel = topbarUi.reasoningLabel(topbarState)
    val enableMenu = topbarUi.isMenuEnabled(topbarState)
    val menuItems = topbarUi.menuItems(topbarState)
    // Two-stage picker state: `null` = showing model list, non-null
    // = showing reasoning levels for that ModelMenuItem. Reset on
    // every dropdown close so the user always starts on the model
    // stage. Lives at TerminalTopBar scope so it survives recomposes
    // triggered by underlying flow updates while the menu is open.
    var pickerDrillModel by remember { mutableStateOf<ModelMenuItem?>(null) }
    LaunchedEffect(modelMenuOpen) { if (!modelMenuOpen) pickerDrillModel = null }
    // Window-x of the model chip (the dropdown's anchor) — used to center
    // the stage-2 effort panel on the SCREEN instead of hanging off the
    // anchor's left edge.
    var modelAnchorX by remember { mutableStateOf(0f) }
    // Focus requester for the inline search field — when search opens
    // we want the keyboard up immediately, no extra tap to focus.
    val searchFieldFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(searchActive) {
        if (searchActive) {
            // tiny yield so the field has been measured/composed before
            // requesting focus — without this, requestFocus on a freshly
            // expanded TextField is sometimes dropped on the floor.
            kotlinx.coroutines.delay(40)
            SilentlyTry.fired("SshAi-ChatTopBar", "request search focus (open)") { searchFieldFocus.requestFocus() }
            // Explicitly poke the IME up. requestFocus on a freshly
            // composed TextField sometimes only sets focus state without
            // raising the soft keyboard (esp. when the field appears
            // mid-animation). Android will keep the soft keyboard hidden
            // anyway if a physical keyboard is attached — that's the
            // platform's behaviour, not something we override.
            SilentlyTry.fired("SshAi-ChatTopBar", "show soft keyboard") { keyboardController?.show() }
        }
    }
    // Focus-loss → close. Tapping anywhere outside the field (chat
    // content, status line, etc.) drops focus → we close search after
    // a short debounce window. The window lets the × icon clear the
    // text and re-request focus WITHOUT also closing the search.
    val closeScope = rememberCoroutineScope()
    val pendingClose = remember { object { var job: kotlinx.coroutines.Job? = null } }
    // Re-armed each time the bar opens — without this, sawFocus would
    // remain true after a close/reopen cycle and the first focus-loss
    // would close immediately before the field had a chance to focus.
    var sawFocus by remember(searchActive) { mutableStateOf(false) }
    CenterAlignedTopAppBar(
        // The title now holds TWO lines on the model side (model name + the
        // reasoning degree under it) next to the single-line server name, so
        // the bar is a touch taller than the old single-line 44dp. 56dp clears
        // the 48dp icon touch targets and fits the stacked model chip.
        expandedHeight = 56.dp,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.primary,
            navigationIconContentColor = MaterialTheme.colorScheme.primary,
            actionIconContentColor = MaterialTheme.colorScheme.primary,
        ),
        title = {
            // Title slot is a Box so the inline search field can OVERLAY
            // the normal title+subtitle while it animates open. When
            // `searchActive` is true the title fades out and the search
            // bar expands from the End edge (right side, anchored at the
            // loupe icon's column) leftward — feels like the loupe
            // "unfolds" into a full-width input. Other actions disappear
            // for the duration so the input owns the whole topbar width.
            // fillMaxWidth ONLY while searching (the search field needs the
            // whole width). Otherwise intrinsic, so CenterAlignedTopAppBar can
            // center the model chip at TRUE bar-center.
            Box(modifier = if (searchActive) Modifier.fillMaxWidth() else Modifier) {
                // Layer 1: normal title. Fades out (not collapses) when
                // search opens so the topbar height stays constant.
                AnimatedVisibility(
                    visible = !searchActive,
                    enter = fadeIn(animationSpec = tween(160)),
                    exit = fadeOut(animationSpec = tween(120)),
                ) {
            Column {
                // The title is JUST the model chip — CenterAlignedTopAppBar
                // centers it at true bar-center (no Row/weights → no left-jump,
                // no overlap). The server name moved to the navigation slot
                // (next to the back arrow), where a long name has room.
                Box(
                    modifier = Modifier.onGloballyPositioned {
                        modelAnchorX = it.positionInWindow().x
                    },
                ) {
                        // The chip renders even when NO model is known yet. Gating it
                        // on displayLabel meant a FRESH chat had no chip at all — and
                        // the chip is the picker's only entry point, so the user could
                        // not choose a model until after the first message, by which
                        // time the CLI had already started the session on whatever
                        // ~/.claude/settings.json pins (and, when that pin is
                        // credit-gated, already silently fallen back). That is the
                        // trap (user, 2026-07-23): the pick only became possible once
                        // it no longer mattered. The placeholder is a NOUN, never a
                        // model name, so TOPBAR-MODEL-NEVER-INVENTED-1 still holds —
                        // we never claim to be running something we have not observed.
                            Column(
                                modifier = Modifier
                                    .clickable(enabled = enableMenu) { onToggleModelMenu() }
                                    .padding(top = 2.dp, bottom = 2.dp),
                            ) {
                                // Line 1: agent brand icon + model name + chevron.
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    androidx.compose.material3.Icon(
                                        painter = androidx.compose.ui.res.painterResource(
                                            ai.eight24family.conch.agent.spec.AgentSpecRegistry[agent].iconRes,
                                        ),
                                        contentDescription = agent.cliCommand,
                                        tint = androidx.compose.ui.graphics.Color.Unspecified,
                                        modifier = Modifier.size(12.dp).padding(end = 4.dp),
                                    )
                                    // Always primary when we have a label —
                                    // never greys mid-probe (that thrash was a
                                    // past bug); the click is gated by enableMenu.
                                    Text(
                                        // NO "model" placeholder word — a new chat
                                        // must open with its real default already
                                        // showing so the user can just type. The
                                        // default now comes from the /model menu's
                                        // "Default (recommended)" row (see
                                        // ClaudeTopbarUi.displayLabel). Blank only
                                        // in the brief window before the catalog is
                                        // known; the chip stays tappable throughout.
                                        displayLabel.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Icon(
                                        Icons.Default.ArrowDropDown,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        // Smaller chevron — the default 24dp icon
                                        // set the model-row height and forced a
                                        // gap above the reasoning line.
                                        modifier = Modifier.padding(start = 1.dp).size(18.dp),
                                    )
                                }
                                // Line 2: reasoning degree, directly under the
                                // model name — almost touching it. Tight lineHeight
                                // (= fontSize) strips the label's leading so it
                                // sits right under "Opus 4.8"; indented past the
                                // brand icon to align under the model text.
                                if (!reasoningSubLabel.isNullOrBlank()) {
                                    Text(
                                        reasoningSubLabel,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            lineHeight = MaterialTheme.typography.labelSmall.fontSize,
                                        ),
                                        color = MaterialTheme.colorScheme.outline,
                                        fontWeight = FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(start = 16.dp),
                                    )
                                }
                            }
                        // No `else` branch any more: the chip itself now occupies
                        // that space with the placeholder, so the bar still doesn't
                        // pop when the real label resolves — and, unlike the old
                        // fixed-height Spacer, it stays tappable the whole time.
                // Stage 2 panel: full-ish width, centered on the SCREEN.
                // The popup normally hangs off the anchor's left edge; an
                // x-offset of (screen−panel)/2 − anchorX recenters it. The
                // offset is keyed into the popup's position provider, so
                // flipping stages while open repositions correctly.
                val effortPanelWidthDp = minOf(LocalConfiguration.current.screenWidthDp - 32, 380)
                val stage2OffsetX: Dp = if (pickerDrillModel != null) {
                    with(LocalDensity.current) {
                        val screenWpx = LocalConfiguration.current.screenWidthDp.dp.toPx()
                        val panelWpx = effortPanelWidthDp.dp.toPx()
                        ((screenWpx - panelWpx) / 2f - modelAnchorX).toDp()
                    }
                } else 0.dp
                DropdownMenu(
                    expanded = modelMenuOpen,
                    onDismissRequest = onToggleModelMenu,
                    offset = DpOffset(stage2OffsetX, 0.dp),
                ) {
                    // Two-stage drill-down picker. Stage 1 lists models
                    // (each row with a `▸` if it has a reasoning submenu);
                    // tapping such a row swaps the menu contents to
                    // Stage 2 (back arrow + reasoning levels for that
                    // model). Stage 2 click commits BOTH model and
                    // reasoning atomically and closes the menu.
                    //
                    // Models without a reasoning catalog commit immediately
                    // on first click — single-stage flow for Gemini and
                    // any future spec that doesn't expose reasoning.
                    // Resolve the drilled model AGAINST THE LIVE menu items:
                    // the tap snapshots a ModelMenuItem, but the probe can
                    // land while the menu is open and refresh the reasoning
                    // catalog — a frozen snapshot kept the effort slider on
                    // the stale pre-probe levels (2026-06-10: hardcoded
                    // 4-level ladder shown while the probe had already
                    // delivered low…ultracode).
                    val drillModel = pickerDrillModel?.let { snap ->
                        menuItems.firstOrNull { it.storedValue == snap.storedValue } ?: snap
                    }
                    if (drillModel == null) {
                        // Say WHERE the list came from when it isn't live. The
                        // picker deliberately keeps the cached catalog rather
                        // than emptying itself, but presenting a cache as a
                        // fresh read is what made this look like a hardcoded
                        // list. One dim line, the fact and the reason, no advice.
                        if (modelsStale) {
                            Text(
                                text = "cached list — could not read /model on this server",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                        menuItems.forEach { item ->
                            val isSelected = selectedModel == item.storedValue
                            val hasReasoning = item.reasoning.isNotEmpty()
                            // A flagged model is DIMMED BUT STILL SELECTABLE.
                            // Disabling the tap made our guess final, and the
                            // guess can be wrong: Fable 5 reads "Requires usage
                            // credits" in the CLI menu off a stale profile cache
                            // while the account's Max plan actually includes it —
                            // the API answers `allowed` and `--model
                            // claude-fable-5` returns a real reply. Vetoing it
                            // locked the user out of a model they pay for (user,
                            // 2026-07-28). The server is the authority; passing
                            // `--model <id>` is the CLI's own documented path, so
                            // let the user try and let the reply decide. If it
                            // genuinely cannot run, the fallback note says why.
                            val unavailable = !item.available
                            val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            DropdownMenuItem(
                                enabled = true,
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            item.display,
                                            modifier = Modifier.weight(1f),
                                            color = when {
                                                unavailable -> dim
                                                isSelected -> MaterialTheme.colorScheme.primary
                                                else -> MaterialTheme.colorScheme.onSurface
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                        if (unavailable) {
                                            Text(
                                                "unavailable",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = dim,
                                                modifier = Modifier.padding(start = 6.dp),
                                            )
                                        }
                                        if (isSelected && !unavailable) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(start = 6.dp, end = 4.dp)
                                            )
                                        }
                                        if (hasReasoning && !unavailable) {
                                            // Right-pointing chevron tells the user
                                            // there's a submenu. Using a literal '▸'
                                            // glyph instead of an icon to avoid
                                            // pulling another Material icon dep.
                                            Text(
                                                "▸",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.padding(start = 6.dp),
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    if (unavailable) return@DropdownMenuItem
                                    if (hasReasoning) {
                                        pickerDrillModel = item
                                    } else {
                                        onSelectModel(item.storedValue)
                                    }
                                }
                            )
                        }
                    } else {
                        // Stage 2 — Claude-style horizontal effort slider
                        // (Faster ↔ Smarter), driven entirely by the
                        // model's PROBED reasoning levels. The level in
                        // effect = explicit pick for this model, else its
                        // default (which is the server's probed current).
                        val activeEffort = if (selectedModel == drillModel.storedValue) {
                            selectedReasoning?.takeIf { it.isNotBlank() } ?: drillModel.defaultReasoning
                        } else {
                            drillModel.defaultReasoning
                        }
                        EffortSlider(
                            modelDisplay = drillModel.display,
                            levels = drillModel.reasoning,
                            defaultEffort = drillModel.defaultReasoning,
                            activeEffort = activeEffort,
                            panelWidth = effortPanelWidthDp.dp,
                            onBack = { pickerDrillModel = null },
                            onPick = { effort ->
                                // Always store the literal level tapped —
                                // never null out even when it matches the
                                // model's default (the CLI would otherwise
                                // fall back to its config-file effort and
                                // silently override the picker).
                                onSelectModelAndReasoning(drillModel.storedValue, effort)
                            },
                        )
                    }
                }
                    }
            }
                }
                // Layer 2: the inline search field. Anchored to the End
                // of the Box, expanding horizontally from End → Start
                // so it visually unfolds from the loupe icon's column.
                // Spring with low stiffness gives a slight elastic settle;
                // exit is a tighter tween so closing doesn't drag.
                AnimatedVisibility(
                    visible = searchActive,
                    enter = expandHorizontally(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                        expandFrom = Alignment.End,
                    ) + fadeIn(animationSpec = tween(140)),
                    exit = shrinkHorizontally(
                        animationSpec = tween(180, easing = FastOutSlowInEasing),
                        shrinkTowards = Alignment.End,
                    ) + fadeOut(animationSpec = tween(120)),
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    // No prefix loupe — the actions-slot icon already
                    // serves search-open / search-close. Two loupes
                    // side-by-side read as visual noise (different
                    // tints, unclear which is which).
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.TextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(searchFieldFocus)
                                .onFocusChanged { fs ->
                                    if (fs.isFocused) {
                                        // Field (re-)gained focus — cancel any
                                        // pending close so a focus-bounce (e.g.
                                        // tap on × that briefly steals focus)
                                        // doesn't tear down the search bar.
                                        pendingClose.job?.cancel()
                                        pendingClose.job = null
                                        sawFocus = true
                                    } else if (sawFocus && searchActive) {
                                        pendingClose.job?.cancel()
                                        pendingClose.job = closeScope.launch {
                                            kotlinx.coroutines.delay(80)
                                            onCloseSearch()
                                        }
                                    }
                                },
                            placeholder = {
                                Text(
                                    "search in chat",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            },
                            singleLine = true,
                            // ImeAction.Search renders Enter as the IME's
                            // magnifying-glass button. Filtering is live
                            // (every keystroke updates results), so Enter
                            // doesn't trigger a new search — it just
                            // dismisses the keyboard so the user can see
                            // their results full-screen.
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = { keyboardController?.hide() },
                            ),
                            colors = androidx.compose.material3.TextFieldDefaults.colors(
                                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                // Visible indicator so the field reads
                                // as an input, not static text.
                                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                                unfocusedIndicatorColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                cursorColor = MaterialTheme.colorScheme.primary,
                            ),
                            textStyle = MaterialTheme.typography.bodyMedium,
                        )
                        // Match counter sits flush against the close
                        // button so the right edge feels like an
                        // information-dense ribbon, not random chrome.
                        if (searchQuery.isNotEmpty()) {
                            Text(
                                "$searchMatchCount",
                                color = MaterialTheme.colorScheme.tertiary,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(end = 2.dp, start = 2.dp),
                            )
                        }
                    }
                }
            }
        },
        navigationIcon = {
            // Back arrow + server name in the LEADING slot (the model is the
            // centered title now). Server name is clickable → host info, capped
            // + ellipsized so it can't shove into the centered model.
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
                }
                if (serverName.isNotBlank() && !searchActive) {
                    Text(
                        serverName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize * 0.85f,
                        color = ai.eight24family.conch.ui.theme.serverNameColor(
                            serverId = serverId,
                            serverName = serverName,
                            fallback = MaterialTheme.colorScheme.primary,
                        ),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            // Cap a touch tighter so name + dot still clear the
                            // centered model picker on narrow screens.
                            .widthIn(max = 88.dp)
                            .clickable { onOpenServerStats() },
                    )
                    // Online/offline at a glance, right after the server
                    // name. Same ●/◐/○ ConnectionDot the server list + agent
                    // picker use.
                    ConnectionDot(connected = connected)
                }
            }
        },
        actions = {
            // Approval shield — a DIRECT top-bar icon showing the current
            // mode's shield; tap → the SAFE/AUTO/YOLO picker with details.
            // Approval is a per-agent, mid-chat concern, so it lives out here
            // (not buried in the ⋮ overflow). Hidden while search is open and
            // when the user turned it off in Settings.
            AnimatedVisibility(
                visible = !searchActive && showApprovalIcon,
                enter = fadeIn(tween(160)) + expandHorizontally(tween(180, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(120)) + shrinkHorizontally(tween(160, easing = FastOutSlowInEasing)),
            ) {
                ApprovalShield(
                    mode = approvalMode,
                    menuOpen = approvalMenuOpen,
                    onToggle = onToggleApprovalMenu,
                    onPick = onSelectApproval,
                    planSupported = ai.eight24family.conch.agent.spec
                        .AgentSpecRegistry[agent].supportsPlanMode,
                    approvalsCaveat = ai.eight24family.conch.agent.spec
                        .AgentSpecRegistry[agent].approvalsCaveat,
                    // Collected, not read once: the audit lands asynchronously
                    // after an install/update, and the sheet must show the
                    // fresh verdict the next time it opens rather than whatever
                    // was true when this bar was first composed.
                    flagAudit = ai.eight24family.conch.agent.CliFlagAuditStore.reports
                        .collectAsState().value[
                        "$serverId:${agent.name}",
                    ],
                    onAskAgentToRelax = {
                        onAskAgentToRelaxApprovals()
                        onToggleApprovalMenu()
                    },
                    showAnchorIcon = true,
                )
            }
            // Three-state toggle in the same slot, so muscle memory and
            // the animation choreography stay coherent: 0 ▸ search off
            // → loupe — tap opens 1 ▸ search on, empty → loupe — tap
            // closes (no destructive op available) 2 ▸ search on, has
            // text → × — tap CLEARS the query contentKey collapses 0/1
            // to the same animation-key so crossing the loupe↔loupe
            // boundary is silent; only the loupe↔× transition gets the
            // scale+fade choreography.
            val haptic = LocalSshAiHaptics.current
            val iconVariant = when {
                !searchActive -> 0
                searchQuery.isEmpty() -> 1
                else -> 2
            }
            AnimatedContent(
                targetState = iconVariant,
                transitionSpec = {
                    (fadeIn(tween(140)) + scaleIn(tween(140), initialScale = 0.7f)) togetherWith
                        (fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.7f))
                },
                contentKey = { it == 2 },
                label = "search-icon-toggle",
            ) { variant ->
                val isCross = variant == 2
                IconButton(onClick = {
                    haptic.perform(if (isCross) SshAiHaptic.Tick else SshAiHaptic.Tap)
                    when (variant) {
                        0 -> onOpenSearch()
                        1 -> onCloseSearch()
                        2 -> {
                            // Clear field but keep the search bar open —
                            // re-request focus so the IME stays up and
                            // the user can immediately type a new query.
                            // Cancel any pending close that the focus
                            // bounce might have armed.
                            pendingClose.job?.cancel()
                            pendingClose.job = null
                            onSearchQueryChange("")
                            SilentlyTry.fired("SshAi-ChatTopBar", "request search focus (clear)") { searchFieldFocus.requestFocus() }
                        }
                    }
                }) {
                    Icon(
                        if (isCross) androidx.compose.material.icons.Icons.Filled.Close
                        else androidx.compose.material.icons.Icons.Filled.Search,
                        contentDescription = when (variant) {
                            0 -> "search in chat"
                            1 -> "close search"
                            else -> "clear search"
                        },
                    )
                }
            }
            // AnimatedVisibility(visible=…, exit=fadeOut+shrinkH) gives
            // us a clean retraction instead of a jarring instant blink.
            AnimatedVisibility(
                visible = !searchActive,
                enter = fadeIn(tween(160)) +
                    expandHorizontally(tween(180, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(120)) +
                    shrinkHorizontally(tween(160, easing = FastOutSlowInEasing)),
            ) {
            // Single overflow: low-frequency actions all live here so the
            // header stays visually quiet (just back / title / model / ⋮).
            //   • host info   — server name, address, fingerprint
            //   • approval    — current mode, with submenu for SAFE/AUTO/YOLO
            //   • memory      — CLAUDE.md / AGENTS.md / GEMINI.md
            //   • subagents   — Claude-only browser
            //   • custom /commands — discovered from ~/.claude/commands
            Box {
                IconButton(onClick = onToggleCommandMenu) {
                    Icon(Icons.Default.MoreVert, contentDescription = "menu")
                }
                DropdownMenu(
                    expanded = commandMenuOpen,
                    onDismissRequest = onToggleCommandMenu
                ) {
                    // "host" moved OUT of this menu — the server name in the
                    // top bar title is now the tap target for host info.
                    if (showMemoryIcon) {
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.Notes,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            text = { Text("memory", style = MaterialTheme.typography.bodyMedium) },
                            onClick = {
                                onToggleCommandMenu()
                                onOpenMemory()
                            }
                        )
                    }
                    if (showSubagentsIcon) {
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.SmartToy,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            text = { Text("subagents", style = MaterialTheme.typography.bodyMedium) },
                            onClick = {
                                onToggleCommandMenu()
                                onOpenAgents()
                            }
                        )
                    }
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                Icons.Filled.RestartAlt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        text = { Text("restart CLI", style = MaterialTheme.typography.bodyMedium) },
                        onClick = {
                            onToggleCommandMenu()
                            onRestartCli()
                        }
                    )
                    if (showCompactItem) {
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Compress,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            text = { Text("compact conversation", style = MaterialTheme.typography.bodyMedium) },
                            onClick = {
                                onToggleCommandMenu()
                                onCompact()
                            }
                        )
                    }
                    if (showForkItem) {
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.CallSplit,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            text = { Text("fork into a new chat", style = MaterialTheme.typography.bodyMedium) },
                            onClick = {
                                onToggleCommandMenu()
                                onForkChat()
                            }
                        )
                    }
                    if (showRenameItem) {
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Edit,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            text = { Text("rename session", style = MaterialTheme.typography.bodyMedium) },
                            onClick = {
                                onToggleCommandMenu()
                                onRenameSession()
                            }
                        )
                    }
                    if (customCommands.isNotEmpty()) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                        Text(
                            "// custom (~/.claude/commands)",
                            color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 2.dp)
                        )
                        customCommands.forEach { cmd ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            "/${cmd.name}",
                                            color = MaterialTheme.colorScheme.tertiary,
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            cmd.description,
                                            color = MaterialTheme.colorScheme.outline,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                },
                                onClick = {
                                    onToggleCommandMenu()
                                    onPickCommand(cmd)
                                }
                            )
                        }
                    }
                    // Settings live in the bottom nav now — removed here.
                }
            }
            }
        }
    )
}

// ───────────────────────── Effort slider ─────────────────────────

/**
 * Claude-style `/effort` slider for the model picker's stage 2 — a
 * horizontal Faster↔Smarter track with one tick per PROBED reasoning
 * level, a draggable handle, and the active level's name + description.
 *
 * Fully data-driven: it renders whatever [levels] the spec's probe
 * reported (low/medium/high/xhigh/max/ultracode today, anything Anthropic
 * ships next with zero code changes). NO hardcoded level list lives here —
 * the previous fixed Low/Medium/High/Max submenu was exactly the
 * hardcode the user rejected.
 *
 * Selection commits on tap of a level label or release of a drag, via
 * [onPick] with the literal effort string.
 */
@Composable
private fun EffortSlider(
    modelDisplay: String,
    levels: List<ai.eight24family.conch.agent.spec.ReasoningLevel>,
    defaultEffort: String?,
    activeEffort: String?,
    panelWidth: Dp,
    onBack: () -> Unit,
    onPick: (String) -> Unit,
) {
    if (levels.isEmpty()) return
    val n = levels.size
    val activeIdx = levels.indexOfFirst { it.effort == activeEffort }
        .let { if (it >= 0) it else levels.indexOfFirst { l -> l.effort == defaultEffort }.coerceAtLeast(0) }
    // Live index while dragging so the handle tracks the finger before the
    // pick commits; resets to the real active index on (re)compose.
    var dragIdx by remember(activeEffort, n) { mutableStateOf(activeIdx) }
    val accent = MaterialTheme.colorScheme.primary
    val trackBase = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val curLevel = levels.getOrNull(dragIdx) ?: levels[activeIdx]

    Column(
        modifier = Modifier
            .width(panelWidth)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // Header: back arrow + model + current level name (accent).
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹",
                style = MaterialTheme.typography.bodyMedium,
                color = accent,
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(end = 8.dp),
            )
            Text(
                modelDisplay,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                curLevel.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = accent,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(10.dp))
        // Faster / Smarter ends.
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                "Faster",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Smarter",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.height(6.dp))
        // The track: line + tick per level + draggable handle. Tap or drag
        // anywhere snaps to the nearest level.
        //
        // FIXED width everywhere below (panelWidth − the Column's 16+16dp
        // padding), NEVER fillMaxWidth/BoxWithConstraints: DropdownMenu
        // measures its content with IntrinsicSize, where fillMaxWidth
        // resolves against an INFINITE max width — laying out at
        // constraints.maxWidth crashed with "Size(2147483647 x 44) is out
        // of range" the moment the slider opened (2026-06-10), and
        // BoxWithConstraints (SubcomposeLayout) can't answer intrinsics
        // at all.
        val density = LocalDensity.current
        val trackWidth = panelWidth - 32.dp
        val handleR = with(density) { 7.dp.toPx() }
        val trackWidthPx = with(density) { trackWidth.toPx() }
        Box(
            modifier = Modifier
                .width(trackWidth)
                .height(22.dp),
        ) {
            val widthPx = trackWidthPx
            fun idxFromX(x: Float): Int =
                if (n <= 1) 0
                else ((x - handleR) / (widthPx - 2 * handleR) * (n - 1))
                    .roundToInt().coerceIn(0, n - 1)
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(n, widthPx) {
                        detectTapGestures { off ->
                            val idx = idxFromX(off.x)
                            dragIdx = idx
                            onPick(levels[idx].effort)
                        }
                    }
                    .pointerInput(n, widthPx) {
                        detectHorizontalDragGestures(
                            onDragEnd = { onPick(levels[dragIdx].effort) },
                        ) { change, _ ->
                            dragIdx = idxFromX(change.position.x)
                        }
                    },
            ) {
                val cy = size.height / 2f
                val x0 = handleR
                val x1 = size.width - handleR
                val span = (x1 - x0).coerceAtLeast(1f)
                fun cx(i: Int): Float = if (n <= 1) x0 else x0 + span * i / (n - 1)
                val handleX = cx(dragIdx)
                // Base track.
                drawLine(trackBase, Offset(x0, cy), Offset(x1, cy), strokeWidth = 3f)
                // Accent fill up to the handle.
                drawLine(accent, Offset(x0, cy), Offset(handleX, cy), strokeWidth = 3f)
                // Ticks.
                for (i in 0 until n) {
                    drawCircle(
                        color = if (i <= dragIdx) accent else trackBase,
                        radius = 3f,
                        center = Offset(cx(i), cy),
                    )
                }
                // Handle.
                drawCircle(accent, radius = handleR, center = Offset(handleX, cy))
            }
        }
        Spacer(Modifier.height(4.dp))
        // Level names centered under THEIR ticks via a custom layout —
        // equal-weight columns ellipsized long names ("Ultracode" →
        // "Ultrac…", user report). Here each label keeps its intrinsic
        // width and borrows neighbouring space; edges clamp into the panel.
        Layout(
            content = {
                levels.forEachIndexed { i, level ->
                    val isCur = i == dragIdx
                    Text(
                        level.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isCur) accent else MaterialTheme.colorScheme.outline,
                        fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.clickable {
                            dragIdx = i
                            onPick(level.effort)
                        },
                    )
                }
            },
            // Fixed width — see the track comment: intrinsic passes hand
            // out infinite max width and laying out at it crashes.
            modifier = Modifier.width(trackWidth),
        ) { measurables, _ ->
            val placeables = measurables.map { it.measure(Constraints()) }
            val w = trackWidthPx.roundToInt()
            val h = placeables.maxOf { it.height }
            layout(w, h) {
                placeables.forEachIndexed { i, p ->
                    // Same x-mapping as the track's ticks (handleR insets).
                    val cx = if (n <= 1) handleR else handleR + (w - 2 * handleR) * i / (n - 1)
                    val x = (cx - p.width / 2f).roundToInt()
                        .coerceIn(0, (w - p.width).coerceAtLeast(0))
                    p.place(x, 0)
                }
            }
        }
        // Current level's description + default marker.
        if (curLevel.description.isNotBlank() || curLevel.effort == defaultEffort) {
            Spacer(Modifier.height(8.dp))
            Text(
                buildString {
                    append(curLevel.description)
                    if (curLevel.effort == defaultEffort) {
                        if (isNotEmpty()) append(" · ")
                        append("default")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
