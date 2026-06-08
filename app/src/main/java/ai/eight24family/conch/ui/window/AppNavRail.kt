package ai.eight24family.conch.ui.window

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import ai.eight24family.conch.ui.navigation.Routes

/**
 * Side navigation rail shown at Medium+ window widths (≥600dp). Provides
 * always-visible jumps to the three top-level destinations: Servers (home),
 * Keychain, Settings. The deeper hierarchy (Server → AgentPicker → Sessions
 * → Chat) still lives inside the NavHost to the right of this rail — rail
 * items don't replace navigation, they just give one-tap access to roots
 * that previously took 2-3 back-presses to reach.
 *
 * Rail visibility is decided one level up in [AppScaffold]; this composable
 * doesn't read [LocalAppWindowAdaptive] itself so it can be reused at any
 * width if a future caller wants to embed it (e.g. inside a `Box` overlay).
 *
 * Selection state uses `NavBackStackEntry.hierarchy` rather than equality
 * on the route string — a nested route like `chat/<id>/<agent>` should
 * keep the Servers item highlighted (its hierarchy still includes the
 * Servers root). Matches Material 3 bottom-nav/rail guidance.
 *
 * Tapping a rail item calls `navigate(route) { popUpTo(Servers) {
 * saveState = true } ; launchSingleTop = true ; restoreState = true }`,
 * which is the Material 3 canonical "stable bottom-nav" pattern: each
 * top-level destination remembers its own deep state across rail taps so
 * the user can hop Servers → Settings → Servers and land back on the
 * same Chat they left.
 */
@Composable
fun AppNavRail(nav: NavHostController, modifier: Modifier = Modifier) {
    val currentEntry by nav.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    val adaptive = LocalAppWindowAdaptive.current

    NavigationRail(modifier = modifier) {
        // At Expanded width the [ServersSessionsChatThreePane] already
        // shows a full Servers column on the far left of the content
        // area — the rail's "Servers" item would just duplicate it and
        // steal a row of touch real estate. Hide it on Expanded, keep
        // Keychain / Settings (those are still useful shortcuts and
        // aren't represented elsewhere on the screen).
        if (!adaptive.isExpanded) {
            RailItem(
                selected = currentRoute.isInHierarchy(nav, Routes.SERVERS),
                label = "Servers",
                icon = Icons.Outlined.Storage,
                onClick = { navigateTopLevel(nav, Routes.SERVERS) },
            )
        }
        RailItem(
            selected = currentRoute.isInHierarchy(nav, Routes.KEYCHAIN),
            label = "Keychain",
            icon = Icons.Outlined.VpnKey,
            onClick = { navigateTopLevel(nav, Routes.keychain()) },
        )
        RailItem(
            selected = currentRoute.isInHierarchy(nav, Routes.SETTINGS),
            label = "Settings",
            icon = Icons.Outlined.Settings,
            onClick = { navigateTopLevel(nav, Routes.SETTINGS) },
        )
    }
}

@Composable
private fun RailItem(
    selected: Boolean,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    NavigationRailItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label) },
        alwaysShowLabel = false,
    )
}

/**
 * True when the current route is the given root OR a descendant of it
 * (e.g. `agents/<id>` should keep "Servers" highlighted because the
 * agent picker is reached from the Servers root). For deep routes
 * (`chat/...`) the back-stack hierarchy doesn't actually contain the
 * Servers route name unless we walked there — see [Routes] template
 * matching by prefix as a pragmatic fallback.
 */
private fun String?.isInHierarchy(nav: NavHostController, route: String): Boolean {
    if (this == null) return false
    // Direct match against the template (handles parameterized routes
    // like `keychain?attach=...` matching `keychain?...`).
    val templateMatch = this.startsWithRouteTemplate(route)
    if (templateMatch) return true
    // Fall back to NavBackStackEntry.hierarchy walk for nested graphs —
    // future-proof if we introduce nested navigation per rail item.
    val current = nav.currentBackStackEntry?.destination ?: return false
    return current.hierarchy.any { dest -> dest.route?.startsWithRouteTemplate(route) == true }
}

/**
 * Compare a concrete route (`servers`, `chat/abc/CLAUDE?resume=...`) to a
 * template (`servers`, `chat/{serverId}/{agent}?resume={resume}&path={path}`)
 * by their path prefix — strips both query strings and braces, then matches
 * the leading segment. `servers` matches `servers`; `agents/{serverId}`
 * matches `agents/abc-123`; `keychain?attach=...` matches `keychain` and
 * vice-versa.
 */
private fun String.startsWithRouteTemplate(template: String): Boolean {
    val thisPath = this.substringBefore('?')
    val templatePath = template.substringBefore('?')
    if (thisPath == templatePath) return true
    // Special case for the Servers tree: any deeper route starting with
    // `agents/` / `sessions/` / `chat/` originated from Servers, so the
    // Servers rail item should stay lit.
    if (templatePath == Routes.SERVERS && (
            thisPath.startsWith("agents/") ||
                thisPath.startsWith("sessions/") ||
                thisPath.startsWith("chat/") ||
                thisPath.startsWith("subagents/") ||
                thisPath.startsWith("subagent_edit/") ||
                thisPath == Routes.ADD_SERVER ||
                thisPath.startsWith("edit_server/")
            )
    ) return true
    return false
}

/**
 * Navigate to a top-level rail destination with Material 3's canonical
 * stable-tab pattern: `popUpTo(start) { saveState = true }`,
 * `launchSingleTop = true`, `restoreState = true`. Each rail tap snaps to
 * the requested root and preserves the deep state of the rail item we're
 * leaving behind — so a quick Servers→Settings→Servers round-trip lands
 * back on whatever chat the user was in.
 */
private fun navigateTopLevel(nav: NavHostController, route: String) {
    nav.navigate(route) {
        // popUpTo with saveState clears the visible stack to the start
        // destination but remembers what was there. restoreState below
        // brings it back if the user returns to this root later.
        popUpTo(Routes.SERVERS) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
