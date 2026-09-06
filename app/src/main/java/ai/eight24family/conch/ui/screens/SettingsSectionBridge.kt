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
 * it cannot — and when it cannot, it renders [PhoneBridgeSteps], the same live
 * flow the modal shows. This page used to carry its own second copy of those
 * steps, written as a page of prose: two implementations of one flow, drifting
 * apart, and the only one anybody read was whichever came up first.
 *
 * What is left here that the modal does not have is the part that is not a
 * step: what this permission IS, and what it costs, for someone who came to
 * Settings to find out rather than because something failed.
 */
@Composable
internal fun SettingsSectionBridge(@Suppress("UNUSED_PARAMETER") vm: SettingsViewModel) {
    val ctx = LocalContext.current

    // Starts UNKNOWN. hasLiveSession() is a field, and it stays true over a
    // socket the phone closed while this screen was away — so the screen opened
    // on "Ready ✓" and corrected itself two seconds later. A moment of "checking"
    // is honest; a moment of "Ready" is not.
    var connected by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        // Landing here usually means the user just did the thing that fixes it.
        LocalAdbShell.retryNow()
        while (true) {
            connected = LocalAdbShell.check()
            if (connected == true) {
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
            // The steps, live, exactly as the modal runs them — including the
            // watcher that catches Android's pairing dialog by itself.
            PhoneBridgeSteps()
            // ⛔ NOTHING HERE MAY NAME A COMPUTER. This paragraph used to offer
            // `adb tcpip 5555` "plugged into a computer" as the better way in —
            // advice about a machine most of the people reading it do not own,
            // inside the app whose whole premise is that they do not need one
            // (owner, 2026-09-06). The steps above are the entire path, and
            // they need nothing but this phone.
            //
            // ⚠ AND THE APP MUST NOT ISSUE `tcpip` FOR THEM EITHER, though it
            // could: adbd runs it for any client on an existing connection, so
            // one service open during a live session would buy a listener that
            // outlives every Wi-Fi drop. It also puts adb on 0.0.0.0 — a debug
            // port on the LAN of a phone whose owner asked for none of that,
            // against a store listing, a landing page, an About screen and a
            // privacy policy that all say this app opens nothing but the
            // servers the user adds. Measured cost of NOT doing it: one switch
            // per restart, which is what the wizard is for.
            Aside(
                "Android turns the switch off at every restart, and at every Wi-Fi drop, because " +
                    "it ties the switch to the network. That is its rule, not Conch's: nothing " +
                    "on the phone can turn it back on — not the app, and not shell-level access " +
                    "either (measured: Android refuses both, from the same shell it hands out).\n\n" +
                    "Conch spends that moment starting this phone's Linux. After that the " +
                    "machine runs on its own until the phone restarts — no adb, no Wi-Fi, " +
                    "nothing to re-arm. Chats keep working either way; only reading this " +
                    "phone's logs and running commands ON it wait for the switch.",
            )
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
 * button therefore says "opens the page" and the text beside it NAMES the row
 * to look for — promising a highlight that never appears is worse than
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
    // ⚠ THE SAME PAGE WITHOUT THE EXTRAS IS ITS OWN RUNG, and it was missing:
    // the ladder went from "the highlighted row" straight to "all of Settings",
    // so an OEM that rejects the fragment-args deep link (they validate the
    // caller, and the strict ones just finish with a toast) cost the user the
    // dev-options page entirely — over an argument that is only ever a nicety.
    val plain = opened || SilentlyTry.loggedOrElse("Conch-Settings", "open developer options", false) {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }
    if (!plain) {
        SilentlyTry.fired("Conch-Settings", "open settings fallback") {
            ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
