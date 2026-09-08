package ai.eight24family.conch.ui.viewmodel

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.linux.LocalLlm
import ai.eight24family.conch.linux.LocalLlmEngine
import ai.eight24family.conch.linux.chat.LocalChatApi
import ai.eight24family.conch.linux.chat.LocalChatStore
import ai.eight24family.conch.linux.store.ModelRecords
import ai.eight24family.conch.util.LocalImageShrink
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The phone's own model, as a CHAT — the app talking to
 * [LocalLlmEngine]'s loopback endpoint itself.
 *
 * ⛔ THIS IS THE SHORT PATH, AND ITS WHOLE VALUE IS BEING SHORT. It must
 * work with a downloaded model and nothing else: no Linux environment, no
 * phone bridge, no developer options, no CLI install, no account. Anything
 * added here that needs one of those has to be OPTIONAL, or this screen
 * stops being the thing it exists to be.
 *
 * It is deliberately NOT [ChatViewModel]: that one drives a CLI over SSH —
 * resume ids, session files, approval modes, tool permissions, sk touches,
 * reconnect watchdogs. A local chat has a socket on 127.0.0.1 and two roles.
 * Threading it through the CLI machinery would have meant a spray of
 * `if (local)` branches through eleven thousand lines; the RENDERING is
 * shared instead (the same `UserLine` / `AssistantLine` / `EventLine` /
 * `PromptBar` the CLI chat draws), which is the part worth sharing.
 *
 * The agent path stays one tap away from here (the top bar's `[ agent ]`) —
 * that is where a real shell and real tools live, and this screen never
 * pretends to have them.
 */
class LocalChatViewModel(savedStateHandle: SavedStateHandle) : ViewModel() {

    val modelId: String = checkNotNull(savedStateHandle["modelId"])

    /** The chat this screen is: an id from the route when reopening a stored
     *  chat, a fresh one otherwise. Minted ONCE per ViewModel, so a rotation
     *  or a trip to the background keeps writing the same transcript. */
    val chatId: String = savedStateHandle.get<String>("chat")
        ?.takeIf { it.isNotBlank() }
        ?: UUID.randomUUID().toString()

    val model: LocalLlm.Model? get() = LocalLlm.byId(modelId)

    /**
     * The transcript, including this turn's transient rows.
     *
     * `note` / `error` rows live in the SAME list as the conversation rather
     * than in a side channel: they belong between two turns, and a separate
     * list would have to re-derive that position on every append. The store
     * drops those roles on save, so nothing transient survives a reopen and
     * nothing transient is ever sent to the model.
     */
    private val _msgs = MutableStateFlow<List<LocalChatStore.Msg>>(emptyList())

    private data class Live(
        val text: String = "",
        val thinking: String = "",
        val active: Boolean = false,
    )

    private val _live = MutableStateFlow(Live())

    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working.asStateFlow()

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    /** Staged pictures — the same type the CLI chat's prompt bar stages, so
     *  [ai.eight24family.conch.ui.screens.PromptBar] needs no local variant. */
    private val _attachments = MutableStateFlow<List<StagedAttachment>>(emptyList())
    val attachments: StateFlow<List<StagedAttachment>> = _attachments.asStateFlow()

    /** Measured generation speed of the last turn, straight from the engine's
     *  own timings — the honest answer to "how fast is this phone", shown
     *  where a cloud chat would show a quota. */
    private val _tokPerSec = MutableStateFlow<Double?>(null)
    val tokPerSec: StateFlow<Double?> = _tokPerSec.asStateFlow()

    /** Reasoning on/off. Engine LAUNCH state (`--reasoning-budget`), so
     *  flipping it restarts a serving engine — the same rule the CLI path
     *  follows through the chat's effort picker. */
    private val _thinking = MutableStateFlow(false)
    val thinking: StateFlow<Boolean> = _thinking.asStateFlow()

    val engineState: StateFlow<LocalLlmEngine.State> = LocalLlmEngine.state

    val enterSends: StateFlow<Boolean> = ServiceLocator.preferences.enterSends
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * One drawn row: a message for the shared renderers, plus the pictures
     * that belong to it.
     *
     * The pictures ride BESIDE the text, never inside it. The CLI chat writes
     * image paths into the message body and pulls them back out for display —
     * it has to, because a path is all that crosses an SSH channel. Here the
     * body is what the MODEL is handed, so a path in it would be a path the
     * model reads out loud.
     */
    data class Row(val msg: AgentMessage, val images: List<String> = emptyList())

    /** What the screen draws. */
    val messages: StateFlow<List<Row>> =
        combine(_msgs, _live) { stored, live -> render(stored, live) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The window the engine is really serving, for the context line. */
    val ctxTokens: Int get() = LocalLlmEngine.activeCtx

    private var turnJob: Job? = null

    /** Kept in memory so a save never has to read the file back to learn it. */
    @Volatile private var createdAtMs = 0L

    init {
        viewModelScope.launch(Dispatchers.IO) {
            LocalChatStore.load(chatId)?.let { c ->
                createdAtMs = c.createdAtMs
                _msgs.value = c.msgs
            }
            // Warm the engine the moment the chat opens: the weights stream
            // off ext4 while the user is still typing, so the first send does
            // not pay for the load. Only when nothing else is being served,
            // though — a model swap stops the other model, and merely OPENING
            // a screen must never kill a turn another chat is running.
            val m = model ?: return@launch
            if (LocalLlm.status(m) !is LocalLlm.Status.Ready) return@launch
            val busyWithAnother = when (val st = LocalLlmEngine.state.value) {
                is LocalLlmEngine.State.Up -> st.modelId != m.id
                is LocalLlmEngine.State.Starting -> st.modelId != m.id
                else -> false
            }
            if (busyWithAnother) return@launch
            LocalLlmEngine.start(m, thinking = _thinking.value)
        }
    }

    // ── composing ──

    fun setInput(text: String) { _input.value = text }

    fun addAttachment(bytes: ByteArray, displayName: String, mimeType: String?) {
        if (_attachments.value.size >= MAX_ATTACHMENTS) return
        val isImage = mimeType?.startsWith("image/") == true ||
            displayName.substringAfterLast('.', "").lowercase() in IMAGE_EXTS
        _attachments.update {
            it + StagedAttachment(
                id = UUID.randomUUID().toString(),
                displayName = displayName,
                mimeType = mimeType,
                bytes = bytes,
                isImage = isImage,
                // Nothing is uploaded anywhere — the engine is a process on
                // this phone. Staged == ready, and the empty remote path says
                // "there is no remote" honestly.
                status = UploadStatus.Ready(""),
            )
        }
    }

    fun removeAttachment(id: String) {
        _attachments.update { list -> list.filterNot { it.id == id } }
    }

    fun setThinking(on: Boolean) {
        if (_thinking.value == on) return
        _thinking.value = on
        // A serving engine has the old switch baked into its launch — restart
        // it now rather than letting the next send silently answer in the
        // wrong mode. An idle engine picks the flag up when it starts.
        val m = model ?: return
        if (LocalLlmEngine.state.value is LocalLlmEngine.State.Up) {
            viewModelScope.launch { LocalLlmEngine.start(m, thinking = on) }
        }
    }

    // ── dictation ──
    //
    // ⛔ THE MIC PRODUCES TEXT HERE, NOT AN ATTACHMENT. The CLI chat's mic
    // records a voice MESSAGE for an agent to listen to on a server; this
    // engine has no ears at all (the word "audio" does not appear in
    // llama-server's own --help on this build), so a recording attached here
    // would be a file nothing can read. It goes through whisper on this phone
    // and lands in the composer as words the owner can edit before sending.

    val voiceState: StateFlow<ai.eight24family.conch.linux.voice.LocalVoice.State> =
        ai.eight24family.conch.linux.voice.LocalVoice.state

    @Volatile private var capture: ai.eight24family.conch.linux.voice.VoiceCapture.Session? = null
    private var ticker: Job? = null

    /** Is there a voice model to dictate with? */
    fun voiceReady(): Boolean =
        ai.eight24family.conch.linux.voice.LocalVoice.available() &&
            ai.eight24family.conch.linux.voice.LocalVoice.model() != null

    /**
     * Tap once to start, once to stop. The transcript is APPENDED to whatever
     * is already typed - dictation adds to a sentence, it does not replace it.
     */
    fun micTap() {
        val live = capture
        if (live != null) {
            capture = null
            ticker?.cancel()
            val wav = live.stop()
            ai.eight24family.conch.linux.voice.LocalVoice.setState(
                ai.eight24family.conch.linux.voice.LocalVoice.State.Idle,
            )
            if (wav == null) {
                note("nothing was recorded")
                return
            }
            viewModelScope.launch(Dispatchers.IO) {
                when (val r = ai.eight24family.conch.linux.voice.LocalVoice.transcribe(wav)) {
                    is ai.eight24family.conch.linux.voice.LocalVoice.Result.Ok -> {
                        val sep = if (_input.value.isBlank()) "" else " "
                        _input.value = _input.value + sep + r.text
                    }
                    ai.eight24family.conch.linux.voice.LocalVoice.Result.NoModel ->
                        note("the voice model is not downloaded - get it in local models")
                    is ai.eight24family.conch.linux.voice.LocalVoice.Result.Failed ->
                        note(r.reason, error = true)
                }
            }
            return
        }
        if (!voiceReady()) {
            note("dictation needs the voice model - get it in local models (60 MB)")
            return
        }
        val started = ai.eight24family.conch.linux.voice.VoiceCapture.start(
            ServiceLocator.appContext,
        )
        if (started == null) {
            note("the microphone is not available", error = true)
            return
        }
        capture = started
        ai.eight24family.conch.linux.voice.LocalVoice.setState(
            ai.eight24family.conch.linux.voice.LocalVoice.State.Recording(0),
        )
        ticker = viewModelScope.launch {
            var s = 0
            while (capture != null) {
                kotlinx.coroutines.delay(1_000)
                s++
                ai.eight24family.conch.linux.voice.LocalVoice.setState(
                    ai.eight24family.conch.linux.voice.LocalVoice.State.Recording(s),
                )
                // The capture stops itself at the cap; close the UI loop too.
                if (s >= ai.eight24family.conch.linux.voice.VoiceCapture.MAX_SECONDS) {
                    micTap()
                    return@launch
                }
            }
        }
    }

    // ── the turn ──

    fun send() {
        if (_working.value) return
        val m = model
        if (m == null) {
            note("this model is no longer installed", error = true)
            return
        }
        val text = _input.value.trim()
        val staged = _attachments.value
        if (text.isEmpty() && staged.none { it.isImage }) return
        if (LocalLlm.status(m) !is LocalLlm.Status.Ready) {
            note("${m.label} is not downloaded yet", error = true)
            return
        }

        val pics = staged.filter { it.isImage }
        _input.value = ""
        _attachments.value = emptyList()
        _working.value = true
        _live.value = Live(active = true)

        turnJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // A picture needs the model's eyes. Without the vision pack
                // the honest move is to say the photo did not go — never to
                // send the words and let the user believe it was looked at.
                val canSee = LocalLlm.hasVision(m)
                val imagePaths = if (canSee) {
                    pics.mapNotNull { att ->
                        LocalChatStore.writeImage(chatId, LocalImageShrink.shrink(att.bytes))
                    }
                } else {
                    emptyList()
                }
                _msgs.update {
                    it + LocalChatStore.Msg(role = "user", text = text, images = imagePaths)
                }
                if (pics.isNotEmpty() && !canSee) {
                    note(
                        if (m.mmprojUrl != null) {
                            "${m.label} needs its vision pack to see pictures — " +
                                "add it on the model's page"
                        } else {
                            "${m.label} cannot see pictures"
                        },
                    )
                }
                persist()
                runTurn(m)
            } finally {
                // Whatever happened — done, failed, or stopped by the user —
                // the words the model DID produce are the user's. They land in
                // the transcript instead of evaporating with the buffer.
                commitLive()
                _working.value = false
            }
        }
    }

    /**
     * Stop, as the user means it: the words the model already said are kept,
     * the row says why it ended, and the composer is a Send again — all before
     * the socket has finished unwinding.
     *
     * ⛔ NOT LEFT TO THE TURN'S OWN `finally`. That runs when the cancelled
     * coroutine actually unwinds, and a blocked read can hold it: on the
     * owner's phone the button stayed a Stop while the answer went on being
     * written (2026-09-08). The engine-side fix is in [LocalChatApi.stream];
     * this half makes the SCREEN honest the instant the tap lands, and the
     * turn's `finally` is left as an idempotent backstop.
     */
    fun stop() {
        val job = turnJob
        turnJob = null
        job?.cancel()
        commitLive()
        note("stopped")
        _working.value = false
    }

    private suspend fun runTurn(m: LocalLlm.Model) {
        if (!LocalLlmEngine.start(m, thinking = _thinking.value)) {
            val why = (LocalLlmEngine.state.value as? LocalLlmEngine.State.Failed)?.reason
                ?: "the engine did not come up"
            note(why, error = true)
            return
        }
        val ctx = LocalLlmEngine.activeCtx
        val all = turnsFrom(_msgs.value)
        var sent = LocalChatApi.fit(all, ctx)
        if (sent.size < all.size) {
            note("earlier turns dropped — this model's window is ${ctx / 1024}K tokens")
        }
        var outcome = stream(m, sent)
        // The estimate was wrong about the real tokenizer. Retry ONCE on a
        // halved window: a shorter memory beats a chat that answers nothing,
        // and the note says what happened.
        if (outcome is LocalChatApi.Outcome.TooLong) {
            sent = LocalChatApi.fit(all, ctx / 2)
            note("the conversation outgrew the window — continuing with the recent turns")
            outcome = stream(m, sent)
        }
        when (val o = outcome) {
            is LocalChatApi.Outcome.Failed -> note(o.reason, error = true)
            is LocalChatApi.Outcome.TooLong -> note(o.message, error = true)
            LocalChatApi.Outcome.Done -> Unit
        }
    }

    /**
     * One request, published to the screen on a cadence.
     *
     * Tokens arrive faster than a screen can be redrawn (60/s under GPU
     * offload), so deltas accumulate and land every [FLUSH_MS] — the same
     * trick the CLI chat's emit batcher uses. Time-checked inside the
     * callback rather than from a second coroutine: a flusher of its own
     * would outlive a cancelled turn (it did, and spun).
     */
    private suspend fun stream(
        m: LocalLlm.Model,
        turns: List<LocalChatApi.Turn>,
    ): LocalChatApi.Outcome {
        val text = StringBuilder()
        val think = StringBuilder()
        var lastFlush = System.currentTimeMillis()
        fun publish() {
            if (text.isEmpty() && think.isEmpty()) return
            val t = text.toString(); text.setLength(0)
            val th = think.toString(); think.setLength(0)
            _live.update { it.copy(text = it.text + t, thinking = it.thinking + th) }
        }
        try {
            return LocalChatApi.stream(m.id, turns) { chunk ->
                when (chunk) {
                    is LocalChatApi.Chunk.Text -> text.append(chunk.delta)
                    is LocalChatApi.Chunk.Thinking -> think.append(chunk.delta)
                    is LocalChatApi.Chunk.Speed -> {
                        _tokPerSec.value = chunk.tokPerSec
                        // The store's speed numbers stop being a button's job:
                        // a real answer of real length IS the measurement.
                        // Short bursts are ignored — they are dominated by the
                        // first-token latency.
                        if (chunk.predicted >= 16) {
                            ModelRecords.noteMeasured(m.id, chunk.tokPerSec)
                        }
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastFlush >= FLUSH_MS) {
                    lastFlush = now
                    publish()
                }
            }
        } finally {
            publish()
        }
    }

    /** Fold the streamed answer into the transcript and clear the buffer. */
    private fun commitLive() {
        val live = _live.value
        _live.value = Live()
        if (live.text.isBlank() && live.thinking.isBlank()) return
        _msgs.update {
            it + LocalChatStore.Msg(
                role = "assistant",
                text = live.text,
                thinking = live.thinking.takeIf { t -> t.isNotBlank() },
            )
        }
        persist()
    }

    private fun note(text: String, error: Boolean = false) {
        _msgs.update { it + LocalChatStore.Msg(role = if (error) "error" else "note", text = text) }
    }

    private fun persist() {
        val snapshot = _msgs.value
        viewModelScope.launch(Dispatchers.IO) {
            LocalChatStore.save(
                LocalChatStore.Chat(
                    id = chatId,
                    modelId = modelId,
                    title = LocalChatStore.titleFrom(snapshot),
                    createdAtMs = createdAtMs,
                    msgs = snapshot,
                ),
            )
            if (createdAtMs == 0L) createdAtMs = System.currentTimeMillis()
        }
    }

    /** Transcript → what the engine is sent. Notes and errors are OURS, not
     *  the conversation's, and never reach the model. */
    private fun turnsFrom(msgs: List<LocalChatStore.Msg>): List<LocalChatApi.Turn> =
        msgs.filter { it.role == "user" || it.role == "assistant" }
            .map { LocalChatApi.Turn(role = it.role, text = it.text, images = it.images) }

    /**
     * Transcript → rows. Ids are positional and therefore stable for an
     * append-only list, which is what a LazyColumn key has to be; the
     * in-flight answer carries a fixed id so its item is updated rather than
     * replaced on every flush.
     */
    private fun render(stored: List<LocalChatStore.Msg>, live: Live): List<Row> {
        val out = ArrayList<Row>(stored.size + 2)
        stored.forEachIndexed { i, m ->
            when (m.role) {
                "user" -> out += Row(
                    AgentMessage.UserText(id = "u$i", text = m.text),
                    images = m.images,
                )
                "assistant" -> {
                    m.thinking?.takeIf { it.isNotBlank() }?.let { th ->
                        out += Row(
                            AgentMessage.EventNote(
                                id = "t$i", label = "thought", detail = th,
                                tone = AgentMessage.EventNote.Tone.DIM,
                            ),
                        )
                    }
                    if (m.text.isNotBlank()) {
                        out += Row(AgentMessage.AssistantText(id = "a$i", text = m.text))
                    }
                }
                "error" -> out += Row(AgentMessage.Error(id = "e$i", text = m.text))
                else -> out += Row(
                    AgentMessage.EventNote(
                        id = "n$i", label = m.text,
                        tone = AgentMessage.EventNote.Tone.DIM,
                    ),
                )
            }
        }
        if (live.active) {
            if (live.thinking.isNotBlank()) {
                out += Row(
                    AgentMessage.EventNote(
                        id = "live-think",
                        label = "thinking",
                        detail = live.thinking,
                        tone = AgentMessage.EventNote.Tone.DIM,
                    ),
                )
            }
            if (live.text.isNotBlank()) {
                out += Row(AgentMessage.AssistantText(id = "live", text = live.text))
            }
        }
        return out
    }

    override fun onCleared() {
        // An open mic must never outlive the screen that opened it.
        capture?.discard()
        capture = null
        ticker?.cancel()
        // The engine is NOT stopped here. Leaving a chat is not a decision to
        // unload two gigabytes of weights — the next send (or another chat on
        // the same model) would pay the whole load again. Stopping it is an
        // explicit act: this screen's `[ stop ]`, or the model row's `[ end ]`.
        turnJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val FLUSH_MS = 60L
        const val MAX_ATTACHMENTS = 4
        val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp", "gif")
    }
}
