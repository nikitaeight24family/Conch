package ai.eight24family.conch.ssh

import ai.eight24family.conch.domain.AuthMethod
import ai.eight24family.conch.domain.Server
import ai.eight24family.conch.domain.ServerSecrets
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.security.PublicKey
import java.util.concurrent.TimeUnit

sealed interface ConnectResult {
    data class UnknownHost(val fingerprint: String, val keyType: String) : ConnectResult
    data class Success(val fingerprint: String) : ConnectResult
    data class HostKeyMismatch(val expected: String, val actual: String) : ConnectResult
    data class Failure(val reason: String, val kind: FailureKind) : ConnectResult
}

enum class FailureKind {
    AUTH_PASSWORD_REJECTED,
    AUTH_KEY_REJECTED,
    NETWORK_UNREACHABLE,
    HOST_NOT_RESOLVED,
    TIMEOUT,
    OTHER
}

// `open` so tests can plug in fakes via subclassing without spinning up a
// real SSH server — see `FakeSshClient` in test sources. Production code
// shouldn't subclass; kept narrow for test seam only.
open class SshClient {

    private fun newClient(
        connectTimeoutSec: Int = 15,
        socketTimeoutSec: Int = 15,
    ) = SSHClient(DefaultConfig()).apply {
        connectTimeout = TimeUnit.SECONDS.toMillis(connectTimeoutSec.toLong()).toInt()
        timeout = TimeUnit.SECONDS.toMillis(socketTimeoutSec.toLong()).toInt()
    }

    open suspend fun testConnection(server: Server, secrets: ServerSecrets): ConnectResult =
        withContext(Dispatchers.IO) {
            // Read from Settings → Connection. Wrapped in runCatching because
            // tests construct SshClient without booting ServiceLocator —
            // missing prefs falls back to the legacy 15 s default.
            val tSec = SilentlyTry.logged("SshAi-SshClient", "read connect timeout pref") {
                ai.eight24family.conch.di.ServiceLocator.preferences
                    .sshConnectTimeoutSec.first()
                    .takeIf { it > 0 }?.coerceIn(5, 60)
            } ?: 15
            val client = newClient(connectTimeoutSec = tSec, socketTimeoutSec = tSec)
            val verifier = TofuHostKeyVerifier(server.knownHostKey)
            client.addHostKeyVerifier(verifier)

            try {
                client.connect(server.host, server.port)
                authenticate(client, server, secrets)
                val fp = verifier.seenFingerprint ?: server.knownHostKey ?: ""
                SilentlyTry.fired("SshAi-SshClient", "disconnect after test") { client.disconnect() }
                if (server.knownHostKey == null) {
                    ConnectResult.UnknownHost(fp, verifier.seenKeyType ?: "")
                } else {
                    ConnectResult.Success(fp)
                }
            } catch (e: Exception) {
                SilentlyTry.fired("SshAi-SshClient", "disconnect after test failure") { client.disconnect() }
                if (verifier.mismatch) {
                    ConnectResult.HostKeyMismatch(
                        expected = server.knownHostKey ?: "",
                        actual = verifier.seenFingerprint ?: ""
                    )
                } else {
                    val (msg, kind) = humanizeError(e, server.authMethod)
                    ConnectResult.Failure(msg, kind)
                }
            }
        }

    open suspend fun execute(
        server: Server,
        secrets: ServerSecrets,
        command: String,
        /**
         * Hardware-token signer, supplied by the caller when the server
         * row points to an SK key. Without this the SK auth path fails
         * fast with "security-key signer not provided". Software-key
         * and password servers ignore it.
         */
        skSigner: ai.eight24family.conch.ssh.securitykey.SkSigner? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        // Read from Settings → Connection. runCatching for the same
        // test-seam reason as testConnection above. Socket timeout is
        // then overridden to 60s below because one-shot exec needs a
        // longer read window.
        val tSec = SilentlyTry.logged("SshAi-SshClient", "read exec timeout pref") {
            ai.eight24family.conch.di.ServiceLocator.preferences
                .sshConnectTimeoutSec.first()
                .takeIf { it > 0 }?.coerceIn(5, 60)
        } ?: 15
        val client = newClient(connectTimeoutSec = tSec, socketTimeoutSec = tSec).apply {
            timeout = TimeUnit.SECONDS.toMillis(60).toInt()
        }
        client.addHostKeyVerifier(TofuHostKeyVerifier(server.knownHostKey))

        try {
            client.connect(server.host, server.port)
            authenticate(client, server, secrets, skSigner)

            val session = client.startSession()
            val cmd = session.exec(command)
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            cmd.inputStream.copyTo(out)
            cmd.errorStream.copyTo(err)
            cmd.join(60, TimeUnit.SECONDS)
            val exit = cmd.exitStatus ?: -1
            session.close()
            client.disconnect()

            val combined = buildString {
                append(String(out.toByteArray(), Charsets.UTF_8))
                if (err.size() > 0) {
                    if (isNotEmpty()) append('\n')
                    append("[stderr]\n")
                    append(String(err.toByteArray(), Charsets.UTF_8))
                }
                if (exit != 0) {
                    if (isNotEmpty()) append('\n')
                    append("[exit $exit]")
                }
            }
            Result.success(combined)
        } catch (e: Exception) {
            SilentlyTry.fired("SshAi-SshClient", "disconnect after exec failure") { client.disconnect() }
            Result.failure(e)
        }
    }

    private fun authenticate(
        client: SSHClient,
        server: Server,
        secrets: ServerSecrets,
        skSigner: ai.eight24family.conch.ssh.securitykey.SkSigner? = null,
    ) {
        when (server.authMethod) {
            AuthMethod.PASSWORD -> {
                val pwd = secrets.password ?: error("Password is required")
                client.authPassword(server.username, pwd)
            }
            AuthMethod.KEY -> {
                if (secrets.skKeys.isNotEmpty()) {
                    val signer = skSigner
                        ?: error("security-key signer not provided — call execute(..., skSigner = …) for SK servers")
                    // Single-method legacy path: SshClient is only used by
                    // the test-connection / one-shot exec entry points,
                    // where multi-key wouldn't add value (the user already
                    // taps once and we just need any matching cred). Use
                    // the first enrolled key.
                    val skKey = secrets.skKeys.first()
                    val skAuth = ai.eight24family.conch.ssh.securitykey.SkAuthPublickey(
                        publicKeyBlob = ai.eight24family.conch.ssh.securitykey.SkPublicKey.blobBytes(skKey),
                        algorithmName = ai.eight24family.conch.ssh.securitykey.SkPublicKey.algorithmName(skKey.type),
                        signer = signer,
                    )
                    client.auth(server.username, listOf(skAuth))
                } else {
                    val pem = secrets.privateKeyPem ?: error("Private key is required")
                    val provider = pickKeyProvider(pem, secrets.keyPassphrase)
                    client.authPublickey(server.username, provider)
                }
            }
        }
    }

    private fun humanizeError(e: Throwable, authMethod: AuthMethod): Pair<String, FailureKind> {
        return when {
            e is UnknownHostException ->
                "Host not found. Check the address." to FailureKind.HOST_NOT_RESOLVED
            e is ConnectException ->
                "Could not reach the server. Check the address, port, and that SSH is running." to FailureKind.NETWORK_UNREACHABLE
            e.message?.contains("timeout", ignoreCase = true) == true ||
                e.message?.contains("timed out", ignoreCase = true) == true ->
                "Connection timed out. Server might be down or behind a firewall." to FailureKind.TIMEOUT
            e is UserAuthException || e.message?.contains("Exhausted available authentication methods") == true -> {
                if (authMethod == AuthMethod.PASSWORD)
                    "Wrong password for this user." to FailureKind.AUTH_PASSWORD_REJECTED
                else
                    "Server rejected the SSH key. Add the public half to ~/.ssh/authorized_keys on the server." to FailureKind.AUTH_KEY_REJECTED
            }
            e is IOException -> (e.message ?: "Network error.") to FailureKind.OTHER
            else -> ai.eight24family.conch.util.ErrorMessages.humanize(e) to FailureKind.OTHER
        }
    }
}

internal fun pickKeyProvider(pem: String, passphrase: String?): FileKeyProvider {
    // Bound input to defend against DoS / OOM on pathological PEM blobs — real keys are 1-4 KB.
    require(pem.length <= 65_536) { "PEM input too large: ${pem.length} chars (max 64KB)" }
    val provider: FileKeyProvider = if (pem.contains("BEGIN OPENSSH PRIVATE KEY")) {
        OpenSSHKeyV1KeyFile()
    } else {
        OpenSSHKeyFile()
    }
    if (passphrase.isNullOrEmpty()) {
        provider.init(pem, null)
    } else {
        provider.init(pem, null, PasswordUtils.createOneOff(passphrase.toCharArray()))
    }
    return provider
}

private class TofuHostKeyVerifier(private val expectedFingerprint: String?) : HostKeyVerifier {
    var seenFingerprint: String? = null
    var seenKeyType: String? = null
    var mismatch: Boolean = false

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val fp = SecurityUtils.getFingerprint(key)
        seenFingerprint = fp
        seenKeyType = key.algorithm
        return when {
            expectedFingerprint == null -> true        // TOFU: trust on first use, capture fp
            expectedFingerprint == fp -> true
            else -> { mismatch = true; false }
        }
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
}
