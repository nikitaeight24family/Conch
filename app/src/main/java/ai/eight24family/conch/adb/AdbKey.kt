package ai.eight24family.conch.adb

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Certificate
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.Date

/**
 * The identity Conch presents to a phone's ADB: an RSA key pair, a self-signed
 * certificate built on it, and the public key in the peculiar binary shape ADB
 * has always used.
 *
 * Both forms of the SAME key matter, in different places. Pairing hands the
 * device the ADB public-key blob, which is what the device stores and shows in
 * its list of paired computers; the later TLS connection presents a certificate
 * carrying that key, and the device matches the two. Give it a certificate over
 * a different key and it pairs happily, then refuses every connection.
 *
 * The key is generated once and kept; a new one means pairing again.
 */
class AdbKey(val keyPair: KeyPair, private val subject: String = "conch@android") {

    /** The certificate the TLS handshake presents, self-signed by this key. */
    val certificate: Certificate by lazy { selfSign() }

    /** The private key in the form the TLS stack wants. */
    val tlsPrivateKey: AsymmetricKeyParameter by lazy {
        PrivateKeyFactory.createKey(keyPair.private.encoded)
    }

    /**
     * The public key as ADB writes it — the content of an `adbkey.pub`.
     *
     * ⚠ NOT an ordinary key encoding. It is a fixed C struct, little-endian,
     * carrying the modulus, the Montgomery constants the device's bootloader-era
     * verifier expects, and the exponent — then base64, then a space and a name.
     * The device stores this verbatim; anything else is rejected without a word.
     */
    /**
     * Sign adbd's 20-byte auth challenge, the way the legacy handshake wants it.
     *
     * ⚠ The token IS ALREADY A SHA-1 DIGEST — it must not be hashed again. adbd
     * verifies a PKCS#1 v1.5 signature whose DigestInfo says SHA-1, so the bytes
     * that get padded and encrypted are the fixed ASN.1 prefix followed by the
     * token. Signing with "SHA1withRSA" would hash the digest a second time and
     * be rejected with no message at all — the device simply asks again.
     */
    fun signAdbToken(token: ByteArray): ByteArray {
        val sha1DigestInfoPrefix = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )
        val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keyPair.private)
        return cipher.doFinal(sha1DigestInfoPrefix + token)
    }

    fun publicKeyBlob(): ByteArray {
        val pub = keyPair.public as RSAPublicKey
        val n = pub.modulus
        require(n.bitLength() == 2048) { "ADB expects a 2048-bit key, got ${n.bitLength()}" }

        val words = 2048 / 32
        val r32 = BigInteger.ONE.shiftLeft(32)
        // n0inv = -(n^-1) mod 2^32, as the verifier's Montgomery multiplication
        // needs it.
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32))
        // rr = (2^2048)^2 mod n.
        val rr = BigInteger.ONE.shiftLeft(2048 * 2).mod(n)

        val out = ByteArrayOutputStream()
        fun putWord(v: Long) {
            out.write((v and 0xFF).toInt())
            out.write(((v ushr 8) and 0xFF).toInt())
            out.write(((v ushr 16) and 0xFF).toInt())
            out.write(((v ushr 24) and 0xFF).toInt())
        }
        fun putBigEndianAsLittleWords(value: BigInteger) {
            // The struct holds the number as an array of 32-bit words, LEAST
            // significant word first, each word itself little-endian.
            var v = value
            repeat(words) {
                putWord(v.and(BigInteger.valueOf(0xFFFFFFFFL)).toLong())
                v = v.shiftRight(32)
            }
        }
        putWord(words.toLong())
        putWord(n0inv.toLong())
        putBigEndianAsLittleWords(n)
        putBigEndianAsLittleWords(rr)
        putWord(pub.publicExponent.toLong())

        val encoded = Base64.getEncoder().encodeToString(out.toByteArray())
        return (encoded + " " + subject).toByteArray(Charsets.US_ASCII)
    }

    private fun selfSign(): Certificate {
        val now = System.currentTimeMillis()
        val name = X500Name("CN=$subject")
        val builder = X509v3CertificateBuilder(
            name,
            // A serial that is unique per key rather than per second: two
            // certificates minted in the same millisecond would otherwise
            // collide, and the device keys some state on the serial.
            BigInteger(64, java.security.SecureRandom()).abs().max(BigInteger.ONE),
            Date(now - ONE_DAY_MS),
            Date(now + TEN_YEARS_MS),
            name,
            SubjectPublicKeyInfo.getInstance(keyPair.public.encoded),
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        return builder.build(signer).toASN1Structure()
    }

    companion object {
        private const val ONE_DAY_MS = 24L * 60 * 60 * 1000
        private const val TEN_YEARS_MS = 10L * 365 * 24 * 60 * 60 * 1000

        /** A fresh 2048-bit key — the size ADB's own tooling uses. */
        fun generate(subject: String = "conch@android"): AdbKey {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048)
            return AdbKey(generator.generateKeyPair(), subject)
        }

        /** Rebuild from a stored private key; the public half rides inside it. */
        fun fromPrivateKey(privateKey: RSAPrivateKey, subject: String = "conch@android"): AdbKey {
            val factory = java.security.KeyFactory.getInstance("RSA")
            val crt = privateKey as? java.security.interfaces.RSAPrivateCrtKey
                ?: throw IllegalArgumentException("need a CRT private key to recover the public half")
            val pub = factory.generatePublic(
                java.security.spec.RSAPublicKeySpec(crt.modulus, crt.publicExponent),
            )
            return AdbKey(KeyPair(pub, privateKey), subject)
        }
    }
}
