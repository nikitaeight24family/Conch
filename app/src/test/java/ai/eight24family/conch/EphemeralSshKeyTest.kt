package ai.eight24family.conch

import ai.eight24family.conch.ssh.EphemeralSshKey
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Validates the seamless-reconnect device-key encoding END-TO-END on the JVM,
 * so a regression like the original "Exhausted available authentication
 * methods" (hand-rolled pubkey blob that didn't match what sshj presents) is
 * caught HERE, not by tapping a FIDO key on a phone.
 *
 * The on-device key lives in AndroidKeyStore (not available in a JVM unit
 * test), but the bug was never the signing — it was the AUTHORIZED_KEYS LINE
 * ENCODING ([EphemeralSshKey.authorizedKeyLineFor]), which is pure and works on
 * any [PublicKey]. So we drive it with a software EC P-256 key, which exercises
 * the identical sshj encode + sign + auth path.
 */
class EphemeralSshKeyTest {

    @Test
    fun `device-key authorized_keys line authenticates against a real sshd`() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()

        // Production encoder.
        val line = EphemeralSshKey.authorizedKeyLineFor(kp.public, "sshai-ephemeral-test")
        assertNotNull("encoder returned null", line)
        assertTrue("not an ecdsa-sha2-nistp256 line: $line", line!!.startsWith("ecdsa-sha2-nistp256 "))

        // A real SSH server (Apache MINA) parses our line the OpenSSH way; it
        // must resolve to exactly the key we generated. If the blob encoding
        // were off (the original bug), this comparison fails.
        val authorizedPub: PublicKey = AuthorizedKeyEntry.parseAuthorizedKeyEntry(line)
            .resolvePublicKey(null, PublicKeyEntryResolver.FAILING)
        assertTrue("MINA-parsed pubkey != generated key", KeyUtils.compareKeys(authorizedPub, kp.public))

        val sshd = SshServer.setUpDefaultServer()
        sshd.port = 0
        sshd.keyPairProvider = SimpleGeneratorHostKeyProvider()
        sshd.publickeyAuthenticator = PublickeyAuthenticator { _, key, _ -> KeyUtils.compareKeys(key, authorizedPub) }
        sshd.start()
        try {
            val client = SSHClient()
            client.addHostKeyVerifier(PromiscuousVerifier())
            client.connect("127.0.0.1", sshd.port)
            try {
                client.authPublickey("tester", object : KeyProvider {
                    override fun getPrivate(): PrivateKey = kp.private
                    override fun getPublic(): PublicKey = kp.public
                    override fun getType(): KeyType = KeyType.ECDSA256
                })
                assertTrue("sshj failed to authenticate with the encoded device key", client.isAuthenticated)
            } finally {
                client.disconnect()
            }
        } finally {
            sshd.stop(true)
        }
    }
}
