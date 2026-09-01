package ai.eight24family.conch.ssh.securitykey

import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.fido.CtapException
import com.yubico.yubikit.core.fido.FidoConnection
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.fido.ctap.ClientPin
import com.yubico.yubikit.fido.ctap.Ctap2Session
import com.yubico.yubikit.fido.ctap.PinUvAuthProtocol
import com.yubico.yubikit.fido.ctap.PinUvAuthProtocolV1
import com.yubico.yubikit.fido.ctap.PinUvAuthProtocolV2
import com.yubico.yubikit.fido.webauthn.AuthenticatorData
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Base64

/**
 * Sign an SSH authentication challenge using a FIDO security key.
 *
 * The contract is intentionally narrow: caller hands us a 32-byte
 * `clientDataHash` (which in the SSH SK protocol is `SHA-256` of the
 * userauth signing payload), we wake the token, ask it to authenticate
 * with the previously-registered credential, and hand back the bits the
 * SSH wire format wants:
 *
 *  - `rawSignature` — for Ed25519 it's the 64 raw bytes; for ECDSA
 *    P-256 it's the ASN.1 DER `SEQUENCE { INTEGER r, INTEGER s }` the
 *    token produces (caller decodes if it needs r/s separately).
 *  - `flags` — single byte from `authenticatorData[32]`.
 *  - `counter` — big-endian uint32 from `authenticatorData[33..37]`.
 *
 * The signer is a one-shot object — keep a reference only as long as
 * the SSH handshake. After the user lifts an NFC card or unplugs USB,
 * subsequent calls fail.
 */
interface SkSigner {
    /** Identifies the originally-registered credential. Replayed in the
     *  CTAP2 `allowList` so the token knows which key handle to use. */
    val credentialIdBase64: String
    /** Same `application` (rpId) we registered with — `ssh:` by default. */
    val application: String

    /**
     * Block on token I/O until the user touches the security key or
     * the operation times out / errors. Throws [SkAuthException] on any
     * failure that can't be retried at this layer (no PIN handling
     * here — surface failures up so the UI can decide what to show).
     */
    @Throws(SkAuthException::class, IOException::class)
    fun sign(clientDataHash: ByteArray): SkSignResult
}

class SkAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class SkSignResult(
    val rawSignature: ByteArray,
    val flags: Byte,
    val counter: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SkSignResult) return false
        return rawSignature.contentEquals(other.rawSignature) &&
            flags == other.flags && counter == other.counter
    }
    override fun hashCode(): Int =
        rawSignature.contentHashCode() * 31 * 31 + flags.hashCode() * 31 + counter
}

/**
 * Concrete [SkSigner] that drives a YubiKey-style FIDO authenticator
 * (any vendor) via yubikit. Whatever connection the [device] supports —
 * USB-HID FIDO or NFC ISO-DEP — we open it on each `sign()` call,
 * dispatch a CTAP2 `getAssertion`, and close cleanly.
 *
 * The `getAssertion` call BLOCKS until the user touches the
 * authenticator (or it times out at the token's discretion — typically
 * 30 seconds). The blocking thread comes from sshj's reader; that's
 * acceptable because nothing else interesting happens on the SSH
 * connection during an auth challenge.
 */
class YubikitSkSigner(
    private val device: YubiKeyDevice,
    override val credentialIdBase64: String,
    override val application: String,
) : SkSigner {

    override fun sign(clientDataHash: ByteArray): SkSignResult {
        val tag = "Conch-SK-Sign"
        android.util.Log.d(tag, "sign: enter (challenge=${clientDataHash.size}B credId=${credentialIdBase64.length}B64)")
        if (clientDataHash.size != 32) {
            throw SkAuthException("clientDataHash must be 32 bytes, got ${clientDataHash.size}")
        }
        val credId = Base64.getDecoder().decode(credentialIdBase64)
        val allowList = listOf(
            mapOf<String, Any>(
                "type" to "public-key",
                "id" to credId,
            )
        )

        return runOnSession(device) { session ->
            val assertions: List<Ctap2Session.AssertionData> = try {
                session.getAssertions(
                    application,
                    clientDataHash,
                    allowList,
                    null, null, null, null, null,
                )
            } catch (e: CtapException) {
                throw mapCtapErrorTopLevel(e)
            }
            val first = assertions.firstOrNull()
                ?: throw SkAuthException("token returned no assertion")
            val authData = AuthenticatorData.parseFrom(ByteBuffer.wrap(first.authenticatorData))
            android.util.Log.d(tag, "  getAssertions ok: sig=${first.signature.size}B counter=${authData.signCount}")
            SkSignResult(
                rawSignature = first.signature,
                flags = authData.flags,
                counter = authData.signCount,
            )
        }
    }

    private fun <T> runOnSession(device: YubiKeyDevice, block: (Ctap2Session) -> T): T {
        return when {
            device.supportsConnection(FidoConnection::class.java) -> {
                device.openConnection(FidoConnection::class.java).use { conn ->
                    Ctap2Session(conn).use(block)
                }
            }
            device.supportsConnection(SmartCardConnection::class.java) -> {
                device.openConnection(SmartCardConnection::class.java).use { conn ->
                    Ctap2Session(conn).use(block)
                }
            }
            else -> throw SkAuthException("device offers neither FIDO nor smart-card transport")
        }
    }
}

/**
 * Pick the strongest PIN/UV-auth protocol the token supports.
 */
internal fun pickPinProtocolFor(session: Ctap2Session): PinUvAuthProtocol? {
    val supported = session.cachedInfo.pinUvAuthProtocols ?: return null
    return when {
        supported.contains(2) -> PinUvAuthProtocolV2()
        supported.contains(1) -> PinUvAuthProtocolV1()
        else -> null
    }
}

/**
 * Shared state across N [DeferredCtapSkSigner]s that all draw from the
 * SAME Ctap2Session opened on a single tap. The auth flow registers one
 * [SkAuthPublickey] per public key enrolled for the server, each with
 * its own credential id; sshj walks them in order. The user only taps
 * the key ONCE — the holder hands the same session to every signer in
 * turn until the server picks one (or all fail).
 *
 * Stripped of recovery state on 2026-05-10: no cachedSession (sign() can
 * keep the session alive via signDone), no cachedPin (we don't re-mint
 * for different rpIds — every sibling shares one rpId in the connect
 * path), no touch-only fallback.
 */
class SkSessionHolder {
    enum class Phase { WaitingForSsh, WaitingForTap, TagCaptured, WaitingForPin, Done, Failed }

    val phase = kotlinx.coroutines.flow.MutableStateFlow(Phase.WaitingForSsh)

    private val sessionReady = kotlinx.coroutines.CompletableDeferred<Ctap2Session>()
    @Volatile private var pinReady = kotlinx.coroutines.CompletableDeferred<CharArray?>()
    val signDone = kotlinx.coroutines.CompletableDeferred<Unit>()

    /** Wrong-PIN signal for the dialog. `tick` increments on each rejected
     *  PIN (drives the shake + 0.3 s buzz); `retriesLeft` is the token's
     *  remaining attempts from `getPinRetries` for the "N / 8" counter
     *  (null when unknown). A wrong PIN is recoverable IN PLACE — the key
     *  is still held, so we re-arm the PIN wait and re-prompt in the same
     *  pad, no re-tap, no "Connect failed". */
    data class WrongPinState(val tick: Int, val retriesLeft: Int?)
    val wrongPin = kotlinx.coroutines.flow.MutableStateFlow<WrongPinState?>(null)

    /** Re-arm PIN entry after a wrong attempt: fresh deferred so [awaitPin]
     *  blocks again on the next typed PIN, bump the wrong-PIN signal, and
     *  put the dialog back into PIN-entry. */
    fun noteWrongPin(retriesLeft: Int?) {
        pinReady = kotlinx.coroutines.CompletableDeferred()
        wrongPin.value = WrongPinState((wrongPin.value?.tick ?: 0) + 1, retriesLeft)
        phase.value = Phase.WaitingForPin
    }

    /** Cached after the first PIN handshake. Reused by sibling signers
     *  so the user types the PIN only once even when N enrolled keys
     *  fall through sshj's method walk before the server picks one. */
    @Volatile var cachedPinToken: ByteArray? = null
    @Volatile var cachedPinProtocol: PinUvAuthProtocol? = null

    /** Raw PIN chars retained between siblings so each can re-mint a
     *  fresh pinToken on demand. */
    @Volatile var cachedPin: CharArray? = null

    /** All credentialIds enrolled for this server (set by the pool). */
    @Volatile var candidateCredIds: List<ByteArray> = emptyList()

    /** Set by the dialog AFTER credentialManagement.enumerate runs —
     *  whichever credentialId the touched token actually has for
     *  [application]. The pool then builds sshj's auth method list
     *  with EXACTLY this one signer; sshj sends a single pubkey
     *  query and signer.sign() runs once. Avoids Yubico's
     *  one-getAssertion-per-session firmware quirk by doing zero
     *  probe-getAssertion calls. */
    @Volatile var matchedCredId: ByteArray? = null
    /** rpId / application of the matched credential (might differ from
     *  the original signer's `application` if recovery enrolled a
     *  brand-new credential under a different rpId). */
    @Volatile var matchedApplication: String? = null

    /** Server id, set by the pool. The dialog uses this to attach
     *  freshly-enumerated credentials to the right server row. */
    @Volatile var serverId: String? = null

    /** rpId / application for THIS server's auth, set by the pool. The
     *  dialog filters enumerated credentials by this string. */
    @Volatile var application: String = "ssh:"

    /** Signaled by the dialog once enumerate+attach+match-pick is done.
     *  The pool blocks on this BEFORE building methods + calling auth,
     *  so the method list reflects any new credentials we just learned
     *  about on the token. */
    val tokenCredsReady = kotlinx.coroutines.CompletableDeferred<Unit>()

    fun markTagCaptured() { phase.value = Phase.TagCaptured }
    fun provideSession(s: Ctap2Session) {
        if (!sessionReady.isCompleted) sessionReady.complete(s)
    }
    fun providePin(chars: CharArray?) { if (!pinReady.isCompleted) pinReady.complete(chars) }
    val pinSubmitted: Boolean get() = pinReady.isCompleted

    fun awaitSession(): Ctap2Session = kotlinx.coroutines.runBlocking { sessionReady.await() }
    fun awaitPin(): CharArray? = kotlinx.coroutines.runBlocking { pinReady.await() }

    fun cancel(reason: String = "user cancelled") {
        phase.value = Phase.Failed
        if (!sessionReady.isCompleted) sessionReady.completeExceptionally(SkAuthException(reason))
        if (!pinReady.isCompleted) pinReady.complete(null)
        if (!signDone.isCompleted) signDone.complete(Unit)
        // CRITICAL: also abort tokenCredsReady. The SSH-auth thread in
        // SshConnectionPool.openAndAuthenticate blocks on
        // `runBlocking { sharedHolder.tokenCredsReady.await() }` waiting
        // for the dialog to finish enumerate+attach+match. If the dialog
        // fails BEFORE setting tokenCredsReady (TagLost during the brief
        // window between tag capture and Ctap2Session INIT being the
        // common case), the SSH thread is stuck forever, opJob never
        // throws, runDeferredEitherAttempt's catch never fires, the
        // classifier never runs, attempt never bumps, and any subsequent
        // user tap goes into a black hole until the 90 s AgentPicker
        // watchdog finally cancels everything.
        if (!tokenCredsReady.isCompleted) tokenCredsReady.completeExceptionally(SkAuthException(reason))
        wipePin()
    }

    /** Zero out cached PIN bytes + pinToken. Call once per holder
     *  lifecycle (after final sign success, or on cancel). */
    fun wipePin() {
        cachedPin?.let { for (i in it.indices) it[i] = ' ' }
        cachedPin = null
        cachedPinToken?.let { for (i in it.indices) it[i] = 0 }
        cachedPinToken = null
        cachedPinProtocol = null
    }
}

/**
 * `SkSigner` whose [sign] BLOCKS until something feeds it a live
 * [Ctap2Session] via [provideSession]. Designed for the NFC flow where
 * tag handles age out fast (~2 s) — see CLAUDE.md §3b for the full
 * rationale; in short, opening the session before SSH starts wastes the
 * tag's lifetime, so we kick off SSH first and only arm the NFC reader
 * once sshj is at the userauth signing step.
 *
 * Phases: WaitingForSsh → WaitingForTap → TagCaptured → (WaitingForPin)
 * → Done / Failed.
 */
class DeferredCtapSkSigner(
    override val credentialIdBase64: String,
    override val application: String,
    /** Shared state when this signer is part of a multi-key authentication
     *  attempt. Defaults to a fresh per-signer holder so single-key callers
     *  don't have to know about the multi-key flow. */
    val holder: SkSessionHolder = SkSessionHolder(),
) : SkSigner {
    /** Phase enum kept here for source compatibility with the old
     *  `DeferredCtapSkSigner.Phase.*` references throughout the UI. */
    enum class Phase { WaitingForSsh, WaitingForTap, TagCaptured, WaitingForPin, Done, Failed }

    val phase: kotlinx.coroutines.flow.StateFlow<Phase> = object : kotlinx.coroutines.flow.StateFlow<Phase> {
        override val value: Phase get() = Phase.valueOf(holder.phase.value.name)
        override val replayCache: List<Phase> get() = listOf(value)
        override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<Phase>): Nothing {
            holder.phase.collect { collector.emit(Phase.valueOf(it.name)) }
        }
    }

    val signDone get() = holder.signDone

    fun markTagCaptured() = holder.markTagCaptured()
    fun provideSession(s: Ctap2Session) = holder.provideSession(s)
    fun providePin(chars: CharArray?) = holder.providePin(chars)
    val pinSubmitted: Boolean get() = holder.pinSubmitted
    fun cancel(reason: String = "user cancelled") = holder.cancel(reason)

    override fun sign(clientDataHash: ByteArray): SkSignResult {
        val tag = "Conch-SK-Sign"
        android.util.Log.d(tag, "deferred sign(${credentialIdBase64.take(8)}): enter — phase=${holder.phase.value}")
        if (clientDataHash.size != 32) {
            throw SkAuthException("clientDataHash must be 32 bytes, got ${clientDataHash.size}")
        }
        if (holder.phase.value == SkSessionHolder.Phase.WaitingForSsh) {
            holder.phase.value = SkSessionHolder.Phase.WaitingForTap
        }
        val session = try {
            holder.awaitSession()
        } catch (e: SkAuthException) {
            holder.phase.value = SkSessionHolder.Phase.Failed
            throw e
        } catch (t: Throwable) {
            holder.phase.value = SkSessionHolder.Phase.Failed
            throw SkAuthException("waiting for security-key tap was interrupted: ${t.message}", t)
        }
        // The dialog has already done credentialManagement enumerate +
        // attach inside withNfc{}, set holder.matchedCredId, and cached
        // the user's PIN bytes for re-mint. The pool then trimmed the
        // method list to ONLY the matching credId, so this signer.sign()
        // is a one-shot getAssertion — no probe, no walk, no
        // firmware-lockout dance.
        val myCredId = Base64.getDecoder().decode(credentialIdBase64)
        val cachedPin = holder.cachedPin
        val proto = holder.cachedPinProtocol
        if (cachedPin == null || proto == null) {
            holder.phase.value = SkSessionHolder.Phase.Failed
            throw SkAuthException("PIN/protocol not cached — dialog flow broken")
        }
        // Fix: wipe cached PIN bytes on every exit (success OR exception) so PIN
        // material doesn't survive in memory until the next sign() / GC.
        try {
            val gaToken = try {
                ClientPin(session, proto).getPinToken(
                    cachedPin,
                    ClientPin.PIN_PERMISSION_GA,
                    application,
                )
            } catch (e: CtapException) {
                holder.phase.value = SkSessionHolder.Phase.Failed
                android.util.Log.w(tag, "  sign: getPinToken(GA) failed ctapErr=0x${"%02x".format(e.ctapError)}")
                throw mapCtapErrorTopLevel(e)
            }
            val authParam = proto.authenticate(gaToken, clientDataHash)
            val allowList = listOf(mapOf<String, Any>("type" to "public-key", "id" to myCredId))
            val tStart = System.nanoTime()
            val assertions = try {
                session.getAssertions(
                    application, clientDataHash, allowList,
                    null, null, authParam, proto.version, null,
                )
            } catch (e: CtapException) {
                holder.phase.value = SkSessionHolder.Phase.Failed
                android.util.Log.w(tag, "  getAssertions failed ctapErr=0x${"%02x".format(e.ctapError)}")
                throw mapCtapErrorTopLevel(e)
            } catch (e: java.io.IOException) {
                holder.phase.value = SkSessionHolder.Phase.Failed
                throw SkAuthException("the security key was removed too early — please tap and hold until you see Done", e)
            }
            val durationMs = (System.nanoTime() - tStart) / 1_000_000
            val first = assertions.firstOrNull()
                ?: run {
                    holder.phase.value = SkSessionHolder.Phase.Failed
                    throw SkAuthException("token returned no assertion")
                }
            val authData = AuthenticatorData.parseFrom(ByteBuffer.wrap(first.authenticatorData))
            android.util.Log.d(tag, "  getAssertions ok in ${durationMs}ms: sig=${first.signature.size}B (phase=Done)")
            holder.phase.value = SkSessionHolder.Phase.Done
            holder.signDone.complete(Unit)
            return SkSignResult(
                rawSignature = first.signature,
                flags = authData.flags,
                counter = authData.signCount,
            )
        } finally {
            holder.wipePin()
        }
    }
}

/**
 * SkSigner that talks to a [Ctap2Session] handed to it FULLY OPEN.
 *
 * Used by registration / discovery flows where the dialog has already
 * opened a session for one CTAP operation (makeCredential / enumerate)
 * and wants to reuse the same handle for a follow-up signature without
 * a second tap. Connect-flow uses [DeferredCtapSkSigner] instead.
 */
class PreopenedCtap2SkSigner(
    private val session: Ctap2Session,
    override val credentialIdBase64: String,
    override val application: String,
) : SkSigner {

    override fun sign(clientDataHash: ByteArray): SkSignResult {
        if (clientDataHash.size != 32) {
            throw SkAuthException("clientDataHash must be 32 bytes, got ${clientDataHash.size}")
        }
        val credId = Base64.getDecoder().decode(credentialIdBase64)
        val allowList = listOf(mapOf<String, Any>("type" to "public-key", "id" to credId))
        val assertions = try {
            session.getAssertions(application, clientDataHash, allowList, null, null, null, null, null)
        } catch (e: CtapException) {
            throw mapCtapErrorTopLevel(e)
        }
        val first = assertions.firstOrNull()
            ?: throw SkAuthException("token returned no assertion")
        val authData = AuthenticatorData.parseFrom(ByteBuffer.wrap(first.authenticatorData))
        return SkSignResult(
            rawSignature = first.signature,
            flags = authData.flags,
            counter = authData.signCount,
        )
    }
}

internal fun mapCtapErrorTopLevel(e: CtapException): SkAuthException = when (e.ctapError) {
    CtapException.ERR_USER_ACTION_TIMEOUT,
    CtapException.ERR_KEEPALIVE_CANCEL ->
        SkAuthException("the user did not touch the security key in time", e)
    CtapException.ERR_OPERATION_DENIED ->
        SkAuthException("token denied the operation (declined touch?)", e)
    CtapException.ERR_NO_CREDENTIALS ->
        SkAuthException("this token does not hold a credential for this server (was the key registered on a different token?)", e)
    CtapException.ERR_PIN_REQUIRED ->
        SkAuthException("this credential requires a PIN — enter it in the dialog and try again", e)
    CtapException.ERR_PIN_INVALID ->
        SkAuthException("wrong PIN — try again", e)
    CtapException.ERR_PIN_AUTH_INVALID ->
        SkAuthException("PIN authentication failed — try again", e)
    CtapException.ERR_PIN_NOT_SET ->
        SkAuthException("this token has no PIN configured — set one with `ykman fido access change-pin` first", e)
    CtapException.ERR_PIN_BLOCKED ->
        SkAuthException("PIN is blocked — too many wrong attempts; reset via `ykman fido reset`", e)
    CtapException.ERR_PIN_AUTH_BLOCKED ->
        SkAuthException("PIN attempts exhausted for this session — unplug and replug the token, then try again", e)
    else -> SkAuthException("CTAP error 0x${"%02x".format(e.ctapError)}: ${e.message}", e)
}
