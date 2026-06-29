package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.semantics
import ai.eight24family.conch.ui.window.handCursor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Shield
import ai.eight24family.conch.ui.components.HostInfoSheet
import ai.eight24family.conch.ui.components.PhoneBridgeGlyph
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.eight24family.conch.agent.RemoteSession
import ai.eight24family.conch.data.prefs.AgentApprovalMode
import ai.eight24family.conch.ui.viewmodel.SessionsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    serverId: String,
    onBack: () -> Unit,
    onOpenSession: (resumeId: String, filePath: String, model: String?, reasoning: String?) -> Unit,
    onNewSession: () -> Unit,
    onOpenSubagents: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenKeychain: () -> Unit = {},
    /** Tap on a search-result row → navigate to that exact chat at the
     *  matched message and pass the search query downstream so the chat
     *  view can scroll-to-match + draw highlight spans. */
    onOpenChatFromSearch: (sessionId: String, msgId: String, ordinal: Int, query: String, charOffset: Int) -> Unit = { _, _, _, _, _ -> },
    /** Open a downloaded session file in the built-in text viewer (the
     *  "Open here" choice from the disk-icon chooser). Same callback the
     *  chat uses; AppNav routes both to TextViewerScreen. */
    onOpenTextViewer: (uri: android.net.Uri, filename: String, serverId: String, remotePath: String) -> Unit = { _, _, _, _ -> },
    /** Host-info sheet → "open terminal": route to the real shell for this host. */
    onOpenTerminal: (serverId: String, serverName: String) -> Unit = { _, _ -> },
    /**
     * Phase 2 of the foldable workstream uses this to constrain
     * SessionsScreen to the left pane of a two-pane layout. Defaulting to
     * `Modifier` preserves the old fill-the-window behavior at Compact.
     * Threaded into the root [Scaffold] below so the size constraint
     * propagates correctly through Scaffold's internal SubcomposeLayout.
     */
    modifier: Modifier = Modifier,
    vm: SessionsViewModel = viewModel()
) {
    val server by vm.server.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val phoneBridgeIds by vm.phoneBridgeIds.collectAsState()
    val phoneBridgeLive by vm.phoneBridgeLive.collectAsState()
    val sessionsListState = rememberLazyListState()
    // Keep a newly-created / bumped session in view when the user is at/near the
    // top (same rationale as the unified Home list) — otherwise it lands just
    // above the fold and they'd have to scroll up. Deep readers untouched.
    val topSessionKey = sessions.firstOrNull()?.let { it.id + "·" + it.path }
    LaunchedEffect(topSessionKey) {
        if (topSessionKey != null && sessionsListState.firstVisibleItemIndex <= 1) {
            sessionsListState.animateScrollToItem(0)
        }
    }
    val downloads by vm.downloads.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    // File-open routing (Open here / Other app / Share) for the per-row disk
    // icon — same machinery the chat uses, reused via the shared sheet.
    SessionFileOpenHandlers(vm = vm, serverId = serverId, onOpenTextViewer = onOpenTextViewer)
    val initialLoading by vm.initialLoading.collectAsState()
    val error by vm.error.collectAsState()
    val memorySheetOpen by vm.memorySheetOpen.collectAsState()
    val memoryDocs by vm.memory.collectAsState()
    val skTouch by vm.skTouchRequest.collectAsState()
    // Hard rule (mirrors AgentPicker): no live SSH for this server +
    // nothing trying to get one ⇒ pop back to the server list. Polls
    // pool.peek every 3 s while we're foregrounded so a Doze-killed
    // transport doesn't strand the user on a dead screen.
    // Re-pull the session list every time the screen comes back into the
    // foreground. Without this, returning from a chat where the user just
    // wrote a message leaves the sessions list with the old mtime → the
    // chat stays in its old position instead of jumping to the top. We use
    // ON_RESUME (not just init) so it also fires when navigating back from
    // chat, agent picker, etc.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                vm.softRefresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    androidx.compose.runtime.LaunchedEffect(serverId) {
        // First frame: if there's no live SSH for this server, EJECT immediately.
        // Sessions screen is downstream of the AgentPicker / pool.userConnect
        // gate — landing here without a connection means we got here via
        // saved-state restoration (Android cold-start, process death) not by
        // user intent. Don't show a touch dialog: the user might not even want
        // this server. Pop to the server list and let them pick.
        if (ai.eight24family.conch.di.ServiceLocator.sshConnectionPool.peek(serverId) == null) {
            android.util.Log.d("SshAi-Sessions", "no active session on entry — popping to servers")
            onBack()
            return@LaunchedEffect
        }
        // Subsequent polling: catch transport drops mid-screen.
        while (true) {
            kotlinx.coroutines.delay(3_000)
            val live = ai.eight24family.conch.di.ServiceLocator.sshConnectionPool.peek(serverId) != null
            val touchInProgress = vm.skTouchRequest.value != null
            if (!live && !touchInProgress) {
                android.util.Log.d("SshAi-Sessions", "session dropped — popping to servers")
                onBack()
                break
            }
        }
    }
    skTouch?.let { req ->
        SkInlineTouchDialog(
            transport = req.transport,
            credentialIdBase64 = req.credentialIdBase64,
            application = req.application,
            onUsbSigner = { signer -> vm.runDiscoveryWithSigner(signer) },
            onNfcSigner = { signer -> vm.runDiscoveryWithSigner(signer) },
            onCancel = { vm.cancelSkRefresh() },
            onDiscoverOnKey = {
                vm.cancelSkRefresh()
                onOpenKeychain()
            },
            onRegisterNewKey = {
                vm.cancelSkRefresh()
                onOpenKeychain()
            },
        )
    }
    val cyan = MaterialTheme.colorScheme.primary
    val busy = refreshing || initialLoading
    val busyLabel = if (initialLoading) "loading…" else "refreshing…"
    var hostSheetOpen by rememberSaveable { mutableStateOf(false) }

    val connected = server?.let {
        ai.eight24family.conch.di.ServiceLocator.sshConnectionPool.peek(it.id) != null
    } ?: false
    ai.eight24family.conch.ui.components.SearchableScaffold(
        modifier = modifier,
        scopedServerId = serverId,
        scopedAgent = vm.agent,
        onPickHit = onOpenChatFromSearch,
        title = {
            // Single-line, titleMedium font (was titleLarge) — even at
            // scale=1.5× the topbar stays one row and "<server> ·
            // <agent>" fits without ellipsising the server name in the
            // common "8240 Server" / "ethernetservers" range. Server
            // is the primary (you came here by tapping it), agent is
            // the dim breadcrumb. Refresh / prefetch indicators are
            // NOT in the topbar anymore — they live in a slim status
            // row below it so the topbar stays a fixed-height pure
            // navigation surface.
            val name = server?.name ?: "…"
            // Tap the server name (icon + name + dot) → host info. This is the
            // home for `host` now; it was removed from the ⋮ overflow menu.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { hostSheetOpen = true },
            ) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(
                        ai.eight24family.conch.agent.spec.AgentSpecRegistry[vm.agent].iconRes,
                    ),
                    contentDescription = vm.agent.cliCommand,
                    tint = androidx.compose.ui.graphics.Color.Unspecified,
                    modifier = Modifier
                        .size(16.dp)
                        .padding(end = 6.dp),
                )
                Text(
                    name,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                ai.eight24family.conch.ui.components.ConnectionDot(connected = connected)
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
            }
        },
        extraActions = {
                    // Status indicators (refresh spinner, prefetch
                    // progress) used to live here, but they made the
                    // topbar feel like a control panel rather than a
                    // navigation surface — at app-scale > 1 the title
                    // truncated to fit them. They're now in a slim
                    // status row rendered below the topbar; this slot
                    // is just navigation/overflow.
                    //
                    // The search loupe is owned by SearchableScaffold —
                    // it's appended automatically to the right of the
                    // extraActions block.
                    // Single overflow ⋮ — same pattern as ChatScreen.
                    // Low-frequency actions (host info, approval mode,
                    // memory, subagents) all live behind one icon so the
                    // header stays visually quiet.
                    var menuOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menuOpen = !menuOpen }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "menu")
                        }
                        // Terminal-styled overflow: sharp rectangle, app
                        // background, 1px accent hairline, no Material card
                        // shadow/tonal tint. Each row is a single status line
                        // — `[icon] label .......... value` — instead of the
                        // stock two-line Material list item.
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            shape = RectangleShape,
                            containerColor = MaterialTheme.colorScheme.background,
                            tonalElevation = 0.dp,
                            shadowElevation = 0.dp,
                            border = BorderStroke(1.dp, cyan.copy(alpha = 0.6f)),
                        ) {
                            // `host` → tap the top-bar server name. `approval`
                            // → the chat top-bar shield icon (approval is about
                            // what the agent does mid-chat, not the session
                            // list). This overflow is memory / subagents /
                            // settings only.
                            // ── memory
                            if (vm.agent.supportsMemory) {
                                DropdownMenuItem(
                                    leadingIcon = {
                                        Icon(
                                            Icons.AutoMirrored.Filled.Notes,
                                            contentDescription = "memory",
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    text = { Text("memory", style = MaterialTheme.typography.bodyMedium) },
                                    onClick = {
                                        menuOpen = false
                                        vm.openMemoryEditor()
                                    }
                                )
                            }
                            // ── subagents
                            if (vm.agent.supportsSubagents) {
                                DropdownMenuItem(
                                    leadingIcon = {
                                        Icon(
                                            Icons.Filled.SmartToy,
                                            contentDescription = "subagents",
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    text = { Text("subagents", style = MaterialTheme.typography.bodyMedium) },
                                    onClick = {
                                        menuOpen = false
                                        onOpenSubagents()
                                    }
                                )
                            }
                            // Settings live in the bottom nav now — removed here.
                        }
                    }
        },
        floatingActionButton = {
            Surface(
                onClick = onNewSession,
                shape = RectangleShape,
                color = MaterialTheme.colorScheme.background,
                contentColor = cyan,
                border = BorderStroke(1.dp, cyan),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Text(
                    "[ + new session ]",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
    ) { padding ->
        // Sync/refresh state was previously rendered as its own row
        // BELOW the topbar — a green ✓ ring + "sync done" label plus a
        // separate "// N sessions · pull down to refresh" line. User
        // pushed back. We now fold the sync status into the existing
        // header line, optionally prefixed with the tiny
        // PrefetchSyncBadge ring when actively syncing. Zero extra rows;
        // same information density.
        val progress by vm.prefetchProgress.collectAsState()
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { vm.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding),
            // Suppress the default overlay arrow — we render our own
            // content-pushing header below so the spinner sits ABOVE the
            // list rather than ON TOP of it.
            indicator = {}
        ) {
            LazyColumn(
                state = sessionsListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                    item {
                        // Inline status: "// 30 sessions · syncing 5/30",
                        // "// loading…", "// $error", etc. Single row,
                        // single style.
                        val statusPart: String? = when {
                            initialLoading -> "loading…"
                            refreshing -> "refreshing…"
                            progress != null && !progress!!.complete ->
                                "syncing ${progress!!.done}/${progress!!.total}"
                            error != null && sessions.isNotEmpty() -> error
                            sessions.isNotEmpty() -> "pull down to refresh"
                            else -> null
                        }
                        val countPart: String? =
                            if (sessions.isNotEmpty()) {
                                "${sessions.size} session${if (sessions.size == 1) "" else "s"}"
                            } else null
                        val parts = listOfNotNull(countPart, statusPart)
                        if (parts.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                                    .semantics {
                                        contentDescription =
                                            "swipe down to refresh sessions list"
                                    },
                            ) {
                                // Tiny ring badge inline while sync is
                                // still running — preserves the at-a-
                                // glance amber→green visual without
                                // costing a whole row.
                                progress?.takeIf { !it.complete }?.let { p ->
                                    PrefetchSyncBadge(p)
                                    androidx.compose.foundation.layout.Spacer(
                                        modifier = Modifier.size(6.dp)
                                    )
                                }
                                Text(
                                    "// ${parts.joinToString(" · ")}",
                                    color = MaterialTheme.colorScheme.outline,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    // Empty-list guidance: three distinct states cover the
                    // window between "screen mounted" and "first row paints".
                    //   1. initial load → 3 skeleton rows pulsing alpha.
                    //   2. loaded but errored → "// $error" + Retry.
                    //   3. loaded and empty → friendly cyberpunk-flavour
                    //      hint pointing at the `[ + new session ]` FAB.
                    if (sessions.isEmpty() && initialLoading) {
                        items(3) {
                            SessionSkeletonRow()
                        }
                    } else if (sessions.isEmpty() && !initialLoading && error != null) {
                        item {
                            EmptyErrorState(
                                error = error ?: "",
                                onRetry = { vm.refresh() },
                            )
                        }
                    } else if (sessions.isEmpty() && !initialLoading && error == null) {
                        item {
                            EmptySessionsHint()
                        }
                    }
                    items(sessions, key = { it.id + "·" + it.path }) { s ->
                        SwipeToRevealDelete(onDelete = { vm.deleteSession(s) }) {
                            SessionRow(
                                session = s,
                                onClick = { onOpenSession(s.id, s.path, s.model, s.reasoning) },
                                downloadState = downloads[s.id],
                                onDownload = { vm.downloadSession(s) },
                                phonePresence = ai.eight24family.conch.diagnostics
                                    .bridgePresenceFromLiveState(
                                        phoneBridgeIds.contains(s.id), phoneBridgeLive),
                            )
                        }
                    }
            }
        }
    }

    if (memorySheetOpen) {
        MemorySheet(
            docs = memoryDocs,
            onSave = { scope, content -> vm.saveMemory(scope, content) },
            onRefresh = { vm.refreshMemory() },
            onDismiss = { vm.closeMemoryEditor() }
        )
    }
    if (hostSheetOpen) {
        server?.let {
            HostInfoSheet(
                server = it,
                onDismiss = { hostSheetOpen = false },
                onOpenTerminal = { onOpenTerminal(it.id, it.name) },
            )
        } ?: run { hostSheetOpen = false }
    }
}

/**
 * Swipe a row left to reveal a red Delete button on the right (mail-client
 * style); tap it to delete. Built on a plain horizontal [draggable] + an
 * [Animatable] offset — stable across Compose versions, unlike the churning
 * AnchoredDraggable API. The foreground sits on the screen background so the
 * button stays fully hidden until the row is dragged open. Drag is clamped to
 * the left half only (0 … −revealWidth); a flick or past-halfway release snaps
 * it open, otherwise it springs shut.
 */
@Composable
internal fun SwipeToRevealDelete(
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val revealDp = 96.dp
    val revealPx = with(density) { revealDp.toPx() }
    val offsetX = remember { Animatable(0f) }

    Box(modifier = Modifier.fillMaxWidth()) {
        // Behind: the delete affordance, pinned to the right edge, full row height.
        Row(
            modifier = Modifier.matchParentSize(),
            horizontalArrangement = Arrangement.End,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(revealDp)
                    .background(MaterialTheme.colorScheme.error)
                    .clickable {
                        onDelete()
                        scope.launch { offsetX.snapTo(0f) }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete session",
                        tint = MaterialTheme.colorScheme.onError,
                    )
                    Text(
                        "Delete",
                        color = MaterialTheme.colorScheme.onError,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        // Foreground: the row itself — opaque (covers the button when closed),
        // offset by the drag, draggable horizontally to the left only.
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch { offsetX.snapTo((offsetX.value + delta).coerceIn(-revealPx, 0f)) }
                    },
                    onDragStopped = { velocity ->
                        scope.launch {
                            val open = offsetX.value < -revealPx / 2f || velocity < -800f
                            offsetX.animateTo(if (open) -revealPx else 0f)
                        }
                    },
                ),
        ) {
            content()
        }
    }
}

@Composable
internal fun SessionRow(
    session: RemoteSession,
    onClick: () -> Unit,
    downloadState: ai.eight24family.conch.ui.viewmodel.ChatViewModel.DownloadStatus? = null,
    onDownload: () -> Unit = {},
    /** Phone glyph state for this row (NONE/IDLE/LIVE): colored when the bridge
     *  is live, dim when the session was wired but is offline now, absent when
     *  never wired. Same tri-state the home list and chat title use. */
    phonePresence: ai.eight24family.conch.diagnostics.BridgePresence =
        ai.eight24family.conch.diagnostics.BridgePresence.NONE,
) {
    val cyan = MaterialTheme.colorScheme.primary
    val magenta = MaterialTheme.colorScheme.secondary
    val dim = MaterialTheme.colorScheme.outline
    val fg = MaterialTheme.colorScheme.onSurface

    // Pre-pool history: rows used to flag with a loud `● LIVE` marker
    // and an inline `[ end ]` button when the row's session had an
    // open AgentSession. Now that SSH is shared at the server level
    // via SshConnectionPool, holding a chat open no longer pins
    // resources per row — it just keeps the (single) per-server
    // transport ref-counted up. The marker became visual noise and
    // the End button overlapped what Settings → Active sessions
    // already does. Both removed; engineering teardown still lives
    // on the Settings screen.
    val rowMod = Modifier
        .fillMaxWidth()
        // Phase 7 DeX polish: hand cursor over session rows on mouse
        // hover. No-op on touch.
        .handCursor()
        .clickable(onClick = onClick)
        // A11Y-1: collapse preview + timestamp + phone/download glyphs into one
        // labelled focus stop instead of several unlabelled fragments.
        .semantics(mergeDescendants = true) {}
        .padding(vertical = 8.dp, horizontal = 4.dp)

    Column(modifier = rowMod) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "❯ ",
                color = cyan,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                session.preview.ifBlank { "(empty session)" },
                color = fg,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                modifier = Modifier.weight(1f, fill = true)
            )
            // Phone wired to this session via conch-bridge — tri-state glyph
            // (colored live / dim offline / absent never), shared with the home
            // list and chat title.
            PhoneBridgeGlyph(
                phonePresence,
                modifier = Modifier.padding(end = 4.dp),
                size = 15.dp,
            )
            // Floppy/disk — download THIS session's JSONL to the phone, then
            // Open here / Other app / Share (same flow as chat file downloads).
            SessionDiskButton(state = downloadState, onClick = onDownload)
            Text("›", color = dim, style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            "  ${formatStamp(session.lastActiveAt)} · ${session.id.take(8)}" +
                (session.sizeBytes?.let { " · ${formatSize(it)}" } ?: ""),
            color = dim,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/** Human-readable file size: B / KB / MB (binary, 1 decimal ≥10 units). */
internal fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> {
        val kb = bytes / 1024.0
        if (kb >= 10) "${kb.toInt()} KB" else String.format(Locale.US, "%.1f KB", kb)
    }
    else -> {
        val mb = bytes / (1024.0 * 1024.0)
        if (mb >= 10) "${mb.toInt()} MB" else String.format(Locale.US, "%.1f MB", mb)
    }
}

internal fun formatStamp(unixSeconds: Long): String {
    if (unixSeconds <= 0) return "—"
    val now = System.currentTimeMillis() / 1000L
    val diff = now - unixSeconds
    return when {
        diff < 60 -> "just now"
        diff < 3600 -> "${diff / 60}m ago"
        diff < 86400 -> "${diff / 3600}h ago"
        diff < 86400 * 7 -> "${diff / 86400}d ago"
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(unixSeconds * 1000))
    }
}

/**
 * Compact circular sync indicator. While the per-(server, agent)
 * prefetch is running, draws an amber arc filling from 0 → 360°
 * proportional to `progress.fraction` with the live `done/total`
 * count printed inside; flips to a green ✓ on completion. Lets the
 * user see at a glance whether tapping a row will paint instantly
 * from cache or wait on a fresh JSONL fetch.
 */
@Composable
private fun PrefetchSyncBadge(progress: ai.eight24family.conch.ui.viewmodel.SessionsViewModel.PrefetchProgress) {
    val amber = MaterialTheme.colorScheme.tertiary
    val green = androidx.compose.ui.graphics.Color(0xFF4ADE80)
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val complete = progress.complete

    // Smoothly animate the arc fill so increments tick rather than jump.
    val animated by androidx.compose.animation.core.animateFloatAsState(
        targetValue = progress.fraction,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 350),
        label = "prefetch-fill",
    )

    // Tap-to-inspect: tiny popup with the exact done / total / status, so
    // a curious user can confirm "yes, all my sessions are cached" or see
    // how far the prefetch has gotten without leaving the screen.
    var infoOpen by remember { mutableStateOf(false) }
    val tapInteractionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(23.dp)
            // No ripple — the click target is a tiny progress indicator,
            // a stock ripple would visually drown the amber arc.
            .clickable(
                interactionSource = tapInteractionSource,
                indication = null,
                onClick = { infoOpen = !infoOpen },
            ),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 2.5f
            // Track ring (always visible).
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
            )
            // Filled arc — amber while running, green when done.
            drawArc(
                color = if (complete) green else amber,
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
            )
        }
        if (complete) {
            // ✓ glyph centred. Scaled to fit inside the 23dp ring.
            androidx.compose.material3.Icon(
                imageVector = androidx.compose.material.icons.Icons.Default.Check,
                contentDescription = "sync complete",
                tint = green,
                modifier = Modifier.size(13.dp),
            )
        } else {
            Text(
                text = "${progress.done}/${progress.total}",
                color = amber,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 7.5.sp),
                fontWeight = FontWeight.Bold,
            )
        }
        // Floating popup with the actual numbers — appears above the badge
        // when tapped. Dismissed by tapping outside or on the badge again.
        if (infoOpen) {
            androidx.compose.ui.window.Popup(
                alignment = Alignment.TopEnd,
                offset = androidx.compose.ui.unit.IntOffset(x = 0, y = -6),
                onDismissRequest = { infoOpen = false },
                properties = androidx.compose.ui.window.PopupProperties(focusable = true),
            ) {
                androidx.compose.material3.Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    tonalElevation = 4.dp,
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                    modifier = Modifier.padding(top = 28.dp, end = 4.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            if (complete) "Synced" else "Syncing",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (complete) green else amber,
                        )
                        Text(
                            "${progress.done} of ${progress.total} sessions cached",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (!complete) {
                            Text(
                                "${(progress.fraction * 100).toInt()}% · ${progress.total - progress.done} left",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Pulsing placeholder row painted while the initial discovery is in
 * flight and we still have zero rows to show. Matches the visual footprint
 * of [SessionRow] so the LazyColumn doesn't reflow once real data lands.
 */
@Composable
private fun SessionSkeletonRow() {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeleton-alpha",
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha),
        shape = RectangleShape,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(vertical = 4.dp),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {}
}

/**
 * Shown when the initial fetch has settled, the list is still empty, and
 * the VM has reported an error. Renders the error inline and offers a
 * one-tap retry via [SessionsViewModel.refresh].
 */
@Composable
private fun EmptyErrorState(error: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 32.dp, start = 8.dp, end = 8.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            "// $error",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        TextButton(onClick = onRetry) {
            Text(
                "[ retry ]",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Friendly "no sessions yet" state. Keeps the cyberpunk chevron prefix
 * so it doesn't read as a generic Material empty state.
 */
@Composable
private fun EmptySessionsHint() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp, start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            "❯ ",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            buildAnnotatedString {
                append("No sessions yet. Tap ")
                withStyle(
                    SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                ) {
                    append("[ + new session ]")
                }
                append(" above to start one.")
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Floppy/disk button on a session row. Mirrors the chat download disk's
 *  visual states (idle Save / Downloading spinner / Done Check / Failed). */
@Composable
private fun SessionDiskButton(
    state: ai.eight24family.conch.ui.viewmodel.ChatViewModel.DownloadStatus?,
    onClick: () -> Unit,
) {
    val cyan = MaterialTheme.colorScheme.primary
    val err = MaterialTheme.colorScheme.error
    IconButton(
        onClick = onClick,
        enabled = state !is ai.eight24family.conch.ui.viewmodel.ChatViewModel.DownloadStatus.Downloading,
        modifier = Modifier.size(36.dp),
    ) {
        when (state) {
            is ai.eight24family.conch.ui.viewmodel.ChatViewModel.DownloadStatus.Downloading -> {
                val p = state.progress
                if (p < 0f) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = cyan)
                } else {
                    CircularProgressIndicator(progress = { p.coerceIn(0f, 1f) }, modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = cyan)
                }
            }
            is ai.eight24family.conch.ui.viewmodel.ChatViewModel.DownloadStatus.Done ->
                Icon(Icons.Filled.Check, contentDescription = "saved — tap to open/share", tint = cyan, modifier = Modifier.size(18.dp))
            is ai.eight24family.conch.ui.viewmodel.ChatViewModel.DownloadStatus.Failed ->
                Icon(Icons.Filled.Save, contentDescription = "download failed — tap to retry", tint = err, modifier = Modifier.size(18.dp))
            null ->
                Icon(Icons.Filled.Save, contentDescription = "download session", tint = cyan, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Open-downloaded-session routing — copy of ChatFileOpenHandlers, typed to
 * SessionsViewModel. Collects the VM's open events (internal viewer / external
 * ACTION_VIEW / share ACTION_SEND) and hosts the shared OpenFileChooserSheet.
 */
@Composable
internal fun SessionFileOpenHandlers(
    vm: ai.eight24family.conch.ui.viewmodel.SessionsViewModel,
    serverId: String,
    onOpenTextViewer: (uri: android.net.Uri, filename: String, serverId: String, remotePath: String) -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        vm.openInViewer.collect { req -> onOpenTextViewer(req.uri, req.filename, req.serverId, req.remotePath) }
    }
    LaunchedEffect(Unit) {
        vm.openExternally.collect { req ->
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(req.uri, req.mime)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ai.eight24family.conch.util.SilentlyTry.fired("SshAi-Sessions", "open external") { ctx.startActivity(intent) }
        }
    }
    LaunchedEffect(Unit) {
        vm.shareFile.collect { req ->
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = req.mime
                putExtra(android.content.Intent.EXTRA_STREAM, req.uri)
                putExtra(android.content.Intent.EXTRA_TITLE, req.filename)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = android.content.Intent.createChooser(send, req.filename).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ai.eight24family.conch.util.SilentlyTry.fired("SshAi-Sessions", "share session") { ctx.startActivity(chooser) }
        }
    }
    var openPrompt by remember {
        mutableStateOf<ai.eight24family.conch.ui.viewmodel.ChatViewModel.OpenFilePromptRequest?>(null)
    }
    LaunchedEffect(Unit) { vm.openFilePrompt.collect { openPrompt = it } }
    openPrompt?.let { prompt ->
        OpenFileChooserSheet(
            request = prompt,
            onPick = { choice, rememberPick ->
                if (rememberPick && prompt.extension.isNotBlank()) {
                    vm.rememberOpenFileChoice(prompt.extension, choice)
                }
                when (choice) {
                    "internal" -> onOpenTextViewer(prompt.uri, prompt.filename, serverId, prompt.remotePath)
                    "external" -> {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            setDataAndType(prompt.uri, prompt.mime)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        ai.eight24family.conch.util.SilentlyTry.fired("SshAi-Sessions", "open external (prompt)") { ctx.startActivity(intent) }
                    }
                    "share" -> {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = prompt.mime
                            putExtra(android.content.Intent.EXTRA_STREAM, prompt.uri)
                            putExtra(android.content.Intent.EXTRA_TITLE, prompt.filename)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        val chooser = android.content.Intent.createChooser(send, prompt.filename).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        ai.eight24family.conch.util.SilentlyTry.fired("SshAi-Sessions", "share session (prompt)") { ctx.startActivity(chooser) }
                    }
                }
                openPrompt = null
            },
            onDismiss = { openPrompt = null },
        )
    }
}
