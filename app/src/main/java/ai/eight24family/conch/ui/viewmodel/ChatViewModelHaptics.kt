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
    private val haptics: SshAiHaptics,
) {
    private val seen = HashSet<String>()
    private var seeded = false
    private var lastPulseMs = 0L
    private var lastTurnEndMs = 0L
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
        // Oldest first, so a tool-then-text pair feels in the order it happened.
        for (m in fresh.asReversed()) {
            when (m) {
                is AgentMessage.ToolUse, is AgentMessage.ToolResult -> pulse(SshAiHaptic.Tick)
                // No `working` gate. The final row of a turn arrives with (or
                // just after) the turn ending, and gating on state is what made
                // the answer itself the least reliable buzz in the app.
                is AgentMessage.AssistantText -> pulse(SshAiHaptic.Tap)
                else -> Unit
            }
        }
    }

    private fun onWorking(working: Boolean) {
        if (wasWorking && !working) {
            val now = System.currentTimeMillis()
            if (now - lastTurnEndMs >= turnEndDebounceMs) {
                lastTurnEndMs = now
                haptics.perform(SshAiHaptic.TurnEnd)
            }
        }
        wasWorking = working
    }

    private fun pulse(intent: SshAiHaptic) {
        val now = System.currentTimeMillis()
        if (now - lastPulseMs < minGapMs) return
        lastPulseMs = now
        haptics.perform(intent)
    }
}
