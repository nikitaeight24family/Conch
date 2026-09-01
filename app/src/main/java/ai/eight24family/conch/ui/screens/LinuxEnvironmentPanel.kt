package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.linux.LinuxEnv
import ai.eight24family.conch.linux.LinuxInstaller
import ai.eight24family.conch.linux.LinuxSsh
import kotlinx.coroutines.launch

/**
 * "Linux environment" — a real distribution, on the phone, for someone who has
 * no computer and no server.
 *
 * It is Alpine with `apk`: 25 000 packages, python, git, compilers, whatever the
 * user needs, installed from inside it. No root anywhere — the userland runs
 * under a syscall-rewriting runtime as the shell uid the phone bridge already
 * obtained, and believes it is root only within its own directory tree.
 *
 * ⛔ THIS PAGE IS THE ENVIRONMENT'S LIFECYCLE AND NOTHING ELSE — no agent list,
 * no install buttons, no "open chat". Installing it REGISTERS THE PHONE AS A
 * SERVER, and from that moment it is reached exactly like every other machine:
 * the same list, the same picker, the same install / login / chat. A
 * phone-shaped copy of those screens is a second thing to keep correct and a
 * second thing for the owner to learn.
 *
 * What the old version of this page said — "Ready ✓" and three paragraphs
 * restating the previous screen — is gone for the same reason. The one line
 * kept is the SIZE, because that is the fact a phone owner decides on.
 */
@Composable
fun LinuxEnvironmentPanel() {
    val scope = rememberCoroutineScope()

    var presence by remember { mutableStateOf<LinuxEnv.Presence?>(null) }
    var size by remember { mutableStateOf<String?>(null) }
    var busyStep by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var confirmRemove by remember { mutableStateOf(false) }

    LaunchedEffect(refresh) {
        presence = LinuxEnv.presence()
        if (presence == LinuxEnv.Presence.INSTALLED) {
            // An environment set up by an older Conch keeps that Conch's
            // runtime, and the runtime is what decides whether anything inside
            // runs at all. Free once the versions agree.
            LinuxInstaller.ensureRuntimeCurrent()
            // And it is a machine, so it has a row — including when it was
            // installed before that was true.
            LinuxSsh.ensureRow()
            size = LinuxEnv.diskUsage()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        when {
            busyStep != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    LinuxBody(busyStep!!)
                }
                LinuxAside("It takes a few seconds. Nothing is downloaded — the whole system ships inside Conch.")
            }

            presence == null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    LinuxBody("Checking…")
                }
            }

            presence == LinuxEnv.Presence.INSTALLED -> {
                size?.let { LinuxAside("Using $it") }
                LinuxBody("It is on the machines list, as any other machine is.")
                OutlinedButton(
                    onClick = { confirmRemove = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Remove Linux") }
            }

            presence == LinuxEnv.Presence.UNREACHABLE -> {
                SettingsRow(
                    icon = Icons.Filled.Terminal,
                    title = "Phone shell not connected",
                    subtitle = "The environment is installed; Conch cannot reach it right now.",
                )
                LinuxAside(
                    "Turn Wireless debugging on once (Android only allows it on Wi-Fi) and it comes " +
                        "back — nothing here has to be installed again.",
                )
            }

            else -> {
                SettingsRow(
                    icon = Icons.Filled.Terminal,
                    title = "Not installed",
                    subtitle = "A full Linux, on this phone, with no computer and no server.",
                )
                LinuxBody(
                    "Alpine Linux with its package manager — 25 000 packages, from python and git " +
                        "to compilers. It runs as an ordinary app would: no root, nothing unlocked, " +
                        "nothing outside its own folder is touched.",
                )
                // ⚠ SAY WHAT HAS NO NETWORK, AND WHAT DOES. The first wording
                // said "installing it opens no network connection at all", which
                // reads as "this Linux has no internet" — and a Linux with no
                // internet is worth nothing (owner, 2026-08-30). Installation is
                // offline; the environment itself is fully online.
                LinuxAside(
                    "Installing it needs no network at all — the whole system ships inside Conch, " +
                        "so it works in flight mode. Once it is there it uses this phone's " +
                        "connection like anything else, so `apk add` reaches its mirrors normally.",
                )
                LinuxAside(
                    "It needs the phone bridge armed once, because only the shell's own directory " +
                        "is allowed to hold something runnable.",
                )
                problem?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = {
                        scope.launch {
                            problem = null
                            busyStep = "starting"
                            val err = LinuxInstaller.install { step -> busyStep = step }
                            // The machine exists now, so it takes its place on
                            // the list beside the others.
                            if (err == null) LinuxSsh.ensureRow()
                            busyStep = null
                            problem = err
                            refresh++
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Install Linux") }
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove the Linux environment?") },
            text = { Text("Everything installed inside it goes with it — the agents, their logins and anything you built there.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    scope.launch {
                        busyStep = "removing"
                        LinuxSsh.forget()
                        LinuxEnv.remove()
                        busyStep = null
                        size = null
                        refresh++
                    }
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun LinuxBody(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun LinuxAside(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
