package ai.eight24family.conch.ssh.securitykey

import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Message
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.method.AbstractAuthMethod
import ai.eight24family.conch.util.SilentlyTry
import java.security.MessageDigest

/**
 * Custom SSH `publickey` auth method for FIDO security keys.
 *
 * sshj 0.39 / 0.40 don't know the SK key types
 * (`sk-ssh-ed25519@openssh.com`, `sk-ecdsa-sha2-nistp256@openssh.com`)
 * — its `KeyType` enum doesn't list them, so the stock
 * `AuthPublickey` path fails to even build the userauth REQUEST. This
 * class bypasses that path entirely: we hand-build the wire-format
 * packets per RFC 4252 §7 ("Public Key Authentication Method") and
 * OpenSSH's PROTOCOL.u2f extension, asking a [SkSigner] (the token)
 * to produce the inner signature when the server tells us to sign.
 *
 * Flow:
 *   1. `request()` (sshj called once at start of method)  →
 *      send a `publickey` USERAUTH_REQUEST with `has_signature=false`
 *      and the registered SK pubkey blob. RFC 4252 calls this the
 *      "test-only" form: server replies with PK_OK if it's willing to
 *      accept that key, or USERAUTH_FAILURE if it isn't.
 *   2. `handle(USERAUTH_60 = PK_OK)`  →
 *      build the signing payload (session_id + the same userauth
 *      fields, with `has_signature=true` this time), hash it
 *      SHA-256, ask the token to sign that hash via [SkSigner],
 *      pack the inner signature blob and send the SIGNED form
 *      of the userauth request.
 *   3. `handle(USERAUTH_SUCCESS)`  →  sshj's transport sees this
 *      after we send the signed request and finishes auth.
 *
 * The blocking call into the token happens on sshj's reader thread.
 * That's acceptable: while we wait for the user to touch the key
 * nothing else productive can happen on this transport. Caller is
 * expected to surface a "Touch your key" UI before initiating connect.
 */
class SkAuthPublickey(
    /**
     * SSH wire-format public-key blob for this credential — the same
     * bytes whose Base64 encoding sits on the server's `authorized_keys`
     * line. We send it on the wire verbatim.
     */
    private val publicKeyBlob: ByteArray,
    /** Algorithm name as used in the userauth packet, e.g.
     *  `sk-ssh-ed25519@openssh.com`. */
    private val algorithmName: String,
    private val signer: SkSigner,
) : AbstractAuthMethod("publickey") {

    /**
     * Set after we've received PK_OK from the server (so a subsequent
     * spurious USERAUTH_60 can't trick us into double-signing).
     */
    private var awaitingPkOk = false

    @Throws(UserAuthException::class, TransportException::class)
    override fun request() {
        // One-shot dump of the EXACT authorized_keys line we expect
        // the server to have. When auth fails with "key not in
        // authorized_keys" the user can grep `SshAi-SK-AuthorizedKey`
        // in logcat to see what to paste server-side. Format mirrors
        // OpenSSH's `ssh-keygen -y` output:
        //   <algorithm-name> <base64-blob>
        val b64 = android.util.Base64.encodeToString(
            publicKeyBlob,
            android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        // Re-add padding (Base64.NO_PADDING strips it but openssh wants it).
        val padded = run {
            val rem = b64.length % 4
            if (rem == 0) b64 else b64 + "=".repeat(4 - rem)
        }
        android.util.Log.i("SshAi-SK-AuthorizedKey", "$algorithmName $padded")
        // Log the credential ID so we can cross-reference with what's on the token
        // (e.g. via ykman fido credentials list). Useful when CTAP 0x2e (ERR_NO_CREDENTIALS)
        // fires — the credId in the app may not match what's physically on the token.
        val credIdHex = SilentlyTry.loggedOrElse("SshAi-SK-Auth", "render credId hex", "?") {
            val credId = java.util.Base64.getDecoder().decode(signer.credentialIdBase64)
            credId.take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) } + "… (${credId.size}B)"
        }
        android.util.Log.i("SshAi-SK-AuthorizedKey", "  credentialId (first 8B): $credIdHex  application: ${signer.application}")
        android.util.Log.d("SshAi-SK-Auth", "request: sending test (has_signature=false) for $algorithmName")
        awaitingPkOk = true
        params.transport.write(buildTestRequest())
    }

    @Throws(UserAuthException::class, TransportException::class)
    override fun handle(cmd: Message, buf: SSHPacket) {
        android.util.Log.d("SshAi-SK-Auth", "handle: cmd=$cmd")
        when (cmd) {
            Message.USERAUTH_60 -> {
                if (!awaitingPkOk) {
                    throw UserAuthException("unexpected USERAUTH_60 — server sent PK_OK twice?")
                }
                awaitingPkOk = false
                sendSignedRequest()
            }
            Message.USERAUTH_FAILURE -> {
                // Decode and log the failure detail so we can see why
                // the server rejected the key — most informative log
                // when the key looks valid client-side but server's
                // authorized_keys / sshd_config is the real culprit.
                val authMethods = SilentlyTry.loggedOrElse("SshAi-SK-Auth", "read remaining auth methods", "?") { buf.readString() }
                val partial = SilentlyTry.loggedOrElse("SshAi-SK-Auth", "read partial_success flag", false) { buf.readBoolean() }
                android.util.Log.w(
                    "SshAi-SK-Auth",
                    "USERAUTH_FAILURE: server still accepts methods=[$authMethods] partial_success=$partial"
                )
                // Wrap with informative message; sshj's default would
                // otherwise log a generic "auth failed".
                throw UserAuthException(
                    "server rejected sk-ssh-ed25519 key — accepts methods: $authMethods"
                )
            }
            else -> super.handle(cmd, buf)
        }
    }

    override fun shouldRetry(): Boolean = false

    private fun buildTestRequest(): SSHPacket {
        val pkt = SSHPacket(Message.USERAUTH_REQUEST)
        // The five framing fields are common to every userauth method.
        pkt.putString(params.username)
        pkt.putString(params.nextServiceName)
        pkt.putString("publickey")
        pkt.putBoolean(false) // has_signature
        pkt.putString(algorithmName)
        pkt.putString(publicKeyBlob)
        return pkt
    }

    private fun sendSignedRequest() {
        // Build the EXACT bytes the server will hash and verify against:
        //   string  session_id
        //   byte    SSH_MSG_USERAUTH_REQUEST (= 50)
        //   string  user
        //   string  service
        //   string  "publickey"
        //   boolean TRUE
        //   string  algorithm name
        //   string  public-key blob
        // For SK keys the server then computes SHA-256(this) and treats
        // the result as the clientDataHash that the token must have
        // signed (along with the token-side authData prefix).
        val payload = Buffer.PlainBuffer().apply {
            putString(params.transport.sessionID)
            putByte(Message.USERAUTH_REQUEST.toByte())
            putString(params.username)
            putString(params.nextServiceName)
            putString("publickey")
            putBoolean(true)
            putString(algorithmName)
            putString(publicKeyBlob)
        }.compactData

        val clientDataHash = MessageDigest.getInstance("SHA-256").digest(payload)
        val signResult = try {
            signer.sign(clientDataHash)
        } catch (e: SkAuthException) {
            // Surface the user-friendly reason as a UserAuthException
            // so the connect dialog can render it instead of dumping
            // a generic "auth failed".
            throw UserAuthException(e.message ?: "security-key signing failed", e)
        }

        // Inner signature blob (per OpenSSH PROTOCOL.u2f). For
        // ed25519: `string raw_sig || byte flags || uint32 counter`.
        // For ecdsa: `mpint r || mpint s || byte flags || uint32 counter`
        // — we DER-decode the ASN.1 SEQUENCE the token produces and
        // re-encode r/s as SSH mpints.
        // Wire format per OpenSSH ssh-ed25519-sk.c (verified against 9.2 source):
        //   string  algo_name           e.g. "sk-ssh-ed25519@openssh.com"
        //   string  raw_signature       64 bytes for Ed25519 / DER-encoded ECDSA sig
        //   byte    flags                FIDO2 authData flags
        //   uint32  counter              FIDO2 authData counter
        //
        // CRITICAL: flags + counter are at the OUTER level, NOT nested
        // inside another `string` blob. The earlier "innerSig wrapped in
        // an extra string" form looked plausible from PROTOCOL.u2f docs
        // but produced "unverified: invalid format" from sshd 9.2 — the
        // server's parse path (ssh_ed25519_sk_verify) reads cstring +
        // string + u8 + u32 sequentially with no inner-blob unwrap.
        val outerSig = Buffer.PlainBuffer().apply {
            putString(algorithmName)
            when (algorithmName) {
                "sk-ssh-ed25519@openssh.com" -> {
                    putString(signResult.rawSignature) // string raw_64
                }
                "sk-ecdsa-sha2-nistp256@openssh.com" -> {
                    // Fix: OpenSSH PROTOCOL.u2f / ssh-ecdsa-sk.c expects the inner
                    // signature blob as `mpint r || mpint s`, NOT a raw DER blob.
                    // Decode the token's ASN.1 SEQUENCE { INTEGER r, INTEGER s }
                    // and re-encode r/s as SSH mpints inside a `string`.
                    val (r, s) = decodeEcdsaDerSignature(signResult.rawSignature)
                    val innerBuf = Buffer.PlainBuffer().putMPInt(r).putMPInt(s).compactData
                    putString(innerBuf)
                }
                else -> throw UserAuthException("unsupported SK algorithm: $algorithmName")
            }
            putByte(signResult.flags)
            putUInt32(signResult.counter.toLong() and 0xFFFFFFFFL)
        }.compactData

        android.util.Log.d(
            "SshAi-SK-Auth",
            "outerSig[${outerSig.size}B]=${outerSig.joinToString("") { "%02x".format(it.toInt() and 0xFF) }}"
        )
        android.util.Log.d(
            "SshAi-SK-Auth",
            "rawSig[${signResult.rawSignature.size}B]=${signResult.rawSignature.joinToString("") { "%02x".format(it.toInt() and 0xFF) }} flags=0x${"%02x".format(signResult.flags.toInt() and 0xFF)} counter=${signResult.counter}"
        )

        // Now the actual signed USERAUTH_REQUEST.
        val pkt = SSHPacket(Message.USERAUTH_REQUEST)
        pkt.putString(params.username)
        pkt.putString(params.nextServiceName)
        pkt.putString("publickey")
        pkt.putBoolean(true)
        pkt.putString(algorithmName)
        pkt.putString(publicKeyBlob)
        pkt.putString(outerSig)

        params.transport.write(pkt)
    }
}

/**
 * Parse an ASN.1 DER-encoded ECDSA `SEQUENCE { INTEGER r, INTEGER s }`
 * and return r / s as `BigInteger`. Tokens hand back ECDSA sigs in this
 * exact format (CTAP2 spec §6.1.2 paragraph 4). We can't use a
 * cryptographic library to do this cleanly because we explicitly want
 * the raw mpint bytes, no padding/sign games.
 */
internal fun decodeEcdsaDerSignature(der: ByteArray): Pair<java.math.BigInteger, java.math.BigInteger> {
    var i = 0
    fun readLength(): Int {
        val first = der[i++].toInt() and 0xFF
        return if (first and 0x80 == 0) first else {
            val n = first and 0x7F
            require(n in 1..4) { "DER length too long: $n" }
            var len = 0
            repeat(n) { len = (len shl 8) or (der[i++].toInt() and 0xFF) }
            len
        }
    }
    require(der[i++].toInt() == 0x30) { "DER ECDSA sig must start with SEQUENCE" }
    readLength() // total length, ignored
    require(der[i++].toInt() == 0x02) { "expected INTEGER for r" }
    val rLen = readLength()
    val r = java.math.BigInteger(1, der.copyOfRange(i, i + rLen))
    i += rLen
    require(der[i++].toInt() == 0x02) { "expected INTEGER for s" }
    val sLen = readLength()
    val s = java.math.BigInteger(1, der.copyOfRange(i, i + sLen))
    return r to s
}
