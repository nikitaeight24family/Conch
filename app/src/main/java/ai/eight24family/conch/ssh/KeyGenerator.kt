package ai.eight24family.conch.ssh

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64

data class GeneratedSshKey(
    val publicKeyOpenSsh: String,
    val privateKeyOpenSsh: String,
    val fingerprintSha256: String
)

object SshKeyGenerator {

    fun generateEd25519(comment: String = "sshai@android"): GeneratedSshKey {
        val random = SecureRandom()
        val gen = Ed25519KeyPairGenerator().apply {
            init(Ed25519KeyGenerationParameters(random))
        }
        val pair = gen.generateKeyPair()
        val priv = (pair.private as Ed25519PrivateKeyParameters).encoded
        val pub = (pair.public as Ed25519PublicKeyParameters).encoded

        val pubLine = encodePublicKey(pub, comment)
        val privPem = encodePrivateKey(pub, priv, comment, random)
        val fp = sha256Fingerprint(pub)
        return GeneratedSshKey(pubLine, privPem, fp)
    }

    private fun encodePublicKey(pub: ByteArray, comment: String): String {
        val payload = ByteArrayOutputStream().apply {
            writeSshString("ssh-ed25519")
            writeSshBytes(pub)
        }.toByteArray()
        val b64 = Base64.getEncoder().encodeToString(payload)
        return "ssh-ed25519 $b64 $comment"
    }

    private val OPENSSH_MAGIC = byteArrayOf(
        0x6F, 0x70, 0x65, 0x6E, 0x73, 0x73, 0x68, 0x2D,
        0x6B, 0x65, 0x79, 0x2D, 0x76, 0x31, 0x00
    )

    private fun encodePrivateKey(
        pub: ByteArray,
        priv: ByteArray,
        comment: String,
        random: SecureRandom
    ): String {
        val out = ByteArrayOutputStream()
        out.write(OPENSSH_MAGIC)
        out.writeSshString("none")
        out.writeSshString("none")
        out.writeSshString("")
        out.writeUint32(1)

        val pubBlock = ByteArrayOutputStream().apply {
            writeSshString("ssh-ed25519")
            writeSshBytes(pub)
        }.toByteArray()
        out.writeSshBytes(pubBlock)

        val privInner = ByteArrayOutputStream().apply {
            val checkInt = random.nextInt()
            writeUint32(checkInt)
            writeUint32(checkInt)
            writeSshString("ssh-ed25519")
            writeSshBytes(pub)
            writeSshBytes(priv + pub)
            writeSshString(comment)
        }.toByteArray()

        val padLen = (8 - (privInner.size % 8)) % 8
        val padded = ByteArray(privInner.size + padLen).also {
            System.arraycopy(privInner, 0, it, 0, privInner.size)
            for (i in 0 until padLen) it[privInner.size + i] = (i + 1).toByte()
        }
        out.writeSshBytes(padded)

        val b64 = Base64.getEncoder().encodeToString(out.toByteArray())
        return buildString {
            append("-----BEGIN OPENSSH PRIVATE KEY-----\n")
            b64.chunked(70).forEach { append(it).append('\n') }
            append("-----END OPENSSH PRIVATE KEY-----\n")
        }
    }

    private fun sha256Fingerprint(pub: ByteArray): String {
        val payload = ByteArrayOutputStream().apply {
            writeSshString("ssh-ed25519")
            writeSshBytes(pub)
        }.toByteArray()
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(payload)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    private fun ByteArrayOutputStream.writeUint32(value: Int) {
        write(ByteBuffer.allocate(4).putInt(value).array())
    }
    private fun ByteArrayOutputStream.writeSshBytes(bytes: ByteArray) {
        writeUint32(bytes.size)
        write(bytes)
    }
    private fun ByteArrayOutputStream.writeSshString(value: String) {
        writeSshBytes(value.toByteArray(Charsets.UTF_8))
    }
}
