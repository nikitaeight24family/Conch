package ai.eight24family.conch.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.service.ConnectionPermissions
import ai.eight24family.conch.util.SilentlyTry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect

/**
 * Bottom sheet that walks the user through the three OS-level grants
 * Conch needs to keep its SSH pool alive across backgrounding /
 * swipe-from-recents / overnight:
 *
 *  1. POST_NOTIFICATIONS (runtime permission, Android 13+).
 *  2. Ignore battery optimisations (system dialog).
 *  3. OEM auto-start whitelist (manual, deep-linked).
 *
 * Each row shows its current status (✓ done, ⚠ pending, hidden if N/A)
 * and a single primary action. The sheet re-evaluates status on every
 * ON_RESUME so flipping a toggle in system Settings, then coming back,
 * updates the row without the user having to relaunch the sheet.
 *
 * `onDismiss` is called when the user taps outside or hits the close
 * button. The sheet is non-blocking — even all-three-still-pending
 * lets the user dismiss; it just means Conch will be more aggressively
 * killed by the OS, not that it won't work at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionGuardSheet(
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = ServiceLocator.preferences
    val oemAcked by prefs.oemAutoStartAcknowledged.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    // Status is re-read in a tick var that bumps on lifecycle resume +
    // after any settings intent returns. Compose then re-reads the
    // status getters which are pure functions on PowerManager / pm.
    var tick by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) tick += 1
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val notifStatus = remember(tick) { ConnectionPermissions.notificationStatus(ctx) }
    val batteryStatus = remember(tick) { ConnectionPermissions.batteryStatus(ctx) }
    val oemStatus = remember(tick, oemAcked) {
        ConnectionPermissions.oemAutoStartStatus(ctx, oemAcked)
    }

    // POST_NOTIFICATIONS runtime launcher. We need the Activity in the
    // composition tree to host it; rememberLauncher does that. The result
    // doesn't carry useful data — we just bump tick so the row re-reads.
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> tick += 1 }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Keep SSH connected",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Android can kill background apps to save battery. Grant " +
                    "the three things below so your SSH stays open until " +
                    "you tap End in the notification.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()

            if (notifStatus != ConnectionPermissions.Status.NotApplicable) {
                GuardRow(
                    title = "Show the connection notification",
                    body = "We need to post a persistent notification so Android " +
                        "lets the SSH socket stay open in the background. Without " +
                        "this the connection drops the moment you swipe the app away.",
                    status = notifStatus,
                    actionLabel = "Allow",
                    onAction = {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                )
            }

            if (batteryStatus != ConnectionPermissions.Status.NotApplicable) {
                GuardRow(
                    title = "Allow unrestricted battery use",
                    body = "Doze / App Standby will shut us down after the screen " +
                        "is off for ~30 minutes unless Conch is whitelisted. The " +
                        "Android dialog will ask once.",
                    status = batteryStatus,
                    actionLabel = "Open setting",
                    onAction = {
                        val intent = ConnectionPermissions.batteryRequestIntent(ctx)
                            ?: ConnectionPermissions.batteryAppDetailIntent(ctx)
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        SilentlyTry.fired("Conch-ConnGuard", "open permission settings") { ctx.startActivity(intent) }
                    },
                )
            }

            if (oemStatus != ConnectionPermissions.Status.NotApplicable) {
                GuardRow(
                    title = "Allow background activity (vendor)",
                    body = "Your device's manufacturer (${android.os.Build.MANUFACTURER}) " +
                        "has its own kill-switch on top of Android's, with no API to " +
                        "read it back. We'll open the vendor page — flip Conch's " +
                        "autostart / background-allow toggle and come back.",
                    status = oemStatus,
                    actionLabel = if (oemStatus == ConnectionPermissions.Status.Pending) "Open vendor settings"
                                  else "Done — re-open",
                    onAction = {
                        val intent = ConnectionPermissions.oemAutoStartIntent(ctx)
                        if (intent != null) {
                            SilentlyTry.fired("Conch-ConnGuard", "open battery whitelist") { ctx.startActivity(intent) }
                        }
                        // Mark acknowledged on first launch so we don't keep
                        // pestering the user — they can flip it back from
                        // Settings if they want a re-prompt.
                        scope.launch { prefs.setOemAutoStartAcknowledged(true) }
                    },
                )
            }

            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}

@Composable
private fun GuardRow(
    title: String,
    body: String,
    status: ConnectionPermissions.Status,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusBubble(status)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 2.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (status == ConnectionPermissions.Status.Pending) {
                OutlinedButton(
                    onClick = onAction,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Icon(
                        Icons.Default.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text("  $actionLabel")
                }
            }
        }
    }
}

@Composable
private fun StatusBubble(status: ConnectionPermissions.Status) {
    val (icon, tint) = when (status) {
        ConnectionPermissions.Status.Granted -> Icons.Default.Check to MaterialTheme.colorScheme.primary
        ConnectionPermissions.Status.Pending -> Icons.Default.Warning to MaterialTheme.colorScheme.tertiary
        ConnectionPermissions.Status.NotApplicable -> Icons.Default.Check to MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(
                color = tint.copy(alpha = 0.15f),
                shape = RoundedCornerShape(50),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}
