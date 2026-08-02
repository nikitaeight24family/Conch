package ai.eight24family.conch.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import ai.eight24family.conch.agent.RemoteSession
import ai.eight24family.conch.agent.SlashCommand
import ai.eight24family.conch.ui.viewmodel.ChatModal
import ai.eight24family.conch.ui.viewmodel.ChatViewModel

/**
 * Host for all modal-style chat overlays that sit above the Scaffold
 * tree:
 *  - Connection-lost dialog (reconnecting banner with leave button)
 *  - Ctrl+K command palette (sessions + slash commands)
 *  - SK hardware-security-key touch dialog (USB + NFC)
 *  - ChatModal sheets (Memory / ModelHint / Unsupported notice)
 *  - Server stats bottom sheet with 5s auto-refresh
 *
 * All state is pulled from the VM via [collectAsState] inside this
 * composable so the orchestrator doesn't need to thread half a dozen
 * dialog flags through its arg list.
 */
@Composable
internal fun ChatModalsHost(
    vm: ChatViewModel,
    onBack: () -> Unit,
    onOpenKeychain: () -> Unit,
    paletteOpen: Boolean,
    onPaletteDismiss: () -> Unit,
    onPickSlashCommand: (SlashCommand) -> Unit,
    statsSheetOpen: Boolean,
    onStatsSheetDismiss: () -> Unit,
    selectedModel: String?,
    onOpenTerminal: (serverId: String, serverName: String) -> Unit = { _, _ -> },
) {
    val remoteSessions by vm.remoteSessions.collectAsState()
    val customCommands by vm.customCommands.collectAsState()
    val modal by vm.modal.collectAsState()
    val server by vm.server.collectAsState()
    val observedModel by vm.observedModel.collectAsState()
    val serverStats by vm.serverStats.collectAsState()
    val statsLoading by vm.statsLoading.collectAsState()

    // Connection loss is handled SEAMLESSLY now: the chat auto-reconnects in the
    // background via the hardware device key (retry() → userConnectEphemeral →
    // startNewChat), no blocking overlay. The old "// connection lost /
    // Reconnecting…" center dialog was removed — user:. (ConnectionLostDialog is
    // now dead.)

    // Phase 5.1: Ctrl+K command palette. Renders when paletteOpen=true.
    // Selecting a session calls vm.openRemoteSession() which drives the
    // navigation; selecting a slash command pre-fills the input box so
    // the user can append arguments (most commands need them) — we
    // never auto-execute commands from the palette.
    if (paletteOpen) {
        ai.eight24family.conch.ui.keyboard.ChatCommandPalette(
            sessions = remoteSessions,
            customCommands = customCommands,
            onPickSession = { session ->
                onPaletteDismiss()
                vm.openRemoteSession(session)
            },
            onPickSlashCommand = { cmd ->
                onPaletteDismiss()
                // Pre-fill with "/<name> " — the trailing space sets the
                // user up to type arguments immediately. If the command
                // takes no args they hit Enter and we run it the same
                // way an in-input slash would.
                onPickSlashCommand(cmd)
            },
            onDismiss = onPaletteDismiss,
        )
    }

    // ── Hardware security-key touch request ──
    // The dialog runs the actual SSH op INSIDE yubikit's NFC callback
    // for NFC tokens (the IsoDep tag handle dies as soon as we
    // return). For USB it just builds a signer + drives the chat
    // open normally. ChatViewModel pre-connect awaits the signer,
    // runs the SSH handshake, then signals done — at which point the
    // dialog returns from `withNfc` and the user can lift the tag.
    val skTouch by vm.skTouchRequest.collectAsState()
    skTouch?.let { req ->
        SkInlineTouchDialog(
            transport = req.transport,
            credentialIdBase64 = req.credentialIdBase64,
            application = req.application,
            onUsbSigner = { signer ->
                vm.provideSkSignerForChatOpen(signer)
                vm.awaitSkOpDone()
            },
            onNfcSigner = { signer ->
                // Inside yubikit's NFC callback — pass the signer to
                // the VM and block here until the SSH handshake
                // completes (markSkOpDone fires).
                vm.provideSkSignerForChatOpen(signer)
                vm.awaitSkOpDone()
            },
            onCancel = { vm.cancelSkTouch() },
            onDiscoverOnKey = {
                vm.cancelSkTouch()
                onOpenKeychain()
            },
            onRegisterNewKey = {
                vm.cancelSkTouch()
                onOpenKeychain()
            },
        )
    }

    when (val m = modal) {
        ChatModal.Memory -> {
            val mem by vm.memory.collectAsState()
            MemorySheet(
                docs = mem,
                onSave = { scope, content -> vm.saveMemory(scope, content) },
                onRefresh = { vm.refreshMemory() },
                onDismiss = { vm.dismissModal() }
            )
        }
        ChatModal.ModelHint -> SimpleNotice(
            title = "/model",
            body = "Tap the topbar (where it says “${selectedModel ?: "default"} ▾”) to switch model.",
            onDismiss = { vm.dismissModal() }
        )
        is ChatModal.Unsupported -> SimpleNotice(
            title = "/${m.name}",
            body = m.reason,
            onDismiss = { vm.dismissModal() }
        )
        null -> {}
    }

    if (statsSheetOpen) {
        // Auto-refresh CPU/mem/net every 5s while the sheet is open.
        LaunchedEffect(Unit) {
            while (true) {
                vm.refreshServerStats()
                kotlinx.coroutines.delay(5_000)
            }
        }
        ServerStatsSheet(
            serverName = server?.name.orEmpty(),
            username = server?.username.orEmpty(),
            host = server?.host.orEmpty(),
            port = server?.port ?: 0,
            observedModel = observedModel,
            stats = serverStats,
            loading = statsLoading,
            onDismiss = onStatsSheetDismiss,
            onOpenTerminal = { server?.let { s -> onOpenTerminal(s.id, s.name) } },
        )
    }
    val pendingSwitch by vm.pendingModelSwitch.collectAsState()
    pendingSwitch?.let { p ->
        ModelSwitchDialog(
            label = p.label,
            isEffort = p.isEffort,
            onConfirm = { vm.confirmModelSwitch() },
            onCancel = { vm.cancelModelSwitch() },
        )
    }
}

/**
 * The cache-miss warning shown before a model switch. Wording and gating are
 * Anthropic's own, lifted from their CLI so the two agree — see
 * [ai.eight24family.conch.ui.viewmodel.ModelSwitchWarning] for the predicate and
 * why each "don't nag" rule earns its place.
 */
@androidx.compose.runtime.Composable
private fun ModelSwitchDialog(
    label: String,
    isEffort: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { androidx.compose.material3.Text(if (isEffort) "Change effort level?" else "Switch model?") },
        text = {
            androidx.compose.foundation.layout.Column(
                verticalArrangement = androidx.compose.foundation.layout.Arrangement
                    .spacedBy(androidx.compose.ui.unit.Dp(8f)),
            ) {
                androidx.compose.material3.Text(
                    "Your next response will be slower and use more tokens",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.tertiary,
                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                )
                androidx.compose.material3.Text(
                    "This conversation is cached for the current " +
                        (if (isEffort) "effort level" else "model") + ". " +
                        "Switching to $label means the full history gets re-read " +
                        "on your next message.",
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                androidx.compose.material3.Text("Yes, switch to $label")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onCancel) {
                androidx.compose.material3.Text("No, go back")
            }
        },
    )
}
