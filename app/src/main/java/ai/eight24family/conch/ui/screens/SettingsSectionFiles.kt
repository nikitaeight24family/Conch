package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.ui.viewmodel.SettingsViewModel
import ai.eight24family.conch.util.SilentlyTry

@Composable
internal fun SettingsSectionFiles(vm: SettingsViewModel) {
    DownloadsFolderRow(vm)
}

/**
 * "Downloads folder" row in Settings. Default path is
 * `Download/sshai/` (MediaStore). Tapping "Choose folder" opens the
 * system `ACTION_OPEN_DOCUMENT_TREE` picker; the resulting tree URI
 * gets a `persistableUriPermission` so it survives app restarts.
 *
 * The picked tree URI is stored in prefs and used by
 * `ChatViewModel.performRemoteDownload` as the write target. Files
 * created inside it are owned by the user — they choose where in
 * their phone's storage downloads land.
 */
@Composable
private fun DownloadsFolderRow(vm: SettingsViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val display by vm.downloadsFolderDisplay.collectAsState()
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri: android.net.Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Make the grant survive app restarts. Without this the URI
        // works for one Activity-result hop and then throws
        // SecurityException on next use.
        SilentlyTry.fired("SshAi-Settings", "takePersistableUriPermission") {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val label = SilentlyTry.logged("SshAi-Settings", "read downloads folder name") {
            androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, uri)?.name
        } ?: uri.lastPathSegment ?: uri.toString()
        vm.setDownloadsFolder(uri, label)
    }
    SettingsRow(
        icon = Icons.Filled.Folder,
        title = "Downloads folder",
        subtitle = display ?: "Default: Download/sshai/",
        onClick = { launcher.launch(null) },
    ) {
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
        )
    }
    if (display != null) {
        TextButton(
            onClick = { vm.setDownloadsFolder(null, null) },
            modifier = Modifier.padding(start = 36.dp),
        ) {
            Text(
                "Reset to default",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
