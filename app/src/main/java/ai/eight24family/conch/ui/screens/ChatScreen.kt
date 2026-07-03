package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.eight24family.conch.ui.keyboard.shortcuts
import ai.eight24family.conch.ui.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    serverId: String,
    onBack: () -> Unit,
    onOpenSubagents: (chatId: String?) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenKeychain: () -> Unit = {},
    /**
     * Navigate to the built-in text viewer/editor for a downloaded
     * file. `serverId` + `remotePath` are passed through to enable
     * Save-back-to-server from inside the viewer.
     */
    onOpenTextViewer: (
        uri: android.net.Uri,
        filename: String,
        serverId: String,
        remotePath: String,
    ) -> Unit = { _, _, _, _ -> },
    /** Server-stats sheet → "open terminal": route to the real shell for this host. */
    onOpenTerminal: (serverId: String, serverName: String) -> Unit = { _, _ -> },
    /**
     * Phase 2 of the foldable workstream uses this to constrain the chat
     * surface to the right pane of a two-pane layout. Default `Modifier`
     * preserves the old fill-the-window behavior at Compact width. The
     * modifier is threaded into ChatScreen's root [Scaffold] so the size
     * constraint propagates through Scaffold's internal SubcomposeLayout
     * — important because Compose's `Row { … weight(0.65f) }` expects a
     * Modifier on the direct child, not on a grandchild.
     */
    modifier: Modifier = Modifier,
    vm: ChatViewModel = viewModel()
) {
    var input by rememberSaveable { mutableStateOf("") }
    // Restore the saved input draft — leaving the chat must never throw away unsent
    // text. KEYED on the resume id, not the VM: a resumed chat's _resumeId is null
    // for the first frames, and the draft is stored under the resume id — so
    // loading on first composition read the WRONG key and missed the draft. Only
    // when the field is empty, so a typed / rotation-preserved input is never
    // clobbered.
    val draftRestoreKey by vm.resumeId.collectAsState()
    androidx.compose.runtime.LaunchedEffect(draftRestoreKey) {
        if (input.isBlank()) {
            val d = vm.loadInputDraft()
            if (d.isNotBlank()) input = d
        }
    }
    val messages by vm.messages.collectAsState()
    val state by vm.state.collectAsState()
    val reconnecting by vm.reconnecting.collectAsState()
    val currentAgent by vm.currentAgent.collectAsState()

    // Phase 9: when the Activity is in PiP mode (system entered via
    // onUserLeaveHint while a chat was active), short-circuit to the
    // compact ChatPipView. Everything else in this function — Scaffold,
    // input bar, dialogs, lifecycle watchdogs — is pointless in a
    // 240x180 floating window. The PiP layout only shows the assistant
    // stream + a working dot. Tap the window → system restores
    // full-screen Activity → this branch flips back to the full UI.
    // Publish THIS chat as the foreground conversation so the PiP overlay (drawn
    // by MainActivity.Root, which survives the PiP short-circuit below) renders
    // THIS chat + reading position — not a recency-guessed session whose history
    // diverges from what's on screen ("PiP shows the wrong message").
    androidx.compose.runtime.DisposableEffect(vm) {
        ai.eight24family.conch.ui.window.PipForegroundChat.current.value = vm
        onDispose {
            if (ai.eight24family.conch.ui.window.PipForegroundChat.current.value === vm) {
                ai.eight24family.conch.ui.window.PipForegroundChat.current.value = null
            }
        }
    }
    val adaptiveInfo = ai.eight24family.conch.ui.window.LocalAppWindowAdaptive.current
    if (adaptiveInfo.isInPip) {
        // The compact PiP view is drawn by Root as an overlay (so it survives this
        // short-circuit). Here we just stop composing the full chat (Scaffold /
        // input / dialogs / watchdogs) into a 240x180 window. The EXACT scroll
        // position is restored on expand from the VM-persisted reading anchor
        // (see rememberChatScrollController's VM-seeding), not lost to the first
        // message.
        android.util.Log.d(
            "SshAi-PiP",
            "ChatScreen short-circuit (inPip) anchor=${vm.readingAnchorMsgId.value} off=${vm.readingAnchorOffset.value}",
        )
        return
    }
    val attachments by vm.attachments.collectAsState()
    val selectedModel by vm.selectedModel.collectAsState()

    // Phone bridge (paperclip → "Connect phone to server"): if Shizuku isn't
    // set up, send the user to Settings to do it; if it is, confirm before we
    // write the conch-bridge helper to THIS server, then the VM prompts the
    // agent. We never touch a server uninvited.
    val bridgeStep by vm.bridgeStep.collectAsState()
    val bridgeLog by vm.bridgeLog.collectAsState()
    val bridgeUpdateNotice by vm.bridgeUpdateNotice.collectAsState()
    val bridgeHostWarning by vm.bridgeHostWarning.collectAsState()
    androidx.compose.runtime.LaunchedEffect(bridgeStep) {
        if (bridgeStep == ChatViewModel.BridgeStep.NeedSettings) {
            vm.dismissBridge()
            // Land directly in the Phone-bridge section (the "open Shizuku" button),
            // not the Settings index — the user came here to enable the bridge.
            ai.eight24family.conch.ui.navigation.SettingsDeepLink.pendingCategory = "bridge"
            onOpenSettings()
        }
    }
    if (bridgeStep != ChatViewModel.BridgeStep.None &&
        bridgeStep != ChatViewModel.BridgeStep.NeedSettings
    ) {
        val step = bridgeStep
        val installing = step == ChatViewModel.BridgeStep.Installing
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!installing) vm.dismissBridge() },
            title = {
                androidx.compose.material3.Text(
                    when (step) {
                        ChatViewModel.BridgeStep.Confirm -> "Connect phone to this server?"
                        ChatViewModel.BridgeStep.Installing -> "Installing…"
                        ChatViewModel.BridgeStep.Done -> "Phone connected ✓"
                        else -> "Install failed"
                    },
                )
            },
            text = {
                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    androidx.compose.material3.Text(
                        when (step) {
                            ChatViewModel.BridgeStep.Confirm ->
                                "Conch installs a small helper (conch-bridge) on this server over your SSH " +
                                    "connection. After that the agent can run shell commands and read logs on " +
                                    "THIS phone via Shizuku. Nothing leaves your device except to your own server."
                            ChatViewModel.BridgeStep.Installing ->
                                "Writing conch-bridge to the server over SSH…"
                            ChatViewModel.BridgeStep.Done ->
                                "$bridgeLog\n\n✓ Installed successfully — the agent has been told how to use it."
                            else ->
                                "Couldn't install the bridge:\n\n$bridgeLog"
                        },
                    )
                    if (step == ChatViewModel.BridgeStep.Confirm) {
                        bridgeHostWarning?.let { w ->
                            androidx.compose.material3.Text(
                                "⚠ $w",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                when (step) {
                    ChatViewModel.BridgeStep.Confirm -> androidx.compose.material3.TextButton(
                        onClick = { vm.confirmInstallBridge() },
                    ) { androidx.compose.material3.Text("Install") }
                    ChatViewModel.BridgeStep.Done -> androidx.compose.material3.TextButton(
                        onClick = { vm.dismissBridge() },
                    ) { androidx.compose.material3.Text("Done") }
                    ChatViewModel.BridgeStep.Failed -> androidx.compose.material3.TextButton(
                        onClick = { vm.confirmInstallBridge() },
                    ) { androidx.compose.material3.Text("Retry") }
                    else -> {}
                }
            },
            dismissButton = {
                if (step == ChatViewModel.BridgeStep.Confirm || step == ChatViewModel.BridgeStep.Failed) {
                    androidx.compose.material3.TextButton(onClick = { vm.dismissBridge() }) {
                        androidx.compose.material3.Text("Cancel")
                    }
                }
            },
        )
    }

    // Chats opened from a global-search hit get to STAY even when not
    // connected: HistoryCache renders the conversation from local
    // JSONL, the user came here to read the match they found. Eject
    // logic is suppressed for that flow; the connect chip handles
    // re-arm. For "normal" chats (session-row tap, deep link, cold
    // start) the eject still fires — landing without SSH means the
    // user navigated by accident and we send them back to pick a
    // server explicitly.
    val cameFromSearch = vm.initialSearchQuery != null
    ChatEjectWatchdog(
        serverId = serverId,
        cameFromSearch = cameFromSearch,
        vm = vm,
        onBack = onBack,
    )
    // "Remote is working" — answered by the server itself, not a
    // wall-clock guess. The tail-poll runs `lsof` on the session JSONL
    // every poll tick; if any process still has it open for writing,
    // the agent's turn isn't finished — even if it's been silent for
    // 30 minutes deep in thought. The instant the agent process exits
    // (clean Result OR the user Ctrl+C'd it on the PC) the file's
    // writer count drops to zero and this flips off.
    val remoteWorking by vm.remoteFileOpen.collectAsState()

    // ── Streaming haptics (user-requested) ── While a turn runs: a NEW tool/action
    // row → finest Tick; a NEW assistant text row → stronger Tap; the turn
    // finishing → Confirm (double tap-tap). Seeded on the FIRST non-empty history
    // so opening a chat never buzzes through existing messages; only rows added
    // during an active turn buzz.
    val haptic = ai.eight24family.conch.ui.haptic.LocalSshAiHaptics.current
    val seenHaptic = androidx.compose.runtime.remember(vm) { HashSet<String>() }
    val hapticSeeded = androidx.compose.runtime.remember(vm) { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(messages) {
        if (!hapticSeeded.value) {
            if (messages.isNotEmpty()) {
                messages.forEach { seenHaptic.add(it.id) }
                hapticSeeded.value = true
            }
            return@LaunchedEffect
        }
        val working = state is ai.eight24family.conch.agent.SessionState.Working || remoteWorking
        for (m in messages.asReversed()) {
            if (!seenHaptic.add(m.id)) break // hit an already-seen row → older ones below
            if (!working) continue
            when (m) {
                is ai.eight24family.conch.agent.AgentMessage.ToolUse,
                is ai.eight24family.conch.agent.AgentMessage.ToolResult ->
                    haptic.perform(ai.eight24family.conch.ui.haptic.SshAiHaptic.Tick)
                is ai.eight24family.conch.agent.AgentMessage.AssistantText ->
                    haptic.perform(ai.eight24family.conch.ui.haptic.SshAiHaptic.Tap)
                else -> {}
            }
        }
    }
    val prevWorkingHaptic = androidx.compose.runtime.remember(vm) { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(state, remoteWorking) {
        val w = state is ai.eight24family.conch.agent.SessionState.Working || remoteWorking
        if (prevWorkingHaptic.value && !w) haptic.perform(ai.eight24family.conch.ui.haptic.SshAiHaptic.Confirm)
        prevWorkingHaptic.value = w
    }

    // If a buffered send timed out (the session never reached Running within
    // the buffer window), the VM emits the original text back here. Drop it
    // into the input box so the user can retry / edit / give up — never
    // silently lost.
    LaunchedEffect(Unit) {
        vm.returnedText.collect { text ->
            input = if (input.isBlank()) text else "$text\n$input"
        }
    }
    // Open-downloaded-file routing. Three events the VM can emit
    // after the disk-icon click: open-internal (navigate to our
    // viewer), open-external (fire ACTION_VIEW), or prompt (show
    // a chooser bottom sheet — user picks + optionally remembers).
    ChatFileOpenHandlers(
        vm = vm,
        serverId = serverId,
        onOpenTextViewer = onOpenTextViewer,
    )
    // Full-screen image viewer/annotator. Opened by tapping an inline image;
    // reads the already-decoded bitmap from inlineImages so there's no re-fetch.
    val fullScreenImagePath by vm.fullScreenImage.collectAsState()
    val inlineImagesState by vm.inlineImages.collectAsState()
    androidx.compose.runtime.LaunchedEffect(fullScreenImagePath) {
        fullScreenImagePath?.let { vm.loadInlineImage(it) }
    }
    fullScreenImagePath?.let { p ->
        // Show only once decoded — if tapped before the bitmap is ready, the
        // overlay opens automatically the moment decode completes (no flash).
        (inlineImagesState[p] as? ai.eight24family.conch.ui.viewmodel.ChatViewModelDownloads.InlineImage.Ready)?.let { img ->
            ImageAnnotatorOverlay(
                image = img.bitmap,
                filename = p.substringAfterLast('/'),
                onClose = { vm.closeImageViewer() },
            )
        }
    }
    var modelMenuOpen by rememberSaveable { mutableStateOf(false) }
    var commandMenuOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf<String?>(null) }
    var paletteOpen by rememberSaveable { mutableStateOf(false) }
    var statsSheetOpen by rememberSaveable { mutableStateOf(false) }
    var usageExpanded by rememberSaveable { mutableStateOf(false) }
    var approvalMenuOpen by rememberSaveable { mutableStateOf(false) }

    // Search-opened highlight — the query the user typed before
    // tapping the hit. Drives [LocalSearchHighlight] which the
    // matched message's renderer reads to paint the background.
    var searchHighlight by rememberSaveable { mutableStateOf(vm.initialSearchQuery) }
    // Message id to scroll to after an in-chat search hit is tapped (set on tap,
    // consumed by a LaunchedEffect once the chat list is back on screen).
    var pendingHitMsgId by rememberSaveable { mutableStateOf<String?>(null) }

    // All autoscroll/anchor state + effects (LazyListState,
    // wasAtBottomSnapshot, first-scroll pipeline, user-send trigger,
    // streaming-follow, IME handler). See ChatScreenAutoscroll.kt for
    // the per-effect invariants — DO NOT consolidate the split
    // LaunchedEffects there.
    val scrollCtl = rememberChatScrollController(
        messages = messages,
        vm = vm,
        cameFromSearch = cameFromSearch,
        // Pinned working-status row toggles with this → re-pin to bottom.
        working = state is ai.eight24family.conch.agent.SessionState.Working || remoteWorking,
    )
    val lazyListState = scrollCtl.lazyListState
    val anchorApplied = scrollCtl.anchorApplied
    val targetOrd = scrollCtl.targetOrd
    val matchCharOffset = scrollCtl.matchCharOffset
    val matchAnchor = scrollCtl.matchAnchor

    // Jump to a tapped in-chat search hit: after search closes and the chat list
    // is back, scroll to the hit's message (it used to land at the top, user
    // 2026-06-14). Retries briefly in case `messages` is still settling.
    LaunchedEffect(pendingHitMsgId, searchQuery) {
        val target = pendingHitMsgId ?: return@LaunchedEffect
        if (!searchQuery.isNullOrEmpty()) return@LaunchedEffect  // still in search view
        repeat(20) {
            val idx = messages.indexOfFirst { it.id == target }
            if (idx >= 0) {
                lazyListState.scrollToItem(idx)
                pendingHitMsgId = null
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(30)
        }
        pendingHitMsgId = null
    }

    ChatTailPollLifecycle(vm = vm)

    Scaffold(
        modifier = modifier.shortcuts(
            buildChatShortcuts(
                vm = vm,
                paletteOpen = { paletteOpen },
                setPaletteOpen = { paletteOpen = it },
                commandMenuOpen = { commandMenuOpen },
                setCommandMenuOpen = { commandMenuOpen = it },
                modelMenuOpen = { modelMenuOpen },
                setModelMenuOpen = { modelMenuOpen = it },
            )
        ),
        containerColor = MaterialTheme.colorScheme.background,
        // Canonical Android-developers pattern for Scaffold + bottomBar
        // (with TextField) + edge-to-edge + IME (targetSdk 35):
        //
        //   1. Strip the bottom inset from contentWindowInsets so the
        //      content slot doesn't get its own nav-bar / IME padding —
        //      that would double up with the bottomBar's imePadding and
        //      leave the chat covered. WindowInsets(0) gives the content
        //      no insets at all; the topBar's TopAppBar handles status-
        //      bar padding internally, and the bottomBar adds its own
        //      ime/navBar padding (next param).
        //   2. bottomBar's wrapper uses imePadding() + navigationBars-
        //      Padding() to push the prompt above the IME (when open)
        //      and above the nav bar (when closed).
        //
        // Source: developer.android.com/develop/ui/compose/system/insets-ui
        contentWindowInsets = WindowInsets(0),
        topBar = {
            ChatTopBarHost(
                vm = vm,
                messages = messages,
                state = state,
                remoteWorking = remoteWorking,
                modelMenuOpen = modelMenuOpen,
                onToggleModelMenu = {
                    val opening = !modelMenuOpen
                    modelMenuOpen = opening
                    // Tap-to-open → live re-probe of availability (models can
                    // be suspended mid-session; freshness gate bypassed). The
                    // cached list shows instantly and refreshes in place.
                    if (opening) vm.onModelPickerOpened()
                },
                onCloseModelMenu = { modelMenuOpen = false },
                commandMenuOpen = commandMenuOpen,
                onToggleCommandMenu = { commandMenuOpen = !commandMenuOpen },
                onCloseCommandMenu = { commandMenuOpen = false },
                approvalMenuOpen = approvalMenuOpen,
                onToggleApprovalMenu = { approvalMenuOpen = !approvalMenuOpen },
                onCloseApprovalMenu = { approvalMenuOpen = false },
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                onOpenStatsSheet = { statsSheetOpen = true },
                onOpenSubagents = onOpenSubagents,
                onOpenSettings = onOpenSettings,
                onBack = onBack,
            )
        },
    ) { padding ->
            // Put EVERYTHING (status line, message list, autocomplete,
            // prompt) into the content slot as one column, then wrap
            // the whole column in imePadding + navigationBarsPadding.
            // With imePadding on the outer Column, the whole tree
            // shifts up by IME height when the keyboard opens.
            //
            // Trade-off accepted: the topmost messages may scroll
            // partially behind the topBar when the keyboard is up.
            // The previous bottomBar-slot layout (now removed) had
            // the right separation of concerns but didn't visually
            // lift the chat as the user wanted.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .navigationBarsPadding()
            ) {
                // No standalone search bar here anymore — the input has
                // been promoted INTO the topbar itself, where it unfolds
                // from the loupe icon over the title. See
                // TerminalTopBar's title slot for the AnimatedVisibility
                // overlay that does the work. Suppress the
                // SessionState.Failed / Closed status line entirely when
                // the chat was opened in offline read-only mode
                // (search-opened, no pool yet). In that mode the agent
                // session can't possibly start — it doesn't have a
                // signer — and reporting "── ERR · security-key signer
                // not provided —" is exactly the noise the user
                // flagged:. The connect chip below already conveys
                // offline state.
                val hideErrorStatus = cameFromSearch &&
                    ai.eight24family.conch.di.ServiceLocator.sshConnectionPool.peek(serverId) == null
                if (!hideErrorStatus) {
                    StatusLine(state, reconnecting)
                }

                // Bridge already installed but older than what this app ships —
                // a tiny, dismissible nudge pointing at the Server-settings
                // updater. We never force-update from a chat.
                bridgeUpdateNotice?.let { notice ->
                    BridgeUpdateBanner(notice = notice, onDismiss = { vm.dismissBridgeUpdateNotice() })
                }

                // Sticky-above-scroll: connect chip + search-match
                // banner live OUTSIDE the verticalScroll Column below.
                // Above the scroll area their height changes only
                // reflow the OUTER chat layout once each, never the
                // messages themselves.
                if (searchQuery.isNullOrEmpty() && cameFromSearch) {
                    val connStateOuter by vm.searchOpenConnState.collectAsState()
                    SearchOpenConnChip(
                        state = connStateOuter,
                        onTap = { vm.beginSearchOpenedConnect() },
                    )
                }

                Box(modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                ) {
                    val activeQuery = searchQuery
                    if (!activeQuery.isNullOrEmpty()) {
                        val inChatHits = buildInChatHits(messages, activeQuery)
                        InChatHitsList(
                            hits = inChatHits,
                            // Remember which message to jump to, THEN close search —
                            // a LaunchedEffect scrolls there once the chat list is back
                            // (used to just close + land at the top, user 2026-06-14).
                            onPickHit = { hit -> pendingHitMsgId = hit.msgId; searchQuery = null },
                        )
                    } else {
                        // Highlight target = the actual message at
                        // targetOrd (resolved via ord-first, mid as
                        // verification). For Codex/Gemini sessions
                        // mid is a random UUID that differs from
                        // the indexed value, so we MUST key the
                        // highlight off the resolved message's
                        // current id — not the URL `mid` arg.
                        val highlightSpec: SearchHighlightSpec? = run {
                            val q = searchHighlight
                            if (q.isNullOrBlank() || targetOrd < 0) return@run null
                            val targetMsg = messages.getOrNull(targetOrd)
                                ?: return@run null
                            SearchHighlightSpec(
                                query = q,
                                targetMsgId = targetMsg.id,
                                targetCharOffset = matchCharOffset,
                            )
                        }
                        ChatMessageList(
                            messages = messages,
                            lazyListState = lazyListState,
                            anchorApplied = anchorApplied,
                            highlightSpec = highlightSpec,
                            matchAnchor = matchAnchor,
                            state = state,
                            remoteWorking = remoteWorking,
                            vm = vm,
                        )
                    }
                    ScrollToBottomButton(
                        lazyListState = lazyListState,
                        messages = messages,
                        modifier = Modifier.align(Alignment.BottomEnd),
                    )
                }

                // Lift the chat when the usage bar expands: pin the list to the
                // bottom while the panel grows (same trick as keyboard-open).
                // Without it the panel just hides the last messages instead of
                // raising them —.
                LaunchedEffect(usageExpanded) {
                    if (usageExpanded && messages.isNotEmpty()) {
                        // Pin to bottom ONLY if the user was truly at the end —
                        // PIXEL-genuine (canScrollForward=false), not "the last
                        // item intersects the viewport": one CLI reply = one
                        // multi-screen LazyColumn item, so the old index check
                        // read "at bottom" while the user was parked mid-message
                        // and opening the panel yanked them down (2026-06-10).
                        // Unmeasured list (empty visibleItemsInfo) = DON'T move
                        // the user.
                        val atBottom = lazyListState.layoutInfo.visibleItemsInfo.isNotEmpty() &&
                            !lazyListState.canScrollForward
                        if (atBottom) repeat(30) {
                            lazyListState.scrollToBottom(messages.size)
                            kotlinx.coroutines.delay(16)
                        }
                    }
                }

                // Working-status row PINNED here — directly above the prompt /
                // usage bar — so it always holds its place instead of scrolling
                // with the chat.
                PinnedWorkingStatus(vm = vm, state = state, remoteWorking = remoteWorking)

                // PromptBar lives at the bottom of the content column
                // (not in Scaffold.bottomBar) so it rises with the
                // whole chat block via the outer Column's imePadding.
                ChatPromptHost(
                    vm = vm,
                    input = input,
                    onInputChange = { input = it; vm.saveInputDraft(it) },
                    onSlashAcPick = { cmd ->
                        if (cmd.acceptsArgs) {
                            input = "/${cmd.name} "
                        } else {
                            input = ""
                            vm.clearInputDraft()
                            vm.dispatchSlash(cmd)
                        }
                    },
                    onSend = {
                        val cmd = input.trim()
                        if (cmd.isNotEmpty() || attachments.isNotEmpty()) {
                            vm.send(cmd); input = ""; vm.clearInputDraft()
                        }
                    },
                    serverId = serverId,
                    cameFromSearch = cameFromSearch,
                    currentAgent = currentAgent,
                    state = state,
                    remoteWorking = remoteWorking,
                    usageExpanded = usageExpanded,
                    onUsageExpandedChange = { usageExpanded = it },
                )
            }
    }

    ChatModalsHost(
        vm = vm,
        onBack = onBack,
        onOpenKeychain = onOpenKeychain,
        paletteOpen = paletteOpen,
        onPaletteDismiss = { paletteOpen = false },
        onPickSlashCommand = { cmd -> input = "/${cmd.name} " },
        statsSheetOpen = statsSheetOpen,
        onStatsSheetDismiss = { statsSheetOpen = false },
        selectedModel = selectedModel,
        onOpenTerminal = onOpenTerminal,
    )
}

/** Thin amber line shown above the chat when the server's conch-bridge is older
 *  than the version this app ships. Informational only — it points the user at
 *  the Server-settings updater (one-button Install/Update/Remove there). */
@Composable
private fun BridgeUpdateBanner(notice: String, onDismiss: () -> Unit) {
    val amber = MaterialTheme.colorScheme.tertiary
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Text(
            "⬆ Bridge update available ($notice) — update it in Server settings",
            color = amber,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        androidx.compose.material3.TextButton(onClick = onDismiss) {
            androidx.compose.material3.Text("dismiss")
        }
    }
}

/** The live working-status row, PINNED above the prompt/usage bar (NOT a
 * scrolling list item) so it always holds its place. Renders nothing when
 * no turn is in flight. */
@Composable
private fun PinnedWorkingStatus(
    vm: ChatViewModel,
    state: ai.eight24family.conch.agent.SessionState,
    remoteWorking: Boolean,
) {
    val isWorking = state is ai.eight24family.conch.agent.SessionState.Working || remoteWorking
    if (!isWorking) return
    val liveTokens by vm.liveThinkingTokens.collectAsState()
    val remoteTokens by vm.remoteTokens.collectAsState()
    val remoteTurnStart by vm.remoteTurnStartMs.collectAsState()
    val remoteThinking by vm.remoteThinking.collectAsState()
    val remoteWaiting by vm.remoteWaitingForInput.collectAsState()
    val activeEffort by vm.activeReasoningEffort.collectAsState()
    val workingAgent by vm.currentAgent.collectAsState()
    // Local fallback start — used only until the file's turn-start timestamp is
    // read; keyed on isWorking so it resets per turn.
    var localStartMs by remember { mutableStateOf(0L) }
    LaunchedEffect(isWorking) { if (isWorking) localStartMs = System.currentTimeMillis() }
    // Live thinking_tokens (app-driven) when we have them, else the file-summed
    // count (mirrored console turn — no live feed).
    val tokens = (liveTokens ?: 0L).takeIf { it > 0L } ?: remoteTokens.takeIf { it > 0L }
    WorkingStatusRow(
        startMs = remoteTurnStart ?: localStartMs,
        thinkingTokens = tokens,
        effort = activeEffort,
        thinking = remoteThinking,
        waitingForInput = remoteWaiting,
        agent = workingAgent,
    )
}
