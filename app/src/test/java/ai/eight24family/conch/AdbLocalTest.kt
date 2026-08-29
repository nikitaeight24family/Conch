package ai.eight24family.conch

import ai.eight24family.conch.adb.AdbKey
import ai.eight24family.conch.adb.AdbLocal
import ai.eight24family.conch.adb.AdbProtocol
import ai.eight24family.conch.adb.AdbShellV2
import org.bouncycastle.tls.CertificateRequest
import org.bouncycastle.tls.DefaultTlsServer
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.SignatureAndHashAlgorithm
import org.bouncycastle.tls.TlsCredentialedSigner
import org.bouncycastle.tls.TlsServerProtocol
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCertificate
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Vector
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The whole client, driven against a stand-in for `adbd`: plain handshake, TLS
 * upgrade with our certificate, the SECOND handshake inside TLS, then a command
 * run at shell level with its output and exit status coming back apart.
 *
 * The step this exists to pin is the one that is easy to miss — the ADB
 * conversation RESTARTS after the TLS upgrade. Treat the TLS handshake as the
 * end of the handshake and the peer sits waiting for a connection message that
 * never arrives, while the first service you open is answered with silence and
 * no error anywhere.
 */
class AdbLocalTest {

    private val deviceKey = AdbKey.generate("device@test")
    private val clientKey = AdbKey.generate("conch@test")
    private val pool = Executors.newSingleThreadExecutor()

    /** Minimal TLS server half, accepting whatever certificate the client shows. */
    private inner class TlsHalf(crypto: BcTlsCrypto) : DefaultTlsServer(crypto) {
        override fun getSupportedVersions(): Array<ProtocolVersion> =
            ProtocolVersion.TLSv13.downTo(ProtocolVersion.TLSv12)

        override fun getCredentials(): TlsCredentialedSigner = signer()
        override fun getRSASignerCredentials(): TlsCredentialedSigner = signer()

        override fun getCertificateRequest(): CertificateRequest {
            val algorithms = Vector<SignatureAndHashAlgorithm>()
            algorithms.add(SignatureAndHashAlgorithm.rsa_pss_rsae_sha256)
            // ⚠ The two TLS versions want DIFFERENT shapes here — 1.3 carries a
            // request context, 1.2 a list of certificate types — and handing over
            // the wrong one aborts the handshake with a bare internal_error that
            // names nothing. Cost one debugging round already.
            return if (ProtocolVersion.TLSv13.equals(context.serverVersion)) {
                CertificateRequest(ByteArray(0), algorithms, null, null)
            } else {
                CertificateRequest(
                    shortArrayOf(org.bouncycastle.tls.ClientCertificateType.rsa_sign),
                    algorithms,
                    null,
                )
            }
        }

        override fun notifyClientCertificate(certificate: org.bouncycastle.tls.Certificate?) = Unit

        private fun signer(): TlsCredentialedSigner {
            val c = crypto as BcTlsCrypto
            val leaf = BcTlsCertificate(c, deviceKey.certificate)
            // ⚠ TLS 1.3 changed the certificate message into a list of entries
            // carrying a request context. Built the 1.2 way it does not merely
            // look wrong — encoding it throws, and the peer sees only
            // internal_error with no mention of certificates. The client side had
            // exactly this bug; a test double that cannot reproduce the shape
            // adbd actually negotiates would never have shown it.
            val version = context.serverVersion
            val chain = if (version != null && ProtocolVersion.TLSv13.isEqualOrEarlierVersionOf(version)) {
                org.bouncycastle.tls.Certificate(
                    ByteArray(0),
                    arrayOf(org.bouncycastle.tls.CertificateEntry(leaf, null)),
                )
            } else {
                org.bouncycastle.tls.Certificate(arrayOf(leaf))
            }
            return BcDefaultTlsCredentialedSigner(
                TlsCryptoParameters(context),
                c,
                deviceKey.tlsPrivateKey,
                chain,
                SignatureAndHashAlgorithm.rsa_pss_rsae_sha256,
            )
        }
    }

    /**
     * Everything a device does, in the order it does it.
     *
     * Wrapped so a failure on this side is PRINTED rather than swallowed by the
     * executor: without that, the client just sees a TLS alert with no cause and
     * the real mistake is invisible.
     */
    private fun deviceSide(input: InputStream, output: OutputStream, reply: ByteArray, exitCode: Int): String =
        try {
            deviceSideInner(input, output, reply, exitCode)
        } catch (t: Throwable) {
            var c: Throwable? = t
            val seen = HashSet<Throwable>()
            while (c != null && seen.add(c)) {
                println("[device] FAILED: ${c.javaClass.name}: ${c.message}")
                c.stackTrace.take(6).forEach { println("[device]    at $it") }
                c = c.cause
            }
            throw t
        }

    private fun deviceSideInner(input: InputStream, output: OutputStream, reply: ByteArray, exitCode: Int): String {
        // 1. plain CNXN in, STLS out
        val hello = AdbProtocol.read(input)!!
        check(hello.command == AdbProtocol.A_CNXN) { "expected CNXN first" }
        AdbProtocol.write(output, AdbProtocol.Message(AdbProtocol.A_STLS, AdbProtocol.STLS_VERSION, 0))
        // 2. the client's STLS, then TLS
        val theirStls = AdbProtocol.read(input)!!
        check(theirStls.command == AdbProtocol.A_STLS) { "expected the client to accept TLS" }
        val tls = TlsServerProtocol(input, output)
        tls.accept(TlsHalf(BcTlsCrypto()))
        // 3. the SECOND handshake, now encrypted
        val secureHello = AdbProtocol.read(tls.inputStream)!!
        check(secureHello.command == AdbProtocol.A_CNXN) { "expected CNXN inside TLS" }
        AdbProtocol.write(
            tls.outputStream,
            AdbProtocol.stringMessage(
                AdbProtocol.A_CNXN, AdbProtocol.VERSION, AdbProtocol.MAX_PAYLOAD, "device::model=TEST",
            ),
        )
        // 4. a service
        val open = AdbProtocol.read(tls.inputStream)!!
        check(open.command == AdbProtocol.A_OPEN) { "expected OPEN" }
        val service = String(open.payload, Charsets.UTF_8).trimEnd(Char(0))
        val remoteId = 77
        AdbProtocol.write(tls.outputStream, AdbProtocol.Message(AdbProtocol.A_OKAY, remoteId, open.arg0))
        val body = AdbShellV2.packet(AdbShellV2.ID_STDOUT, reply) +
            AdbShellV2.packet(AdbShellV2.ID_EXIT, byteArrayOf(exitCode.toByte()))
        AdbProtocol.write(tls.outputStream, AdbProtocol.Message(AdbProtocol.A_WRTE, remoteId, open.arg0, body))
        AdbProtocol.read(tls.inputStream) // the client's OKAY for that write
        AdbProtocol.write(tls.outputStream, AdbProtocol.Message(AdbProtocol.A_CLSE, remoteId, open.arg0))
        return service
    }

    @Test
    fun `the client reaches a shell and gets output, stderr and exit status apart`() {
        val clientToDevice = PipedOutputStream()
        val deviceIn = PipedInputStream(clientToDevice, 1 shl 16)
        val deviceToClient = PipedOutputStream()
        val clientIn = PipedInputStream(deviceToClient, 1 shl 16)

        val device = pool.submit<String> {
            deviceSide(deviceIn, deviceToClient, "uid=2000(shell)".toByteArray(), exitCode = 0)
        }

        val session = AdbLocal.handshake(clientIn, clientToDevice, clientKey)
        assertEquals("device::model=TEST", session.deviceBanner)

        val result = session.exec("id")
        assertEquals("uid=2000(shell)", result.stdout)
        assertEquals(0, result.exitCode)
        assertEquals("", result.stderr)

        // And the service name is the one adbd expects for a non-interactive run.
        assertEquals("shell,v2,raw:id", device.get(30, TimeUnit.SECONDS))
        session.close()
    }

    @Test
    fun `a failing command comes back with its own exit code`() {
        val clientToDevice = PipedOutputStream()
        val deviceIn = PipedInputStream(clientToDevice, 1 shl 16)
        val deviceToClient = PipedOutputStream()
        val clientIn = PipedInputStream(deviceToClient, 1 shl 16)

        pool.submit { deviceSide(deviceIn, deviceToClient, "".toByteArray(), exitCode = 127) }
        val session = AdbLocal.handshake(clientIn, clientToDevice, clientKey)
        assertEquals(127, session.exec("nope").exitCode)
        session.close()
    }

    /**
     * The legacy handshake, when the device already trusts this key.
     *
     * ⚠ THIS USED TO ASSERT A REFUSAL — the path was called "a dead end that
     * needs a computer". It is not a dead end, it is the ONLY route that
     * survives leaving Wi-Fi: Android tears wireless debugging down the moment
     * the association drops, and this listener keeps serving until the phone
     * reboots (2026-08-30). Refusing it was the difference between a phone
     * shell that works all day on mobile data and one that dies on a network
     * change.
     */
    @Test
    fun `a trusted key completes the legacy handshake with no dialog`() {
        val clientToDevice = PipedOutputStream()
        val deviceIn = PipedInputStream(clientToDevice, 1 shl 16)
        val deviceToClient = PipedOutputStream()
        val clientIn = PipedInputStream(deviceToClient, 1 shl 16)

        pool.submit {
            AdbProtocol.read(deviceIn) // our CNXN
            AdbProtocol.write(deviceToClient, AdbProtocol.Message(AdbProtocol.A_AUTH, 1, 0, ByteArray(20)))
            val signed = AdbProtocol.read(deviceIn)!!
            // The client answers a challenge with a SIGNATURE (type 2), never
            // with the key — offering the key is what raises Android's dialog,
            // and a device that already knows us must not be asked to show one.
            check(signed.command == AdbProtocol.A_AUTH && signed.arg0 == 2)
            AdbProtocol.write(
                deviceToClient,
                AdbProtocol.stringMessage(AdbProtocol.A_CNXN, AdbProtocol.VERSION, 1 shl 20, "device::legacy"),
            )
        }

        val session = AdbLocal.handshake(clientIn, clientToDevice, clientKey)
        assertEquals("device::legacy", session.deviceBanner)
        session.close()
    }

    /** An unknown key is OFFERED, which is what makes the phone ask its owner. */
    @Test
    fun `an untrusted key is offered so the phone can ask`() {
        val clientToDevice = PipedOutputStream()
        val deviceIn = PipedInputStream(clientToDevice, 1 shl 16)
        val deviceToClient = PipedOutputStream()
        val clientIn = PipedInputStream(deviceToClient, 1 shl 16)

        pool.submit {
            AdbProtocol.read(deviceIn) // our CNXN
            AdbProtocol.write(deviceToClient, AdbProtocol.Message(AdbProtocol.A_AUTH, 1, 0, ByteArray(20)))
            AdbProtocol.read(deviceIn) // the signature, which this device does not know
            AdbProtocol.write(deviceToClient, AdbProtocol.Message(AdbProtocol.A_AUTH, 1, 0, ByteArray(20)))
            val offered = AdbProtocol.read(deviceIn)!!
            check(offered.command == AdbProtocol.A_AUTH && offered.arg0 == 3)
            // The blob is the base64 key plus a name, NUL-terminated.
            check(offered.payload.last() == 0.toByte())
            AdbProtocol.write(
                deviceToClient,
                AdbProtocol.stringMessage(AdbProtocol.A_CNXN, AdbProtocol.VERSION, 1 shl 20, "device::allowed"),
            )
        }

        val session = AdbLocal.handshake(clientIn, clientToDevice, clientKey)
        assertEquals("device::allowed", session.deviceBanner)
        session.close()
    }
}
