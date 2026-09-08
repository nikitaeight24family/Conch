package ai.eight24family.conch.ui.screens

import ai.eight24family.conch.linux.LocalLlm
import ai.eight24family.conch.linux.PhoneResources
import ai.eight24family.conch.ui.window.handCursor
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * `[ import ]` — a `.gguf` the owner already has becomes a model in the
 * library.
 *
 * ⛔ WHY IT MATTERS: anyone who runs local models on a phone already has
 * files — pulled on a laptop, copied off an SD card, quantised themselves —
 * and until now the app could only offer them a download of something else.
 *
 * The bytes are COPIED into the app's own storage, and the dialog says so
 * before it costs anything: the engine is a separate process that mmaps a
 * PATH, a SAF document is not one, and removable storage can vanish
 * mid-answer. The size verdict comes from free disk, because a phone with
 * 3 GB left should hear that before a 5 GB copy starts, not after.
 */
@Composable
internal fun ImportModelAction() {
    val ctx = LocalContext.current
    var pending by remember {
        mutableStateOf<Triple<android.net.Uri, String, Long>?>(null)
    }
    var problem by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        // GGUF has no registered mime type; every provider reports something
        // different for it (octet-stream, or nothing at all), so the filter is
        // the NAME, checked below.
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "model.gguf"
        var size = 0L
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        .takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                    c.getColumnIndex(OpenableColumns.SIZE)
                        .takeIf { it >= 0 }?.let { size = c.getLong(it) }
                }
            }
        }
        if (!name.endsWith(".gguf", ignoreCase = true)) {
            problem = "$name is not a .gguf — Conch runs GGUF models"
            return@rememberLauncherForActivityResult
        }
        pending = Triple(uri, name, size)
    }

    Text(
        "[ import ]",
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .handCursor()
            .clickable { problem = null; picker.launch(arrayOf("*/*")) }
            .padding(horizontal = 8.dp, vertical = 8.dp),
    )

    problem?.let { msg ->
        AlertDialog(
            onDismissRequest = { problem = null },
            title = { Text("Can't import that") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { problem = null }) { Text("ok") } },
        )
    }

    pending?.let { (uri, name, size) ->
        val free = remember(name) { runCatching { PhoneResources.read().diskFreeBytes }.getOrDefault(0L) }
        val tooBig = size > 0L && free in 1 until size + 200_000_000L
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Import this model?") },
            text = {
                Text(
                    buildString {
                        append(name)
                        if (size > 0L) {
                            append("\n\n")
                            append(PhoneResources.gb(size))
                            append(" GB will be COPIED into Conch's own storage — the engine")
                            append(" opens a real file, and a picked document is not one.")
                            append("\n\nFree space: ")
                            append(PhoneResources.gb(free))
                            append(" GB")
                        }
                        if (tooBig) append("\n\nThere is not enough room for this copy.")
                    },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !tooBig,
                    onClick = {
                        LocalLlm.importFrom(uri, name, size)
                        pending = null
                    },
                ) { Text("import") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("cancel") } },
        )
    }
}
