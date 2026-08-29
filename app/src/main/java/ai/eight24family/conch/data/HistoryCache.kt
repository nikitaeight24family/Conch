package ai.eight24family.conch.data

import android.content.Context
import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.util.SilentlyTry
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicInteger

/**
 * Filesystem cache of raw JSONL bytes per CLI session id, so reopening a chat
 * paints the prior turns INSTANTLY before any SSH probing happens.
 *
 * Why a file and not DataStore: a real Claude session can be many MB of
 * stream-json (every tool call, every diff). DataStore values must stay tiny
 * (< 128 KiB) — way smaller than what we need to cache. Plain files in
 * `cacheDir` are right-sized for this and the OS reclaims them under pressure.
 *
 * Layout: `<cacheDir>/session_history/<safeId>.jsonl` — id stripped to
 * `[A-Za-z0-9._-]` so paths can't escape the dir or break on weird ids.
 *
 * The bytes saved here are always trimmed to a complete-line boundary
 * (last `\n`) — the caller relies on that to know how many bytes of the
 * remote file we've already absorbed, so the next tail-fetch picks up
 * exactly where we left off without re-feeding a half-line.
 *
 * **Drafts (issue #38)**. Brand-new chats have no CLI-side `resumeId` yet,
 * so a UserText queued while SSH is bootstrapping lives only in
 * `ChatViewModel._pending` — process death / VM reinit on back-and-return
 * loses it silently. Drafts persist that pending text to disk, keyed by
 * (serverId, agent), so a fresh VM can restore the queue. The draft is
 * cleared the moment the CLI hands back a real resumeId and the
 * conversation owns itself.
 */
class HistoryCache internal constructor(private val rootDir: File) {

    /** Production constructor — stores history under [Context.filesDir]
     * so Android **cannot reclaim it** under memory pressure. Earlier
     * versions used `cacheDir`, but the user explicitly asked for
     * durable local history. One-time migration in
     * [migrateFromCacheDirIfNeeded] folds any pre-existing cacheDir
     * contents into the new filesDir location so the cutover doesn't
     * drop existing chats. */
    constructor(context: Context) : this(File(context.filesDir, "session_history").also {
        migrateFromCacheDirIfNeeded(context, it)
    })

    private val dir: File by lazy { rootDir.apply { mkdirs() } }

    /**
     * Read-only view of a cached JSONL file. **AutoCloseable** (Durov
     * critique #6).
     *
     * **Memory-mapped, not heap-copied.** [buffer] is a `MappedByteBuffer`
     * over the on-disk file — pages fault in lazily as the parser walks
     * the bytes, and they're reclaimable by the OS under pressure. A
     * 24 MB session that used to allocate 24 MB of Java heap on every
     * open now costs zero heap (modulo a tiny direct-buffer header) and
     * is reclaimed automatically when the snapshot is GC'd.
     *
     * **Explicit close discipline.** Without `.close()`, the mapping
     * survives until the next GC reclaims the `MappedByteBuffer`. On
     * Android there is no reliable munmap from userspace (the
     * `sun.misc.Cleaner` path was sealed at API 28; `jdk.internal.ref.Cleaner`
     * is hidden behind module reflection), so eager close is best-effort:
     * we drop our last strong reference + try a reflective `cleaner.clean()`
     * via [MmapCleaner]. On API levels where that works the kernel
     * mapping is released immediately; on the rest, the next GC handles
     * it. Either way `close()` is cheap and idempotent, so callers
     * should always `.use { … }` — that way the upper-bound on
     * outstanding mappings is the call depth, not the GC schedule.
     *
     * Lifecycle: the underlying [FileChannel] is closed immediately
     * after the map call — mmap-ed regions survive channel close on
     * every platform we ship to (per `FileChannel.map` Javadoc).
     *
     * Concurrent rewrites: if `save()`/`append()` mutates the file
     * between `load()` and the caller's consumption, the buffer
     * shows whatever the OS page cache decides — but our writers go
     * through `writeBytes`/`appendBytes` (full-file replace or
     * end-append) and never overlap a load window in practice. Same
     * "snapshot at load time" semantics as the old `readBytes` path.
     */
    class Snapshot internal constructor(
        @Volatile private var mapped: MappedByteBuffer?,
        val cachedAt: Long,
    ) : AutoCloseable {

        /** Read-only view of the mmap region. Throws if [close] already ran —
         *  catching this is a bug, not a recovery path. */
        val buffer: ByteBuffer
            get() = mapped?.asReadOnlyBuffer()
                ?: error("Snapshot accessed after close")

        /** True when [close] hasn't been called yet. Cheap. */
        val isOpen: Boolean get() = mapped != null

        /** Releases the strong reference to the mapping and best-effort
         *  forces the kernel mapping closed via [MmapCleaner]. Idempotent.
         *  Safe to call from any thread; the volatile mapped slot guards
         *  the race. */
        override fun close() {
            val toClean = mapped ?: return
            mapped = null
            MmapCleaner.tryClean(toClean)
            outstandingMaps.decrementAndGet()
        }

        // Belt-and-braces safety net — if a caller forgets to .use{} the
        // snapshot, GC eventually reaches finalize which best-effort cleans.
        // Don't rely on this; it's a diagnostic backstop, not a strategy.
        protected fun finalize() {
            // Skip cleaner.clean() here — direct buffer's own NativeAllocationRegistry
            // already does the right thing on GC. We just drop our refcount.
            if (mapped != null) {
                mapped = null
                outstandingMaps.decrementAndGet()
            }
        }
    }

    /** Loads a memory-mapped view of the cached JSONL for [sessionId].
     *
     *  **Callers MUST `.use {}` the result** (or call `.close()` in a
     *  `finally`) — see [Snapshot] for why eager close matters on
     *  Android. Returns null on absence / empty / IO failure; the file
     *  channel is closed before this method returns regardless. */
    fun load(sessionId: String): Snapshot? = ai.eight24family.conch.util.Tracing.section(
        ai.eight24family.conch.util.Tracing.Names.HISTORY_CACHE_LOAD
    ) {
        val f = file(sessionId)
        if (!f.exists() || f.length() == 0L) return@section null
        val mtime = f.lastModified()
        try {
            RandomAccessFile(f, "r").use { raf ->
                raf.channel.use { ch ->
                    val len = ch.size()
                    if (len == 0L) null else {
                        val mapped = ch.map(FileChannel.MapMode.READ_ONLY, 0L, len)
                        outstandingMaps.incrementAndGet()
                        Snapshot(mapped, mtime)
                    }
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Replace the cache for [sessionId] entirely. */
    /**
     * ⚠ ATOMIC, NEVER IN PLACE. Readers hold this file MAPPED INTO MEMORY (the
     * windowed display parse, the search indexer). Rewriting it under them
     * shortens the mapping's backing, and the next page they touch kills the
     * process outright: SIGBUS / BUS_ADRERR, no exception, no dialog — the app
     * simply vanishes and comes back. That is what the user was seeing as, four
     * times in fifteen minutes on a 28 MB session (crash log, 2026-08-04), and
     * it took the whole in-memory state with it every time: which chat is
     * which, whether a turn is running.
     *
     * Writing a sibling and renaming over the name keeps the old inode alive
     * for anyone already reading it: their mapping stays valid to the last
     * byte, and new readers open the new file.
     */
    fun save(sessionId: String, bytes: ByteArray) {
        // A save REPLACES the body — a compaction merge, a verbatim re-adopt, a
        // local repair. The unread watermark is a byte offset into this very
        // file, so any rewrite moves the goalposts under it. Remember where the
        // file stood so [rebaseSeenAfterRewrite] can tell "the user had read to
        // the end and the file was re-laid-out" from "there is genuinely new
        // content". See the note on that method.
        val sizeBeforeRewrite = size(sessionId)
        SilentlyTry.fired("SshAi-HistCache", "write session bytes") {
            val target = file(sessionId)
            val tmp = java.io.File(target.parentFile, target.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                // Same directory, so a rename failure means something is badly
                // wrong; fall back rather than leave the cache missing.
                target.writeBytes(bytes)
                tmp.delete()
            }
        }
        if (sizeBeforeRewrite > 0L) rebaseSeenAfterRewrite(sessionId, sizeBeforeRewrite)
        // A save IS the complete remote body (compaction merge, verbatim
        // re-adopt, repair) — a leftover tail-base from an earlier tail-first
        // preload would shift every remote-offset computation off by its value.
        setBaseOffset(sessionId, 0L)
        SilentlyTry.fired("SshAi-HistCache", "index session after save") { ai.eight24family.conch.di.ServiceLocator.searchIndexer.indexSession(sessionId) }
    }

    /**
     * Apply OUR OWN `"entrypoint":"sdk-cli"` → `"entrypoint":"cli"` rewrite to
     * the cached copy, in place, and return the new size (null = nothing to do
     * or the rewrite failed).
     *
     * Why this exists: `listSessionsScript` performs exactly this substitution
     * on the SERVER so `claude --resume` can see conch sessions. It removes 4
     * bytes per tag, so the remote file SHRINKS — and the tail-poll used to
     * react to any shrink by downloading the entire file again to re-adopt it
     * verbatim. On a live 102 MB rollout, with the CLI writing fresh `sdk-cli`
     * tags every turn, that turned into a permanent re-download loop: measured
     * 3 GB pulled in ~4 hours against 10 MB sent (user, 2026-07-23 — it ate a
     * month of mobile data).
     *
     * The substitution is deterministic and byte-exact, so the SAME edit can be
     * made locally for free. The caller compares the returned size against the
     * server's; only an exact match lets it skip the download, so a real
     * compaction still takes the authoritative path.
     *
     * Streams line-by-line through a temp file — never holds the session in
     * RAM (a 134 MB rollout already OOM-killed one naive read, 2026-06-28).
     */
    fun rewriteEntrypointTags(sessionId: String): Long? {
        val f = file(sessionId)
        if (!f.exists() || f.length() == 0L) return null
        val tmp = java.io.File(f.parentFile, f.name + ".rw")
        return SilentlyTry.loggedOrElse("SshAi-HistCache", "local entrypoint rewrite", null) {
            var hit = false
            f.bufferedReader(Charsets.UTF_8).use { r ->
                tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                    while (true) {
                        val line = r.readLine() ?: break
                        val fixed = line.replace("\"entrypoint\":\"sdk-cli\"", "\"entrypoint\":\"cli\"")
                        if (fixed !== line) hit = true
                        w.write(fixed)
                        w.write("\n")
                    }
                }
            }
            if (!hit) {
                tmp.delete()
                return@loggedOrElse null
            }
            if (!tmp.renameTo(f)) {
                // Rename failed (same directory, so this should not happen).
                // ⚠ copyTo rewrites the SAME file readers may have mapped into
                // memory — the truncation that kills the process with SIGBUS —
                // so it is the last resort, not the normal path.
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
            SilentlyTry.fired("SshAi-HistCache", "index after local rewrite") {
                ai.eight24family.conch.di.ServiceLocator.searchIndexer.indexSession(sessionId)
            }
            f.length()
        }
    }

    /** Append new bytes (typically a tail fetched from the server). */
    fun append(sessionId: String, newBytes: ByteArray, liveActivity: Boolean = true) {
        if (newBytes.isEmpty()) return
        SilentlyTry.fired("SshAi-HistCache", "append session bytes") { file(sessionId).appendBytes(newBytes) }
        if (liveActivity) liveActivityMs[sessionId] = System.currentTimeMillis()
        SilentlyTry.fired("SshAi-HistCache", "index session after append") { ai.eight24family.conch.di.ServiceLocator.searchIndexer.indexSession(sessionId) }
    }

    /** sessionId → epoch ms of the last append that reflected LIVE agent
     * output (an open chat's tail-poll / the persistent stream), as opposed
     * to housekeeping we do to our own mirror. The home list's "working"
     * spinner keys on THIS, not the file mtime: the background catch-up
     * appends old content to cold sessions, and mtime-based "working" lit
     * the spinner on sessions finished 12+ hours earlier — one 90 s flash
     * per caught-up session, every sweep. In-memory only: after a restart no
     * session claims live activity it can't prove. */
    private val liveActivityMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Last LIVE append for [sessionId]; 0 when none this process run. */
    fun lastLiveActivityMs(sessionId: String): Long = liveActivityMs[sessionId] ?: 0L

    /**
     * Stream [input] straight to the cache file for [sessionId] in fixed
     * chunks — RAM stays flat regardless of session size. Replaces the body
     * entirely (like [save]). The old String-based prefetch
     * (copyTo→toByteArray→String→toByteArray) allocated 3-4× the file size and
     * OOM-killed the read on a ~134 MB rollout (256 MB heap cap). After the
     * write we trim any trailing partial line (scanning only the file tail,
     * not the whole file) and index. Returns post-trim byte length, 0 on
     * empty/failure (caller then leaves it uncached → retried next sweep).
     *
     * The caller is responsible for `proc.join()` AFTER this returns (this
     * drains stdout to EOF, same ordering as the old copyTo path).
     */
    fun saveFromStream(sessionId: String, input: java.io.InputStream): Long {
        val f = file(sessionId)
        // ⚠ SAME ATOMIC-RENAME DISCIPLINE AS [save] — this was the one writer
        // still rewriting the target IN PLACE (`f.outputStream()` truncates to
        // zero, then setLength trims), and it is exactly the crash the header
        // of [save] documents: the search indexer had the OLD copy mmap'd, the
        // prefetch streamed a fresh body over the same inode, the mapping's
        // backing shrank, and the next `DirectByteBuffer.get` died with SIGBUS.
        // Stream to a sibling, trim the SIBLING, rename over the target: old
        // readers keep a valid mapping to the last byte, new readers open the
        // new bytes.
        val tmp = java.io.File(f.parentFile, f.name + ".stream.tmp")
        try {
            tmp.outputStream().use { fos -> input.copyTo(fos, 64 * 1024) }
        } catch (t: Throwable) {
            android.util.Log.w("SshAi-HistCache", "stream session ${sessionId.take(8)} failed: ${t.message}")
            SilentlyTry.fired("SshAi-HistCache", "drop partial streamed tmp") { if (tmp.exists()) tmp.delete() }
            return 0L
        }
        if (tmp.length() == 0L) { tmp.delete(); return 0L }
        trimFileToLastNewline(tmp)
        if (tmp.length() == 0L) { tmp.delete(); return 0L }
        if (!tmp.renameTo(f)) {
            // Same directory — rename "can't" fail; the in-place copy is the
            // documented last resort, not the normal path (see [save]).
            SilentlyTry.fired("SshAi-HistCache", "fallback copy streamed tmp") {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        }
        // Same complete-body statement as [save] — see the base reset there.
        setBaseOffset(sessionId, 0L)
        SilentlyTry.fired("SshAi-HistCache", "index session after stream") {
            ai.eight24family.conch.di.ServiceLocator.searchIndexer.indexSession(sessionId)
        }
        return f.length()
    }

    /** Truncate [f] to its last `\n` — the file equivalent of
     *  [JsonlUtils.trimToLastNewline], but it reads only the tail (one block
     *  at a time, backwards) so a 100 MB file costs a few KB of RAM, not 100.
     *  No trailing newline at all ⇒ no complete line yet ⇒ empty the file
     *  (same "nothing complete yet" semantics as the ByteArray variant). */
    private fun trimFileToLastNewline(f: File) {
        try {
            val len = f.length()
            if (len == 0L) return
            val nl = '\n'.code.toByte()
            RandomAccessFile(f, "rw").use { raf ->
                val buf = ByteArray(8192)
                var pos = len
                var lastNl = -1L
                while (pos > 0 && lastNl < 0L) {
                    val readSize = minOf(buf.size.toLong(), pos).toInt()
                    pos -= readSize
                    raf.seek(pos)
                    raf.readFully(buf, 0, readSize)
                    var i = readSize - 1
                    while (i >= 0) {
                        if (buf[i] == nl) { lastNl = pos + i; break }
                        i--
                    }
                }
                when {
                    lastNl < 0L -> raf.setLength(0L)
                    lastNl + 1 < len -> raf.setLength(lastNl + 1)
                    // else: already ends exactly at a newline — leave as-is
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("SshAi-HistCache", "trim file ${f.name} failed: ${t.message}")
        }
    }

    // ───────────────────── session owner sidecar ─────────────────────
    //
    // The search index covers every session whose bytes are cached here.
    // Navigation from a search hit needs (serverId, agent, path) to open
    // the owning chat — but that mapping previously lived ONLY in the
    // volatile, preview-filtered SessionsCache, so hits on sessions that
    // SessionsCache didn't currently hold were a silent no-op. We persist
    // the owner durably right next to the bytes, written automatically by
    // whoever cached/listed the session (it always knows the server+agent
    // at that point). This survives FTS rebuilds and needs zero user action
    // — no "index" button.
    //
    // Layout: `<safeId>.owner` — single TSV line `serverId\tAGENT\tpath`.

    data class CachedOwner(
        val serverId: String,
        val agent: Agent,
        val path: String?,
        /** Server-side recency (RemoteSession.lastActiveAt) captured when the
         *  session was listed — the date we sort search results by. 0 when
         *  recorded by an old (pre-date) sidecar. */
        val lastActiveAt: Long = 0L,
    )

    /** Record (or refresh) the owner of [sessionId] — a durable, cumulative
     * log entry: "this session lived on (serverId, agent) at [path], last
     * active [lastActiveAt]". Written every time a server lists the session and
     * NEVER pruned when the server later compacts/deletes it, so a search hit
     * stays navigable forever. Latest-write-wins: serverId/agent/path are
     * stable per session and lastActiveAt only moves forward, so a plain
     * overwrite is correct. */
    fun recordOwner(sessionId: String, serverId: String, agent: Agent, path: String?, lastActiveAt: Long = 0L) {
        if (serverId.isBlank()) return
        SilentlyTry.fired("SshAi-HistCache", "record session owner") {
            ownerFile(sessionId).writeText(
                listOf(serverId, agent.name, path.orEmpty(), lastActiveAt.toString()).joinToString("\t"),
                Charsets.UTF_8,
            )
        }
    }

    /** Bulk owner record for a discovered (server, agent) session list. */
    fun recordOwners(serverId: String, agent: Agent, sessions: List<ai.eight24family.conch.agent.RemoteSession>) {
        for (s in sessions) recordOwner(s.id, serverId, agent, s.path, s.lastActiveAt)
    }

    /** Durable owner of [sessionId], or null if never recorded. Drives the
     *  search-hit → chat navigation (both index-time stamping and the
     *  tap-time fallback in AppNav). */
    fun owner(sessionId: String): CachedOwner? {
        val f = ownerFile(sessionId)
        if (!f.exists()) return null
        return SilentlyTry.logged("SshAi-HistCache", "read session owner") {
            val parts = f.readText(Charsets.UTF_8).split('\t')
            val serverId = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@logged null
            val agent = Agent.entries.firstOrNull { it.name == parts.getOrNull(1) } ?: return@logged null
            CachedOwner(
                serverId,
                agent,
                parts.getOrNull(2)?.takeIf { it.isNotBlank() },
                parts.getOrNull(3)?.toLongOrNull() ?: 0L,
            )
        }
    }

    private fun ownerFile(sessionId: String): File {
        val safe = sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(dir, "$safe.owner")
    }

    // ───────────────────── seen watermark sidecar ─────────────────────
    //
    // DURABLE half of the home "N new" badge. SessionSeenTracker's message
    // counts die with the process ("resets on restart, which is fine" — it
    // stopped being fine: a turn that ran while the app was dead showed no
    // unread, no working, no done-mark after relaunch; user 2026-08-10).
    // The watermark is the CACHED BODY SIZE (bytes) at the moment the user
    // last had the chat on screen — bytes because that's the one unit that
    // survives restarts on both sides (historyCache.size ↔ listing sizeBytes).

    /** Local mtime of the cached body — when OUR mirror last grew (open-chat
     *  poller or the background catch-up). The freshest "is this session
     *  being written RIGHT NOW" signal there is: no server clock skew, no
     *  listing lag. 0 when nothing is cached. */
    fun lastWriteMs(sessionId: String): Long = file(sessionId).lastModified()

    /** Cached-body byte size the user had seen at last view; null = never
     *  viewed (never badge a session the user hasn't opened at all). */
    fun seenBytes(sessionId: String): Long? {
        seenMemo[sessionId]?.let { return if (it == SEEN_NONE) null else it }
        val f = seenFile(sessionId)
        val v = if (!f.exists()) null else SilentlyTry.logged("SshAi-HistCache", "read seen watermark") {
            f.readText(Charsets.UTF_8).trim().toLongOrNull()
        }
        seenMemo[sessionId] = v ?: SEEN_NONE
        return v
    }

    /**
     * Watermark memo. The home list asks for every cached session's watermark on
     * every reload — 380 sessions on this phone, a tick every 2.5 s — so the
     * uncached version was ~380 `exists()` plus a `readText()` each, forever, in
     * a directory holding over a thousand files.
     *
     * "No watermark on disk" (a session never opened) is worth remembering just
     * as much as a number, and a ConcurrentHashMap cannot store null, hence the
     * sentinel. Every writer in this class goes through [writeSeen], and nothing
     * outside this process writes these files, so the memo cannot drift.
     */
    private val seenMemo = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Sentinel for "checked, and there is no watermark file". */
    private val SEEN_NONE = Long.MIN_VALUE

    /** The ONE place a watermark reaches disk — so the memo is never stale. */
    private fun writeSeen(sessionId: String, value: Long) {
        seenFile(sessionId).writeText(value.toString(), Charsets.UTF_8)
        seenMemo[sessionId] = value
    }

    /** Stamp the watermark. Monotonic — a stale writer (background collector
     *  of a chat the user already left) can't roll a fresher view back. */
    fun markSeenBytes(sessionId: String, bytes: Long) {
        SilentlyTry.fired("SshAi-HistCache", "write seen watermark") {
            val prev = seenBytes(sessionId) ?: -1L
            if (bytes > prev) writeSeen(sessionId, bytes)
        }
    }

    private fun seenFile(sessionId: String): File {
        val safe = sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(dir, "$safe.seen")
    }

    // ───────────────────── tail-base sidecar ─────────────────────
    //
    // TAIL-FIRST MIRRORING (Workstream C, 2026-08-17). A session bigger than
    // the prefetch cap used to stay entirely uncached — «loading» on open, no
    // search, no badge — because the only alternative was pulling the whole
    // rollout, and unbounded prefetch once pulled 3 GB in 4 hours. The middle
    // way is caching only the DISPLAY TAIL, which changes one axiom: local
    // byte 0 no longer equals remote byte 0.
    //
    // `<safeId>.base` holds that origin: the REMOTE byte offset at which the
    // local cache file begins. Absent = 0 (a complete-from-zero mirror, the
    // overwhelmingly common case — nothing changes for it). Every consumer
    // that turns a local length into a remote offset must add it:
    //   remoteOffset = baseOffset(id) + localLen
    // The .seen watermark stays LOCAL-relative on purpose (its consumers are
    // all local reads); a re-tail that moves the base rebases it in the same
    // breath — see [saveTail].

    /** Remote byte offset of local byte 0. 0 = complete mirror from the start
     *  of the remote file (also the answer for "no cache at all"). */
    fun baseOffset(sessionId: String): Long {
        val f = baseFile(sessionId)
        if (!f.exists()) return 0L
        return SilentlyTry.loggedOrElse("SshAi-HistCache", "read tail base", 0L) {
            f.readText(Charsets.UTF_8).trim().toLongOrNull() ?: 0L
        }
    }

    /** Record where the local file starts in remote coordinates. 0 deletes the
     *  sidecar — "no sidecar" and "complete mirror" are the same statement. */
    fun setBaseOffset(sessionId: String, base: Long) {
        SilentlyTry.fired("SshAi-HistCache", "write tail base") {
            val f = baseFile(sessionId)
            if (base <= 0L) f.delete() else f.writeText(base.toString(), Charsets.UTF_8)
        }
    }

    private fun baseFile(sessionId: String): File {
        val safe = sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(dir, "$safe.base")
    }

    /**
     * Adopt a TAIL slab as the entire cache: atomic replace (same sibling-tmp
     * discipline as [save] — readers hold the old file mmap'd), then move the
     * base and REBASE the seen watermark so the user's read position survives
     * the re-lay-out in remote coordinates:
     *   newSeenLocal = clamp(oldBase + oldSeenLocal − newBase, 0, newLen).
     * Without that, every re-tail of a big active session would either badge
     * the whole tail as unread (seen clamped low) or mark genuinely new bytes
     * read (seen left at its old local value pointing past them).
     *
     * [bytes] must already be whole-line trimmed at BOTH ends by the caller
     * (the slab starts mid-record; [newBase] must point at the first kept
     * byte). Never touches liveActivity — a background re-tail is mirror
     * housekeeping, not agent output (the 2026-08-17 fake-working rule).
     */
    fun saveTail(sessionId: String, bytes: ByteArray, newBase: Long) {
        if (bytes.isEmpty()) return
        val oldBase = baseOffset(sessionId)
        val oldSeen = seenBytes(sessionId)
        SilentlyTry.fired("SshAi-HistCache", "write tail slab") {
            val target = file(sessionId)
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                target.writeBytes(bytes)
                tmp.delete()
            }
        }
        setBaseOffset(sessionId, newBase)
        if (oldSeen != null) {
            val rebased = (oldBase + oldSeen - newBase).coerceIn(0L, bytes.size.toLong())
            // Direct write, not [markSeenBytes]: the LOCAL number may go DOWN
            // while naming the same remote position, and the monotonic guard
            // would (correctly, for its own callers) refuse that.
            SilentlyTry.fired("SshAi-HistCache", "rebase seen after re-tail") {
                writeSeen(sessionId, rebased)
            }
        }
        SilentlyTry.fired("SshAi-HistCache", "index session after tail save") {
            ai.eight24family.conch.di.ServiceLocator.searchIndexer.indexSession(sessionId)
        }
    }

    // ───────────────────── task-name sidecar ─────────────────────
    //
    // The chat's task board folds the DISPLAY window (~2 MB tail). On a long
    // session the TaskCreate calls scroll out of that window and only the
    // TaskUpdate {id, status} rows remain — the board degraded to "task #4".
    // The CLI never forgets because its list is server-side state. This
    // sidecar is our durable id→subject dictionary per session: every
    // subject the fold ever learns is recorded once and survives windowing
    // AND restarts.

    /** Known task subjects for [sessionId]: taskId → subject. */
    fun taskNames(sessionId: String): Map<String, String> {
        val f = taskNamesFile(sessionId)
        if (!f.exists()) return emptyMap()
        return SilentlyTry.loggedOrElse("SshAi-HistCache", "read task names", emptyMap()) {
            f.readLines(Charsets.UTF_8).mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) null else line.substring(0, tab) to line.substring(tab + 1)
            }.toMap()
        }
    }

    /** Merge-write [names] into the sidecar (latest subject wins per id). */
    fun recordTaskNames(sessionId: String, names: Map<String, String>) {
        if (names.isEmpty()) return
        SilentlyTry.fired("SshAi-HistCache", "write task names") {
            val merged = taskNames(sessionId) + names
            taskNamesFile(sessionId).writeText(
                merged.entries.joinToString("\n") { (id, subj) ->
                    "$id\t${subj.replace('\n', ' ').replace('\t', ' ')}"
                },
                Charsets.UTF_8,
            )
        }
    }

    private fun taskNamesFile(sessionId: String): File {
        val safe = sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(dir, "$safe.tasks")
    }

    /** Count MESSAGE records in the cached body beyond [fromBytes] — the durable
     *  "N new" for the home badge. Bounded to the last [cap] bytes so a giant
     *  backlog costs one small read, and never loads the whole file.
     *
     * ⚠ MESSAGES, NOT LINES. This counted every '\n', and a rollout line is not a
     * message: tool calls, tool results, system rows, the model-observed marker
     * and the CLI's own bookkeeping are all lines. One background metadata write
     * badged a session the user had read to the end — (2026-08-16). A record
     * counts only if it is a user or assistant turn.
     *
     *  Cheap on purpose: a substring test per line, no JSON parse. Both agents
     *  write the discriminator near the front of the record, and a false negative
     *  (an odd shape we don't recognise) under-counts the badge rather than
     *  inventing one — the failure this method is here to stop. */
    fun newLinesSince(sessionId: String, fromBytes: Long, cap: Long = 512 * 1024): Int {
        val f = file(sessionId)
        val len = f.length()
        if (len <= fromBytes) return 0
        return SilentlyTry.loggedOrElse("SshAi-HistCache", "count new messages", 0) {
            val start = maxOf(fromBytes, len - cap)
            var count = 0
            RandomAccessFile(f, "r").use { raf ->
                raf.seek(start)
                // Whole lines only: a clipped window starts mid-record, and half a
                // record is not a message. Skipping the partial first line also
                // removes the old "window clipped → at least 1" fudge, which
                // manufactured a badge out of nothing but file size.
                if (start > fromBytes) raf.readLine()
                while (true) {
                    val line = raf.readLine() ?: break
                    if (isMessageRecord(line)) count++
                }
            }
            count
        }
    }

    /** True for a rollout record that represents a turn the user would call a
     *  message. Everything else in a JSONL rollout — tool_use, tool_result,
     *  system rows, summaries, the model marker — is bookkeeping. */
    private fun isMessageRecord(line: String): Boolean {
        if (line.isEmpty()) return false
        // Claude rollout: {"type":"user"|"assistant", …}. Codex/Gemini wrap the
        // same idea one level down as {"payload":{"type":"message","role":…}}.
        if (line.contains("\"type\":\"assistant\"") || line.contains("\"type\":\"user\"")) {
            // A tool RESULT is carried inside a "user" record; it is not a message.
            return !line.contains("\"tool_use_id\"") && !line.contains("\"toolUseResult\"")
        }
        return line.contains("\"type\":\"message\"")
    }

    /**
     * The cached body was REWRITTEN rather than appended to (a CLI compaction
     * merged into our mirror) — re-stamp the seen watermark so the rewrite can
     * not read as unread.
     *
     * The watermark is a byte offset into a file we ourselves rewrite. When the
     * CLI compacts its rollout the app merges the old cache with the new server
     * body to keep the history, and the merged file is a different size for the
     * same conversation — measured on device: watermark 694851, merged file
     * 695605, so 754 bytes of re-laid-out OLD content became "new". If the user
     * had read to the end before the rewrite, they have read to the end after it.
     */
    fun rebaseSeenAfterRewrite(sessionId: String, oldSize: Long) {
        val seen = seenBytes(sessionId) ?: return
        if (seen < oldSize) return  // genuinely unread content existed — keep it
        SilentlyTry.fired("SshAi-HistCache", "rebase seen watermark after rewrite") {
            writeSeen(sessionId, size(sessionId))
        }
        android.util.Log.d(
            "SshAi-HistCache",
            "rebased seen watermark for ${sessionId.take(8)} after cache rewrite ($seen → ${size(sessionId)})",
        )
    }

    /** Every recorded owner sidecar, keyed by sessionId. Lets global search
     *  label a hit's server + agent even when [SessionsCache] (volatile,
     *  preview-filtered) no longer holds that session — the sidecar persists
     *  for every session ever listed/opened. Key is the on-disk (sanitized)
     *  id, which equals the sessionId for the uuid ids we deal with. */
    fun allOwners(): Map<String, CachedOwner> {
        val files = dir.listFiles() ?: return emptyMap()
        val out = HashMap<String, CachedOwner>()
        for (f in files) {
            if (!f.isFile || !f.name.endsWith(".owner")) continue
            SilentlyTry.logged("SshAi-HistCache", "read owner in allOwners") {
                val parts = f.readText(Charsets.UTF_8).split('\t')
                val serverId = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@logged
                val agent = Agent.entries.firstOrNull { it.name == parts.getOrNull(1) } ?: return@logged
                out[f.name.removeSuffix(".owner")] = CachedOwner(
                    serverId,
                    agent,
                    parts.getOrNull(2)?.takeIf { it.isNotBlank() },
                    parts.getOrNull(3)?.toLongOrNull() ?: 0L,
                )
            }
        }
        return out
    }

    fun forget(sessionId: String) {
        seenMemo.remove(sessionId)
        SilentlyTry.fired("SshAi-HistCache", "delete owner sidecar") { ownerFile(sessionId).delete() }
        val f = file(sessionId)
        if (!f.exists()) return
        if (f.delete()) return
        // On Windows, `File.delete()` refuses to remove a file that's
        // still memory-mapped by our own process — even after the
        // caller's `Snapshot.close()`, because [MmapCleaner]'s
        // reflective munmap may be blocked by the JDK 17 module
        // boundary (`jdk.internal.ref.Cleaner` is not exported).
        // GC reclaims the `MappedByteBuffer` and runs the registered
        // `NativeAllocationRegistry` cleaner, which calls the OS-
        // level munmap; once that's done, delete succeeds. Linux /
        // macOS / Android happily delete mapped files, so this is a
        // Windows-test-only path in practice, but it's also a
        // future-proof safety net.
        //
        // `System.gc()` is a HINT, not a guarantee, so a single attempt is a
        // coin flip — the repo's own HistoryCacheTest failed roughly one run in
        // three because of exactly that. Retry a bounded few times with a short
        // yield between them: deterministic in practice, still bounded, and it
        // protects the real app too if a Snapshot is ever leaked.
        repeat(5) { attempt ->
            SilentlyTry.fired("SshAi-HistCache", "gc before delete") { System.gc() }
            if (SilentlyTry.loggedOrElse("SshAi-HistCache", "delete after gc", false) { f.delete() } ||
                !f.exists()
            ) {
                return
            }
            SilentlyTry.fired("SshAi-HistCache", "yield before retry") { Thread.sleep(20L * (attempt + 1)) }
        }
        android.util.Log.w("SshAi-HistCache", "could not delete cached session file: ${f.name}")
        // Indexer's reconcile will drop this session next pass; eager
        // cleanup of the search rows happens through the indexer's
        // own reconcile, which is also kicked off by app start.
    }

    /** Current cached size on disk in bytes (0 if not present). */
    fun size(sessionId: String): Long = file(sessionId).length()

    /**
     * The last [maxLines] COMPLETE lines of the cached session, read by seeking
     * from the end — never loading the whole file. Empty when nothing is cached.
     *
     * Exists so the turn-state projection can run on the phone against bytes the
     * app has already paid to download, instead of asking the server to re-derive
     * them with `jq` (see [ai.eight24family.conch.agent.spec.AgentCliSpec.projectTurnStateRecords]).
     * A session line can be very large (a whole tool_result), so the read is
     * bounded by [maxBytes] as well; hitting that bound just yields fewer lines,
     * which the turn detector already tolerates.
     *
     * The first line of the returned list is guaranteed complete: whatever the
     * byte window cut in half is dropped.
     */
    fun tailLines(sessionId: String, maxLines: Int = 400, maxBytes: Long = 2L * 1024 * 1024): List<String> {
        val f = file(sessionId)
        val len = f.length()
        if (len <= 0L) return emptyList()
        return SilentlyTry.loggedOrElse("SshAi-HistCache", "read cached tail", emptyList()) {
            val take = minOf(len, maxBytes)
            val buf = ByteArray(take.toInt())
            java.io.RandomAccessFile(f, "r").use { raf ->
                raf.seek(len - take)
                raf.readFully(buf)
            }
            val text = String(buf, Charsets.UTF_8)
            // Drop a leading partial line whenever we started mid-file.
            val body = if (take < len) text.substringAfter('\n', "") else text
            body.lineSequence().filter { it.isNotBlank() }.toList().takeLast(maxLines)
        }
    }

    private fun file(sessionId: String): File {
        val safe = sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(dir, "$safe.jsonl")
    }

    // ───────────────────────── drafts (#38) ─────────────────────────

    /**
     * Queue of texts the user typed-and-sent on a brand-new chat
     * (no resumeId yet) that haven't reached the CLI because SSH was
     * still bootstrapping. Stored one-per-line; entries can't contain
     * newlines themselves because `send()` only buffers the final
     * `finalText` string which the caller built with `\n` separators
     * encoded as literal newlines — so we keep raw bytes and split
     * by a NUL-line separator that can't appear in user-typed text
     * or in any uploaded-file path string the buffer carries.
     */
    fun loadDrafts(serverId: String, agent: Agent): List<String> {
        val f = draftFile(serverId, agent)
        if (!f.exists() || f.length() == 0L) return emptyList()
        val raw = SilentlyTry.logged("SshAi-HistCache", "read drafts file") { f.readText(Charsets.UTF_8) } ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        return raw.split(DRAFT_SEPARATOR).filter { it.isNotEmpty() }
    }

    fun appendDraft(serverId: String, agent: Agent, text: String) {
        if (text.isEmpty()) return
        val f = draftFile(serverId, agent)
        SilentlyTry.fired("SshAi-HistCache", "append draft text") {
            val sep = if (f.exists() && f.length() > 0L) DRAFT_SEPARATOR else ""
            f.appendText(sep + text, Charsets.UTF_8)
        }
    }

    /** Wipe one specific text from the draft queue (e.g. after a successful
     *  `s.send(p.text)` drain). Removes only the first match — same text
     *  queued twice stays queued once. */
    fun removeDraft(serverId: String, agent: Agent, text: String) {
        val remaining = loadDrafts(serverId, agent).toMutableList()
        val idx = remaining.indexOf(text)
        if (idx < 0) return
        remaining.removeAt(idx)
        writeDrafts(serverId, agent, remaining)
    }

    fun clearDrafts(serverId: String, agent: Agent) {
        SilentlyTry.fired("SshAi-HistCache", "delete drafts file") { draftFile(serverId, agent).delete() }
    }

    private fun writeDrafts(serverId: String, agent: Agent, texts: List<String>) {
        val f = draftFile(serverId, agent)
        if (texts.isEmpty()) {
            SilentlyTry.fired("SshAi-HistCache", "delete empty drafts file") { f.delete() }
            return
        }
        SilentlyTry.fired("SshAi-HistCache", "write drafts file") { f.writeText(texts.joinToString(DRAFT_SEPARATOR), Charsets.UTF_8) }
    }

    private fun draftFile(serverId: String, agent: Agent): File {
        val safeServer = serverId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(dir, "draft-$safeServer-${agent.name}.txt")
    }

    /**
     * Merge [serverBytes] (full fetch of agent JSONL on the server)
     * into the current local cache for [sessionId]. Returns combined
     * bytes ready to be saved.
     *
     * Why: Claude / Codex auto-compact rewrites the JSONL with a
     * shorter file. User explicitly wants local kept as source of
     * truth, server is just a sync feed. Dedup is by JSONL-line id
     * (message.id / payload.id / uuid); lines without an extractable
     * id fall back to content-hash dedup.
     */
    /** Above this local size the dedup-merge is skipped (returns null → the
     *  caller keeps the existing file untouched). Rebuilding via StringBuilder
     *  + toByteArray peaks at ~5× the session size, so a huge session (e.g.
     *  one holding a 475 MB runaway tool dump) would OOM. Such sessions are
     *  historical/inert; the display + index paths already render/index them
     *  via the oversized-line truncation, so nothing is lost to the user. */
    private val MERGE_MAX_BYTES = 32 * 1024 * 1024

    fun mergeServer(sessionId: String, serverBytes: ByteArray): ByteArray? = ai.eight24family.conch.util.Tracing.section(
        ai.eight24family.conch.util.Tracing.Names.HISTORY_CACHE_MERGE
    ) {
        // `localSnap` is a mmap view of the on-disk JSONL; we close it
        // eagerly at the end of this method so the mapping doesn't
        // outlive the merge. (Durov #6.)
        val localSnap = load(sessionId)
        try {
            val localBuffer = localSnap?.buffer
            val localLen = localBuffer?.remaining() ?: 0
            if (localLen > MERGE_MAX_BYTES) {
                android.util.Log.w(
                    "SshAi-HistCache",
                    "merge skipped — local ${localLen}B exceeds ${MERGE_MAX_BYTES}B cap; keeping file as-is (no crash)"
                )
                return null
            }
            if (serverBytes.isEmpty()) {
                return if (localBuffer == null || localLen == 0) {
                    ByteArray(0)
                } else {
                    val arr = ByteArray(localLen)
                    localBuffer.duplicate().get(arr)
                    arr
                }
            }
            if (localBuffer == null || localLen == 0) return serverBytes
            // This path runs ONLY for a REAL compaction (server genuinely dropped
            // ids — the benign entrypoint-rewrite shrink is caught upstream by
            // serverContainsAllLocal and re-adopts the server verbatim). Keep the
            // dropped turns in CHRONOLOGICAL, LOSSLESS order: 1. local lines the
            // server no longer has = the OLDEST (dropped) turns → emitted first,
            // in local order; 2. then the server file verbatim = the
            // post-compaction truth (summary + recent), in server order. Dedup on
            // a PER-LINE key: the top-level `uuid` (unique per physical line), NOT
            // the bare message.id. One assistant turn writes its thinking /
            // tool_use / text blocks as SEPARATE lines that SHARE a message.id —
            // an id key collapsed a turn to its first block and DROPPED the rest
            // (the old lossy merge). uuid never collapses them. Streams
            // line-by-line (forEachLine dups+rewinds) so the mmap'd local is never
            // decoded whole — the 57MB CharBuffer OOM guard (2026-05-29).
            fun key(line: String): String =
                extractLineUuid(line) ?: extractLineId(line) ?: line.hashCode().toString()
            val serverKeys = HashSet<String>()
            ai.eight24family.conch.util.JsonlUtils.forEachLine(
                java.nio.ByteBuffer.wrap(serverBytes)
            ) { if (it.isNotBlank()) serverKeys.add(key(it)) }
            val emitted = HashSet<String>()
            val out = StringBuilder(localLen + serverBytes.size)
            ai.eight24family.conch.util.JsonlUtils.forEachLine(localBuffer) { line ->
                if (line.isBlank()) return@forEachLine
                val k = key(line)
                if (k in serverKeys) return@forEachLine  // present in server → emit from server below
                if (emitted.add(k)) out.append(line).append('\n')
            }
            ai.eight24family.conch.util.JsonlUtils.forEachLine(
                java.nio.ByteBuffer.wrap(serverBytes)
            ) { line ->
                if (line.isBlank()) return@forEachLine
                if (emitted.add(key(line))) out.append(line).append('\n')
            }
            return out.toString().toByteArray(Charsets.UTF_8)
        } finally {
            localSnap?.close()
        }
    }

    /**
     * True iff EVERY extractable line-id in the local cache is also present in
     * [serverBytes]. Used to tell a REAL Claude auto-compaction (server genuinely
     * dropped old turns → containment fails) apart from a benign file shrink that
     * kept all content — e.g. our own `sdk-cli`→`cli` entrypoint rewrite in
     * listSessionsScript (−4 bytes/tag), or a plain re-fetch. On the benign case
     * the caller must NOT run the lossy, offset-desyncing [mergeServer]; it should
     * re-adopt the server bytes verbatim (server is authoritative + complete).
     * Bounded by [MERGE_MAX_BYTES] and streamed line-by-line (RAM-flat); returns
     * false (→ conservative merge path) when local is absent or too large to scan.
     */
    fun serverContainsAllLocal(sessionId: String, serverBytes: ByteArray): Boolean {
        val localSnap = load(sessionId) ?: return false
        try {
            val localBuffer = localSnap.buffer ?: return false
            if (localBuffer.remaining() > MERGE_MAX_BYTES) return false
            val serverIds = HashSet<String>()
            ai.eight24family.conch.util.JsonlUtils.forEachLine(java.nio.ByteBuffer.wrap(serverBytes)) { line ->
                extractLineId(line)?.let { serverIds.add(it) }
            }
            var allPresent = true
            ai.eight24family.conch.util.JsonlUtils.forEachLine(localBuffer) { line ->
                if (!allPresent) return@forEachLine
                val id = extractLineId(line) ?: return@forEachLine
                if (id !in serverIds) allPresent = false
            }
            return allPresent
        } finally {
            localSnap.close()
        }
    }

    /**
     * DIAG (2026-06-13): does this session file hold duplicate COPIES of
     * the same history (blind-append bloat) rather than one merged log?
     * Returns (totalLines, uniqueById, bytes). uniqueById ≪ totalLines ⇒
     * the file is mostly copies → the source of the 1.3 GB. Gated caller
     * (Logx), bounded by MERGE_MAX_BYTES so it never scans a runaway file.
     */
    fun duplicationStats(sessionId: String): Triple<Int, Int, Long> {
        val f = file(sessionId)
        if (!f.exists() || f.length() == 0L || f.length() > MERGE_MAX_BYTES) {
            return Triple(0, 0, f.length().coerceAtLeast(0))
        }
        val snap = load(sessionId) ?: return Triple(0, 0, f.length())
        var total = 0
        val ids = HashSet<String>()
        try {
            ai.eight24family.conch.util.JsonlUtils.forEachLine(snap.buffer) { line ->
                if (line.isBlank()) return@forEachLine
                total++
                ids.add(extractLineId(line) ?: line.hashCode().toString())
            }
        } finally {
            snap.close()
        }
        return Triple(total, ids.size, f.length())
    }

    /** Per-PHYSICAL-LINE key: Claude stamps every rollout line with a unique
     *  top-level `uuid` (thinking / tool_use / text blocks of one assistant turn
     *  are separate lines with the SAME message.id but DISTINCT uuids). Used by
     *  the compaction merge so it never collapses a turn's blocks. First `uuid`
     *  match = the line's own (Claude writes it early, before any nested body). */
    private val uuidRe = Regex("\"uuid\"\\s*:\\s*\"([^\"]+)\"")
    private fun extractLineUuid(line: String): String? =
        uuidRe.find(line)?.groupValues?.get(1)

    /** Best-effort id extractor for one JSONL line. */
    private fun extractLineId(line: String): String? {
        val pats = listOf(
            Regex("\"message\"\\s*:\\s*\\{[^}]*?\"id\"\\s*:\\s*\"([^\"]+)\""),
            Regex("\"payload\"\\s*:\\s*\\{[^}]*?\"id\"\\s*:\\s*\"([^\"]+)\""),
            Regex("\"uuid\"\\s*:\\s*\"([^\"]+)\""),
            Regex("\"id\"\\s*:\\s*\"(msg_[A-Za-z0-9_]+(?:#\\d+)?)\""),
        )
        for (p in pats) {
            val m = p.find(line)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    /** All known session ids — drives global-search scan. */
    fun listSessionIds(): List<String> {
        val files = dir.listFiles() ?: return emptyList()
        return files.asSequence()
            .filter { it.isFile && it.name.endsWith(".jsonl") }
            .map { it.name.removeSuffix(".jsonl") }
            .toList()
    }

    /** Debug accessor: live count of outstanding [Snapshot]s. Should hover
     *  at single digits during normal use. Spiking past 20 means a caller
     *  forgot to `.use { }` and the GC is the only thing reclaiming mappings.
     *  Exposed only for the about-screen / perf dashboard. */
    fun outstandingMappingCount(): Int = outstandingMaps.get()

    private companion object {
        // NUL-bracketed separator: NUL bytes don't appear in valid UTF-8
        // user text or in any of the file paths the buffer carries.
        const val DRAFT_SEPARATOR = "  "

        /** Process-wide counter of live mappings. Read via
         *  [outstandingMappingCount]; written by [Snapshot.close] and
         *  [load]. Atomic so the counter is correct under concurrent
         *  load+close from indexer + chat open + merge. */
        val outstandingMaps = AtomicInteger(0)

        /** Move history files from legacy cacheDir to durable filesDir,
         *  once. No-op if no legacy files exist. */
        fun migrateFromCacheDirIfNeeded(context: Context, newDir: File) {
            val oldDir = File(context.cacheDir, "session_history")
            if (!oldDir.exists()) return
            newDir.mkdirs()
            val files = oldDir.listFiles() ?: return
            var moved = 0
            for (f in files) {
                val dst = File(newDir, f.name)
                if (dst.exists()) continue
                SilentlyTry.fired("SshAi-HistCache", "migrate history file") {
                    f.copyTo(dst, overwrite = false)
                    f.delete()
                    moved++
                }
            }
            if (moved > 0) {
                android.util.Log.i(
                    "SshAi-HistCache",
                    "migrated $moved history file(s) from cacheDir to filesDir",
                )
            }
            SilentlyTry.fired("SshAi-HistCache", "delete legacy cache dir") { oldDir.delete() }
        }
    }
}

/**
 * Best-effort reflective munmap of a [MappedByteBuffer]. Android sealed
 * the public `sun.misc.Cleaner` path at API 28 (the field exists but is
 * blocked by the hidden-API allowlist) and never exposed `jdk.internal.ref.Cleaner`
 * publicly, so on most current devices this is a no-op and the kernel
 * mapping is released by the buffer's `NativeAllocationRegistry` cleaner
 * when GC reaches it.
 *
 * We still try the reflection path because:
 *  1. Vendor JVMs (Samsung, Huawei OEM forks) sometimes ship a less
 *     locked-down hidden-API allowlist and the call goes through.
 *  2. When it DOES work the mapping is released within microseconds of
 *     the snapshot's `close()`, slashing the worst-case "80 sessions
 *     during reconcile" virtual address space high-water mark.
 *  3. The reflection cost is one method-handle lookup + invocation —
 *     trivial compared to the syscall it amortises.
 *
 * Failures are swallowed silently — there is no recovery action, and the
 * GC fallback is already in place.
 */
private object MmapCleaner {

    // Cached reflection handles. Set on first successful resolution;
    // null means "we tried and either the API surface isn't there or
    // it's blocked". @Volatile so the first thread's discovery is
    // visible to everyone else without locking.
    @Volatile private var cleanerMethod: java.lang.reflect.Method? = null
    @Volatile private var cleanMethod: java.lang.reflect.Method? = null
    @Volatile private var resolved = false

    fun tryClean(buffer: MappedByteBuffer) {
        if (!resolved) resolve(buffer)
        val cm = cleanerMethod ?: return
        val clean = cleanMethod ?: return
        try {
            val cleaner = cm.invoke(buffer) ?: return
            clean.invoke(cleaner)
        } catch (_: Throwable) {
            // Hidden-API access denied, cleaner already ran, anything
            // else — let GC pick it up. The mapping is still safe to
            // read until then; we just lose the eager-release win.
        }
    }

    @Synchronized
    private fun resolve(buffer: MappedByteBuffer) {
        if (resolved) return
        try {
            // `cleaner()` lives on `DirectByteBuffer` (the runtime class
            // of `MappedByteBuffer` on every JVM we know). It's a no-arg
            // method returning either `sun.misc.Cleaner` (pre-9 / older
            // Android) or `jdk.internal.ref.Cleaner` (post-9).
            val cm = buffer.javaClass.getDeclaredMethod("cleaner").also {
                it.isAccessible = true
            }
            val sampleCleaner = cm.invoke(buffer)
            if (sampleCleaner != null) {
                val clean = sampleCleaner.javaClass.getMethod("clean").also {
                    it.isAccessible = true
                }
                cleanerMethod = cm
                cleanMethod = clean
            }
        } catch (_: Throwable) {
            // Reflection not available or blocked. Fine — GC handles it.
        }
        resolved = true
    }
}
