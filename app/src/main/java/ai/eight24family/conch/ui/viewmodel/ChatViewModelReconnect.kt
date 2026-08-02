package ai.eight24family.conch.ui.viewmodel

import ai.eight24family.conch.agent.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Reconnect / stream-stall coordinator.
 *
 * Owns:
 *  - `_streamStalled` / `streamStalled` — sessionId whose assistant stream went silent in Working.
 *  - `lastStreamUpdate` — sessionId → epoch ms of last fresh content/state delta (driven from
 *    the collectors in [ChatViewModel.startNewChat]).
 *  - `_reconnecting` / `_reconnectAttempt` — exposed by the chat ViewModel so the top-bar
 *    can show a "reconnecting…" pill.
 *  - The scheduled `reconnectJob` that calls back into [retry] (a lambda the VM passes in).
 *
 * [shouldAutoRetry] is the policy gate — user-cancel and auth-failure reasons do NOT
 * loop us back into the touch dialog.
 *
 * See ChatViewModel.kt for the original inline implementation prior to extraction.
 */
internal class ChatViewModelReconnect(
    private val scope: CoroutineScope,
    private val retry: () -> Unit,
) {
    private val _streamStalled = MutableStateFlow<String?>(null)
    val streamStalled: StateFlow<String?> = _streamStalled.asStateFlow()

    /** sessionId → epoch millis of the last fresh content/state delta we saw. */
    val lastStreamUpdate: MutableMap<String, Long> = mutableMapOf()

    private val _reconnecting = MutableStateFlow(false)
    val reconnecting: StateFlow<Boolean> = _reconnecting.asStateFlow()

    private val _reconnectAttempt = MutableStateFlow(0)
    val reconnectAttempt: StateFlow<Int> = _reconnectAttempt.asStateFlow()

    private var reconnectJob: Job? = null

    fun setStreamStalled(value: String?) {
        _streamStalled.value = value
    }

    /**
     * Launch the two long-lived session-health watchers. Previously these
     * lived inline in `ChatViewModel.init{}`, but they're pure
     * reconnect/stall concerns — they belong here, next to the policy
     * ([shouldAutoRetry]) and the schedule ([scheduleReconnect]) they drive.
     * The ViewModel now just calls this once, passing its session-state
     * flows + the one side-effect it owns ([onRunning], which clears the
     * remote-active spinner via the tail-poll coordinator).
     *
     * Both loops run for the lifetime of [scope] (the ViewModel scope).
     *
     *  - **Stall watchdog**: every 5 s, any session still in
     *    [SessionState.Working] with no fresh content/state delta for longer
     *    than [stallTimeoutMs] gets flagged via [setStreamStalled] so the
     *    AssistantLine can offer a "tap to retry" pill. The startNewChat
     *    collectors bump [lastStreamUpdate] on every emission, so a healthy
     *    token stream never trips this — only a real transport stall does.
     *  - **Auto-reconnect**: on `Running` we reset attempts ([onSessionRunning])
     *    and fire [onRunning]; on `Failed` we reconnect iff [shouldAutoRetry].
     */
    fun installWatchdogs(
        state: StateFlow<SessionState>,
        stateBySession: StateFlow<Map<String, SessionState>>,
        stallTimeoutMs: Long,
        onRunning: () -> Unit,
    ) {
        scope.launch {
            while (true) {
                delay(5_000)
                val now = System.currentTimeMillis()
                // Iterate over a snapshot — collectors may insert keys concurrently.
                for ((sid, st) in stateBySession.value) {
                    if (st !is SessionState.Working) continue
                    val last = lastStreamUpdate[sid] ?: continue
                    if (now - last > stallTimeoutMs) {
                        if (_streamStalled.value != sid) setStreamStalled(sid)
                    }
                }
            }
        }
        scope.launch {
            state.collect { st ->
                when (st) {
                    is SessionState.Running -> {
                        onSessionRunning()
                        // Local turn just finished (Working → Running) —
                        // drop the spinner immediately. The next pgrep tick
                        // re-lights it if a sibling instance on the user's
                        // PC happens to still be working.
                        onRunning()
                    }
                    is SessionState.Failed ->
                        if (shouldAutoRetry(st.reason)) scheduleReconnect(st.reason)
                    else -> {}
                }
            }
        }
    }

    /** Called from the state collector on every Running tick — healthy again. */
    fun onSessionRunning() {
        if (_reconnectAttempt.value > 0) {
            android.util.Log.d("SshAi-Reconnect", "back to Running, resetting attempts")
        }
        _reconnectAttempt.value = 0
        _reconnecting.value = false
        reconnectJob?.cancel()
        reconnectJob = null
    }

    fun shouldAutoRetry(reason: String): Boolean {
        val r = reason.lowercase()
        // Things we shouldn't auto-retry on (config errors, missing CLI):
        if (r.contains("not on path")) return false
        if (r.contains("missing")) return false
        if (r.contains("server not found")) return false
        if (r.contains("authentication")) return false
        if (r.contains("auth failed")) return false
        // Hardware-token "I cancelled" is a deliberate user action —
        // looping back into the touch dialog would lock the user out
        // of every other screen until they either complete or
        // force-stop the app. They'll re-tap retry from the chat
        // header when they're ready.
        if (r.contains("touch cancelled")) return false
        if (r.contains("security-key signer not provided")) return false
        if (r.contains("security key signer")) return false
        return true
    }

    fun scheduleReconnect(reason: String) {
        if (reconnectJob?.isActive == true) return
        val attempt = _reconnectAttempt.value + 1
        _reconnectAttempt.value = attempt
        _reconnecting.value = true
        // 1s, 2s, 5s, 10s, 30s, 30s, 30s...
        val delays = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)
        val waitMs = delays.getOrElse(attempt - 1) { 30_000L }
        android.util.Log.d(
            "SshAi-Reconnect",
            "scheduling reconnect attempt=$attempt in ${waitMs}ms (reason=$reason)"
        )
        reconnectJob = scope.launch {
            delay(waitMs)
            android.util.Log.d("SshAi-Reconnect", "firing retry, attempt=$attempt")
            retry()
        }
    }

    /**
     * Clear the stream-stalled flag so the UI hides the "Stream paused"
     * pill. Bumps lastStreamUpdate so the watchdog doesn't immediately
     * re-flag the same session, giving the user a clean ~30 s grace
     * window to see whether the transport unsticks on its own.
     */
    fun retryStream(sid: String?) {
        if (sid == null) {
            _streamStalled.value = null
            return
        }
        _streamStalled.value = null
        lastStreamUpdate[sid] = System.currentTimeMillis()
    }
}
