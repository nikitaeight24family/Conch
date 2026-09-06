package ai.eight24family.conch.linux

import ai.eight24family.conch.domain.AuthMethod
import ai.eight24family.conch.domain.Server
import ai.eight24family.conch.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The phone's Linux, reachable the way every other machine in this app is.
 *
 * ⛔ THIS EXISTS SO THAT NOTHING ELSE HAD TO BE WRITTEN TWICE.
 *
 * Installing the CLIs into the environment was the easy half; DRIVING one is
 * the half the app already solved, in ten thousand lines that know how to build
 * each CLI's command, stream its JSONL, resume a session, run its OAuth login,
 * fetch its models, edit its memory file and cancel a turn — all of it against
 * an SSH transport. Re-implementing that over an ADB pipe would mean a second
 * copy of the hardest code in the codebase, permanently behind the first.
 *
 * So the environment grows an SSH endpoint on loopback instead, and the phone
 * becomes an ordinary [Server] row: chat, login, sessions, terminal, file
 * transfer and the agent picker all work on it with NO new code at all.
 *
 * ── WHY OPENSSH AND NOT DROPBEAR ──
 *
 * Dropbear was here first, and it worked — right up to the moment the app said
 * anything long. Measured on the owner's phone, 2026-08-31:
 *
 *     Pubkey auth succeeded for 'root' with ssh-ed25519 …
 *     Exit (root) from <127.0.0.1:45872>: String too long
 *
 * ⛔ DROPBEAR CAPS THE LENGTH OF AN EXEC COMMAND (~1.4 KB), AND THIS APP IS
 * BUILT ON LONG ONES. The agent status probe alone is assembled from ten CLI
 * specs and runs to several kilobytes; the install bootstrap is longer still.
 * Every one of them authenticated and was then hung up on, which the picker
 * could only report as "server isn't responding". No amount of app-side work
 * fixes that — the limit is compiled into the daemon.
 *
 * OpenSSH has no such cap. The one thing that made it look impossible earlier
 * was a TEST HARNESS artifact: in a user namespace `sshd` dies dropping
 * privileges (`setgroups: Operation not permitted`). Under PRoot it does not —
 * `fake_id0` intercepts `setuid`/`setgid`/`setgroups` and answers 0 without
 * reaching the kernel, which is the same mechanism that already lets `apk`
 * believe it is root.
 *
 * ── WHAT IT IS NOT ──
 *
 * It is bound to `127.0.0.1` and nothing else, so it is not on the network:
 * loopback on Android is reachable only by processes on the device. It is also
 * not a login you can use — password authentication is off, and the only key
 * that opens it is the one this app generated for itself.
 */
object LinuxSsh {

    private const val TAG = "Conch-LinuxSsh"

    /** Fixed so the row is found again, never duplicated, and can be filtered
     *  out of the servers list — where "this phone" is already a row. */
    const val SERVER_ID = "conch-this-phone"

    const val HOST = "127.0.0.1"
    const val PORT = 8022
    const val USER = "root"

    /** The name the chat, the sessions list and the notifications will show.
     * Device-neutral by default — Conch runs on tablets and Android desktops
     * too — and the owner can rename this row. */
    const val NAME = "this device"

    private const val SCRIPT = "sshd"

    enum class Phase { OFF, STARTING, UP, FAILED }

    /**
     * The phone could not be made reachable, and [message] says why in words the
     * owner can act on.
     *
     * ⛔ IT CARRIES A SENTENCE BECAUSE THE SCREENS PRINT ONE. Without it the row
     * said "Failed: IllegalStateException" — a class name, about a machine in
     * the owner's hand, with nothing to do about it (2026-08-31). Every screen
     * that shows a connection error already runs [ErrorMessages.humanize], so
     * one type here is honest everywhere at once.
     */
    class NotReachable(message: String) : IllegalStateException(message)

    /** [detail] carries the daemon's own last words when it did not come up —
     *  the page prints it verbatim rather than inventing a diagnosis. */
    data class State(val phase: Phase = Phase.OFF, val detail: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val lock = Mutex()

    /**
     * Is something answering on the port RIGHT NOW?
     *
     * ⛔ ASKED BY OPENING A SOCKET, never by remembering that we started it. The
     * daemon lives outside our process and outlives it; equally, the phone can
     * reboot and take it away while a stored flag would still say "up". One
     * loopback connect settles it in a millisecond, so there is no reason to
     * keep an opinion instead.
     */
    suspend fun isListening(): Boolean = withContext(Dispatchers.IO) {
        banner() != null
    }

    /**
     * What answers on the port, by its own greeting — `SSH-2.0-OpenSSH_9.9` and
     * the like. Null when nothing answers.
     *
     * ⛔ "SOMETHING IS LISTENING" IS NOT "OUR DAEMON IS LISTENING", and the
     * difference cost a day: a leftover dropbear from an older build answers the
     * socket perfectly and then hangs up on every command over ~1.4 KB, which is
     * most of what this app sends. An SSH server states what it is in the first
     * bytes it writes, before anything is asked of it — so this costs one
     * connect and needs neither the phone shell nor a key.
     */
    private suspend fun banner(): String? = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(HOST, PORT), 400)
                sock.soTimeout = 800
                val buf = ByteArray(255)
                val n = sock.getInputStream().read(buf)
                if (n <= 0) null else String(buf, 0, n, Charsets.US_ASCII).trim()
            }
        }.getOrNull()
    }

    /** Ours = OpenSSH. Anything else on that port is a leftover to replace. */
    private suspend fun oursIsUp(): Boolean =
        banner()?.contains("OpenSSH", ignoreCase = true) == true

    /**
     * Bring the endpoint up if it is not already, and make sure the row that
     * points at it exists. Idempotent; safe to call on every visit to the page.
     *
     * @return the server row when the environment is reachable, else null.
     */
    suspend fun ensureUp(): Server? = lock.withLock {
        // ⛔ THE PORT IS ASKED FIRST, AND THAT IS THE WHOLE POINT OF THE ORDER.
        //
        // A daemon that is already running needs NOTHING from the phone shell:
        // no ADB session, no wireless debugging, no Wi-Fi. It is a process that
        // lives until the phone reboots. The previous order asked the shell
        // first — presence, then the key file — so a bridge that had lapsed took
        // a perfectly working machine down with it, and the row started
        // demanding a debugging toggle to talk to a port that was answering all
        // along.
        //
        // Arming the shell is needed to START the environment. It is not needed
        // to USE it.
        if (oursIsUp()) {
            val row = ensureRow()
            if (row != null) {
                _state.value = State(Phase.UP)
                return@withLock pinnedToEnvironmentKey(row)
            }
        }
        // Nothing of ours is answering, so it has to be started — and only that
        // needs the shell.
        //
        // ⛔ CLEAR THE BACKOFF FIRST. Everything that reaches this line is a
        // person's own action (a tap on the row, a message sent into its chat),
        // and LocalAdbShell backs off for 20s after any failed open — a guard
        // meant for the two-second pollers. Without this, one poller's miss made
        // the owner's next tap fail without so much as trying, and the row then
        // told him to go and set up a phone bridge he had already set up.
        ai.eight24family.conch.adb.LocalAdbShell.retryNow()
        when (LinuxEnv.presence()) {
            LinuxEnv.Presence.INSTALLED -> Unit
            LinuxEnv.Presence.ABSENT -> {
                _state.value = State(Phase.OFF, "no Linux environment on this phone yet")
                return@withLock null
            }
            // ⛔ NOT "absent". An install that is merely out of reach is still an
            // install, and saying otherwise would send the owner back to the
            // install button for something already on his phone.
            //
            // ⛔ AND THE SENTENCE NAMES NO `adb` COMMAND. This app exists for
            // someone with no computer; telling him to run one from a machine he
            // does not have is not an instruction, it is a shrug. Android hands
            // shell access to an app exactly one way without root — the pairing
            // Conch already implements — so that is what it points at, in the
            // app's own words, on the app's own screen.
            LinuxEnv.Presence.UNREACHABLE -> {
                // ⛔ NAME THE REAL OBSTACLE. "Arm wireless debugging" is the right
                // advice for exactly one of the two ways in here, and it was
                // given for both — so an owner whose phone had simply stopped
                // honouring the app's key was sent to a switch that was already
                // on (2026-09-03). The shell layer knows which it is, because
                // adbd says so.
                _state.value = State(Phase.OFF, ai.eight24family.conch.adb.LocalAdbShell.whyNoShell())
                return@withLock null
            }
        }
        val row = ensureRow() ?: run {
            _state.value = State(Phase.FAILED, "could not create the key for it")
            return@withLock null
        }
        val pub = ServiceLocator.sshKeyRepository.getById(row.sshKeyIds.first())?.publicKey
        if (pub.isNullOrBlank()) {
            _state.value = State(Phase.FAILED, "the phone's key is missing from the keychain")
            return@withLock null
        }
        // Written from OUTSIDE the environment: the rootfs is a plain directory
        // to the phone shell, so this needs neither proot nor a running daemon.
        LinuxEnv.writeFile("/root/.ssh/authorized_keys", pub.trim() + "\n", mode = "600")
        // A leftover from the build that used dropbear holds the port and
        // truncates everything; [oursIsUp] already told us it is not ours.
        LinuxEnv.run("pkill dropbear")
        _state.value = State(Phase.STARTING)
        if (!LinuxEnv.launchScript(SCRIPT, daemonScript())) {
            _state.value = State(Phase.FAILED, ai.eight24family.conch.adb.PhoneBridgeCopy.SHELL_OFF)
            return@withLock null
        }
        // First run installs the daemon (a few seconds of apk over proot); later
        // runs are instant. Poll the port rather than the log — the port is the
        // thing that has to be true.
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(700)
            if (oursIsUp()) {
                _state.value = State(Phase.UP)
                android.util.Log.i(TAG, "ssh endpoint up on $HOST:$PORT")
                return@withLock pinnedToEnvironmentKey(row)
            }
        }
        val tail = LinuxEnv.scriptLog(SCRIPT)
            ?.lineSequence()?.map { it.trim() }?.lastOrNull { it.isNotEmpty() }
        _state.value = State(Phase.FAILED, tail ?: "the ssh daemon did not come up")
        android.util.Log.w(TAG, "ssh endpoint did not come up: $tail")
        null
    }

    /** Stop it — the environment stays, the endpoint goes. */
    suspend fun stop() {
        LinuxEnv.run("pkill -f sshd_conch.conf; pkill dropbear")
        _state.value = State(Phase.OFF)
    }

    /** The environment is going away: stop the endpoint and take the row with
     *  it, so the list never offers a machine that no longer exists. */
    suspend fun forget() {
        stop()
        ai.eight24family.conch.util.SilentlyTry.fired(TAG, "delete the phone's row") {
            ServiceLocator.serverRepository.delete(SERVER_ID)
        }
    }

    /**
     * The row — an ORDINARY server row, on the ordinary list.
     *
     * ⛔ NOT HIDDEN, AND NOT A SPECIAL CASE. The first version kept it out of
     * the servers list and gave the phone its own page with its own agent list,
     * its own install buttons and its own "open chat". That is a second copy of
     * the app's hardest screens, and the owner threw it out on sight:
     * (2026-08-31). Installing the environment creates this row; from there
     * every existing screen does the work.
     */
    suspend fun ensureRow(): Server? {
        val repo = ServiceLocator.serverRepository
        val existing = repo.getById(SERVER_ID)
        if (existing != null && existing.sshKeyIds.isNotEmpty()) return existing
        val keyId = existing?.sshKeyIds?.firstOrNull()
            ?: ServiceLocator.sshKeyRepository
                .generateEd25519(name = NAME, comment = "conch@this-phone")
                .id
        return repo.save(
            Server(
                id = SERVER_ID,
                name = NAME,
                host = HOST,
                port = PORT,
                username = USER,
                authMethod = AuthMethod.KEY,
                // Trust on first use, like any other new host. Nothing is
                // pinned yet because nothing has answered yet.
                knownHostKey = existing?.knownHostKey,
                sshKeyIds = listOf(keyId),
                colorHex = existing?.colorHex,
            ),
            password = null,
        )
    }

    /** The daemon's identity: the one host key [daemonScript] lets sshd serve. */
    private const val HOST_KEY_PUB = "/etc/ssh/ssh_host_ed25519_key.pub"

    /**
     * Make the row's pinned fingerprint FOLLOW the environment's own host key.
     *
     * For every other server a changed host key is a question only the owner
     * can answer, so the pool refuses and the row offers "forget". For THIS
     * row the app IS the machine's owner: it lays down the rootfs, writes the
     * daemon's config and starts it — so when the key legitimately changes
     * (the dropbear → OpenSSH swap did it on 2026-08-31; a reinstalled rootfs
     * does it every time), the app already knows, and greeting the owner with
     * a man-in-the-middle warning about his own phone — with the forget
     * ritual as the toll — is the app blaming him for what it did itself.
     *
     * ⛔ THE TRUTH IS READ FROM THE ENVIRONMENT'S DISK, NEVER FROM THE PORT.
     * Loopback ports are first-come: a foreign process squatting
     * 127.0.0.1:8022 answers the socket, but it cannot put its key into the
     * rootfs — so its handshake still hits the mismatch refusal. The security
     * property pinning exists for survives the convenience.
     *
     * Returns the row the caller must DIAL WITH: updating only the DB would
     * leave the in-flight snapshot carrying the stale pin for one more
     * refusal.
     */
    private suspend fun pinnedToEnvironmentKey(row: Server): Server {
        val fp = LinuxEnv.readFile(HOST_KEY_PUB)?.let(::opensshPubMd5Fingerprint)
            ?: return row // no readable key — leave TOFU / mismatch in charge
        if (fp == row.knownHostKey) return row
        android.util.Log.i(TAG, "pin follows the environment's host key: ${row.knownHostKey} → $fp")
        ai.eight24family.conch.util.SilentlyTry.fired(TAG, "re-pin the phone's host key") {
            ServiceLocator.serverRepository.updateKnownHostKey(row.id, fp)
        }
        return row.copy(knownHostKey = fp)
    }

    /**
     * The pool's fingerprint format, computed from an OpenSSH `.pub` line:
     * MD5 over the base64 key blob, hex pairs colon-joined. Byte-identical
     * input to what sshj hashes at the handshake
     * (`SecurityUtils.getFingerprint`), because the wire host key IS this
     * blob.
     */
    internal fun opensshPubMd5Fingerprint(pub: String): String? = runCatching {
        val b64 = pub.trim().split(Regex("\\s+")).getOrNull(1) ?: return@runCatching null
        val blob = java.util.Base64.getDecoder().decode(b64)
        java.security.MessageDigest.getInstance("MD5").digest(blob)
            .joinToString(":") { "%02x".format(it) }
    }.getOrNull()

    /**
     * The daemon, as a script that never returns.
     *
     *  • `-D` keeps it in the foreground ON PURPOSE. PRoot drives its tracees
     *    with ptrace and cannot detach from them, so a daemon that forks itself
     *    into the background and lets its parent exit takes proot's session down
     *    with it. Staying in the foreground of a detached proot is what makes it
     *    survive — the whole tree is one background process on the phone.
     *  • `ListenAddress 127.0.0.1` — loopback only. Never a wildcard bind: on a
     *    phone that would be a listening port on every Wi-Fi network it joins.
     *  • ONE `HostKey`, the ed25519 one — the same file
     *    [pinnedToEnvironmentKey] reads. `ssh-keygen -A` lays down a key of
     *    every type, and with all of them loaded, WHICH one the client sees is
     *    the client library's preference-order trivia; a pin must not depend
     *    on that.
     *  • Password authentication off, in both of its forms. The only key that
     *    opens this is the one the app generated for itself.
     *  • Its own config file, never `/etc/ssh/sshd_config`: the environment is
     *    the owner's, and Conch does not get to redefine his sshd.
     */
    internal fun daemonScript(): String = """
        set -u
        export PATH="/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        export HOME=/root TMPDIR=/tmp
        if [ ! -x /usr/sbin/sshd ]; then
          echo 'installing the ssh daemon'
          apk add --no-cache openssh-server openssh-sftp-server 2>&1 | tail -3
        fi
        mkdir -p /var/empty /run /root/.ssh
        chmod 700 /root/.ssh
        [ -f /root/.ssh/authorized_keys ] && chmod 600 /root/.ssh/authorized_keys
        [ -f /etc/ssh/ssh_host_ed25519_key ] || ssh-keygen -A >/dev/null 2>&1
        cat > /etc/ssh/sshd_conch.conf <<'CONCH_SSHD'
        Port $PORT
        ListenAddress $HOST
        HostKey /etc/ssh/ssh_host_ed25519_key
        PermitRootLogin yes
        PubkeyAuthentication yes
        PasswordAuthentication no
        KbdInteractiveAuthentication no
        PidFile /run/sshd-conch.pid
        Subsystem sftp /usr/lib/ssh/sftp-server
        CONCH_SSHD
        echo 'starting the ssh endpoint on $HOST:$PORT'
        exec /usr/sbin/sshd -D -e -f /etc/ssh/sshd_conch.conf
    """.trimIndent()
}
