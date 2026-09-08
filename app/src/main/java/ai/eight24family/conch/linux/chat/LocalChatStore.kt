package ai.eight24family.conch.linux.chat

import ai.eight24family.conch.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Where the phone's own chats live: one directory per chat under
 * `filesDir/llm/chats/`, holding its transcript and the pictures it showed
 * the model.
 *
 * ⛔ ONE DIRECTORY PER CHAT, NOT ONE FILE. A chat with photos in it owns
 * those bytes: keeping them beside the transcript makes deleting a chat a
 * single `deleteRecursively()` with no orphaned megabytes left behind, which
 * is the only version of "delete" that is true on a phone where models
 * already claim gigabytes.
 *
 * Local, like everything else about local models: nothing here is ever sent
 * anywhere, and there is no cloud copy to reconcile with.
 */
object LocalChatStore {

    private const val TAG = "Conch-LocalChat"

    @Serializable
    data class Msg(
        /** "user" or "assistant". */
        val role: String,
        val text: String,
        /** Absolute paths inside this chat's own directory. */
        val images: List<String> = emptyList(),
        /** The model's reasoning for this answer, when thinking was on —
         *  kept so a reopened chat still folds it away instead of losing it. */
        val thinking: String? = null,
    )

    @Serializable
    data class Chat(
        val id: String,
        val modelId: String,
        val title: String = "",
        val createdAtMs: Long = 0L,
        val updatedAtMs: Long = 0L,
        val msgs: List<Msg> = emptyList(),
    )

    /** List-row view of a chat: what the library shows without needing the
     *  transcript. */
    data class Meta(
        val id: String,
        val modelId: String,
        val title: String,
        val updatedAtMs: Long,
        val turns: Int,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _chats = MutableStateFlow<List<Meta>>(emptyList())

    /** Every stored chat, newest first. Refreshed by [reload] and by writes. */
    val chats: StateFlow<List<Meta>> = _chats.asStateFlow()

    @Volatile private var loaded = false

    private fun root(): File =
        File(ServiceLocator.appContext.filesDir, "llm/chats").apply { mkdirs() }

    fun dirOf(chatId: String): File = File(root(), chatId).apply { mkdirs() }

    private fun fileOf(chatId: String): File = File(dirOf(chatId), "chat.json")

    /** Cheap: a handful of small transcripts. Called on first use and after
     *  every write, so the library list never lies about what is on disk. */
    fun reload() {
        val metas = runCatching {
            root().listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { dir ->
                runCatching {
                    val c = json.decodeFromString(Chat.serializer(), File(dir, "chat.json").readText())
                    Meta(
                        id = c.id,
                        modelId = c.modelId,
                        title = c.title.ifBlank { "new chat" },
                        updatedAtMs = c.updatedAtMs,
                        turns = c.msgs.count { it.role == "user" },
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
        _chats.value = metas.sortedByDescending { it.updatedAtMs }
        loaded = true
    }

    fun ensureLoaded() {
        if (!loaded) reload()
    }

    fun load(chatId: String): Chat? = runCatching {
        json.decodeFromString(Chat.serializer(), fileOf(chatId).readText())
    }.getOrNull()

    /**
     * Write the transcript. Empty chats are NOT written: opening a chat and
     * walking away must not leave a row in the library (the same reason a
     * brand-new CLI session does not mint a session file until its first
     * turn).
     *
     * ⛔ ONLY THE CONVERSATION IS SAVED. The caller's list also carries this
     * turn's transient rows — "stopped", "earlier turns dropped", an engine
     * error — which belong to the moment, not to the transcript: reopened
     * they would read as things the model said, and they must never be fed
     * back to it. Filtered HERE so no caller can forget.
     */
    fun save(chat: Chat) {
        val kept = chat.msgs.filter { it.role == "user" || it.role == "assistant" }
        if (kept.isEmpty()) return
        runCatching {
            val now = System.currentTimeMillis()
            val withStamps = chat.copy(
                msgs = kept,
                createdAtMs = if (chat.createdAtMs > 0) chat.createdAtMs else now,
                updatedAtMs = now,
                title = chat.title.ifBlank { titleFrom(chat.msgs) },
            )
            fileOf(chat.id).writeText(json.encodeToString(Chat.serializer(), withStamps))
        }.onFailure {
            android.util.Log.w(TAG, "could not save chat ${chat.id}: ${it.message}")
        }
        reload()
    }

    /** The chat and every picture in it. */
    fun delete(chatId: String) {
        runCatching { dirOf(chatId).deleteRecursively() }
        reload()
    }

    /** Deletes the chats of a model that is being removed — a transcript
     *  whose brain is gone can still be READ, so this is called only when the
     *  owner asks for the chats to go, never as a side effect of a model
     *  delete. */
    fun deleteFor(modelId: String) {
        ensureLoaded()
        _chats.value.filter { it.modelId == modelId }.forEach { delete(it.id) }
    }

    /** First user line, clipped to a row: the title a chat earns by its first
     *  question, the same way the session list titles a CLI thread. */
    fun titleFrom(msgs: List<Msg>): String {
        val first = msgs.firstOrNull { it.role == "user" }?.text?.trim().orEmpty()
        if (first.isEmpty()) return "new chat"
        val oneLine = first.replace(Regex("\\s+"), " ")
        return if (oneLine.length <= 42) oneLine else oneLine.take(41).trimEnd() + "…"
    }

    /** Copy a picture into the chat's own directory, returning its path.
     *  [bytes] have already been shrunk for the phone's model. */
    fun writeImage(chatId: String, bytes: ByteArray, ext: String = "jpg"): String? = runCatching {
        val f = File(dirOf(chatId), "img-${System.currentTimeMillis()}-${bytes.size}.$ext")
        f.writeBytes(bytes)
        f.absolutePath
    }.getOrNull()
}
