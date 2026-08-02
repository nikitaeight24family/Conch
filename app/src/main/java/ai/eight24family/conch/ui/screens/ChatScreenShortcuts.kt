package ai.eight24family.conch.ui.screens

import ai.eight24family.conch.ui.keyboard.DefaultShortcuts
import ai.eight24family.conch.ui.keyboard.KeyShortcut
import ai.eight24family.conch.ui.viewmodel.ChatViewModel

/**
 * Bind the chat keyboard shortcut table to the orchestrator's local
 * state. Each closure is invoked by [ai.eight24family.conch.ui.keyboard.shortcuts]
 * when the corresponding chord fires. Kept as a plain helper (not a
 * composable) because shortcuts are state-free between fires — the
 * accessors capture by reference, so updates to the underlying
 * MutableState are picked up automatically.
 *
 * Esc dispatch is layered: palette > command menu > model menu > modal.
 * The early-return chain means a single Esc walks back through one
 * layer at a time instead of nuking everything (matches platform
 * convention).
 */
internal fun buildChatShortcuts(
    vm: ChatViewModel,
    paletteOpen: () -> Boolean,
    setPaletteOpen: (Boolean) -> Unit,
    commandMenuOpen: () -> Boolean,
    setCommandMenuOpen: (Boolean) -> Unit,
    modelMenuOpen: () -> Boolean,
    setModelMenuOpen: (Boolean) -> Unit,
): List<Pair<KeyShortcut, () -> Unit>> = listOf(
    // Ctrl+N — fresh chat for the current (server, agent).
    // Routes through ChatViewModel.newSession() which also
    // bumps the in-memory selection so the two-pane layout
    // immediately swaps the right pane.
    DefaultShortcuts.NewChat to { vm.newSession() },
    // Esc — close whatever's open, in priority order:
    //   palette > command menu > model menu > modal
    // The early-return chain means a single Esc walks back
    // through one layer at a time instead of nuking
    // everything (matches platform convention).
    DefaultShortcuts.Dismiss to {
        when {
            paletteOpen() -> setPaletteOpen(false)
            commandMenuOpen() -> setCommandMenuOpen(false)
            modelMenuOpen() -> setModelMenuOpen(false)
            else -> vm.dismissModal()
        }
    },
    // Shift+Esc — stop the agent's current turn (mirrors the
    // ⨯ button in the input bar).
    DefaultShortcuts.StopTurn to { vm.stopCurrent() },
    // Phase 5.1: Ctrl+K — open command palette (fuzzy search
    // over sessions + slash commands). Toggle behavior — a
    // second press closes it, matching VS Code / Linear.
    DefaultShortcuts.CommandPalette to { setPaletteOpen(!paletteOpen()) },
    // Ctrl+/ — open the slash-command menu directly. Same
    // effect as tapping the `/` icon in the input bar.
    DefaultShortcuts.SlashMenu to { setCommandMenuOpen(true) },
    // Ctrl+R — retry the current chat (re-establish SSH
    // session + tail poller on the same resume id). Mirrors
    // the topbar Retry action so DeX users don't need to
    // hunt for it.
    DefaultShortcuts.Retry to { vm.retry() },
)
