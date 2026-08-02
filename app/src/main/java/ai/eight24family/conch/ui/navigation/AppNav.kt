package ai.eight24family.conch.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.AgentStatus
import ai.eight24family.conch.di.ServiceLocator
// Pane composables (SessionsChatTwoPane, ServersSessionsChatThreePane,
// LocalAppWindowAdaptive) are no longer used — chat goes full-width
// regardless of device size, per 2026-05-12 UX feedback.
import ai.eight24family.conch.ui.screens.AboutScreen
import ai.eight24family.conch.ui.screens.AddServerScreen
import ai.eight24family.conch.ui.screens.AgentEditScreen
import ai.eight24family.conch.ui.screens.AgentPickerScreen
import ai.eight24family.conch.ui.screens.AgentsListScreen
import ai.eight24family.conch.ui.screens.AgentsOverviewScreen
import ai.eight24family.conch.ui.screens.ChatScreen
import ai.eight24family.conch.ui.screens.HomeSessionsScreen
import ai.eight24family.conch.ui.screens.KeychainScreen
import ai.eight24family.conch.ui.screens.PrivacyPolicyScreen
import ai.eight24family.conch.ui.screens.ServersScreen
import ai.eight24family.conch.ui.screens.SessionsScreen
import ai.eight24family.conch.util.SilentlyTry
// GlobalSearchScreen used to be a dedicated destination — search now
// runs in-place on Servers / AgentPicker / Sessions via
// SearchableScaffold. Route removed.
import ai.eight24family.conch.ui.screens.TermsOfServiceScreen
import ai.eight24family.conch.ui.screens.SettingsScreen
import java.net.URLEncoder

object Routes {
    /** Unified Sessions home (Telegram-style chat list across all
     *  servers×agents). The app's start destination + first bottom tab. */
    const val HOME = "home"
    const val SERVERS = "servers"
    const val ADD_SERVER = "add_server"
    /** Add ANOTHER user on an existing host: same screen as ADD_SERVER, only
     *  host/port pre-filled (a different SSH user = its own server entry, with
     *  its own $HOME / agent auth / sessions). */
    const val ADD_USER = "add_user?host={host}&port={port}"
    fun addUser(host: String, port: Int): String =
        "add_user?host=" + java.net.URLEncoder.encode(host, "UTF-8") + "&port=$port"
    const val EDIT_SERVER = "edit_server/{serverId}"
    fun editServer(serverId: String) = "edit_server/$serverId"
    /** One server's management page (Server detail) — opened by tapping a row
     *  in the Servers tab. Connect / terminal / activity log / edit / add user
     *  / delete all live here; a list tap never connects. */
    const val SERVER_DETAIL = "server_detail/{serverId}"
    fun serverDetail(serverId: String) = "server_detail/$serverId"
    /** Agents bottom-tab: overview of ALL servers + their agents (cached, no
     *  key). Tapping a server drills into its per-server [AGENTS] picker. */
    const val AGENTS_OVERVIEW = "agents_overview"
    // `browse=true` (drilled from the Agents overview) = show cached agent
    // statuses without
    // demanding a connection/FIDO touch and WITHOUT the not-connected watchdog
    // that bounces you out. `browse=false` (drilled from a server tap, already
    // connecting) keeps the live-probe behaviour.
    const val AGENTS = "agents/{serverId}?browse={browse}&login={login}"
    const val SESSIONS = "sessions/{serverId}/{agent}"
    const val CHAT = "chat/{serverId}/{agent}?resume={resume}&path={path}&model={model}&reasoning={reasoning}&q={q}&mid={mid}&ord={ord}&off={off}"

    /** Sentinel `serverId` for a chat opened READ-ONLY from local cache when
     * we no longer know (or never recorded) which server owned the session —
     * e.g. an old rollout the server has since compacted away. The bytes are
     * in HistoryCache (keyed by sessionId, not serverId), so the chat paints
     * fine; ChatViewModel sees this id, forces offline-read-only, and never
     * attempts SSH. */
    const val CACHE_ONLY_SERVER_ID = "__cache_only__"
    const val SETTINGS = "settings"
    // Plain Keychain route. Optional query args drive the "fix wrong-key
    // error from connect dialog" detour:
    //   ?attachToServer=<id>  — auto-attach every freshly imported /
    //                            registered key to this server.
    //   ?mode=discover|register
    //                          — auto-arm one of the SK flows on first
    //                            composition. `discover` enumerates
    //                            existing creds on the touched key;
    //                            `register` mints a brand-new credential.
    // Both args together send the user back to AGENTS/<id> on success
    // (via [onAttachedRetry]) so the picker re-runs auth with the new key.
    const val KEYCHAIN = "keychain?attachToServer={attachToServer}&mode={mode}"
    fun keychain(): String = "keychain?attachToServer=&mode="
    fun keychainForDiscover(serverId: String): String =
        "keychain?attachToServer=" + java.net.URLEncoder.encode(serverId, "UTF-8") + "&mode=discover"
    fun keychainForRegister(serverId: String): String =
        "keychain?attachToServer=" + java.net.URLEncoder.encode(serverId, "UTF-8") + "&mode=register"
    const val ABOUT = "about"
    const val OPERATIONS = "operations"
    const val ACTIVITY_LOG = "activity_log/{serverId}?name={name}"
    fun activityLog(serverId: String, name: String): String =
        "activity_log/" + URLEncoder.encode(serverId, "UTF-8") +
            "?name=" + URLEncoder.encode(name, "UTF-8")
    const val PRIVACY_POLICY = "privacy_policy"
    const val TERMS_OF_SERVICE = "terms_of_service"
    const val LICENSES = "licenses"
    const val SUBAGENTS = "subagents/{serverId}/{agent}?chatId={chatId}"
    const val SUBAGENT_EDIT = "subagent_edit/{serverId}/{agent}?chatId={chatId}&path={path}"

    /** Built-in text viewer/editor for files the OS has no default
     *  opener for. `uri` is the local Storage URI of the downloaded
     *  file (URL-encoded). `serverId` + `remotePath` are needed for
     *  the Save action — it writes the edited content back to the
     *  server via the pooled SSH client. */
    const val TEXT_VIEWER =
        "text_viewer?uri={uri}&filename={filename}&serverId={serverId}&remotePath={remotePath}"
    fun textViewer(
        uri: String,
        filename: String,
        serverId: String,
        remotePath: String,
    ): String =
        "text_viewer?uri=" + java.net.URLEncoder.encode(uri, "UTF-8") +
            "&filename=" + java.net.URLEncoder.encode(filename, "UTF-8") +
            "&serverId=" + java.net.URLEncoder.encode(serverId, "UTF-8") +
            "&remotePath=" + java.net.URLEncoder.encode(remotePath, "UTF-8")

    /** Read-only in-app viewers (paginated/rendered + share). Each needs only
     *  the local file uri + name. */
    const val PDF_VIEWER = "pdf_viewer?uri={uri}&filename={filename}"
    fun pdfViewer(uri: String, filename: String): String =
        "pdf_viewer?uri=" + java.net.URLEncoder.encode(uri, "UTF-8") +
            "&filename=" + java.net.URLEncoder.encode(filename, "UTF-8")

    const val MARKDOWN_VIEWER = "md_viewer?uri={uri}&filename={filename}"
    fun markdownViewer(uri: String, filename: String): String =
        "md_viewer?uri=" + java.net.URLEncoder.encode(uri, "UTF-8") +
            "&filename=" + java.net.URLEncoder.encode(filename, "UTF-8")

    const val DIFF_VIEWER = "diff_viewer?uri={uri}&filename={filename}"
    fun diffViewer(uri: String, filename: String): String =
        "diff_viewer?uri=" + java.net.URLEncoder.encode(uri, "UTF-8") +
            "&filename=" + java.net.URLEncoder.encode(filename, "UTF-8")

    /** Route an "open here" request to the right in-app viewer by file type:
     *  PDF → paginated reader, Markdown → rendered, diff/patch → colored,
     *  everything else → the bounded, binary-sniffed text viewer. Callers just
     *  say "open it here". */
    fun fileViewer(uri: String, filename: String, serverId: String, remotePath: String): String =
        when (filename.substringAfterLast('.', "").lowercase()) {
            "pdf" -> pdfViewer(uri, filename)
            "md", "markdown" -> markdownViewer(uri, filename)
            "diff", "patch" -> diffViewer(uri, filename)
            else -> textViewer(uri, filename, serverId, remotePath)
        }

    /** Real interactive PTY shell over the pooled SSH transport for a
     *  server. `name` is only the top-bar label — the live shell is keyed
     *  by `serverId` (which must already have a pooled connection; the
     *  servers screen connects first, then routes here). */
    const val TERMINAL = "terminal/{serverId}?name={name}"
    fun terminal(serverId: String, name: String): String =
        "terminal/" + java.net.URLEncoder.encode(serverId, "UTF-8") +
            "?name=" + java.net.URLEncoder.encode(name, "UTF-8")

    fun agents(serverId: String, browse: Boolean = false, login: String = "") =
        "agents/$serverId?browse=$browse&login=$login"
    fun sessions(serverId: String, agent: Agent) = "sessions/$serverId/${agent.name}"
    fun chat(
        serverId: String,
        agent: Agent,
        resumeId: String? = null,
        resumePath: String? = null,
        sessionModel: String? = null,
        sessionReasoning: String? = null,
        // Live search-highlight query: when the user opened this chat
        // by tapping a global-search hit, [searchHighlight] is the
        // query they typed; [searchHitMsgId] is the stable id of the
        // matched AgentMessage. Anchoring by id (not a positional
        // ordinal) is the Telegram pattern — ChatScreen resolves the
        // id → list index once the cache is parsed, so any parsing
        // jitter between index-time and chat-open-time can never point
        // at the wrong message. Both null on the normal session-row
        // tap path.
        searchHighlight: String? = null,
        searchHitMsgId: String? = null,
        searchHitOrdinal: Int? = null,
        searchHitCharOffset: Int? = null,
    ): String {
        val q = buildList {
            if (resumeId != null) add("resume=" + URLEncoder.encode(resumeId, "UTF-8"))
            if (resumePath != null) add("path=" + URLEncoder.encode(resumePath, "UTF-8"))
            if (sessionModel != null) add("model=" + URLEncoder.encode(sessionModel, "UTF-8"))
            if (sessionReasoning != null) add("reasoning=" + URLEncoder.encode(sessionReasoning, "UTF-8"))
            if (searchHighlight != null) add("q=" + URLEncoder.encode(searchHighlight, "UTF-8").replace("+", "%20"))
            if (searchHitMsgId != null) add("mid=" + URLEncoder.encode(searchHitMsgId, "UTF-8").replace("+", "%20"))
            if (searchHitOrdinal != null) add("ord=$searchHitOrdinal")
            if (searchHitCharOffset != null) add("off=$searchHitCharOffset")
        }.joinToString("&")
        val tail = if (q.isNotEmpty()) "?$q" else ""
        return "chat/$serverId/${agent.name}$tail"
    }
    fun subagents(serverId: String, agent: Agent, chatId: String?): String {
        val tail = chatId?.let { "?chatId=" + URLEncoder.encode(it, "UTF-8") }.orEmpty()
        return "subagents/$serverId/${agent.name}$tail"
    }

    fun subagentEdit(
        serverId: String,
        agent: Agent,
        chatId: String?,
        path: String? = null,
    ): String {
        val parts = buildList {
            if (chatId != null) add("chatId=" + URLEncoder.encode(chatId, "UTF-8"))
            if (path != null) add("path=" + URLEncoder.encode(path, "UTF-8"))
        }
        val tail = if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
        return "subagent_edit/$serverId/${agent.name}$tail"
    }
}

/**
 * NavHost rendering all app routes. Takes the [NavHostController] as a
 * parameter so callers can hoist it to the top — [AppScaffold] uses this
 * to share one controller between the side rail (when shown at Medium+
 * window widths) and the actual content area, so a rail tap and a deep
 * back-press operate on the same back stack.
 *
 * Cold-start route restoration and current-route persistence used to live
 * here; they moved to [AppScaffold] because they're a single-instance
 * concern that should run regardless of compact-vs-adaptive layout.
 *
 * [modifier] lets the caller constrain the NavHost to a sub-region (e.g.
 * `Row { rail ; NavHost(weight = 1f) }`). Default is unconstrained,
 * preserving phone-width behavior.
 */
@Composable
fun AppNavGraph(nav: NavHostController, modifier: Modifier = Modifier) {
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        modifier = modifier,
        // Kill the default screen-to-screen slide/fade transitions —
        // user explicitly asked for instant navigation, no animations.
        enterTransition = { androidx.compose.animation.EnterTransition.None },
        exitTransition = { androidx.compose.animation.ExitTransition.None },
        popEnterTransition = { androidx.compose.animation.EnterTransition.None },
        popExitTransition = { androidx.compose.animation.ExitTransition.None },
    ) {
        // Shared helper — every screen with in-place search uses the
        // same logic: resolve sessionId → (server, agent, path), then
        // navigate to that chat at the matched message. Inlined as a
        // factory because each composable destination has its own
        // CoroutineScope (rememberCoroutineScope inside the composable
        // block).
        @Composable
        fun rememberOpenChatFromSearch(): (String, String, Int, String, Int) -> Unit {
            val scope = rememberCoroutineScope()
            return { sessionId, msgId, ordinal, query, charOffset ->
                scope.launch {
                    fun go(serverId: String, agent: Agent, path: String?) = nav.navigate(
                        Routes.chat(
                            serverId = serverId,
                            agent = agent,
                            resumeId = sessionId,
                            resumePath = path,
                            searchHighlight = query.takeIf { it.isNotBlank() },
                            searchHitMsgId = msgId,
                            searchHitOrdinal = ordinal.takeIf { it >= 0 },
                            searchHitCharOffset = charOffset.takeIf { it >= 0 },
                        )
                    )
                    when (val r = resolveSession(sessionId)) {
                        is SessionResolve.Owned -> go(r.serverId, r.agent, r.path)
                        is SessionResolve.CacheOnly -> {
                            // Server unknown but bytes are cached — open
                            // read-only from local cache (sentinel serverId).
                            // The path; the chat paints from the cached JSONL
                            // by sessionId and never dials SSH.
                            android.util.Log.d(
                                "SshAi-AppNav",
                                "search hit cache-only sid=${sessionId.take(8)} agent=${r.agent} — read-only from local cache (server unknown)",
                            )
                            go(Routes.CACHE_ONLY_SERVER_ID, r.agent, null)
                        }
                        SessionResolve.Unresolved -> android.util.Log.w(
                            "SshAi-AppNav",
                            "search hit not navigable: sid=${sessionId.take(8)} — nothing cached or indexed for this id",
                        )
                    }
                }
            }
        }
        composable(Routes.HOME) {
            HomeSessionsScreen(
                onOpenChat = { serverId, agent, resumeId, path, model, reasoning ->
                    nav.navigate(
                        Routes.chat(
                            serverId = serverId,
                            agent = agent,
                            resumeId = resumeId,
                            resumePath = path,
                            sessionModel = model,
                            sessionReasoning = reasoning,
                        )
                    )
                },
                onOpenChatFromSearch = rememberOpenChatFromSearch(),
                onAddServer = { nav.navigate(Routes.ADD_SERVER) },
                // New session = a fresh chat (no resumeId) for the picked agent
                // on the picked server.
                onNewChat = { serverId, agent -> nav.navigate(Routes.chat(serverId, agent)) },
            )
        }
        composable(Routes.AGENTS_OVERVIEW) {
            AgentsOverviewScreen(
                // Install / update / login / method-switch / touch-connect are
                // all handled INLINE by the per-server panel now. The overview
                // only routes a READY agent tap to its chat + the SK-recovery
                // actions surfaced from the inline touch dialog.
                onOpenChat = { sid, agent -> nav.navigate(Routes.chat(sid, agent)) },
                onManageServer = { sid -> nav.navigate(Routes.agents(sid, browse = true)) },
                onOpenKeychainForDiscover = { sid -> nav.navigate(Routes.keychainForDiscover(sid)) },
                onOpenKeychainForRegister = { sid -> nav.navigate(Routes.keychainForRegister(sid)) },
                onOpenChatFromSearch = rememberOpenChatFromSearch(),
            )
        }
        composable(Routes.SERVERS) {
            ServersScreen(
                onAddServer = { nav.navigate(Routes.ADD_SERVER) },
                // Tap → the server's management page. No connect here; that
                // (plus terminal/edit/add-user/delete) lives on the detail page.
                onOpenServer = { id -> nav.navigate(Routes.serverDetail(id)) },
                onOpenChatFromSearch = rememberOpenChatFromSearch(),
            )
        }
        composable(
            Routes.SERVER_DETAIL,
            arguments = listOf(navArgument("serverId") { type = NavType.StringType })
        ) {
            // serverId flows into ServerDetailViewModel's SavedStateHandle
            // automatically (Navigation seeds it from the route args).
            ai.eight24family.conch.ui.screens.ServerDetailScreen(
                onBack = { nav.popBackStack() },
                onOpenTerminal = { sid, name -> nav.navigate(Routes.terminal(sid, name)) },
                onOpenActivityLog = { sid, name -> nav.navigate(Routes.activityLog(sid, name)) },
                onEditServer = { sid -> nav.navigate(Routes.editServer(sid)) },
                onAddUserHere = { host, port -> nav.navigate(Routes.addUser(host, port)) },
            )
        }
        composable(Routes.ADD_SERVER) {
            AddServerScreen(
                onBack = { nav.popBackStack() },
                onOpenKeychain = { nav.navigate(Routes.keychain()) }
            )
        }
        composable(
            Routes.ADD_USER,
            arguments = listOf(
                navArgument("host") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("port") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) {
            // Same screen as ADD_SERVER; host/port arrive via the route and
            // AddServerViewModel pre-fills them.
            AddServerScreen(
                onBack = { nav.popBackStack() },
                onOpenKeychain = { nav.navigate(Routes.keychain()) },
            )
        }
        composable(
            Routes.EDIT_SERVER,
            arguments = listOf(navArgument("serverId") { type = NavType.StringType })
        ) {
            // AddServerScreen reads `editServerId` from the
            // SavedStateHandle to pre-fill the form. The route arg is
            // wired automatically by Navigation Compose into the
            // ViewModel's SavedStateHandle, so the screen itself
            // doesn't need to receive it as a parameter.
            AddServerScreen(
                onBack = { nav.popBackStack() },
                onOpenKeychain = { nav.navigate(Routes.keychain()) },
            )
        }
        composable(
            Routes.AGENTS,
            arguments = listOf(
                navArgument("serverId") { type = NavType.StringType },
                navArgument("browse") { type = NavType.BoolType; defaultValue = false },
                navArgument("login") { type = NavType.StringType; defaultValue = "" },
            )
        ) { entry ->
            val id = entry.arguments?.getString("serverId") ?: return@composable
            val browse = entry.arguments?.getBoolean("browse") ?: false
            // Set when arriving from the Agents overview tapping a "log in" agent
            // → auto-open that agent's login chooser on first composition (so the
            // tap actually STARTS login instead of just re-listing the agents).
            val autoLoginAgent = entry.arguments?.getString("login")?.takeIf { it.isNotEmpty() }
                ?.let { name -> SilentlyTry.logged("SshAi-AppNav", "parse autoLogin agent") { Agent.valueOf(name) } }
            AgentPickerScreen(
                serverId = id,
                browse = browse,
                autoLoginAgent = autoLoginAgent,
                onBack = { nav.popBackStack() },
                onPickAgent = { agent -> nav.navigate(Routes.sessions(id, agent)) },
                onOpenKeychainForDiscover = { sid ->
                    // Pop the AgentPicker on the way out so its watchdog
                    // doesn't fire and pop Keychain itself once we're
                    // there. User comes back via [Keychain.onAttachedRetry]
                    // which re-enters AGENTS for [sid].
                    nav.popBackStack()
                    nav.navigate(Routes.keychainForDiscover(sid))
                },
                onOpenKeychainForRegister = { sid ->
                    nav.popBackStack()
                    nav.navigate(Routes.keychainForRegister(sid))
                },
                onOpenSettings = { ai.eight24family.conch.ui.window.navigateTab(nav, Routes.SETTINGS) },
                onOpenChatFromSearch = rememberOpenChatFromSearch(),
                onOpenTerminal = { sid, name -> nav.navigate(Routes.terminal(sid, name)) },
            )
        }
        composable(
            Routes.SESSIONS,
            arguments = listOf(
                navArgument("serverId") { type = NavType.StringType },
                navArgument("agent") { type = NavType.StringType }
            )
        ) { entry ->
            val id = entry.arguments?.getString("serverId") ?: return@composable
            val agentName = entry.arguments?.getString("agent") ?: return@composable
            val agent = SilentlyTry.logged("SshAi-AppNav", "parse agent name from nav") { Agent.valueOf(agentName) } ?: return@composable
            SessionsScreen(
                serverId = id,
                onBack = { nav.popBackStack() },
                onOpenSession = { resumeId, path, model, reasoning ->
                    nav.navigate(Routes.chat(
                        id, agent,
                        resumeId = resumeId,
                        resumePath = path,
                        sessionModel = model,
                        sessionReasoning = reasoning,
                    ))
                },
                onNewSession = { nav.navigate(Routes.chat(id, agent)) },
                onOpenSubagents = {
                    nav.navigate(Routes.subagents(id, agent, chatId = null))
                },
                onOpenSettings = { ai.eight24family.conch.ui.window.navigateTab(nav, Routes.SETTINGS) },
                onOpenKeychain = { nav.navigate(Routes.keychain()) },
                onOpenChatFromSearch = rememberOpenChatFromSearch(),
                onOpenTextViewer = { uri, filename, sId, remotePath ->
                    nav.navigate(Routes.fileViewer(uri.toString(), filename, sId, remotePath))
                },
                onOpenTerminal = { sid, name -> nav.navigate(Routes.terminal(sid, name)) },
            )
        }
        composable(
            Routes.CHAT,
            arguments = listOf(
                navArgument("serverId") { type = NavType.StringType },
                navArgument("agent") { type = NavType.StringType },
                navArgument("resume") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("path") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("model") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("reasoning") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("q") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("mid") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("ord") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("off") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { entry ->
            val id = entry.arguments?.getString("serverId") ?: return@composable
            val agentName = entry.arguments?.getString("agent") ?: return@composable
            val agent = SilentlyTry.logged("SshAi-AppNav", "parse agent name from nav") { Agent.valueOf(agentName) } ?: return@composable
            // Chat ALWAYS full-width, on every device size. The earlier
            // two-pane (sessions+chat) and three-pane (servers+sessions+
            // chat) layouts reserved 30-50% of the window for a session
            // list the user reaches for maybe 4% of their session.
            // Result on DeX / foldable inner: chat squeezed into 60-65%
            // of width while a permanent sessions panel just sat there.
            // Sessions are now reached via back-button (one tap) or the
            // Ctrl+K command palette (zero taps from the keyboard) —
            // both already wired. Removed in the 2026-05-12 UX pass.
            ChatScreen(
                serverId = id,
                onBack = { nav.popBackStack() },
                onOpenSubagents = { chatId ->
                    nav.navigate(Routes.subagents(id, agent, chatId))
                },
                onOpenSettings = { ai.eight24family.conch.ui.window.navigateTab(nav, Routes.SETTINGS) },
                onOpenKeychain = { nav.navigate(Routes.keychain()) },
                onOpenTextViewer = { uri, filename, serverId, remotePath ->
                    nav.navigate(Routes.fileViewer(uri.toString(), filename, serverId, remotePath))
                },
                onOpenTerminal = { sid, name -> nav.navigate(Routes.terminal(sid, name)) },
            )
        }
        composable(
            Routes.SUBAGENTS,
            arguments = listOf(
                navArgument("serverId") { type = NavType.StringType },
                navArgument("agent") { type = NavType.StringType },
                navArgument("chatId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            )
        ) { entry ->
            val id = entry.arguments?.getString("serverId") ?: return@composable
            val agentName = entry.arguments?.getString("agent") ?: return@composable
            val agent = SilentlyTry.logged("SshAi-AppNav", "parse agent name from nav") { Agent.valueOf(agentName) } ?: return@composable
            val chatId = entry.arguments?.getString("chatId")
            AgentsListScreen(
                onBack = { nav.popBackStack() },
                onNew = { nav.navigate(Routes.subagentEdit(id, agent, chatId, path = null)) },
                onEdit = { p -> nav.navigate(Routes.subagentEdit(id, agent, chatId, path = p)) }
            )
        }
        composable(
            Routes.SUBAGENT_EDIT,
            arguments = listOf(
                navArgument("serverId") { type = NavType.StringType },
                navArgument("agent") { type = NavType.StringType },
                navArgument("chatId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("path") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            )
        ) {
            AgentEditScreen(onBack = { nav.popBackStack() })
        }
        composable(
            Routes.TEXT_VIEWER,
            arguments = listOf(
                navArgument("uri") { type = NavType.StringType },
                navArgument("filename") { type = NavType.StringType },
                navArgument("serverId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("remotePath") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            )
        ) { entry ->
            val uriStr = entry.arguments?.getString("uri") ?: return@composable
            val filename = entry.arguments?.getString("filename") ?: "file"
            val serverId = entry.arguments?.getString("serverId")
            val remotePath = entry.arguments?.getString("remotePath")
            val uri = SilentlyTry.logged("SshAi-AppNav", "parse Uri from nav") { android.net.Uri.parse(uriStr) }
                ?: return@composable
            ai.eight24family.conch.ui.screens.TextViewerScreen(
                uri = uri,
                filename = filename,
                serverId = serverId,
                remotePath = remotePath,
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            Routes.PDF_VIEWER,
            arguments = listOf(
                navArgument("uri") { type = NavType.StringType },
                navArgument("filename") { type = NavType.StringType },
            ),
        ) { entry ->
            val uriStr = entry.arguments?.getString("uri") ?: return@composable
            val filename = entry.arguments?.getString("filename") ?: "file.pdf"
            val uri = SilentlyTry.logged("SshAi-AppNav", "parse Uri from nav (pdf)") { android.net.Uri.parse(uriStr) }
                ?: return@composable
            ai.eight24family.conch.ui.screens.PdfViewerScreen(
                uri = uri,
                filename = filename,
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            Routes.MARKDOWN_VIEWER,
            arguments = listOf(
                navArgument("uri") { type = NavType.StringType },
                navArgument("filename") { type = NavType.StringType },
            ),
        ) { entry ->
            val uriStr = entry.arguments?.getString("uri") ?: return@composable
            val filename = entry.arguments?.getString("filename") ?: "file.md"
            val uri = SilentlyTry.logged("SshAi-AppNav", "parse Uri from nav (md)") { android.net.Uri.parse(uriStr) }
                ?: return@composable
            ai.eight24family.conch.ui.screens.MarkdownViewerScreen(
                uri = uri,
                filename = filename,
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            Routes.DIFF_VIEWER,
            arguments = listOf(
                navArgument("uri") { type = NavType.StringType },
                navArgument("filename") { type = NavType.StringType },
            ),
        ) { entry ->
            val uriStr = entry.arguments?.getString("uri") ?: return@composable
            val filename = entry.arguments?.getString("filename") ?: "file.diff"
            val uri = SilentlyTry.logged("SshAi-AppNav", "parse Uri from nav (diff)") { android.net.Uri.parse(uriStr) }
                ?: return@composable
            ai.eight24family.conch.ui.screens.DiffViewerScreen(
                uri = uri,
                filename = filename,
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            Routes.TERMINAL,
            arguments = listOf(
                navArgument("serverId") { type = NavType.StringType },
                navArgument("name") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = ""
                },
            ),
        ) { entry ->
            // serverId flows into the TerminalViewModel's SavedStateHandle
            // automatically (Navigation seeds it from the route args), so
            // the screen takes only the display name + back.
            val name = entry.arguments?.getString("name").orEmpty()
            ai.eight24family.conch.ui.screens.TerminalScreen(
                serverName = name,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenKeychain = { nav.navigate(Routes.keychain()) },
                onOpenAbout = { nav.navigate(Routes.ABOUT) },
                onOpenPrivacyPolicy = { nav.navigate(Routes.PRIVACY_POLICY) },
                onOpenTermsOfService = { nav.navigate(Routes.TERMS_OF_SERVICE) },
                onOpenLicenses = { nav.navigate(Routes.LICENSES) },
            )
        }
        composable(
            Routes.KEYCHAIN,
            arguments = listOf(
                navArgument("attachToServer") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("mode") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val attach = entry.arguments?.getString("attachToServer")?.takeIf { it.isNotBlank() }
            val mode = entry.arguments?.getString("mode")?.takeIf { it.isNotBlank() }
            KeychainScreen(
                onBack = { nav.popBackStack() },
                attachToServerId = attach,
                autoArmMode = mode?.let { SilentlyTry.logged("SshAi-AppNav", "parse AddSkMode") { ai.eight24family.conch.ui.screens.AddSkMode.valueOf(it.uppercase()) } },
                onAttachedRetry = { sid ->
                    // After auto-attach, send the user back to AGENTS for
                    // [sid] so the picker can re-run auth with the new key.
                    nav.popBackStack()
                    nav.navigate(Routes.agents(sid))
                },
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(
                onBack = { nav.popBackStack() },
                onOpenPrivacyPolicy = { nav.navigate(Routes.PRIVACY_POLICY) },
                onOpenTermsOfService = { nav.navigate(Routes.TERMS_OF_SERVICE) },
                onOpenOperations = { nav.navigate(Routes.OPERATIONS) },
                onOpenLicenses = { nav.navigate(Routes.LICENSES) },
            )
        }
        composable(Routes.OPERATIONS) {
            ai.eight24family.conch.ui.screens.OperationsScreen(onBack = { nav.popBackStack() })
        }
        composable(
            Routes.ACTIVITY_LOG,
            arguments = listOf(
                navArgument("serverId") { type = NavType.StringType },
                navArgument("name") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = ""
                },
            ),
        ) { entry ->
            val sid = entry.arguments?.getString("serverId") ?: return@composable
            val name = entry.arguments?.getString("name").orEmpty()
            ai.eight24family.conch.ui.screens.ServerActivityLogScreen(
                serverId = sid,
                serverName = name,
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.PRIVACY_POLICY) {
            PrivacyPolicyScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.TERMS_OF_SERVICE) {
            TermsOfServiceScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.LICENSES) {
            ai.eight24family.conch.ui.screens.LicensesScreen(onBack = { nav.popBackStack() })
        }
    }
}

/**
 * Find which `(serverId, agent)` owns a given chat session by scanning
 * the per-server-per-agent session-list cache. Used by global search
 * to navigate from a found message into its owning chat. Returns null
 * if no cached session matches.
 */
/** Outcome of locating which chat a search hit belongs to. */
private sealed interface SessionResolve {
    /** We know the owning server — open the chat normally (it paints from
     *  cache instantly and connects opportunistically via the chip). */
    data class Owned(val serverId: String, val agent: Agent, val path: String?) : SessionResolve
    /** Bytes are cached and we know the agent (stamped into the index from
     * Open it READ-ONLY from the local cache: */
    data class CacheOnly(val agent: Agent) : SessionResolve
    /** Nothing cached/indexed for this id — truly nothing to show. */
    object Unresolved : SessionResolve
}

private suspend fun resolveSession(sessionId: String): SessionResolve {
    val servers = SilentlyTry.logged("SshAi-AppNav", "list servers for resolver") {
        ServiceLocator.serverRepository.observeServers().first()
    } ?: emptyList()
    val cache = ServiceLocator.sessionsCache
    for (s in servers) {
        for (agent in Agent.entries) {
            val snap = cache.load(s.id, agent)
            val match = snap.sessions.firstOrNull { it.id == sessionId }
            if (match != null) return SessionResolve.Owned(s.id, agent, match.path)
        }
    }
    // The durable owner sidecar HistoryCache writes for EVERY listed session
    // and never prunes — authoritative and survives SessionsCache churn.
    ServiceLocator.historyCache.owner(sessionId)?.let {
        return SessionResolve.Owned(it.serverId, it.agent, it.path)
    }
    // Owner stamped into the search index. serverId may be null (the server
    // was never recorded — pre-durable-log), but the AGENT is content-
    // detected, so we can still open the cache read-only.
    var knownAgent: Agent? = null
    SilentlyTry.logged("SshAi-AppNav", "index session owner") {
        ServiceLocator.searchDatabase.searchDao().sessionOwner(sessionId)
    }?.let { row ->
        val agent = row.agent?.let { a -> Agent.entries.firstOrNull { it.name == a } }
        val sid = row.serverId
        if (sid != null && agent != null) return SessionResolve.Owned(sid, agent, row.path)
        knownAgent = agent
    }
    return knownAgent?.let { SessionResolve.CacheOnly(it) } ?: SessionResolve.Unresolved
}
