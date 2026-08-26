package ai.eight24family.conch.ui.viewmodel

import ai.eight24family.conch.agent.AgentSession
import ai.eight24family.conch.di.ServiceLocator
import androidx.compose.ui.graphics.asImageBitmap
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Per-path download pipeline.
 *
 * Owns:
 *  - [downloads] — per-path [ChatViewModel.DownloadStatus].
 *  - [fileExists] / [fileSizes] — remote stat probe results (path → exists/bytes).
 *  - Internal [remoteHashes] — server-side SHA-256 used to dedupe by content.
 *  - Open-with one-shot SharedFlows: [openInViewer], [openExternally], [shareFile],
 *    [openFilePrompt].
 *
 * Reuses [ChatViewModel.DownloadStatus] / [ChatViewModel.OpenInViewerRequest] /
 * [ChatViewModel.OpenExternallyRequest] / [ChatViewModel.ShareRequest] /
 * [ChatViewModel.OpenFilePromptRequest] so the UI consumers in `ChatDownloadDisk.kt`
 * and `ChatScreenFileOpen.kt` keep compiling without rename.
 *
 * Reads `_localSessionId` + `activeSessions` via lambdas — that keeps this class
 * decoupled from the central state map in ChatViewModel.
 */
internal class ChatViewModelDownloads(
    private val scope: CoroutineScope,
    private val serverId: String,
    /** Read-only accessor for the current local session id. */
    private val currentLocalSessionId: () -> String?,
    /** Read-only accessor for the active AgentSession map. */
    private val activeSessionFor: (String) -> AgentSession?,
) {
    private val _downloads = MutableStateFlow<Map<String, ChatViewModel.DownloadStatus>>(emptyMap())
    val downloads: StateFlow<Map<String, ChatViewModel.DownloadStatus>> = _downloads.asStateFlow()

    private val _fileExists = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val fileExists: StateFlow<Map<String, Boolean>> = _fileExists.asStateFlow()

    private val _fileSizes = MutableStateFlow<Map<String, Long>>(emptyMap())
    val fileSizes: StateFlow<Map<String, Long>> = _fileSizes.asStateFlow()

    /** Remote `sha256sum` of each probed path. Used by [downloadFile] to key the
     *  persisted "already-downloaded" index by CONTENT hash instead of by basename. */
    private val _remoteHashes = MutableStateFlow<Map<String, String>>(emptyMap())

    private val fileExistsInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val _openInViewer = MutableSharedFlow<ChatViewModel.OpenInViewerRequest>(
        replay = 0, extraBufferCapacity = 4
    )
    val openInViewer: SharedFlow<ChatViewModel.OpenInViewerRequest> = _openInViewer

    private val _openExternally = MutableSharedFlow<ChatViewModel.OpenExternallyRequest>(
        replay = 0, extraBufferCapacity = 4
    )
    val openExternally: SharedFlow<ChatViewModel.OpenExternallyRequest> = _openExternally

    private val _shareFile = MutableSharedFlow<ChatViewModel.ShareRequest>(
        replay = 0, extraBufferCapacity = 4
    )
    val shareFile: SharedFlow<ChatViewModel.ShareRequest> = _shareFile

    private val _openFilePrompt = MutableSharedFlow<ChatViewModel.OpenFilePromptRequest>(
        replay = 0, extraBufferCapacity = 4
    )
    val openFilePrompt: SharedFlow<ChatViewModel.OpenFilePromptRequest> = _openFilePrompt

    // ── Inline image rendering ──
    // We don't exchange image bytes in chat — both sides pass FILE PATHS. To
    // show the actual picture (not the path) we stream the bytes into memory
    // over the live session and decode to an ImageBitmap. The renderer shows a
    // spinner until Ready, then the image — the path text is never displayed.
    sealed interface InlineImage {
        object Loading : InlineImage
        data class Ready(val bitmap: androidx.compose.ui.graphics.ImageBitmap) : InlineImage
        data class Failed(val reason: String) : InlineImage
    }
    private val _inlineImages = MutableStateFlow<Map<String, InlineImage>>(emptyMap())
    val inlineImages: StateFlow<Map<String, InlineImage>> = _inlineImages.asStateFlow()
    private val inlineInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val trimKey = "inlineImages@${Integer.toHexString(System.identityHashCode(this))}"

    init {
        // Decoded inline bitmaps are the biggest heap objects this class owns
        // (≤1600 px each — up to ~10 MB). When the UI goes off-screen
        // (TRIM_MEMORY_UI_HIDDEN and up) drop them all: every consumer calls
        // the idempotent loadInlineImage from composition, so a scrolled-back
        // or reopened chat repaints from the on-disk cache in one frame.
        ai.eight24family.conch.util.MemoryPressure.register(trimKey) { trimDecodedImages() }
    }

    /** Unhook from [ai.eight24family.conch.util.MemoryPressure]. The owning
     *  ViewModel calls this from onCleared — without it the registered lambda
     *  would keep this coordinator (and through it the whole VM graph)
     *  reachable for the life of the process. */
    fun close() {
        ai.eight24family.conch.util.MemoryPressure.unregister(trimKey)
    }

    /** Drop every decoded bitmap, keep Loading/Failed states (a Failed entry
     *  must not silently retry just because memory got tight). */
    internal fun trimDecodedImages() {
        _inlineImages.update { m -> m.filterValues { it !is InlineImage.Ready } }
    }

    private companion object {
        /**
         * Hard budget for decoded inline images held at once. "Bitmap memory"
         * is its own Android-vitals metric under Google Play's memory-quality
         * requirement; before this cap a screenshot-heavy chat accumulated an
         * unbounded map of ≤1600 px bitmaps for the ViewModel's whole life.
         * Eight ≈ one screen of images plus scroll margin — evicted paths
         * vanish from the map, so the renderer's idempotent load re-issues on
         * the next composition and repaints from the disk cache.
         */
        const val MAX_READY_IMAGES = 8
    }

    /** Insert [img]; a [InlineImage.Ready] additionally enforces
     *  [MAX_READY_IMAGES] by dropping the OLDEST Ready entries (map insertion
     *  order — first decoded, first out). Internal for the budget test. */
    internal fun putInlineImage(path: String, img: InlineImage) {
        _inlineImages.update { m ->
            val next = m + (path to img)
            if (img !is InlineImage.Ready) return@update next
            val ready = next.keys.filter { next[it] is InlineImage.Ready }
            if (ready.size <= MAX_READY_IMAGES) next
            else next - ready.take(ready.size - MAX_READY_IMAGES).toSet()
        }
    }

    /** Download [remotePath] into memory + decode (downscaled) to an
     *  ImageBitmap for inline display. Idempotent; cached once Ready. */
    fun loadInlineImage(remotePath: String) {
        if (_inlineImages.value[remotePath] is InlineImage.Ready) return
        if (!inlineInFlight.add(remotePath)) return
        putInlineImage(remotePath, InlineImage.Loading)
        scope.launch(Dispatchers.IO) {
            try {
                // 1. Local disk cache FIRST — render without touching the server.
                //    Every image we've ever shown or sent is cached by its remote
                //    path, so a reopened chat (no live session / FIDO not tapped /
                //    server /tmp already cleaned) paints instantly from disk.
                val cached = SilentlyTry.logged("SshAi-Chat", "decode cached inline image") {
                    val cf = imageCacheFile(remotePath)
                    if (cf.exists() && cf.length() > 0) decodeDownscaled(cf.readBytes()) else null
                }
                if (cached != null) {
                    putInlineImage(remotePath, InlineImage.Ready(cached))
                    return@launch
                }
                // 2. Not cached → stream it from the server (and cache on success).
                var session: AgentSession? = null
                var waited = 0
                while (waited < 15_000) {
                    val sid = currentLocalSessionId()
                    session = if (sid != null) activeSessionFor(sid) else null
                    if (session != null) break
                    delay(300); waited += 300
                }
                val s = session ?: run {
                    putInlineImage(remotePath, InlineImage.Failed("no session"))
                    return@launch
                }
                val buf = java.io.ByteArrayOutputStream()
                val outcome = runCatching { s.downloadFile(remotePath, buf) { _, _ -> } }
                    .getOrElse { AgentSession.DownloadOutcome.Failed(it.message ?: "io error") }
                when (outcome) {
                    is AgentSession.DownloadOutcome.Done -> {
                        val bytes = buf.toByteArray()
                        cacheImageBytes(remotePath, bytes)  // persist for offline reopen
                        val bmp = SilentlyTry.logged("SshAi-Chat", "decode inline image") {
                            decodeDownscaled(bytes)
                        }
                        putInlineImage(
                            remotePath,
                            bmp?.let { b -> InlineImage.Ready(b) } ?: InlineImage.Failed("decode failed"),
                        )
                    }
                    is AgentSession.DownloadOutcome.Failed ->
                        putInlineImage(remotePath, InlineImage.Failed(outcome.reason))
                }
            } finally {
                inlineInFlight.remove(remotePath)
            }
        }
    }

    /**
     * Pre-populate an inline image from bytes WE ALREADY HAVE — the user just
     * attached + uploaded it, so its bytes are still in memory. Decode locally
     * and stash as Ready so the chat shows the picture INSTANTLY: no server
     * round-trip, no spinner, and it's the user's own image we obviously have.
     * The later `loadInlineImage(path)` from the renderer then short-circuits
     * on the cached Ready.
     */
    fun preloadInlineImage(remotePath: String, bytes: ByteArray) {
        if (_inlineImages.value[remotePath] is InlineImage.Ready) return
        scope.launch(Dispatchers.IO) {
            // Persist to disk so a LATER reopen of this chat renders the image
            // locally too (the /tmp upload on the server may be gone by then).
            cacheImageBytes(remotePath, bytes)
            val bmp = SilentlyTry.logged("SshAi-Chat", "decode preloaded inline image") {
                decodeDownscaled(bytes)
            }
            if (bmp != null) {
                putInlineImage(remotePath, InlineImage.Ready(bmp))
            }
        }
    }

    // ── On-disk inline-image cache ──
    // Keyed by remote path (SHA-1 → filename). Written whenever we hold the
    // bytes (user upload OR a successful server download); read FIRST in
    // loadInlineImage so a reopened chat paints locally with zero server I/O.
    // Lives under cacheDir → the OS reclaims it under storage pressure.
    private fun imageCacheFile(remotePath: String): java.io.File {
        val dir = java.io.File(ServiceLocator.appContext.cacheDir, "inline_images").apply { mkdirs() }
        val key = SilentlyTry.loggedOrElse("SshAi-Chat", "hash image path", remotePath.hashCode().toString()) {
            java.security.MessageDigest.getInstance("SHA-1")
                .digest(remotePath.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
        return java.io.File(dir, key)
    }

    private fun cacheImageBytes(remotePath: String, bytes: ByteArray) {
        SilentlyTry.fired("SshAi-Chat", "cache inline image bytes") {
            imageCacheFile(remotePath).writeBytes(bytes)
        }
    }

    /** Decode JPEG/PNG/etc bytes, downscaled so a 12 MP phone screenshot
     *  doesn't blow the bitmap heap when shown thumbnail-sized in chat. */
    private fun decodeDownscaled(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap? =
        ai.eight24family.conch.util.Bitmaps.decodeSampled(bytes, maxDim = 1600)?.asImageBitmap()

    /**
     * Kick off (or skip) an async `[ -f <path> ]` probe on the live SSH transport.
     * Safe to call from any Composable on every recomposition — idempotent both
     * ways (already-cached results return immediately, already-running probes
     * don't get duplicated). The result lands in [fileExists] and the chat
     * surface conditionally shows the download disk based on it.
     */
    fun checkFileExists(path: String) {
        if (_fileExists.value.containsKey(path)) return
        if (!fileExistsInFlight.add(path)) return
        scope.launch(Dispatchers.IO) {
            try {
                // Cached JSONL renders AssistantLine BEFORE SSH handshake completes —
                // `activeSessions[sid]` is still null in that window. Earlier impl
                // cached `false` then, which permanently hid the disk icon. Wait
                // for the session to become available before probing.
                var waited = 0
                var session: AgentSession? = null
                while (waited < 15_000) {
                    val sid = currentLocalSessionId()
                    session = if (sid != null) activeSessionFor(sid) else null
                    if (session != null) break
                    delay(300)
                    waited += 300
                }
                if (session == null) return@launch

                when (val probe = session.statRemoteFile(path)) {
                    is AgentSession.RemoteFileProbe.Exists -> {
                        val info = probe.info
                        _fileExists.update { it + (path to true) }
                        _fileSizes.update { it + (path to info.sizeBytes) }
                        _remoteHashes.update { it + (path to info.sha256) }
                        val hash = info.sha256.takeIf { it.length == 64 }
                        if (hash != null && _downloads.value[path] !is ChatViewModel.DownloadStatus.Done) {
                            val entry = ServiceLocator.preferences.downloadIndex.first()[hash]
                            val uri = entry?.let {
                                SilentlyTry.logged("SshAi-Chat", "parse download uri (probe)") {
                                    android.net.Uri.parse(it.uriString)
                                }
                            }
                            if (uri != null && isLocalUriReadable(uri)) {
                                _downloads.update {
                                    it + (path to ChatViewModel.DownloadStatus.Done(
                                        uri, entry.basename, entry.sizeBytes,
                                    ))
                                }
                            } else if (uri != null) {
                                ServiceLocator.preferences.removeDownloadIndexEntry(hash)
                            }
                        }
                    }
                    AgentSession.RemoteFileProbe.NotFound -> {
                        _fileExists.update { it + (path to false) }
                    }
                    AgentSession.RemoteFileProbe.ProbeError -> {
                        // Network blip — DON'T cache; let next render retry.
                    }
                }
            } finally {
                fileExistsInFlight.remove(path)
            }
        }
    }

    /**
     * Entry point invoked by the disk-icon click after a download has completed.
     * Routes to one of three destinations (internal / external / share) — or to
     * the chooser bottom-sheet if no preference is remembered.
     */
    fun openDownloadedFile(
        uri: android.net.Uri,
        remotePath: String,
        mime: String,
        sizeBytes: Long,
    ) {
        val filename = remotePath.substringAfterLast('/')
        val ext = filename.substringAfterLast('.', "").lowercase()
        scope.launch {
            val pref = if (ext.isNotEmpty()) {
                ServiceLocator.preferences.openFilePreferenceForExtension(ext).first()
            } else null
            when (pref) {
                "internal" -> _openInViewer.emit(
                    ChatViewModel.OpenInViewerRequest(uri, filename, serverId, remotePath)
                )
                "external" -> _openExternally.emit(
                    ChatViewModel.OpenExternallyRequest(
                        uri, mime, ext, filename, sizeBytes, remotePath,
                    )
                )
                "share" -> _shareFile.emit(
                    ChatViewModel.ShareRequest(uri, mime, filename)
                )
                else -> _openFilePrompt.emit(
                    ChatViewModel.OpenFilePromptRequest(uri, filename, mime, ext, sizeBytes, remotePath)
                )
            }
        }
    }

    /**
     * The remembered "open with" choice could not be honoured (no app handles the
     * type any more). Drop it and re-offer the chooser — a remembered shortcut
     * that stops working has to give the long way back, or the disk icon is dead
     * for that extension with no screen able to revive it.
     */
    fun openFileFallbackToPrompt(req: ChatViewModel.OpenExternallyRequest) {
        scope.launch {
            if (req.extension.isNotBlank()) {
                ServiceLocator.preferences.setOpenFilePreferenceForExtension(req.extension, null)
            }
            _openFilePrompt.emit(
                ChatViewModel.OpenFilePromptRequest(
                    uri = req.uri,
                    filename = req.filename.ifBlank { req.uri.lastPathSegment.orEmpty() },
                    mime = req.mime,
                    extension = req.extension,
                    sizeBytes = req.sizeBytes,
                    remotePath = req.remotePath,
                )
            )
        }
    }

    /** Persist the user's "where to open .ext files" choice. */
    fun rememberOpenFileChoice(extension: String, choice: String) {
        if (extension.isBlank()) return
        scope.launch {
            ServiceLocator.preferences.setOpenFilePreferenceForExtension(extension, choice)
        }
    }

    /**
     * Pull a file the agent mentioned in its reply down to the phone. Re-tap during
     * a download is a no-op; re-tap on a finished one re-runs the SSH stream.
     */
    fun downloadFile(remotePath: String) {
        val cur = _downloads.value[remotePath]
        if (cur is ChatViewModel.DownloadStatus.Downloading) return
        if (cur is ChatViewModel.DownloadStatus.Done) {
            val mime = mimeForName(remotePath.substringAfterLast('/'))
            openDownloadedFile(cur.localUri, remotePath, mime, cur.sizeBytes)
            return
        }
        val basename = remotePath.substringAfterLast('/').ifBlank { "download.bin" }
        val knownHash = _remoteHashes.value[remotePath]
        if (knownHash != null && knownHash.length == 64) {
            scope.launch(Dispatchers.IO) {
                val entry = ServiceLocator.preferences.downloadIndex.first()[knownHash]
                val uri = entry?.let {
                    SilentlyTry.logged("SshAi-Chat", "parse download uri (download)") {
                        android.net.Uri.parse(it.uriString)
                    }
                }
                if (uri != null && isLocalUriReadable(uri)) {
                    _downloads.update {
                        it + (remotePath to ChatViewModel.DownloadStatus.Done(
                            uri, entry.basename, entry.sizeBytes,
                        ))
                    }
                    val mime = mimeForName(basename)
                    openDownloadedFile(uri, remotePath, mime, entry.sizeBytes)
                } else {
                    if (uri != null) {
                        ServiceLocator.preferences.removeDownloadIndexEntry(knownHash)
                    }
                    performRemoteDownload(remotePath, basename)
                }
            }
            return
        }
        performRemoteDownload(remotePath, basename)
    }

    /** Sanity-check that a stored local URI still points to an existing, readable file. */
    private fun isLocalUriReadable(uri: android.net.Uri): Boolean {
        val ctx = ServiceLocator.appContext
        return SilentlyTry.loggedOrElse("SshAi-Chat", "probe local uri readable", false) {
            if (uri.scheme == "file") {
                val f = uri.path?.let { java.io.File(it) }
                f != null && f.exists() && f.length() > 0
            } else {
                ctx.contentResolver.openInputStream(uri)?.use { true } ?: false
            }
        }
    }

    private fun performRemoteDownload(remotePath: String, basename: String) {
        val sid = currentLocalSessionId() ?: run {
            _downloads.update { it + (remotePath to ChatViewModel.DownloadStatus.Failed("no active session")) }
            return
        }
        val s = activeSessionFor(sid) ?: run {
            _downloads.update { it + (remotePath to ChatViewModel.DownloadStatus.Failed("no active session")) }
            return
        }
        _downloads.update { it + (remotePath to ChatViewModel.DownloadStatus.Downloading(-1f)) }
        scope.launch(Dispatchers.IO) {
            val ctx = ServiceLocator.appContext
            val basename2 = remotePath.substringAfterLast('/').ifBlank { "download.bin" }
            val resultUri: android.net.Uri?
            val displayLocation: String

            val customFolder = ServiceLocator.preferences.downloadsFolderUri.first()
            if (customFolder != null) {
                val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, customFolder)
                if (tree == null || !tree.canWrite()) {
                    _downloads.update {
                        it + (remotePath to ChatViewModel.DownloadStatus.Failed(
                            "Custom downloads folder is no longer accessible. " +
                                "Reset it in Settings → Downloads."
                        ))
                    }
                    return@launch
                }
                val existing = tree.findFile(basename2)
                val file = existing ?: tree.createFile(mimeForName(basename2), basename2)
                if (file == null) {
                    _downloads.update {
                        it + (remotePath to ChatViewModel.DownloadStatus.Failed("Couldn't create file in chosen folder"))
                    }
                    return@launch
                }
                val outcome = runCatching {
                    ctx.contentResolver.openOutputStream(file.uri, "wt")!!.use { os ->
                        s.downloadFile(remotePath, os) { got, total ->
                            val p = if (total > 0) got.toFloat() / total else -1f
                            _downloads.update { it + (remotePath to ChatViewModel.DownloadStatus.Downloading(p)) }
                        }
                    }
                }.getOrElse { AgentSession.DownloadOutcome.Failed(it.message ?: "io error") }
                when (outcome) {
                    is AgentSession.DownloadOutcome.Done -> {
                        resultUri = file.uri
                        displayLocation = file.uri.toString()
                    }
                    is AgentSession.DownloadOutcome.Failed -> {
                        if (existing == null) SilentlyTry.fired("SshAi-Chat", "delete failed download file") { file.delete() }
                        _downloads.update { it + (remotePath to ChatViewModel.DownloadStatus.Failed(outcome.reason)) }
                        return@launch
                    }
                }
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val cv = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, basename2)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeForName(basename2))
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/conch/")
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                if (uri == null) {
                    _downloads.update { it + (remotePath to ChatViewModel.DownloadStatus.Failed("MediaStore insert failed")) }
                    return@launch
                }
                val outcome = runCatching {
                    resolver.openOutputStream(uri)!!.use { os ->
                        s.downloadFile(remotePath, os) { got, total ->
                            val p = if (total > 0) got.toFloat() / total else -1f
                            _downloads.update { it + (remotePath to ChatViewModel.DownloadStatus.Downloading(p)) }
                        }
                    }
                }.getOrElse { AgentSession.DownloadOutcome.Failed(it.message ?: "io error") }

                when (outcome) {
                    is AgentSession.DownloadOutcome.Done -> {
                        val finalize = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                        }
                        resolver.update(uri, finalize, null, null)
                        resultUri = uri
                        displayLocation = "Download/conch/$basename2"
                    }
                    is AgentSession.DownloadOutcome.Failed -> {
                        SilentlyTry.fired("SshAi-Chat", "delete failed mediastore uri") { resolver.delete(uri, null, null) }
                        _downloads.update { it + (remotePath to ChatViewModel.DownloadStatus.Failed(outcome.reason)) }
                        return@launch
                    }
                }
            } else {
                val dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                if (dir == null) {
                    _downloads.update { it + (remotePath to ChatViewModel.DownloadStatus.Failed("no external storage")) }
                    return@launch
                }
                val target = java.io.File(dir, basename2)
                val outcome = runCatching {
                    target.outputStream().use { os ->
                        s.downloadFile(remotePath, os) { got, total ->
                            val p = if (total > 0) got.toFloat() / total else -1f
                            _downloads.update { it + (remotePath to ChatViewModel.DownloadStatus.Downloading(p)) }
                        }
                    }
                }.getOrElse { AgentSession.DownloadOutcome.Failed(it.message ?: "io error") }
                when (outcome) {
                    is AgentSession.DownloadOutcome.Done -> {
                        resultUri = android.net.Uri.fromFile(target)
                        displayLocation = target.absolutePath
                    }
                    is AgentSession.DownloadOutcome.Failed -> {
                        SilentlyTry.fired("SshAi-Chat", "delete failed target file") { target.delete() }
                        _downloads.update { it + (remotePath to ChatViewModel.DownloadStatus.Failed(outcome.reason)) }
                        return@launch
                    }
                }
            }
            // Size for the label next to the disk icon.
            val sizeBytes = run {
                val u = resultUri ?: return@run -1L
                if (u.scheme == "file") {
                    SilentlyTry.loggedOrElse("SshAi-Chat", "read file size", -1L) {
                        java.io.File(u.path ?: return@loggedOrElse -1L).length()
                    }
                } else {
                    SilentlyTry.loggedOrElse("SshAi-Chat", "query content uri size", -1L) {
                        ctx.contentResolver.query(u, null, null, null, null)?.use { c ->
                            val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                            if (c.moveToFirst() && idx >= 0) c.getLong(idx) else -1L
                        } ?: -1L
                    }
                }
            }
            _downloads.update {
                it + (remotePath to ChatViewModel.DownloadStatus.Done(resultUri, displayLocation, sizeBytes))
            }
            if (resultUri != null) {
                val hash = _remoteHashes.value[remotePath]?.takeIf { it.length == 64 }
                    ?: SilentlyTry.logged("SshAi-Chat", "compute local sha256") { computeLocalSha256(resultUri) }
                if (!hash.isNullOrBlank() && hash.length == 64) {
                    SilentlyTry.fired("SshAi-Chat", "add download index entry") {
                        ServiceLocator.preferences.addDownloadIndexEntry(
                            hash,
                            ai.eight24family.conch.data.prefs.AppPreferences.DownloadIndexEntry(
                                uriString = resultUri.toString(),
                                basename = basename2,
                                sizeBytes = sizeBytes,
                            ),
                        )
                    }
                }
                val mime = mimeForName(basename2)
                openDownloadedFile(resultUri, remotePath, mime, sizeBytes)
            }
        }
    }

    /**
     * Stream-read the local file at [uri] and return its SHA-256 hex digest. Used
     * as a fallback when we didn't get a hash from the remote `stat` probe (host
     * had no `sha256sum`/`shasum`/`openssl`). Reads in 64KB chunks so large files
     * don't blow heap.
     */
    private fun computeLocalSha256(uri: android.net.Uri): String? {
        val ctx = ServiceLocator.appContext
        return SilentlyTry.logged("SshAi-Chat", "compute SHA-256 of local file") {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            ctx.contentResolver.openInputStream(uri)?.use { stream ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = stream.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }
        }
    }

    private fun mimeForName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }
}
