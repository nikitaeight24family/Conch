package ai.eight24family.conch.adb

import org.bouncycastle.asn1.x509.Certificate
import org.bouncycastle.tls.AlertDescription
import org.bouncycastle.tls.CertificateRequest
import org.bouncycastle.tls.DefaultTlsClient
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.SignatureAndHashAlgorithm
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.TlsCredentials
import org.bouncycastle.tls.TlsServerCertificate
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import java.io.InputStream
import java.io.OutputStream

/**
 * The TLS carrier the pairing conversation rides on.
 *
 * Two things are needed here that the platform's own TLS cannot give:
 *
 *  1. **Exported keying material** (RFC 5705). ADB binds the pairing exchange to
 *     the exact connection it happens on by appending 64 exported bytes to the
 *     six-digit code. `javax.net.ssl` has no API for this at all, and the
 *     platform's own TLS engine keeps its equivalent behind classes an app
 *     cannot reach — so the TLS stack itself has to be one that exposes it.
 *  2. **Accepting any server certificate.** The pairing server presents a
 *     self-signed certificate that nothing can chain to a root, and the
 *     reference client accepts it unconditionally. That is not a lapse: the
 *     connection is authenticated by the code the user types, through SPAKE2,
 *     not by the certificate. Refusing an unverifiable certificate here would
 *     make pairing impossible, and accepting one buys an attacker nothing —
 *     without the code the exchange yields no key.
 *
 * Everything here is glue we own; the TLS implementation underneath is the same
 * Bouncy Castle already shipping in this app for SSH.
 */
object AdbTls {

    /** What to pull out of the finished handshake, and how much of it. */
    data class Export(val label: ByteArray, val length: Int) {
        override fun equals(other: Any?): Boolean =
            other is Export && label.contentEquals(other.label) && length == other.length

        override fun hashCode(): Int = 31 * label.contentHashCode() + length
    }

    /** A connected TLS session, plus what was exported from its handshake. */
    class Session internal constructor(
        private val protocol: TlsClientProtocol,
        private val exports: Map<Export, ByteArray>,
    ) {
        val input: InputStream get() = protocol.inputStream
        val output: OutputStream get() = protocol.outputStream

        /**
         * RFC 5705 keying material captured for [label].
         *
         * ⚠ TWO traps in one call. First, [label] is BYTES, not text, and the
         * caller passes it WITH its NUL terminator (see
         * [AdbPairing.TLS_EXPORT_LABEL]) because the peer measures its C string
         * with `sizeof`; nine bytes instead of ten derives something else and
         * nothing reports an error. Second, this cannot be asked for after the
         * fact — the TLS stack destroys the exporter secret the moment the
         * handshake callback returns, so everything needed must be named in
         * [connect] up front. Asking here for something not requested then is a
         * programming error, not a runtime condition.
         */
        fun exportKeyingMaterial(label: ByteArray, length: Int): ByteArray =
            exports[Export(label, length)]
                ?: throw IllegalArgumentException(
                    "keying material for this label was not requested before the handshake",
                )

        fun close() {
            runCatching { protocol.close() }
        }
    }

    /**
     * Speak TLS as the client over [rawInput]/[rawOutput], presenting
     * [clientCertificate] signed by [clientPrivateKey], and accepting whatever
     * certificate the peer offers.
     */
    fun connect(
        rawInput: InputStream,
        rawOutput: OutputStream,
        clientCertificate: Certificate,
        clientPrivateKey: AsymmetricKeyParameter,
        exports: List<Export> = listOf(Export(AdbPairing.TLS_EXPORT_LABEL, AdbPairing.TLS_EXPORT_SIZE)),
    ): Session {
        val crypto = BcTlsCrypto()
        val protocol = TlsClientProtocol(rawInput, rawOutput)
        val captured = LinkedHashMap<Export, ByteArray>()
        val client = object : DefaultTlsClient(crypto) {

            /** The handshake context is protected in the base class; this hands
             *  it out so the code below can reach it. */
            fun tlsContext() = context

            /**
             * The ONLY moment keying material can be taken. Bouncy Castle
             * destroys the exporter secret as soon as this returns, so whatever
             * the protocol will need has to be pulled out here — which is why
             * [connect] takes the list up front instead of handing back a
             * function to call later.
             */
            override fun notifyHandshakeComplete() {
                super.notifyHandshakeComplete()
                val ctx = context ?: return
                for (export in exports) {
                    captured[export] = ctx.exportKeyingMaterial(
                        String(export.label, Charsets.ISO_8859_1), null, export.length,
                    )
                }
            }

            override fun getSupportedVersions(): Array<ProtocolVersion> =
                ProtocolVersion.TLSv13.downTo(ProtocolVersion.TLSv12)

            override fun getAuthentication(): TlsAuthentication = object : TlsAuthentication {
                // Deliberately empty: see the note on this file. The code the
                // user types is the authentication.
                override fun notifyServerCertificate(serverCertificate: TlsServerCertificate?) = Unit

                override fun getClientCredentials(request: CertificateRequest?): TlsCredentials? {
                    val ctx = context ?: return null
                    // Offer our certificate only for a signature scheme the peer
                    // actually asked for; guessing one gets the handshake torn
                    // down with a bad_certificate rather than a useful message.
                    val algorithm = chooseSignatureAlgorithm(request) ?: return null
                    return BcDefaultTlsCredentialedSigner(
                        org.bouncycastle.tls.crypto.TlsCryptoParameters(ctx),
                        crypto,
                        clientPrivateKey,
                        certificateFor(ctx.serverVersion, crypto, clientCertificate, request),
                        algorithm,
                    )
                }
            }
        }
        protocol.connect(client)
        if (captured.size != exports.size) {
            throw IllegalStateException(
                "the handshake completed without exporting keying material " +
                    "(${captured.size} of ${exports.size})",
            )
        }
        return Session(protocol, captured)
    }

    /**
     * Wrap our certificate the way the NEGOTIATED version expects.
     *
     * ⚠ TLS 1.3 did not merely add fields — it changed the certificate message
     * into a list of entries carrying the request context, and Bouncy Castle
     * keeps the two shapes in one class. Hand a 1.3 handshake a certificate
     * built the old way and it does not complain at construction; it throws deep
     * inside the encoder, and the peer sees only `internal_error`. Android 11 and
     * later negotiate 1.3, so this is the path that matters.
     */
    private fun certificateFor(
        version: ProtocolVersion?,
        crypto: BcTlsCrypto,
        certificate: Certificate,
        request: CertificateRequest?,
    ): org.bouncycastle.tls.Certificate {
        val entry = org.bouncycastle.tls.crypto.impl.bc.BcTlsCertificate(crypto, certificate)
        return if (version != null && ProtocolVersion.TLSv13.isEqualOrEarlierVersionOf(version)) {
            org.bouncycastle.tls.Certificate(
                request?.certificateRequestContext ?: ByteArray(0),
                arrayOf(org.bouncycastle.tls.CertificateEntry(entry, null)),
            )
        } else {
            org.bouncycastle.tls.Certificate(arrayOf(entry))
        }
    }

    /**
     * Pick a signature scheme from the peer's request.
     *
     * The peer lists what it will accept; anything else makes the handshake fail
     * at the certificate rather than at a place that names the cause. RSA with
     * SHA-256 first because that is what an ADB key is, then whatever else the
     * peer offered for RSA.
     */
    private fun chooseSignatureAlgorithm(request: CertificateRequest?): SignatureAndHashAlgorithm? {
        val offered = request?.supportedSignatureAlgorithms ?: return SignatureAndHashAlgorithm.rsa_pss_rsae_sha256
        val list = offered.filterIsInstance<SignatureAndHashAlgorithm>()
        return list.firstOrNull { it == SignatureAndHashAlgorithm.rsa_pss_rsae_sha256 }
            ?: list.firstOrNull {
                it.signature == org.bouncycastle.tls.SignatureAlgorithm.rsa ||
                    it.signature == org.bouncycastle.tls.SignatureAlgorithm.rsa_pss_rsae_sha256
            }
            ?: list.firstOrNull()
    }

    /** Alert we send when we give up mid-handshake, for the peer's log. */
    internal const val ALERT_USER_CANCELED = AlertDescription.user_canceled
}
