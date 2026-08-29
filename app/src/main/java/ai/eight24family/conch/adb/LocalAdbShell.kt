package ai.eight24family.conch.adb

import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.Dispatchers
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
        val stored = SilentlyTry.logged("SshAi-LocalAdb", "load adb identity") { store.loadAdbPrivateKey() }
        if (stored != null) {
            val recovered = SilentlyTry.logged("SshAi-LocalAdb", "rebuild adb identity") {
                val pk = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(stored)) as RSAPrivateKey
                AdbKey.fromPrivateKey(pk)
            }
            if (recovered != null) return recovered
            // A stored key we cannot parse is worse than none: it would fail
            // every connection while looking like a paired device. Say so and
            // start over — the user pairs again, which is recoverable.
            android.util.Log.w("SshAi-LocalAdb", "stored ADB identity is unreadable; generating a new one")
        }
        val fresh = AdbKey.generate()
        SilentlyTry.fired("SshAi-LocalAdb", "persist adb identity") {
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
                    val result = SilentlyTry.logged("SshAi-LocalAdb", "exec over own adb") {
                        live.exec(command, limit)
                    }
                    if (result != null) return@withContext result
                    android.util.Log.w("SshAi-LocalAdb", "session died (attempt ${attempt + 1}); reconnecting")
                    closeLocked()
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
        lock.withLock { session != null || openLocked() != null }
    }

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

    private suspend fun openLocked(): AdbLocal.Session? {
        if (System.currentTimeMillis() < cooldownUntil) return null
        // Ask the Wi-Fi-independent listener FIRST. If it is up, nothing about
        // the network can take the shell away until the phone reboots.
        val legacy = SilentlyTry.logged("SshAi-LocalAdb", "connect to adbd on the legacy port") {
            AdbLocal.connect(LEGACY_TCPIP_PORT, identity(), "127.0.0.1", connectTimeoutMs = 1_500)
        }
        if (legacy != null) {
            android.util.Log.i("SshAi-LocalAdb", "connected to own adbd on 127.0.0.1:$LEGACY_TCPIP_PORT (legacy, wifi-independent)")
            session = legacy
            return legacy
        }
        val endpoint = AdbDiscovery.find(ServiceLocator.appContext) ?: run {
            android.util.Log.i("SshAi-LocalAdb", "no adbd advertised — wireless debugging is probably off")
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
            "SshAi-LocalAdb",
            "connected to own adbd on ${local.host}:${local.port} — ${opened.deviceBanner}",
        )
        session = opened
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
            "SshAi-LocalAdb",
            "adbd not reachable on $port (${t.javaClass.simpleName}) — backing off ${COOLDOWN_MS / 1000}s",
        )
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
        SilentlyTry.fired("SshAi-LocalAdb", "close own adb session") { session?.close() }
        session = null
    }

    /** Drop the connection (a reboot, a revoked pairing, or the user's request). */
    fun close() {
        synchronized(this) { closeLocked() }
    }
}
