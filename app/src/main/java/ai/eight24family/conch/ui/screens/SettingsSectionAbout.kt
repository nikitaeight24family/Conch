package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.util.SilentlyTry

@Composable
internal fun SettingsSectionAbout(
    onOpenAbout: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onOpenTermsOfService: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Resolve via PackageManager rather than touching BuildConfig
    // directly — keeps this composable agnostic of the
    // `applicationIdSuffix=".debug"` split (BuildConfig.VERSION_NAME
    // is correct in both flavours).
    val versionInfo = remember(ctx) {
        SilentlyTry.loggedOrElse("SshAi-Settings", "read version info", "v?") {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
                pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
            "v${pi.versionName} (build $code)"
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Version row — read-only display, no chevron.
        SettingsRow(
            icon = Icons.Filled.Info,
            title = "Version",
            subtitle = versionInfo,
        ) {}
        SettingsRow(
            icon = Icons.AutoMirrored.Filled.Notes,
            title = "What Conch is",
            subtitle = "How it works, what's stored where",
            onClick = onOpenAbout,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
        SettingsRow(
            icon = Icons.Filled.Shield,
            title = "Privacy Policy",
            subtitle = "What data we collect and how we handle it",
            onClick = onOpenPrivacyPolicy,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
        SettingsRow(
            icon = Icons.AutoMirrored.Filled.InsertDriveFile,
            title = "Terms of Service",
            subtitle = "Rules of using the app",
            onClick = onOpenTermsOfService,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
        SettingsRow(
            icon = Icons.Filled.Gavel,
            title = "Open source licenses",
            subtitle = "Libraries we use and their licenses",
            onClick = onOpenLicenses,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
        // Community / source / socials — brand logos only, at the very bottom.
        SocialLinksRow()
    }
}
