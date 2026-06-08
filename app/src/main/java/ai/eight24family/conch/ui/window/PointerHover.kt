package ai.eight24family.conch.ui.window

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon

/**
 * Phase 7 of the foldable / DeX workstream.
 *
 * Turns the mouse cursor into the canonical "pointing hand" when hovered.
 * No-op on plain touch screens — Android phones don't have a cursor — so
 * applying this everywhere a `.clickable` lives is free. Recompositions
 * don't even fire because the underlying [pointerHoverIcon] modifier is
 * declared stable.
 *
 * On Samsung DeX, Chromebook, and any other Android desktop variant
 * where the user has a mouse, this is the single biggest "your app
 * understands desktop" tell. Without it, even buttons look like dead
 * weight — the cursor stays an arrow over what should clearly be a
 * pointer target. With it, the app feels native to the form factor.
 *
 * Wired into:
 *  - Server rows on `ServersScreen`
 *  - Session rows on `SessionsScreen`
 *  - The chat input's Send button and core topbar icons in
 *    `ChatScreen`
 *  - Settings rows and the NavigationRail items (the latter via
 *    Material 3's own pointer-icon handling, which we don't override).
 *
 * Future calls (Phase 7.1 if friction): I-beam over text fields (Compose
 * autodetects this on `TextField`; verify on DeX), text-cursor over
 * code blocks (so the user knows they can select), `Resize*` cursors
 * over the pane divider between Sessions / Chat at Medium+ so the user
 * can grab and drag the split — would require a resizable Splitter
 * composable too.
 */
fun Modifier.handCursor(): Modifier = this.pointerHoverIcon(PointerIcon.Hand)
