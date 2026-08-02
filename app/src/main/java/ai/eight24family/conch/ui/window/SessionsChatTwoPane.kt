package ai.eight24family.conch.ui.window

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.ui.navigation.Routes
import ai.eight24family.conch.ui.screens.ChatScreen
import ai.eight24family.conch.ui.screens.SessionsScreen

/**
 * Two-pane layout shown at Medium+ width (≥600dp) when the user is on the
 * CHAT route. Sessions list lives in the left pane (35% width); the
 * current chat lives in the right pane (65%). Both panes share the same
 * [NavHostController], so a deep-link via `nav.navigate(...)` from
 * either pane drives the same back stack.
 *
 * Compose tree shape:
 *
 *   ┌────────────────┬─────────────────────────────────────────────┐
 *   │ SessionsScreen │ ChatScreen                                  │
 *   │  (35%, list)   │  (65%, chat history + input + topbar)       │
 *   │  topbar        │  topbar                                     │
 *   │  ...           │  ...                                        │
 *   └────────────────┴─────────────────────────────────────────────┘
 *
 * Navigation behavior at Medium+:
 *  - Tap on a session row → `nav.navigate(CHAT(serverId, agent,
 *    resumeId, path)) { popUpTo(SESSIONS) ; launchSingleTop = true }`.
 *    `popUpTo(SESSIONS)` prevents stacking multiple chats — without it,
 *    selecting four different sessions would push four CHAT frames on
 *    the stack and the user would have to press back four times to
 *    leave. With it, each tap REPLACES the current CHAT route.
 *  - Tap on "new session" → same shape, just no resume/path args.
 *  - Tap back on either pane's topbar → `nav.popBackStack()` pops the
 *    CHAT route → lands on SESSIONS-only single-pane view.
 *
 * Known limitation (Phase 2 MVP): SessionsViewModel here is bound to the
 * CHAT route's NavBackStackEntry (because viewModel() looks up by class
 * within the local scope). So the SessionsScreen on this two-pane view
 * uses a FRESH VM that has to re-fetch its session list — distinct from
 * the SessionsViewModel that lived on the SESSIONS route. Cheap fetch
 * (cached in [SessionsCache]) but you may notice a brief empty-then-full
 * blink when going SESSIONS → CHAT. Phase 2.1 fixes this with nav-graph
 * nesting so both routes share one VM scope.
 */
@Composable
fun SessionsChatTwoPane(
    nav: NavHostController,
    serverId: String,
    agent: Agent,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxSize()) {
        SessionsScreen(
            serverId = serverId,
            onBack = { nav.popBackStack() },
            onOpenSession = { resumeId, path, model, reasoning ->
                nav.navigate(Routes.chat(
                    serverId, agent,
                    resumeId = resumeId,
                    resumePath = path,
                    sessionModel = model,
                    sessionReasoning = reasoning,
                )) {
                    popUpTo(Routes.sessions(serverId, agent)) { inclusive = false }
                    launchSingleTop = true
                }
            },
            onNewSession = {
                nav.navigate(Routes.chat(serverId, agent)) {
                    popUpTo(Routes.sessions(serverId, agent)) { inclusive = false }
                    launchSingleTop = true
                }
            },
            onOpenSubagents = { nav.navigate(Routes.subagents(serverId, agent, chatId = null)) },
            onOpenSettings = { navigateTab(nav, Routes.SETTINGS) },
            onOpenKeychain = { nav.navigate(Routes.keychain()) },
            modifier = Modifier.weight(0.35f),
        )
        VerticalDivider()
        ChatScreen(
            serverId = serverId,
            onBack = { nav.popBackStack() },
            onOpenSubagents = { chatId -> nav.navigate(Routes.subagents(serverId, agent, chatId)) },
            onOpenSettings = { navigateTab(nav, Routes.SETTINGS) },
            onOpenKeychain = { nav.navigate(Routes.keychain()) },
            onOpenTextViewer = { uri, filename, serverIdArg, remotePath ->
                nav.navigate(Routes.fileViewer(uri.toString(), filename, serverIdArg, remotePath))
            },
            modifier = Modifier.weight(0.65f),
        )
    }
}
