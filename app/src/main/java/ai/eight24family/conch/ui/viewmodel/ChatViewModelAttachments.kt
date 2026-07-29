package ai.eight24family.conch.ui.viewmodel

import ai.eight24family.conch.agent.AgentSession
import ai.eight24family.conch.agent.SessionState
import ai.eight24family.conch.analytics.Telemetry
import ai.eight24family.conch.data.UploadCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Staged attachment pipeline.
 *
 * Owns:
 *  - [attachments] — StagedAttachment list (image / file chips above the prompt bar).
 *  - [anyUploading] — derived "send button disabled" flag.
 *  - The async upload coroutine for [addAttachment]: hash → dedupe via UploadCache →
 *    [AgentSession.uploadFile] → progress emissions.
 *
 * UploadStatus / StagedAttachment data classes stay top-level (already are — they're
 * referenced from compose code outside ChatViewModel).
 *
 * See ChatViewModel.kt prior to extraction for the original inline comments.
 */
internal class ChatViewModelAttachments(
    private val scope: CoroutineScope,
    private val serverId: String,
    private val uploadCache: ai.eight24family.conch.data.UploadCache,
    /** Read-only accessor for the current local session id. */
    private val currentLocalSessionId: () -> String?,
    /** Read-only accessor for the active AgentSession map. */
    private val activeSessionFor: (String) -> AgentSession?,
) {
    private val _attachments = MutableStateFlow<List<StagedAttachment>>(emptyList())
    val attachments: StateFlow<List<StagedAttachment>> = _attachments.asStateFlow()

    val anyUploading: StateFlow<Boolean> = _attachments
        .map { list -> list.any { it.status is UploadStatus.Uploading } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Take a snapshot of currently-staged attachments and clear the list. Used by
     *  ChatViewModel.send() to consume the staged set atomically. */
    fun snapshotAndClear(): List<StagedAttachment> {
        val snap = _attachments.value
        _attachments.value = emptyList()
        return snap
    }

    /** Current uploading state — used by ChatViewModel.send() to block sends while
     *  uploads are in flight. */
    fun snapshot(): List<StagedAttachment> = _attachments.value

    fun addAttachment(bytes: ByteArray, displayName: String, mimeType: String?) {
        if (_attachments.value.size >= MAX_ATTACHMENTS) return
        val isImage = mimeType?.startsWith("image/") == true ||
            displayName.substringAfterLast('.', "").lowercase() in IMAGE_EXTS
        Telemetry.attachmentUploaded(
            if (isImage) Telemetry.AttachmentKind.PHOTO else Telemetry.AttachmentKind.FILE
        )
        val attId = UUID.randomUUID().toString()
        _attachments.update {
            it + StagedAttachment(
                id = attId,
                displayName = displayName,
                mimeType = mimeType,
                bytes = bytes,
                isImage = isImage,
                status = UploadStatus.Uploading(0f),
            )
        }
        scope.launch(Dispatchers.IO) {
            val tag = "SshAi-Upload"
            android.util.Log.d(tag, "addAttachment: $displayName ${bytes.size}B mime=$mimeType")

            val sha = UploadCache.sha256Hex(bytes)
            android.util.Log.d(tag, "sha256=$sha")

            // Wait until the session has actually reached Running.
            var waited = 0
            while (waited < 15_000) {
                val sid = currentLocalSessionId()
                val sess = if (sid != null) activeSessionFor(sid) else null
                if (sess != null && sess.state.value is SessionState.Running) break
                if (sess != null && sess.state.value is SessionState.Working) break
                delay(200); waited += 200
            }
            val s = currentLocalSessionId()?.let { activeSessionFor(it) }
            if (s == null) {
                android.util.Log.w(tag, "give up: session never became available (waited ${waited}ms)")
                updateAttachmentStatus(attId, UploadStatus.Failed("session not ready"))
                return@launch
            }

            // Dedupe: if we've already pushed bytes with this hash to this server, and
            // the file is still there, skip the transfer entirely.
            val cached = uploadCache.lookup(serverId, sha)
            if (cached != null) {
                android.util.Log.d(tag, "cache hit: $cached, verifying remote presence")
                val stillThere = s.checkRemoteFileExists(cached)
                if (stillThere) {
                    android.util.Log.d(tag, "remote still has it — skipping upload")
                    updateAttachmentStatus(attId, UploadStatus.Uploading(1f))
                    updateAttachmentStatus(attId, UploadStatus.Ready(cached))
                    return@launch
                } else {
                    android.util.Log.d(tag, "remote gone — forgetting cache, re-uploading")
                    uploadCache.forget(serverId, sha)
                }
            }

            android.util.Log.d(tag, "session ready (${s.state.value::class.simpleName}), starting uploadFile")
            val path = s.uploadFile(bytes, displayName) { progress ->
                updateAttachmentStatus(attId, UploadStatus.Uploading(progress))
            }
            android.util.Log.d(tag, "uploadFile returned path=$path")
            if (path != null) {
                uploadCache.record(serverId, sha, path)
                updateAttachmentStatus(attId, UploadStatus.Ready(path))
            } else {
                updateAttachmentStatus(attId, UploadStatus.Failed("upload failed"))
            }
        }
    }

    /**
     * Large-file variant: the picked content has already been streamed to a
     * temp [file] in cacheDir (never fully in the phone's heap). We hash it
     * streaming, dedupe, then stream it up via [AgentSession.uploadStream], and
     * delete the temp file when done (user 2026-06-14: a 439 MB zip OOM'd the
     * in-memory [addAttachment] path).
     */
    fun addFileAttachment(file: java.io.File, displayName: String, mimeType: String?, sizeBytes: Long) {
        if (_attachments.value.size >= MAX_ATTACHMENTS) { file.delete(); return }
        Telemetry.attachmentUploaded(Telemetry.AttachmentKind.FILE)
        val attId = UUID.randomUUID().toString()
        _attachments.update {
            it + StagedAttachment(
                id = attId,
                displayName = displayName,
                mimeType = mimeType,
                bytes = ByteArray(0),
                isImage = false,
                status = UploadStatus.Uploading(0f),
                localFile = file,
                sizeBytes = sizeBytes,
            )
        }
        scope.launch(Dispatchers.IO) {
            val tag = "SshAi-Upload"
            try {
                android.util.Log.d(tag, "addFileAttachment: $displayName ${sizeBytes}B mime=$mimeType (streamed)")
                val sha = file.inputStream().use { UploadCache.sha256HexStream(it) }

                var waited = 0
                while (waited < 15_000) {
                    val sid = currentLocalSessionId()
                    val sess = if (sid != null) activeSessionFor(sid) else null
                    if (sess != null && (sess.state.value is SessionState.Running ||
                            sess.state.value is SessionState.Working)) break
                    delay(200); waited += 200
                }
                val s = currentLocalSessionId()?.let { activeSessionFor(it) }
                if (s == null) {
                    updateAttachmentStatus(attId, UploadStatus.Failed("session not ready"))
                    return@launch
                }

                val cached = uploadCache.lookup(serverId, sha)
                if (cached != null && s.checkRemoteFileExists(cached)) {
                    updateAttachmentStatus(attId, UploadStatus.Uploading(1f))
                    updateAttachmentStatus(attId, UploadStatus.Ready(cached))
                    return@launch
                } else if (cached != null) {
                    uploadCache.forget(serverId, sha)
                }

                val path = s.uploadStream({ file.inputStream() }, sizeBytes, displayName) { progress ->
                    updateAttachmentStatus(attId, UploadStatus.Uploading(progress))
                }
                if (path != null) {
                    uploadCache.record(serverId, sha, path)
                    updateAttachmentStatus(attId, UploadStatus.Ready(path))
                } else {
                    updateAttachmentStatus(attId, UploadStatus.Failed("upload failed"))
                }
            } finally {
                // The temp copy is only a staging buffer — the bytes live on the
                // server now (or the upload failed). Either way, reclaim the space.
                //
                // AUDIO IS THE EXCEPTION. A voice note is played from the LOCAL
                // file, and the upload finishes within a second of recording, so
                // deleting here left a play button that could only ever fail with
                // ENOENT. The reclaim rule exists for a 439 MB zip; a voice note is
                // kilobytes, capped at five minutes, and swept after a day by
                // AudioRecorder.sweepOld.
                val keepForPlayback = mimeType?.startsWith("audio/") == true
                if (!keepForPlayback) {
                    ai.eight24family.conch.util.SilentlyTry.fired(tag, "delete temp upload file") { file.delete() }
                }
            }
        }
    }

    private fun updateAttachmentStatus(id: String, status: UploadStatus) {
        _attachments.update { list ->
            list.map { if (it.id == id) it.copy(status = status) else it }
        }
    }

    fun removeAttachment(id: String) {
        _attachments.update { list -> list.filterNot { it.id == id } }
    }

    fun clearAttachments() {
        _attachments.value = emptyList()
    }

    companion object {
        const val MAX_ATTACHMENTS: Int = 10
        private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")
    }
}
