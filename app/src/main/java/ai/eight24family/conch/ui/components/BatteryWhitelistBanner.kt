package ai.eight24family.conch.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Banner shown on the servers list when the OS still considers Conch a
 * battery-optimization candidate. The OEM-agnostic
 * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intent is the AOSP way to ask:
 * Samsung, Xiaomi, Google all honor it — and on top, vendor-specific
 * extra "Never sleeping apps" lists work better once the AOSP whitelist
 * is set, because OEM whitelists usually cascade off it.
 *
 * Without it: the foreground service can be killed by Doze when the user
 * switches to YouTube for 5 minutes, and the chat session is silently
 * gone when they come back. This banner is the user's one-tap fix.
 */
@Composable
fun BatteryWhitelistBanner() {
    val ctx = LocalContext.current
    val pm = remember { ctx.getSystemService(Context.POWER_SERVICE) as PowerManager }

    // Re-check the status every time the user comes back to the screen so
    // the banner disappears as soon as they grant the permission. Without
    // a Lifecycle observer the banner sticks around until next process
    // start because `isIgnoringBatteryOptimizations` isn't observable.
    var ignoringBattOpt by remember {
        mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoringBattOpt = pm.isIgnoringBatteryOptimizations(ctx.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
    }

    if (ignoringBattOpt) return

    val tertiary = MaterialTheme.colorScheme.tertiary
    val outline = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface

    Surface(
        onClick = {
            // Standard AOSP intent — works on Samsung, Pixel, Xiaomi,
            // OnePlus, etc. Some OEMs additionally honour the `package:`
            // URI to deep-link straight to the app's specific dialog
            // rather than the global list, which is what we want here.
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + ctx.packageName)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(intent) }.onFailure {
                // Some Android distributions block the package: variant.
                // Fall back to the un-targeted list and let the user find
                // Conch manually.
                ctx.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        },
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.background,
        contentColor = onSurface,
        border = BorderStroke(1.dp, tertiary),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                "// battery optimisation is ON for Conch",
                color = tertiary,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "the OS may kill the SSH session when you switch apps.",
                color = outline,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    "❯ ",
                    color = tertiary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "tap to allow Conch to run unrestricted",
                    color = onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
