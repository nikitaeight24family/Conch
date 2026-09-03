package ai.eight24family.conch.ui.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.adb.LocalAdbShell
import ai.eight24family.conch.adb.PairingNotifier
import ai.eight24family.conch.adb.PairingWatcher
import ai.eight24family.conch.ui.viewmodel.SettingsViewModel
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.delay

/**
 * "Phone bridge" — letting the agent read this phone's logs and run commands on
 * it, and nothing else.
 *
 * ⛔ TWO STATES, BOTH OBSERVED. There used to be three, and the third — "paired,
 * just arm the switch" — was read from a stored flag. A remembered claim about a
 * phone that can be un-paired from Android's own dialog at any moment, on a
 * device we cannot ask while adbd is unreachable. It went stale, the screen
 * showed a ✓ it could not back up, and correcting it meant writing a value by
 * hand: not a fix for one user, let alone everyone (owner, 2026-08-29).
 *
 * So this screen shows what it can see: either a command can run right now, or
 * it cannot. The remedy is one flow either way, because both halves of it live
 * behind the same Android switch — arm Wireless debugging, and if the phone does
 * not recognise Conch, open its pairing dialog. Conch notices that dialog by
 * itself and asks for the six digits in a notification.
 *
 * ⚠ The Wi-Fi sentence is a fact about Android, not about Conch: the platform
 * refuses to arm wireless debugging unless the phone is associated with a Wi-Fi
 * network, and reverts the setting within milliseconds otherwise (measured).
 * Nothing on the device gets around it without root, so never promise softer.
 */
@Composable
internal fun SettingsSectionBridge(@Suppress("UNUSED_PARAMETER") vm: SettingsViewModel) {
    val ctx = LocalContext.current

    // Starts UNKNOWN. hasLiveSession() is a field, and it stays true over a
    // socket the phone closed while this screen was away — so the screen opened
    // on "Ready ✓" and corrected itself two seconds later. A moment of "checking"
    // is honest; a moment of "Ready" is not.
    var connected by remember { mutableStateOf<Boolean?>(null) }
    var waitingForDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Landing here usually means the user just did the thing that fixes it.
        LocalAdbShell.retryNow()
        while (true) {
            connected = LocalAdbShell.check()
            if (connected == true) {
                waitingForDialog = false
                PairingWatcher.stop()
                PairingNotifier.clear(ctx)
            }
            delay(2_000)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        if (connected == null) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                Body("Checking the phone…")
            }
            return@Column
        }

        if (connected == true) {
            Step(
                "Ready ✓",
                "The agent can read this phone's logs and run shell commands.",
                icon = Icons.Filled.CheckCircle,
            ) {
                Body(
                    "Ask the agent in a chat to read the log, list the installed apps or run any " +
                        "shell command — it answers with the screen off too.",
                )
                Aside(
                    "This needs no network of any kind: the connection runs inside the phone and " +
                        "never leaves it.",
                )
            }
            return@Column
        }

        Step("Not connected", "One switch, and a code the first time.") {
            Body("The button opens Android's Developer options. There:")
            Instruction(1, "Turn on “Wireless debugging”.")
            Instruction(
                2,
                "If this phone does not recognise Conch yet, tap “Pair device with pairing code”. " +
                    "Conch sends you a notification with a box for the code — swipe the shade down " +
                    "over Android's dialog and type the six digits into it.",
            )
            Aside(
                "Leave Android's dialog open while you type — it cancels the pairing the moment it " +
                    "closes. That is why the box is in a notification instead of on this page.\n\n" +
                    "No Wi-Fi? Turn on this phone's own hotspot for a moment — Android needs a " +
                    "local network for wireless debugging, not an internet connection, and the " +
                    "hotspot is one. Once Conch is connected you can switch the hotspot back off; " +
                    "the connection stays.\n\n" +
                    "Android turns the switch off at every restart — and at every Wi-Fi drop, " +
                    "because it ties the switch to the network. That is its rule, not Conch's: " +
                    "nothing on the phone can turn it back on, not even shell-level access.\n\n" +
                    "Plugged into a computer, `adb tcpip 5555` is the other way in, and the only " +
                    "one that does not care about Wi-Fi at all — it survives the network going " +
                    "away until the phone restarts, and Conch tries it before anything else.\n\n" +
                    "Whichever way it is armed, Conch spends that moment starting this phone's " +
                    "Linux. After that the machine runs on its own until the phone restarts — " +
                    "no adb, no Wi-Fi, nothing to re-arm. Chats keep working either way; only " +
                    "reading this phone's logs and running commands ON it wait for the switch.",
            )
            Button(
                onClick = {
                    // ORDER MATTERS: arm the watcher BEFORE leaving, because the
                    // dialog can be open before we are asked again, and the code
                    // only exists while it is.
                    waitingForDialog = true
                    PairingWatcher.start(ctx)
                    openWirelessDebugging(ctx)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open Developer options")
            }
            if (waitingForDialog) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Text(
                        "Waiting for Android's pairing dialog…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * The words on this screen, in the same two weights the rest of Settings uses.
 *
 * ⚠ They used to be `outline`/`bodySmall` — the dimmest colour in the theme at
 * the smallest size, for the one screen whose whole job is to be READ and
 * followed, in the middle of a task the user has never done before (owner,
 * 2026-08-29). Instructions are the content here, not a footnote under it.
 */
@Composable
private fun Body(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** A numbered step. The number is a separate column so a wrapped step lines up
 *  under itself instead of under the digit. */
@Composable
private fun Instruction(number: Int, text: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            "$number.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun Aside(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A status row, then its actions. */
@Composable
private fun Step(
    title: String,
    subtitle: String,
    icon: ImageVector = Icons.Filled.Terminal,
    content: @Composable ColumnScope.() -> Unit,
) {
    SettingsRow(icon = icon, title = title, subtitle = subtitle)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/**
 * Open Developer options, asking for the "Wireless debugging" row to be scrolled
 * to and highlighted — and not relying on it.
 *
 * ⚠ The highlight is an AOSP convention (`:settings:fragment_args_key` =
 * `toggle_adb_wireless`) that OEM skins may ignore, and ColorOS does: it lands
 * on plain Developer options with nothing highlighted (owner, 2026-08-29). The
 * button therefore says "opens Developer options" and the text beside it NAMES
 * the row to look for — promising a highlight that never appears is worse than
 * promising nothing. Falls back to plain Developer options, then all Settings,
 * if the dev-options action cannot resolve at all.
 */
internal fun openWirelessDebugging(ctx: Context) {
    val key = "toggle_adb_wireless"
    val opened = SilentlyTry.loggedOrElse("Conch-Settings", "open wireless debugging (highlighted)", false) {
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
        SilentlyTry.fired("Conch-Settings", "open settings fallback") {
            ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
