package ai.eight24family.conch

import ai.eight24family.conch.ssh.EphemeralSshKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.eight24family.conch.ssh.KeystoreEcdsa256Signature
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.Signature as JcaSignature

/**
 * Runs ON-DEVICE (AndroidKeyStore is real hardware — not available in a JVM unit
 * test). Verifies the ONE thing the PC sim test couldn't cover: that the
 * hardware ephemeral device key can SIGN exactly the way sshj presents during
 * publickey auth, and the signature verifies.
 *
 * If this passes, the "Exhausted available authentication methods" reconnect
 * failures are NOT a signing problem → the enrolled authorized_keys line is
 * stale (old encoder) and just needs re-enrolling. If it FAILS, AndroidKeyStore
 * signing via sshj is the culprit and the failure tells us exactly how.
 */
@RunWith(AndroidJUnit4::class)
class EphemeralKeystoreSigningTest {

    private val sid = "ittest_signing"

    @Test
    fun keystoreKeySignsAndVerifies() {
        EphemeralSshKey.delete(sid)
        try {
            assertTrue("ensure() failed to mint the Keystore key", EphemeralSshKey.ensure(sid))
            val kp = EphemeralSshKey.keyProvider(sid)
            assertNotNull("keyProvider() returned null", kp)
            val pub = kp!!.public
            val priv = kp.private
            val data = "ssh-publickey-auth-challenge-sample-blob".toByteArray()

            // (1) JCA SHA256withECDSA — the exact algorithm sshj uses for
            //     ecdsa-sha2-nistp256. This is THE proof the hardware key can
            //     produce a verifiable ECDSA-SHA256 signature.
            val signer = JcaSignature.getInstance("SHA256withECDSA")
            signer.initSign(priv)
            signer.update(data)
            val der = signer.sign()
            assertTrue("empty signature from Keystore key", der.isNotEmpty())
            val verifier = JcaSignature.getInstance("SHA256withECDSA")
            verifier.initVerify(pub)
            verifier.update(data)
            assertTrue("JCA SHA256withECDSA verify FAILED for the Keystore key", verifier.verify(der))

            // (2) Our KeystoreEcdsa256Signature — the production path for the
            //     ephemeral reconnect. Stock sshj SignatureECDSA throws here
            //     ("no encoding for EC private key", BouncyCastle); this one signs
            //     via the AndroidKeyStore provider and must round-trip.
            val s = KeystoreEcdsa256Signature()
            s.initSign(priv)
            s.update(data)
            val sshSig = s.sign()
            assertTrue("empty signature", sshSig.isNotEmpty())
            val v = KeystoreEcdsa256Signature()
            v.initVerify(pub)
            v.update(data)
            assertTrue("KeystoreEcdsa256Signature verify FAILED for the Keystore key", v.verify(sshSig))
        } finally {
            EphemeralSshKey.delete(sid)
        }
    }
}
