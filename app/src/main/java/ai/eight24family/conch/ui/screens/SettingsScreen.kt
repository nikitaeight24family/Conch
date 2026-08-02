package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.eight24family.conch.ui.viewmodel.SettingsViewModel

/**
 * Top-level Settings: shows a clickable list of CATEGORIES; tapping
 * one swaps the body to that category's controls (back arrow then
 * returns to the index). Single composable + internal state so we
 * don't need a separate nav route per category — and the URL never
 * leaks "/settings/appearance" type strings into route-restore on
 * cold start.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenKeychain: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenPrivacyPolicy: () -> Unit = {},
    onOpenTermsOfService: () -> Unit = {},
    onOpenLicenses: () -> Unit = {},
    vm: SettingsViewModel = viewModel()
) {
    var openCategory by rememberSaveable { mutableStateOf<String?>(null) }

    // Deep-link: a screen (e.g. chat → "Connect phone" with Shizuku off) can ask
    // us to open straight at a category instead of the index. Consumed once.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        ai.eight24family.conch.ui.navigation.SettingsDeepLink.pendingCategory?.let {
            openCategory = it
            ai.eight24family.conch.ui.navigation.SettingsDeepLink.pendingCategory = null
        }
    }

    // Intercept the system back gesture / hardware back so it
    // returns to the category index INSTEAD OF popping out of
    // Settings entirely. Topbar back already handled this; system
    // back was leaking straight through to the nav controller.
    androidx.activity.compose.BackHandler(enabled = openCategory != null) {
        openCategory = null
    }

    val categoryTitle = SETTINGS_CATEGORIES.firstOrNull { it.id == openCategory }?.title
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // Match the other tabs' "Conch ▌ <section>" title at the
                    // index; inside a sub-category show that category's name.
                    Text(
                        categoryTitle ?: "Conch ▌ settings",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                navigationIcon = {
                    // Settings is a bottom-nav TAB now — no back arrow at the
                    // index (back is meaningless on a tab). Inside a
                    // sub-category the arrow returns to the index.
                    if (openCategory != null) {
                        IconButton(onClick = { openCategory = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        }
    ) { padding ->
        ai.eight24family.conch.ui.window.WideContentColumn {
        Column(
            modifier = Modifier
                // Only the TOP inset goes on the scroll CONTAINER — the viewport then
                // runs edge-to-edge to the screen bottom so content scrolls UNDER the
                // floating glass bar (the haze samples it → real glass). The bottom
                // clearance is CONTENT padding (applied AFTER verticalScroll), not
                // viewport padding: the old `.padding(bottom=96).verticalScroll()`
                // shrank the viewport, leaving an empty band behind the bar so the
                // glass sampled blank background and read as solid.
                .padding(top = padding.calculateTopPadding())
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            when (openCategory) {
                null -> SettingsIndex(onOpenCategory = { openCategory = it })
                "appearance" -> SettingsSectionAppearance(vm)
                "chat_defaults" -> SettingsSectionChatDefaults(vm)
                "connection" -> SettingsSectionConnection(vm)
                "bridge" -> SettingsSectionBridge(vm)
                "input" -> SettingsSectionInput(vm)
                "files" -> SettingsSectionFiles(vm)
                "security" -> SettingsSectionSecurity(vm, onOpenKeychain)
                "privacy" -> SettingsSectionPrivacy(vm)
                "about" -> SettingsSectionAbout(
                    onOpenAbout = onOpenAbout,
                    onOpenPrivacyPolicy = onOpenPrivacyPolicy,
                    onOpenTermsOfService = onOpenTermsOfService,
                    onOpenLicenses = onOpenLicenses,
                )
            }
        }
        }
    }
}

/**
 * Static catalog of Settings categories. Drives both the index UI
 * (one row per entry) and the topbar title when a category is open.
 * Adding a new category is one new entry here plus one new
 * `SettingsSectionX(vm)` composable.
 */
private data class SettingsCategory(
    val id: String,
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val subtitle: String,
)

private val SETTINGS_CATEGORIES = listOf(
    SettingsCategory("appearance", "Appearance", Icons.Filled.Palette,
        "Theme, accent color"),
    SettingsCategory("chat_defaults", "Chat defaults", Icons.Filled.SmartToy,
        "Approval mode, input behavior"),
    SettingsCategory("connection", "Connection", Icons.Filled.Cable,
        "SSH timeouts, background reliability"),
    SettingsCategory("bridge", "Phone bridge", Icons.Filled.Terminal,
        "Let the agent read logs & run commands (Shizuku)"),
    SettingsCategory("input", "Input", Icons.Filled.Keyboard,
        "Send-on-enter, keyboard shortcuts"),
    SettingsCategory("files", "Files", Icons.Filled.Folder,
        "Downloads folder"),
    SettingsCategory("security", "Security", Icons.Filled.Lock,
        "SSH keys, lock-screen visibility"),
    SettingsCategory("privacy", "Privacy & data", Icons.Filled.Shield,
        "Crash reporting, delete all data"),
    SettingsCategory("about", "About", Icons.Filled.Info,
        "Version, licenses, privacy policy"),
)

@Composable
private fun SettingsIndex(onOpenCategory: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SETTINGS_CATEGORIES.forEach { cat ->
            SettingsRow(
                icon = cat.icon,
                title = cat.title,
                subtitle = cat.subtitle,
                onClick = { onOpenCategory(cat.id) },
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}
