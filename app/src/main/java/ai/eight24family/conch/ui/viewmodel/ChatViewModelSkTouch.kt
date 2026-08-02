package ai.eight24family.conch.ui.viewmodel

import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.domain.SecurityKeyTransport
import ai.eight24family.conch.domain.SshKeySecurityInfo
import ai.eight24family.conch.ssh.securitykey.SecurityKeyNotifier
import ai.eight24family.conch.ssh.securitykey.SkSigner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The "Touch your security key" prompt that the chat screen renders. Public so it can
 * appear in the public `skTouchRequest` StateFlow signature exposed by ChatViewModel.
 *
 * The other ViewModels that drive their own SK touch flow (AgentPicker / Sessions /
 * Servers) keep their own nested SkTouchRequest data classes — those are not the
 * same type and switching them is outside this refactor's scope.
 */
data class ChatSkTouchRequest(
    val keyName: String,
    val transport: SecurityKeyTransport,
    val application: String,
    val credentialIdBase64: String,
)

/**
 * Security-key touch coordinator.
 *
 * The actual NFC race-condition logic is preserved verbatim — the IsoDep tag handle
 * is only valid INSIDE yubikit's `withNfc` callback so the dialog has to drive the
 * SSH op inline. ChatViewModel publishes a request to the screen via [skTouchRequest],
 * awaits a signer through a CompletableDeferred, then signals the screen when the op
 * is done so the NFC callback can be released.
 *
 * Phases (see [ChatSkTouchRequest] / `provideSkSignerForChatOpen` / `awaitSkOpDone`):
 *   1. [awaitSkSignerFromUi] publishes [ChatSkTouchRequest], posts the notifier, waits.
 *   2. UI taps a transport, builds an [SkSigner] inside `withNfc` callback, calls
 *      [provideSkSignerForChatOpen] which completes the inner deferred.
 *   3. Chat-open coroutine receives the signer, runs `agentSession.start()`.
 *   4. Chat-open coroutine calls [markSkOpDone] which releases the `withNfc` callback.
 *
 * See ChatViewModel.kt prior to extraction for the original inline comments.
 */
class ChatViewModelSkTouch(
    /** Server name/host for the notifier label; passed in as a getter so it reflects
     *  the latest value when the dialog appears (which can be after the VM has hydrated
     *  the server). */
    private val serverLabel: () -> String?,
) {
    /**
     * True when this VM is about to open an SSH session against a server keyed to a
     * FIDO security-key row. The ChatScreen renders a "Touch your security key" dialog
     * whenever this is non-null.
     */
    private val _skTouchRequest = MutableStateFlow<ChatSkTouchRequest?>(null)
    val skTouchRequest: StateFlow<ChatSkTouchRequest?> = _skTouchRequest.asStateFlow()

    /**
     * Used to be a CompletableDeferred-backed "give me a signer" pattern, but
     * that fell apart for NFC: the IsoDep tag handle is only valid INSIDE yubikit's
     * `withNfc` callback, so an signer captured outside dies before sshj's userauth
     * gets to use it. The new shape: pre-connect path publishes [ChatSkTouchRequest] to
     * the screen and awaits a `signerCompleted` deferred; the screen runs the actual
     * SSH op (chat session start) inline within a yubikit callback, calling
     * [provideSkSignerForChatOpen] to give us the signer + drive the open. We hold the
     * callback open for the duration of `agentSession.start()` (one handshake + one
     * userauth signature ≈ 1-3 seconds).
     */
    @Volatile private var pendingSkSignerOp: CompletableDeferred<SkSigner?>? = null

    @Volatile private var skOpDoneDeferred: CompletableDeferred<Unit>? = null

    /** Called by the touch dialog when it has a signer (USB or NFC). For NFC this
     *  must be invoked synchronously inside the yubikit callback so the IsoDep
     *  handle stays live. */
    fun provideSkSignerForChatOpen(signer: SkSigner) {
        pendingSkSignerOp?.complete(signer)
    }

    /** Suspend until the chat's pre-connect coroutine is done with the signer
     *  (i.e. agentSession.start() has returned). The dialog uses this to keep
     *  the NFC callback alive while sshj's userauth fires. */
    suspend fun awaitSkOpDone() {
        skOpDoneDeferred?.await()
    }

    fun cancelSkTouch() {
        pendingSkSignerOp?.complete(null)
        pendingSkSignerOp = null
        skOpDoneDeferred?.complete(Unit)
        skOpDoneDeferred = null
        _skTouchRequest.value = null
        SecurityKeyNotifier.cancel(ServiceLocator.appContext)
    }

    suspend fun awaitSkSignerFromUi(
        info: SshKeySecurityInfo,
    ): SkSigner? {
        val deferred = CompletableDeferred<SkSigner?>()
        val opDone = CompletableDeferred<Unit>()
        pendingSkSignerOp = deferred
        skOpDoneDeferred = opDone
        _skTouchRequest.value = ChatSkTouchRequest(
            keyName = "security key",
            transport = SecurityKeyTransport.EITHER,
            application = info.application,
            credentialIdBase64 = info.credentialIdBase64,
        )
        SecurityKeyNotifier.post(
            context = ServiceLocator.appContext,
            reason = SecurityKeyNotifier.Reason.CONNECT,
            transport = SecurityKeyTransport.EITHER,
            target = serverLabel(),
        )
        return deferred.await().also {
            // Don't null pendingSkSignerOp yet — caller will use the
            // signer to call agentSession.start(). When that returns,
            // markSkOpDone() releases the dialog's NFC callback.
        }
    }

    /**
     * Called by the chat-open coroutine immediately after the SSH handshake completes
     * and the signer is no longer needed. Releases the screen-side `withNfc` callback
     * so the NFC tag handle can be dropped (and the touch dialog auto-dismissed).
     */
    fun markSkOpDone() {
        skOpDoneDeferred?.complete(Unit)
        skOpDoneDeferred = null
        pendingSkSignerOp = null
        _skTouchRequest.value = null
        SecurityKeyNotifier.cancel(ServiceLocator.appContext)
    }
}
