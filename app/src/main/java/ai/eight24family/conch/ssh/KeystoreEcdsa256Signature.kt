package ai.eight24family.conch.ssh

import ai.eight24family.conch.ssh.securitykey.decodeEcdsaDerSignature
import com.hierynomus.sshj.key.KeyAlgorithm
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.signature.Signature
import net.schmizz.sshj.signature.SignatureECDSA
import java.security.PrivateKey
import java.security.PublicKey

/**
 * sshj `ecdsa-sha2-nistp256` signature that SIGNS via the default JCA provider
 * instead of BouncyCastle.
 *
 * Why this exists: sshj's stock [SignatureECDSA] obtains its `java.security.
 * Signature` from BouncyCastle. BouncyCastle's ECDSA `engineInitSign` calls
 * `ECUtil.generatePrivateKeyParameter`, which needs the private key's encoded
 * bytes. A hardware-backed **AndroidKeyStore** EC key is non-extractable —
 * `getEncoded()` returns null — so BC throws `InvalidKeyException: no encoding
 * for EC private key` and publickey auth fails with "Exhausted available
 * authentication methods". Proven by `EphemeralKeystoreSigningTest`: a software
 * key (which HAS an encoding) works; the Keystore key does not. This is exactly
 * why seamless reconnect worked in the PC sim but never on the phone.
 *
 * Getting the JCA `Signature` WITHOUT forcing a provider lets the platform route
 * the AndroidKeyStore private key to the AndroidKeyStore provider, which signs
 * inside the TEE (the key never leaves hardware). VERIFY and the SSH wire
 * encoding (DER → `mpint r || mpint s`) are delegated to sshj's own
 * [SignatureECDSA] so the bytes are byte-identical to stock sshj output.
 */
internal class KeystoreEcdsa256Signature : Signature {
    private val jcaSign: java.security.Signature = java.security.Signature.getInstance("SHA256withECDSA")
    private val sshDelegate: Signature = SignatureECDSA.Factory256().create()
    private var signing = false

    override fun initSign(prv: PrivateKey) {
        jcaSign.initSign(prv)
        signing = true
    }

    override fun initVerify(pub: PublicKey) {
        sshDelegate.initVerify(pub)
        signing = false
    }

    override fun update(H: ByteArray) {
        if (signing) jcaSign.update(H) else sshDelegate.update(H)
    }

    override fun update(H: ByteArray, off: Int, len: Int) {
        if (signing) jcaSign.update(H, off, len) else sshDelegate.update(H, off, len)
    }

    /** JCA produces a DER-encoded ECDSA signature. The SSH wire form for
     *  ecdsa-sha2-nistp256 is `string(name) string(mpint r || mpint s)` — build
     *  that explicitly (sshj's encode() yields only the inner blob, and verify()
     *  expects the named outer form). Mirrors SkAuthPublickey's ECDSA path. */
    override fun sign(): ByteArray {
        val (r, s) = decodeEcdsaDerSignature(jcaSign.sign())
        val inner = Buffer.PlainBuffer().putMPInt(r).putMPInt(s).compactData
        return Buffer.PlainBuffer()
            .putString("ecdsa-sha2-nistp256")
            .putString(inner)
            .compactData
    }

    override fun verify(sig: ByteArray): Boolean = sshDelegate.verify(sig)

    override fun encode(signature: ByteArray): ByteArray = sshDelegate.encode(signature)

    override fun getSignatureName(): String = "ecdsa-sha2-nistp256"
}

/**
 * Drop-in replacement for sshj's stock `ecdsa-sha2-nistp256` [KeyAlgorithm] that
 * only swaps [newSignature] for [KeystoreEcdsa256Signature] (AndroidKeyStore-
 * compatible). Everything else — pubkey buffer read/write, key format — delegates
 * to the wrapped original so the wire behaviour is unchanged.
 */
internal class KeystoreEcdsaKeyAlgorithm(
    private val base: KeyAlgorithm,
) : KeyAlgorithm by base {
    override fun newSignature(): Signature = KeystoreEcdsa256Signature()

    /** Named factory that wraps the stock ecdsa-sha2-nistp256 factory; install it
     *  into a client's `config.keyAlgorithms` in place of the original. */
    class Factory(
        private val base: net.schmizz.sshj.common.Factory.Named<KeyAlgorithm>,
    ) : net.schmizz.sshj.common.Factory.Named<KeyAlgorithm> {
        override fun create(): KeyAlgorithm = KeystoreEcdsaKeyAlgorithm(base.create())
        override fun getName(): String = base.getName()
    }
}
