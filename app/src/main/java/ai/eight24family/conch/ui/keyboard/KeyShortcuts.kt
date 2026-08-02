package ai.eight24family.conch.ui.keyboard

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * One Bluetooth-keyboard binding.
 *
 *  - [key]: the physical key that ends the chord (Enter, N, K, Escape, …).
 *  - [ctrl]: also accepts the Command/Meta modifier on macOS / Apple keyboards
 *    so DeX users with a Magic Keyboard get the same chord without
 *    re-learning. Compose surfaces both via `isCtrlPressed` and
 *    `isMetaPressed`; we treat the meta key as Ctrl-equivalent.
 *
 * Equality semantics are strict: a shortcut configured `Ctrl+Enter` will
 * NOT fire on bare Enter or Ctrl+Shift+Enter — keeps interactions in the
 * chat input box predictable. Shift / Alt are matched literally; if you
 * want a "with Shift" variant register it as a separate shortcut.
 *
 * See `ChatScreen.kt` for the call sites and the human-readable shortcut
 * table that lives in [DefaultShortcuts] / "Settings → Shortcuts".
 */
data class KeyShortcut(
    val key: Key,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
) {
    /**
     * Human label like "Ctrl+Enter" / "Ctrl+Shift+N". Drives the
     * shortcut table in Settings; intentionally uses "Ctrl" even on
     * macOS-style hardware where the key is actually Cmd — Android
     * Bluetooth maps both to the same logical event for non-Apple
     * keyboards, and the canonical label is more universal.
     */
    val label: String = buildString {
        if (ctrl) append("Ctrl+")
        if (shift) append("Shift+")
        if (alt) append("Alt+")
        append(keyDisplayName(key))
    }

    fun matches(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        if (event.key != key) return false
        // Treat Meta (Cmd on Apple keyboards) as a Ctrl-equivalent so the
        // same Ctrl+Enter binding works on Magic Keyboard hooked to DeX.
        val ctrlOrMeta = event.isCtrlPressed || event.isMetaPressed
        if (ctrl != ctrlOrMeta) return false
        if (shift != event.isShiftPressed) return false
        if (alt != event.isAltPressed) return false
        return true
    }
}

private fun keyDisplayName(key: Key): String = when (key) {
    Key.Enter, Key.NumPadEnter -> "Enter"
    Key.Escape -> "Esc"
    Key.Spacebar -> "Space"
    Key.Slash -> "/"
    Key.Comma -> ","
    Key.Period -> "."
    Key.Tab -> "Tab"
    Key.Backspace -> "Backspace"
    else -> {
        // `Key.toString()` returns "Key: N", "Key: Escape" — strip prefix
        // and uppercase letters so we get "N" / "Escape" cleanly.
        val s = key.toString().removePrefix("Key: ")
        if (s.length == 1) s.uppercase() else s
    }
}

/**
 * Attach a single keyboard shortcut to any composable. Uses
 * `onPreviewKeyEvent` so the event is observed at the root of the
 * focused-element chain BEFORE any TextField or button consumes it —
 * crucial for `Ctrl+N`-style global shortcuts that should fire even when
 * the chat input has focus.
 *
 * Returns `true` to consume the event when [shortcut] matches, so it
 * doesn't double-trigger and doesn't leak into a TextField (which
 * would otherwise interpret `Ctrl+N` as "insert N").
 */
fun Modifier.shortcut(
    shortcut: KeyShortcut,
    onTrigger: () -> Unit,
): Modifier = this.onPreviewKeyEvent { event ->
    if (shortcut.matches(event)) {
        onTrigger()
        true
    } else false
}

/**
 * Attach multiple shortcuts in one place. Iterates in declaration order;
 * first match wins. Cheaper than chaining [shortcut] N times because
 * Compose only installs ONE `onPreviewKeyEvent` modifier node.
 */
fun Modifier.shortcuts(
    bindings: List<Pair<KeyShortcut, () -> Unit>>,
): Modifier = this.onPreviewKeyEvent { event ->
    for ((shortcut, action) in bindings) {
        if (shortcut.matches(event)) {
            action()
            return@onPreviewKeyEvent true
        }
    }
    false
}

/**
 * The canonical chord set Conch exposes. Used both for the actual
 * dispatch in `ChatScreen` AND for the "Settings → Keyboard shortcuts"
 * page so the two never drift out of sync.
 */
object DefaultShortcuts {
    /** Send the current draft regardless of "Enter sends" setting. */
    val SendMessage = KeyShortcut(Key.Enter, ctrl = true)

    /** Start a fresh chat for the current server + agent. */
    val NewChat = KeyShortcut(Key.N, ctrl = true)

    /** Phase 5.1: `Ctrl+K` opens the quick-search palette over sessions
     *  + slash commands. See [ai.eight24family.conch.ui.keyboard.ChatCommandPalette]. */
    val CommandPalette = KeyShortcut(Key.K, ctrl = true)

    /** Phase 5.1: `Ctrl+/` opens the slash-command menu directly
     *  (equivalent to tapping the `/` icon in the input bar). */
    val SlashMenu = KeyShortcut(Key.Slash, ctrl = true)

    /** Phase 5.1: `Ctrl+R` retries the current chat — tears down the
     *  SSH transport for this AgentSession and re-establishes it on
     *  the same resume id. Mirrors the topbar Retry action. */
    val Retry = KeyShortcut(Key.R, ctrl = true)

    /** Close any modal / dismiss inline touch dialog. Walks back one
     *  layer at a time when multiple overlays are open (palette →
     *  command menu → model menu → modal). */
    val Dismiss = KeyShortcut(Key.Escape)

    /** Stop the agent's current turn (mirrors the ⨯ button in chat). */
    val StopTurn = KeyShortcut(Key.Escape, shift = true)

    /**
     * Stable list, in display order, used by the settings page. Add new
     * entries here so the help table stays one source of truth.
     */
    val All: List<Pair<KeyShortcut, String>> = listOf(
        SendMessage to "Send message",
        NewChat to "New chat",
        CommandPalette to "Command palette (sessions + slash search)",
        SlashMenu to "Slash menu",
        Retry to "Retry chat (rebuild SSH session)",
        Dismiss to "Close modal / dialog",
        StopTurn to "Stop current turn",
    )
}
