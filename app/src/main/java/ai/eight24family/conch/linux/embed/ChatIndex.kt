package ai.eight24family.conch.linux.embed

import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.linux.chat.LocalChatStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Search your own conversations by MEANING, on the phone.
 *
 * ⛔ WHY THIS AND NOT A KEYWORD SEARCH. A local chat archive is where the
 * answer to "how did we un-brick that phone" lives, and the words in the
 * question are never the words in the answer — the chat says "fastboot" and
 * the memory says. Measured on the owner's phone with the model this ships
 * with (bge-m3): a Russian question put both the Russian and the ENGLISH
 * answer at the top (0.535 / 0.443) with everything unrelated at 0.38 and
 * below. Substring matching cannot do that in any language, let alone across
 * two.
 *
 * ── THE INDEX IS A FILE, AND IT KNOWS WHOSE VECTORS IT HOLDS ──
 *
 * One flat binary under `filesDir/llm/embed/`, stamped with the model id and
 * the dimension it was built with. A different embedder produces vectors that
 * mean nothing to these ones (384 dims against 1024, and different geometry
 * even at equal size), so the stamp is checked on load and a mismatched index
 * is DISCARDED rather than searched — a wrong answer with a confident score
 * is worse than no answer.
 *
 * Nothing leaves the device, and nothing is indexed unless the owner asks:
 * embedding an archive is minutes of CPU, and a background surprise that
 * warms the phone is exactly what this app does not do.
 */
object ChatIndex {

    private const val TAG = "Conch-Embed"
    private const val MAGIC = 0x43494458 // "CIDX"

    data class Entry(
        val chatId: String,
        val modelId: String,
        /** Which message in that chat, for opening it where the hit is. */
        val msgIndex: Int,
        val role: String,
        /** Enough of the text to recognise the hit in a list. */
        val snippet: String,
        val vector: FloatArray,
    )

    data class Hit(val entry: Entry, val score: Float, val chatTitle: String)

    data class Status(
        val entries: Int,
        val chats: Int,
        val modelId: String?,
        val dim: Int,
        val builtAtMs: Long,
    ) {
        val isEmpty: Boolean get() = entries == 0
    }

    private val _status = MutableStateFlow(Status(0, 0, null, 0, 0L))
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Live progress of a build: done/total messages, or null when idle. */
    private val _progress = MutableStateFlow<Pair<Int, Int>?>(null)
    val progress: StateFlow<Pair<Int, Int>?> = _progress.asStateFlow()

    @Volatile private var loaded = false
    @Volatile private var entries: List<Entry> = emptyList()

    private fun dir(): File =
        File(ServiceLocator.appContext.filesDir, "llm/embed").apply { mkdirs() }

    private fun file(): File = File(dir(), "chats.idx")

    // ── on disk ──

    private fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            entries = runCatching { readFile() }.getOrElse {
                android.util.Log.i(TAG, "index unreadable, starting empty: ${it.message}")
                emptyList()
            }
            loaded = true
            publish()
        }
    }

    private fun readFile(): List<Entry> {
        val f = file()
        if (!f.isFile) return emptyList()
        DataInputStream(f.inputStream().buffered()).use { d ->
            if (d.readInt() != MAGIC) return emptyList()
            val version = d.readInt()
            if (version != 1) return emptyList()
            val modelId = d.readUTF()
            val dim = d.readInt()
            val builtAt = d.readLong()
            val count = d.readInt()
            if (dim !in 1..8192 || count < 0 || count > 1_000_000) return emptyList()
            builtModel = modelId
            builtDim = dim
            builtAtMs = builtAt
            val out = ArrayList<Entry>(count)
            repeat(count) {
                val chatId = d.readUTF()
                val chatModel = d.readUTF()
                val idx = d.readInt()
                val role = d.readUTF()
                val snippet = d.readUTF()
                val v = FloatArray(dim) { d.readFloat() }
                out += Entry(chatId, chatModel, idx, role, snippet, v)
            }
            return out
        }
    }

    @Volatile private var builtModel: String? = null
    @Volatile private var builtDim: Int = 0
    @Volatile private var builtAtMs: Long = 0L

    private fun writeFile(modelId: String, dim: Int, list: List<Entry>) {
        runCatching {
            DataOutputStream(file().outputStream().buffered()).use { o ->
                o.writeInt(MAGIC)
                o.writeInt(1)
                o.writeUTF(modelId)
                o.writeInt(dim)
                o.writeLong(System.currentTimeMillis())
                o.writeInt(list.size)
                list.forEach { e ->
                    o.writeUTF(e.chatId)
                    o.writeUTF(e.modelId)
                    o.writeInt(e.msgIndex)
                    o.writeUTF(e.role)
                    o.writeUTF(e.snippet)
                    e.vector.forEach { o.writeFloat(it) }
                }
            }
            builtModel = modelId
            builtDim = dim
            builtAtMs = System.currentTimeMillis()
        }.onFailure { android.util.Log.w(TAG, "could not write index: ${it.message}") }
    }

    private fun publish() {
        _status.value = Status(
            entries = entries.size,
            chats = entries.map { it.chatId }.distinct().size,
            modelId = builtModel,
            dim = builtDim,
            builtAtMs = builtAtMs,
        )
    }

    fun ensureLoaded() = load()

    fun clear() {
        synchronized(this) {
            entries = emptyList()
            builtModel = null
            builtDim = 0
            builtAtMs = 0L
            runCatching { file().delete() }
            loaded = true
            publish()
        }
    }

    // ── building ──

    sealed interface Build {
        data class Done(val entries: Int, val chats: Int) : Build
        data object NothingToIndex : Build
        data object NoModel : Build
        data class Failed(val reason: String) : Build
    }

    /**
     * Embed every message of every stored chat.
     *
     * Rebuilds from scratch on purpose: a chat archive on a phone is
     * hundreds of messages, not millions, and an incremental index that
     * quietly disagrees with the transcripts is a worse bug than a minute of
     * CPU. The progress flow is what the row shows meanwhile.
     */
    suspend fun build(): Build = withContext(Dispatchers.IO) {
        load()
        val ready = LocalEmbedEngine.ensureUp()
        val modelId = when (ready) {
            is LocalEmbedEngine.Ready.Ok -> ready.modelId
            LocalEmbedEngine.Ready.NoModel -> return@withContext Build.NoModel
            is LocalEmbedEngine.Ready.Failed -> return@withContext Build.Failed(ready.reason)
        }
        LocalChatStore.reload()
        val metas = LocalChatStore.chats.value
        // (chatId, modelId, index, role, text) for everything worth searching.
        val units = metas.flatMap { meta ->
            val chat = LocalChatStore.load(meta.id) ?: return@flatMap emptyList()
            chat.msgs.flatMapIndexed { i, m ->
                // A long answer holds several topics, and the tail is exactly
                // where the specific detail usually is - so it is CHUNKED, not
                // truncated. Split on whitespace so a chunk is words.
                chunk(m.text.trim()).map { part ->
                    Quint(meta.id, chat.modelId, i, m.role, part)
                }
            }
        }
        android.util.Log.i(TAG, "indexing ${units.size} messages from ${metas.size} chats")
        if (units.isEmpty()) return@withContext Build.NothingToIndex
        val out = ArrayList<Entry>(units.size)
        var dim = 0
        _progress.value = 0 to units.size
        try {
            units.chunked(LocalEmbedApi.BATCH).forEach { batch ->
                when (val r = LocalEmbedApi.embed(batch.map { it.text })) {
                    is LocalEmbedApi.Result.Failed -> {
                        android.util.Log.w(TAG, "index build stopped: ${r.reason}")
                        return@withContext Build.Failed(r.reason)
                    }
                    is LocalEmbedApi.Result.Ok -> {
                        if (r.vectors.size != batch.size) {
                            return@withContext Build.Failed("embedder returned the wrong count")
                        }
                        batch.forEachIndexed { i, u ->
                            val v = r.vectors[i]
                            if (dim == 0) dim = v.size
                            out += Entry(
                                chatId = u.chatId,
                                modelId = u.modelId,
                                msgIndex = u.index,
                                role = u.role,
                                snippet = u.text.replace(Regex("\\s+"), " ").take(160),
                                vector = v,
                            )
                        }
                        _progress.value = out.size to units.size
                    }
                }
            }
        } finally {
            _progress.value = null
        }
        synchronized(this) {
            entries = out
            writeFile(modelId, dim, out)
            publish()
        }
        android.util.Log.i(
            TAG,
            "indexed ${out.size} messages from ${metas.size} chats with $modelId (dim $dim)",
        )
        Build.Done(out.size, metas.size)
    }

    /** Pieces of at most [LocalEmbedApi.MAX_CHARS], split on whitespace, with
     *  anything too short to carry meaning dropped. */
    internal fun chunk(text: String): List<String> {
        val t = text.trim()
        if (t.length < 8) return emptyList()
        if (t.length <= LocalEmbedApi.MAX_CHARS) return listOf(t)
        val out = ArrayList<String>()
        var i = 0
        while (i < t.length) {
            var end = (i + LocalEmbedApi.MAX_CHARS).coerceAtMost(t.length)
            if (end < t.length) {
                // Step back to the last space so a chunk does not end
                // mid-word; give up after a quarter of the window (a wall of
                // base64 has no spaces and is better cut hard than skipped).
                val minEnd = i + LocalEmbedApi.MAX_CHARS * 3 / 4
                val space = t.lastIndexOf(' ', end - 1)
                if (space > minEnd) end = space
            }
            val piece = t.substring(i, end).trim()
            if (piece.length >= 8) out += piece
            i = end
        }
        return out
    }

    private data class Quint(
        val chatId: String,
        val modelId: String,
        val index: Int,
        val role: String,
        val text: String,
    )

    // ── searching ──

    sealed interface Found {
        data class Ok(val hits: List<Hit>) : Found
        data object NotIndexed : Found
        /** The index was built by a different embedder — it cannot be trusted
         *  against this one's vectors, and says so instead of guessing. */
        data object StaleModel : Found
        data class Failed(val reason: String) : Found
    }

    suspend fun search(query: String, top: Int = 12): Found = withContext(Dispatchers.IO) {
        load()
        if (entries.isEmpty()) return@withContext Found.NotIndexed
        val ready = LocalEmbedEngine.ensureUp()
        val modelId = when (ready) {
            is LocalEmbedEngine.Ready.Ok -> ready.modelId
            LocalEmbedEngine.Ready.NoModel -> return@withContext Found.NotIndexed
            is LocalEmbedEngine.Ready.Failed -> return@withContext Found.Failed(ready.reason)
        }
        if (builtModel != null && builtModel != modelId) return@withContext Found.StaleModel
        val qv = when (val r = LocalEmbedApi.embed(listOf(query))) {
            is LocalEmbedApi.Result.Failed -> return@withContext Found.Failed(r.reason)
            is LocalEmbedApi.Result.Ok -> r.vectors.firstOrNull()
                ?: return@withContext Found.Failed("no vector for the query")
        }
        val titles = LocalChatStore.chats.value.associate { it.id to it.title }
        // A phone archive is thousands of vectors at most: a full scan is
        // microseconds and needs no index structure to go stale.
        val hits = entries.asSequence()
            .map { e -> Hit(e, LocalEmbedApi.cosine(qv, e.vector), titles[e.chatId] ?: "chat") }
            .filter { it.score > 0.25f }
            .sortedByDescending { it.score }
            // One hit per chat: ten messages from the same conversation is
            // one answer, not ten.
            .distinctBy { it.entry.chatId }
            .take(top)
            .toList()
        Found.Ok(hits)
    }
}
