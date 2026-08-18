package ai.eight24family.conch.ui.viewmodel

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.SessionState
import ai.eight24family.conch.ui.haptic.SshAiHaptic
import ai.eight24family.conch.ui.haptic.SshAiHaptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Turn haptics for one chat.
 *
 * ## Why this is not in ChatScreen any more
 *
 * It used to live in two `LaunchedEffect`s inside `ChatScreen`, which made the
 * buzz conditional on the chat being COMPOSED.
 *
 *  1. **Picture-in-Picture.** `ChatScreen` returns early when `isInPip` — and it
 *     does so ABOVE where the haptic effects were declared. Swipe home (the
 *     exact moment you stop looking and start relying on feel) and haptics were
 *     off entirely.
 *  2. **The row gate.** Per-row buzzing was gated on `working`, sampled at the
 *     moment the row arrived. The FINAL assistant row of a turn routinely lands
 *     in the same breath as the turn ending, so the most important buzz of all
 *     — the answer — was the one most likely to be skipped.
 *  3. **Leaving the chat's composition** for any other reason (a dialog route, a
 *     viewer) stopped the stream haptics mid-turn.
 *
 * The ViewModel outlives all three: it is scoped to the nav entry, survives the
 * PiP round-trip (Root keeps the NavHost composed) and keeps collecting with the
 * screen off. Haptics themselves come from the app-scoped
 * [ai.eight24family.conch.di.ServiceLocator.haptics], and the Settings toggle is
 * still honoured inside [SshAiHaptics.perform], so there is nothing to check
 * here.
 *
 * Preserves the shipped design (INVARIANT STREAM-HAPTICS-1, 2026-06-28): a new
 * tool row is a [SshAiHaptic.Tick], a new assistant row is a
 * [SshAiHaptic.Tap], and the seed guard means opening a chat never buzzes
 * through existing history. What changed: the turn END is now a
 * [SshAiHaptic.TurnEnd] — three long pulses instead of the old double-tick,
 * which was too close to an ordinary UI ack to feel from a pocket.
 */
internal class ChatViewModelHaptics(
    private val scope: CoroutineScope,
    /**
     * Where a buzz goes. A function, not the [SshAiHaptics] object, for one
     * reason: this class had to be force-stopped by the user once, and a rule
     * that can do that must be unit-testable without an Android Vibrator.
     * Production passes `ServiceLocator.haptics::perform`.
     */
    private val perform: (SshAiHaptic) -> Unit,
    /**
     * The clock. Injectable because every rule in this class is a TIME rule —
     * "held for a settle window", "at most once per turn", "a turn ran long
     * enough" — and a rule that once vibrated until the app had to be killed is
     * not one to verify by hand on a phone. Tests drive it from the virtual
     * scheduler so the windows are exercised exactly.
     */
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val seen = HashSet<String>()
    private var seeded = false
    // ⚠ NULL means "never", not 0. Zero IS a valid timestamp — and treating it
    // as "long ago" is the kind of sentinel that works only because a wall clock
    // happens to be a big number.
    private var lastPulseMs: Long? = null
    private var lastTurnEndMs: Long? = null
    private var wasWorking = false

    /**
     * A batch bigger than this is a history LOAD (cache hydration, a mirrored
     * turn's backlog, a reconnect re-paint), not an answer arriving — buzzing
     * once per row would be a burst of nonsense. Streaming appends arrive in
     * ones and twos, so the cap separates the two cleanly.
     */
    private val burstCap = 5

    /** Minimum gap between pulses. The vibrator queues effects, so a fast
     *  tool-and-text pair without this reads as one long mush. */
    private val minGapMs = 70L

    /** A turn can report its end more than once (result event plus the state
     *  machine settling). The user must feel that once. */
    private val turnEndDebounceMs = 2_000L

    /**
     * How long "not working" must HOLD before it counts as the answer being
     * finished.
     *
     * ⚠ THIS IS THE WHOLE POINT, DO NOT DROP IT TO ZERO.
     *
     * "Working" is the OR of two signals that hand off to each other: our own
     * `SessionState.Working` (immediate, from the stream we drive) and the file
     * mirror's `remoteFileOpen` (authoritative for a turn running server-side,
     * but only refreshed on the tail-poll tick, ~7 s apart). Between our state
     * settling off Working and the poller confirming the turn is still in
     * flight, the OR is momentarily FALSE — a handoff gap, not an ending.
     *
     * Firing on the raw falling edge buzzed three long pulses the instant the
     * user pressed send, and then the chat sat there looking dead — while the
     * turn was in fact running (measured on the device: `inFlight=true`, tokens
     * 1960→3155, session file growing, 2026-08-18). The old double-tick had the
     * same false edge and got away with it by being nearly imperceptible; making
     * the signal loud made the lie loud.
     *
     * So: arm on the falling edge, and only buzz if nothing re-raised `working`
     * within this window. Longer than a poll gap would be too slow to feel like
     * a completion; shorter than a state-settle race is no guard at all.
     */
    private val turnEndSettleMs = 2_500L

    /** Minimum time a turn must have been running before its end is worth a
     *  buzz. Stops a state blip on connect/adopt from announcing an "answer"
     *  that never happened. */
    private val minTurnMs = 1_000L

    private var workingSinceMs: Long? = null
    private var pendingEnd: kotlinx.coroutines.Job? = null

    /**
     * Already announced the end of the CURRENT turn.
     *
     * ⛔ THE HARD STOP. WITHOUT THIS, ONE BAD ROW BECOMES AN ENDLESS BUZZ.
     *
     * Keying the completion haptic off the arrival of an [AgentMessage.TurnEnd]
     * row assumed row ids are stable. They are not: `turnEnd()` mints a random
     * uuid, so every RE-PARSE of the same `result` record — a tail-poll tick that
     * re-reads a frozen file, a reconnect re-paint, a rescue reload — produced
     * another "new" end-of-turn row. The 2 s debounce then metered it into a
     * triple pulse every two seconds, forever, at full amplitude, from every
     * chat ViewModel alive in the back stack at once. The user had to force-stop
     * the app (2026-08-18).
     *
     * The id is stable now, but the lesson is that a haptic must not be able to
     * repeat because a message repeated. So the rule is a state one and cannot
     * loop by construction: AT MOST ONE completion buzz per turn, and only a new
     * turn (`working` going true) re-arms it.
     */
    private var announcedThisTurn = false

    /** When `working` last went false; null until it has. Row haptics keep firing
     *  for a grace tail after that — see [turnRecentlyMs]. */
    private var workingUntilMs: Long? = null

    /**
     * How long after a turn ends new rows still count as part of it.
     *
     * ⚠ THERE HAS TO BE SOME GATE. Dropping it entirely (the first cut of this
     * class) meant ANY small batch buzzed with nothing running: opening a chat
     * that was continued on the PC and letting the tail-poll append its two or
     * three new rows, a reconnect re-paint, a late JSONL echo of a tool result.
     * But gating strictly on `working` is what made the ANSWER — the final row,
     * which lands with or just after the turn ending — the least reliable buzz in
     * the app. So: a turn in flight, or one that ended a moment ago.
     */
    private val turnRecentlyMs = 4_000L

    fun install(
        messages: StateFlow<List<AgentMessage>>,
        state: StateFlow<SessionState>,
        remoteWorking: StateFlow<Boolean>,
    ) {
        scope.launch {
            messages.collect { list -> onMessages(list) }
        }
        scope.launch {
            combine(state, remoteWorking) { st, remote ->
                st is SessionState.Working || remote
            }.collect { working -> onWorking(working) }
        }
    }

    private fun onMessages(list: List<AgentMessage>) {
        if (!seeded) {
            // Seed on the FIRST non-empty history so opening a chat never buzzes
            // through what is already there. An empty chat stays unseeded, which
            // is right: its first row IS new.
            if (list.isEmpty()) return
            list.forEach { seen.add(it.id) }
            seeded = true
            return
        }
        // Walk from the newest backwards and stop at the first row we have
        // already seen — new rows are appended at the tail, so this is O(new)
        // per emission rather than O(history).
        val fresh = ArrayList<AgentMessage>()
        for (m in list.asReversed()) {
            if (!seen.add(m.id)) break
            fresh += m
        }
        if (fresh.isEmpty()) return
        if (fresh.size > burstCap) return
        // Rows only buzz while a turn is in flight — or just after one ended, so
        // the answer itself still lands. Outside that, an append is catch-up, not
        // an answer. The TurnEnd marker below is exempt: it IS the end.
        val turnRecent = wasWorking ||
            workingUntilMs?.let { now() - it < turnRecentlyMs } == true
        // Oldest first, so a tool-then-text pair feels in the order it happened.
        for (m in fresh.asReversed()) {
            when (m) {
                is AgentMessage.ToolUse, is AgentMessage.ToolResult ->
                    if (turnRecent) pulse(SshAiHaptic.Tick)
                is AgentMessage.AssistantText ->
                    if (turnRecent) pulse(SshAiHaptic.Tap)
                // The AUTHORITATIVE end of a turn — the parser's non-rendering
                // marker, emitted from the CLI's own `result`/`error` record and
                // ordered after the final text. Preferred over the state edge:
                // it cannot be faked by the handoff gap between our own
                // `Working` and the file mirror's flag, and it arrives at once
                // instead of after the settle window. The state edge stays as
                // the fallback for paths that produce no marker.
                is AgentMessage.TurnEnd -> confirmTurnEnd()
                else -> Unit
            }
        }
    }

    /** A turn is definitively over. Cancels any pending edge-based guess. */
    private fun confirmTurnEnd() {
        pendingEnd?.cancel()
        pendingEnd = null
        // Only if a turn was actually observed in this chat — a marker replayed
        // by a history load must not announce an answer that already happened
        // (the burst cap above drops big reloads, this covers the rest).
        if (workingSinceMs == null) return
        // The end of a turn that ISN'T running any more, and hasn't been running
        // recently, is a replay of an old record, not news.
        val recently = workingUntilMs?.let { now() - it < turnRecentlyMs } == true
        if (!wasWorking && !recently) return
        announce()
    }

    /** Fire the completion buzz — at most once per turn, ever. */
    private fun announce() {
        if (announcedThisTurn) return
        val now = now()
        if (lastTurnEndMs?.let { now - it < turnEndDebounceMs } == true) return
        announcedThisTurn = true
        lastTurnEndMs = now
        perform(SshAiHaptic.TurnEnd)
    }

    private fun onWorking(working: Boolean) {
        val now = now()
        if (working) {
            // Back to working — either a new turn, or the file mirror catching up
            // after our own state settled. Either way the pending "it finished"
            // buzz was wrong: cancel it.
            pendingEnd?.cancel()
            pendingEnd = null
            if (!wasWorking) {
                workingSinceMs = now
                // A NEW turn is the only thing that re-arms the completion buzz.
                announcedThisTurn = false
            }
        } else if (wasWorking) {
            workingUntilMs = now
            val ranFor = workingSinceMs?.let { now - it } ?: 0L
            if (ranFor >= minTurnMs) {
                pendingEnd?.cancel()
                pendingEnd = scope.launch {
                    kotlinx.coroutines.delay(turnEndSettleMs)
                    // Survived the settle window with nothing re-raising
                    // `working`, so the turn really is over.
                    announce()
                }
            }
        }
        wasWorking = working
    }

    private fun pulse(intent: SshAiHaptic) {
        val now = now()
        if (lastPulseMs?.let { now - it < minGapMs } == true) return
        lastPulseMs = now
        perform(intent)
    }
}
