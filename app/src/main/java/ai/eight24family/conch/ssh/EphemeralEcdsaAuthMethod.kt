package ai.eight24family.conch.ssh

import ai.eight24family.conch.ssh.securitykey.decodeEcdsaDerSignature
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Message
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.method.AbstractAuthMethod
import java.security.PrivateKey
import java.security.PublicKey

/**
 * Custom SSH `publickey` auth for the hardware **AndroidKeyStore** ephemeral
 * device key (seamless reconnect).
 *
 * Why not sshj's stock `authPublickey`: sshj resolves the ecdsa-sha2-nistp256
 * KeyAlgorithm internally and signs via **BouncyCastle**, which cannot use a
 * non-extractable AndroidKeyStore private key (`getEncoded()==null` →
 * `InvalidKeyException: no encoding for EC private key`). sshj swallows that and
 * the whole method fails → "Exhausted available authentication methods" — proven
 * on-device. Swapping the KeyAlgorithm in the client config did NOT take effect
 * (KeyedAuthMethod resolves the algorithm from its own negotiated queue), so we
 * bypass the stock path entirely — exactly like [SkAuthPublickey] does for FIDO.
 *
 * We hand-build the RFC 4252 §7 packets:
 *  1. [request] — send the test form (has_signature=false) with the pubkey blob
 *     encoded by sshj's own `Buffer.putPublicKey` (BYTE-IDENTICAL to the line in
 *     `authorized_keys`, so the server matches it and replies PK_OK).
 *  2. [sendSignedRequest] on PK_OK — sign `string(sessionId) || byte(50) ||
 *     <the request fields>` with `java.security.Signature("SHA256withECDSA")`
 *     WITHOUT forcing a provider, so the platform routes signing to the
 *     AndroidKeyStore provider (TEE). The DER signature is re-encoded as the SSH
 *     ECDSA blob `string(name) || string(mpint r || mpint s)`.
 */
class EphemeralEcdsaAuthMethod(
    pub: PublicKey,
    private val priv: PrivateKey,
) : AbstractAuthMethod("publickey") {

    private val algorithmName = "ecdsa-sha2-nistp256"
    private val publicKeyBlob: ByteArray = Buffer.PlainBuffer().putPublicKey(pub).compactData
    private var awaitingPkOk = false

    @Throws(UserAuthException::class, TransportException::class)
    override fun request() {
        awaitingPkOk = true
        params.transport.write(buildReq(false))
    }

    @Throws(UserAuthException::class, TransportException::class)
    override fun handle(cmd: Message, buf: SSHPacket) {
        when (cmd) {
            Message.USERAUTH_60 -> {
                if (!awaitingPkOk) throw UserAuthException("unexpected USERAUTH_60 (PK_OK twice?)")
                awaitingPkOk = false
                sendSignedRequest()
            }
            else -> super.handle(cmd, buf)
        }
    }

    override fun shouldRetry(): Boolean = false

    private fun buildReq(hasSignature: Boolean): SSHPacket {
        val pkt = SSHPacket(Message.USERAUTH_REQUEST)
        pkt.putString(params.username)
        pkt.putString(params.nextServiceName)
        pkt.putString("publickey")
        pkt.putBoolean(hasSignature)
        pkt.putString(algorithmName)
        pkt.putString(publicKeyBlob)
        return pkt
    }

    private fun sendSignedRequest() {
        // EXACT bytes the server verifies (RFC 4252 §7).
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

        val der = try {
            // No explicit provider → the AndroidKeyStore key routes to the
            // AndroidKeyStore provider (signs in the TEE). NOT BouncyCastle.
            val s = java.security.Signature.getInstance("SHA256withECDSA")
            s.initSign(priv)
            s.update(payload)
            s.sign()
        } catch (e: Exception) {
            throw UserAuthException("device-key signing failed: ${e.message}", e)
        }

        val (r, signComp) = decodeEcdsaDerSignature(der)
        val sigBlob = Buffer.PlainBuffer().putMPInt(r).putMPInt(signComp).compactData
        val outerSig = Buffer.PlainBuffer()
            .putString(algorithmName)
            .putString(sigBlob)
            .compactData

        val pkt = buildReq(true)
        pkt.putString(outerSig)
        params.transport.write(pkt)
    }
}
