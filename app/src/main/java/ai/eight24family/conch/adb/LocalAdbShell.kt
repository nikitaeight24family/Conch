package ai.eight24family.conch.adb

import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Shell-level access to this phone, obtained by Conch itself.
 *
 * This is the whole of it. The privilege is the same — the `shell` uid, what
 * `adb shell` gives a desktop — reached by speaking ADB to the device over its
 * own loopback interface with a key it has been paired with. No second app to
 * install, keep updated, or re-grant.
 *
 * WHAT COSTS WHAT, so nobody has to guess:
 *  - The pairing code is typed ONCE, ever. The device keeps the public key.
 *  - Arming Wireless Debugging is once per BOOT, and Android requires an
 *    associated Wi-Fi network for it — that rule lives in the platform
 *    (`AdbDebuggingManager` clears the flag the moment Wi-Fi drops or is not
 *    associated), so no app on the device avoids it without root.
 *  - Between those, nothing: the session below is held open and reused, so
 *    commands cost one round trip on loopback and work with no network of any
 *    kind at all.
 */
object LocalAdbShell {

    private val lock = Mutex()

    @Volatile private var session: AdbLocal.Session? = null

    @Volatile private var identity: AdbKey? = null

    // ⛔ THERE IS NO STORED "PAIRED" FLAG, AND THERE MUST NOT BE ONE.
    //
    // One lived here. It was set when a pairing or a connection succeeded and
    // cleared when adbd refused our key — a remembered CLAIM, which is exactly
    // what cannot be kept true: the owner can revoke the pairing in Android's own
    // dialog at any moment, and while adbd is unreachable (on mobile data,
    // always) nothing can ask the phone whether it still trusts us. So the flag
    // went stale, the screen showed a ✓ it could not back up, and the only way to
    // correct it was to write the value by hand — which is not a fix for one
    // user, let alone everyone.
    //
    // The app now reads the world instead of remembering an opinion about it: a
    // connection either opens or it does not, and Android advertises a pairing
    // service exactly while its own pairing dialog is open. Both are observable
    // at the moment they matter, on any device, with nothing to migrate and
    // nothing to go stale.

    /**
     * Is a shell connection open RIGHT NOW?
     *
     * Cheap and non-suspending on purpose: the phone glyph asks this once per
     * row of a list, so it must not touch the network or take a lock. It answers
     * about the HELD SESSION only, which can be stale — anything that must be
     * right asks [check], which proves it.
     */
    fun hasLiveSession(): Boolean = session != null

    /**
     * Our identity, generated on first use and kept for good.
     *
     * ⚠ Regenerating it silently would be the worst possible failure: the device
     * still holds the OLD public key, so pairing would look done and every
     * connection would be refused. Hence load-then-generate, never the reverse.
     */
    fun identity(): AdbKey = identity ?: synchronized(this) {
        identity ?: loadOrCreate().also { identity = it }
    }

    private fun loadOrCreate(): AdbKey {
        val store = ServiceLocator.secretsStore
        val stored = SilentlyTry.logged("Conch-LocalAdb", "load adb identity") { store.loadAdbPrivateKey() }
        if (stored != null) {
            val recovered = SilentlyTry.logged("Conch-LocalAdb", "rebuild adb identity") {
                val pk = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(stored)) as RSAPrivateKey
                AdbKey.fromPrivateKey(pk)
            }
            if (recovered != null) return recovered
            // A stored key we cannot parse is worse than none: it would fail
            // every connection while looking like a paired device. Say so and
            // start over — the user pairs again, which is recoverable.
            android.util.Log.w("Conch-LocalAdb", "stored ADB identity is unreadable; generating a new one")
        }
        val fresh = AdbKey.generate()
        SilentlyTry.fired("Conch-LocalAdb", "persist adb identity") {
            store.saveAdbPrivateKey(fresh.keyPair.private.encoded)
        }
        return fresh
    }

    /**
     * Run one command at shell level, opening or reusing a connection.
     *
     * Returns null when there is no way in right now — Wireless Debugging not
     * armed, or this key not paired. Null is deliberately distinct from a
     * command that ran and failed: the caller has to tell the user two very
     * different things.
     */
    suspend fun exec(command: String, limit: Int = 4 * 1024 * 1024): AdbShellV2.Result? =
        withContext(Dispatchers.IO) {
            lock.withLock {
                // One retry, because the common failure is a session the device
                // dropped while we were idle, and reconnecting is cheap.
                repeat(2) { attempt ->
                    val live = session ?: openLocked() ?: return@withContext null
                    val result = try {
                        live.exec(command, limit)
                    } catch (e: AdbConnection.NeedsTls) {
                        // Not a transport problem and not worth a retry: the
                        // daemon is refusing our certificate. Remember it so the
                        // screens can name the one fix (see [needsPairing]).
                        unauthorized = true
                        android.util.Log.w("Conch-LocalAdb", "adbd refuses our key on this device - pairing needed")
                        null
                    } catch (t: Throwable) {
                        android.util.Log.w("Conch-LocalAdb", "exec over own adb - swallowed: ${t.javaClass.simpleName}: ${t.message}")
                        null
                    }
                    if (result != null) return@withContext result
                    android.util.Log.w("Conch-LocalAdb", "session died (attempt ${attempt + 1}); reconnecting")
                    closeLocked()
                    // A DEAD SESSION IS NOT AN UNREACHABLE DAEMON, so the
                    // 20s backoff - which exists to stop the 2s pollers from
                    // hammering a port with nothing behind it - must not veto
                    // the one reconnect this command is owed. Without this a
                    // stale cooldown, set by some poller seconds earlier, made
                    // exec return null without dialling at all, and callers read
                    // that as "this phone has no shell".
                    retryNow()
                }
                null
            }
        }

    /**
     * Can a command run on this phone right now?
     *
     * ⛔ IT NEVER PROBES A HELD SESSION, AND IT NEVER CLOSES ONE.
     *
     * An open loopback socket to adbd KEEPS WORKING after Android turns wireless
     * debugging off — the listener is only needed to make a NEW connection. That
     * is what let the owner work with his phone on mobile data all day, having
     * armed it once on Wi-Fi.
     *
     * A connection that would have survived the whole day died on the first
     * hiccup, permanently, and every screen then said "not connected".
     *
     * The only honest teardown is a command that actually FAILED — [exec] already
     * does that, once, and reconnects if it can. Nothing else may close a
     * session that the user is relying on.
     */
    suspend fun check(userInitiated: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (userInitiated) retryNow()
        lock.withLock {
            // A HELD session is trusted, never probed — that rule is above.
            if (session != null) return@withLock true
            val fresh = openLocked() ?: return@withLock false
            // ⛔ AN OPEN SOCKET IS NOT SHELL ACCESS, AND SAYING SO IS THE LIE
            // THAT COST A DAY. adbd completes the whole TLS handshake with a
            // client certificate it does not know, sends its banner, and only
            // refuses when a SERVICE is opened — so a connection that proves
            // nothing looked identical to a working one (measured on the owner's
            // phone, 2026-09-03: banner received, `shell,v2` answered with
            // A_STLS). Every caller of this then walked past the pairing screen
            // it exists to route them to, and the failure surfaced three layers
            // later as "the Linux is not reachable".
            //
            // Only a session we JUST opened is probed, and only with `echo` —
            // nothing that could disturb a connection the user relies on.
            val proof = SilentlyTry.logged("Conch-LocalAdb", "prove the new session") {
                fresh.exec("echo conch-shell-ok")
            }
            if (proof?.stdout?.contains("conch-shell-ok") == true) {
                unauthorized = false
                return@withLock true
            }
            android.util.Log.w(
                "Conch-LocalAdb",
                "adbd took the connection but not a command " +
                    "(${if (unauthorized) "our key is not authorized" else "unknown"}) — dropping it",
            )
            closeLocked()
            false
        }
    }

    /**
     * The device answered a service open with `STLS` — adbd's way of saying our
     * certificate is not one it has been paired with.
     *
     * ⛔ THIS IS OBSERVED, NOT INFERRED, which is what makes it safe to say out
     * loud. The old NOT_PAIRED state was guessed from a connection that would
     * not open, and that guess was wrong: an advertised-but-dead port refuses
     * the same way a revoked pairing does, so a paired phone got told it was
     * unpaired. This one comes from the daemon completing a TLS handshake and
     * then refusing the service in a specific, documented way. Nothing else
     * produces it.
     */
    @Volatile private var unauthorized = false

    /** True when this phone has stopped honouring our key — the one thing here
     *  that a pairing (and only a pairing) fixes. */
    fun needsPairing(): Boolean = unauthorized

    /** Kept for callers that read as a question rather than a check. Same proof
     *  either way — there is only one implementation. */
    suspend fun available(): Boolean = check()

    /**
     * Why commands cannot run — in the only distinction that can be OBSERVED.
     *
     * There used to be a third state, NOT_PAIRED, inferred from a connection
     * that would not open. That inference is unsound: mDNS keeps advertising a
     * port after adbd stops listening, so "wireless debugging is off" and "this
     * phone forgot our key" arrive as the same ECONNREFUSED. It told a paired
     * owner he was not set up, and a stored flag papered over it until the flag
     * itself went stale.
     *
     * What can be told apart is whether a connection opens. Everything else the
     * user needs to know is on one screen anyway, because both remedies live
     * behind the same Android switch.
     */
    enum class Readiness { READY, NOT_ARMED }

    suspend fun readiness(): Readiness =
        if (check()) Readiness.READY else Readiness.NOT_ARMED

    /** One sentence naming what is actually in the way, for the screens that
     *  have to tell someone. Only ever called when [check] came back false. */
    fun whyNoShell(): String = when {
        unauthorized ->
            "This phone has stopped honouring Conch's shell key. Pair it once in " +
                "Settings > Phone bridge - Android shows a six-digit code."
        // ⛔ NAME THE PLATFORM RULE, DO NOT IMPLY THE APP CHOSE THIS. Android
        // switches wireless debugging off with the Wi-Fi it is tied to, and
        // nothing on the device can switch it back on - not the app, not even
        // the shell uid (SELinux refuses `service.adb.tcp.port`). Saying "set it
        // up in Settings" to someone who set it up yesterday reads as a bug in
        // the app (owner, 2026-09-03).
        wifiIsOff() ->
            "Wi-Fi is off, and Android switches its wireless-debugging switch off with it - " +
                "no app can turn that back on. An already-running Linux is unaffected; " +
                "starting one again needs the switch once (Wi-Fi on, Settings > Phone " +
                "bridge), or one `adb tcpip 5555` from any computer, which then survives " +
                "Wi-Fi going away until the phone reboots."
        else ->
            "Conch can't reach this phone's own shell, which is what starts the Linux. " +
                "Set it up in Settings > Phone bridge; Android needs it armed once per " +
                "boot, and after that this machine keeps working even if Wi-Fi drops."
    }

    /** Wi-Fi association, the thing Android's wireless debugging is tied to. */
    private fun wifiIsOff(): Boolean = SilentlyTry.loggedOrElse(
        "Conch-LocalAdb", "read wifi state", false,
    ) {
        val wm = ServiceLocator.appContext
            .applicationContext.getSystemService(android.content.Context.WIFI_SERVICE)
            as? android.net.wifi.WifiManager
        wm != null && !wm.isWifiEnabled
    }

    /**
     * The port `adb tcpip` leaves adbd listening on.
     *
     * ⭐ THIS IS THE ONLY PATH THAT SURVIVES LEAVING WI-FI. Android's wireless
     * debugging is gated on a Wi-Fi association and is torn down the moment it
     * drops — measured. This listener is not: it runs until the phone reboots,
     * needs no network of any kind (we reach it on loopback), and authenticates
     * with the same key over the legacy handshake. It is never advertised over
     * mDNS, so it can only be found by asking.
     */
    private const val LEGACY_TCPIP_PORT = 5555

    /**
     * The port this phone's adbd last let us in on.
     *
     * ⭐ TRIED BEFORE mDNS, AND IT IS WHAT KEEPS THE SHELL ACROSS A RESTART.
     * mDNS is the only PUBLIC way to learn the port - and it is not dependable:
     * adbd stops advertising long before it stops listening. Measured on the
     * owner's phone, 2026-09-03: adbd answering every command on port 40377
     * while `adb mdns services` returned an empty list and `service.adb.tls.port`
     * was gone. Nobody noticed while a session was held, because a held session
     * needs no discovery; the moment the process restarted, the app could not
     * find a daemon that was RIGHT THERE, and every screen told the owner to go
     * and set up a phone bridge that was already set up.
     *
     * A port that worked once costs 700ms on loopback to try. A live adbd
     * answers in milliseconds; a stale port fails fast and we fall through to
     * mDNS exactly as before, so this can only add ways in, never remove one.
     */
    @Volatile private var portMemo: Int = 0

    private suspend fun rememberedAdbPort(): Int {
        portMemo.takeIf { it > 0 }?.let { return it }
        val stored = SilentlyTry.loggedOrElse("Conch-LocalAdb", "read remembered adb port", 0) {
            ServiceLocator.preferences.lastAdbPort.first()
        }
        portMemo = stored
        return stored
    }

    private suspend fun rememberAdbPort(port: Int) {
        if (port <= 0 || port == portMemo) return
        portMemo = port
        SilentlyTry.fired("Conch-LocalAdb", "remember adb port") {
            ServiceLocator.preferences.setLastAdbPort(port)
        }
    }

    private suspend fun openLocked(): AdbLocal.Session? {
        if (System.currentTimeMillis() < cooldownUntil) return null
        // Ask the Wi-Fi-independent listener FIRST. If it is up, nothing about
        // the network can take the shell away until the phone reboots.
        val legacy = SilentlyTry.logged("Conch-LocalAdb", "connect to adbd on the legacy port") {
            AdbLocal.connect(LEGACY_TCPIP_PORT, identity(), "127.0.0.1", connectTimeoutMs = 1_500)
        }
        if (legacy != null) {
            android.util.Log.i("Conch-LocalAdb", "connected to own adbd on 127.0.0.1:$LEGACY_TCPIP_PORT (legacy, wifi-independent)")
            session = legacy
            return legacy
        }
        // The port that worked last time, before we go looking - because
        // looking is the part that stops working (see [rememberedAdbPort]).
        val remembered = rememberedAdbPort()
        if (remembered > 0) {
            val reopened = SilentlyTry.logged("Conch-LocalAdb", "reconnect on the remembered adb port") {
                AdbLocal.connect(remembered, identity(), "127.0.0.1", connectTimeoutMs = 700)
            }
            if (reopened != null) {
                cooldownUntil = 0L
                android.util.Log.i(
                    "Conch-LocalAdb",
                    "connected to own adbd on 127.0.0.1:$remembered (remembered port) - ${reopened.deviceBanner}",
                )
                session = reopened
                return reopened
            }
            android.util.Log.i("Conch-LocalAdb", "remembered adb port $remembered no longer answers - asking mDNS")
        }
        val endpoint = AdbDiscovery.find(ServiceLocator.appContext) ?: run {
            android.util.Log.i("Conch-LocalAdb", "no adbd advertised — wireless debugging is probably off")
            return null
        }
        val local = AdbDiscovery.onLoopback(endpoint)
        val opened = try {
            // 1.5 s, not 5: this is the SAME DEVICE. A real adbd answers on
            // loopback in milliseconds, so a long timeout only makes a dead
            // endpoint expensive — and a dead endpoint is the common case,
            // because mDNS keeps advertising a port after adbd stops listening.
            AdbLocal.connect(local.port, identity(), local.host, connectTimeoutMs = 1_500)
        } catch (t: Throwable) {
            onOpenFailed(local.port, t)
            return null
        }
        cooldownUntil = 0L
        android.util.Log.i(
            "Conch-LocalAdb",
            "connected to own adbd on ${local.host}:${local.port} — ${opened.deviceBanner}",
        )
        // Learned the hard way, so learn it once: this port outlives the
        // advertisement that named it.
        rememberAdbPort(local.port)
        session = opened
        spendOnIndependence()
        return opened
    }

    /**
     * Why an open failed, and what we are allowed to conclude from it.
     *
     * ⛔ A FAILED CONNECTION IS NOT A REVOKED PAIRING. This used to clear
     * [everConnected] whenever an advertised endpoint would not open, on the
     * theory that "advertised but refuses us" can only mean the phone forgot our
     * key. It cannot: mDNS keeps advertising a port after adbd has stopped
     * listening on it, so the ordinary case — wireless debugging switched off,
     * or its port changed — arrives as ECONNREFUSED against a stale record. The
     * app then un-paired a perfectly paired phone every two seconds and sent the
     * owner back to "Start pairing" with a code he had already typed
     * (2026-08-29, measured: port 44263, ECONNREFUSED in a loop, the stored flag
     * flipping to false).
     *
     * So the flag is cleared ONLY when the socket got far enough to be REFUSED
     * BY THE PROTOCOL — a TLS or auth failure, which requires a live adbd on the
     * other end. Anything that fails at the transport is a phone we cannot reach,
     * not a phone that disowns us.
     */
    private fun onOpenFailed(port: Int, t: Throwable) {
        // Back off. The bridge screen asks every two seconds, and every ask was
        // paying a full connect timeout against a port with nothing behind it —
        // mDNS keeps advertising one after adbd has stopped listening.
        cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS
        android.util.Log.i(
            "Conch-LocalAdb",
            "adbd not reachable on $port (${t.javaClass.simpleName}) — backing off ${COOLDOWN_MS / 1000}s",
        )
    }

    /**
     * ⭐ THE FIRST SHELL OF A SESSION IS SPENT ON NOT NEEDING ONE.
     *
     * Android ties wireless debugging to a Wi-Fi association and switches it
     * OFF the moment Wi-Fi drops (measured: `adb_wifi_enabled` goes to 0, and
     * no app can set it back — the shell uid cannot even write
     * `service.adb.tcp.port`, SELinux refuses it). So shell access is a WINDOW,
     * not a state, and the owner leaving his Wi-Fi took the phone's whole Linux
     * with it (2026-09-03: "how does dropping Wi-Fi affect the Linux ON the
     * phone?" — it should not, and now it does not).
     *
     * The environment needs the shell only to START. Once its sshd is up it is
     * an ordinary process: no adb, no Wi-Fi, no discovery, alive until the phone
     * reboots. So the instant a shell exists — whatever opened it, whichever
     * screen — it is spent starting the machine, and Wi-Fi stops mattering for
     * the rest of the boot. Fire-and-forget on purpose: this must never delay or
     * fail the command the caller actually wanted, and the pool's own mutex
     * makes concurrent callers pay for one boot.
     */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun spendOnIndependence() {
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            SilentlyTry.fired("Conch-LocalAdb", "start the phone's Linux while a shell exists") {
                val d = ServiceLocator.sshConnectionPool.ensureOwnDeviceUp()
                if (d is ai.eight24family.conch.ssh.SshConnectionPool.Dialled.Up) {
                    android.util.Log.i(
                        "Conch-LocalAdb",
                        "shell window spent: the phone's Linux is up and no longer needs adb",
                    )
                }
            }
        }
    }

    /** How long to leave an unreachable adbd alone. */
    private const val COOLDOWN_MS = 20_000L

    @Volatile private var cooldownUntil = 0L

    /** Try again NOW, whatever the backoff says — for the moments the user just
     *  did the thing that fixes it (armed wireless debugging, finished pairing). */
    fun retryNow() {
        cooldownUntil = 0L
    }

    private fun closeLocked() {
        SilentlyTry.fired("Conch-LocalAdb", "close own adb session") { session?.close() }
        session = null
    }

    /** Drop the connection (a reboot, a revoked pairing, or the user's request). */
    fun close() {
        synchronized(this) { closeLocked() }
    }
}
