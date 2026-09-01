package ai.eight24family.conch.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.data.ChatSearch
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.ui.haptic.LocalConchHaptics
import ai.eight24family.conch.ui.haptic.ConchHaptic
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Drop-in [Scaffold] replacement for any screen that wants in-place
 * full-text search over chat history. The host screen continues to
 * render its normal content via [content] while the search query is
 * empty — i.e. tapping the loupe does NOT blank the screen. Only when
 * the user actually types something does the result list take over.
 *
 * Behaviour parity across screens: • Loupe icon in actions slot — tap unfolds
 * the input from End to Start over the title area. • While query is empty: loupe
 * remains; tapping it again closes the search bar (and the original title
 * returns). • Once any char is typed: loupe → × (clear). Tapping × wipes the
 * query but keeps the bar open + keyboard up. • Tapping anywhere outside the
 * input loses focus → bar closes after an 80 ms debounce (the × bounce stays
 * under the window). • Enter (ImeAction.Search) just dismisses the keyboard. •
 * Haptic Tap on loupe-toggle, Tick on × (lighter).
 *
 * Result rendering matches the original GlobalSearchScreen spec:
 *   - flat row list (no per-session grouping)
 *   - top line: chat title, bold, primary
 *   - bottom line: matched snippet, gray, with the match itself
 *     re-styled bold white
 *
 * Scope:
 *   [scopedServerId] + [scopedAgent] both null  → search every chat
 *   [scopedServerId] set, [scopedAgent] null    → that server only
 *   both set                                    → that (server, agent) pair only
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchableScaffold(
    /** Title block displayed when the search bar is closed. Pass any
     *  composable — single Text, Column with subtitle row, whatever
     *  the host screen needs. Caller controls styling. */
    title: @Composable () -> Unit,
    /** Back / drawer / etc. — same semantics as TopAppBar. */
    navigationIcon: @Composable () -> Unit = {},
    /** Extra trailing actions rendered to the LEFT of the loupe icon.
     *  They retract while the search bar is open so the input owns the
     *  whole topbar width. */
    extraActions: @Composable RowScope.() -> Unit = {},
    /** Scope filter — see kdoc above. */
    scopedServerId: String? = null,
    scopedAgent: Agent? = null,
    /** Tap on a result row. Receives session id + ordinal (position in
     *  the parsed message list — the deterministic anchor that works
     *  for both stable-id agents like Claude and random-UUID agents
     *  like Codex/Gemini) + msgId (verification probe for Claude) +
     *  user query + char-offset of THIS specific occurrence within the
     *  message body. */
    onPickHit: (sessionId: String, msgId: String, ordinal: Int, query: String, charOffset: Int) -> Unit,
    /** Placeholder text inside the input. Defaults to a scope hint. */
    placeholder: String? = null,
    /** Optional FAB. */
    floatingActionButton: @Composable () -> Unit = {},
    /** Modifier passed through to the underlying [Scaffold]. Used for
     *  foldable layouts to constrain the screen to a column slot. */
    modifier: Modifier = Modifier,
    /** Normal screen body — rendered when search is closed OR open
     *  with an empty query. Receives the Scaffold paddings. */
    content: @Composable (PaddingValues) -> Unit,
) {
    // `rememberSaveable` so that navigating into a chat from a search
    // hit and then back returns the user to the search results view
    // they tapped from — not the bare server / agent / session list.
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val resolvedPlaceholder = placeholder ?: when {
        scopedAgent != null -> "search · ${scopedAgent.name.lowercase()}"
        scopedServerId != null -> "search · this server"
        else -> "search · all chats"
    }

    val closeSearch: () -> Unit = {
        searchActive = false
        searchQuery = ""
    }

    // Android back-button / edge-swipe-back: when search is open, the
    // gesture closes the search instead of navigating to the previous
    // screen. User rule:. When search is closed the handler is
    // disabled so back propagates normally.
    BackHandler(enabled = searchActive) { closeSearch() }

    Scaffold(
        modifier = modifier,
        topBar = {
            SearchableTopBar(
                title = title,
                navigationIcon = navigationIcon,
                extraActions = extraActions,
                searchActive = searchActive,
                searchQuery = searchQuery,
                searchPlaceholder = resolvedPlaceholder,
                onOpenSearch = { searchActive = true },
                onCloseSearch = closeSearch,
                onSearchQueryChange = { searchQuery = it },
            )
        },
        // Hide the host FAB (e.g. "[ + add server ]") while searching — it
        // has nothing to do with the results list and just floats over the
        // hits. Restored the moment search closes.
        floatingActionButton = { if (!searchActive) floatingActionButton() },
    ) { padding ->
        // Decide what to render below the topbar.
        if (searchActive && searchQuery.isNotBlank()) {
            // Query non-empty → show search hits. Same UI as the old
            // GlobalSearchScreen body.
            SearchHitsBody(
                query = searchQuery,
                scopedServerId = scopedServerId,
                scopedAgent = scopedAgent,
                onPickHit = onPickHit,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else {
            // Either search is closed OR open with empty query — in
            // both cases the host's normal content stays visible. User
            // spec:.
            //
            // The TextField sits in the topbar (above this Box), so
            // any tap that lands in the body area is by definition
            // "outside the input". Child clickables (server rows,
            // etc.) consume the down event first via the standard
            // pointer pipeline, so tapping a row still navigates —
            // only stray taps on empty space trip the close. User
            // rule:.
            Box(
                modifier = if (searchActive) {
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { closeSearch() })
                        }
                } else {
                    Modifier.fillMaxSize()
                }
            ) {
                content(padding)
            }
        }
    }
}

/**
 * Topbar that morphs between "normal" (title + extra actions + loupe)
 * and "search-active" (input field + counter + ×). Same pattern as
 * ChatScreen's TerminalTopBar but reusable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchableTopBar(
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit,
    extraActions: @Composable RowScope.() -> Unit,
    searchActive: Boolean,
    searchQuery: String,
    searchPlaceholder: String,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
) {
    val haptic = LocalConchHaptics.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    // Auto-focus + raise the keyboard ONLY on the first open of a search
    // session (the user tapped the loupe), NOT every time the search screen
    // recomposes. Without this guard, opening a result → back returns to the
    // still-active search and re-fires the focus → the keyboard pops up
    // unprompted, which the user flagged as annoying. `rememberSaveable`
    // survives the nav-to-chat-and-back (same back-stack entry as the
    // restored `searchActive`), so the return is NOT treated as a fresh open.
    // Reset when search closes so the NEXT real open focuses again.
    var autoFocusedOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(searchActive) {
        if (searchActive) {
            if (!autoFocusedOnce) {
                autoFocusedOnce = true
                delay(40)
                SilentlyTry.fired("Conch-Search", "request search field focus (open)") { focus.requestFocus() }
                SilentlyTry.fired("Conch-Search", "show soft keyboard for search") { keyboardController?.show() }
            }
        } else {
            autoFocusedOnce = false
        }
    }
    // Focus-loss → close, with the same 80 ms debounce ChatScreen uses
    // so the × bounce doesn't trip it.
    val scope = rememberCoroutineScope()
    val pendingClose = remember { object { var job: Job? = null } }
    var sawFocus by remember(searchActive) { mutableStateOf(false) }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.primary,
            navigationIconContentColor = MaterialTheme.colorScheme.primary,
            actionIconContentColor = MaterialTheme.colorScheme.primary,
        ),
        // Navigation icon (back arrow / drawer) collapses to zero width
        // while search is open so the input field gets that horizontal
        // real-estate. User rule:. expand/shrinkHorizontally animates
        // the slot's measured width down to 0 — Material3 TopAppBar's
        // Layout re-measures every frame so the title slot picks up the
        // freed space mid-animation. Fade overlays the size change so
        // the icon doesn't pop.
        navigationIcon = {
            AnimatedVisibility(
                visible = !searchActive,
                enter = expandHorizontally(
                    animationSpec = tween(180, easing = FastOutSlowInEasing),
                    expandFrom = Alignment.Start,
                ) + fadeIn(animationSpec = tween(140)),
                exit = shrinkHorizontally(
                    animationSpec = tween(160, easing = FastOutSlowInEasing),
                    shrinkTowards = Alignment.Start,
                ) + fadeOut(animationSpec = tween(110)),
            ) {
                navigationIcon()
            }
        },
        title = {
            Box(modifier = Modifier.fillMaxWidth()) {
                AnimatedVisibility(
                    visible = !searchActive,
                    enter = fadeIn(animationSpec = tween(160)),
                    exit = fadeOut(animationSpec = tween(120)),
                ) {
                    title()
                }
                AnimatedVisibility(
                    visible = searchActive,
                    enter = expandHorizontally(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                        expandFrom = Alignment.End,
                    ) + fadeIn(animationSpec = tween(140)),
                    exit = shrinkHorizontally(
                        animationSpec = tween(180, easing = FastOutSlowInEasing),
                        shrinkTowards = Alignment.End,
                    ) + fadeOut(animationSpec = tween(120)),
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    // No prefix loupe here — the actions-slot icon
                    // already serves that role. Earlier rev rendered a
                    // small tertiary-tinted Search icon as a prefix; it
                    // visually clashed with the actions-slot icon (two
                    // loupes side by side, different tints, "what do
                    // they each do?"). One icon only.
                    // Compact BasicTextField that OVERLAYS the title and
                    // slides in right→left (the AnimatedVisibility above does
                    // the expand-from-End). It's ONE text line tall and
                    // vertically centred by the CenterEnd alignment — exactly
                    // where the title sat — so opening search does NOT make
                    // the title bounce up/down or the input land lower.
                    // No Material chrome → no built-in underline (the grey
                    // separator lives at the top of the results body).
                    //
                    // (Earlier this was wrapped in a fillMaxHeight + BottomStart
                    // box to pull that body line a few dp closer; but changing
                    // the title-slot height on open is what made the title jump
                    // and the field drop. A smooth overlay matters more than
                    // shaving the gap — reverted to a plain centred field.)
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .focusRequester(focus)
                            .onFocusChanged { fs ->
                                if (fs.isFocused) {
                                    pendingClose.job?.cancel()
                                    pendingClose.job = null
                                    sawFocus = true
                                } else if (sawFocus && searchActive) {
                                    pendingClose.job?.cancel()
                                    pendingClose.job = scope.launch {
                                        delay(80)
                                        onCloseSearch()
                                    }
                                }
                            },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = { keyboardController?.hide() },
                        ),
                        decorationBox = { inner ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    searchPlaceholder,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            inner()
                        },
                    )
                }
            }
        },
        actions = {
            // Order rule: loupe ALWAYS sits LEFT of any host-supplied
            // extras. Host screens park the settings cog last in their
            // extraActions, so the resulting layout is
            // `[loupe] [other extras] [settings]` — settings always in
            // the rightmost corner of the topbar, as requested.
            //
            // Loupe / × toggle — same 3-state logic as ChatScreen.
            val iconVariant = when {
                !searchActive -> 0
                searchQuery.isEmpty() -> 1
                else -> 2
            }
            AnimatedContent(
                targetState = iconVariant,
                transitionSpec = {
                    (fadeIn(tween(140)) + scaleIn(tween(140), initialScale = 0.7f)) togetherWith
                        (fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.7f))
                },
                contentKey = { it == 2 },
                label = "search-icon-toggle",
            ) { variant ->
                val isCross = variant == 2
                IconButton(onClick = {
                    haptic.perform(if (isCross) ConchHaptic.Tick else ConchHaptic.Tap)
                    when (variant) {
                        0 -> onOpenSearch()
                        1 -> onCloseSearch()
                        2 -> {
                            pendingClose.job?.cancel()
                            pendingClose.job = null
                            onSearchQueryChange("")
                            SilentlyTry.fired("Conch-Search", "request search field focus (clear)") { focus.requestFocus() }
                        }
                    }
                }) {
                    Icon(
                        if (isCross) Icons.Filled.Close else Icons.Filled.Search,
                        contentDescription = when (variant) {
                            0 -> "search"
                            1 -> "close search"
                            else -> "clear search"
                        },
                    )
                }
            }
            // Extra actions retract while search is open so the input
            // owns the full topbar width.
            AnimatedVisibility(
                visible = !searchActive,
                enter = fadeIn(tween(160)) +
                    expandHorizontally(tween(180, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(120)) +
                    shrinkHorizontally(tween(160, easing = FastOutSlowInEasing)),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    extraActions()
                }
            }
        },
    )
}

/**
 * The search result body. Queries the FTS4 index in the background
 * with a 60-ms debounce; renders the first page of hits (~200 rows)
 * and shows a count (capped at "10000+" for very-common-prefix
 * queries that would otherwise scan millions of posting-list entries).
 *
 * Scope filtering (per-server / per-agent) runs in Kotlin post-query
 * — we don't push scope into the SQL because the joining cost
 * outweighs filtering on a 200-row page. Will revisit if scoped
 * counts become unreliable.
 */
/** What server + agent owns a given session id. Used to render the
 *  breadcrumb line above each hit so a global-search user can tell at a
 *  glance which agent + server a match came from. The FTS index itself
 *  doesn't store this (lines are agent-agnostic JSONL); we resolve it
 *  by scanning the in-memory sessions cache once per scope-change. */
private data class HitSource(val serverName: String, val agent: Agent, val serverId: String? = null)


@Composable
private fun SearchHitsBody(
    query: String,
    scopedServerId: String?,
    scopedAgent: Agent?,
    onPickHit: (sessionId: String, msgId: String, ordinal: Int, query: String, charOffset: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var result by remember { mutableStateOf(ai.eight24family.conch.data.ChatSearch.Result("", 0L, 0L, emptyList())) }
    var queryMs by remember { mutableStateOf(0L) }

    // Live indexer progress — when the index is being rebuilt (first
    // run after upgrade, etc.) we surface that so the user knows
    // results are incomplete.
    val indexProgress by ServiceLocator.searchIndexer.progress.collectAsState()

    // sessionId → (serverName, agent) — derived from the in-memory
    // SessionsCache once per scope change. Doubles as the scoped-search
    // allow-set: a hit whose session isn't in this map is from a
    // (server, agent) outside the current scope. For unscoped global
    // search this map covers EVERY known session across every server ×
    // every agent. User asked: — this is the data source for that
    // breadcrumb.
    var sourceBySession by remember { mutableStateOf<Map<String, HitSource>>(emptyMap()) }
    LaunchedEffect(scopedServerId, scopedAgent) {
        val cache = ServiceLocator.sessionsCache
        val allServers = ServiceLocator.serverRepository
            .observeServers()
            .first()
        val servers = if (scopedServerId != null) {
            allServers.filter { it.id == scopedServerId }
        } else allServers
        val agents = if (scopedAgent != null) listOf(scopedAgent) else Agent.entries.toList()
        val map = HashMap<String, HitSource>()
        for (server in servers) {
            for (a in agents) {
                val snap = cache.load(server.id, a)
                for (s in snap.sessions) {
                    // First-writer-wins: the same JSONL filename should
                    // not collide across (server, agent) pairs in
                    // practice, but on the off-chance two caches do
                    // claim the same id, keep the earlier match (the
                    // user's view of "first" is deterministic by the
                    // server list order).
                    map.putIfAbsent(s.id, HitSource(server.name, a, server.id))
                }
            }
        }
        // Fallback layer: durable owner sidecars. SessionsCache is volatile
        // (preview-filtered + overwritten by each sweep), so a hit whose session
        // it no longer holds rendered with NO server. The per-session sidecar
        // persists for every session EVER listed/opened — merge it so the row's
        // server + agent resolve even then. SessionsCache wins (authoritative
        // name); the sidecar only fills gaps. Server name blank ⇒ server deleted,
        // row still shows the agent icon (never anonymous).
        val serversById = allServers.associateBy { it.id }
        val sidecars = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ServiceLocator.historyCache.allOwners()
        }
        for ((sid, owner) in sidecars) {
            if (map.containsKey(sid)) continue
            if (scopedServerId != null && owner.serverId != scopedServerId) continue
            if (scopedAgent != null && owner.agent != scopedAgent) continue
            map[sid] = HitSource(serversById[owner.serverId]?.name ?: "", owner.agent)
        }
        sourceBySession = map
    }

    LaunchedEffect(query, sourceBySession, scopedServerId, scopedAgent) {
        val q = query.trim()
        if (q.isEmpty()) {
            result = ai.eight24family.conch.data.ChatSearch.Result("", 0L, 0L, emptyList())
            queryMs = 0
            return@LaunchedEffect
        }
        // Debounce: 60 ms between the last keystroke and the actual
        // SQLite call. Cancels via LaunchedEffect's coroutine when
        // the user keeps typing.
        delay(60)
        val t0 = System.nanoTime()
        val raw = ai.eight24family.conch.data.ChatSearch.search(q)
        queryMs = (System.nanoTime() - t0) / 1_000_000
        // Scope filter: only apply when the user actually scoped to a
        // specific server. For truly global search, sourceBySession
        // contains every session — we still use it for the breadcrumb,
        // but a hit missing from the map (e.g. the index has rows for
        // a session whose cache was forgotten) is shown without label
        // rather than dropped.
        val filtered = if (scopedServerId == null) raw
        else raw.copy(hits = raw.hits.filter { it.sessionId in sourceBySession.keys })
        result = filtered
    }

    // System-role hits (CLI-injected payloads: auto-compact handoffs,
    // <system-reminder>, environment_context, …) are hidden from the
    // default hit list because they're noise for most queries. The thin
    // affordance below the search input lets the user reveal them when
    // they're explicitly looking for system content. Resets when the
    // query changes.
    var showSystem by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(query) { showSystem = false }

    val systemHits = remember(result) { result.hits.filter { it.role == "system" } }
    val nonSystemHits = remember(result) { result.hits.filter { it.role != "system" } }
    // When the user reveals system hits, show them ALL AT THE TOP (above
    // the chat hits), per request — not interleaved in date order.
    val visibleHits = if (showSystem) systemHits + nonSystemHits else nonSystemHits

    Column(modifier = modifier) {
        // Full-width hairline flush under the search bar. Lives in the body
        // (not the topbar) so it spans the ENTIRE screen width — the topbar
        // title slot is narrower (it reserves room for the × action), which
        // is why an in-topbar line stopped short of the edge. No top padding
        // → it reads as the top border of the results, no odd gap.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
        )
        // Result summary — ONE visible line: match count + (if any) a
        // tappable "+N system" toggle. The numbers are the TRUE FTS totals
        // (result.count / result.systemCount), NOT the deduped first-page
        // size — searching a common token like "c" used to read "99 · +2"
        // because that was the page, not reality. Capped at 10000+.
        fun fmt(n: Long) = if (n > ChatSearch.COUNT_OVERFLOW) "10000+" else "$n"
        val total = result.count
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    total <= 0L -> "// no matches"
                    total == 1L -> "// 1 match"
                    else -> "// ${fmt(total)} matches"
                },
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
            )
            if (result.systemCount > 0L) {
                Text(
                    text = if (showSystem) "   ·  hide ${fmt(result.systemCount)} system"
                        else "   ·  +${fmt(result.systemCount)} system",
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable { showSystem = !showSystem },
                )
            }
        }
        // Indexer-progress stripe — transient, only during a live re-index.
        if (indexProgress.running && indexProgress.total > 0) {
            Text(
                "// indexing ${indexProgress.done}/${indexProgress.total} sessions…",
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            items(visibleHits, key = { it.hitKey }) { hit ->
                HitRow(
                    hit = hit,
                    source = sourceBySession[hit.sessionId],
                    query = query,
                    onPickHit = onPickHit,
                )
            }
        }
    }
}

/** Renders one FTS hit. Snippet bytes from SQLite's `snippet()` are
 *  the verbatim line content with U+0001 / U+0002 markers around the
 *  matched span (we passed those as start/end markers in the SQL).
 *  Compose styles the wrapped range in bold-white; everything else
 *  outline-grey. */
@Composable
private fun HitRow(
    hit: ai.eight24family.conch.data.ChatSearch.Hit,
    source: HitSource?,
    query: String,
    onPickHit: (sessionId: String, msgId: String, ordinal: Int, query: String, charOffset: Int) -> Unit,
) {
    // Snippet → styled AnnotatedString. PERF (120Hz scroll jank fix): this
    // is `remember`-ed on (snippet, colour) so it's built once per row, not
    // on every recomposition; and consecutive same-style chars are batched
    // into ONE span (a `withStyle{append(run)}` per RUN) instead of one span
    // PER CHARACTER. The per-char version produced ~80 spans per row, and
    // text measure/layout is roughly O(spans) — that's what dropped the
    // results list from 120 to ~30 fps while scrolling. Runs collapse it to
    // ~3 spans (before-match / match / after-match).
    val outlineColor = MaterialTheme.colorScheme.outline
    val annotated = remember(hit.snippet, outlineColor) {
        val whiteBold = SpanStyle(color = Color(0xFFFFFFFF), fontWeight = FontWeight.Bold)
        val outline = SpanStyle(color = outlineColor)
        buildAnnotatedString {
            var inMatch = false
            val run = StringBuilder()
            // Inline parser: 0x01 / 0x02 mark match-span boundaries (only
            // SQLite's snippet() emits them in our pipeline — unambiguous).
            for (c in hit.snippet) {
                when (c.code) {
                    0x01, 0x02 -> {
                        if (run.isNotEmpty()) {
                            withStyle(if (inMatch) whiteBold else outline) { append(run.toString()) }
                            run.setLength(0)
                        }
                        inMatch = c.code == 0x01
                    }
                    else -> run.append(c)
                }
            }
            if (run.isNotEmpty()) {
                withStyle(if (inMatch) whiteBold else outline) { append(run.toString()) }
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                android.util.Log.d(
                    "Conch-Hl",
                    "HitRow tap: sid=${hit.sessionId.take(8)} mid=${hit.msgId} ord=${hit.ordinal} charOff=${hit.charOffset} snippet=${hit.snippet.filter { it.code >= 0x20 }.take(60)}"
                )
                onPickHit(hit.sessionId, hit.msgId, hit.ordinal, query, hit.charOffset)
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // Breadcrumb: <agent-icon> <server>. Agent icon goes FIRST so
        // the eye can scan the icon column down the result list and
        // immediately tell which agent a hit belongs to — server name
        // is secondary context that follows.
        //
        // Agent source order: SessionsCache (gives icon + server name) →
        // the agent stamped into the index at index time (gives the icon
        // for sessions SessionsCache has forgotten — the "github rows with
        // no icon" the user flagged). Server name only shows when we
        // actually know it (SessionsCache); for index-only owners we still
        // render the icon so the row isn't anonymous.
        val hitAgent = source?.agent
            ?: hit.agentName?.let { n -> Agent.entries.firstOrNull { it.name == n } }
        if (hitAgent != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(
                        ai.eight24family.conch.agent.spec.AgentSpecRegistry[hitAgent].iconRes,
                    ),
                    contentDescription = hitAgent.cliCommand,
                    tint = androidx.compose.ui.graphics.Color.Unspecified,
                    modifier = Modifier.size(14.dp),
                )
                if (!source?.serverName.isNullOrBlank()) {
                    Text("  ", style = MaterialTheme.typography.labelSmall)
                    Text(
                        source!!.serverName,
                        color = ai.eight24family.conch.ui.theme.serverNameColor(
                            serverId = source!!.serverId,
                            serverName = source!!.serverName,
                            fallback = MaterialTheme.colorScheme.outline,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }
        Text(
            hit.sessionPreview.ifBlank { "(empty chat)" },
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Snippet line. Visual treatment mirrors the chat:
        //   user message    →  `❯ ` cyan-bold prefix + tinted background +
        //                       2dp neon stripe on the left edge (matches
        //                       UserLine in ChatScreen)
        //   assistant       →  plain outline grey, no background
        //   system payload  →  italic + dim tertiary tint, `· system ·`
        //                       prefix — clearly NOT user/assistant, lives
        //                       in its own visual lane
        // Lets the user tell at a glance whose message contains the
        // match without having to open the chat.
        if (hit.role == "system") {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    "· system · ",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    annotated,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else if (hit.role == "user") {
            val cyan = MaterialTheme.colorScheme.primary
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .background(cyan.copy(alpha = 0.06f))
                    .drawBehind {
                        drawRect(
                            color = cyan,
                            topLeft = androidx.compose.ui.geometry.Offset.Zero,
                            size = androidx.compose.ui.geometry.Size(
                                2.dp.toPx(),
                                size.height,
                            ),
                        )
                    }
                    .padding(start = 10.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    "❯ ",
                    color = cyan,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    annotated,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Text(
                annotated,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
