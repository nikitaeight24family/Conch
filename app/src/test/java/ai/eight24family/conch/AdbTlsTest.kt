package ai.eight24family.conch

import ai.eight24family.conch.adb.AdbKey
import ai.eight24family.conch.adb.AdbPairing
import ai.eight24family.conch.adb.AdbTls
import org.bouncycastle.tls.CertificateRequest
import org.bouncycastle.tls.ClientCertificateType
import org.bouncycastle.tls.DefaultTlsServer
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.SignatureAndHashAlgorithm
import org.bouncycastle.tls.TlsCredentialedSigner
import org.bouncycastle.tls.TlsServerProtocol
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCertificate
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Vector
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A real TLS handshake, in one process, proving the piece the whole pairing
 * hangs on: that both ends can export the SAME keying material for the label
 * ADB uses.
 *
 * This is not a formality. ADB appends 64 exported bytes to the six-digit code
 * before the key exchange, so if our export disagrees with the peer's by a
 * single byte, everything downstream still runs perfectly and simply produces a
 * key the phone does not share. There is no error to read; pairing just fails.
 * The platform's own TLS cannot export at all, which is why the stack here is a
 * different one — and why it gets its own end-to-end test rather than trust.
 */
class AdbTlsTest {

    private val key = AdbKey.generate("test@conch")

    /** Minimal TLS server: presents a certificate, asks for one, accepts any. */
    private inner class TestServer(crypto: BcTlsCrypto) : DefaultTlsServer(crypto) {
        var exported: ByteArray? = null

        override fun getSupportedVersions(): Array<ProtocolVersion> =
            ProtocolVersion.TLSv13.downTo(ProtocolVersion.TLSv12)

        override fun getCredentials(): TlsCredentialedSigner = signer()

        override fun getRSASignerCredentials(): TlsCredentialedSigner = signer()

        override fun getCertificateRequest(): CertificateRequest {
            val algorithms = Vector<SignatureAndHashAlgorithm>()
            algorithms.add(SignatureAndHashAlgorithm.rsa_pss_rsae_sha256)
            // TLS 1.3 replaced the certificate-type list with a request context;
            // the two versions take different constructors entirely.
            return if (ProtocolVersion.TLSv13.equals(context.serverVersion)) {
                CertificateRequest(ByteArray(0), algorithms, null, null)
            } else {
                CertificateRequest(shortArrayOf(ClientCertificateType.rsa_sign), algorithms, null)
            }
        }

        override fun notifyClientCertificate(clientCertificate: org.bouncycastle.tls.Certificate?) {
            // Accept whatever the client offers, exactly as the pairing server does.
        }

        override fun notifyHandshakeComplete() {
            exported = context.exportKeyingMaterial(
                String(AdbPairing.TLS_EXPORT_LABEL, Charsets.ISO_8859_1), null, AdbPairing.TLS_EXPORT_SIZE,
            )
        }

        private fun signer(): TlsCredentialedSigner {
            val bcCrypto = crypto as BcTlsCrypto
            val entry = BcTlsCertificate(bcCrypto, key.certificate)
            // Same version split as the client: 1.3 wants entries and a request
            // context, everything earlier wants a bare chain.
            val chain = if (ProtocolVersion.TLSv13.equals(context.serverVersion)) {
                org.bouncycastle.tls.Certificate(
                    ByteArray(0),
                    arrayOf(org.bouncycastle.tls.CertificateEntry(entry, null)),
                )
            } else {
                org.bouncycastle.tls.Certificate(arrayOf(entry))
            }
            return BcDefaultTlsCredentialedSigner(
                TlsCryptoParameters(context),
                bcCrypto,
                key.tlsPrivateKey,
                chain,
                SignatureAndHashAlgorithm.rsa_pss_rsae_sha256,
            )
        }
    }

    private fun handshake(): Triple<ByteArray, ByteArray, AdbTls.Session> {
        val clientToServer = PipedOutputStream()
        val serverReads = PipedInputStream(clientToServer, 1 shl 16)
        val serverToClient = PipedOutputStream()
        val clientReads = PipedInputStream(serverToClient, 1 shl 16)

        val pool = Executors.newSingleThreadExecutor()
        val server = TestServer(BcTlsCrypto())
        // Keep the server's own failure: when a handshake dies, the side that
        // threw is the side that knows why, and the other one only ever sees
        // "internal_error". Without this the test reports the symptom.
        var serverError: Throwable? = null
        val serverTask = pool.submit {
            runCatching { TlsServerProtocol(serverReads, serverToClient).accept(server) }
                .onFailure { serverError = it }
        }
        val session = try {
            AdbTls.connect(
                clientReads, clientToServer, key.certificate, key.tlsPrivateKey,
                // Everything the tests will ask for has to be named BEFORE the
                // handshake: the exporter secret is gone once it completes.
                exports = listOf(
                    AdbTls.Export(AdbPairing.TLS_EXPORT_LABEL, AdbPairing.TLS_EXPORT_SIZE),
                    AdbTls.Export("adb-label".toByteArray(), 64),
                ),
            )
        } catch (clientFailure: Throwable) {
            runCatching { serverTask.get(10, TimeUnit.SECONDS) }
            pool.shutdownNow()
            serverError?.let { throw AssertionError("server side failed the handshake", it) }
            throw clientFailure
        }
        serverTask.get(30, TimeUnit.SECONDS)
        pool.shutdownNow()
        serverError?.let { throw AssertionError("server side failed the handshake", it) }

        val clientExport = session.exportKeyingMaterial(
            AdbPairing.TLS_EXPORT_LABEL, AdbPairing.TLS_EXPORT_SIZE,
        )
        return Triple(clientExport, server.exported!!, session)
    }

    @Test
    fun `both ends export identical keying material for ADB's label`() {
        val (client, server, session) = handshake()
        assertEquals(AdbPairing.TLS_EXPORT_SIZE, client.size)
        assertArrayEquals(client, server)
        // Not a block of zeros, i.e. actually derived from the handshake.
        assertFalse(client.all { it.toInt() == 0 })
        session.close()
    }

    @Test
    fun `the label's NUL byte changes the answer`() {
        // The exact reason the label is passed as bytes: the reference measures
        // its C string with sizeof, so the terminator is part of it. Nine bytes
        // instead of ten derives something else entirely — and nothing anywhere
        // would report that as an error.
        val (_, _, session) = handshake()
        val withNul = session.exportKeyingMaterial(AdbPairing.TLS_EXPORT_LABEL, 64)
        val withoutNul = session.exportKeyingMaterial("adb-label".toByteArray(), 64)
        assertFalse(withNul.contentEquals(withoutNul))
        session.close()
    }

    @Test
    fun `the exchange is unique per handshake`() {
        val (first, _, s1) = handshake()
        val (second, _, s2) = handshake()
        assertFalse(first.contentEquals(second))
        s1.close()
        s2.close()
    }

    @Test
    fun `the password the pairing uses is the code plus this material`() {
        val (client, server, session) = handshake()
        val code = "642099".toByteArray()
        val ours = AdbPairing.passwordWithChannelBinding(code, client)
        val theirs = AdbPairing.passwordWithChannelBinding(code, server)
        // Both sides must arrive at the same SPAKE2 password, or the exchange
        // silently produces two different keys.
        assertArrayEquals(ours, theirs)
        assertEquals(70, ours.size)
        session.close()
    }
}
