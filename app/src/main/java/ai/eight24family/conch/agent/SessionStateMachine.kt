package ai.eight24family.conch.agent

/**
 * Pure-Kotlin transition function for [SessionState]. No Android,
 * coroutines, or sshj — just `(current, event) → next`. Lets the
 * legal-vs-illegal transitions be locked down by JUnit tests
 * without spinning up the full `AgentSession` (which depends on
 * `Server`, `ServerSecrets`, `SshClient`, plus a CoroutineScope).
 *
 * Extracted as part of issue #11 so the state-machine contract has
 * its own home and its own test surface. `AgentSession` and
 * `ChatViewModel` continue to manage their own StateFlows directly
 * — this object is the canonical decision table they can defer to
 * when a transition is non-trivial (e.g. "disconnected" during a
 * turn vs. while idle).
 *
 * Invariants the table enforces:
 *
 *   1. **Failed and Closed are terminal-ish.** Once Failed, only
 *      an explicit Reset (user re-attempt / chat reopen) can leave
 *      it. Once Closed, same — the AgentSession is GC-eligible.
 *      Both states ignore turn / disconnect events.
 *
 *   2. **TurnStart only legal from Running.** From Bootstrapping
 *      it's silently swallowed (drain buffer logic upstream will
 *      retry once Running). From Working / Idle / Failed / Closed
 *      it's a no-op.
 *
 *   3. **TurnEnd from Working → Running.** Mirrors the
 *      `if (_state.value is Working) _state.value = Running` snippet
 *      that AgentSession runs after `runOneShot` exits.
 *
 *   4. **Disconnect from anything live → Failed("disconnected").**
 *      Matches the wire behaviour: if the SSH transport dies
 *      mid-turn, we surface a Failed state with a stable reason so
 *      the chat shows the right banner.
 */
object SessionStateMachine {

    /**
     * Events that drive state transitions. Names mirror what
     * actually happens in [AgentSession]:
     *
     *  - [Bootstrap] — SSH connect + CLI check, with a per-step
     *    label so the topbar can show "connecting" → "checking codex".
     *  - [Connected] — handshake done, CLI handshake passed.
     *  - [TurnStart] — user pressed send (or drain coroutine fired).
     *  - [TurnEnd] — CLI process exited 0.
     *  - [Fail] — anything threw on the way up.
     *  - [Disconnect] — transport dropped from under us.
     *  - [Close] — explicit user / VM teardown.
     *  - [Reset] — user retry from a Failed state.
     */
    sealed interface Event {
        data class Bootstrap(val step: String) : Event
        data object Connected : Event
        data object TurnStart : Event
        data object TurnEnd : Event
        data class Fail(val reason: String) : Event
        data object Disconnect : Event
        data object Close : Event
        data object Reset : Event
    }

    /**
     * May a chat ADOPT a session sitting in [state] as its live session?
     *
     * "The transport is up" ([AgentSession.isAlive]) is not the same question,
     * and answering with it is what produced the 2026-08-16 reconnect livelock.
     * `isAlive()` reads `liveClient()`, which RE-BINDS a session onto whatever
     * transport the pool currently holds — so a session left behind in
     * `Failed("disconnected")` starts reporting "alive" the instant the pool
     * reconnects, even though nothing restarted it and its state is terminal.
     *
     * Adoptable = the session can still carry a turn: `Idle`, `Bootstrapping`,
     * `Running`, `Working`. `Failed`/`Closed` are terminal here — only an
     * explicit [Event.Reset] (a rebuild) leaves them, so adopting one hands the
     * chat a state it can never move out of.
     */
    fun isAdoptable(state: SessionState): Boolean = when (state) {
        is SessionState.Failed, SessionState.Closed -> false
        SessionState.Idle, is SessionState.Bootstrapping,
        SessionState.Running, SessionState.Working -> true
    }

    fun transition(current: SessionState, event: Event): SessionState = when (event) {
        is Event.Bootstrap -> when (current) {
            // Bootstrap can fire from Idle (cold start), Bootstrapping
            // (next step), or Failed/Closed (retry after reset). It
            // should NOT yank a live session out from under itself —
            // a stale Bootstrap arriving while Running/Working is a bug.
            SessionState.Idle,
            is SessionState.Bootstrapping -> SessionState.Bootstrapping(event.step)
            SessionState.Running, SessionState.Working,
            is SessionState.Failed, SessionState.Closed -> current
        }

        Event.Connected -> when (current) {
            is SessionState.Bootstrapping -> SessionState.Running
            // Idempotent — re-emitting Connected from Running is fine.
            SessionState.Running, SessionState.Working -> current
            // Failed/Closed/Idle: ignore (a delayed Connected from a
            // dead coroutine shouldn't resurrect the session).
            SessionState.Idle, is SessionState.Failed, SessionState.Closed -> current
        }

        Event.TurnStart -> when (current) {
            SessionState.Running -> SessionState.Working
            // From Bootstrapping: don't flip — drain coroutine
            // upstream re-fires TurnStart once Running. From
            // Working: idempotent (additional sends queue in the
            // CLI's own buffer). From Failed/Closed/Idle: dropped.
            else -> current
        }

        Event.TurnEnd -> when (current) {
            SessionState.Working -> SessionState.Running
            else -> current
        }

        is Event.Fail -> when (current) {
            // Failure can hit at any phase, *including* an already-Failed
            // state (re-failing during retry). Closed stays Closed —
            // failing a dead session is meaningless.
            SessionState.Closed -> SessionState.Closed
            else -> SessionState.Failed(event.reason)
        }

        Event.Disconnect -> when (current) {
            // Live-state disconnect → Failed("disconnected"). Already-
            // Failed/Closed/Idle ignore (we don't want to overwrite a
            // useful Failed reason with the generic "disconnected").
            is SessionState.Bootstrapping,
            SessionState.Running,
            SessionState.Working -> SessionState.Failed("disconnected")
            SessionState.Idle, is SessionState.Failed, SessionState.Closed -> current
        }

        Event.Close -> when (current) {
            // Closing a Failed session keeps the Failed reason
            // visible — the UI surfaces it in the chat header.
            // Anything else collapses to Closed.
            is SessionState.Failed -> current
            else -> SessionState.Closed
        }

        Event.Reset -> when (current) {
            // Reset only resurrects Failed sessions (user retry).
            // From Closed or any live state it's a no-op.
            is SessionState.Failed -> SessionState.Idle
            else -> current
        }
    }
}
