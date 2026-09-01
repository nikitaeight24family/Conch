package ai.eight24family.conch.ui.screens

import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.domain.SecurityKeyTransport
import ai.eight24family.conch.ssh.securitykey.DeferredCtapSkSigner
import ai.eight24family.conch.ssh.securitykey.SkSigner
import ai.eight24family.conch.ssh.securitykey.YubikitSkSigner
import ai.eight24family.conch.util.SilentlyTry
import com.yubico.yubikit.core.fido.FidoConnection
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.fido.ctap.Ctap2Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Touch dialog that drives an SSH op through a hardware FIDO key, with
 * **deferred-tap** support to dodge the Android NFC tag-handle TTL.
 *
 * The naive ordering — *capture tag → kick off SSH → sign* — burns
 * almost the entire ~2 s tag-handle lifetime on TCP + TLS + key
 * exchange, then tries to call CTAP `getAssertions` and gets
 * `SecurityException: Tag is out of date` from `IsoDep.setTimeout`.
 * The user lifts the YubiKey after a normal NFC-pay style touch
 * (~0.5 s) and we'd need them to hold for 2.5 s, which they won't.
 *
 * The fix this dialog implements: kick off SSH **first** with a
 * [DeferredCtapSkSigner] that BLOCKS at sign time. While SSH is
 * crunching through TCP+TLS+kex (~2 s), the user already has the
 * dialog up. The moment sshj reaches userauth and calls sign(), the
 * signer flips `isWaitingForTag=true` and we arm NFC reader-mode.
 * User taps → Ctap2Session opens on the **fresh** tag → fed to the
 * signer → `getAssertions` runs in <300 ms → SSH finishes → done.
 *
 * Because we don't arm NFC until SSH is at the signing step, we
 * never start a 2-second clock against the NFC tag. The tap is
 * consumed within ~half a second of capture.
 *
 * USB is unaffected — `UsbYubiKeyDevice`'s connection handle has no
 * such TTL. We keep the simple eager-signer flow for that case.
 *
 * The two `on*Signer` lambdas are kept separate because USB / NFC
 * have different ordering constraints. Callers usually point them
 * at the same VM method.
 */
@Composable
fun SkInlineTouchDialog(
    transport: SecurityKeyTransport,
    credentialIdBase64: String,
    application: String,
    onUsbSigner: suspend (SkSigner) -> Unit,
    onNfcSigner: suspend (SkSigner) -> Unit,
    onCancel: () -> Unit,
    /** "Find existing credentials on this security key" — surfaced when
     *  the user taps a token whose credId we don't have on file (CTAP
     *  NO_CREDENTIALS). Drives a Keychain detour that runs CTAP
     *  enumerateCredentials and auto-attaches discovered creds to the
     *  current server. Optional — null collapses the action button. */
    onDiscoverOnKey: (() -> Unit)? = null,
    /** "Register a brand-new credential on this security key" — surfaced
     *  when the server rejects every enrolled pubkey (KeyNotAuthorized).
     *  Drives a Keychain detour that runs CTAP makeCredential and
     *  auto-attaches the freshly minted credential to the server. */
    onRegisterNewKey: (() -> Unit)? = null,
    /** True when this dialog is re-armed after a TagLost-style failure
     * during the upstream `pool.userConnect` (PIN-token-request /
     * tag-out-of-date / similar). Adds a hint to the prompt so the
     * user knows they fumbled the previous tap. */
    retry: Boolean = false,
) {
    val ctx = LocalContext.current
    val activity = ctx as? android.app.Activity
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<TouchStatus>(TouchStatus.Idle) }

    // Attempts counter: bumped automatically every time an NFC tap
    // attempt fails (tag lifted too early, Exhausted, TagLost, etc).
    // The signer is recreated per attempt because DeferredCtapSkSigner
    // is one-shot — once it's resolved or cancelled it can't be
    // re-armed. Bumping `attempt` recomputes `nfcSigner` and re-fires
    // the LaunchedEffect, asking the user to tap again. NO BUTTON —
    // the user keeps tapping, the app keeps trying.
    var attempt by remember { mutableStateOf(0) }
    // PIN flow: PIN is requested AFTER tap, only when the signer's CTAP2
    // session reports the token has a PIN set (clientPin == true). The
    // signer flips to phase=WaitingForPin and blocks; we render a pad and
    // call signer.providePin(chars) once the user submits.
    var pinInput by remember { mutableStateOf("") }
    // Defense-in-depth: zero out the PIN buffer when the dialog leaves
    // composition (cancel / dismiss / config change). The submit path
    // already clears it after providePin(); this catches the cancel
    // path so a stray PIN string never lingers in Compose state.
    DisposableEffect(Unit) {
        onDispose { pinInput = "" }
    }
    // Sticky across the WHOLE dialog (not keyed on attempt!): once
    // the user has entered the PIN flow, keep the dialog in PIN-visual
    // mode through a TagLost-during-PIN → re-arm cycle so it doesn't
    // flicker through 'Tap or plug' / 'Got it — keep holding'. Keying
    // this on `attempt` was the bug that put 'Couldn't connect' +
    // SkKeyHero on screen after a lift — every TagLostDuringPin bumps
    // attempt, which would reset the flag and drop us out of PIN mode.
    var pinFlowEntered by remember { mutableStateOf(false) }
    // PER-ATTEMPT: each fresh attempt starts with no PIN submitted.
    // Resets on attempt so re-arm puts us back into typing mode.
    var pinSubmittedLocal by remember(attempt) { mutableStateOf(false) }
    // **Wrong-PIN attempts counter** — declared here but logic wires
    // up AFTER nfcSigner/phase exist (see LaunchedEffect below).
    var wrongPinAttempts by remember { mutableIntStateOf(0) }
    // **Cross-composition signer cache** (ServiceLocator slot). Rotation
    // / theme switch / Samsung night-dim configChange / back-forward
    // nav can all dispose+recompose this dialog with no warning. If
    // `nfcSigner` lived purely in a `remember` slot, the dialog would
    // be reborn with a fresh `DeferredCtapSkSigner` whose holder has
    // default `application = "ssh:"` and empty `candidateCredIds` —
    // while the pool is STILL blocked on the ORIGINAL holder's
    // tokenCredsReady, holding the per-server lock. The fresh
    // signer's userConnect call from `onNfcSigner(signer)` would then
    // block forever on that lock, and the dialog's PIN/enumerate flow
    // would drive an orphaned uninitialised holder → "Security key
    // has no credentials". Pulling the signer from ServiceLocator
    // means every composition sees the SAME signer; pool's
    // initialisation lands on the holder the dialog actually drives.
    //
    // `attempt` is intentionally NOT in the key: each retry-bump
    // should explicitly cancel + replace the cached signer (handled
    // below in the attempt-aware effect), so the same composition
    // sees the new one. Stale signers in Done/Failed terminal phases
    // are discarded here too.
    val signerKey = "$credentialIdBase64|$application"
    val nfcSigner = remember(attempt, signerKey) {
        val cached = ai.eight24family.conch.di.ServiceLocator.cachedSkDialogSigner
        val terminal = cached?.holder?.phase?.value in setOf(
            ai.eight24family.conch.ssh.securitykey.SkSessionHolder.Phase.Done,
            ai.eight24family.conch.ssh.securitykey.SkSessionHolder.Phase.Failed,
        )
        if (cached != null && !terminal &&
            cached.credentialIdBase64 == credentialIdBase64 &&
            cached.application == application
        ) {
            android.util.Log.d(
                "Conch-SK-Dlg",
                "reusing cached signer (phase=${cached.holder.phase.value}) — composition was recreated"
            )
            cached
        } else {
            // Drop the previous one if it was terminal or wrong key.
            if (cached != null && (terminal ||
                    cached.credentialIdBase64 != credentialIdBase64 ||
                    cached.application != application)) {
                ai.eight24family.conch.di.ServiceLocator.cachedSkDialogSigner = null
            }
            val fresh = DeferredCtapSkSigner(credentialIdBase64, application)
            ai.eight24family.conch.di.ServiceLocator.cachedSkDialogSigner = fresh
            android.util.Log.d(
                "Conch-SK-Dlg",
                "created fresh signer (attempt=$attempt key=$signerKey)"
            )
            fresh
        }
    }
    val phase by nfcSigner.phase.collectAsState()
    // Which transport actually won the EITHER race (set when the
    // USB device arrives OR the NFC tag is captured). Drives copy:
    // USB needs "tap the sensor" prompts because the user already
    // plugged in; NFC needs "hold against the back of the phone"
    // because the antenna read is continuous-presence. Null until
    // resolved — fall back to the requested `transport` until then.
    var actualTransport by remember(attempt) {
        mutableStateOf<SecurityKeyTransport?>(null)
    }
    // Bump wrongPinAttempts on every WrongPin failure transition.
    // Sticky across `attempt` — same physical token = same hardware
    // 8-tries budget; the user lifting/tapping shouldn't reset our
    // local mirror of that.
    androidx.compose.runtime.LaunchedEffect(status) {
        val f = status as? TouchStatus.Failed
        if (f?.kind == SkFailureKind.WrongPin) {
            wrongPinAttempts = (wrongPinAttempts + 1).coerceAtMost(8)
        }
    }
    androidx.compose.runtime.LaunchedEffect(phase) {
        // Successful sign-out resets the counter — fresh budget
        // next time the user opens the dialog.
        if (phase == DeferredCtapSkSigner.Phase.Done) {
            wrongPinAttempts = 0
        }
    }
    // In-place wrong-PIN reaction. The CTAP loop calls holder.noteWrongPin()
    // instead of cancelling, so a wrong PIN never becomes a "Connect failed"
    // dialog. Here we react in the SAME pad: shake the dialog left↔right, buzz
    // 0.3 s, bump the N/8 counter (from the token's authoritative remaining
    // attempts), and clear the half-typed PIN.
    val shakeX = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    val wrongPinSignal = nfcSigner.holder.wrongPin.collectAsState().value
    val vibrateCtx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(wrongPinSignal?.tick) {
        val sig = wrongPinSignal ?: return@LaunchedEffect
        if (sig.tick == 0) return@LaunchedEffect
        // Counter "× / 8 used": prefer the token's real remaining attempts;
        // fall back to a local increment if the retries read failed.
        wrongPinAttempts = sig.retriesLeft?.let { (8 - it).coerceIn(1, 8) }
            ?: (wrongPinAttempts + 1).coerceAtMost(8)
        pinInput = ""
        // 0.3 s vibration.
        SilentlyTry.fired("Conch-SkDialog", "wrong-pin vibrate") {
            val vib = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                vibrateCtx.getSystemService(android.os.VibratorManager::class.java).defaultVibrator
            else
                @Suppress("DEPRECATION")
                (vibrateCtx.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator)
            vib.vibrate(android.os.VibrationEffect.createOneShot(300, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        }
        // Quick decaying left↔right shake (~300 ms).
        shakeX.snapTo(0f)
        shakeX.animateTo(
            targetValue = 0f,
            animationSpec = androidx.compose.animation.core.keyframes {
                durationMillis = 300
                (-12f) at 30
                12f at 75
                (-9f) at 120
                9f at 165
                (-5f) at 210
                5f at 255
                0f at 300
            },
        )
    }
    // Tracks the in-flight NFC attempt's coroutine so we can CANCEL it
    // before launching a retry. Without this, every `attempt += 1`
    // recomposition starts another `scope.launch` while the previous
    // one is still mid-handshake — three or four parallel SSH
    // connects to the same host hit OpenSSH's MaxAuthTries / get
    // rate-limited / step on each other and produce a tight failure
    // loop ("Exhausted available authentication methods" / "Connection
    // reset" every 2 sec, dialog flickers between Connecting and Tap
    // the user can never act on).
    val nfcJob = remember { androidx.compose.runtime.mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun startUsb() {
        status = TouchStatus.Waiting(SecurityKeyTransport.USB)
        actualTransport = SecurityKeyTransport.USB
        scope.launch {
            try {
                val device = withContext(Dispatchers.IO) {
                    ServiceLocator.securityKeyManager.awaitUsb(timeoutMs = Long.MAX_VALUE)
                }
                if (device == null) {
                    status = TouchStatus.Failed("no security key detected — timed out")
                    return@launch
                }
                val signer = YubikitSkSigner(
                    device = device,
                    credentialIdBase64 = credentialIdBase64,
                    application = application,
                )
                onUsbSigner(signer)
                status = TouchStatus.Done
            } catch (t: Throwable) {
                status = TouchStatus.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /**
     * Kick off the deferred-tap NFC flow.
     *
     * Two coroutines run in parallel inside `coroutineScope { ... }`:
     *
     *   - **op-job**: runs `onNfcSigner(deferredSigner)` — i.e. the
     *     SSH op. Sets up TCP + TLS + kex, hits userauth, blocks on
     *     `signer.sign()` waiting for a Ctap2Session.
     *   - **nfc-job**: arms reader-mode and waits for the tag. As
     *     soon as `device` arrives, opens a Ctap2Session on the
     *     fresh tag and feeds it via [DeferredCtapSkSigner.provideSession].
     *     The block then waits on [DeferredCtapSkSigner.signDone] so
     *     `Ctap2Session.use` doesn't close the connection out from
     *     under sign(). Once sign() returns, we exit the use block
     *     cleanly.
     *
     * If the user cancels mid-flight, we [DeferredCtapSkSigner.cancel]
     * to unblock sign() with an SkAuthException.
     */
    /**
     * Parallel USB + NFC arm: kick off the SSH op with the deferred
     * signer, then race two coroutines — one waiting for a USB device
     * to show up on awaitUsb, one arming NFC reader-mode. Whichever
     * lands a [Ctap2Session] first feeds the signer; the other is
     * cancelled. The user does whatever feels natural with their key —
     * we don't care which channel they pick.
     */
    fun startEither() {
        if (activity == null) {
            status = TouchStatus.Failed("touch dialog requires an activity")
            return
        }
        android.util.Log.d(
            "Conch-SK-Dlg",
            "startEither: arming USB+NFC in parallel (credId=${credentialIdBase64.length}B64 app=$application attempt=$attempt)"
        )
        // Preserve the TagLostDuringPin pause state across re-arm —
        // overwriting status here would flip the dialog from
        // 'Enter your PIN · Put the key back' to 'Tap or plug your
        // security key' for the brief window between attempt++ and the
        // user actually re-tapping, which is exactly the UX flip we
        // promised not to do.
        if ((status as? TouchStatus.Failed)?.kind != SkFailureKind.TagLostDuringPin) {
            status = TouchStatus.Waiting(SecurityKeyTransport.NFC)
        }
        nfcJob.value?.cancel()
        nfcJob.value = scope.launch {
            runDeferredEitherAttempt(
                activity = activity,
                signer = nfcSigner,
                onNfcSigner = onNfcSigner,
                onSuccess = { status = TouchStatus.Done },
                onRetry = { reason ->
                    android.util.Log.w("Conch-SK-Dlg", "  attempt $attempt failed: $reason — retrying")
                    attempt += 1
                },
                onPermanent = { reason, kind ->
                    android.util.Log.w("Conch-SK-Dlg", "  permanent ($kind): $reason")
                    status = TouchStatus.Failed(reason, kind)
                },
                onTransportResolved = { t -> actualTransport = t },
            )
        }
    }

    fun startNfc() {
        if (activity == null) {
            status = TouchStatus.Failed("NFC requires the activity context")
            return
        }
        android.util.Log.d(
            "Conch-SK-Dlg",
            "startNfc: deferred-tap flow (credId=${credentialIdBase64.length}B64 app=$application attempt=$attempt)"
        )
        if ((status as? TouchStatus.Failed)?.kind != SkFailureKind.TagLostDuringPin) {
            status = TouchStatus.Waiting(SecurityKeyTransport.NFC)
        }
        // Cancel the previous attempt's coroutine before launching the
        // new one — see comment on `nfcJob` above for why this matters.
        nfcJob.value?.cancel()
        nfcJob.value = scope.launch {
            runDeferredNfcAttempt(
                activity = activity,
                signer = nfcSigner,
                onNfcSigner = onNfcSigner,
                onSuccess = { status = TouchStatus.Done },
                onRetry = { reason ->
                    android.util.Log.w("Conch-SK-Dlg", "  attempt $attempt failed: $reason — retrying")
                    attempt += 1
                },
                onPermanent = { reason, kind ->
                    android.util.Log.w("Conch-SK-Dlg", "  permanent ($kind): $reason")
                    status = TouchStatus.Failed(reason, kind)
                },
                onTagCaptured = { actualTransport = SecurityKeyTransport.NFC },
            )
        }
    }

    // Auto-arm based on the registered transport. Safe because the
    // dialog only appears on explicit user action (pull-to-refresh
    // or first-open), not on init init — so there's no NavHost
    // back-stack restoration race that cancels us mid-flight.
    // Re-fire on every `attempt` bump so a failed tap automatically
    // re-arms NFC with a fresh signer. User just taps again — no
    // button to press, no error spam, no exit-and-retry dance.
    // Tap-to-retry on Failed: when the dialog has bailed with a recoverable
    // failure (TagLost / cancel / timeout / network blip), keep listening
    // for the user to bring a key into range. The MOMENT a device shows up
    // we bump `attempt`, which re-fires the main auto-arm and starts a
    // fresh SSH+touch flow. No "Try again" button — the user just attaches
    // the key and we figure it out.
    val failedKind = (status as? TouchStatus.Failed)?.kind
    val isRecoverableFailed = status is TouchStatus.Failed && (
        failedKind == SkFailureKind.TagLost ||
        failedKind == SkFailureKind.TagLostDuringPin ||
        failedKind == SkFailureKind.NetworkBlip ||
        failedKind == SkFailureKind.Unknown
    )
    androidx.compose.runtime.LaunchedEffect(isRecoverableFailed) {
        if (!isRecoverableFailed || activity == null) return@LaunchedEffect
        // Previous approach armed a SECOND NFC watcher in parallel here
        // and bumped `attempt` on the first tap it caught. The bug:
        // user's single tap was consumed by THIS watcher, which then
        // closed the reader-mode and triggered the main arm flow. By
        // the time the main flow's withNfc re-armed reader-mode, the
        // user's key was already lifted — and they thought "I tapped,
        // nothing happened". Two-tap UX, indistinguishable from broken.
        //
        // New approach: just bump `attempt` immediately. The main
        // LaunchedEffect re-fires with a fresh signer, runs startEither
        // / startNfc, which arms reader-mode and waits for THE user's
        // first real tap. Brief delay so the user sees the "Couldn't
        // connect" error for a moment before the new arm kicks in.
        // Cap-aware: the main LaunchedEffect's MAX_AUTO_RETRIES = 6
        // refuses to re-arm past attempt 6 (to avoid grinding when the
        // server is dead). Mirroring that here prevents an infinite
        // bump→cap→Failed→bump loop. Past the cap the user must hit
        // the explicit Cancel + reopen flow.
        if (attempt >= 6) {
            android.util.Log.w("Conch-SK-Dlg", "  recoverable failure but attempt=$attempt past cap — not auto-bumping")
            return@LaunchedEffect
        }
        android.util.Log.d("Conch-SK-Dlg", "recoverable failure (kind=$failedKind) → auto-bump attempt $attempt→${attempt + 1} to re-arm main flow")
        kotlinx.coroutines.delay(800L)
        attempt += 1
    }

    // Clear the TagLostDuringPin pause state once a fresh tag has been
    // captured by the re-armed signer. At that point the dialog flips
    // back to the live PIN flow naturally (status reset → phase-driven
    // body branches resume normal rendering). Without this hook the
    // 'Put the key back' message would linger past the moment the user
    // actually put the key back.
    val dialogHaptic = ai.eight24family.conch.ui.haptic.LocalConchHaptics.current
    androidx.compose.runtime.LaunchedEffect(phase) {
        if (phase == DeferredCtapSkSigner.Phase.WaitingForPin) {
            pinFlowEntered = true
            // Tag captured + asking for PIN — emphatic Heavy so the
            // user physically feels "you can stop pressing the key
            // against the back of the phone, we got it".
            dialogHaptic.perform(ai.eight24family.conch.ui.haptic.ConchHaptic.Heavy)
        }
        if (phase == DeferredCtapSkSigner.Phase.TagCaptured &&
            (status as? TouchStatus.Failed)?.kind == SkFailureKind.TagLostDuringPin) {
            status = TouchStatus.Idle
        }
        if (phase == DeferredCtapSkSigner.Phase.Done) {
            // Auth completed end-to-end — double-tap Confirm. This
            // also gives the user a tactile cue when the dialog
            // disappears for the success path.
            dialogHaptic.perform(ai.eight24family.conch.ui.haptic.ConchHaptic.Confirm)
            // Drop the cached signer — this auth flow is done, next
            // dialog open should mint a fresh one.
            if (ai.eight24family.conch.di.ServiceLocator.cachedSkDialogSigner === nfcSigner) {
                ai.eight24family.conch.di.ServiceLocator.cachedSkDialogSigner = null
            }
        }
        if (phase == DeferredCtapSkSigner.Phase.Failed) {
            dialogHaptic.perform(ai.eight24family.conch.ui.haptic.ConchHaptic.Reject)
        }
    }


    androidx.compose.runtime.LaunchedEffect(transport, attempt) {
        android.util.Log.d("Conch-SK-Dlg", "auto-arm fired, transport=$transport attempt=$attempt activity=${activity != null}")
        // Hard cap on auto-retries. 6 × ~2 s backoff each = ~12 s of
        // patient retry before showing the user a 'something's really
        // wrong' UI.
        val MAX_AUTO_RETRIES = 6
        if (attempt >= MAX_AUTO_RETRIES) {
            android.util.Log.w("Conch-SK-Dlg", "  attempt $attempt >= cap — stopping auto-retry, user must pull-down")
            status = TouchStatus.Failed("Can't reach the server. Plug in or tap your key to retry.", SkFailureKind.NetworkBlip)
            return@LaunchedEffect
        }
        when (transport) {
            SecurityKeyTransport.NFC -> if (activity != null) startNfc()
            SecurityKeyTransport.USB -> if (attempt == 0) startUsb()  // USB is one-shot; retry is via the dialog button
            // EITHER: arm BOTH NFC reader-mode and USB await in parallel.
            // Whichever the user reaches for first wins — the other arm is
            // cancelled when a device shows up. No "USB or NFC" buttons:
            // the user just plugs in or taps, the app figures it out.
            SecurityKeyTransport.EITHER -> if (activity != null) startEither()
        }
    }

    // Phase 7.1 fix (round 3): Material 3's [AlertDialog] clamps its
    // text slot's max-height regardless of how we lay out the body —
    // tried verticalScroll, tried a 3×4 pin pad, both still got
    // clipped under the dismissButton strip in landscape (DeX, foldable
    // inner, split-screen). Replaced with a custom Dialog + Surface so
    // **Hide the dialog window once the SIGNER reports Done.**
    //
    // The user has lifted the key by the time signing returns;
    // showing them "Done — You can lift the key now" for the
    // ~seconds pool.userConnect + status probe takes is pure
    // clutter (the user has yelled about it five times now).
    //
    // We hide ONLY the Dialog window — the composable function itself
    // MUST continue executing because the in-flight sshj auth is built
    // around the `nfcSigner` instance remembered above. An early
    // `return` here would dispose the `remember(.)` slot and any inner
    // LaunchedEffects / DisposableEffects that hold the NFC reader open
    // and keep the signer alive — sshj would then continue userauth
    // post- signing against a torn-down signer and the SSH handshake
    // would fail mid-stride.
    //
    // Wrapping only the Dialog call in `if (phase != Done) {…}`
    // keeps the composable's slot table intact: nfcSigner is
    // still remembered, all child LaunchedEffects keep running,
    // pool.userConnect finishes normally. The user just doesn't
    // see the celebratory "Done" pane.
    if (phase != DeferredCtapSkSigner.Phase.Done) {
    // WE control the size. `usePlatformDefaultWidth = false` removes
    // the platform-default tablet width that was constraining height
    // too; widthIn + an explicit verticalScroll on the body column
    // lets all content fit OR scroll on truly tiny windows.
    // Local helper: user-driven cancel paths must (1) cancel the signer
    // so pool's runBlocking on tokenCredsReady unblocks, AND (2) drop
    // the ServiceLocator cache so the next dialog open creates a fresh
    // signer instead of reusing the Failed one.
    val userCancel: (String) -> Unit = { reason ->
        nfcSigner.cancel(reason)
        if (ai.eight24family.conch.di.ServiceLocator.cachedSkDialogSigner === nfcSigner) {
            ai.eight24family.conch.di.ServiceLocator.cachedSkDialogSigner = null
        }
    }
    Dialog(
        onDismissRequest = {
            // Guard mid-PIN entry: a stray outside-touch / back-press
            // shouldn't nuke the user's work after they've already
            // dealt with the tap. They can still hit the explicit
            // Cancel button to bail out.
            if (phase == DeferredCtapSkSigner.Phase.WaitingForPin) return@Dialog
            userCancel("dialog dismissed")
            onCancel()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // **Don't auto-dismiss on outside touch.** When the user
            // plugs in a USB security key the system can briefly steal
            // focus (USB permission prompt, accessory-detected toast,
            // etc.) — Android translates the focus loss into a fake
            // "outside touch" which fires our onDismissRequest. Symptom:
            // user inserts the key, dialog vanishes, they're popped
            // back to the server list with no explanation. Only the
            // explicit Cancel button or a real back press should
            // unwind us.
            dismissOnClickOutside = false,
        ),
    ) {
        // User policy (2026-05-12): the dialog MUST fit on any device
        // without scrolling. Compressed paddings + spacing + the
        // adaptive PinPad below make this hold even on narrow
        // landscape phones (~360dp tall) where AlertDialog used to
        // clip the bottom rows. No `verticalScroll` here on purpose —
        // if we ever need it the policy is "shrink elements further",
        // not "let user scroll".
        val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        Surface(
            shape = androidx.compose.material3.MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                // Cap at 360dp so the dialog stays a comfortable phone-
                // keypad shape on wide windows (DeX, tablet) instead of
                // stretching to 560dp and forcing the PIN pad to a
                // weird 4-wide layout. 360dp ≈ standard phone width;
                // the classic 3-column pin pad fits naturally.
                .widthIn(min = 280.dp, max = 360.dp)
                .offset { androidx.compose.ui.unit.IntOffset(shakeX.value.toInt(), 0) }
                .padding(if (landscape) 8.dp else 16.dp),
        ) {
          Box(modifier = Modifier.fillMaxWidth()) {
            // Top-right key+waves indicator visible during PIN entry.
            // Reminds the user the key MUST stay against the antenna
            // while they type the PIN. The animated ripples turn OFF
            // when the watcher has detected the key was lifted
            // (TagLostDuringPin) so the user gets a clear "key absent"
            // visual on top of the "Put the key back" text below.
            // Shows as long as we're in PIN flow (sticky once entered).
            val isPinPause = (status as? TouchStatus.Failed)?.kind == SkFailureKind.TagLostDuringPin
            val failedKindForIndicator = (status as? TouchStatus.Failed)?.kind
            // Fatal-for-indicator: kinds where re-tapping the security
            // key can't possibly fix the problem. The dialog should
            // suppress the radiating waves and show a clear error
            // card instead. All connect-layer and post-auth-config
            // kinds qualify here. TagLost* are NOT fatal — those are
            // exactly the "try tapping again" cases.
            val fatalForIndicator = failedKindForIndicator in setOf(
                SkFailureKind.KeyNotAuthorized,
                SkFailureKind.HostKeyMismatch,
                SkFailureKind.TokenRejected,
                SkFailureKind.ServerUnreachable,
                SkFailureKind.ConnectionRefused,
                SkFailureKind.BadHostname,
                SkFailureKind.NoNetwork,
                SkFailureKind.ProtocolMismatch,
                SkFailureKind.KexFailure,
                SkFailureKind.AuthFailedPassword,
                SkFailureKind.TooManyAuthFailures,
                SkFailureKind.UserNotAllowed,
                SkFailureKind.ChannelDenied,
                SkFailureKind.ServerDisconnect,
            )
            if (pinFlowEntered && !pinSubmittedLocal && !fatalForIndicator) {
                PinPadKeyIndicator(
                    // Animate waves only when we're CONFIDENT the key
                    // is in contact: phase=WaitingForPin or TagCaptured
                    // and not paused.
                    animated = !isPinPause && (phase == DeferredCtapSkSigner.Phase.WaitingForPin ||
                        phase == DeferredCtapSkSigner.Phase.TagCaptured),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 10.dp, end = 12.dp)
                )
            }
            Column(
                modifier = Modifier.padding(
                    horizontal = if (landscape) 16.dp else 24.dp,
                    vertical = if (landscape) 12.dp else 20.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(if (landscape) 8.dp else 16.dp),
            ) {
                // ── Title ──
                val titleText = run {
                    val s = status
                    when {
                        // Once we've entered PIN flow, stay on the PIN
                        // title for the rest of the dialog's life
                        // (until Done / Cancel / fatal). This is the
                        // anchor that prevents the dialog from flicking
                        // back to 'Tap or plug' / 'Got it — keep
                        // holding' during a re-arm after a TagLost.
                        // Fatal Failed states (KeyNotAuthorized,
                        // HostKeyMismatch, TokenRejected) still take
                        // over because they're not recoverable here.
                        s is TouchStatus.Failed && s.kind in setOf(
                            SkFailureKind.KeyNotAuthorized,
                            SkFailureKind.HostKeyMismatch,
                            SkFailureKind.TokenRejected,
                            SkFailureKind.ServerUnreachable,
                            SkFailureKind.ConnectionRefused,
                            SkFailureKind.BadHostname,
                            SkFailureKind.NoNetwork,
                            SkFailureKind.ProtocolMismatch,
                            SkFailureKind.KexFailure,
                            SkFailureKind.AuthFailedPassword,
                            SkFailureKind.TooManyAuthFailures,
                            SkFailureKind.UserNotAllowed,
                            SkFailureKind.ChannelDenied,
                            SkFailureKind.ServerDisconnect,
                            SkFailureKind.PinBlocked,
                            SkFailureKind.PinAuthBlocked,
                        ) -> when (s.kind) {
                            SkFailureKind.KeyNotAuthorized -> "Key not recognised by server"
                            SkFailureKind.HostKeyMismatch -> "Server identity changed"
                            SkFailureKind.TokenRejected -> "New security key"
                            SkFailureKind.ServerUnreachable -> "Server unreachable"
                            SkFailureKind.ConnectionRefused -> "Connection refused"
                            SkFailureKind.BadHostname -> "Hostname not found"
                            SkFailureKind.NoNetwork -> "No network route"
                            SkFailureKind.ProtocolMismatch -> "Not an SSH port"
                            SkFailureKind.KexFailure -> "Encryption mismatch"
                            SkFailureKind.AuthFailedPassword -> "Wrong password"
                            SkFailureKind.TooManyAuthFailures -> "Too many auth attempts"
                            SkFailureKind.UserNotAllowed -> "User not allowed"
                            SkFailureKind.ChannelDenied -> "Login OK but exec refused"
                            SkFailureKind.ServerDisconnect -> "Server cut connection"
                            SkFailureKind.PinBlocked -> "Security key locked"
                            SkFailureKind.PinAuthBlocked -> "PIN paused — unplug + replug"
                            else -> "Couldn't connect"
                        }
                        pinFlowEntered && !pinSubmittedLocal -> "Enter your PIN"
                        s is TouchStatus.Failed -> "Couldn't connect"
                        transport == SecurityKeyTransport.USB -> "Touch your security key"
                        phase == DeferredCtapSkSigner.Phase.WaitingForPin -> "Enter your PIN"
                        // Post-PIN: USB needs an explicit sensor TAP
                        // because the key is plugged in and blinking,
                        // not held against the phone. NFC continues the
                        // "keep holding" copy because lifting the tag
                        // aborts CTAP.
                        phase == DeferredCtapSkSigner.Phase.TagCaptured ->
                            if (actualTransport == SecurityKeyTransport.USB) "Tap your key's sensor"
                            else "Got it — keep holding"
                        phase == DeferredCtapSkSigner.Phase.Done -> "Done"
                        // EITHER + WaitingForSsh both look the same to the user:
                        // they just need to attach a key. Don't show a separate
                        // "Connecting…" title that implies they should wait.
                        else -> "Tap or plug your security key"
                    }
                }
                Text(
                    titleText,
                    style = MaterialTheme.typography.headlineSmall,
                )
                // Re-arm hint: when this dialog is mounted after a
                // TagLost-style failure on the previous pass, render
                // a thin subtitle so the user sees "yeah, you fumbled
                // it, try again" without an alert popping over the
                // dialog. Subtitle (not a title suffix) keeps the
                // primary instruction clean — the headline still
                // reads as the action to take, the secondary line
                // is just context for why we're here again.
                if (retry) {
                    Text(
                        "Tag dropped — try again",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                // Wrong-PIN attempts counter / lockout warning.
                // Only shown when the user has actually fat-fingered
                // the PIN at least once this sitting. At 8 the token
                // itself blocks PIN and we surface the much louder
                // PinBlocked title above; this sub-line covers the
                // 1–7 range with escalating urgency.
                val failedKind = (status as? TouchStatus.Failed)?.kind
                when {
                    failedKind == SkFailureKind.PinBlocked -> {
                        // Title path already handled PinBlocked
                        // explicitly — sub-line gives the recovery
                        // command. The (only) way out is a
                        // factory-reset of the token's FIDO2 app,
                        // which wipes every credential on it.
                        Text(
                            "PIN locked. Recovery: `ykman fido reset` (wipes all credentials).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    failedKind == SkFailureKind.PinAuthBlocked -> {
                        Text(
                            "PIN locked for this card session. Unplug + replug the key (or re-tap NFC) and try once more.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    wrongPinAttempts in 1..7 -> {
                        val remaining = 8 - wrongPinAttempts
                        val urgent = wrongPinAttempts >= 5
                        Text(
                            "Wrong PIN · $wrongPinAttempts / 8 used" +
                                if (urgent) " · $remaining left before the key locks itself" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (urgent) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }

                // ── Body ──
                Column(
                    verticalArrangement = Arrangement.spacedBy(if (landscape) 6.dp else 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                // TagLostDuringPin = soft pause. The window stays
                // exactly as the user sees it during normal PIN entry —
                // same PinPad, same layout, same title. Only the top
                // text line swaps to a "Put the security key back"
                // prompt, the top-right indicator stops its wave
                // animation (handled above where showIndicator is
                // computed), and pinInput is cleared so any half-typed
                // digits don't auto-submit when the user re-attaches.
                val isPinPaused = (status as? TouchStatus.Failed)?.kind == SkFailureKind.TagLostDuringPin
                if (isPinPaused) {
                    LaunchedEffect(Unit) { pinInput = "" }
                }
                // Render PIN body whenever we're in PIN flow — either
                // the signer is actively at WaitingForPin, or we've
                // been there once and the dialog is in its sticky
                // pin-flow mode (re-arm cycle after TagLost in PIN).
                // The latter is what stops the dialog from flicking
                // to 'Tap or plug' / 'Got it — keep holding'
                // SkKeyHero screens between TagLost and the new
                // capture.
                val failedKindNow = (status as? TouchStatus.Failed)?.kind
                val fatalFail = failedKindNow in setOf(
                    SkFailureKind.KeyNotAuthorized,
                    SkFailureKind.HostKeyMismatch,
                    SkFailureKind.TokenRejected,
                    SkFailureKind.ServerUnreachable,
                    SkFailureKind.ConnectionRefused,
                    SkFailureKind.BadHostname,
                    SkFailureKind.NoNetwork,
                    SkFailureKind.ProtocolMismatch,
                    SkFailureKind.KexFailure,
                    SkFailureKind.AuthFailedPassword,
                    SkFailureKind.TooManyAuthFailures,
                    SkFailureKind.UserNotAllowed,
                    SkFailureKind.ChannelDenied,
                    SkFailureKind.ServerDisconnect,
                )
                val showPinBody = !fatalFail && (
                    (pinFlowEntered && !pinSubmittedLocal) ||
                        (phase == DeferredCtapSkSigner.Phase.WaitingForPin && status !is TouchStatus.Failed)
                    )
                if (showPinBody) {
                    // Copy branches by actualTransport. For NFC we ASK the
                    // user to hold still — lifting the tag aborts CTAP
                    // mid-flight. For USB the key is already mechanically
                    // inserted; the relevant action is the impending
                    // sensor TAP after PIN submit, not "don't move".
                    // EITHER (transport not yet resolved) falls back to
                    // the conservative NFC-style copy.
                    val accent = if (isPinPaused) MaterialTheme.colorScheme.tertiary
                                 else MaterialTheme.colorScheme.primary
                    val isUsb = actualTransport == SecurityKeyTransport.USB
                    val annotated = androidx.compose.ui.text.buildAnnotatedString {
                        if (isPinPaused) {
                            // Pause copy. Same Text widget so the layout
                            // doesn't shift — only the wording changes.
                            append("Put the\n")
                            withStyle(
                                androidx.compose.ui.text.SpanStyle(
                                    color = accent,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                )
                            ) { append("security key") }
                            append(" ")
                            appendInlineContent("keyIcon", "[key]")
                            append("\nback to continue.")
                        } else if (isUsb) {
                            // USB: key is plugged in, mechanical stability
                            // is guaranteed. Tell the user what's coming —
                            // a sensor tap after they submit the PIN.
                            if (landscape) {
                                append("After PIN, tap the sensor on your ")
                                withStyle(
                                    androidx.compose.ui.text.SpanStyle(
                                        color = accent,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                    )
                                ) { append("security key") }
                                append(" ")
                                appendInlineContent("keyIcon", "[key]")
                                append(".")
                            } else {
                                append("After PIN, tap the sensor\non your ")
                                withStyle(
                                    androidx.compose.ui.text.SpanStyle(
                                        color = accent,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                    )
                                ) { append("security key") }
                                append(" ")
                                appendInlineContent("keyIcon", "[key]")
                                append(".")
                            }
                        } else if (landscape) {
                            append("Don't move the ")
                            withStyle(
                                androidx.compose.ui.text.SpanStyle(
                                    color = accent,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                )
                            ) { append("security key") }
                            append(" ")
                            appendInlineContent("keyIcon", "[key]")
                            append(" while you type.")
                        } else {
                            append("Don't move the\n")
                            withStyle(
                                androidx.compose.ui.text.SpanStyle(
                                    color = accent,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                )
                            ) { append("security key") }
                            append(" ")
                            appendInlineContent("keyIcon", "[key]")
                            append("\nwhile you type.")
                        }
                    }
                    val inlineContent = mapOf(
                        "keyIcon" to androidx.compose.foundation.text.InlineTextContent(
                            androidx.compose.ui.text.Placeholder(
                                width = 28.sp,
                                height = 14.sp,
                                placeholderVerticalAlign =
                                    androidx.compose.ui.text.PlaceholderVerticalAlign.Center,
                            )
                        ) {
                            ai.eight24family.conch.ui.components.SecurityKeyIcon(
                                modifier = Modifier.fillMaxSize(),
                                tint = accent,
                            )
                        }
                    )
                    Text(
                        text = annotated,
                        inlineContent = inlineContent,
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PinPad(
                        pin = pinInput,
                        // Key gone (TagLostDuringPin pause) → disable the pad so
                        // digits can't go into the cancelled flow. Re-enables the
                        // moment the key is back (status → Idle, isPinPaused=false).
                        enabled = !isPinPaused,
                        onChange = { pinInput = it },
                        onSubmit = {
                            if (pinInput.isNotEmpty()) {
                                nfcSigner.providePin(pinInput.toCharArray())
                                pinInput = ""
                                // Drop the sticky PIN-flow lock so the
                                // dialog can transition through the
                                // natural post-PIN phases:
                                // TagCaptured → Done (SkKeyHero with
                                // ripples → checkmark + chime).
                                pinSubmittedLocal = true
                            }
                        },
                    )
                    return@Column
                }
                // Single visual centre: the key hero. State derives from
                // current phase / status — no separate "Connecting…" vs
                // "Tap your key" screens, just one fluid experience.
                val heroState = when {
                    status is TouchStatus.Failed -> KeyHeroState.Idle
                    phase == DeferredCtapSkSigner.Phase.Done -> KeyHeroState.Done
                    phase == DeferredCtapSkSigner.Phase.TagCaptured -> KeyHeroState.Captured
                    else -> KeyHeroState.Waiting
                }
                SkKeyHero(state = heroState)

                // One supporting line under the hero. Failed states show the
                // classifier's user-friendly reason; otherwise a short
                // imperative copy that doesn't change between WaitingForSsh
                // and WaitingForTap (same action either way: attach the key).
                //
                // TagCaptured copy branches by actualTransport: after PIN
                // entry, getAssertions blocks until the user provides
                // physical "user presence" — for NFC that's "don't lift
                // the tag", for USB that's a tap on the side sensor (the
                // key blinks to prompt). The previous copy was NFC-only
                // ("Don't lift yet"), which left USB users staring at a
                // blinking key with no clue what to do.
                val isUsb = actualTransport == SecurityKeyTransport.USB
                val supportingText = when {
                    status is TouchStatus.Failed -> (status as TouchStatus.Failed).reason
                    phase == DeferredCtapSkSigner.Phase.Done -> if (isUsb)
                        "Done — you can unplug the key."
                        else "You can lift the key now."
                    phase == DeferredCtapSkSigner.Phase.TagCaptured -> if (isUsb)
                        "Touch the blinking sensor on your key."
                        else "Don't lift yet — talking to the key."
                    transport == SecurityKeyTransport.USB -> "Plug into USB-C and touch the sensor."
                    else -> "Plug into USB-C, or hold against the back of the phone."
                }
                Text(
                    supportingText,
                    color = if (status is TouchStatus.Failed)
                        MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

                // ── Buttons ──
                // Replaces AlertDialog's confirmButton + dismissButton
                // slots. Right-aligned Row with optional Failed-state
                // action button + the universal Cancel/Close/"Not now"
                // dismiss button.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Action shown in the Failed state when the failure has an
                    // obvious next step:
                    //   TokenRejected (CTAP NO_CREDENTIALS) → user tapped a token
                    //     whose credId isn't on file → "Find on this key" runs
                    //     Keychain's discover flow against the current server.
                    //   KeyNotAuthorized (server reject) → server doesn't have any
                    //     of our enrolled pubkeys in authorized_keys → "Register
                    //     a new key" mints a fresh credential.
                    val s = status
                    when {
                        s is TouchStatus.Failed && s.kind == SkFailureKind.TokenRejected && onDiscoverOnKey != null -> {
                            TextButton(onClick = {
                                userCancel("opening keychain")
                                onDiscoverOnKey()
                            }) { Text("Add this key") }
                        }
                        s is TouchStatus.Failed && s.kind == SkFailureKind.KeyNotAuthorized && onRegisterNewKey != null -> {
                            TextButton(onClick = {
                                userCancel("opening keychain")
                                onRegisterNewKey()
                            }) { Text("Register a new key") }
                        }
                    }
                    TextButton(onClick = {
                        userCancel("user pressed cancel")
                        onCancel()
                    }) {
                        Text(
                            when {
                                status is TouchStatus.Done -> "Close"
                                status is TouchStatus.Failed -> "Not now"
                                else -> "Cancel"
                            }
                        )
                    }
                }
            }  // outer Column closes
          }  // Box (for top-right key indicator overlay) closes
        }  // Surface closes
    }  // Dialog closes
    }  // `if (phase != Done)` closes
}

/**
 * Arm NFC reader-mode and, on first tap, open a [Ctap2Session] on the
 * captured tag and feed it to [signer]. Then block until the signer
 * signals it's done with the session.
 *
 * Runs inside the SSH op's surrounding `coroutineScope { ... }` so
 * cancellation propagates: if the SSH op fails, this is cancelled
 * before the user even taps.
 */
/**
 * Run one full deferred-tap NFC attempt. On failure, calls [onRetry]
 * with the error reason; the surrounding LaunchedEffect uses that to
 * bump its attempt counter and re-fire this function with a fresh
 * signer. On success, calls [onSuccess].
 *
 * Failures auto-retry forever (until the user hits Cancel) because
 * the dominant failure mode — "tag was lost" / "Exhausted available
 * authentication methods" / "TagLost" — happens when the user lifts
 * the YubiKey too quickly. Asking them to click a button between
 * attempts is busywork; just re-arm and ask them to tap again.
 */
private suspend fun runDeferredNfcAttempt(
    activity: android.app.Activity,
    signer: DeferredCtapSkSigner,
    onNfcSigner: suspend (SkSigner) -> Unit,
    onSuccess: () -> Unit,
    onRetry: (reason: String) -> Unit,
    onPermanent: (reason: String, kind: SkFailureKind) -> Unit,
    onTagCaptured: () -> Unit = {},
) {
    try {
        coroutineScope {
            // Coroutine A: the SSH op (consumer of the signer).
            val opJob = async(Dispatchers.IO) {
                onNfcSigner(signer)
            }
            // Coroutine B: NFC reader. Captures tag, opens Ctap2Session,
            // feeds it to signer, waits for sign() to finish, releases.
            val nfcJob = async(Dispatchers.IO) {
                runDeferredNfcDance(activity, signer, onTagCaptured)
            }
            opJob.await()
            nfcJob.await()
        }
        onSuccess()
    } catch (e: kotlinx.coroutines.CancellationException) {
        signer.cancel("dialog cancelled")
        throw e
    } catch (t: Throwable) {
        // Snapshot signer phase BEFORE cancel() flips it to Failed.
        // Used by the classifier to distinguish "server rejected our
        // signature" (signer reached Phase.Done) from "tag/link died
        // mid-sign" (signer was in TagCaptured / WaitingForPin / etc.
        // when the throw bubbled up). Without this distinction every
        // mid-sign tag loss got "Exhausted methods" from sshj's outer
        // wrapper and was mis-classified as KeyNotAuthorized.
        val signerPhaseAtFailure = signer.phase.value
        signer.cancel("classifying: ${t.message}")
        val classified = classifySkFailure(t, signerPhaseAtFailure)
        if (classified.permanent) {
            // Don't loop on something that won't fix itself. Surface
            // the real reason once, let the user act.
            android.util.Log.w("Conch-SK-Dlg", "  permanent failure: ${classified.userMessage}")
            onPermanent(classified.userMessage, classified.kind)
        } else {
            android.util.Log.w("Conch-SK-Dlg", "  transient failure: ${classified.userMessage} — retrying")
            // Backoff so the previous TCP socket closes, NFC reader-
            // mode releases, and the user has a moment to react.
            kotlinx.coroutines.delay(2_000L)
            onRetry(classified.userMessage)
        }
    }
}

/**
 * Categorisation of the SK auth failure for UI rendering.
 *
 * Ordered by SSH lifecycle stage: TCP → handshake → host-key →
 * authentication → channel → post-auth → SK-token-internal →
 * transient → catchall. The classifier walks branches in roughly
 * this order so a more specific kind never gets shadowed by a
 * generic one.
 *
 * Most kinds are `permanent = true` — re-tapping the security key
 * cannot fix a wrong host, a closed port, a banned IP, or a server
 * that doesn't speak SSH. Only the genuinely-transient ones (NFC
 * tag wobbles, mid-session blips) set `permanent = false` so the
 * dialog quietly re-arms.
 */
enum class SkFailureKind {
    // ─────── TCP / transport layer (before SSH banner) ───────
    /** TCP connect never opened — packets dropped on the floor. Most
     *  likely a firewall silently filtering port 22, or no SSH daemon
     *  listening. Distinct from [ConnectionRefused] (RST received). */
    ServerUnreachable,
    /** TCP connect got an RST — port closed (no daemon), or the server
     *  explicitly refused us. Faster failure than [ServerUnreachable]. */
    ConnectionRefused,
    /** DNS resolution failed — host string is bad (typo / wrong domain). */
    BadHostname,
    /** Local network has no route to the server's IP — phone is offline,
     *  on a captive portal, or routing is broken. */
    NoNetwork,

    // ─────── SSH protocol negotiation (after TCP, before auth) ───────
    /** Port answered but didn't send an SSH banner — wrong port, or
     *  some other service on that port (HTTP, mosquitto, etc). */
    ProtocolMismatch,
    /** SSH version exchange succeeded but kex / cipher / MAC algorithms
     *  couldn't agree on a common set. Old server, locked-down config. */
    KexFailure,

    // ─────── Host key verification ───────
    /** Server's host key fingerprint changed since last time. Could be
     *  MITM or legitimate re-key. UX: warn, don't auto-retry. */
    HostKeyMismatch,

    // ─────── Authentication ───────
    /** Server doesn't have this key in `authorized_keys`. Most likely
     *  the registered key was rotated on the server side. UX: nudge
     *  the user toward the Keychain screen to register a fresh one. */
    KeyNotAuthorized,
    /** Password rejected (only ever fired for AuthMethod.PASSWORD). */
    AuthFailedPassword,
    /** Server's `MaxAuthTries` exceeded — typically 3 failed attempts.
     *  Server disconnects; the user must wait or fix credentials. */
    TooManyAuthFailures,
    /** Server config rule (`AllowUsers`, `DenyUsers`, `Match` block)
     *  forbade this username. Auth-time but not credential-fixable. */
    UserNotAllowed,

    // ─────── Post-auth / channel ───────
    /** Server-side `ForceCommand` / `AllowAgentForwarding no` etc
     *  rejected the exec channel after auth succeeded. Rare — only fires
     *  on hardened bastion / restricted-shell setups. */
    ChannelDenied,
    /** Server actively disconnected us (fail2ban, sshd `MaxStartups`
     *  throttle, idle-timeout). Distinct from a network blip because
     *  the server explicitly chose to close, not the link wobbling. */
    ServerDisconnect,

    // ─────── Security key (CTAP / NFC / USB) — token-internal ───────
    /** Token was lifted mid-operation; user can tap again. */
    TagLost,
    /** Token was lifted specifically WHILE the user was typing the PIN.
     *  We don't bounce them back to the "Tap or plug" screen — instead
     *  the dialog keeps the PIN UI visible but in a paused state with
     *  "Put the key back" copy and the wave animation stopped. The
     *  auto-retry re-arm runs invisibly behind it so the moment the
     *  user re-taps, runEnumerateAndHoldSession enters again and
     *  flips the dialog back to normal PIN entry. */
    TagLostDuringPin,
    /** Token rejected for an internal CTAP reason (PIN, no creds). */
    TokenRejected,
    /** Wrong PIN entered — CTAP_ERR_PIN_INVALID. Recoverable: user
     *  enters PIN again. Bumps the local attempts counter in the
     *  dialog. After 8 of these on the same hardware the token
     *  itself blocks PIN (returns PinBlocked instead — see below). */
    WrongPin,
    /** PIN auth blocked for this session — CTAP_ERR_PIN_AUTH_BLOCKED.
     *  Fires after 3 consecutive wrong PINs in one card session.
     *  Recoverable: user unplugs/replugs (or re-taps for NFC) to
     *  reset the per-session counter. PIN itself is still alive. */
    PinAuthBlocked,
    /** Token-level PIN permanently blocked — CTAP_ERR_PIN_BLOCKED.
     *  Fires after 8 total wrong PINs across all sessions. **Not
     *  recoverable from the dialog** — the user must factory-reset
     *  the FIDO2 application on the token (`ykman fido reset`)
     *  which wipes every credential on the key. */
    PinBlocked,

    // ─────── Transient (auto-retry is appropriate) ───────
    /** Mid-session TCP/TLS hiccup; the next connect rebuilds the socket
     *  from scratch so retry usually fixes it. */
    NetworkBlip,

    // ─────── Catchall ───────
    /** Anything we couldn't classify. */
    Unknown,
}

private data class SkFailureClass(
    val permanent: Boolean,
    val userMessage: String,
    val kind: SkFailureKind,
)

/**
 * Walk the exception's cause chain looking for the most specific
 * reason Conch's auth failed, then decide whether retrying makes
 * sense. The outer "Exhausted available authentication methods" that
 * sshj raises is useless to the user — the real reason almost always
 * lives 2-3 causes down (e.g. our own SkAuthPublickey threw because
 * the server returned USERAUTH_FAILURE before we even called sign()).
 */
private fun classifySkFailure(
    t: Throwable,
    /**
     * The signer's phase at the moment the throwable escaped the
     * coroutineScope. Lets the classifier distinguish a real
     * server-rejected-the-signature failure (signer made it to
     * [SkSessionHolder.Phase.Done]) from a tag/link death mid-sign
     * (signer was in TagCaptured / WaitingForPin / WaitingForTap when
     * the exception bubbled up). Without it, every mid-sign tag loss
     * got sshj's "Exhausted available authentication methods" outer
     * wrapper and was mis-classified as KeyNotAuthorized — telling
     * the user "register a new key" when actually they just lifted
     * the NFC tag a quarter-second early.
     */
    signerPhaseAtFailure: DeferredCtapSkSigner.Phase = DeferredCtapSkSigner.Phase.Failed,
): SkFailureClass {
    // Collect all messages in the cause chain.
    val chain = generateSequence(t as Throwable?) { it.cause }.take(8).toList()
    val joined = chain.joinToString(" | ") { "${it.javaClass.simpleName}: ${it.message ?: "?"}" }

    // Visibility: dump the full chain + phase at debug level so we can
    // see which classifier branch SHOULD have caught a given failure.
    android.util.Log.d("Conch-SK-Dlg", "  classifying chain (phaseAtFailure=$signerPhaseAtFailure): $joined")

    // 0a. Tag-lift detected by the in-PIN watcher (cancel reason ends with
    //     'TagLost during PIN entry'). Must come BEFORE the generic
    //     'PIN entry' cancel match below, otherwise that broader regex
    //     would shadow this kind into Unknown and the dialog would
    //     flip to the generic 'Couldn't connect' Failed state instead
    //     of the soft pause we want.
    if (joined.contains("TagLost during PIN entry", ignoreCase = true)) {
        return SkFailureClass(
            permanent = true,
            userMessage = "Put the security key back to continue.",
            kind = SkFailureKind.TagLostDuringPin,
        )
    }

    // 0pre. User cancelled / dialog dismissed / NFC timed out without a tap.
    //       sshj wraps these as "Exhausted available authentication methods"
    //       — the next check would mis-classify them as KeyNotAuthorized
    //       and shame the user with "Open Keychain to register a fresh key"
    //       even though they never tapped anything. Catch them first.
    val cancelRe = Regex(
        "user cancelled|dialog (dismissed|cancelled)|no NFC tap detected|user pressed cancel|opening keychain|classifying:|timeout expired|TimeoutException",
        RegexOption.IGNORE_CASE,
    )
    if (chain.any { cancelRe.containsMatchIn(it.message.orEmpty()) }) {
        return SkFailureClass(
            // permanent=true here means "stop the auto-retry loop". The
            // user explicitly didn't attach a key (or cancelled / timed out
            // waiting), so there's no point silently retrying every 30 s.
            // The dialog stays with "Cancelled" until they swipe to retry.
            permanent = true,
            userMessage = "No key detected. Plug it in or tap the back of the phone.",
            kind = SkFailureKind.Unknown,
        )
    }
    // PIN entry interrupted (user dismissed the pad before submitting).
    if (chain.any { it.message?.contains("PIN entry", ignoreCase = true) == true }) {
        return SkFailureClass(
            permanent = true,
            userMessage = "PIN entry cancelled.",
            kind = SkFailureKind.Unknown,
        )
    }
    if (joined.contains("Tag is out of date", ignoreCase = true) ||
        joined.contains("TagLost", ignoreCase = true) ||
        joined.contains("Tag was lost", ignoreCase = true) ||
        joined.contains("removed too early", ignoreCase = true)) {
        return SkFailureClass(
            permanent = true,  // don't auto-retry — user has to re-attach
            userMessage = "Security key disconnected. Keep it in place and try again.",
            kind = SkFailureKind.TagLost,
        )
    }

    // 0a. CTAP ERR_NO_CREDENTIALS — the token doesn't recognise the
    //     credentialId stored in the app's keychain. Happens when:
    //       • the user deleted the credential from the token on their desktop
    //       • two credentials were imported and the wrong one's ID got stored
    //     The server may still return USERAUTH_60 (public key recognised),
    //     but getAssertions fails with 0x2e.  Fix: delete + re-import from
    //     Keychain so the app learns the current credentialId off the token.
    //     Must be checked BEFORE the "Exhausted" wrapper below — sshj
    //     wraps everything in "Exhausted available authentication methods"
    //     which would otherwise shadow this more specific cause.
    if (chain.any { it.message?.contains("does not hold a credential", ignoreCase = true) == true }) {
        return SkFailureClass(
            permanent = true,
            // Most common cause is "user tapped a DIFFERENT security key
            // than the one registered for this server" — phrase the message
            // around that. The "key was regenerated" / "deleted on desktop"
            // case is real but rare; mention it as the secondary reason
            // and surface Keychain as a fallback action.
            userMessage = "I don't know this security key yet. Tap one that's already enrolled — or add this one to the server.",
            kind = SkFailureKind.TokenRejected,
        )
    }

    // 1a. "Exhausted available authentication methods" (sshj's outer
    //     wrapper). With our single-method setup (only SkAuthPublickey
    //     registered), exhaustion can mean two completely different
    //     things — and we have to use the signer's phase to tell them
    //     apart, because sshj wraps both in the SAME outer message:
    //
    //     A) Signer made it to Phase.Done → sign() returned a valid
    //        signature → server REJECTED it. This is the real
    //        "KeyNotAuthorized" case — nudge user to Keychain to
    //        register a fresh key.
    //
    //     B) Signer never reached Phase.Done → sign() threw before
    //        completing the CTAP getAssertion (NFC tag lifted, USB
    //        unplugged, CTAP internal error). The server NEVER saw a
    //        signature. Classifying this as KeyNotAuthorized was the
    //        bug behind "tap key, type PIN, lift, see 'server doesn't
    //        recognise this key'" — the server didn't reject anything,
    //        the LINK died.
    if (chain.any { it.message?.contains("Exhausted available authentication methods", ignoreCase = true) == true }) {
        return if (signerPhaseAtFailure == DeferredCtapSkSigner.Phase.Done) {
            SkFailureClass(
                permanent = true,
                userMessage = "The server doesn't recognise this security key. The key may have been rotated, or this security key isn't enrolled on this server yet.\n\nOpen Keychain to register a fresh key for this server.",
                kind = SkFailureKind.KeyNotAuthorized,
            )
        } else {
            SkFailureClass(
                // Transient — user just needs to re-tap and hold a moment longer.
                permanent = false,
                userMessage = "Security key disconnected before the signature completed. Tap again and keep it in place until you see Done.",
                kind = SkFailureKind.TagLost,
            )
        }
    }

    // 1b. Server rejected the key without asking for a signature
    //     (USERAUTH_FAILURE on the test request). The test request is
    //     sent BEFORE sign() runs — if we got rejected at that stage,
    //     the signer never advanced past WaitingForTap and so the
    //     phase-aware check above doesn't apply here. This branch is
    //     a real KeyNotAuthorized: server saw our pubkey and refused.
    val rejectedRe = Regex("server rejected sk-ssh-ed25519 key|server rejected the key|accepts methods: ", RegexOption.IGNORE_CASE)
    if (chain.any { rejectedRe.containsMatchIn(it.message.orEmpty()) }) {
        return SkFailureClass(
            permanent = true,
            userMessage = "The server doesn't recognize the security key registered for it. The key may have been rotated, or this security key isn't enrolled here yet.\n\nOpen Keychain to register a fresh key for this server.",
            kind = SkFailureKind.KeyNotAuthorized,
        )
    }

    // 2. Wrong host key fingerprint.
    if (joined.contains("HostKey", ignoreCase = true) ||
        joined.contains("known_hosts", ignoreCase = true) ||
        joined.contains("fingerprint", ignoreCase = true)) {
        return SkFailureClass(
            permanent = true,
            userMessage = "The server's identity changed — its SSH host key doesn't match what we saved last time. This can happen after a server reinstall, or it could mean a man-in-the-middle. Worth checking before reconnecting.",
            kind = SkFailureKind.HostKeyMismatch,
        )
    }

    // 3. NFC tag died mid-operation.
    if (joined.contains("Tag is out of date", ignoreCase = true) ||
        joined.contains("TagLost", ignoreCase = true) ||
        joined.contains("Tag was lost", ignoreCase = true)) {
        return SkFailureClass(
            permanent = false,
            userMessage = "Tag lifted too early — tap again and hold until you see Done.",
            kind = SkFailureKind.TagLost,
        )
    }

    // 3a. PIN-specific failures. Three distinct CTAP statuses we
    //     translate from the wrapped SkAuthException message:
    //
    //   - PIN_BLOCKED        — 8 wrong attempts hit, token-level lock.
    //                          NOT recoverable from dialog. Tell user
    //                          how to factory-reset via ykman.
    //   - PIN_AUTH_BLOCKED   — 3 wrong in a session. Unplug+replug
    //                          resets. PIN itself still alive.
    //   - PIN_INVALID        — wrong PIN this attempt. Recoverable;
    //                          dialog increments local counter and
    //                          shows "X / 8 used".
    //
    // The classifier order matters: check BLOCKED before INVALID
    // because both messages contain the word "PIN".
    if (joined.contains("PIN is blocked", ignoreCase = true) ||
        joined.contains("ERR_PIN_BLOCKED", ignoreCase = true)) {
        return SkFailureClass(
            permanent = true,
            userMessage = "The security key's PIN is blocked after too many wrong attempts. The token won't accept any PIN now — only a factory reset will recover it (and that wipes every credential stored on it).\n\nOn the desktop: `ykman fido reset`.",
            kind = SkFailureKind.PinBlocked,
        )
    }
    if (joined.contains("PIN attempts exhausted for this session", ignoreCase = true) ||
        joined.contains("ERR_PIN_AUTH_BLOCKED", ignoreCase = true)) {
        return SkFailureClass(
            permanent = false,
            userMessage = "Too many wrong PIN attempts this session. Unplug your security key (or remove it from NFC) for a moment, then try again — that resets the per-session counter without affecting the PIN itself.",
            kind = SkFailureKind.PinAuthBlocked,
        )
    }
    if (joined.contains("wrong PIN", ignoreCase = true) ||
        joined.contains("PIN authentication failed", ignoreCase = true) ||
        joined.contains("ERR_PIN_INVALID", ignoreCase = true) ||
        joined.contains("ERR_PIN_AUTH_INVALID", ignoreCase = true)) {
        return SkFailureClass(
            permanent = false,
            // The dialog renders the "× / 8" counter itself — we
            // just say "wrong, try again" here. Counter logic lives
            // in the composable so it can survive across re-arms.
            userMessage = "Wrong PIN. Try again.",
            kind = SkFailureKind.WrongPin,
        )
    }

    // 4. CTAP token said no.
    if (joined.contains("CTAP", ignoreCase = true) ||
        joined.contains("token", ignoreCase = true) ||
        joined.contains("CredentialId", ignoreCase = true)) {
        val ctapMsg = chain.firstOrNull { it.message?.contains("token", ignoreCase = true) == true }?.message
        return SkFailureClass(
            permanent = true,
            userMessage = ctapMsg ?: "The security key didn't respond to that request.",
            kind = SkFailureKind.TokenRejected,
        )
    }

    // Helper: pull "ip:port" out of a typical sshj exception message
    // for inclusion in error copy. Returns "" when no match (so callers
    // can blindly interpolate without conditional checks bloating the
    // classifier).
    val hostPortHint: String = Regex("""/([\d.a-fA-F:]+).*?\(port (\d+)\)""")
        .find(joined)?.let { " (${it.groupValues[1]}:${it.groupValues[2]})" } ?: ""

    // 5a. DNS resolution failed — wrong hostname.
    if (chain.any {
            it is java.net.UnknownHostException
                || it.message?.contains("Unable to resolve host", ignoreCase = true) == true
                || it.message?.contains("nodename nor servname provided", ignoreCase = true) == true
        }) {
        return SkFailureClass(
            permanent = true,
            userMessage = "DNS can't find that hostname. Check spelling — or use the IP address directly.",
            kind = SkFailureKind.BadHostname,
        )
    }

    // 5b. No network at all — local route is broken.
    if (chain.any {
            it is java.net.NoRouteToHostException
                || it.message?.contains("Network is unreachable", ignoreCase = true) == true
                || it.message?.contains("No route to host", ignoreCase = true) == true
        }) {
        return SkFailureClass(
            permanent = true,
            userMessage = "No network route to the server. Phone offline, captive portal, or VPN dropped?",
            kind = SkFailureKind.NoNetwork,
        )
    }

    // 5c. TCP RST — port actively closed (no SSH daemon). Faster failure
    //     than the timeout below; distinct user-facing copy because the
    //     "definitely closed" answer is more actionable than "didn't reply
    //     in time".
    if (chain.any {
            it is java.net.ConnectException
                || it.message?.contains("Connection refused", ignoreCase = true) == true
        }) {
        return SkFailureClass(
            permanent = true,
            userMessage = "The server refused the connection$hostPortHint — SSH isn't running on that port, or the port is closed.",
            kind = SkFailureKind.ConnectionRefused,
        )
    }

    // 5d. TCP connect TIMED OUT — packets silently dropped. Either the
    //     host is down, or a firewall is filtering. Distinct from 5b
    //     (no route) — here the route exists but nothing answers.
    //
    //     CRITICAL: must come BEFORE the generic NetworkBlip branch.
    //     The earlier `networkRe` was catching every `"timed out"`
    //     substring including this one and classifying it as a
    //     transient blip with `permanent = false`. Result: dialog
    //     auto-retried, fired another connect, timed out again, the
    //     user saw an infinite "tap key → enter PIN → tap key → enter
    //     PIN" loop instead of "your server is unreachable".
    val connectTimeoutRe = Regex(
        "failed to connect.*after \\d+ms",
        RegexOption.IGNORE_CASE,
    )
    val isConnectTimeout = chain.any {
        (it is java.net.SocketTimeoutException
            && it.message?.contains("failed to connect", ignoreCase = true) == true)
            || connectTimeoutRe.containsMatchIn(it.message.orEmpty())
    }
    if (isConnectTimeout) {
        return SkFailureClass(
            permanent = true,
            userMessage = "Can't reach the server$hostPortHint. The port isn't answering — firewall closed, SSH not running, or wrong port. Tapping the security key again won't help.",
            kind = SkFailureKind.ServerUnreachable,
        )
    }

    // 5e. Port answered but didn't speak SSH — wrong port (someone's
    //     HTTP / Redis / IMAP running there), or middlebox stripping
    //     the SSH banner. sshj surfaces this as TransportException
    //     with "Server identification" / "bad version" / "kex_exchange
    //     _identification" / "Bad packet length" messages.
    val protocolRe = Regex(
        "server identification|invalid server|bad version|kex_exchange_identification|Bad packet length|Premature SSH version",
        RegexOption.IGNORE_CASE,
    )
    if (chain.any { protocolRe.containsMatchIn(it.message.orEmpty()) }) {
        return SkFailureClass(
            permanent = true,
            userMessage = "Something is answering on that port but it isn't SSH$hostPortHint. Wrong port number?",
            kind = SkFailureKind.ProtocolMismatch,
        )
    }

    // 5f. Kex / cipher / MAC algorithm negotiation failed. Old sshd on
    //     a locked-down config that doesn't share any algorithms with
    //     sshj's defaults. Rare on modern servers.
    val kexRe = Regex(
        "no kex algorithms in common|no kex algorithms|no common encryption|no common ciphers|no matching mac|MAC algorithms|key exchange algorithm",
        RegexOption.IGNORE_CASE,
    )
    if (chain.any { kexRe.containsMatchIn(it.message.orEmpty()) }) {
        return SkFailureClass(
            permanent = true,
            userMessage = "Couldn't agree on encryption algorithms with the server. The server's SSH config may be too restrictive — ask the admin.",
            kind = SkFailureKind.KexFailure,
        )
    }

    // 5g. Server explicitly disconnected us — fail2ban / rate-limit /
    //     MaxStartups / idle-timeout. Distinct from a TCP-layer drop
    //     because the server's sshd CHOSE to close the connection (we
    //     get a DISCONNECT packet, not a TCP reset).
    val disconnectRe = Regex(
        "disconnection received|disconnected by application|Connection closed by|received disconnect|too many concurrent|server refused to start a shell session",
        RegexOption.IGNORE_CASE,
    )
    if (chain.any { disconnectRe.containsMatchIn(it.message.orEmpty()) }) {
        return SkFailureClass(
            permanent = true,
            userMessage = "The server cut the connection. Could be fail2ban / rate-limit (too many failed attempts) — wait a few minutes and try again.",
            kind = SkFailureKind.ServerDisconnect,
        )
    }

    // 5h. Too many authentication failures — server's `MaxAuthTries`
    //     exceeded. The server disconnects after the limit; user must
    //     wait for fail2ban to release or check the credentials.
    if (chain.any {
            it.message?.contains("Too many authentication failures", ignoreCase = true) == true
        }) {
        return SkFailureClass(
            permanent = true,
            userMessage = "Too many failed authentication attempts. The server may have temporarily banned your IP — wait a few minutes.",
            kind = SkFailureKind.TooManyAuthFailures,
        )
    }

    // 5i. AllowUsers / DenyUsers / Match block rejected. Surfaces as a
    //     UserAuthException with "User <name> from <ip> not allowed
    //     because not listed in AllowUsers" — sshd's standard message
    //     when the username isn't in the server's whitelist.
    if (chain.any {
            it.message?.contains("not allowed because", ignoreCase = true) == true
                || it.message?.contains("not listed in AllowUsers", ignoreCase = true) == true
                || it.message?.contains("listed in DenyUsers", ignoreCase = true) == true
        }) {
        return SkFailureClass(
            permanent = true,
            userMessage = "The server is configured to reject this user. Check the username — or ask the admin about AllowUsers / DenyUsers rules.",
            kind = SkFailureKind.UserNotAllowed,
        )
    }

    // 5j. Wrong password — only fires when the SERVER actually saw a
    //     password attempt (i.e. server returned USERAUTH_FAILURE on
    //     a password method). For Auth.PASSWORD servers only. SK and
    //     key servers route through KeyNotAuthorized above.
    val passwordRejectRe = Regex(
        "password authentication failed|Permission denied \\(password|Authentication failed: password",
        RegexOption.IGNORE_CASE,
    )
    if (chain.any { passwordRejectRe.containsMatchIn(it.message.orEmpty()) }) {
        return SkFailureClass(
            permanent = true,
            userMessage = "Wrong password for this user. Edit the server and update it.",
            kind = SkFailureKind.AuthFailedPassword,
        )
    }

    // 5k. Post-auth channel rejection — ForceCommand / restricted shell
    //     etc. The user authenticated successfully but can't exec.
    val channelRe = Regex(
        "channel open failure|administratively prohibited|open failed: \\d+|Couldn't open|forced-command|disallowed",
        RegexOption.IGNORE_CASE,
    )
    if (chain.any { channelRe.containsMatchIn(it.message.orEmpty()) }) {
        return SkFailureClass(
            permanent = true,
            userMessage = "Server allowed the login but refused the exec channel. Likely a restricted shell or ForceCommand config — ask the admin.",
            kind = SkFailureKind.ChannelDenied,
        )
    }

    // 5l. Mid-session network blip — already past connect, link wobbled.
    //     Retry IS appropriate here because the next attempt rebuilds
    //     the TCP socket from scratch.
    val networkRe = Regex("Connection reset|read timeout|EOFException|Software caused connection abort|Broken pipe", RegexOption.IGNORE_CASE)
    if (chain.any { networkRe.containsMatchIn(it.message.orEmpty()) }) {
        return SkFailureClass(
            permanent = false,
            userMessage = "Network blip — trying again.",
            kind = SkFailureKind.NetworkBlip,
        )
    }

    // 6. Default.
    val firstUseful = chain.firstOrNull { !it.message.isNullOrBlank() }?.message
        ?: t.javaClass.simpleName
    return SkFailureClass(permanent = false, userMessage = firstUseful, kind = SkFailureKind.Unknown)
}

/**
 * Like [runDeferredNfcAttempt] but races a USB await against NFC reader-
 * mode. The first transport to deliver a [Ctap2Session] feeds the signer;
 * the loser is cancelled. The op (SSH userauth via deferred signer) is the
 * same single instance regardless of which channel won.
 */
private suspend fun runDeferredEitherAttempt(
    activity: android.app.Activity,
    signer: DeferredCtapSkSigner,
    onNfcSigner: suspend (SkSigner) -> Unit,
    onSuccess: () -> Unit,
    onRetry: (reason: String) -> Unit,
    onPermanent: (reason: String, kind: SkFailureKind) -> Unit,
    onTransportResolved: (SecurityKeyTransport) -> Unit = {},
) {
    val tag = "Conch-SK-Dlg"
    try {
        coroutineScope {
            val opJob = async(Dispatchers.IO) { onNfcSigner(signer) }
            // NFC arm: same as the NFC-only path.
            val nfcJob = async(Dispatchers.IO) {
                runDeferredNfcDance(activity, signer) { onTransportResolved(SecurityKeyTransport.NFC) }
            }
            // USB arm: poll awaitUsb. Once a device shows up, open a
            // Ctap2Session on it and feed the signer. The session must
            // stay open until signer.signDone resolves so getAssertions
            // doesn't observe a closed connection.
            val usbJob = async(Dispatchers.IO) {
                val device = ai.eight24family.conch.di.ServiceLocator.securityKeyManager
                    .awaitUsb(timeoutMs = Long.MAX_VALUE) ?: return@async
                android.util.Log.d(tag, "  USB device arrived — opening Ctap2Session")
                onTransportResolved(SecurityKeyTransport.USB)
                signer.markTagCaptured()
                val fidoCls = com.yubico.yubikit.core.fido.FidoConnection::class.java
                val scCls = com.yubico.yubikit.core.smartcard.SmartCardConnection::class.java
                try {
                    when {
                        device.supportsConnection(fidoCls) -> {
                            device.openConnection(fidoCls).use { conn ->
                                com.yubico.yubikit.fido.ctap.Ctap2Session(conn).use { session ->
                                    runEnumerateAndHoldSession(tag, signer, session)
                                }
                            }
                        }
                        device.supportsConnection(scCls) -> {
                            device.openConnection(scCls).use { conn ->
                                com.yubico.yubikit.fido.ctap.Ctap2Session(conn).use { session ->
                                    runEnumerateAndHoldSession(tag, signer, session)
                                }
                            }
                        }
                        else -> {
                            android.util.Log.e(tag, "  USB device offers neither FIDO nor SmartCard")
                        }
                    }
                } catch (t: Throwable) {
                    android.util.Log.w(tag, "  USB Ctap2Session setup failed: ${t.javaClass.simpleName}: ${t.message}")
                    signer.cancel("USB device disconnected during setup — ${t.javaClass.simpleName}")
                }
            }
            opJob.await()
            // Whichever arm fed the signer is now done. Cancel the other so
            // we don't leave a USB poller hanging or NFC reader-mode armed.
            usbJob.cancel()
            nfcJob.cancel()
        }
        onSuccess()
    } catch (e: kotlinx.coroutines.CancellationException) {
        signer.cancel("dialog cancelled")
        throw e
    } catch (t: Throwable) {
        // Snapshot signer phase BEFORE cancel() flips it to Failed.
        // Used by the classifier to distinguish "server rejected our
        // signature" (signer reached Phase.Done) from "tag/link died
        // mid-sign" (signer was in TagCaptured / WaitingForPin / etc.
        // when the throw bubbled up). Without this distinction every
        // mid-sign tag loss got "Exhausted methods" from sshj's outer
        // wrapper and was mis-classified as KeyNotAuthorized.
        val signerPhaseAtFailure = signer.phase.value
        signer.cancel("classifying: ${t.message}")
        val classified = classifySkFailure(t, signerPhaseAtFailure)
        if (classified.permanent) {
            android.util.Log.w(tag, "  permanent failure: ${classified.userMessage}")
            onPermanent(classified.userMessage, classified.kind)
        } else {
            android.util.Log.w(tag, "  transient failure: ${classified.userMessage} — retrying")
            kotlinx.coroutines.delay(2_000L)
            onRetry(classified.userMessage)
        }
    }
}

private suspend fun runDeferredNfcDance(
    activity: android.app.Activity,
    signer: DeferredCtapSkSigner,
    onTagCaptured: () -> Unit = {},
) {
    val tag = "Conch-SK-Dlg"
    android.util.Log.d(tag, "  runDeferredNfcDance: arming reader-mode")
    val ok = ServiceLocator.securityKeyManager.withNfc(
        activity = activity,
        timeoutMs = Long.MAX_VALUE,
    ) { device ->
        android.util.Log.d(tag, "  NFC tag captured (${device.javaClass.simpleName})")
        onTagCaptured()
        signer.markTagCaptured()
        try {
            when {
                device.supportsConnection(FidoConnection::class.java) -> {
                    device.openConnection(FidoConnection::class.java).use { conn ->
                        Ctap2Session(conn).use { session ->
                            runEnumerateAndHoldSession(tag, signer, session)
                        }
                    }
                }
                device.supportsConnection(SmartCardConnection::class.java) -> {
                    device.openConnection(SmartCardConnection::class.java).use { conn ->
                        Ctap2Session(conn).use { session ->
                            runEnumerateAndHoldSession(tag, signer, session)
                        }
                    }
                }
                else -> {
                    android.util.Log.e(tag, "  device offers neither FIDO nor SmartCard")
                    signer.cancel("device has no usable transport")
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w(tag, "  Ctap2Session setup/run failed: ${t.javaClass.simpleName}: ${t.message}")
            signer.cancel("security key was removed too early — ${t.javaClass.simpleName}")
        }
        true
    }
    if (ok != true) {
        android.util.Log.w(tag, "  NFC dance ended without a tap")
        signer.cancel("no NFC tap detected — timed out")
    }
}

/**
 * Inside the NFC `withNfc{}` (or USB analog) callback, drive the
 * complete CTAP-side flow on the freshly-opened [session]:
 *
 *   1. Set phase=WaitingForPin so the dialog mounts its PIN pad.
 *   2. Block on the user's PIN (via signer.holder.awaitPin()).
 *   3. getPinToken(CM, null) → run credentialManagement.enumerate*,
 *      filter to creds whose rpId matches our server.
 *   4. For each enumerated cred, persist via SshKeyRepository
 *      (dedup-aware) and attach to the server.
 *   5. Pick a matched credId — preferring one already in
 *      [holder.candidateCredIds] (i.e. server already had it
 *      attached) so we don't shuffle the user's primary on every
 *      connect; otherwise the first new one.
 *   6. Cache PIN bytes + protocol on the holder so signer.sign() can
 *      re-mint a GA-scoped pinToken for the actual signature.
 *   7. Signal holder.tokenCredsReady so the pool can build the auth
 *      method and call client.auth(). Provide the session so
 *      signer.sign() unblocks.
 *   8. Wait on signDone — held until the pool's auth flow finishes.
 *      This keeps the IsoDep / USB session open across SSH userauth.
 *
 * Throws back into the withNfc{} callback on any CTAP / I/O error so
 * the outer try/catch can run signer.cancel() and unblock the dialog.
 */
private fun runEnumerateAndHoldSession(
    tag: String,
    signer: DeferredCtapSkSigner,
    session: Ctap2Session,
) {
    val holder = signer.holder
    android.util.Log.d(tag, "  runEnumerateAndHoldSession: entered, flipping phase=WaitingForPin")
    holder.phase.value = ai.eight24family.conch.ssh.securitykey.SkSessionHolder.Phase.WaitingForPin

    // Hoisted so the outer finally (which wipes it) sees the successful PIN
    // across the retry loop below.
    var pinChars: CharArray? = null
    try {
        // PIN protocol is PIN-independent — pick it once, before the entry loop.
        val proto = try {
            ai.eight24family.conch.ssh.securitykey.pickPinProtocolFor(session)
        } catch (t: Throwable) {
            android.util.Log.w(tag, "  pickPinProtocolFor threw: ${t.javaClass.simpleName}: ${t.message}")
            signer.cancel("PIN protocol probe failed: ${t.javaClass.simpleName}")
            return
        } ?: run {
            android.util.Log.w(tag, "  pickPinProtocolFor returned null")
            signer.cancel("token does not advertise a PIN/UV protocol")
            return
        }
        android.util.Log.d(tag, "  pin protocol picked: v${proto.version}")
        // PIN-entry loop. A WRONG PIN (CTAP 0x31 / auth-invalid) is recoverable
        // IN PLACE: the key is still held, so we re-arm the PIN wait and
        // re-prompt in the SAME pad — holder.noteWrongPin() makes the dialog
        // shake, buzz 0.3 s, and show the N/8 counter. NEVER a "Connect failed"
        // dialog (the user's complaint). A per-session PIN lock (0x34) or a
        // token-level PIN block (0x32) is NOT recoverable here → cancel with a
        // message the classifier maps to the right guidance.
        var ct: ByteArray? = null
        while (ct == null) {
            // Tag-presence watcher for THIS entry — yubikit won't notify us if
            // the user lifts the key mid-PIN, so poll a cheap CTAP read every
            // 1.2 s and abort awaitPin with null on loss (→ "removed too early").
            val watcher = Thread {
                var alive = true
                while (alive && !Thread.currentThread().isInterrupted) {
                    try {
                        Thread.sleep(1200)
                    } catch (_: InterruptedException) {
                        break
                    }
                    if (holder.pinSubmitted) break
                    try {
                        session.info  // re-fetches authenticatorGetInfo
                    } catch (t: Throwable) {
                        android.util.Log.w(tag, "  tag presence probe failed during PIN entry: ${t.javaClass.simpleName} → aborting")
                        alive = false
                        holder.providePin(null)
                        break
                    }
                }
            }.also {
                it.name = "Conch-SK-TagWatcher"
                it.isDaemon = true
                it.start()
            }

            val entered = runBlocking { holder.awaitPin() }
            // Stop the watcher BEFORE issuing any CTAP command on the same
            // session — Ctap2Session / FidoConnection are NOT thread-safe, and
            // two concurrent commands scramble the USB endpoint. join() lets an
            // in-flight session.info() round-trip finish first.
            SilentlyTry.fired("Conch-SkDialog", "interrupt watcher thread") { watcher.interrupt() }
            SilentlyTry.fired("Conch-SkDialog", "join watcher thread") { watcher.join(2_000L) }
            if (watcher.isAlive) {
                android.util.Log.w(tag, "  watcher thread didn't exit after 2s — may collide with main session use")
            }
            android.util.Log.d(tag, "  awaitPin returned: ${if (entered == null) "null (cancelled)" else "got ${entered.size} chars"}")
            if (entered == null) {
                signer.cancel("security key was removed too early — TagLost during PIN entry")
                return
            }
            pinChars = entered
            holder.phase.value = ai.eight24family.conch.ssh.securitykey.SkSessionHolder.Phase.TagCaptured
            android.util.Log.d(tag, "  phase=TagCaptured (post-PIN); requesting CM pin token")
            try {
                ct = com.yubico.yubikit.fido.ctap.ClientPin(session, proto).getPinToken(
                    entered,
                    com.yubico.yubikit.fido.ctap.ClientPin.PIN_PERMISSION_CM,
                    /* permissionRpId */ null,
                )
            } catch (e: com.yubico.yubikit.core.fido.CtapException) {
                android.util.Log.w(tag, "  getPinToken(CM) failed ctapErr=0x${"%02x".format(e.ctapError)}")
                when (e.ctapError) {
                    com.yubico.yubikit.core.fido.CtapException.ERR_PIN_INVALID,
                    com.yubico.yubikit.core.fido.CtapException.ERR_PIN_AUTH_INVALID -> {
                        // Wrong PIN — recover IN PLACE. Read the token's
                        // remaining attempts (out of 8) for the counter, then
                        // re-arm + signal the dialog (shake + buzz). Loop back to
                        // awaitPin without cancelling the SSH userauth.
                        val left = SilentlyTry.logged(tag, "read pin retries") {
                            com.yubico.yubikit.fido.ctap.ClientPin(session, proto).pinRetries.count
                        }
                        android.util.Log.w(tag, "  wrong PIN — retriesLeft=$left — re-prompting in the same pad")
                        for (i in entered.indices) entered[i] = ' '  // wipe the rejected PIN
                        pinChars = null
                        holder.noteWrongPin(left)
                    }
                    com.yubico.yubikit.core.fido.CtapException.ERR_PIN_AUTH_BLOCKED -> {
                        signer.cancel("ERR_PIN_AUTH_BLOCKED — too many wrong PIN attempts this session")
                        return
                    }
                    com.yubico.yubikit.core.fido.CtapException.ERR_PIN_BLOCKED -> {
                        signer.cancel("ERR_PIN_BLOCKED — the security key's PIN is blocked")
                        return
                    }
                    else -> {
                        signer.cancel("PIN error 0x${"%02x".format(e.ctapError)}")
                        return
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w(tag, "  getPinToken(CM) threw: ${t.javaClass.simpleName}: ${t.message}", t)
                signer.cancel("PIN token request failed: ${t.javaClass.simpleName}")
                return
            }
        }
        val cmToken = ct!!
        android.util.Log.d(tag, "  getPinToken(CM) ok (${cmToken.size}B)")
        val mgmt = com.yubico.yubikit.fido.ctap.CredentialManagement(session, proto, cmToken)
        val tokenCreds = mutableListOf<EnumeratedCred>()
        val rps = try {
            mgmt.enumerateRps()
        } catch (e: com.yubico.yubikit.core.fido.CtapException) {
            android.util.Log.w(tag, "  enumerateRps failed ctapErr=0x${"%02x".format(e.ctapError)}")
            signer.cancel("Security key's PIN state is locked — lift the key, wait a couple seconds, tap again.")
            return
        } catch (t: Throwable) {
            android.util.Log.w(tag, "  enumerateRps threw: ${t.javaClass.simpleName}: ${t.message}", t)
            signer.cancel("RP enumerate failed: ${t.javaClass.simpleName}")
            return
        }
        // Match strategy: prefer credentialId match (ground truth) over
        // rpId/application string match. The DB row's `application` is
        // sometimes truncated to "ssh:" from a stale registerNewCredential
        // default — when that happens, the rpId comparison would fail
        // and the user would be told "Security key has no credentials
        // for ssh:" even though the credential is sitting right there on
        // the token under rpId="ssh:eight24" (or wherever). credId is
        // immutable on the token, so matching against the candidate
        // credIds set by the pool is robust against any application
        // string drift in the DB.
        //
        // We still keep the rpId-based match as a fallback for the
        // "discover credentials" flow where no pre-registered candidates
        // exist yet (the user just tapped a fresh token).
        val wantApp = holder.application
        val wantCredIds = holder.candidateCredIds
        android.util.Log.d(
            tag,
            "  enumerateRps ok (${rps.size} rps; want app=$wantApp, ${wantCredIds.size} pre-registered credId(s))"
        )
        for (rpData in rps) {
            val rpId = rpData.rp["id"]?.toString() ?: continue
            val rpIdMatches = rpId == wantApp
            // Cheap-first filter: if neither the app matches nor we
            // have any credIds to look for, skip this RP's enumerate
            // round-trip entirely.
            if (!rpIdMatches && wantCredIds.isEmpty()) {
                android.util.Log.d(tag, "    skip rp=$rpId (not our app, no credIds to match)")
                continue
            }
            val creds = try { mgmt.enumerateCredentials(rpData.rpIdHash) } catch (t: Throwable) {
                android.util.Log.w(tag, "    enumerateCredentials threw for $rpId: ${t.javaClass.simpleName}: ${t.message}")
                continue
            }
            android.util.Log.d(tag, "    rp=$rpId: ${creds.size} cred(s)")
            for (cred in creds) {
                val credId = cred.credentialId["id"] as? ByteArray ?: continue
                val matchesByCredId = wantCredIds.any { it.contentEquals(credId) }
                if (!rpIdMatches && !matchesByCredId) {
                    // Different rpId AND we don't recognise this credId — not ours.
                    continue
                }
                val coseKey = cred.publicKey.mapKeys { (it.key as? Number)?.toInt() ?: -999 }
                tokenCreds.add(EnumeratedCred(credId, coseKey, rpId, cred))
                val credIdHead = credId.take(4).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                val reason = when {
                    matchesByCredId && rpIdMatches -> "credId+app"
                    matchesByCredId -> "credId (app drift: db='$wantApp' vs key='$rpId')"
                    else -> "app=$wantApp"
                }
                android.util.Log.d(tag, "      keep credId=$credIdHead… via $reason")
            }
        }
        if (tokenCreds.isEmpty()) {
            android.util.Log.w(
                tag,
                "  no matching credentials on this token for app=${holder.application} or any of ${holder.candidateCredIds.size} candidate credId(s)"
            )
            signer.cancel("Security key has no credentials for ${holder.application}")
            return
        }
        // Persist + attach (dedup is per-fingerprint inside SshKeyRepository).
        val keyRepo = ServiceLocator.sshKeyRepository
        val srvRepo = ServiceLocator.serverRepository
        runBlocking {
            // Heal any DB rows whose stored `application` drifted from the
            // real rpId on the token. Has to happen BEFORE addSecurityKey
            // below — otherwise the per-(fingerprint+credId) dedup would
            // miss the heal target (fingerprint differs once we rebuild
            // with the correct rpId) and we'd end up with TWO rows for
            // the same physical credential.
            for (tc in tokenCreds) {
                keyRepo.healSecurityKeyApplication(
                    credentialIdBase64 = java.util.Base64.getEncoder().encodeToString(tc.credId),
                    realApplication = tc.rpId,
                )
            }
            for (tc in tokenCreds) {
                val (algo, blob) = ai.eight24family.conch.ssh.securitykey.SecurityKeyRegistrar.Algorithm.entries
                    .firstOrNull { it.coseAlg == (tc.coseKey[3] as? Number)?.toInt() }
                    ?.let { algo ->
                        val pubBlob = buildPublicKeyBlob(algo, tc.coseKey, tc.rpId) ?: return@let null
                        algo to pubBlob
                    } ?: continue
                val openSshLine = "${algo.sshKeyType} ${java.util.Base64.getEncoder().encodeToString(blob)}"
                val fp = "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(
                    java.security.MessageDigest.getInstance("SHA-256").digest(blob)
                )
                val displayName = (tc.cred.user["displayName"] ?: tc.cred.user["name"])?.toString()
                val saved = keyRepo.addSecurityKey(
                    nameHint = displayName ?: "security key",
                    cred = ai.eight24family.conch.ssh.securitykey.SecurityKeyRegistrar.ImportedCredential(
                        openSshLine = openSshLine,
                        fingerprint = fp,
                        credentialIdBase64 = java.util.Base64.getEncoder().encodeToString(tc.credId),
                        application = tc.rpId,
                        rpId = tc.rpId,
                        displayName = displayName,
                        algorithm = algo,
                    ),
                    transport = ai.eight24family.conch.domain.SecurityKeyTransport.EITHER,
                )
                holder.serverId?.let { sid -> srvRepo.attachKey(sid, saved.id) }
            }
        }
        // Pick matched credId. Prefer one already in candidates list (so
        // we don't switch primary on every connect); otherwise first.
        val matched = tokenCreds.firstOrNull { tc ->
            holder.candidateCredIds.any { it.contentEquals(tc.credId) }
        } ?: tokenCreds.first()
        holder.matchedCredId = matched.credId
        holder.matchedApplication = matched.rpId
        holder.cachedPin = pinChars!!.copyOf()
        holder.cachedPinProtocol = proto
        android.util.Log.d(tag, "  matched credId=${matched.credId.take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }}… rpId=${matched.rpId}")
        // Signal pool — it'll build the auth method and call client.auth.
        holder.tokenCredsReady.complete(Unit)
        // Provide session for signer.sign() which the pool's auth will trigger.
        signer.provideSession(session)
        // Wait for auth to complete (signer signals signDone after getAssertion).
        runBlocking { signer.signDone.await() }
        android.util.Log.d(tag, "  signDone — exiting withNfc{}, releasing session")
    } finally {
        pinChars?.let { for (i in it.indices) it[i] = ' ' }
    }
}

private data class EnumeratedCred(
    val credId: ByteArray,
    val coseKey: Map<Int, *>,
    val rpId: String,
    val cred: com.yubico.yubikit.fido.ctap.CredentialManagement.CredentialData,
)

private fun buildPublicKeyBlob(
    algo: ai.eight24family.conch.ssh.securitykey.SecurityKeyRegistrar.Algorithm,
    coseKey: Map<Int, *>,
    rpId: String,
): ByteArray? {
    return when (algo) {
        ai.eight24family.conch.ssh.securitykey.SecurityKeyRegistrar.Algorithm.ED25519 -> {
            val x = coseKey[-2] as? ByteArray ?: return null
            if (x.size != 32) return null
            val buf = net.schmizz.sshj.common.Buffer.PlainBuffer()
            buf.putString(algo.sshKeyType); buf.putString(x); buf.putString(rpId)
            buf.compactData
        }
        ai.eight24family.conch.ssh.securitykey.SecurityKeyRegistrar.Algorithm.ECDSA_NISTP256 -> {
            val x = coseKey[-2] as? ByteArray ?: return null
            val y = coseKey[-3] as? ByteArray ?: return null
            if (x.size != 32 || y.size != 32) return null
            val pt = ByteArray(1 + 64); pt[0] = 0x04
            System.arraycopy(x, 0, pt, 1, 32); System.arraycopy(y, 0, pt, 33, 32)
            val buf = net.schmizz.sshj.common.Buffer.PlainBuffer()
            buf.putString(algo.sshKeyType); buf.putString("nistp256"); buf.putString(pt); buf.putString(rpId)
            buf.compactData
        }
    }
}

private sealed interface TouchStatus {
    data object Idle : TouchStatus
    data class Waiting(val transport: SecurityKeyTransport) : TouchStatus
    /** [kind] is null when the failure came from a non-classified path
     *  (e.g. USB awaitUsb timeout). When non-null the dialog uses it to
     *  pick a friendly title and an action button. */
    data class Failed(val reason: String, val kind: SkFailureKind? = null) : TouchStatus
    data object Done : TouchStatus
}

// ── Apple-Pay style key-icon hero ──────────────────────────────
// A 96-dp circular hero element rendered above the dialog body.
// Three states: Idle (just the key icon), Captured (animated
// outward ripple rings + ramp-up vibration → user knows we're
// reading the tag, keeps holding), Done (pulse out + ✓ animates
// onto the icon, vibration stops, success chime plays).

private enum class KeyHeroState {
    /** Plain key icon, no animation. Reserved for failure / disabled states. */
    Idle,
    /** Slow, soft pulse — invitation to attach a key. No vibration. */
    Waiting,
    /** Tag is in the field — fast ripple + ramp vibration ("we're working"). */
    Captured,
    /** Sign() returned — checkmark + final pulse. */
    Done,
}

@Composable
private fun SkKeyHero(state: KeyHeroState) {
    val ctx = LocalContext.current
    val primary = MaterialTheme.colorScheme.primary
    val onSurfaceDim = MaterialTheme.colorScheme.outline

    // WCAG 2.3.3 — respect the OS "remove animations" / "reduce motion"
    // setting. Compose's LocalAccessibilityManager exposes this; if for
    // some reason it isn't provided in the current tree, fall back to
    // probing Settings.Global.ANIMATOR_DURATION_SCALE == 0 which is
    // what TalkBack / accessibility shortcuts toggle. Haptics are NOT
    // motion — vibration stays on regardless.
    val reduceMotion = run {
        val a11y = androidx.compose.ui.platform.LocalAccessibilityManager.current
        val composeFlag = SilentlyTry.logged("Conch-SkDialog", "read reduceMotion flag") {
            a11y?.javaClass?.getMethod("isReduceMotionEnabled")?.invoke(a11y) as? Boolean
        }
        composeFlag ?: SilentlyTry.loggedOrElse("Conch-SkDialog", "read animator duration scale", false) {
            android.provider.Settings.Global.getFloat(
                ctx.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }
    }

    // ── Ramping vibration during Captured ──
    DisposableEffect(state) {
        val vibrator = vibrator(ctx)
        if (state == KeyHeroState.Captured && vibrator?.hasVibrator() == true) {
            // Smooth ramp from gentle to firm over ~1.6 s. Telegraphs
            // "we're working — keep holding" without being startling.
            // VibrationEffect.createWaveform with rising amplitude.
            SilentlyTry.fired("Conch-SkDialog", "play ramp waveform") {
                val timings = longArrayOf(0, 80, 80, 80, 80, 120, 80, 160, 80, 220, 80, 300)
                val amplitudes = intArrayOf(0, 60, 0, 90, 0, 120, 0, 160, 0, 200, 0, 240)
                val effect = VibrationEffect.createWaveform(timings, amplitudes, /* repeat */ 0)
                vibrator.vibrate(effect)
            }
        }
        onDispose {
            // Stop any in-flight pattern when leaving Captured (Done /
            // Idle / dialog dismissal). Idempotent.
            SilentlyTry.fired("Conch-SkDialog", "cancel ramp vibration") { vibrator?.cancel() }
        }
    }

    // ── Final vibration hit + chime when we hit Done, synced
    //    with the checkmark scale-in animation. ──
    //
    // Orchestration:
    //   - DisposableEffect's onDispose has just cancelled the ramp
    //     above.
    //   - We immediately fire ONE firm pulse (150 ms) — the user
    //     feels a satisfying "thunk" exactly as the ✓ appears.
    //   - Then play the chime, slightly delayed so the haptic and
    //     audio don't pile on top of each other.
    LaunchedEffect(state) {
        if (state == KeyHeroState.Done) {
            val v = vibrator(ctx)
            SilentlyTry.fired("Conch-SkDialog", "play final haptic pulse") {
                v?.cancel()
                if (v?.hasVibrator() == true) {
                    val effect = VibrationEffect.createOneShot(150, 255)
                    v.vibrate(effect)
                }
            }
            kotlinx.coroutines.delay(60)
            SilentlyTry.fired("Conch-SkDialog", "play completion chime") {
                val ringtone = RingtoneManager.getRingtone(
                    ctx,
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                )
                ringtone?.play()
            }
        }
    }

    // ── Animated ripple radius (only in Captured) ──
    val infinite = rememberInfiniteTransition(label = "key-ripple")
    val rippleProgress by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ripple-progress",
    )
    // ── Soft "breathing" pulse (only in Waiting) — slower than Captured,
    //     no haptics, just a single ring softly fading in and out so the
    //     user knows the screen is alive and listening. ──
    val waitPulse by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wait-pulse",
    )

    // ── Done-state pulse + checkmark fade-in ──
    val checkScale by animateFloatAsState(
        targetValue = if (state == KeyHeroState.Done) 1f else 0f,
        animationSpec = tween(durationMillis = 280),
        label = "check-scale",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        contentAlignment = Alignment.Center,
    ) {
        // The hero icon is 80 dp wide (centered in the 120-dp Box) and the
        // touch sensor inside it sits at bodyW × 0.55 = 0.68 × 80 × 0.55 ≈
        // 30 dp from the icon's left edge — i.e. 50 dp from the Box's left
        // edge. The Box's geometric centre is 60 dp; the touch dot is 10 dp
        // to the left of that. We anchor every concentric ripple / glow on
        // the dot so they radiate FROM THE KEY, not from empty space next
        // to the USB connector.
        val touchAnchorXOffsetDp = -10.dp

        // Soft single-ring pulse — only in Waiting. Single expanding
        // ring fades from 25% alpha … 0 over the cycle. Inviting, not
        // urgent. With reduce-motion on, render a STATIC ring at mid-
        // alpha so the visual hierarchy still reads "we're listening"
        // without any animation.
        if (state == KeyHeroState.Waiting) {
            Canvas(modifier = Modifier.size(140.dp)) {
                val center = Offset(size.width / 2 + touchAnchorXOffsetDp.toPx(), size.height / 2)
                val maxRadius = size.minDimension / 2f
                if (reduceMotion) {
                    drawCircle(
                        color = primary.copy(alpha = 0.18f),
                        radius = maxRadius * 0.7f,
                        center = center,
                        style = Stroke(width = 2.5f),
                    )
                } else {
                    val radius = waitPulse * maxRadius
                    val alpha = (1f - waitPulse).coerceIn(0f, 1f) * 0.35f
                    drawCircle(
                        color = primary.copy(alpha = alpha),
                        radius = radius,
                        center = center,
                        style = Stroke(width = 2.5f),
                    )
                }
            }
        }

        // Concentric ripple rings — only visible in Captured state. Skip
        // entirely under reduce-motion: the haptic ramp already telegraphs
        // "we're working", so we don't need a second motion channel.
        if (!reduceMotion && state == KeyHeroState.Captured) {
            Canvas(modifier = Modifier.size(120.dp)) {
                val center = Offset(size.width / 2 + touchAnchorXOffsetDp.toPx(), size.height / 2)
                val maxRadius = size.minDimension / 2f
                // Three offset rings so the user sees a continuous
                // radiating pattern rather than a single pulse.
                for (i in 0..2) {
                    val phase = (rippleProgress + i / 3f) % 1f
                    val radius = phase * maxRadius
                    val alpha = (1f - phase).coerceIn(0f, 1f) * 0.7f
                    drawCircle(
                        color = primary.copy(alpha = alpha),
                        radius = radius,
                        center = center,
                        style = Stroke(width = 3f),
                    )
                }
            }
        }

        // Static glow circle behind the icon in Done state — gives the
        // checkmark something to land on.
        if (state == KeyHeroState.Done) {
            Canvas(modifier = Modifier.size(80.dp)) {
                drawCircle(
                    color = primary.copy(alpha = 0.18f),
                    radius = size.minDimension / 2f,
                    center = Offset(size.width / 2 + touchAnchorXOffsetDp.toPx(), size.height / 2),
                )
            }
        }

        // Generic USB security-key icon — see SecurityKeyIcon doc for the
        // rationale (skeuomorphic VpnKey reads as "password vault").
        ai.eight24family.conch.ui.components.SecurityKeyIcon(
            modifier = Modifier.size(width = 80.dp, height = 40.dp),
            tint = when (state) {
                KeyHeroState.Idle -> onSurfaceDim
                KeyHeroState.Waiting -> primary.copy(alpha = 0.85f)
                KeyHeroState.Captured -> primary
                KeyHeroState.Done -> primary
            },
            showTouchDot = true,
        )

        // Animated checkmark badge — overlaps the lower-right quadrant
        // of the key icon. Scales from 0 → 1 when entering Done.
        if (checkScale > 0f) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .padding(start = 36.dp, top = 36.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = primary,
                    modifier = Modifier
                        .scale(checkScale)
                        .size(28.dp),
                )
            }
        }
    }
}

private fun vibrator(ctx: android.content.Context): Vibrator? {
    return SilentlyTry.logged("Conch-SkDialog", "resolve vibrator service") {
        if (Build.VERSION.SDK_INT >= 31) {
            val mgr = ctx.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            mgr?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
