package ai.eight24family.conch.ssh

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.domain.AuthMethod
import ai.eight24family.conch.domain.Server
import ai.eight24family.conch.domain.ServerSecrets
import kotlinx.coroutines.runBlocking
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.StringWriter
import java.net.ServerSocket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.UUID

/**
 * End-to-end tests that drive the real `SshClient` against an in-process
 * Apache MINA SSHD server. Exercises:
 *   - TOFU host-key capture on first connect
 *   - host-key match / mismatch logic
 *   - password auth success + failure paths
 *   - public-key auth success
 *   - exec command stdout / stderr / non-zero exit composition
 *
 * The test SSH server runs on a random localhost port with a freshly
 * generated host key per test class. No outbound network — completely
 * hermetic.
 */
class SshClientIntegrationTest {

    private lateinit var sshd: SshServer
    private val client = SshClient()
    private var port: Int = -1

    private val hostKeyFile: File by lazy {
        File.createTempFile("sshai-test-hostkey", ".ser").apply { deleteOnExit() }
    }
    /**
     * What the running SSHD will accept:
     *   user "alice" with password "secret"
     *   user "bob"   with password "letmein"
     */
    private val passwords = mapOf("alice" to "secret", "bob" to "letmein")

    /**
     * RSA key pair generated once per test class. We write the private
     * half as a PKCS#1 PEM (`-----BEGIN RSA PRIVATE KEY-----`) so it goes
     * through sshj's `OpenSSHKeyFile` path — exactly what production
     * does for any non-OpenSSH-v1-encoded user key.
     */
    private lateinit var testKeyPair: KeyPair
    private lateinit var testKeyPem: String

    @Before
    fun setUp() {
        // Generate a fresh RSA pair and serialise the private half as
        // PKCS#1 PEM via BouncyCastle. JcaPEMWriter handles the dispatch
        // by Object type — a KeyPair lands as "RSA PRIVATE KEY".
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        testKeyPair = kpg.generateKeyPair()
        val sw = StringWriter()
        JcaPEMWriter(sw).use { it.writeObject(testKeyPair) }
        testKeyPem = sw.toString()

        port = pickFreePort()
        sshd = SshServer.setUpDefaultServer().apply {
            this.port = this@SshClientIntegrationTest.port
            host = "127.0.0.1"
            keyPairProvider = SimpleGeneratorHostKeyProvider(hostKeyFile.toPath())
            passwordAuthenticator = PasswordAuthenticator { user, pwd, _ ->
                passwords[user] == pwd
            }
            publickeyAuthenticator = PublickeyAuthenticator { user, _, _ ->
                // Any presented key is accepted for user "carol" — sufficient
                // for the auth-path coverage we want; identity is enforced by
                // the client demonstrating possession of the matching private
                // key during the auth handshake itself.
                user == "carol"
            }
            commandFactory = TestCommandFactory()
            start()
        }
    }

    @After
    fun tearDown() {
        sshd.stop(true)
        hostKeyFile.delete()
    }

    @Test
    fun `first connect returns UnknownHost with captured fingerprint`() = runBlocking {
        val server = newServer(user = "alice", knownHostKey = null, auth = AuthMethod.PASSWORD)
        val secrets = ServerSecrets(password = "secret")

        val r = client.testConnection(server, secrets)
        assertTrue("expected UnknownHost, got $r", r is ConnectResult.UnknownHost)
        val fp = (r as ConnectResult.UnknownHost).fingerprint
        assertTrue("fingerprint should look like SHA256:base64 — was '$fp'",
            fp.startsWith("SHA256:") || fp.contains(":"))
    }

    @Test
    fun `connect with already-known fingerprint returns Success`() = runBlocking {
        val s1 = newServer(user = "alice", knownHostKey = null, auth = AuthMethod.PASSWORD)
        val first = client.testConnection(s1, ServerSecrets(password = "secret"))
        val captured = (first as ConnectResult.UnknownHost).fingerprint

        val s2 = s1.copy(knownHostKey = captured)
        val r = client.testConnection(s2, ServerSecrets(password = "secret"))
        assertTrue("expected Success, got $r", r is ConnectResult.Success)
        assertEquals(captured, (r as ConnectResult.Success).fingerprint)
    }

    @Test
    fun `connect with mismatched fingerprint returns HostKeyMismatch`() = runBlocking {
        val server = newServer(
            user = "alice",
            knownHostKey = "SHA256:thisisatotallybogusfingerprint=",
            auth = AuthMethod.PASSWORD
        )
        val r = client.testConnection(server, ServerSecrets(password = "secret"))
        assertTrue("expected HostKeyMismatch, got $r", r is ConnectResult.HostKeyMismatch)
        val mm = r as ConnectResult.HostKeyMismatch
        assertEquals("SHA256:thisisatotallybogusfingerprint=", mm.expected)
        assertNotNull(mm.actual)
    }

    @Test
    fun `bad password returns Failure with AUTH_PASSWORD_REJECTED`() = runBlocking {
        val server = newServer(user = "alice", knownHostKey = null, auth = AuthMethod.PASSWORD)
        val r = client.testConnection(server, ServerSecrets(password = "wrong"))
        assertTrue("expected Failure, got $r", r is ConnectResult.Failure)
        assertEquals(FailureKind.AUTH_PASSWORD_REJECTED, (r as ConnectResult.Failure).kind)
    }

    @Test
    fun `key auth succeeds for authorized user`() = runBlocking {
        val server = newServer(user = "carol", knownHostKey = null, auth = AuthMethod.KEY)
        val secrets = ServerSecrets(privateKeyPem = testKeyPem, keyPassphrase = null)
        val r = client.testConnection(server, secrets)
        assertTrue("expected UnknownHost (TOFU first hit), got $r", r is ConnectResult.UnknownHost)
    }

    @Test
    fun `execute returns stdout for echo command`() = runBlocking {
        val server = newServer(user = "alice", knownHostKey = null, auth = AuthMethod.PASSWORD)
        // First call captures fingerprint; we don't care about the value here.
        val first = client.testConnection(server, ServerSecrets(password = "secret"))
        val knownFp = (first as ConnectResult.UnknownHost).fingerprint
        val s2 = server.copy(knownHostKey = knownFp)

        val r = client.execute(s2, ServerSecrets(password = "secret"), "echo hello-world").getOrNull()
        assertNotNull("execute should succeed", r)
        assertTrue("output should contain echoed text — was: '$r'", r!!.contains("hello-world"))
    }

    @Test
    fun `execute captures stderr and exit code`() = runBlocking {
        val server = newServer(user = "alice", knownHostKey = null, auth = AuthMethod.PASSWORD)
        val first = client.testConnection(server, ServerSecrets(password = "secret"))
        val knownFp = (first as ConnectResult.UnknownHost).fingerprint
        val s2 = server.copy(knownHostKey = knownFp)

        val r = client.execute(s2, ServerSecrets(password = "secret"), "fail with stderr").getOrNull()
        assertNotNull(r)
        assertTrue("stderr block must be present — was: '$r'", r!!.contains("[stderr]"))
        assertTrue("exit-code marker must reflect non-zero exit — was: '$r'",
            r.contains("[exit 7]"))
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private fun newServer(user: String, knownHostKey: String?, auth: AuthMethod): Server =
        Server(
            id = UUID.randomUUID().toString(),
            name = "in-proc",
            host = "127.0.0.1",
            port = port,
            username = user,
            authMethod = auth,
            knownHostKey = knownHostKey,
            agent = Agent.CLAUDE,
            sshKeyIds = emptyList(),
        )

    private fun pickFreePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }
}

/**
 * Minimal command factory: returns canned output based on the requested
 * command string. Two recognised forms:
 *   - "echo <text>"          → prints `<text>` then exits 0
 *   - "fail with stderr"     → prints "stderr line" to stderr, exits 7
 *   - anything else          → prints "ok\n", exits 0
 */
private class TestCommandFactory : CommandFactory {
    override fun createCommand(channel: ChannelSession?, command: String?): Command {
        val cmd = command.orEmpty()
        return when {
            cmd.startsWith("echo ") -> {
                val text = cmd.removePrefix("echo ").trim()
                CannedCommand(stdout = "$text\n", exitCode = 0)
            }
            cmd == "fail with stderr" -> CannedCommand(stderr = "stderr line\n", exitCode = 7)
            else -> CannedCommand(stdout = "ok\n", exitCode = 0)
        }
    }
}

private class CannedCommand(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = 0,
) : Command {
    private var inStream: InputStream? = null
    private var outStream: OutputStream? = null
    private var errStream: OutputStream? = null
    private var callback: org.apache.sshd.server.ExitCallback? = null

    override fun setInputStream(`in`: InputStream?) { inStream = `in` }
    override fun setOutputStream(out: OutputStream?) { outStream = out }
    override fun setErrorStream(err: OutputStream?) { errStream = err }
    override fun setExitCallback(callback: org.apache.sshd.server.ExitCallback?) {
        this.callback = callback
    }

    override fun start(channel: ChannelSession?, env: org.apache.sshd.server.Environment?) {
        try {
            outStream?.write(stdout.toByteArray(Charsets.UTF_8))
            outStream?.flush()
            errStream?.write(stderr.toByteArray(Charsets.UTF_8))
            errStream?.flush()
        } finally {
            callback?.onExit(exitCode)
        }
    }

    override fun destroy(channel: ChannelSession?) { /* no-op */ }
}
