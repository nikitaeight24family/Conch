package ai.eight24family.conch.ssh.securitykey

import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.fido.CtapException
import com.yubico.yubikit.core.fido.FidoConnection
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.fido.ctap.ClientPin
import com.yubico.yubikit.fido.ctap.CredentialManagement
import com.yubico.yubikit.fido.ctap.Ctap2Session
import com.yubico.yubikit.fido.ctap.PinUvAuthProtocol
import com.yubico.yubikit.fido.ctap.PinUvAuthProtocolV1
import com.yubico.yubikit.fido.ctap.PinUvAuthProtocolV2
import com.yubico.yubikit.fido.webauthn.AuthenticatorData
import net.schmizz.sshj.common.Buffer
import ai.eight24family.conch.util.SilentlyTry
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Read existing **resident** SK credentials off a FIDO authenticator
 * and import them as `SshKey` rows.
 *
 * The previous shape of this class made a brand-new credential via
 * `authenticatorMakeCredential`, which was wrong for users who had
 * already provisioned a YubiKey via `ssh-keygen -t ed25519-sk -O resident`
 * on a desktop — it produced a SECOND credential on the same key,
 * forcing two lines in `~/.ssh/authorized_keys`. This class instead
 * uses CTAP2 `credentialManagement.enumerateCredentials` to LIST what
 * the user already has and turn each into an SK key row, sharing the
 * exact same credentialId / pubkey as their desktop tools see.
 *
 * Requires a PIN on the FIDO2 application — that's a CTAP-spec
 * requirement, not our choice. Authenticators without a PIN won't
 * expose `credentialManagement` at all (we surface a clear error).
 */
class SecurityKeyRegistrar {

    sealed interface Outcome {
        /** One or more resident credentials found and imported. */
        data class Ok(val imported: List<ImportedCredential>) : Outcome
        data object PinNotSupported : Outcome
        data object PinNotSet : Outcome
        data class WrongPin(val attemptsLeft: Int?) : Outcome
        data object PinBlocked : Outcome
        data object NoResidentCredentials : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /** A credential we successfully read off the token. */
    data class ImportedCredential(
        val openSshLine: String,
        val fingerprint: String,
        val credentialIdBase64: String,
        val application: String,
        val rpId: String,
        val displayName: String?,
        val algorithm: Algorithm,
    )

    enum class Algorithm(val coseAlg: Int, val sshKeyType: String) {
        ED25519(-8, "sk-ssh-ed25519@openssh.com"),
        ECDSA_NISTP256(-7, "sk-ecdsa-sha2-nistp256@openssh.com"),
    }

    /**
     * @param pin The user's FIDO2 PIN. Required.
     * @param onlyRpIdPrefix If non-null, filter to credentials whose
     *        `rpId` starts with this string. SSH SK keys use
     *        `application` as rpId (default `ssh:`), so passing
     *        `"ssh:"` keeps unrelated WebAuthn / passkey creds out of
     *        our keychain.
     */
    /**
     * Read the resident credentials off [device] and turn each one into an
     * [ImportedCredential]. PIN is requested LAZILY through [pinProvider]
     * — registrar opens the session, checks if the token has a PIN set,
     * and only then asks the UI for one. This way the user only sees the
     * PIN pad when their token actually needs it (`clientPin == true`),
     * and it appears AFTER tap so they can confirm "yes there's a key on
     * this side" before entering credentials.
     *
     * [pinProvider] returns null when the user cancels — registrar bails
     * with [Outcome.Failed] in that case.
     *
     * For NFC, this runs inside the `withNfc { device -> ... }` callback
     * so the smart-card connection stays open while [pinProvider] blocks
     * for the PIN. The user must keep the token against the phone during
     * PIN entry — communicated in the UI copy.
     */
    fun importResidentCredentials(
        device: YubiKeyDevice,
        pinProvider: () -> CharArray?,
        onlyRpIdPrefix: String? = "ssh:",
    ): Outcome {
        val tag = "Conch-SK-Import"
        android.util.Log.d(tag, "begin transport=${device.transport}")

        return runOnSession(device) { session ->
            val info = session.cachedInfo
            if (!CredentialManagement.isSupported(info)) {
                android.util.Log.w(tag, "credentialManagement not supported by token")
                return@runOnSession Outcome.Failed(
                    "this token doesn't support credential management — needs a CTAP 2.1 / FIDO2 authenticator"
                )
            }
            // Check whether the token actually has a PIN set. If not,
            // credentialManagement still requires one per CTAP spec; the
            // user has to call `ykman fido access change-pin` first.
            val clientPinSet = (info.options["clientPin"] as? Boolean) == true
            if (!clientPinSet) {
                android.util.Log.w(tag, "clientPin=false on token — credentialManagement needs a PIN; tell user to set one")
                return@runOnSession Outcome.PinNotSet
            }
            val protocol = pickPinProtocol(session) ?: run {
                android.util.Log.w(tag, "PIN not supported")
                return@runOnSession Outcome.PinNotSupported
            }
            val clientPin = ClientPin(session, protocol)
            // PIN is set on token — NOW prompt the UI for it. Connection
            // stays open across the suspend (NFC: tag still pressed against
            // the back of the phone, USB: cable still plugged).
            val pin = pinProvider() ?: run {
                android.util.Log.d(tag, "user cancelled PIN entry")
                return@runOnSession Outcome.Failed("cancelled")
            }
            // Belt-and-suspenders: re-read retries so wrong-PIN feedback can
            // include "X attempts left" without an extra round trip.
            val retries = try {
                clientPin.pinRetries.count
            } catch (e: CtapException) {
                if (e.ctapError == CtapException.ERR_PIN_NOT_SET) {
                    return@runOnSession Outcome.PinNotSet
                }
                throw e
            }
            android.util.Log.d(tag, "pin retries=$retries; requesting pin token")

            val pinToken = try {
                clientPin.getPinToken(
                    pin,
                    ClientPin.PIN_PERMISSION_CM,
                    /* permissionRpId */ null,
                )
            } catch (e: CtapException) {
                return@runOnSession when (e.ctapError) {
                    CtapException.ERR_PIN_INVALID,
                    CtapException.ERR_PIN_AUTH_INVALID -> Outcome.WrongPin(
                        attemptsLeft = SilentlyTry.logged("Conch-SK-Reg", "read pin retries (enroll)") { clientPin.pinRetries.count }
                    )
                    CtapException.ERR_PIN_BLOCKED,
                    CtapException.ERR_PIN_AUTH_BLOCKED -> Outcome.PinBlocked
                    else -> Outcome.Failed("PIN error 0x${"%02x".format(e.ctapError)}: ${e.message}")
                }
            }
            android.util.Log.d(tag, "pin token acquired (${pinToken.size}B)")

            val mgmt = CredentialManagement(session, protocol, pinToken)
            val rps = try {
                mgmt.enumerateRps()
            } catch (e: CtapException) {
                if (e.ctapError == CtapException.ERR_NO_CREDENTIALS) {
                    android.util.Log.d(tag, "no resident credentials at all")
                    return@runOnSession Outcome.NoResidentCredentials
                }
                android.util.Log.w(tag, "enumerateRps failed ctapErr=0x${"%02x".format(e.ctapError)}")
                return@runOnSession when (e.ctapError) {
                    CtapException.ERR_PIN_AUTH_INVALID,
                    CtapException.ERR_PIN_AUTH_BLOCKED ->
                        Outcome.Failed("Security key's PIN state is locked from a previous attempt. Lift the key off the phone (or unplug from USB), wait a couple of seconds, and try again.")
                    CtapException.ERR_OPERATION_DENIED ->
                        Outcome.Failed("Security key declined the request. Unplug/lift the key and tap it fresh, then try again.")
                    else ->
                        Outcome.Failed("Couldn't list credentials on this security key (CTAP 0x${"%02x".format(e.ctapError)}). Try removing the key and tapping it again.")
                }
            }
            android.util.Log.d(tag, "found ${rps.size} RP(s)")
            val imported = mutableListOf<ImportedCredential>()
            for (rpData in rps) {
                val rpId = rpData.rp["id"]?.toString() ?: continue
                if (onlyRpIdPrefix != null && !rpId.startsWith(onlyRpIdPrefix)) {
                    android.util.Log.d(tag, "  skip rp=$rpId (filter)")
                    continue
                }
                android.util.Log.d(tag, "  rp=$rpId")
                val creds = try {
                    mgmt.enumerateCredentials(rpData.rpIdHash)
                } catch (e: CtapException) {
                    android.util.Log.w(tag, "  enumerate failed for rp=$rpId: ${e.message}")
                    continue
                }
                for (cred in creds) {
                    val credId = cred.credentialId["id"] as? ByteArray ?: continue
                    val coseKey = cred.publicKey
                        .mapKeys { (it.key as? Number)?.toInt() ?: -999 }
                    val (algo, blob) = buildOpenSshBlob(coseKey, rpId) ?: continue
                    val b64 = Base64.getEncoder().encodeToString(blob)
                    val openSshLine = "${algo.sshKeyType} $b64"
                    val fp = "SHA256:" + Base64.getEncoder().withoutPadding()
                        .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
                    val display = (cred.user["displayName"] ?: cred.user["name"])?.toString()
                    imported += ImportedCredential(
                        openSshLine = openSshLine,
                        fingerprint = fp,
                        credentialIdBase64 = Base64.getEncoder().encodeToString(credId),
                        application = rpId,
                        rpId = rpId,
                        displayName = display,
                        algorithm = algo,
                    )
                    val credIdHexHead = credId.take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                    val pubXHead = (coseKey[-2] as? ByteArray)?.take(8)?.joinToString("") { "%02x".format(it.toInt() and 0xFF) } ?: "?"
                    android.util.Log.d(tag, "    imported credId=${credId.size}B head=$credIdHexHead… alg=${algo.sshKeyType}")
                    android.util.Log.d(tag, "      pubkey-X first 8B: $pubXHead… (compare with credId head — pubkey-derived IDs are normal)")
                    // Log UV-related fields of the credential, if present in
                    // the CTAP response. credProtect=3 or hmac-secret w/ UV=true
                    // would indicate the credential was created with
                    // -O verify-required and getAssertion will need pinUvAuthParam.
                    android.util.Log.d(tag, "      cred raw fields: ${cred::class.simpleName} keys=${SilentlyTry.loggedOrElse("Conch-SK-Reg", "render cred toString", "?") { cred.toString().take(200) }}")
                }
            }
            if (imported.isEmpty()) Outcome.NoResidentCredentials
            else Outcome.Ok(imported)
        }
    }

    /**
     * Mint a brand-new resident SSH credential on [device] via CTAP
     * `authenticatorMakeCredential`. Used by the Wrong-Key recovery
     * flow when the server doesn't recognise any of the keys we have
     * on file — the user goes to the Keychain "Register a new key"
     * detour, taps + enters PIN once, and a fresh `sk-ssh-ed25519`
     * (or `sk-ecdsa-sha2-nistp256` if the token doesn't support
     * Ed25519) credential lands in the keychain ready to be added
     * to the server's authorized_keys.
     *
     * The shape of the returned [Outcome.Ok.imported] is identical to
     * [importResidentCredentials] — same downstream
     * [ai.eight24family.conch.data.SshKeyRepository.addSecurityKey]
     * path adds the row.
     *
     * @param displayName Used for the credential's `user.displayName` /
     *        `user.name` fields (so a desktop `ykman fido credentials list`
     *        shows the same label) AND as the SshKey row's name. Falls
     *        back to "Conch key" when blank.
     */
    fun registerNewCredential(
        device: YubiKeyDevice,
        pinProvider: () -> CharArray?,
        displayName: String,
        rpId: String = "ssh:",
    ): Outcome {
        val tag = "Conch-SK-Register"
        android.util.Log.d(tag, "begin transport=${device.transport}")

        return runOnSession(device) { session ->
            val info = session.cachedInfo
            val clientPinSet = (info.options["clientPin"] as? Boolean) == true
            if (!clientPinSet) {
                android.util.Log.w(tag, "clientPin=false — token must have a PIN before makeCredential with rk=true")
                return@runOnSession Outcome.PinNotSet
            }
            val protocol = pickPinProtocol(session) ?: return@runOnSession Outcome.PinNotSupported
            val clientPin = ClientPin(session, protocol)
            val pin = pinProvider() ?: return@runOnSession Outcome.Failed("cancelled")

            val pinToken = try {
                clientPin.getPinToken(
                    pin,
                    ClientPin.PIN_PERMISSION_MC,
                    rpId,
                )
            } catch (e: CtapException) {
                return@runOnSession when (e.ctapError) {
                    CtapException.ERR_PIN_INVALID,
                    CtapException.ERR_PIN_AUTH_INVALID -> Outcome.WrongPin(
                        attemptsLeft = SilentlyTry.logged("Conch-SK-Reg", "read pin retries (sign)") { clientPin.pinRetries.count },
                    )
                    CtapException.ERR_PIN_BLOCKED,
                    CtapException.ERR_PIN_AUTH_BLOCKED -> Outcome.PinBlocked
                    else -> Outcome.Failed("PIN error 0x${"%02x".format(e.ctapError)}: ${e.message}")
                }
            }
            android.util.Log.d(tag, "pin token (MC) acquired (${pinToken.size}B)")

            // Synthetic clientDataHash. Not verified later — makeCredential
            // hashes its own attestation, and at SSH userauth time we don't
            // even forward this value. Just needs to be a 32-byte SHA-256
            // hash so the token doesn't reject the request shape.
            val clientDataHash = MessageDigest.getInstance("SHA-256")
                .digest("conch-register".toByteArray(Charsets.UTF_8))

            val rp = mapOf<String, Any>("id" to rpId, "name" to "SSH")
            val userId = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val userName = displayName.ifBlank { "Conch key" }
            val user = mapOf<String, Any>(
                "id" to userId,
                "name" to userName,
                "displayName" to userName,
            )
            // Try Ed25519 first (-8) → ES256 fallback (-7). Tokens iterate
            // pubKeyCredParams in order and pick the first they support.
            val pubKeyCredParams = listOf(
                mapOf<String, Any>("type" to "public-key", "alg" to -8),
                mapOf<String, Any>("type" to "public-key", "alg" to -7),
            )
            val options = mapOf<String, Any>("rk" to true)  // resident credential

            val pinUvAuthParam = protocol.authenticate(pinToken, clientDataHash)
            val credentialData = try {
                session.makeCredential(
                    clientDataHash,
                    rp,
                    user,
                    pubKeyCredParams,
                    /* excludeList */ emptyList(),
                    /* extensions */ null,
                    options,
                    pinUvAuthParam,
                    protocol.version,
                    /* enterpriseAttestation */ null,
                    /* state */ null,
                )
            } catch (e: CtapException) {
                android.util.Log.w(tag, "makeCredential failed ctapErr=0x${"%02x".format(e.ctapError)}")
                return@runOnSession when (e.ctapError) {
                    CtapException.ERR_USER_ACTION_TIMEOUT,
                    CtapException.ERR_KEEPALIVE_CANCEL ->
                        Outcome.Failed("Touch timed out — try again.")
                    CtapException.ERR_OPERATION_DENIED ->
                        Outcome.Failed("Security key declined the request.")
                    else -> Outcome.Failed("makeCredential 0x${"%02x".format(e.ctapError)}: ${e.message}")
                }
            }
            val authData = AuthenticatorData.parseFrom(ByteBuffer.wrap(credentialData.authenticatorData))
            val attested = authData.attestedCredentialData
                ?: return@runOnSession Outcome.Failed("security key returned no attested credential data")
            val credId = attested.credentialId
            val coseKey = attested.cosePublicKey.mapKeys { (it.key as? Number)?.toInt() ?: -999 }
            val (algo, blob) = buildOpenSshBlob(coseKey, rpId)
                ?: return@runOnSession Outcome.Failed("security key's response uses an unsupported key algorithm")
            val b64 = Base64.getEncoder().encodeToString(blob)
            val openSshLine = "${algo.sshKeyType} $b64"
            val fp = "SHA256:" + Base64.getEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
            android.util.Log.d(tag, "registered credId=${credId.size}B alg=${algo.sshKeyType}")
            Outcome.Ok(
                listOf(
                    ImportedCredential(
                        openSshLine = openSshLine,
                        fingerprint = fp,
                        credentialIdBase64 = Base64.getEncoder().encodeToString(credId),
                        application = rpId,
                        rpId = rpId,
                        displayName = userName,
                        algorithm = algo,
                    )
                )
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
            else -> error("device offers neither FIDO nor smart-card transport")
        }
    }

    private fun pickPinProtocol(session: Ctap2Session): PinUvAuthProtocol? {
        val supported = session.cachedInfo.pinUvAuthProtocols ?: return null
        return when {
            supported.contains(2) -> PinUvAuthProtocolV2()
            supported.contains(1) -> PinUvAuthProtocolV1()
            else -> null
        }
    }

    private fun buildOpenSshBlob(coseKey: Map<Int, *>, application: String): Pair<Algorithm, ByteArray>? {
        val alg = (coseKey[3] as? Number)?.toInt() ?: return null
        return when (alg) {
            Algorithm.ED25519.coseAlg -> {
                val xBytes = coseKey[-2] as? ByteArray ?: return null
                if (xBytes.size != 32) return null
                val buf = Buffer.PlainBuffer()
                buf.putString(Algorithm.ED25519.sshKeyType)
                buf.putString(xBytes)
                buf.putString(application)
                Algorithm.ED25519 to buf.compactData
            }
            Algorithm.ECDSA_NISTP256.coseAlg -> {
                val x = coseKey[-2] as? ByteArray ?: return null
                val y = coseKey[-3] as? ByteArray ?: return null
                if (x.size != 32 || y.size != 32) return null
                val ecPoint = ByteArray(1 + 32 + 32)
                ecPoint[0] = 0x04
                System.arraycopy(x, 0, ecPoint, 1, 32)
                System.arraycopy(y, 0, ecPoint, 33, 32)
                val buf = Buffer.PlainBuffer()
                buf.putString(Algorithm.ECDSA_NISTP256.sshKeyType)
                buf.putString("nistp256")
                buf.putString(ecPoint)
                buf.putString(application)
                Algorithm.ECDSA_NISTP256 to buf.compactData
            }
            else -> null
        }
    }
}
