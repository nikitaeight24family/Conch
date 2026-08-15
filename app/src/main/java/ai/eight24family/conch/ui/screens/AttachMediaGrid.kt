package ai.eight24family.conch.ui.screens

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.photopicker.compose.EmbeddedPhotoPicker
import androidx.photopicker.compose.ExperimentalPhotoPickerComposeApi
import androidx.photopicker.compose.rememberEmbeddedPhotoPickerState
import ai.eight24family.conch.util.SilentlyTry

/**
 * Can this device render the SYSTEM photo picker INSIDE our own layout?
 *
 * Requirements read out of the library's own bytecode rather than the docs:
 * `EmbeddedPhotoPicker` carries `@RequiresApi(34)` and
 * `@RequiresExtension(extension = 34, version = 15)`. Below that the sheet keeps
 * the camera tile and photos come from the full-screen system picker, which the
 * tile row launches — also permission-free, just not inline.
 */
internal fun embeddedPickerSupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        android.os.ext.SdkExtensions.getExtensionVersion(
            Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
        ) >= 15

internal fun cameraGranted(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

/**
 * The camera cell at the top of the attachment sheet — a LIVE viewfinder that
 * opens the camera when tapped.
 *
 * ⛔ THE RECENT-PHOTOS GRID THAT USED TO LIVE HERE IS GONE, AND MUST NOT COME
 * BACK IN THIS FORM. It drew our own thumbnails straight out of MediaStore,
 * which costs READ_MEDIA_IMAGES / READ_MEDIA_VIDEO — and Google Play rejected
 * the app for exactly that on 2026-07-30, under the Photo and Video Permissions
 * policy, with enforcement applied: the release was blocked and production
 * stayed on the old version. Those permissions are for apps a picker cannot
 * serve at all (a gallery, an editor); a convenience grid in a composer is not
 * one, and re-declaring it would just be rejected again.
 *
 * Photos now come from the system photo picker, which the tile row below already
 * launches and which needs no permission whatsoever. If the in-sheet grid is
 * wanted back, the compliant way is the EMBEDDED photo picker
 * (`androidx.photopicker:photopicker-compose`) — it renders inside our own
 * layout on a SurfaceView, still asks for nothing, and needs Android 14 with SDK
 * Extensions 15, so it must degrade to the plain picker below that.
 *
 * The camera is a different policy and is unaffected: CAMERA is declared for the
 * viewfinder, and nothing here reads the user's library.
 */
@Composable
internal fun AttachMediaStrip(
    enabled: Boolean,
    onPick: (Uri) -> Unit,
    onSelectionDone: () -> Unit,
    onCameraFallback: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** Rendered to the RIGHT OF THE VIEWFINDER — beside the camera, not beside
     * The distinction is the whole layout: the tiles must line up with the
     * camera cell. */
    besideCamera: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
) {
    if (onCameraFallback == null) return
    val ctx = LocalContext.current
    // Re-read on every open: the user may have changed the grant in Settings.
    var camera by remember { mutableStateOf(cameraGranted(ctx)) }
    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { camera = cameraGranted(ctx) }

    Column(modifier = modifier.fillMaxWidth()) {
        // ⚠ NO WIDTH IS ASSUMED. This ships to every screen Play serves, so the
        // shelf may never depend on a number measured against one phone: on a
        // narrow device a fixed row simply pushed the last tile off the edge
        // (2026-08-04). FlowRow wraps instead of clipping — the tiles take a
        // second line when they must, and nothing is ever unreachable.
        // ⚠ PROPORTIONS, NOT MEASUREMENTS. Every size on this shelf is a share
        // of the width it is actually given: the viewfinder takes a fixed
        // fraction (clamped so it stays a usable square on a small phone and
        // does not become a poster on a tablet), and the tiles beside it split
        // what is left equally. It is one row on every screen Play serves,
        // nothing is ever clipped, and no number here was measured on one
        // device — which is exactly what went wrong before (2026-08-04).
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val shelf = maxWidth - 24.dp
        val cameraW = (shelf * 0.28f).coerceIn(72.dp, 132.dp)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(modifier = Modifier.width(cameraW).aspectRatio(1f)) {
                CameraCell(
                    enabled = enabled,
                    live = camera,
                    onRequestCamera = { permLauncher.launch(arrayOf(Manifest.permission.CAMERA)) },
                    onFallback = onCameraFallback,
                )
            }
            if (besideCamera != null) besideCamera()
        }
        }
    }
}

/**
 * The system photo picker, rendered inside our sheet.
 *
 * It runs in the system's own process on a SurfaceView, so we never see the
 * user's library — we only receive URIs the picker has already granted us. That
 * is the whole reason this exists: the same in-sheet grid, with zero permissions,
 * after Play rejected the MediaStore version.
 *
 * Selection does NOT close the sheet — the picker supports multi-select, and
 * closing on the first tap would fight it. `onSelectionComplete` is the picker
 * telling us the user is done, and that is what dismisses.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@OptIn(ExperimentalPhotoPickerComposeApi::class)
@Composable
private fun EmbeddedPickerPane(
    onPick: (Uri) -> Unit,
    onSelectionDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberEmbeddedPhotoPickerState(
        onUriPermissionGranted = { uris -> uris.forEach(onPick) },
        onSelectionComplete = onSelectionDone,
        onSessionError = { t ->
            // Alpha library talking to a system surface: if the session dies, the
            // sheet keeps its camera tile and the picker tile below still opens
            // the full-screen picker. Never a crash, never a dead grey box.
            android.util.Log.w("SshAi-ChatPrompt", "embedded photo picker session error: ${t.message}")
        },
    )
    EmbeddedPhotoPicker(state = state, modifier = modifier)
}

/** Height of the inline picker: about three rows of the system grid, so the sheet
 *  still leaves the conversation visible behind it. */
private const val EMBEDDED_PICKER_DP = 340

/** Side of the viewfinder tile. Matches the old three-column tile on a phone, so
 *  the sheet keeps the proportions the user picked. */
private const val CAMERA_CELL_DP = 88

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
