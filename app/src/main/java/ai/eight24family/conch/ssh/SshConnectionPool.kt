package ai.eight24family.conch.ssh

import ai.eight24family.conch.domain.AuthMethod
import ai.eight24family.conch.domain.Server
import ai.eight24family.conch.domain.ServerSecrets
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Reference-counted, per-(server) cache of authenticated [SSHClient]s.
 *
 * **Why this exists** — every [AgentSession] used to open its own
 * [SSHClient]. With FIDO security keys, that meant one NFC touch
 * **per chat opened**: refresh sessions list = touch, open chat A =
 * touch, switch agent and open chat B = ANOTHER touch. Sequential
 * touches blow the user's thumb off the YubiKey, and on tap-and-lift
 * NFC the tag is invalidated before sshj's userauth can complete the
 * signature, surfacing as "Exhausted available authentication
 * methods" / "Tag is out of date".
 *
 * Pooling solves it by trading "one client per chat" for "one client
 * per server, shared across all live chats and discovery probes":
 *
 * - First acquire on a fresh server → connect + auth (one touch),
 *   refcount = 1.
 * - Second acquire on the same server → return the live client,
 *   bump refcount. **No touch.** Sshj happily multiplexes multiple
 *   parallel `Session` channels on a single connection, so each
 *   AgentSession gets its own CLI subprocess channel.
 * - On [release], decrement; the last release disconnects.
 *
 * ## Lifetime + safety
 *
 * - The pool owns the client. Callers must NOT call `client.disconnect()`
 *   themselves — they must [release] instead.
 * - If `client.isConnected` returns false on a subsequent acquire (network
 *   blip, server reboot), the pool drops the dead entry and re-acquires
 *   fresh — which on SK servers requires a fresh signer (one new touch).
 *   That re-touch is unavoidable: the cryptographic auth challenge must be
 *   answered fresh by the token.
 *
 * The pool itself is process-singleton ([instance]); the live ServiceLocator
 * exposes it as `sshConnectionPool`.
 */
class SshConnectionPool {

    /** Per-server live entry, alive iff `client.isConnected`. The
     *
     *  [openedAtMs] is the transport's birthday, and it is what stops
     *  [evictPoisoned] from killing a transport that CANNOT be the poisoned
     *  one because it did not exist yet when the turn failed. See the
     *  livelock note on [evictPoisoned]. */
    private data class Entry(
        val client: SSHClient,
        val openedAtMs: Long = System.currentTimeMillis(),
    )

    /** Holds entries by server id. ConcurrentHashMap so [peek] and
     *  [aliveCount] are lock-free reads — those run from the UI
     *  thread (server-list dot indicator polls every 3 s) and a
     *  global lock around them would serialise with the slow
     *  connect+auth path inside [acquire] and cause ANRs. */
    private val pool = ConcurrentHashMap<String, Entry>()

    /**
     * Outstanding [acquire] calls per server: references nobody has released
     * yet.
     *
     * WARNING: it lives OUTSIDE [Entry] on purpose. It used to be a field ON
     * the entry, and the entry is REPLACED on a dead-client rebuild and
     * REMOVED by [evictPoisoned] - both of which threw the count away while
     * every holder was still holding. The rebuilt entry started at 1 with
     * three chats on it, so the FIRST release took it to zero and
     * disconnected a transport the other two were using; the chat that lost
     * it then failed "disconnected" under a connection dot that was still
     * lit. A holder owns its reference for as long as it holds it, whatever
     * happens to the socket underneath, so the count has to outlive the
     * socket.
     *
     * Mutated only under the per-server lock, like everything else here.
     */
    private val outstanding = ConcurrentHashMap<String, Int>()

    /** Bump the outstanding-acquire count for [serverId] and return it.
     *  Caller holds the per-server lock. */
    private fun bumpOutstanding(serverId: String): Int {
        val n = (outstanding[serverId] ?: 0) + 1
        outstanding[serverId] = n
        return n
    }

    /**
     * Per-server lock object used to serialise concurrent [acquire]
     * calls for the SAME server (we want exactly one connect+auth at
     * a time per host). Different servers get different lock objects
     * so a slow handshake on host A never holds up acquire/peek/etc
     * for host B. Lock objects are interned via `computeIfAbsent` so
     * every caller sees the same Object for a given server id.
     */
    private val perServerLock = ConcurrentHashMap<String, Any>()

    /**
     * Get an authenticated client for [server], reusing an existing one
     * if any other AgentSession on the same server is already holding
     * a connection. Caller MUST balance every acquire with one [release].
     *
     * The [skSigner] is consumed only when we open a fresh connection.
     * For pooled hits (refcount > 0) it's ignored — the signer is dead
     * by then anyway (NFC tag has been lifted) and we don't need to
     * re-auth on a connection that's already authenticated.
     *
     * **Threading:** the slow path (connect + TLS + key exchange + auth)
     * runs INSIDE this method while holding the per-server lock. Callers
     * therefore must invoke [acquire] from a non-Main dispatcher (sshj's
     * connect is blocking I/O). The blocking is per-host: acquire on a
     * different server, or [peek] / [aliveCount] from any thread, never
     * waits on the slow path.
     */
    fun acquire(
        server: Server,
        secrets: ServerSecrets,
        skSigner: ai.eight24family.conch.ssh.securitykey.SkSigner? = null,
    ): SSHClient {
        val lock = perServerLock.computeIfAbsent(server.id) { Any() }
        synchronized(lock) {
            val existing = pool[server.id]
            if (existing != null && existing.client.isConnected) {
                val hits = bumpOutstanding(server.id)
                android.util.Log.d(
                    TAG,
                    "acquire(${server.id}) HIT — refcount=$hits (no touch)"
                )
                return existing.client
            }
            if (existing != null) {
                android.util.Log.d(TAG, "acquire(${server.id}): cached client is dead, rebuilding")
                SilentlyTry.fired("SshAi-Pool", "disconnect dead cached client") { existing.client.disconnect() }
                pool.remove(server.id)
            }
            android.util.Log.d(TAG, "acquire(${server.id}) MISS — opening new SSH (touch needed for SK)")
            val fresh = openAndAuthenticate(server, secrets, skSigner)
            // openAndAuthenticate can only throw AFTER handing us a connected
            // client if auth failed — and a client that never lands in the
            // pool is a LEAKED live socket the server has to reap on its own
            // (LoginGraceTime timeout → a preauth line fail2ban counts).
            // openAndAuthenticate owns that cleanup now (see its catch), so a
            // throw out of it never leaves a socket behind.
            // NOT reset to 1: holders of the transport we just replaced still
            // owe a release each.
            pool[server.id] = Entry(fresh)
            val opened = bumpOutstanding(server.id)
            android.util.Log.d(TAG, "acquire(${server.id}) opened refcount=$opened")
            return fresh
        }
    }

    /**
     * Release a reference acquired via [acquire]. The last release
     * disconnects the client.
     */
    fun release(serverId: String) {
        val lock = perServerLock[serverId] ?: return
        synchronized(lock) {
            // Decrement FIRST, and whether or not a transport is pooled right
            // now: an evicted entry must not make a holder's release vanish,
            // or the count drifts up and the transport is never closed.
            val left = ((outstanding[serverId] ?: 0) - 1).coerceAtLeast(0)
            if (left == 0) outstanding.remove(serverId) else outstanding[serverId] = left
            android.util.Log.d(TAG, "release($serverId) — refcount=$left")
            val entry = pool[serverId] ?: return
            if (left <= 0) {
                android.util.Log.d(TAG, "  refcount hit zero — disconnecting client")
                pool.remove(serverId)
                // Off-thread: the chat's retry path releases from Main, where a
                // synchronous disconnect() throws NetworkOnMainThreadException
                // and the socket survives the "close". See [closeAsync].
                closeAsync(entry.client, "disconnect on release")
            }
        }
    }

    /**
     * Throw away the shared transport for [serverId] — it is alive at the TCP
     * level but useless, so the next connect rebuilds it.
     *
     * Needed because sshd caps concurrent channels per connection
     * (`MaxSessions`, 10 by default). Once that ceiling is hit the server
     * answers EVERY channel request with an open-failure, so the sessions list,
     * the model probe, command discovery and the turn itself all fail together
     * and keep failing forever — the app looks connected (the socket is fine)
     * and does nothing.
     *
     * Dropping it is cheap and, on a seamless-enrolled server, invisible: the
     * reconnect authenticates with the enrolled ephemeral device key, so there
     * is NO FIDO tap. The user should never have to disconnect/reconnect by
     * hand to clear this — that is exactly what seamless reconnect is for.
     *
     * ⚠ [minAgeMs] — DO NOT EVICT A TRANSPORT YOUNGER THAN THE FAILURE.
     * This method evicts by `serverId`, i.e. whatever is in the pool *now* —
     * which is not necessarily the transport that failed. A caller reacting to
     * a Failed("disconnected") runs SECONDS after the fact, and by then the
     * ephemeral reconnect / service watchdog may have already built a fresh,
     * healthy transport. Evicting that one is not a recovery, it is the
     * failure: the rebuilt chat session comes up on a corpse, goes
     * Failed("disconnected") again, the ladder fires another retry, and the app
     * destroys a working connection every ~4 s forever — reconnecting,
     * re-parsing the whole session file and never reaching Running, so the
     * user's queued message can never be delivered (livelock measured on
     * device 2026-08-16: 15 evictions/min, CPU 55→72 °C, chat frozen on
     * «⚡ connection lost» ⇄ «↻ 1 message waiting to send»).
     *
     * So a caller that only knows "a turn failed a while ago" passes
     * [minAgeMs] and the pool keeps anything newer than that. A caller that
     * just failed ON the pooled client itself (the MaxSessions path in
     * [ai.eight24family.conch.agent.AgentSessionSshLifecycle]) has proven that
     * exact transport bad and passes 0.
     */
    fun evictPoisoned(serverId: String, reason: String, minAgeMs: Long = 0L) {
        val lock = perServerLock[serverId] ?: return
        synchronized(lock) {
            val entry = pool[serverId] ?: return
            val ageMs = System.currentTimeMillis() - entry.openedAtMs
            if (!shouldEvictPoisoned(ageMs, minAgeMs)) {
                android.util.Log.i(
                    TAG,
                    "evictPoisoned($serverId) SKIPPED — $reason, but the pooled transport is only " +
                        "${ageMs}ms old (< ${minAgeMs}ms): it postdates the failure, so it is a " +
                        "rebuild, not the corpse. Keeping it."
                )
                return
            }
            pool.remove(serverId)
            android.util.Log.w(TAG, "evictPoisoned($serverId) — $reason; transport dropped for rebuild (age=${ageMs}ms)")
            closeAsync(entry.client, "disconnect poisoned client")
        }
    }

    /**
     * Close a transport OFF the caller's thread.
     *
     * `disconnect()` does socket I/O. The chat's retry path calls into the pool
     * from Main, where that throws `NetworkOnMainThreadException` — which
     * [SilentlyTry] swallows, so the socket was NEVER ACTUALLY CLOSED. Every
     * reconnect cycle then leaked a socket, an sshj Reader thread and a
     * server-side sshd session, and leaked sshd sessions are precisely what
     * trips the `MaxSessions` ceiling [evictPoisoned] exists to clear (one
     * swallowed NetworkOnMainThreadException per cycle in the 2026-08-16
     * logcat). The entry is already out of the map by the time we get here, so
     * nothing can hand this client out again while it closes.
     */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun closeAsync(client: SSHClient, why: String) {
        GlobalScope.launch(Dispatchers.IO) {
            SilentlyTry.fired(TAG, why) { client.disconnect() }
        }
    }

    /**
     * Look up an alive shared client without mutating refcounts.
     * Used by the sessions-list / agent-picker reuse paths so they
     * can ride a connection without bumping the count (they don't
     * own the lifetime).
     *
     * **Lock-free** so the UI thread (3 s server-list dot poller)
     * doesn't serialise with the slow connect+auth path. Worst-case
     * inconsistency: peek may briefly see a stale "no entry" while
     * an acquire on the same server is mid-handshake; the row's dot
     * blinks dark for a beat and lights up on the next poll. That's
     * fine — the alternative was a 10-second ANR.
     */
    fun peek(serverId: String): SSHClient? {
        val entry = pool[serverId] ?: return null
        return if (entry.client.isConnected) entry.client else null
    }

    /** Lock-free alive count. Same caveats as [peek]. */
    fun aliveCount(): Int = pool.values.count { it.client.isConnected }

    // ── User-intent connections (held until explicit disconnect) ──
    //
    // Tapping a server in the home screen tells the pool "I want this
    // server connected — open SSH and HOLD IT until I tap disconnect".
    // We model that as a single extra `acquire` reference per server,
    // recorded in [userHeld]. AgentSession opens its own +1 ref for
    // each chat/discovery, so refcount never drops to 0 while the user
    // intent is in place — SSH stays up, the cyan dot stays lit, and
    // every chat-open / sessions-list refresh is free.
    //
    // Released via [userDisconnect] (long-press menu in the home
    // screen) or implicitly when the underlying transport dies and
    // a fresh acquire is needed (caller decides whether to re-touch).
    private val userHeld = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** Per-server timestamp of the last successful userConnect — drives
     *  the "fresh login" gate in AgentPickerScreen: if a connect just
     *  came up, callers should treat cached agent statuses as stale
     *  and re-probe before letting the user click into chat. */
    private val _connectedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** StateFlow mirror of [userHeld]'s size. The foreground service
     *  observes this to keep the process alive for as long as the user
     *  has at least one explicitly-connected server — independent of
     *  whether any chat session is currently active. So you can swipe
     *  the app away, the process stays, the SSH stays, you tap the icon
     *  later and you're still there. Only an explicit [userDisconnect]
     *  (or the underlying transport dying with no replacement) drops it. */
    private val _userHeldCount = kotlinx.coroutines.flow.MutableStateFlow(0)
    val userHeldCount: kotlinx.coroutines.flow.StateFlow<Int> = _userHeldCount

    /** Live set of `serverId`s with a user-intent reference held —
     *  re-emits on every connect/disconnect. Used by the foreground
     *  service to render ONE notification per server with its own
     *  END button instead of a single "N servers connected" summary.
     *  Keep in lockstep with [_userHeldCount] (both updated under
     *  the same per-server lock). */
    private val _userHeldIds = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val userHeldIds: kotlinx.coroutines.flow.StateFlow<Set<String>> = _userHeldIds

    /**
     * Open + hold a user-intent connection for [server]. No-op if a
     * user-intent reference is already held for this server (taps
     * are idempotent).
     *
     * Returns the pooled client. Throws on auth failure (caller
     * surfaces the error to the UI).
     */
    fun userConnect(
        server: Server,
        secrets: ServerSecrets,
        skSigner: ai.eight24family.conch.ssh.securitykey.SkSigner? = null,
    ): SSHClient {
        // THE HUMAN ACTION THAT CLEARS THE SILENT-DIAL BACKOFF.
        //
        // The rule below [resetSilentBackoff] says it plainly: "a HUMAN action
        // resets it". Nothing did - the only caller was the network-change
        // handler. So after a bad-network stretch the streak stood at up to
        // fifteen minutes, and every seamless path (`retry()`'s reconnect, the
        // Connect button's silent attempt) refused with nothing but a logcat
        // line, leaving the user to pay a security-key tap that seamless
        // reconnect exists to spare them. Reaching this function means a person
        // asked for this connection; the streak is stale by definition.
        resetSilentBackoff()
        if (userHeld.contains(server.id)) {
            // Already user-held. Don't bump refcount again — just
            // return whichever live client the pool currently has.
            // If it's gone (transport died silently), fall through
            // to a fresh acquire below; that re-uses the user-held
            // ref slot (we drop the stale set entry first to keep
            // userDisconnect balanced).
            val existing = peek(server.id)
            if (existing != null) {
                android.util.Log.d(TAG, "userConnect(${server.id}) — already held, returning live client")
                return existing
            }
            android.util.Log.d(TAG, "userConnect(${server.id}) — held but client dead, re-acquiring")
            userHeld.remove(server.id)
        }
        val client = acquire(server, secrets, skSigner)
        // A connect that WORKED is the strongest possible evidence the server is
        // reachable, so record it like a successful silent dial - otherwise the
        // per-server streak survived a manual reconnect and kept vetoing the
        // seamless path afterwards.
        noteSilentDialResult(server.id, ok = true)
        // Mark held only AFTER a successful acquire — if auth blew up
        // we don't want a phantom userHeld entry that release can't
        // balance.
        userHeld.add(server.id)
        _connectedAt[server.id] = System.currentTimeMillis()
        _userHeldCount.value = userHeld.size
        _userHeldIds.value = userHeld.toSet()
        persistUserHeldAsync()
        android.util.Log.d(TAG, "userConnect(${server.id}) — held (refcount remains for user intent), userHeld=${userHeld.size}")
        // Kick the foreground service. Idempotent — multiple userConnects
        // for different servers each call start(), the OS dedups so we
        // get exactly one notification.
        SilentlyTry.fired("SshAi-Pool", "start SshAiService") {
            ai.eight24family.conch.service.SshAiService.start(
                ai.eight24family.conch.di.ServiceLocator.appContext
            )
        }
        // SK server + "Seamless reconnect" on → enroll a self-expiring hardware
        // device key so a later network-change reconnect needs no tap. No-op
        // when the setting is off (checked inside). Best-effort, append-safe.
        if (secrets.skKeys.isNotEmpty()) maybeEnrollEphemeralAsync(server)
        return client
    }

    /** Fire-and-forget: snapshot the current userHeld set into prefs
     *  so the next cold app start can attempt auto-reconnect. Runs in
     *  a global IO coroutine to avoid blocking the pool lock. */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun persistUserHeldAsync() {
        val snapshot = userHeld.toSet()
        GlobalScope.launch(Dispatchers.IO) {
            SilentlyTry.fired("SshAi-Pool", "persist user-held server ids") {
                ai.eight24family.conch.di.ServiceLocator.preferences
                    .setUserHeldServerIds(snapshot)
            }
        }
    }

    /** Drop the user-intent reference for [serverId] AND revoke its seamless
     * device key. An EXPLICIT disconnect (notification "End", ServerDetail
     * disconnect, per-server remove) means "I'm done with this server" — so we
     * strip the device key from the server's authorized_keys, delete the local
     * key, and clear its expiry. A server you cut keeps NO silent-reconnect
     * credential — not on the server, not in the app. Network blips / app close
     * do NOT route here, so seamless still survives those — only a deliberate
     * disconnect tears the key down (so [connectAllPossibleSilently] then has
     * nothing to reconnect, and the disconnect actually sticks). */
    fun userDisconnect(serverId: String) {
        val wasHeld = userHeld.remove(serverId)
        if (wasHeld) {
            _connectedAt.remove(serverId)
            _userHeldCount.value = userHeld.size
            _userHeldIds.value = userHeld.toSet()
            persistUserHeldAsync()
        }
        // Revoke the device key regardless of held state (also covers a seamless
        // toggle-off on an already-disconnected server). Async + idempotent.
        stripAndForgetDeviceKeyAsync(serverId)
        android.util.Log.d(TAG, "userDisconnect($serverId) — wasHeld=$wasHeld, revoking device key; userHeld=${userHeld.size}")
        if (wasHeld) release(serverId)
    }

    /** Snapshot of servers currently held by user-intent. */
    fun userHeldIds(): Set<String> = userHeld.toSet()

    /** Wall-clock timestamp of the last successful `userConnect` for
     *  [serverId], or null if we've never connected (or the entry was
     *  cleared by userDisconnect). Callers can subtract from `now` to
     *  decide whether the connection is "fresh" enough to warrant
     *  re-probing dependent state (agent versions, etc.). */
    fun connectedAt(serverId: String): Long? = _connectedAt[serverId]

    /**
     * Walk every user-intent server and drop the ones whose underlying
     * SSHClient has died (TCP closed, server reboot, transport timeout).
     * Without this the foreground-service notification keeps showing
     * "N server connected" forever after a network blip even though
     * the next chat-open will still ask for a fresh tap.
     *
     * Returns the IDs that were pruned so the caller can update its
     * UI (e.g. rebuild the notification text with the corrected
     * count). Idempotent — calling on a fully-alive pool is a no-op.
     */
    /**
     * DEAD CODE ON PURPOSE - do not "wire it up" without reading this.
     *
     * It has no callers, and that is the DESIGN: `userHeld` is user INTENT
     * ("I connected this server"), and dropping a server out of it the moment
     * its socket dies is what used to make a network blip look like the user
     * had disconnected - the reconnect ladder then had nothing to work on and
     * `SshAiService` stopped keeping the process alive. Intent outlives the
     * socket, deliberately (see the note in SshAiService).
     *
     * It stayed here as an escape hatch, and its existence was quietly load
     * bearing in the wrong way: the chat's connection dot read `userHeldIds`
     * and its KDoc justified that by claiming this pruner kept the set honest.
     * Nothing called it, so the dot latched green forever. The dot now reads the
     * transport (`peek`) instead - see `ChatViewModel.connected`.
     *
     * Kept, unused, because the ONE thing it is right for is an explicit
     * "forget everything" path; delete it rather than call it from a watchdog.
     */
    @Suppress("unused")
    fun pruneDeadUserHeld(): List<String> {
        val dead = userHeld.filter { peek(it) == null }
        if (dead.isEmpty()) return emptyList()
        for (id in dead) {
            userHeld.remove(id)
            val lock = perServerLock[id]
            if (lock != null) {
                synchronized(lock) {
                    val entry = pool[id]
                    if (entry != null && !entry.client.isConnected) {
                        pool.remove(id)
                        SilentlyTry.fired("SshAi-Pool", "disconnect on prune") { entry.client.disconnect() }
                    }
                }
            }
        }
        _userHeldCount.value = userHeld.size
        _userHeldIds.value = userHeld.toSet()
        persistUserHeldAsync()
        android.util.Log.d(TAG, "pruneDeadUserHeld removed ${dead.size} dead entr${if (dead.size == 1) "y" else "ies"}; userHeld=${userHeld.size}")
        return dead
    }

    /** User-held servers whose transport is currently DOWN — the user still
     *  WANTS them connected (intent held) but there's no live SSH right now
     *  (network blip / handoff / server reboot). Drives the amber
     *  "reconnect pending" dot + the "tap to reconnect" notification. */
    fun heldButDownIds(): Set<String> = userHeld.filter { peek(it) == null }.toSet()

    /** Servers whose silent auto-connect handshake is IN FLIGHT right now
     *  (launch / foreground). Lets the UI paint a "connecting…" dot during the
     *  ~2s SSH handshake instead of a dead one — so the app visibly connects on
     *  its own, not "only when I open the Servers tab". */
    fun connectingIds(): Set<String> = silentConnectInFlight.toSet()

    /**
     * Open EVERY server we CAN reach without a physical tap, SILENTLY, the moment the
     * app is foregrounded — the doctrine "the app is open and there's access, so just
     * connect, everywhere, without me tapping anything". Per server: - **password /
     * plain private key** → [userConnect] with the stored secret. No tap, no device
     * key needed — these should ALWAYS auto-connect (this is the gap the user hit:
     * non-SK servers only came up when they navigated to the server page and pressed
     * Connect). - **FIDO/SK with an enrolled device key (seamless)** →
     * [userConnectEphemeral], silent via that key. - **FIDO/SK WITHOUT a device key**
     * → skipped; CTAP needs a physical touch, so the user connects it once (turn on
     * Seamless reconnect to make even this tapless on later launches). Idempotent
     * (already-live servers skipped); does NOT require the server to be in the
     * user-held set. Best-effort; never throws. Call off-main.
     */
    /** Guards against concurrent silent-connect runs racing on the SAME server.
     *  onCreate (cold) + onStart (foreground) fire this near-simultaneously on
     *  launch; without the guard both saw peek==null during the ~2s handshake
     *  and BOTH dialed the same server → "Long monitor contention" stall. A
     *  server already being brought up by one run is skipped by the other. */
    private val silentConnectInFlight = ConcurrentHashMap.newKeySet<String>()

    /** serverId → (consecutive silent-dial failures, epoch ms of the last one).
     *  SILENT dials only — the 20 s service watchdog + every-foreground
     *  connectAllPossibleSilently used to redial a failing server forever with
     *  ZERO backoff: 3 full handshakes/minute against stale creds is a fail2ban
     *  ban in under two minutes, and once banned the 15 s connect timeouts kept
     *  refreshing the ban — the phone could never recover on its own. Explicit
     *  user connects NEVER consult this (they must try NOW), and a success or a
     *  network change clears it. */
    private data class SilentFailStreak(val count: Int, val lastMs: Long)
    private val silentFails = ConcurrentHashMap<String, SilentFailStreak>()

    /** User-tunable (Settings → Connection → fail2ban): the backoff FLOOR in ms
     *  and the master AUTO-connect switch. Read via runBlocking on the IO-thread
     *  callers (same pattern as the connect timeouts); cheap DataStore reads. A
     *  stricter jail → raise the floor or turn auto-connect off entirely. */
    private fun silentFloorMs(): Long = runBlocking {
        ai.eight24family.conch.di.ServiceLocator.preferences.silentReconnectFloorSec.first()
    }.coerceIn(20, 600) * 1000L

    /** False → the app must not open ANY handshake on its own; only explicit
     *  user actions (tap connect / retry / open chat) may dial. */
    fun autoConnectAllowed(): Boolean = runBlocking {
        ai.eight24family.conch.di.ServiceLocator.preferences.autoConnectEnabled.first()
    }

    /** True when [sid] may be silently dialed: auto-connect is on, and there is
     *  no failure streak or its exponential cool-down (floor · 2^(n-1), capped
     *  15 min) has passed. The floor is the user's fail2ban knob. */
    private fun silentCooldownPassed(sid: String): Boolean {
        if (!autoConnectAllowed()) return false
        val f = silentFails[sid] ?: return true
        val shift = (f.count - 1).coerceIn(0, 5)
        val waitMs = (silentFloorMs() shl shift).coerceAtMost(15 * 60_000L)
        return System.currentTimeMillis() - f.lastMs >= waitMs
    }

    private fun noteSilentDialResult(sid: String, ok: Boolean) {
        if (ok) {
            silentFails.remove(sid)
        } else {
            val cur = silentFails[sid]
            silentFails[sid] = SilentFailStreak((cur?.count ?: 0) + 1, System.currentTimeMillis())
        }
    }

    /** New default network / explicit user action → the world changed; every
     *  server deserves a fresh immediate try. */
    fun resetSilentBackoff() {
        silentFails.clear()
    }

    suspend fun connectAllPossibleSilently() {
        val repo = ai.eight24family.conch.di.ServiceLocator.serverRepository
        val servers = SilentlyTry.loggedOrElse(TAG, "list servers for silent auto-connect", emptyList<Server>()) {
            repo.observeServers().first()
        }
        // Connect every reachable server CONCURRENTLY — per-server locks make this
        // safe, so launch-time connect costs the SLOWEST single handshake, not the
        // SUM. The in-flight guard dedupes the onCreate+onStart double-fire.
        kotlinx.coroutines.coroutineScope {
            for (server in servers) {
                if (peek(server.id) != null) continue              // already live
                if (!silentCooldownPassed(server.id)) continue     // failing lately — let it cool down
                if (!silentConnectInFlight.add(server.id)) continue // another run is bringing it up
                launch {
                    // Only an ATTEMPTED dial feeds the backoff: an SK server
                    // without a device key is skipped, not failed.
                    var attempted = false
                    try {
                        SilentlyTry.fired(TAG, "silent auto-connect ${server.id}") {
                            val secrets = repo.getSecrets(server.id)
                            if (secrets.skKeys.isNotEmpty()) {
                                // FIDO: silent ONLY via an enrolled device key.
                                // (No `attempted` here — userConnectEphemeral
                                // notes its own dial result now; noting again
                                // would count one failure twice.)
                                if (EphemeralSshKey.exists(server.id)) {
                                    if (userConnectEphemeral(server) != null) {
                                        android.util.Log.d(TAG, "silent auto-connect: SK ${server.name} up via device key")
                                    }
                                } else {
                                    android.util.Log.d(TAG, "silent auto-connect: SK ${server.name} skipped — no device key (needs a tap)")
                                }
                            } else {
                                // Password / plain key: stored secret, no tap.
                                attempted = true
                                userConnect(server, secrets, null)
                                android.util.Log.d(TAG, "silent auto-connect: ${server.name} up via stored secret")
                            }
                        }
                    } finally {
                        if (attempted) noteSilentDialResult(server.id, ok = peek(server.id) != null)
                        silentConnectInFlight.remove(server.id)
                    }
                }
            }
        }
    }

    /**
     * The device's default network just changed (Wi-Fi ⇄ cellular, dropped
     * and came back). For every server the user wants held whose transport
     * has died, try to restore it **without throwing away the user's intent**:
     *
     * - password / private-key servers reconnect SILENTLY on the new network.
     * - FIDO/SK servers can't re-auth without a physical tap, so we KEEP the
     * held intent and leave them down-but-intended. The foreground
     * notification flips to "tap to reconnect", the Servers list shows an
     * amber reconnect-pending dot, and one tap (or the next chat send)
     * restores it in a single touch.
     *
     * Best-effort; never throws. Must be called off the main thread.
     */
    suspend fun reconnectHeldOnNetworkChange() {
        // Master fail2ban switch: auto-connect off → the app opens nothing on a
        // network change either; the user reconnects by hand.
        if (!autoConnectAllowed()) return
        val repo = ai.eight24family.conch.di.ServiceLocator.serverRepository
        // A NEW network invalidates every "this server keeps failing" verdict —
        // the failures may have been the old network's fault (or a fail2ban ban
        // that the new egress IP isn't subject to). Fresh immediate tries.
        resetSilentBackoff()
        // Snapshot — userConnect mutates userHeld while it re-acquires.
        for (sid in userHeld.toSet()) {
            if (peek(sid) != null) continue  // transport survived / already back
            val server = SilentlyTry.logged(TAG, "load server for net-reconnect") { repo.getById(sid) } ?: continue
            val secrets = SilentlyTry.logged(TAG, "load secrets for net-reconnect") { repo.getSecrets(sid) } ?: continue
            if (secrets.skKeys.isNotEmpty()) {
                // FIDO: reconnect SILENTLY via the opt-in hardware device key
                // (the key only EXISTS when "Seamless reconnect" is on). If it's
                // off / no key / it fails → keep intent (amber dot + tap on next
                // use). This is the "I left my key at home" path.
                if (EphemeralSshKey.exists(server.id) && userConnectEphemeral(server) != null) {
                    android.util.Log.d(TAG, "net-change: silently reconnected SK $sid (${server.name}) via device key")
                } else {
                    android.util.Log.d(TAG, "net-change: SK $sid (${server.name}) down — keeping intent, awaiting tap")
                }
                continue
            }
            SilentlyTry.fired(TAG, "silent reconnect on network change") {
                userConnect(server, secrets, null)
                android.util.Log.d(TAG, "net-change: silently reconnected $sid (${server.name})")
            }
        }
    }

    /**
     * Timer-driven self-heal, called periodically by the foreground service.
     * Silently restores every HELD-but-down server so an idle SSH drop (no
     * network-change event, no app-foreground event) recovers on its own —
     * instead of staying "connected-in-intent but transport-down" until the
     * user manually re-logs the server, which was the ONLY way to revive the
     * phone bridge after it went deaf (session finding REL-2: chat alive on the
     * next send, but the bridge poller's peek() never came back). Same per-server
     * logic as [reconnectHeldOnNetworkChange] (SK → opt-in device key, no tap;
     * password/plain key → stored secret; SK-without-key left for a tap), plus
     * the [silentConnectInFlight] guard so it never double-dials a server that a
     * concurrent connect path is already bringing up. Idempotent, never throws,
     * off-main.
     */
    suspend fun reconnectHeldButDownSilently() {
        val repo = ai.eight24family.conch.di.ServiceLocator.serverRepository
        for (sid in userHeld.toSet()) {
            if (peek(sid) != null) continue              // transport already live
            // Exponential cool-down: the 20 s watchdog used to redial a
            // failing server forever (no cap, no backoff, failures swallowed
            // silently) — the fail2ban feeder. See [silentFails].
            if (!silentCooldownPassed(sid)) continue
            if (!silentConnectInFlight.add(sid)) continue // another path is on it
            var attempted = false
            try {
                SilentlyTry.fired(TAG, "watchdog silent reconnect $sid") {
                    val server = repo.getById(sid) ?: return@fired
                    val secrets = repo.getSecrets(sid)
                    if (secrets.skKeys.isNotEmpty()) {
                        if (EphemeralSshKey.exists(sid)) {
                            // No `attempted` — userConnectEphemeral notes its own
                            // dial result; noting again double-counts a failure.
                            if (userConnectEphemeral(server) != null) {
                                android.util.Log.d(TAG, "watchdog: restored SK $sid (${server.name}) via device key")
                            }
                        }
                        // SK without a device key → needs a physical tap; leave it.
                    } else {
                        attempted = true
                        userConnect(server, secrets, null)
                        android.util.Log.d(TAG, "watchdog: restored $sid (${server.name}) via stored secret")
                    }
                }
            } finally {
                if (attempted) noteSilentDialResult(sid, ok = peek(sid) != null)
                silentConnectInFlight.remove(sid)
            }
        }
    }

    /**
     * Per-server seamless toggle just turned ON (server detail page) → mint +
     * enroll THIS server's hardware device key NOW if it has a live transport,
     * instead of waiting for the next FIDO connect. [force] bypasses the
     * per-server opt-in check (the toggle write may not have propagated yet).
     * No-op if offline — it'll enroll on the next connect. Best-effort, off-main.
     */
    fun enrollSeamlessForServer(server: Server) {
        maybeEnrollEphemeralAsync(server, force = true)
    }

    /** Read the opt-in seamless-reconnect setting (blocking — call off-main). */
    /** Per-server: is seamless reconnect enabled for [serverId]? (Moved from a
     *  global app setting — it's a property of the server now.) */
    private fun seamlessEnabledFor(serverId: String): Boolean = runBlocking {
        ai.eight24family.conch.di.ServiceLocator.preferences.seamlessServers.first()
    }.contains(serverId)

    // Default MUST match the Settings selector's default (ServerDetailViewModel
    // .seamlessDays → `?: 7`) or the enrolled key's expiry silently disagrees
    // with what the UI shows: the selector highlighted "7 days" while the key was
    // minted at 30 and displayed "expires in 29d" (user, 2026-07-06). Both read
    // the same `seamlessDaysByServer` pref, so both must default the same when a
    // server has no explicit pick. Coerced to the 1-30 the setter allows.
    private fun seamlessDaysFor(serverId: String): Int = (runBlocking {
        ai.eight24family.conch.di.ServiceLocator.preferences.seamlessDaysByServer.first()
    }[serverId] ?: 7).coerceIn(1, 30)

    /**
     * Connect/reconnect a held SK server SILENTLY via its hardware device key
     * (no FIDO tap). Mirrors [userConnect]'s held bookkeeping. Returns the live
     * client, or null if there's no device key or the connect failed (caller
     * keeps intent + falls back to a tap). Never throws.
     */
    fun userConnectEphemeral(server: Server): SSHClient? {
        val provider = EphemeralSshKey.keyProvider(server.id) ?: return null
        android.util.Log.d(TAG, "ephemeral reconnect ${server.id}: presenting ${EphemeralSshKey.keyPart(server.id)}")
        val lock = perServerLock.computeIfAbsent(server.id) { Any() }
        val client = synchronized(lock) {
            val existing = pool[server.id]
            if (existing != null && existing.client.isConnected) {
                existing.client
            } else {
                // ⚠ THE BACKOFF LIVES HERE, where every silent dial must pass —
                // not only in the watchdog. The 20 s watchdog got its
                // exponential cool-down after the last fail2ban ban, but the
                // chat's reconnect ladder (retry()) calls THIS method directly
                // and kept dialing on every tick. On a flapping radio each tick
                // is a TCP connect torn down before auth completes — one
                // `[preauth]` line in the server's auth.log per attempt, and
                // those are exactly what fail2ban's aggressive mode counts. An
                // evening of bad Wi-Fi was a ban. No path may dial silently
                // faster than the cool-down; a HUMAN action resets it via
                // [resetSilentBackoff].
                if (!silentCooldownPassed(server.id)) {
                    android.util.Log.d(TAG, "ephemeral dial ${server.id} suppressed — cooling down after failures")
                    return null
                }
                if (existing != null) {
                    pool.remove(server.id)
                    closeAsync(existing.client, "disconnect dead before eph")
                }
                val fresh = try {
                    openWithProvider(server, provider)
                } catch (t: Throwable) {
                    noteSilentDialResult(server.id, ok = false)
                    android.util.Log.w(TAG, "device-key connect failed ${server.id}: ${t.javaClass.simpleName}: ${t.message}")
                    // Auth rejection (Exhausted) = the enrolled line is STALE / wrong
                    // encoding (e.g. minted by an older build). Drop the local key so
                    // the NEXT FIDO connect re-mints + re-enrolls a fresh, correctly
                    // encoded one (self-heals the "Exhausted" loop). A transient network
                    // error (not UserAuth) keeps the key — it's still valid.
                    if (t is net.schmizz.sshj.userauth.UserAuthException) {
                        android.util.Log.w(TAG, "dropping stale device key ${server.id} → re-enroll on next FIDO connect")
                        EphemeralSshKey.delete(server.id)
                    }
                    return null
                }
                noteSilentDialResult(server.id, ok = true)
                pool[server.id] = Entry(fresh, 1)
                fresh
            }
        }
        userHeld.add(server.id)
        _connectedAt[server.id] = System.currentTimeMillis()
        _userHeldCount.value = userHeld.size
        _userHeldIds.value = userHeld.toSet()
        persistUserHeldAsync()
        SilentlyTry.fired("SshAi-Pool", "start SshAiService (eph)") {
            ai.eight24family.conch.service.SshAiService.start(
                ai.eight24family.conch.di.ServiceLocator.appContext
            )
        }
        android.util.Log.d(TAG, "userConnectEphemeral(${server.id}) — connected silently via device key")
        // SLIDING EXPIRY: refresh the authorized_keys expiry-time on EVERY silent
        // reconnect too — not only on FIDO taps (userConnect). Without this the
        // server-side expiry counted down from the LAST TAP, so a user who only
        // ever silent-reconnects had the key self-destruct N days later mid-use →
        // UserAuthException Exhausted → key dropped → "needs tap". Now the key
        // stays valid as long as the app connects within the window. Async +
        // idempotent (re-stamps our marker line); honours the per-server seamless
        // opt-in.
        maybeEnrollEphemeralAsync(server)
        return client
    }

    /** Connect + configure + publickey-auth with [provider] (same connect/
     *  config as [openAndAuthenticate], minus the SK dance). */
    private fun openWithProvider(
        server: Server,
        provider: net.schmizz.sshj.userauth.keyprovider.KeyProvider,
    ): SSHClient {
        val connectTimeoutSec = runBlocking {
            ai.eight24family.conch.di.ServiceLocator.preferences.sshConnectTimeoutSec.first()
        }.takeIf { it > 0 }?.coerceIn(5, 60) ?: 15
        val keepaliveIntervalSec = runBlocking {
            ai.eight24family.conch.di.ServiceLocator.preferences.sshKeepaliveIntervalSec.first()
        }.takeIf { it > 0 }?.coerceIn(15, 120) ?: 30
        val client = SSHClient(deadPeerDetectingConfig()).apply {
            connectTimeout = TimeUnit.SECONDS.toMillis(connectTimeoutSec.toLong()).toInt()
            timeout = TimeUnit.MINUTES.toMillis(20).toInt()
        }
        val hostKeyVerifier = FingerprintHostKeyVerifier(server.knownHostKey)
        client.addHostKeyVerifier(hostKeyVerifier)
        try {
            client.connect(server.host, server.port)
        } catch (t: Throwable) {
            if (hostKeyVerifier.mismatch) hostKeyMismatchError(server, hostKeyVerifier.seenFingerprint)
            throw t
        }
        try {
            configureKeepAlive(client, keepaliveIntervalSec)
            // Custom auth — sshj's stock ECDSA auth signs via BouncyCastle, which
            // CANNOT use the hardware AndroidKeyStore key (non-extractable → "no
            // encoding for EC private key" → auth "Exhausted", proven on-device).
            // EphemeralEcdsaAuthMethod signs via the AndroidKeyStore JCA provider (TEE)
            // and encodes the pubkey byte-identically to the enrolled authorized_keys
            // line, so the server matches it. (Swapping config.keyAlgorithms did NOT
            // work — KeyedAuthMethod resolves the algorithm from its own queue.)
            client.auth(server.username, listOf(EphemeralEcdsaAuthMethod(provider.public, provider.private)))
        } catch (t: Throwable) {
            // A throw between connect() and a successful auth used to LEAK the
            // live socket (nobody held a reference) — the server had to reap it
            // at LoginGraceTime, logging the preauth-timeout lines fail2ban
            // counts. Close our half properly.
            SilentlyTry.fired("SshAi-Pool", "disconnect after failed device-key auth") { client.disconnect() }
            throw t
        }
        pinHostKeyIfUnset(server, hostKeyVerifier)
        return client
    }

    /** [DefaultConfig] with the KEEP_ALIVE provider: unlike the default
     *  heartbeat (fire-and-forget SSH_MSG_IGNORE), `keepalive@openssh.com`
     *  requests EXPECT replies, so a dead peer is detected after
     *  [KEEPALIVE_MAX_MISSES] silent intervals and the transport closes itself.
     *  Without this a phone that slept through a network drop held a zombie
     *  transport forever (`isConnected` stays true on a half-open socket): the
     *  pool never pruned it, the bridge went deaf, and the server kept a
     *  hanging session until TCP gave up. Same packet cadence as before — this
     *  costs nothing extra on the radio. */
    private fun deadPeerDetectingConfig(): DefaultConfig =
        DefaultConfig().apply {
            keepAliveProvider = net.schmizz.keepalive.KeepAliveProvider.KEEP_ALIVE
        }

    private fun configureKeepAlive(client: SSHClient, intervalSec: Int) {
        client.connection.transport.setTimeoutMs(0)
        client.connection.keepAlive.keepAliveInterval = intervalSec
        (client.connection.keepAlive as? net.schmizz.keepalive.KeepAliveRunner)
            ?.maxAliveCount = KEEPALIVE_MAX_MISSES
    }

    /**
     * After a FIDO connect — and ONLY when "Seamless reconnect" is on — mint +
     * enroll the hardware device key so later reconnects are silent. The SERVER
     * computes the `expiry-time` against its OWN clock (no client/server TZ
     * skew). SAFE: it strips only our own `sshai-ephemeral-<id>` marker line
     * (the user's FIDO line never matches that comment) via a temp file with a
     * non-empty guard, then appends the fresh line — it can never clobber other
     * keys. Best-effort; failure just means "reconnect needs a tap".
     */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun maybeEnrollEphemeralAsync(server: Server, force: Boolean = false) {
        GlobalScope.launch(Dispatchers.IO) {
            SilentlyTry.fired("SshAi-Pool", "enroll device key") {
                // [force] = the user just toggled seamless ON for this server, so
                // skip the (possibly not-yet-propagated) per-server check and mint
                // now. The auto path (force=false, on every FIDO connect) still
                // honours the per-server opt-in.
                if (!force && !seamlessEnabledFor(server.id)) {
                    android.util.Log.d("SshAi-Pool", "enroll SKIPPED: seamless OFF for server ${server.id}")
                    return@fired
                }
                // Mint the device key ONLY when there's a live, authenticated
                // connection to WRITE it into authorized_keys in the same breath. No
                // live connection => DEFER: don't mint. The toggle just records the
                // per-server opt-in; the NEXT FIDO touch-connect calls this
                // (force=false, line ~251 right after userauth) and mints+writes
                // together. Killed the bug where toggling seamless while OFFLINE
                // minted a local key that never reached the server, so every later
                // reconnect still demanded the physical key: a key that can't be
                // written is never born.
                val client = peek(server.id) ?: run {
                    android.util.Log.d(
                        "SshAi-Pool",
                        "enroll DEFERRED: no live connection for ${server.id} — mint+write on next touch-connect",
                    )
                    return@fired
                }
                android.util.Log.d("SshAi-Pool", "enroll: seamless ON + connected, minting+writing device key for ${server.id}")
                if (!EphemeralSshKey.ensure(server.id)) return@fired
                val keyPart = EphemeralSshKey.keyPart(server.id) ?: return@fired
                val days = seamlessDaysFor(server.id)
                val esc = ai.eight24family.conch.agent.shellEscape(keyPart)
                val markerEsc = ai.eight24family.conch.agent.shellEscape(EphemeralSshKey.markerComment(server.id))
                val inner =
                    "umask 077; mkdir -p ~/.ssh && chmod 700 ~/.ssh; " +
                    "f=~/.ssh/authorized_keys; [ -f \"\$f\" ] || { : > \"\$f\"; chmod 600 \"\$f\"; }; " +
                    "kp=$esc; " +
                    "exp=\$(date -d \"+${days} days\" +%Y%m%d%H%M 2>/dev/null || date -v+${days}d +%Y%m%d%H%M 2>/dev/null || true); " +
                    "tmp=\$(mktemp \"\${f}.XXXXXX\" 2>/dev/null || true); " +
                    "if [ -n \"\$tmp\" ]; then grep -vF $markerEsc \"\$f\" > \"\$tmp\" 2>/dev/null; if [ -s \"\$tmp\" ]; then mv \"\$tmp\" \"\$f\"; else rm -f \"\$tmp\"; fi; fi; " +
                    "if [ -n \"\$exp\" ]; then printf 'expiry-time=\"%s\" %s\\n' \"\$exp\" \"\$kp\" >> \"\$f\"; else printf '%s\\n' \"\$kp\" >> \"\$f\"; fi; " +
                    "chmod 600 \"\$f\""
                runPoolCommand(client, "bash -lc " + ai.eight24family.conch.agent.shellEscape(inner))
                // Record the local expiry estimate (now + N days) so Settings can
                // show a live countdown — the server stamped expiry-time with the
                // same +N-days against its own ~NTP-synced clock (skew = seconds).
                runBlocking {
                    ai.eight24family.conch.di.ServiceLocator.preferences.setDeviceKeyExpiry(
                        server.id, System.currentTimeMillis() + days.toLong() * 86_400_000L,
                    )
                }
                android.util.Log.d(TAG, "device key enrolled on ${server.id} (expiry ${days}d)")
            }
        }
    }

    /** One-shot exec on a pooled client, drained + closed. Transport-level
     *  [RemoteEnv.portable] chokepoint (same as SshClient.execute): the
     *  device-key enroll/strip commands are `bash -lc` scripts, and on a
     *  bash-less host they died silently — seamless reconnect just never
     *  worked there, with nothing in any log (2026-08-17 sweep). */
    private fun runPoolCommand(client: SSHClient, cmd: String) {
        val sess = client.startSession()
        try {
            val proc = sess.exec(ai.eight24family.conch.agent.RemoteEnv.portable(cmd))
            proc.inputStream.readBytes()
            proc.join(20, TimeUnit.SECONDS)
        } finally {
            SilentlyTry.fired("SshAi-Pool", "close pool cmd session") { sess.close() }
        }
    }

    /** Strip OUR `sshai-ephemeral-<id>` marker line from the server's
     *  authorized_keys via a guarded temp-file rewrite. Touches ONLY our own
     *  line (the user's FIDO line never carries that comment), so it can never
     *  clobber other keys. */
    private fun stripDeviceKeyLine(client: SSHClient, serverId: String) {
        val markerEsc = ai.eight24family.conch.agent.shellEscape(EphemeralSshKey.markerComment(serverId))
        val inner =
            "f=~/.ssh/authorized_keys; [ -f \"\$f\" ] || exit 0; " +
            "tmp=\$(mktemp \"\${f}.XXXXXX\" 2>/dev/null || true); " +
            "if [ -n \"\$tmp\" ]; then grep -vF $markerEsc \"\$f\" > \"\$tmp\" 2>/dev/null; if [ -s \"\$tmp\" ]; then mv \"\$tmp\" \"\$f\"; chmod 600 \"\$f\"; else rm -f \"\$tmp\"; fi; fi"
        runPoolCommand(client, "bash -lc " + ai.eight24family.conch.agent.shellEscape(inner))
    }

    /**
     * Revoke the device key for ONE server (Settings → per-server "remove") —
     * keeps every OTHER server's key intact. Strips the line FROM THE SERVER:
     * uses the live pooled client if connected, else opens a TRANSIENT
     * device-key connection just for the strip (the key still exists at this
     * point) and closes it. If the server is unreachable, the local key is still
     * dropped and the line self-destructs server-side via its `expiry-time`.
     */
    fun revokeDeviceKey(server: Server) = userDisconnect(server.id)

    /**
     * Strip OUR `sshai-ephemeral-<id>` line from the server's authorized_keys,
     * delete the local hardware key, and clear the expiry — the full key
     * teardown with NO connection/refcount side effects (the caller,
     * [userDisconnect], handles those). Uses the live pooled client if
     * connected, else opens a TRANSIENT device-key connection just for the strip
     * (the local key still exists at this point) and closes it. Unreachable →
     * local key still dropped + the server line self-destructs via its
     * `expiry-time`. Async, best-effort, idempotent — a no-op when there's no
     * device key.
     */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun stripAndForgetDeviceKeyAsync(serverId: String) {
        if (!EphemeralSshKey.exists(serverId)) return
        GlobalScope.launch(Dispatchers.IO) {
            SilentlyTry.fired("SshAi-Pool", "revoke device key $serverId") {
                val server = ai.eight24family.conch.di.ServiceLocator.serverRepository.getById(serverId)
                val existing = peek(serverId)
                val client = existing ?: server?.let { srv ->
                    runCatching { EphemeralSshKey.keyProvider(serverId)?.let { openWithProvider(srv, it) } }.getOrNull()
                }
                if (client != null) {
                    SilentlyTry.fired("SshAi-Pool", "strip device key line $serverId") {
                        stripDeviceKeyLine(client, serverId)
                    }
                    // Close a TRANSIENT connection we opened just for the strip;
                    // never disconnect one the user is actively holding (existing).
                    if (client !== existing) {
                        SilentlyTry.fired("SshAi-Pool", "close transient revoke conn") { client.disconnect() }
                    }
                } else {
                    android.util.Log.w(TAG, "revoke $serverId: unreachable — server line expires via expiry-time")
                }
                EphemeralSshKey.delete(serverId)
                runBlocking {
                    ai.eight24family.conch.di.ServiceLocator.preferences.clearDeviceKeyExpiry(serverId)
                }
                android.util.Log.d(TAG, "device key revoked for $serverId (server-side strip=${client != null})")
            }
        }
    }

    /**
     * Open a fresh SSH connection and authenticate. For SK servers the
     * caller must hand in [skSigner] — the deferred-tap signer that
     * blocks until the dialog feeds it a Ctap2Session. We build one
     * [SkAuthPublickey] per enrolled SK key in [secrets.skKeys]; sshj
     * walks the methods sending pubkey-only test packets first, and
     * only the one the server picks (USERAUTH_60) actually triggers
     * sign() — so multi-key enrollment costs no extra taps.
     *
     * No recovery / enumerate during the connect path. If the user
     * taps a key whose credId isn't enrolled here, sshj exhausts and
     * throws — the touch dialog's classifier shows a "Wrong key —
     * Discover credentials / Register a new one" UX, sending the user
     * to Keychain to sort it out cleanly. That's a one-touch detour,
     * but it lives outside the connect flow where the CTAP firmware
     * doesn't lock the session state on the second try.
     */
    private fun openAndAuthenticate(
        server: Server,
        secrets: ServerSecrets,
        skSigner: ai.eight24family.conch.ssh.securitykey.SkSigner?,
    ): SSHClient {
        // Read from Settings → Connection. runBlocking is OK here because
        // openAndAuthenticate is always invoked from Dispatchers.IO (per
        // CLAUDE.md §3a). Coerce out-of-range / zero values to the legacy
        // hardcoded defaults so a bad pref can never produce a 0-second
        // timeout that fails every connect.
        val connectTimeoutSec = runBlocking {
            ai.eight24family.conch.di.ServiceLocator.preferences.sshConnectTimeoutSec.first()
        }.takeIf { it > 0 }?.coerceIn(5, 60) ?: 15
        // Read from Settings → Connection. Same fallback rationale as above;
        // sshj treats 0 as "disable keep-alive" which would silently drop the
        // user's intent to keep idle SSH transports warm.
        val keepaliveIntervalSec = runBlocking {
            ai.eight24family.conch.di.ServiceLocator.preferences.sshKeepaliveIntervalSec.first()
        }.takeIf { it > 0 }?.coerceIn(15, 120) ?: 30

        val client = SSHClient(deadPeerDetectingConfig()).apply {
            connectTimeout = TimeUnit.SECONDS.toMillis(connectTimeoutSec.toLong()).toInt()
            timeout = TimeUnit.MINUTES.toMillis(20).toInt()
        }
        val hostKeyVerifier = FingerprintHostKeyVerifier(server.knownHostKey)
        client.addHostKeyVerifier(hostKeyVerifier)
        try {
            client.connect(server.host, server.port)
        } catch (t: Throwable) {
            // Host-key refusal throws out of connect(), before the socket is
            // ours to keep — say WHICH failure this was instead of letting the
            // user read sshj's "Could not verify `ssh-ed25519` host key".
            if (hostKeyVerifier.mismatch) hostKeyMismatchError(server, hostKeyVerifier.seenFingerprint)
            throw t
        }
        // From here on the socket is LIVE — any throw before auth completes
        // must disconnect it, or the server reaps it at LoginGraceTime and
        // fail2ban counts the preauth line (see the catch at the bottom).
        try {
        configureKeepAlive(client, keepaliveIntervalSec)
        when (server.authMethod) {
            AuthMethod.PASSWORD -> client.authPassword(server.username, secrets.password ?: error("password required"))
            AuthMethod.KEY -> {
                if (secrets.skKeys.isNotEmpty()) {
                    val primarySigner = skSigner
                        ?: error("security-key signer not provided — touch the token in the chat opener first")
                    val sharedHolder = (primarySigner as? ai.eight24family.conch.ssh.securitykey.DeferredCtapSkSigner)?.holder
                        ?: error("SK auth requires a deferred-tap signer with a holder")

                    // Hand the holder everything the dialog needs to drive
                    // CTAP enumerate + attach inside its withNfc{} block.
                    sharedHolder.candidateCredIds = secrets.skKeys.mapNotNull { key ->
                        key.securityInfo?.credentialIdBase64?.let { java.util.Base64.getDecoder().decode(it) }
                    }
                    sharedHolder.serverId = server.id
                    sharedHolder.application = primarySigner.application
                    android.util.Log.d(TAG, "  SK auth: waiting for dialog enumerate (serverId=${server.id})")

                    // Block until the dialog has run getPinToken(CM) +
                    // enumerateCredentials + addSecurityKey + attachKey,
                    // then signaled holder.tokenCredsReady. Only then do
                    // we know which credId the touched token actually has,
                    // and can build a single, exact sshj auth method.
                    //
                    // **Bounded wait** (90s). Without the timeout, an
                    // activity recreate (rotation, "Don't keep
                    // activities", system kill) leaves the dialog
                    // disposed without ever feeding the holder — the
                    // wait would block the per-server lock forever,
                    // and subsequent userConnect calls (e.g. from the
                    // newly-mounted dialog) would deadlock on the
                    // serverLock. 90s matches AgentPicker's sk-touch
                    // watchdog so the user sees one consistent
                    // failure mode if the auth never completes.
                    val ready = kotlinx.coroutines.runBlocking {
                        kotlinx.coroutines.withTimeoutOrNull(90_000L) {
                            sharedHolder.tokenCredsReady.await()
                        }
                    }
                    if (ready == null) {
                        android.util.Log.w(
                            TAG,
                            "  SK auth: tokenCredsReady timed out after 90s — releasing serverLock"
                        )
                        SilentlyTry.fired("SshAi-Pool", "disconnect after sk timeout") { client.disconnect() }
                        error("security key dialog never reported credentials — re-tap to retry")
                    }
                    val matchedCredId = sharedHolder.matchedCredId
                        ?: error("dialog signaled tokenCredsReady but matchedCredId is null")
                    val matchedApp = sharedHolder.matchedApplication ?: primarySigner.application

                    // Re-fetch the server's enrolled keys — may have grown
                    // during the dialog's auto-attach pass.
                    val freshSecrets = kotlinx.coroutines.runBlocking {
                        ai.eight24family.conch.di.ServiceLocator.serverRepository.getSecrets(server.id)
                    }
                    // Resolve the matched credential against the re-fetched keys
                    // FIRST (they include anything the dialog just auto-attached),
                    // then fall back to the keys the CALLER passed in. The
                    // fallback is what makes a TRANSIENT verify work (Add-server
                    // "Test"): there `server.id` is a synthetic, unsaved id, so
                    // getSecrets returns nothing — yet the matched credential is
                    // one of the caller's ticked keys. For a persisted server the
                    // first list already holds the match, so behaviour there is
                    // byte-for-byte unchanged.
                    val matchedKey = (freshSecrets.skKeys + secrets.skKeys).firstOrNull { key ->
                        val info = key.securityInfo ?: return@firstOrNull false
                        java.util.Base64.getDecoder().decode(info.credentialIdBase64).contentEquals(matchedCredId)
                    } ?: error("matched credId not found in DB after enumerate+attach")
                    // Log only credId byte length, not contents — credentialIds are sensitive hardware identifiers.
                    android.util.Log.d(TAG, "  SK auth: matched key=${matchedKey.id} credId=${matchedCredId.size}B")

                    val matchedSigner = ai.eight24family.conch.ssh.securitykey.DeferredCtapSkSigner(
                        credentialIdBase64 = matchedKey.securityInfo!!.credentialIdBase64,
                        application = matchedApp,
                        holder = sharedHolder,
                    )
                    val method = ai.eight24family.conch.ssh.securitykey.SkAuthPublickey(
                        publicKeyBlob = ai.eight24family.conch.ssh.securitykey.SkPublicKey.blobBytes(matchedKey),
                        algorithmName = ai.eight24family.conch.ssh.securitykey.SkPublicKey.algorithmName(matchedKey.type),
                        signer = matchedSigner,
                    )
                    try {
                        client.auth(server.username, listOf(method))
                    } finally {
                        if (!sharedHolder.signDone.isCompleted) {
                            sharedHolder.signDone.complete(Unit)
                        }
                    }
                } else {
                    val pem = secrets.privateKeyPem ?: error("private key required")
                    client.authPublickey(server.username, pickKeyProvider(pem, secrets.keyPassphrase))
                }
            }
        }
        } catch (t: Throwable) {
            // The SK-timeout branch above already disconnected before its
            // error(); disconnect() is idempotent, so covering every other
            // throw here (auth rejection, signer errors, interrupted waits)
            // is safe and closes the previously-leaked socket.
            SilentlyTry.fired("SshAi-Pool", "disconnect after failed auth") { client.disconnect() }
            throw t
        }
        // Auth succeeded — NOW the host key is worth remembering. Until this
        // line existed the pin was never written and every reconnect re-ran
        // "trust on first use" against a server it had already met.
        pinHostKeyIfUnset(server, hostKeyVerifier)
        return client
    }

    /**
     * TOFU verifier for the pooled paths.
     *
     * ⚠ IT MUST REMEMBER. This used to accept any key when [expected] was null
     * and then throw the fingerprint away, so the pin was never written and the
     * NEXT connect was a "first" connect again — logcat showed
     * "TOFU: accepting new host key for 203.0.113.10:22" on EVERY reconnect,
     * ~15/min during the 2026-08-16 reconnect storm. Trust-on-first-use that
     * never records the first use is not TOFU, it is trust-on-every-use: the
     * app displayed a "fingerprint" field it had no intention of checking.
     * [seenFingerprint] is what the caller pins after auth succeeds; [mismatch]
     * separates "the host key changed" from an ordinary connect failure so the
     * user gets told which one happened.
     */
    private class FingerprintHostKeyVerifier(private val expected: String?) : HostKeyVerifier {
        @Volatile var seenFingerprint: String? = null
        @Volatile var mismatch: Boolean = false

        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val fp = net.schmizz.sshj.common.SecurityUtils.getFingerprint(key)
            seenFingerprint = fp
            // Audit trail: log TOFU acceptance (first connect) and mismatches (potential MITM).
            return when (hostKeyVerdict(expected, fp)) {
                HostKeyVerdict.FIRST_USE -> {
                    android.util.Log.i(TAG, "TOFU: accepting new host key for $hostname:$port — fingerprint=$fp (pinning after auth)")
                    true
                }
                HostKeyVerdict.MATCH -> true
                HostKeyVerdict.MISMATCH -> {
                    android.util.Log.w(TAG, "host key mismatch for $hostname:$port — expected=$expected actual=$fp")
                    mismatch = true
                    false
                }
            }
        }
        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
    }

    /**
     * Record the host key we just talked to, if this server has no pin yet.
     *
     * AFTER a successful auth, never at verify() time: the fingerprint of a
     * handshake that then fails auth is worth nothing, and pinning it would let
     * one bad handshake poison every future connect. Auth completing is also
     * what makes the pin meaningful — an SSH signature covers the session id,
     * which is derived from this very host key, so a relay in the middle cannot
     * produce one.
     *
     * Fire-and-forget on IO: the connection is already usable and a DB write
     * must not sit in front of it. Idempotent — re-pinning the same value is a
     * no-op, so concurrent connects on the same server can't fight.
     */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    private fun pinHostKeyIfUnset(server: Server, verifier: FingerprintHostKeyVerifier) {
        if (server.knownHostKey != null) return
        val fp = verifier.seenFingerprint ?: return
        GlobalScope.launch(Dispatchers.IO) {
            SilentlyTry.fired(TAG, "pin host key") {
                ai.eight24family.conch.di.ServiceLocator.serverRepository
                    .updateKnownHostKey(server.id, fp)
                android.util.Log.i(TAG, "pinned host key for ${server.name}: $fp")
            }
        }
    }

    /**
     * Turn sshj's opaque "Could not verify host key" into something the user can
     * act on, and mark it as a reason NOT to auto-retry.
     *
     * A rotated host key is permanent until a human decides what it means, so
     * the reconnect ladder must not hammer the server about it — the phrase
     * "host key" is on [ai.eight24family.conch.ui.viewmodel.ChatViewModelReconnect.shouldAutoRetry]'s
     * do-not-retry list, and this is the message that carries it.
     */
    private fun hostKeyMismatchError(server: Server, actual: String?): Nothing = error(
        "Host key changed for ${server.host} — this is either the server being rebuilt/moved, " +
            "or someone sitting in the middle. Expected ${server.knownHostKey?.take(24)}, " +
            "got ${actual?.take(24)}. Nothing will connect until you decide: if you rebuilt it, " +
            "open the server → // system → fingerprint → forget, then reconnect."
    )

    /** What a presented host key means. [FIRST_USE] is an ACCEPT that the caller
     *  must then PIN — the distinction the pool used to lose, which turned
     *  trust-on-first-use into trust-on-every-use. */
    internal enum class HostKeyVerdict { FIRST_USE, MATCH, MISMATCH }

    companion object {
        private const val TAG = "SshAi-Pool"

        /** How old a pooled transport must be before a "a turn failed
         *  disconnected a moment ago" caller is allowed to evict it. Anything
         *  younger was built AFTER the failure (by the ephemeral reconnect or
         *  the service watchdog) and is therefore a rebuild, not the corpse.
         *  5 s covers the whole observed rebuild window — reconnect + hydrate
         *  measured 1.2–2.4 s on device — while a genuinely poisoned transport
         *  is normally minutes old, so this never blocks a real eviction. */
        const val EVICT_MIN_AGE_MS = 5_000L

        /** Pure eviction predicate — see [evictPoisoned]. Split out so the
         *  "never evict a transport younger than the failure" rule has a test
         *  that doesn't need a live SSH transport. */
        internal fun shouldEvictPoisoned(entryAgeMs: Long, minAgeMs: Long): Boolean =
            entryAgeMs >= minAgeMs

        /** Pure host-key decision — see [FingerprintHostKeyVerifier]. */
        internal fun hostKeyVerdict(expected: String?, actual: String): HostKeyVerdict = when {
            expected == null -> HostKeyVerdict.FIRST_USE
            expected == actual -> HostKeyVerdict.MATCH
            else -> HostKeyVerdict.MISMATCH
        }

        /** Unanswered `keepalive@openssh.com` requests before the transport is
         *  declared dead and closed (interval × misses = detection window;
         *  30 s default interval → ~90 s). Generous enough that Doze pauses —
         *  where the keepalive thread doesn't RUN, so nothing is "missed" —
         *  and slow networks never kill a healthy link. */
        private const val KEEPALIVE_MAX_MISSES = 3
    }
}
