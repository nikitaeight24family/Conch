package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.drop
import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.ui.viewmodel.ChatViewModel
import ai.eight24family.conch.util.SilentlyTry

/**
 * Bundle of state that the chat scroll/anchor pipeline exposes to the
 * orchestrator. The orchestrator uses these to:
 *  - drive the [LazyListState] of the messages list
 *  - alpha-hide the list until [anchorApplied] flips true (search-anchor
 *    flow)
 *  - feed [matchAnchor] / [targetOrd] / [matchCharOffset] into the
 *    [LocalSearchHighlight] and [LocalMatchAnchor] composition locals so
 *    the matched message's renderer can report its layout back up.
 */
internal data class ChatScrollController(
    val lazyListState: LazyListState,
    val anchorApplied: Boolean,
    val targetOrd: Int,
    val matchCharOffset: Int,
    val matchAnchor: MatchAnchor?,
)

/**
 * Composable that owns ALL autoscroll behavior for the chat:
 *
 *  - the [LazyListState] (rememberSaveable for config-change survival)
 *  - the "user intent" snapshot `wasAtBottomSnapshot` (updated only when
 *    `isScrollInProgress: true → false` — see INVARIANTS.md #2)
 *  - the search-anchor first-scroll pipeline (resolve msgId → ord →
 *    line-precise centring)
 *  - the user-send trigger that bumps `wasAtBottomSnapshot=true` on a
 *    new UserText so streaming-follow re-engages
 *  - the streaming-follow effect keyed on `contentSig` (size, lastLen)
 *  - the IME-show handler that scrolls to bottom when keyboard opens IF
 *    the user was anchored at the bottom
 *
 * Critical invariants preserved here (do NOT consolidate effects):
 *   - INVARIANT #1: autoscroll split into TWO `LaunchedEffect`s — one on
 *     `messages.size` (animated), one on `scrollState.maxValue` (instant).
 *     Combining them kills streaming.
 *   - INVARIANT #2: `wasAtBottomSnapshot` updates ONLY when scroll
 *     settles (true → false transition of `isScrollInProgress`).
 *   - INVARIANT #3: IME `LaunchedEffect(imeBottomPx)` scrolls to maxValue
 *     when `wasAtBottomSnapshot=true` (NOT the live `isAtBottom`).
 *   - INVARIANT #4: after `lastUserMsgId` change, `wasAtBottomSnapshot
 *     = true` so a search-opened chat jumps to the new send.
 */
@Composable
internal fun rememberChatScrollController(
    messages: List<AgentMessage>,
    vm: ChatViewModel,
    cameFromSearch: Boolean,
    /** A turn is in flight → the pinned working-status row is showing below the
     *  list. Toggling it resizes the chat viewport; we re-pin to bottom so the
     *  last message stays above the row. */
    working: Boolean = false,
): ChatScrollController {
    // Use a plain ScrollState + Column instead of LazyColumn so
    // SelectionContainer can extend selection across messages that are
    // currently off-screen. LazyColumn recycles off-screen items, which
    // disposes their Selectables — official Compose docs say "behavior
    // is undefined" for selection across lazy items. Tradeoff: every
    // message in the history is composed eagerly so initial paint of a
    // huge chat is slower than with LazyColumn. For our chat sizes
    // (typically tens to hundreds of messages, occasionally a few
    // thousand) it's acceptable; selection working across the whole
    // scrollback is the canonical UX users expect.
    // **LazyColumn anchor.** Search-opened chats land at the matched
    // message — anchored by stable msgId (not positional ordinal) so any
    // parsing jitter between SearchIndexer's pass and ChatViewModel's
    // pass can never resolve to the wrong message. The first-scroll
    // LaunchedEffect below resolves msgId → list index once messages
    // hydrate, then refines to line-precise centring (see [MatchAnchor]).
    // While that's happening LazyColumn is alpha-hidden — no visible
    // "list-at-top → jump-to-target" two-step. rememberSaveable + the
    // LazyListState.Saver keep position across config changes.
    val lazyListState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
        )
    }

    // "At bottom" is derived from layoutInfo, not a polled flag.
    // Cheap — LazyListState already tracks visible items.
    val isAtBottom by remember {
        derivedStateOf {
            val info = lazyListState.layoutInfo
            if (info.visibleItemsInfo.isEmpty()) return@derivedStateOf true
            // PIXEL-genuine bottom (nothing left to scroll), not "the last
            // item intersects the viewport": one CLI reply = ONE LazyColumn
            // item that is routinely several screens tall, so the old index
            // check (last.index >= total-1) read "at bottom" while the user
            // was parked SCREENS above the end inside the tail message —
            // re-enabling the streaming yank and wiping the reading anchor
            // on every settle there (2026-06-10).
            !lazyListState.canScrollForward
        }
    }
    // wasAtBottomSnapshot: the user's INTENT, captured at the end of
    // each settle. Search-opened chats start NOT-at-bottom; normal
    // opens start at-bottom (we scroll there once messages load).
    // ALSO start NOT-at-bottom when the VM carries a reading anchor — that means
    // we're re-mounting after a PiP minimize (this controller was disposed by
    // ChatScreen's PiP short-circuit) and the user was parked mid-history. If we
    // started at-bottom here, the streaming-follow effect would yank them to the
    // latest reply, fighting the anchor restore below.
    var wasAtBottomSnapshot by rememberSaveable {
        mutableStateOf(
            vm.readingAnchorMsgId.value == null &&
                vm.initialMatchOrdinal < 0 && vm.initialMatchMsgId == null,
        )
    }
    // Reading anchor: the id (+ pixel offset) of the message the user parked on
    // when NOT at the bottom. Seeded FROM THE VM (which outlives this composable)
    // so a minimize→restore — where the PiP short-circuit DISPOSED this whole
    // controller, losing its rememberSaveable — still returns the chat to EXACTLY
    // where they were reading instead of snapping to the first message. Cleared
    // (in the VM too) when they're at the bottom.
    var readingAnchorId by rememberSaveable { mutableStateOf(vm.readingAnchorMsgId.value) }
    var readingAnchorOffset by rememberSaveable { mutableStateOf(vm.readingAnchorOffset.value) }
    LaunchedEffect(Unit) {
        snapshotFlow { lazyListState.isScrollInProgress }
            // Skip the initial mount emission — it is NOT a settle (the
            // invariant says the snapshot updates only on true→false
            // transitions). Without this, every re-mount (rotation / PiP
            // expand) ran the body against a virgin list at (0,0) or an
            // unmeasured layout, stomping the restored/VM-seeded
            // wasAtBottomSnapshot + readingAnchorId AND writing the damage
            // through to the VM — the anchor-restore effect disarmed
            // itself and the mount-run streaming-follow yanked the chat to
            // the bottom.
            .drop(1)
            .collect { inProgress ->
                if (!inProgress) {
                    // Never evaluate a settle against an unmeasured list:
                    // empty visibleItemsInfo reads as "at bottom" and would
                    // wipe the anchor.
                    if (lazyListState.layoutInfo.visibleItemsInfo.isEmpty()) return@collect
                    wasAtBottomSnapshot = isAtBottom
                    if (isAtBottom) {
                        readingAnchorId = null
                    } else {
                        // First visible item's KEY == message id (items(key={it.id})),
                        // read off layoutInfo so we don't capture a stale `messages`
                        // snapshot in this long-lived collector.
                        readingAnchorId =
                            lazyListState.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String
                        readingAnchorOffset = lazyListState.firstVisibleItemScrollOffset
                    }
                    // Mirror to the VM (id + pixel offset) so (a) the PiP window
                    // renders the SAME line the user parked on and (b) the exact
                    // position survives the chat→PiP→chat swap that disposes this
                    // controller. null = they were at the bottom → PiP follows the
                    // latest, expand lands at the bottom.
                    vm.setReadingAnchor(readingAnchorId, readingAnchorOffset)
                }
            }
    }

    // First-scroll pipeline. Runs once when messages first become non-
    // empty, and re-fires when [matchedLineY] gets reported by the
    // matched message's Text via onTextLayout.
    //
    // Two phases (search-opened):
    //   1. Resolve msgId → ord via messages.indexOfFirst { it.id == mid }
    //      and call scrollToItem(ord) — brings the target into composition
    //      so its Text gets onTextLayout-called.
    //   2. Once [matchedLineY] arrives (line y inside the message text),
    //      compute scrollOffset = (lineY - viewportH/2).coerceAtLeast(0)
    //      and scrollToItem(ord, scrollOffset) — places the matched line
    //      at viewport centre.
    //
    // LazyColumn is alpha-hidden until [anchorApplied] flips true, so
    // the user never sees the intermediate "list at top" frame.
    //
    // Normal opens (no search anchor): one scrollToItem(lastIndex),
    // set anchorApplied.
    val hasSearchAnchor = vm.initialMatchOrdinal >= 0 || vm.initialMatchMsgId != null
    var anchorApplied by rememberSaveable {
        mutableStateOf(!hasSearchAnchor)
    }
    var matchedLineY by remember { mutableStateOf<Int?>(null) }

    // Resolve the matched message's ord in the current `messages` list.
    // Primary source: vm.initialMatchOrdinal (the URL `ord=` arg). This
    // is deterministic for any agent because the indexer and the chat
    // share the same parser spec — same JSONL → same ordinals.
    // Secondary: vm.initialMatchMsgId (for Claude stable IDs, helps if
    // parsing changes between index time and chat open shift the
    // ordinals by a small amount). Returns -1 when neither resolves.
    val targetOrd: Int = run {
        val urlOrd = vm.initialMatchOrdinal
        if (urlOrd in 0 until messages.size) {
            // Verify via msgId when available — if it matches, perfect.
            // If it doesn't, the ordinal is still our best guess (and
            // works for Codex/Gemini where msgId is a random UUID).
            return@run urlOrd
        }
        val mid = vm.initialMatchMsgId
        if (mid != null) {
            val byMid = messages.indexOfFirst { it.id == mid }
            if (byMid >= 0) return@run byMid
        }
        -1
    }

    // Char offset of the user-tapped occurrence inside the matched
    // message's body. Sourced from the URL `off=` int. Falls back to
    // the body's first match if no offset was carried.
    val matchCharOffset: Int = run {
        if (targetOrd < 0) return@run -1
        val msg = messages.getOrNull(targetOrd) ?: return@run -1
        val body = when (msg) {
            is AgentMessage.UserText -> msg.text
            is AgentMessage.AssistantText -> msg.text
            else -> return@run -1
        }
        val q = vm.initialSearchQuery ?: return@run -1
        val urlOff = vm.initialMatchCharOffset
        if (urlOff in 0..body.length) return@run urlOff
        body.indexOf(q, ignoreCase = true).coerceAtLeast(0)
    }

    val matchAnchor: MatchAnchor? = run {
        if (targetOrd < 0) return@run null
        val msg = messages.getOrNull(targetOrd) ?: return@run null
        if (vm.initialSearchQuery.isNullOrBlank()) return@run null
        if (matchCharOffset < 0) return@run null
        MatchAnchor(
            msgId = msg.id,
            charOffset = matchCharOffset,
            reportLayout = { result, prefixLen ->
                val target = (matchCharOffset + prefixLen)
                    .coerceIn(0, result.layoutInput.text.length.coerceAtLeast(0))
                val line = SilentlyTry.loggedOrElse("SshAi-Autoscroll", "getLineForOffset", 0) { result.getLineForOffset(target) }
                val y = SilentlyTry.loggedOrElse("SshAi-Autoscroll", "getLineTop", 0f) { result.getLineTop(line) }.toInt()
                if (matchedLineY != y) matchedLineY = y
            },
        )
    }

    LaunchedEffect(messages.size, matchedLineY) {
        if (anchorApplied) return@LaunchedEffect
        if (messages.isEmpty()) return@LaunchedEffect
        if (!hasSearchAnchor) {
            lazyListState.scrollToBottom(messages.size)
            anchorApplied = true
            android.util.Log.d(
                "SshAi-Hl",
                "first-scroll: normal open msgs=${messages.size} → last"
            )
            return@LaunchedEffect
        }
        val ord = targetOrd
        if (ord < 0) {
            android.util.Log.w(
                "SshAi-Hl",
                "first-scroll: target not in messages (size=${messages.size}, urlOrd=${vm.initialMatchOrdinal}, mid=${vm.initialMatchMsgId}) — falling back to last"
            )
            lazyListState.scrollToBottom(messages.size)
            anchorApplied = true
            return@LaunchedEffect
        }
        // Phase 1: bring the target into composition. scrollToItem is
        // an instant jump. After this, the matched message's Text runs
        // onTextLayout → matchedLineY gets set → this effect re-fires.
        if (lazyListState.firstVisibleItemIndex != ord ||
            lazyListState.firstVisibleItemScrollOffset != 0
        ) {
            lazyListState.scrollToItem(ord)
            kotlinx.coroutines.delay(16)
        }
        val lineY = matchedLineY ?: return@LaunchedEffect
        // Phase 2: line-precise centring. scrollOffset of N pixels
        // pushes the item's top N px above viewport top, so the line
        // sits at (lineY - N) below viewport top. Setting N = lineY -
        // viewportH/2 centres the line vertically.
        val info = lazyListState.layoutInfo
        val viewportH = info.viewportEndOffset - info.viewportStartOffset
        val scrollOffset = if (viewportH > 0) {
            (lineY - viewportH / 2).coerceAtLeast(0)
        } else 0
        lazyListState.scrollToItem(ord, scrollOffset)
        anchorApplied = true
        android.util.Log.d(
            "SshAi-Hl",
            "first-scroll: ord=$ord mid=${messages.getOrNull(ord)?.id} charOff=$matchCharOffset lineY=${lineY}px vh=${viewportH}px scrollOffset=$scrollOffset"
        )
    }

    // Failsafe: if the matched-message Text never reports layout (e.g.
    // odd renderer that skips onTextLayout, or the message dropped out
    // of the list) we still want the chat to BE VISIBLE. After 350 ms
    // give up on line-precise centring and reveal with whatever we
    // managed — at worst the user sees the message at viewport top.
    LaunchedEffect(messages.isNotEmpty(), hasSearchAnchor) {
        if (anchorApplied) return@LaunchedEffect
        if (messages.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(350)
        if (!anchorApplied) {
            android.util.Log.w("SshAi-Hl", "failsafe reveal: line-y never arrived")
            anchorApplied = true
        }
    }

    // Reading-position restore: after the messages list (re)hydrates — coming
    // back from background, or a process-death recreation — jump back to where
    // the user was reading. Runs ONCE. Only when they'd parked mid-history
    // (anchor set, not at bottom) on a non-search open; it beats the
    // streaming/contentSig "follow to bottom" effect (gated off because
    // wasAtBottomSnapshot was restored false). At-bottom / no-anchor opens fall
    // through to the normal bottom-anchored behavior. Fixes "minimize→reopen
    // jumps to the first message instead of where I was reading".
    var readingAnchorRestored by remember { mutableStateOf(false) }
    LaunchedEffect(messages.size) {
        if (readingAnchorRestored) return@LaunchedEffect
        if (messages.isEmpty()) return@LaunchedEffect
        val aid = readingAnchorId
        if (hasSearchAnchor || aid == null || wasAtBottomSnapshot) {
            readingAnchorRestored = true
            return@LaunchedEffect
        }
        val idx = messages.indexOfFirst { it.id == aid }
        android.util.Log.d(
            "SshAi-PiP",
            "anchor restore: aid=$aid off=$readingAnchorOffset idx=$idx/${messages.size}",
        )
        if (idx >= 0) {
            lazyListState.scrollToItem(idx, readingAnchorOffset)
            readingAnchorRestored = true
        }
        // Anchor message not in this emission yet → wait for the next.
    }

    // User-send trigger: when the LAST message flips to UserText (the
    // user just hit ✦ in the prompt bar), force autoscroll + flip the
    // wasAtBottom snapshot back on regardless of where the chat was
    // looking. Without this, a chat opened from a search hit stays
    // anchored at the matched message: wasAtBottomSnapshot started
    // false (we centred the match on entry), the user types something
    // — and the streaming-follow effect below stays gated off because
    // the snapshot never returned to true.
    val lastUserMsgId = remember(messages) {
        messages.lastOrNull { it is AgentMessage.UserText }?.id
    }
    LaunchedEffect(lastUserMsgId) {
        if (lastUserMsgId == null) return@LaunchedEffect
        if (!anchorApplied) return@LaunchedEffect
        if (messages.isEmpty()) return@LaunchedEffect
        // Only react when the new UserText IS the latest message —
        // not when an old user prompt happens to be the last
        // non-assistant chunk visible from a partial replay.
        if (messages.last() !is AgentMessage.UserText) return@LaunchedEffect
        wasAtBottomSnapshot = true
        lazyListState.scrollToBottom(messages.size)
    }

    // Streaming-follow: when content actually grows (new bubble OR
    // the last bubble's text length changed) AND the user was at the
    // bottom, animate to the last item. Markdown layout shifts that
    // don't change message count or last-text length are ignored.
    val contentSig: Pair<Int, Int> = run {
        val last = messages.lastOrNull()
        val lastLen = when (last) {
            is AgentMessage.UserText -> last.text.length
            is AgentMessage.AssistantText -> last.text.length
            is AgentMessage.ToolUse -> last.input.length
            is AgentMessage.ToolResult -> last.output.length
            is AgentMessage.Raw -> last.text.length
            is AgentMessage.Error -> last.text.length
            else -> 0
        }
        messages.size to lastLen
    }
    LaunchedEffect(contentSig) {
        if (!anchorApplied) return@LaunchedEffect
        if (messages.isEmpty()) return@LaunchedEffect
        if (!wasAtBottomSnapshot) return@LaunchedEffect
        lazyListState.scrollToBottom(messages.size)
    }

    // Working-row follow: the pinned status row sits just below the list, so when
    // a turn starts/ends the chat viewport shrinks/grows. Gated on the at-bottom
    // intent — never yanks someone reading scrollback.
    LaunchedEffect(working) {
        if (!anchorApplied) return@LaunchedEffect
        if (messages.isEmpty()) return@LaunchedEffect
        if (!wasAtBottomSnapshot) return@LaunchedEffect
        lazyListState.scrollToBottom(messages.size)
    }

    // Inline-image follow: images load asynchronously AFTER their message's
    // text (download + decode), and the bubble GROWS once the image is Ready.
    // contentSig (size, lastLen) doesn't change then, so the streaming-follow
    // effect above never fires and the freshly-tall image sits below the
    // fold. Re-scroll when the number of Ready inline images grows, IF the
    // user was anchored at the bottom (never yanks someone reading
    // scrollback).
    val inlineImages by vm.inlineImages.collectAsState()
    val readyImageCount = inlineImages.values.count {
        it is ai.eight24family.conch.ui.viewmodel.ChatViewModelDownloads.InlineImage.Ready
    }
    LaunchedEffect(readyImageCount) {
        if (!anchorApplied) return@LaunchedEffect
        if (messages.isEmpty()) return@LaunchedEffect
        if (!wasAtBottomSnapshot) return@LaunchedEffect
        lazyListState.scrollToBottom(messages.size)
    }

    // IME show: keep the prompt-bar-adjacent content in view. Search-
    // opened chats are read-mostly — IME (e.g. the SK touch dialog's
    // PIN pad) shouldn't yank them away from the highlighted match.
    val imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottomPx) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (cameFromSearch) return@LaunchedEffect
        if (imeBottomPx > 0) lazyListState.scrollToBottom(messages.size)
    }

    return ChatScrollController(
        lazyListState = lazyListState,
        anchorApplied = anchorApplied,
        targetOrd = targetOrd,
        matchCharOffset = matchCharOffset,
        matchAnchor = matchAnchor,
    )
}

/**
 * Scroll so the LAST item's BOTTOM sits at the viewport bottom — the true
 * bottom of the chat. Plain `scrollToItem(last)` only puts the last item's
 * TOP at the viewport top, leaving a gap below a short last message. A huge
 * scrollOffset scrolls as far as the content allows and Compose clamps it
 * to the real maximum = content bottom at viewport bottom. Used for every
 * "go to bottom" (open, send, streaming follow, IME, usage-panel lift).
 */
internal suspend fun LazyListState.scrollToBottom(itemCount: Int) {
    if (itemCount <= 0) return
    scrollToItem(itemCount - 1, 1_000_000)
}
