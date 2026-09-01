package ai.eight24family.conch.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import ai.eight24family.conch.ui.viewmodel.ChatViewModel
import ai.eight24family.conch.util.SilentlyTry

/**
 * Open-downloaded-file routing. Three events the VM can emit after the
 * disk-icon click: open-internal (navigate to our viewer), open-external
 * (fire ACTION_VIEW), or prompt (show a chooser bottom sheet — user
 * picks + optionally remembers).
 *
 * Hoisted out of ChatScreen so the orchestrator stays focused on layout
 * and state coordination. The bottom-sheet host stays here next to the
 * effects that feed it.
 */
@Composable
internal fun ChatFileOpenHandlers(
    vm: ChatViewModel,
    serverId: String,
    onOpenTextViewer: (
        uri: android.net.Uri,
        filename: String,
        serverId: String,
        remotePath: String,
    ) -> Unit,
) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        vm.openInViewer.collect { req ->
            onOpenTextViewer(req.uri, req.filename, req.serverId, req.remotePath)
        }
    }
    LaunchedEffect(Unit) {
        vm.openExternally.collect { req ->
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(req.uri, req.mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // WARNING: A REMEMBERED CHOICE MUST NOT BE ABLE TO KILL THE BUTTON.
            //
            // This used to be a bare swallow. Tick "remember for .ext files" +
            // "Other app" for a type nothing on the phone handles (.jsonl, .log,
            // .toml, an .apk with unknown-sources off) - or have the handler app
            // uninstalled later - and every future tap of the disk icon threw
            // ActivityNotFoundException into a log and did NOTHING: no sheet, no
            // error, no way back, because the chooser is skipped once a choice is
            // remembered and no screen can un-remember it. The only escape was
            // Settings -> Delete all data, which also destroys every server and key.
            //
            // So a failure UN-REMEMBERS the choice and re-offers the chooser. The
            // remembered path is a shortcut; a shortcut that stops working has to
            // give the long way back.
            val opened = runCatching { ctx.startActivity(intent) }.isSuccess
            if (!opened) {
                android.util.Log.w(
                    "Conch-FileOpen",
                    "no app handles ${req.mime} - forgetting the remembered choice and asking again",
                )
                vm.openFileFallbackToPrompt(req)
            }
        }
    }
    LaunchedEffect(Unit) {
        vm.shareFile.collect { req ->
            val send = Intent(Intent.ACTION_SEND).apply {
                type = req.mime
                putExtra(Intent.EXTRA_STREAM, req.uri)
                putExtra(Intent.EXTRA_TITLE, req.filename)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, req.filename).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            SilentlyTry.fired("Conch-FileOpen", "startActivity share chooser") { ctx.startActivity(chooser) }
        }
    }
    var openPrompt by remember {
        mutableStateOf<ChatViewModel.OpenFilePromptRequest?>(null)
    }
    LaunchedEffect(Unit) {
        vm.openFilePrompt.collect { openPrompt = it }
    }
    openPrompt?.let { prompt ->
        OpenFileChooserSheet(
            request = prompt,
            onPick = { choice, rememberPick ->
                if (rememberPick && prompt.extension.isNotBlank()) {
                    vm.rememberOpenFileChoice(prompt.extension, choice)
                }
                when (choice) {
                    "internal" -> onOpenTextViewer(
                        prompt.uri, prompt.filename, serverId, prompt.remotePath
                    )
                    "external" -> {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(prompt.uri, prompt.mime)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        SilentlyTry.fired("Conch-FileOpen", "startActivity from prompt: view") { ctx.startActivity(intent) }
                    }
                    "share" -> {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = prompt.mime
                            putExtra(Intent.EXTRA_STREAM, prompt.uri)
                            putExtra(Intent.EXTRA_TITLE, prompt.filename)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        val chooser = Intent.createChooser(send, prompt.filename).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        SilentlyTry.fired("Conch-FileOpen", "startActivity from prompt: share") { ctx.startActivity(chooser) }
                    }
                }
                openPrompt = null
            },
            onDismiss = { openPrompt = null },
        )
    }
}
