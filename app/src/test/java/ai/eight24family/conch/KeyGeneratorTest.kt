package ai.eight24family.conch

import ai.eight24family.conch.ssh.SshKeyGenerator
import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyGeneratorTest {

    @Test
    fun `ed25519 keys round-trip via sshj`() {
        val key = SshKeyGenerator.generateEd25519("test@android")

        assertTrue(key.publicKeyOpenSsh.startsWith("ssh-ed25519 "))
        assertTrue(key.publicKeyOpenSsh.endsWith(" test@android"))
        assertTrue(key.privateKeyOpenSsh.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
        assertTrue(key.privateKeyOpenSsh.contains("-----END OPENSSH PRIVATE KEY-----"))
        assertTrue(key.fingerprintSha256.startsWith("SHA256:"))

        // sshj must be able to parse the private key
        val provider = OpenSSHKeyV1KeyFile()
        provider.init(key.privateKeyOpenSsh, null)
        val parsedPriv = provider.private
        val parsedPub = provider.public
        assertNotNull(parsedPriv)
        assertNotNull(parsedPub)
        assertEquals("EdDSA", parsedPub.algorithm)
    }

    @Test
    fun `consecutive generations differ`() {
        val a = SshKeyGenerator.generateEd25519()
        val b = SshKeyGenerator.generateEd25519()
        assertTrue(a.publicKeyOpenSsh != b.publicKeyOpenSsh)
        assertTrue(a.privateKeyOpenSsh != b.privateKeyOpenSsh)
        assertTrue(a.fingerprintSha256 != b.fingerprintSha256)
    }
}
