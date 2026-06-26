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
    fun save(sessionId: String, bytes: ByteArray) {
        SilentlyTry.fired("SshAi-HistCache", "write session bytes") { file(sessionId).writeBytes(bytes) }
        SilentlyTry.fired("SshAi-HistCache", "index session after save") { ai.eight24family.conch.di.ServiceLocator.searchIndexer.indexSession(sessionId) }
    }

    /** Append new bytes (typically a tail fetched from the server). */
    fun append(sessionId: String, newBytes: ByteArray) {
        if (newBytes.isEmpty()) return
        SilentlyTry.fired("SshAi-HistCache", "append session bytes") { file(sessionId).appendBytes(newBytes) }
        SilentlyTry.fired("SshAi-HistCache", "index session after append") { ai.eight24family.conch.di.ServiceLocator.searchIndexer.indexSession(sessionId) }
    }

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
        try {
            f.outputStream().use { fos -> input.copyTo(fos, 64 * 1024) }
        } catch (t: Throwable) {
            android.util.Log.w("SshAi-HistCache", "stream session ${sessionId.take(8)} failed: ${t.message}")
            SilentlyTry.fired("SshAi-HistCache", "drop partial streamed file") { if (f.exists()) f.delete() }
            return 0L
        }
        if (f.length() == 0L) { f.delete(); return 0L }
        trimFileToLastNewline(f)
        if (f.length() == 0L) { f.delete(); return 0L }
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
        SilentlyTry.fired("SshAi-HistCache", "gc before delete") { System.gc() }
        SilentlyTry.fired("SshAi-HistCache", "delete after gc") { f.delete() }
        // Indexer's reconcile will drop this session next pass; eager
        // cleanup of the search rows happens through the indexer's
        // own reconcile, which is also kicked off by app start.
    }

    /** Current cached size on disk in bytes (0 if not present). */
    fun size(sessionId: String): Long = file(sessionId).length()

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
            val seenIds = HashSet<String>()
            val seenHash = HashSet<Int>()
            val out = StringBuilder(localLen + serverBytes.size)
            fun ingestLine(line: String) {
                if (line.isBlank()) return
                val id = extractLineId(line)
                if (id != null) {
                    if (!seenIds.add(id)) return
                } else {
                    if (!seenHash.add(line.hashCode())) return
                }
                out.append(line).append('\n')
            }
            // Stream both inputs line-by-line. Decoding the local (mmap)
            // buffer whole would allocate a CharBuffer sized to the ENTIRE
            // session (~57 MB for a 28 MB chat) — the same single allocation
            // that OOM-killed chat-open + indexing once "load all" started
            // caching big sessions (2026-05-29). forEachLine dups+rewinds
            // internally, so the caller's buffer position is untouched.
            // serverBytes is the freshly-fetched tail delta; wrap it so it
            // takes the same bounded path.
            ai.eight24family.conch.util.JsonlUtils.forEachLine(localBuffer) { ingestLine(it) }
            ai.eight24family.conch.util.JsonlUtils.forEachLine(
                java.nio.ByteBuffer.wrap(serverBytes)
            ) { ingestLine(it) }
            return out.toString().toByteArray(Charsets.UTF_8)
        } finally {
            localSnap?.close()
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
