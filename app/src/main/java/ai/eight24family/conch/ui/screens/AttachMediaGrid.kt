package ai.eight24family.conch.ui.screens

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The recent-media grid at the top of the attachment sheet — Telegram's most
 * recognisable piece: a live viewfinder in cell zero, then your recent photos,
 * four to a row, scrolling DOWN.
 *
 * ⚠ THIS COSTS TWO RESTRICTED THINGS.
 *
 * 1. READ_MEDIA_IMAGES / READ_MEDIA_VIDEO to render our own grid. Google Play
 *    classes both as restricted: an app targeting API 33+ may request them only
 *    when a system picker is insufficient for CORE functionality, and shipping
 *    them needs an approved Play Console declaration. There is no substitute —
 *    READ_MEDIA_VISUAL_USER_SELECTED cannot be declared on its own.
 * 2. CAMERA, for the live preview. Frames for a viewfinder have to come from
 *    our own process, so the capture-by-intent trick (which needs no permission)
 *    cannot draw one.
 *
 * Both were requested by the user with the trade-offs stated (2026-07-29). The
 * whole thing degrades to nothing: no media access, no camera grant, or an API
 * below 29 and the sheet is just its tile row, which is a complete attachment
 * menu on its own.
 *
 * Nothing is auto-prompted. Each permission is asked for only when the user taps
 * the thing that needs it.
 */

/** One item in the grid. */
internal data class RecentMedia(
    val uri: Uri,
    val isVideo: Boolean,
)

/** What level of media access we actually have RIGHT NOW. Never cached — the
 *  user can change it in Settings while we are backgrounded. */
internal enum class MediaAccess { FULL, PARTIAL, NONE }

/**
 * Can this device back the grid at all?
 *
 * Both `MediaStore.Files.getContentUri(VOLUME_EXTERNAL)` and
 * `ContentResolver.loadThumbnail` are API 29. minSdk here is 26, so on Android 8
 * and 9 the grid does not exist and the sheet is the tile row.
 */
internal fun mediaStripSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

internal fun mediaAccess(ctx: Context): MediaAccess {
    fun granted(p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            (granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO)) ->
            MediaAccess.FULL
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ->
            MediaAccess.PARTIAL
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            granted(Manifest.permission.READ_EXTERNAL_STORAGE) ->
            MediaAccess.FULL
        else -> MediaAccess.NONE
    }
}

internal fun cameraGranted(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

/** The permissions to ask for, per platform level. Requested in ONE call — two
 *  dialogs back to back reads as the app malfunctioning. */
internal fun mediaPermissionsToRequest(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
    )
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/**
 * Newest [limit] images and videos the app is allowed to see.
 *
 * The SAME query serves full and partial access — under partial access
 * MediaStore returns only what the user picked, so there is no second path.
 */
internal suspend fun loadRecentMedia(ctx: Context, limit: Int = 120): List<RecentMedia> =
    withContext(Dispatchers.IO) {
        SilentlyTry.loggedOrElse("SshAi-ChatPrompt", "query recent media", emptyList()) {
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
            )
            val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
            val args = arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            )
            val out = ArrayList<RecentMedia>(limit)
            ctx.contentResolver.query(
                collection, projection, selection, args,
                "${MediaStore.Files.FileColumns.DATE_ADDED} DESC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val typeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                while (c.moveToNext() && out.size < limit) {
                    out += RecentMedia(
                        uri = ContentUris.withAppendedId(collection, c.getLong(idCol)),
                        isVideo = c.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO,
                    )
                }
            }
            out
        }
    }

/** Columns in the grid. Four across is what the user asked for; it also makes
 *  each cell roughly twice the old two-row strip's height on a phone. */
private const val GRID_COLUMNS = 4

/**
 * The grid. Scrolls DOWN. Renders NOTHING when there is no access and nothing to
 * offer, so the caller's tile row is then the whole sheet.
 *
 * @param onCapture called with a JPEG the viewfinder just took.
 * @param onCameraFallback tapped when there is no live preview to tap — opens
 *   the system camera instead, so the cell is never a dead square.
 */
@Composable
internal fun AttachMediaStrip(
    enabled: Boolean,
    onPick: (Uri) -> Unit,
    onCapture: ((java.io.File) -> Unit)?,
    onCameraFallback: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    if (!mediaStripSupported()) return
    // Re-read on every open: the user may have changed a grant in Settings.
    var access by remember { mutableStateOf(mediaAccess(ctx)) }
    var camera by remember { mutableStateOf(cameraGranted(ctx)) }
    var items by remember { mutableStateOf<List<RecentMedia>>(emptyList()) }
    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) {
        access = mediaAccess(ctx)
        camera = cameraGranted(ctx)
    }
    LaunchedEffect(access) {
        items = if (access == MediaAccess.NONE) emptyList() else loadRecentMedia(ctx)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (access == MediaAccess.NONE) {
            // Ask ONLY on an explicit tap. Never on sheet open.
            TextButton(
                onClick = { permLauncher.launch(mediaPermissionsToRequest() + Manifest.permission.CAMERA) },
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("Show recent photos") }
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(GRID_COLUMNS),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            // Bounded so the sheet never swallows the screen; the grid itself
            // scrolls inside it.
            modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp),
        ) {
            if (onCapture != null || onCameraFallback != null) {
                item(key = "camera") {
                    CameraCell(
                        enabled = enabled,
                        live = camera,
                        onCapture = onCapture,
                        onRequestCamera = { permLauncher.launch(arrayOf(Manifest.permission.CAMERA)) },
                        onFallback = onCameraFallback,
                    )
                }
            }
            items(items, key = { it.uri.toString() }) { media ->
                MediaCell(media = media, enabled = enabled, onClick = { onPick(media.uri) })
            }
        }
        if (access == MediaAccess.PARTIAL) {
            // Android 14+ partial grant: the app can only ever see what was
            // picked, so give the user the way back to that dialog instead of
            // leaving them wondering where the rest of their photos went.
            TextButton(
                onClick = { permLauncher.launch(mediaPermissionsToRequest()) },
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("Manage which photos Conch can see") }
        }
    }
}

/**
 * Cell zero: a LIVE viewfinder once CAMERA is granted, tap to shoot.
 *
 * Before the grant it is the same square with a camera glyph — tapping asks for
 * the permission rather than doing nothing. If the grant is refused it still
 * works: the tap falls back to the system camera app, which needs no permission
 * of ours.
 */
@Composable
private fun CameraCell(
    enabled: Boolean,
    live: Boolean,
    onCapture: ((java.io.File) -> Unit)?,
    onRequestCamera: () -> Unit,
    onFallback: (() -> Unit)?,
) {
    val ctx = LocalContext.current
    val cyan = MaterialTheme.colorScheme.primary
    val tint = if (enabled) cyan else MaterialTheme.colorScheme.outline
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember { ImageCapture.Builder().build() }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.14f))
            .clickable(enabled = enabled) {
                when {
                    !live -> onRequestCamera()
                    onCapture != null -> takePhoto(ctx, imageCapture, onCapture)
                    else -> onFallback?.invoke()
                }
            }
            .semantics { contentDescription = "Take a photo" },
    ) {
        if (live && onCapture != null) {
            AndroidView(
                factory = { c ->
                    PreviewView(c).also { view ->
                        view.scaleType = PreviewView.ScaleType.FILL_CENTER
                        // The provider future resolves on a background thread the
                        // first time; bind on the main executor so the use cases
                        // attach to the view that is actually composed.
                        val future = ProcessCameraProvider.getInstance(c)
                        future.addListener({
                            SilentlyTry.fired("SshAi-ChatPrompt", "bind camera preview") {
                                val provider = future.get()
                                val preview = Preview.Builder().build()
                                    .also { p -> p.setSurfaceProvider(view.surfaceProvider) }
                                provider.unbindAll()
                                provider.bindToLifecycle(
                                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview, imageCapture,
                                )
                            }
                        }, ContextCompat.getMainExecutor(c))
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            // Release the camera when the sheet closes — holding it would block
            // every other app's camera for as long as the chat is open.
            DisposableEffect(Unit) {
                onDispose {
                    SilentlyTry.fired("SshAi-ChatPrompt", "release camera") {
                        ProcessCameraProvider.getInstance(ctx).get().unbindAll()
                    }
                }
            }
            // A small glyph over the preview so the cell still reads as "shoot".
            Icon(
                Icons.Default.PhotoCamera,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(22.dp),
            )
        } else {
            Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = tint)
        }
    }
}

/** Shoot straight into the capture folder and hand the file back. */
private fun takePhoto(ctx: Context, capture: ImageCapture, onCapture: (java.io.File) -> Unit) {
    SilentlyTry.fired("SshAi-ChatPrompt", "take photo") {
        val dir = java.io.File(ctx.cacheDir, "conch_camera").apply { mkdirs() }
        val file = java.io.File(dir, "cam_${System.currentTimeMillis()}.jpg")
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(ctx),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) = onCapture(file)
                override fun onError(exc: ImageCaptureException) {
                    android.util.Log.w("SshAi-ChatPrompt", "capture failed: ${exc.message}")
                    SilentlyTry.fired("SshAi-ChatPrompt", "delete failed capture") { file.delete() }
                }
            },
        )
    }
}

@Composable
private fun MediaCell(media: RecentMedia, enabled: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val thumb by produceState<ImageBitmap?>(initialValue = null, media.uri) {
        value = withContext(Dispatchers.IO) {
            SilentlyTry.logged("SshAi-ChatPrompt", "load media thumbnail") {
                // loadThumbnail asks MediaStore for a cached thumbnail — it never
                // decodes the full image, so a 12 MP photo costs what a small one
                // does and the grid stays smooth.
                ctx.contentResolver.loadThumbnail(media.uri, android.util.Size(320, 320), null)
                    .asImageBitmap()
            }
        }
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = if (media.isVideo) "Attach video" else "Attach photo" },
    ) {
        thumb?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (media.isVideo) {
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
