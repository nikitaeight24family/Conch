package ai.eight24family.conch.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.diagnostics.ShizukuShell
import ai.eight24family.conch.ui.viewmodel.SettingsViewModel
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class BridgeStage { NotInstalled, NotRunning, NotGranted, Ready }

/**
 * "Phone bridge (Shizuku)" — a state-aware, step-by-step guide that gets
 * the user from nothing to "the agent can run adb-shell commands on this
 * phone", showing ONLY the current step. It auto-advances as the live
 * Shizuku state changes (polled), so a pro just taps the one prominent
 * action and moves on; the detailed instructions sit quietly beneath it
 * for whoever needs them. This is the only UI that grants the Shizuku
 * permission.
 */
@Composable
internal fun SettingsSectionBridge(@Suppress("UNUSED_PARAMETER") vm: SettingsViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    fun detect(): BridgeStage = when {
        ShizukuShell.available() -> BridgeStage.Ready
        ShizukuShell.bound() -> BridgeStage.NotGranted
        ShizukuShell.installed(ctx) -> BridgeStage.NotRunning
        else -> BridgeStage.NotInstalled
    }

    var stage by remember { mutableStateOf(detect()) }
    var requesting by remember { mutableStateOf(false) }

    // Poll the live state (cheap local IPC + a PM lookup) so the guide
    // auto-advances as the user installs / starts / grants in Shizuku and
    // returns. Frozen while the consent dialog is up to avoid flicker.
    LaunchedEffect(Unit) {
        while (true) {
            if (!requesting) stage = detect()
            delay(1500)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "Let the AI agent on your server read this phone's logs and run shell commands over your SSH connection — the same level as adb shell, no cable, no root. Powered by Shizuku; data only ever goes to your own server.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (stage) {
            BridgeStage.NotInstalled -> Step(1, "Install Shizuku",
                "A free, open-source helper that grants adb-shell rights on-device — no PC, no root.") {
                OutlinedButton(onClick = { openShizuku(ctx) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Install Shizuku")
                }
            }

            BridgeStage.NotRunning -> Step(2, "Start Shizuku",
                "Wireless debugging — no PC. (Has to be redone after each reboot.)") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { openWirelessDebugging(ctx) }, modifier = Modifier.weight(1f)) {
                        Text("Wireless debugging")
                    }
                    Button(onClick = { openShizuku(ctx) }, modifier = Modifier.weight(1f)) {
                        Text("Open Shizuku")
                    }
                }
                Text(
                    "1. Tap “Wireless debugging” → turn it on (it lands on that toggle; on some skins, on Developer options — scroll to it).\n" +
                        "2. In Shizuku: “Start with wireless debugging” → pair with the code.\n" +
                        "3. Shizuku home → Start → it shows “running”.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            BridgeStage.NotGranted -> Step(3, "Allow Conch",
                "Shizuku is running — grant Conch access. One tap.") {
                Button(
                    onClick = {
                        scope.launch {
                            requesting = true
                            if (ShizukuShell.requestPermission()) stage = BridgeStage.Ready
                            requesting = false
                        }
                    },
                    enabled = !requesting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (requesting) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Text("Enable — grant Conch Shizuku access")
                }
            }

            BridgeStage.Ready -> Step(null, "Ready ✓",
                "The agent can now read logs and run shell commands on this phone.",
                icon = Icons.Filled.CheckCircle) {
                Text(
                    "From a chat the agent runs e.g.  conch-bridge shell 'pm list packages'  or  conch-bridge logs --lines 200. Keep Conch in the foreground — polling pauses when it's backgrounded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** One guide step: a status row ("Step N/3 · title") then its actions. */
@Composable
private fun Step(
    step: Int?,
    title: String,
    subtitle: String,
    icon: ImageVector = Icons.Filled.Terminal,
    content: @Composable ColumnScope.() -> Unit,
) {
    SettingsRow(
        icon = icon,
        title = if (step != null) "Step $step/3 · $title" else title,
        subtitle = subtitle,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

private fun openShizuku(ctx: Context) {
    val launch = ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
    if (launch != null) {
        SilentlyTry.fired("SshAi-Settings", "open shizuku app") { ctx.startActivity(launch) }
        return
    }
    SilentlyTry.fired("SshAi-Settings", "open shizuku play page") {
        ctx.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * Open Developer options scrolled-to + highlighting the "Wireless
 * debugging" row, using AOSP's preference-highlight extras
 * (`:settings:fragment_args_key` = the AOSP key `toggle_adb_wireless`).
 * Best-effort: stock/Pixel honours it (scroll + flash); OEM skins
 * (Samsung One UI) usually ignore an unknown key and just open Developer
 * options — still the right screen. Falls back to plain Developer options,
 * then all Settings, if the dev-options action can't resolve at all.
 */
private fun openWirelessDebugging(ctx: Context) {
    val key = "toggle_adb_wireless"
    val opened = SilentlyTry.loggedOrElse("SshAi-Settings", "open wireless debugging (highlighted)", false) {
        val args = android.os.Bundle().apply { putString(":settings:fragment_args_key", key) }
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                putExtra(":settings:fragment_args_key", key)
                putExtra(":settings:show_fragment_args", args)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        true
    }
    if (!opened) {
        SilentlyTry.fired("SshAi-Settings", "open settings fallback") {
            ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
