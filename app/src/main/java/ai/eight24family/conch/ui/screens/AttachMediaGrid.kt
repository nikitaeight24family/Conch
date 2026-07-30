package ai.eight24family.conch.ui.screens

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import ai.eight24family.conch.ui.window.LocalAppWindowAdaptive
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The recent-media grid at the top of the attachment sheet — Telegram's most
 * recognisable piece: a live viewfinder in cell zero, then your recent photos,
 * three to a row (five once the window is medium-wide), scrolling DOWN.
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

/**
 * Columns in the grid: THREE on a phone, FIVE once the window is at least
 * medium-wide.
 *
 * The threshold is `isMediumOrWider` (>= 600dp) rather than `isExpanded`
 * (>= 840dp) deliberately: a book-style foldable's inner display lands around
 * 700dp in portrait, so keying off EXPANDED would leave the unfolded screen on
 * three columns — exactly the state he is asking about. Medium also catches the
 * tablet and DeX windows, where five is right for the same reason.
 */
internal fun gridColumns(mediumOrWider: Boolean): Int = if (mediumOrWider) 5 else 3

/**
 * How tall the grid may get before it scrolls inside itself: three and a half
 * rows of whatever the tile currently is.
 *
 * Derived, not guessed. The old `340.dp` was silently a FOUR-column number —
 * 3.5 x 93.75dp tile + 3 x 4dp gap on a 411dp window — so changing the column
 * count without it would have shrunk the visible grid to 2.6 rows. The half row
 * is deliberate: a clipped row is what tells the eye the grid scrolls.
 *
 * @param widthDp the grid's own width, minus nothing — the horizontal
 *   contentPadding is subtracted here.
 */
internal fun gridMaxHeightDp(widthDp: Float, columns: Int): Float {
    val usable = widthDp - H_PADDING_DP * 2 - GAP_DP * (columns - 1)
    val tile = (usable / columns).coerceAtLeast(48f)
    return tile * 3.5f + GAP_DP * 3
}

internal const val H_PADDING_DP = 12f
internal const val GAP_DP = 4f

/**
 * The grid. Scrolls DOWN. Renders NOTHING when there is no access and nothing to
 * offer, so the caller's tile row is then the whole sheet.
 *
 * @param onCameraFallback what a tap on cell zero does: opens the system camera.
 *   The viewfinder is a preview, never a shutter — see [CameraCell].
 */
@Composable
internal fun AttachMediaStrip(
    enabled: Boolean,
    onPick: (Uri) -> Unit,
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
        val columns = gridColumns(LocalAppWindowAdaptive.current.isMediumOrWider)
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxRows = gridMaxHeightDp(maxWidth.value, columns).dp
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(horizontal = H_PADDING_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(GAP_DP.dp),
            verticalArrangement = Arrangement.spacedBy(GAP_DP.dp),
            // Bounded so the sheet never swallows the screen; the grid itself
            // scrolls inside it.
            modifier = Modifier.fillMaxWidth().heightIn(max = maxRows),
        ) {
            if (onCameraFallback != null) {
                item(key = "camera") {
                    CameraCell(
                        enabled = enabled,
                        live = camera,
                        onRequestCamera = { permLauncher.launch(arrayOf(Manifest.permission.CAMERA)) },
                        onFallback = onCameraFallback,
                    )
                }
            }
            items(items, key = { it.uri.toString() }) { media ->
                MediaCell(media = media, enabled = enabled, onClick = { onPick(media.uri) })
            }
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
 * Cell zero: a LIVE viewfinder once CAMERA is granted. Tapping it OPENS THE
 * CAMERA — it does not shoot.
 *
 * ⚠ It used to shoot on tap, and that was wrong. A viewfinder that fires on
 * touch means every stray tap costs a 2.5 MB attachment the user never asked
 * for, and he had three of them from taps he read as "open the camera". The
 * preview is an affordance, not a shutter: the shot happens in the camera app,
 * after HE presses the shutter, and comes back through the same
 * ACTION_IMAGE_CAPTURE path a device without a viewfinder uses.
 *
 * Before the grant the cell is the same square with a camera glyph and a tap
 * asks for the permission. Asking is not optional politeness here: once this app
 * declares CAMERA, Android requires the grant for ACTION_IMAGE_CAPTURE too, so
 * without it there is nothing the tap could do.
 */
@Composable
private fun CameraCell(
    enabled: Boolean,
    live: Boolean,
    onRequestCamera: () -> Unit,
    onFallback: (() -> Unit)?,
) {
    val ctx = LocalContext.current
    val cyan = MaterialTheme.colorScheme.primary
    val tint = if (enabled) cyan else MaterialTheme.colorScheme.outline
    val lifecycleOwner = LocalLifecycleOwner.current

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.14f))
            .clickable(enabled = enabled) {
                if (live) onFallback?.invoke() else onRequestCamera()
            }
            .semantics { contentDescription = "Open the camera" },
    ) {
        if (live) {
            // The provider future can still be PENDING when the user swipes the
            // sheet away, and both sides of this cell have to survive that:
            // nothing may block waiting for it, and nothing may bind a camera to
            // a cell that is already gone. `remember` inside this branch gives
            // the flag exactly the branch's lifetime, so re-entering the
            // viewfinder starts alive again.
            val alive = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
            AndroidView(
                factory = { c ->
                    PreviewView(c).also { view ->
                        view.scaleType = PreviewView.ScaleType.FILL_CENTER
                        // The provider future resolves on a background thread the
                        // first time; bind on the main executor so the use cases
                        // attach to the view that is actually composed.
                        val future = ProcessCameraProvider.getInstance(c)
                        future.addListener({
                            // Late resolution after dismissal: binding here would
                            // hand the camera to a dead cell and hold it against
                            // every other app, with no DisposableEffect left to
                            // release it.
                            if (alive.get()) {
                                SilentlyTry.fired("SshAi-ChatPrompt", "bind camera preview") {
                                    val provider = future.get()
                                    val preview = Preview.Builder().build()
                                        .also { p -> p.setSurfaceProvider(view.surfaceProvider) }
                                    provider.unbindAll()
                                    // Preview ONLY. No ImageCapture use case is
                                    // bound any more: this cell cannot shoot, so
                                    // binding a capture pipeline would reserve
                                    // camera resources (and a JPEG encoder) for a
                                    // button that does not exist.
                                    provider.bindToLifecycle(
                                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                    )
                                }
                            }
                        }, ContextCompat.getMainExecutor(c))
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            // Release the camera when the sheet closes — holding it would block
            // every other app's camera for as long as the chat is open.
            //
            // ⚠ NEVER `.get()` the provider future here. onDispose runs on the
            // main thread, and CameraX retries provider init for up to 6 s
            // before it completes (1.4.x raised that window from 2.5 s — see the
            // camerax pin note in libs.versions.toml). Blocking on it while a
            // dismissal races init parks the UI past Android's 5 s
            // input-dispatch deadline, i.e. a real ANR rather than a stutter,
            // and SilentlyTry cannot save us because a parked thread throws
            // nothing. So: flip the flag synchronously, then unbind from the
            // future's own listener once it resolves. Listeners fire in
            // registration order on the same executor, so the bind listener
            // above always observes `alive == false` before this one runs.
            DisposableEffect(Unit) {
                onDispose {
                    alive.set(false)
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        SilentlyTry.fired("SshAi-ChatPrompt", "release camera") {
                            future.get().unbindAll()
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                }
            }
            // A small glyph over the preview so the cell still reads as "camera".
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

@Composable
private fun MediaCell(media: RecentMedia, enabled: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val thumb by produceState<ImageBitmap?>(initialValue = null, media.uri) {
        value = withContext(Dispatchers.IO) {
            SilentlyTry.logged("SshAi-ChatPrompt", "load media thumbnail") {
                // loadThumbnail asks MediaStore for a cached thumbnail — it never
                // decodes the full image, so a 12 MP photo costs what a small one
                // does and the grid stays smooth.
                // 512, not 320: at three columns a tile is ~126dp, which is
                // ~378px at 3x and ~504px at 4x — a 320px thumbnail would be
                // visibly upscaled. Still a cached MediaStore thumbnail, so a
                // 12 MP photo costs what a small one does.
                ctx.contentResolver.loadThumbnail(media.uri, android.util.Size(512, 512), null)
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
