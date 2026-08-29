package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.linux.LinuxEnv
import ai.eight24family.conch.linux.LinuxInstaller
import kotlinx.coroutines.launch

/**
 * "Linux environment" — a real distribution, on the phone, for someone who has
 * no computer and no server.
 *
 * It is Alpine with `apk`: 25 000 packages, python, git, compilers, whatever the
 * user needs, installed from inside it. No root anywhere — the userland runs
 * under a syscall-rewriting runtime as the shell uid the phone bridge already
 * obtained, and believes it is root only within its own directory tree.
 */
@Composable
fun LinuxEnvironmentPanel() {
    val scope = rememberCoroutineScope()

    var installed by remember { mutableStateOf<Boolean?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }
    var size by remember { mutableStateOf<String?>(null) }
    var busyStep by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }

    LaunchedEffect(refresh) {
        installed = LinuxEnv.isInstalled()
        if (installed == true) {
            summary = LinuxEnv.describe()
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

            installed == null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    LinuxBody("Checking…")
                }
            }

            installed == true -> {
                SettingsRow(
                    icon = Icons.Filled.CheckCircle,
                    title = "Ready ✓",
                    subtitle = summary ?: "Linux is running on this phone.",
                )
                LinuxBody(
                    "Ask an agent to use it, or run anything yourself: it is a full userland with " +
                        "its own package manager and this phone's internet connection. " +
                        "`apk add python3 git` works exactly as it does on a server.",
                )
                size?.let { LinuxAside("Using $it.") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            busyStep = "removing"
                            LinuxEnv.remove()
                            busyStep = null
                            summary = null
                            size = null
                            refresh++
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Remove") }
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
