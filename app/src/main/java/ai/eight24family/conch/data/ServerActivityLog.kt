package ai.eight24family.conch.data

import ai.eight24family.conch.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-server log of every shell command the app ran on the server.
 * Powers two product needs:
 *
 *  1. **Play-Store transparency** — the listing in `OperationsScreen`
 *     enumerates the categories of commands; this log makes those
 *     categories CONCRETE per-server. A user (or a reviewer) can
 *     see exactly what ran and when. No commands are issued behind
 *     the user's back.
 *
 *  2. **Debugging diagnostic** — when something doesn't behave, the
 *     log shows what the app actually told the server, what came
 *     back, and how long it took. Triage without adb logcat.
 *
 * **Persistence (user-requested 2026-05-30):** entries are kept in an
 * in-memory ring buffer per server (fast reads via [StateFlow]) AND
 * mirrored to disk so they survive app restarts — the user explicitly
 * wants the history retained, not wiped on close. Storage layout:
 *
 *   `<filesDir>/activity-log/<serverId>.jsonl`  — one JSON [Entry] per
 *   line, append-on-write (each command hits disk immediately, so a
 *   hard kill loses nothing). The file is compacted back to the last
 *   [MAX_ENTRIES] once it grows past [COMPACT_BYTES]. On first
 *   [observe]/[append] for a server we lazily load the file into the
 *   flow. `clear` wipes both memory and disk. `DataEraser` deletes the
 *   whole `activity-log/` directory on GDPR erase.
 *
 * **Credential safety:** by design no credential VALUES ever appear in
 * a command string or its output (tokens stay server-side; detection is
 * presence-only). As defense-in-depth before anything is stored we run
 * [redact] over the command + output to strip obvious secret shapes
 * (Bearer tokens, `sk-…`/`AIza…` keys, `key=value` secrets, PEM private
 * keys) so they can never end up on disk.
 *
 * Ring buffer per serverId: oldest entry drops when [MAX_ENTRIES] is
 * exceeded. All disk I/O is off the caller's thread (a private IO
 * scope) and guarded so a missing/unavailable filesystem degrades to
 * in-memory-only rather than crashing.
 */
object ServerActivityLog {
    const val MAX_ENTRIES = 500

    /** Compact the on-disk JSONL once it grows past this (≈ a few full
     *  rings of entries) — rewrite keeping only the last [MAX_ENTRIES]. */
    private const val COMPACT_BYTES = 1_500_000L

    /**
     * One executed command + its outcome on a specific server.
     *
     * @param ts wall-clock ms when the command started
     * @param category one of "probe", "install", "run", "file", "auth",
     *  "diag" — drives badge colour in the UI. Must stay in sync with
     *  the categories enumerated in [OperationsScreen].
     * @param command the actual command line, truncated to ~600 chars.
     *  Secret shapes are redacted in [append]; prompts are kept verbatim
     *  — the user typed them, they're in their own chat history anyway.
     * @param exitCode -1 means "still running / never returned"; ≥0 is
     *  the real exit status from the SSH channel.
     * @param stdoutTail last ~200 chars of combined stdout+stderr.
     *  Truncated head-side; the tail is what matters for diagnosis.
     * @param durationMs how long the channel was open. Null if the
     *  command was fire-and-forget or never returned.
     */
    @Serializable
    data class Entry(
        val ts: Long,
        val category: String,
        val command: String,
        val exitCode: Int = -1,
        val stdoutTail: String = "",
        val durationMs: Long? = null,
    )

    private val buffers = ConcurrentHashMap<String, MutableStateFlow<List<Entry>>>()

    /** serverIds whose disk file has been loaded (or is loading) into the
     *  flow — so we only hit disk once per server per process. */
    private val loaded = ConcurrentHashMap.newKeySet<String>()

    /** Per-server file lock — serialises append/compact/delete so two
     *  coroutines never interleave writes to the same JSONL. */
    private val fileLocks = ConcurrentHashMap<String, Mutex>()

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ──────────────────────────────────────────────────────────────
    // Public API (signatures unchanged — callers don't need to change)
    // ──────────────────────────────────────────────────────────────

    /** Live snapshot of entries for [serverId], newest LAST. UI is
     *  expected to reverse for display. Lazily loads disk history the
     *  first time a server is observed. */
    fun observe(serverId: String): StateFlow<List<Entry>> {
        val flow = buffers.computeIfAbsent(serverId) { MutableStateFlow(emptyList()) }
        ensureLoaded(serverId, flow)
        return flow.asStateFlow()
    }

    /** Append [entry] to [serverId]'s ring AND persist it to disk. Drops
     *  oldest in memory if size ≥ [MAX_ENTRIES]. Thread-safe; callable
     *  from any coroutine. Secret shapes are redacted before storing. */
    fun append(serverId: String, entry: Entry) {
        val safe = entry.copy(
            command = redact(entry.command),
            stdoutTail = redact(entry.stdoutTail),
        )
        val flow = buffers.computeIfAbsent(serverId) { MutableStateFlow(emptyList()) }
        ensureLoaded(serverId, flow)
        flow.update { cur ->
            (cur + safe).let { if (it.size > MAX_ENTRIES) it.takeLast(MAX_ENTRIES) else it }
        }
        ioScope.launch { appendDisk(serverId, safe) }
    }

    /** Wipe one server's log — memory AND disk. Called from the Activity
     *  log "Clear" affordance. Leaves other servers alone. */
    fun clear(serverId: String) {
        buffers[serverId]?.value = emptyList()
        ioScope.launch {
            lockFor(serverId).withLock { runCatching { fileFor(serverId)?.delete() } }
        }
    }

    /** Convenience: time + run an SSH `exec`, record both ends.
     *  Caller passes the actual exec lambda; we wrap timing + result
     *  capture. The lambda should return the stdout string (or null
     *  on failure); throwing is also caught and logged as rc=-1. */
    inline fun timed(
        serverId: String,
        category: String,
        command: String,
        block: () -> String?,
    ): String? {
        val start = System.currentTimeMillis()
        return try {
            val out = block()
            append(
                serverId,
                Entry(
                    ts = start,
                    category = category,
                    command = command.take(600),
                    exitCode = if (out != null) 0 else -1,
                    stdoutTail = (out ?: "").takeLast(200),
                    durationMs = System.currentTimeMillis() - start,
                ),
            )
            out
        } catch (t: Throwable) {
            append(
                serverId,
                Entry(
                    ts = start,
                    category = category,
                    command = command.take(600),
                    exitCode = -1,
                    stdoutTail = ("THROW: " + (t.message ?: t.javaClass.simpleName)).takeLast(200),
                    durationMs = System.currentTimeMillis() - start,
                ),
            )
            null
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Disk persistence
    // ──────────────────────────────────────────────────────────────

    private fun lockFor(serverId: String): Mutex =
        fileLocks.computeIfAbsent(serverId) { Mutex() }

    /** `<filesDir>/activity-log/` — created on demand. Null (→ in-memory
     *  only) if the app context isn't available yet (e.g. unit tests). */
    private fun baseDir(): File? = runCatching {
        File(ServiceLocator.appContext.filesDir, "activity-log").apply { mkdirs() }
    }.getOrNull()

    private fun fileFor(serverId: String): File? {
        val dir = baseDir() ?: return null
        val safe = buildString {
            for (c in serverId) append(if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_')
        }.ifBlank { "default" }.take(128)
        return File(dir, "$safe.jsonl")
    }

    /** Load this server's persisted history into [flow] exactly once. The
     *  disk entries (older) are merged ahead of anything appended this
     *  session, then sorted by timestamp so order is stable regardless of
     *  write/scheduling races. */
    private fun ensureLoaded(serverId: String, flow: MutableStateFlow<List<Entry>>) {
        if (!loaded.add(serverId)) return
        ioScope.launch {
            val disk = lockFor(serverId).withLock { readDisk(serverId) }
            if (disk.isNotEmpty()) {
                flow.update { cur -> (disk + cur).sortedBy { it.ts }.takeLast(MAX_ENTRIES) }
            }
        }
    }

    private fun readDisk(serverId: String): List<Entry> {
        val f = fileFor(serverId) ?: return emptyList()
        if (!f.exists()) return emptyList()
        return runCatching {
            f.readLines().mapNotNull { line ->
                if (line.isBlank()) null
                else runCatching { json.decodeFromString(Entry.serializer(), line) }.getOrNull()
            }.takeLast(MAX_ENTRIES)
        }.getOrElse { emptyList() }
    }

    private suspend fun appendDisk(serverId: String, entry: Entry) {
        val f = fileFor(serverId) ?: return
        lockFor(serverId).withLock {
            runCatching {
                f.appendText(json.encodeToString(Entry.serializer(), entry) + "\n")
                if (f.length() > COMPACT_BYTES) compact(f)
            }
        }
    }

    /** Rewrite [f] keeping only the last [MAX_ENTRIES] entries. Read fully
     *  into memory first, then truncate-write — safe under the per-server
     *  lock (no concurrent reader/writer for this file). */
    private fun compact(f: File) {
        runCatching {
            val kept = f.readLines().mapNotNull { line ->
                if (line.isBlank()) null
                else runCatching { json.decodeFromString(Entry.serializer(), line) }.getOrNull()
            }.takeLast(MAX_ENTRIES)
            f.writeText(kept.joinToString("") { json.encodeToString(Entry.serializer(), it) + "\n" })
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Secret redaction (defense-in-depth — normally a no-op)
    // ──────────────────────────────────────────────────────────────

    private const val MARK = "[redacted]"

    private val pemBlock =
        Regex("(?i)-----BEGIN[^-]*PRIVATE KEY-----[\\s\\S]*?-----END[^-]*PRIVATE KEY-----")
    private val shapePatterns: List<Pair<Regex, String>> = listOf(
        Regex("(?i)bearer\\s+[A-Za-z0-9._\\-]{8,}") to "Bearer $MARK",
        Regex("sk-[A-Za-z0-9_\\-]{16,}") to "sk-$MARK",
        Regex("AIza[A-Za-z0-9_\\-]{16,}") to "AIza$MARK",
    )
    // `key=value` / `key: value` for sensitive key names — keep the key,
    // strip the value. Bearer/Authorization headers are covered above.
    private val kvSecret =
        Regex("(?i)\\b(api[_-]?key|token|secret|password|passwd)(\\s*[=:]\\s*)([^\\s\"']{4,})")

    private fun redact(s: String): String {
        if (s.isEmpty()) return s
        var r = pemBlock.replace(s, "[private-key $MARK]")
        for ((re, repl) in shapePatterns) r = re.replace(r, repl)
        r = kvSecret.replace(r) { m -> m.groupValues[1] + m.groupValues[2] + MARK }
        return r
    }
}
