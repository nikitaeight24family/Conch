package ai.eight24family.conch.ui.window

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat as ChatOutlined
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.Dns as DnsOutlined
import androidx.compose.material.icons.outlined.Settings as SettingsOutlined
import androidx.compose.material.icons.outlined.SmartToy as SmartToyOutlined
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.ui.navigation.AppNavGraph
import ai.eight24family.conch.ui.navigation.Routes

/**
 * Top-level layout host. Renders [AppNavGraph] full-screen at every
 * window size. The side NavigationRail that used to live here (Servers
 * / Keychain / Settings) was removed in the 2026-05-12 UX pass — the
 * items didn't reflect actual usage frequency (Keychain ≈ once a year,
 * Settings weekly, server-switch rare) and the rail ate 80dp of width
 * across every Medium+ session for almost no daily-use payoff.
 *
 * Settings / Keychain / About remain reachable through the existing
 * ⋮ overflow menu on each screen's topbar. At Expanded width
 * `ServersSessionsChatThreePane` (Phase 3) still shows the full server
 * list as the leftmost column when one's actually working — no rail
 * needed to duplicate it.
 *
 * Also the single-instance home for cold-start route restoration and
 * current-route persistence. State is `rememberSaveable` so a
 * configuration change (rotation, fold) doesn't re-trigger restoration.
 */
@Composable
fun AppScaffold() {
    val nav = rememberNavController()

    // Cold start ALWAYS lands on the unified Sessions home (Telegram model):
    // your most recent chat is the top row, one tap away. We deliberately do NOT
    // auto-restore a deep route anymore — restoring to servers/agents was
    // exactly the bounce, and the sessions list already serves as "continue
    // where you were" (offline, from cache, no key).

    // Persist current route on every change. Skip Keychain query args so
    // a cold-start restoration never re-fires its one-shot recovery
    // dialog (attach=… / mode=discover|register).
    val currentEntry by nav.currentBackStackEntryAsState()
    LaunchedEffect(currentEntry?.destination?.route, currentEntry?.arguments) {
        val route = currentEntry?.let { entry ->
            val template = entry.destination.route ?: return@let null
            renderRoute(template, entry.arguments)
        } ?: return@LaunchedEffect
        val toSave = if (route.startsWith("keychain")) "keychain" else route
        ServiceLocator.preferences.setLastRoute(toSave)
    }

    // Telegram-style bottom navigation: Sessions · Agents · Servers ·
    // Settings. Shown ONLY on those 4 top-level tab routes; hidden in chat
    // and every deeper/full-screen destination (sessions list, edit, keychain,
    // terminal, text viewer, …) so the focused views own the whole screen.
    val template = currentEntry?.destination?.route

    val hazeState = rememberHazeState()
    Box(Modifier.fillMaxSize()) {
        // The whole nav content is the blur SOURCE; the floating glass bar
        // samples it so the sessions show through, blurred ("liquid glass").
        AppNavGraph(nav, modifier = Modifier.fillMaxSize().hazeSource(hazeState))
        if (template in TAB_ROUTES) {
            FloatingTabBar(
                hazeState = hazeState,
                currentTemplate = template,
                onNavigate = { route -> navigateTab(nav, route) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
            )
        }
    }
}

/** Route templates that show the bottom bar. */
private val TAB_ROUTES = setOf(Routes.HOME, Routes.AGENTS_OVERVIEW, Routes.SERVERS, Routes.SETTINGS)

/**
 * Floating "liquid glass" tab bar — a compact translucent capsule that hovers
 * over the content (NOT a docked full-width footer). Haze blurs whatever is
 * behind it (the sessions list) so they show through the glass. Every tab
 * stays in the accent (cyan) colour — never greyed out; selection reads via
 * the sliding glass pill plus a slightly bolder/larger glyph + label.
 */
@Composable
private fun FloatingTabBar(
    hazeState: HazeState,
    currentTemplate: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Always the OUTLINE glyph — selection thickens its strokes (see
    // BoldableIcon), never a solid fill.
    data class Tab(val icon: ImageVector, val route: String, val selected: Boolean, val label: String)
    val tabs = listOf(
        Tab(Icons.AutoMirrored.Outlined.ChatOutlined, Routes.HOME, currentTemplate == Routes.HOME, "Sessions"),
        Tab(Icons.Outlined.SmartToyOutlined, Routes.AGENTS_OVERVIEW, currentTemplate == Routes.AGENTS_OVERVIEW, "Agents"),
        Tab(Icons.Outlined.DnsOutlined, Routes.SERVERS, currentTemplate == Routes.SERVERS, "Servers"),
        Tab(Icons.Outlined.SettingsOutlined, Routes.SETTINGS, currentTemplate == Routes.SETTINGS, "Settings"),
    )
    val selectedIndex = tabs.indexOfFirst { it.selected }.let { if (it < 0) 0 else it }
    // Wider slots now that each carries an icon + label under it.
    val slot = 66.dp
    val slotH = 46.dp
    val pillX by animateDpAsState(targetValue = slot * selectedIndex, label = "tabPill")
    val cyan = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.background
    val shape = RoundedCornerShape(percent = 50)

    Box(
        modifier = modifier
            .clip(shape)
            .hazeEffect(state = hazeState) {
                backgroundColor = bg
                // Lighter tint (was 0.40) so the blurred sessions SHOW THROUGH — a
                // heavy dark tint over dark content read as a solid capsule, not
                // glass. Softer blur keeps the content recognisable-through; a
                // touch of noise gives the frosted grain.
                tints = listOf(HazeTint(bg.copy(alpha = 0.24f)))
                blurRadius = 22.dp
                noiseFactor = 0.07f
            }
            // Glass sheen: a bright top highlight fading out, with a faint cyan
            // glow at the bottom edge — the reflection that makes a translucent
            // capsule actually READ as glass in a dark theme (where blurred dark
            // content is otherwise near-uniform). Frosted BASE lift — a hair
            // lighter than the background — so the capsule still reads as glass
            // over a UNIFORM background (short lists / bottom of scroll) where
            // the blur has nothing to sample and the tinted-bg fill would
            // otherwise blend into the bg as a flat solid. Negligible over real
            // content (the blurred content still shows).
            .background(Color.White.copy(alpha = 0.05f), shape)
            // Glass sheen on top: brighter top highlight + a stronger cyan glow at
            // the base, so the capsule's edges read as glass in a dark theme.
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.22f),
                        Color.Transparent,
                        cyan.copy(alpha = 0.10f),
                    ),
                ),
                shape,
            )
            .border(1.dp, cyan.copy(alpha = 0.55f), shape)
            .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        // Sliding selected-pill, drawn behind the items.
        Box(
            Modifier
                .offset(x = pillX)
                .size(slot, slotH)
                .clip(RoundedCornerShape(percent = 50))
                .background(cyan.copy(alpha = 0.16f)),
        )
        Row {
            tabs.forEach { tab ->
                // Always accent-coloured — never greyed. Selection shows via
                // the glass pill (already slid here) + a slightly bolder/larger
                // glyph and bold label.
                val selected = tab.selected
                Column(
                    modifier = Modifier
                        .size(slot, slotH)
                        .clip(RoundedCornerShape(percent = 50))
                        .clickable { onNavigate(tab.route) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // Active = full-weight outline; INACTIVE = thinner/lighter.
                    // Material outlined icons have a FIXED stroke (no genuinely
                    // thinner vector to draw), so "thinner" inactive = lower
                    // opacity → reads as a lighter/finer line. Active fades to full
                    // on select, back to faint on leave. No fill, no resize.
                    val iconAlpha by animateFloatAsState(
                        targetValue = if (selected) 1f else 0.4f,
                        animationSpec = tween(durationMillis = 240),
                        label = "tabIconAlpha",
                    )
                    Icon(
                        tab.icon,
                        contentDescription = tab.label,
                        tint = cyan,
                        modifier = Modifier.size(22.dp).alpha(iconAlpha),
                    )
                    Text(
                        tab.label,
                        color = cyan.copy(alpha = iconAlpha),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Standard bottom-tab navigation: single instance per tab, state saved /
 *  restored across switches, popping up to the start so the back stack
 *  doesn't accumulate one entry per tap. */
/** Top-level (tab) navigation: pop to the start dest saving the current tab's
 *  stack, single-top, restore the target tab's saved stack. MUST be used by
 *  EVERY "jump to a tab" entry point (bottom bar AND in-screen shortcuts like
 *  chat → Settings) — mixing a raw `nav.navigate(tabRoute)` with this corrupts
 *  Navigation's saved-state so other tabs (e.g. Agents) then restore the wrong
 *  destination until app restart (user, 2026-06-26). */
internal fun navigateTab(nav: NavHostController, route: String) {
    nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

// ─── helpers (moved from AppNav.kt) ───

/**
 * Substitute the named arguments in a route template (e.g.
 * `chat/{serverId}/{agent}`) with the actual values from the
 * destination's `arguments` bundle. Query params are kept if the
 * template includes them and the bundle has the key. Result is a
 * fully-qualified route safe to write back into DataStore and later
 * `nav.navigate(...)` against.
 */
private fun renderRoute(template: String, args: android.os.Bundle?): String {
    if (args == null) return template
    val (path, query) = template.split("?", limit = 2).let { it.first() to it.getOrNull(1) }
    val concrete = pathRegex.replace(path) { match ->
        val key = match.groupValues[1]
        java.net.URLEncoder.encode(args.getString(key) ?: "", "UTF-8")
    }
    if (query.isNullOrBlank()) return concrete
    val rendered = query.split("&").mapNotNull { qp ->
        val m = queryRegex.matchEntire(qp) ?: return@mapNotNull null
        val k = m.groupValues[1]
        val v = args.getString(k) ?: return@mapNotNull null
        if (v.isBlank()) null else "$k=" + java.net.URLEncoder.encode(v, "UTF-8")
    }.joinToString("&")
    return if (rendered.isEmpty()) concrete else "$concrete?$rendered"
}

private val pathRegex = Regex("\\{([^}]+)\\}")
private val queryRegex = Regex("([^=]+)=\\{([^}]+)\\}")

/**
 * Pull the `serverId` argument out of a saved Compose nav route string.
 * Returns null for routes that aren't server-scoped (servers / settings /
 * keychain / add_server) — those don't gate on pool state.
 */
private fun extractServerIdFromRoute(route: String): String? = when {
    route.startsWith("chat/") -> route.removePrefix("chat/").split("?", limit = 2).first().split("/").firstOrNull()
    route.startsWith("sessions/") -> route.removePrefix("sessions/").split("/").firstOrNull()
    route.startsWith("agents/") -> route.removePrefix("agents/").split("/").firstOrNull()
    route.startsWith("edit_server/") -> route.removePrefix("edit_server/").split("/").firstOrNull()
    else -> null
}

/**
 * Navigate to [route] with the natural intermediate stack underneath, so
 * tapping `←` from a chat lands on its sessions list, then agent picker,
 * then servers — same shape as if the user had walked there manually.
 * Without this, restoring straight to a deep route makes back-press dump
 * the user out of the app instead of unwinding.
 */
private fun walkBackStackTo(nav: NavHostController, route: String) {
    val ancestors = mutableListOf<String>()
    when {
        route.startsWith("chat/") -> {
            val parts = route.removePrefix("chat/").split("?", limit = 2).first().split("/")
            if (parts.size >= 2) {
                ancestors += "agents/${parts[0]}"
                ancestors += "sessions/${parts[0]}/${parts[1]}"
            }
        }
        route.startsWith("sessions/") -> {
            val parts = route.removePrefix("sessions/").split("/")
            if (parts.isNotEmpty()) ancestors += "agents/${parts[0]}"
        }
        route.startsWith("agents/") -> {
            // no ancestors beyond servers (which is the start destination)
        }
    }
    ancestors.forEach { nav.navigate(it) }
    nav.navigate(route)
}
