package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.util.SilentlyTry

/**
 * Open-source attribution screen. Lists every third-party component that
 * ships INSIDE the app, grouped by license, with the copyright holder and the
 * license's FULL TEXT bundled offline (`assets/licenses/ (one .txt per license)`) — tap a
 * license header to read it. Bundling a verbatim copy of each license in the
 * distribution is what Apache-2.0 §4(a) and the MIT / BSD / OFL attribution
 * clauses actually require; the in-app viewer means it travels with the APK,
 * no network needed.
 *
 * Scope = runtime/shipped dependencies + bundled font assets ONLY. Test-only
 * libraries (JUnit, Robolectric, Espresso, Apache MINA SSHD, xerial sqlite-jdbc)
 * and build-time plugins (AGP, KSP, the Sentry Gradle plugin) are NOT bundled
 * into the APK, so they carry no end-user attribution duty and are omitted.
 *
 * Single source of truth: [components] below + the asset text files. When a
 * SHIPPED dependency changes in `gradle/libs.versions.toml`, mirror it here and
 * refresh the matching `assets/licenses/ (one .txt per license)`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val cyan = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val outline = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface
    val uriHandler = LocalUriHandler.current
    val ctx = LocalContext.current
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Open source licenses") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                "// Conch is built with the open-source components below. Each is " +
                    "used under its license — tap a license to read its full, verbatim " +
                    "text (bundled in the app, no network needed). We comply with every " +
                    "attribution requirement here.",
                color = outline,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )

            // Grouped by license, in a stable order, so the same license header
            // never repeats and each component sits under exactly one group.
            for (license in licenseOrder) {
                val inGroup = components.filter { it.license == license }
                if (inGroup.isEmpty()) continue
                val isOpen = expanded[license.spdx] == true
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // License header — tap to expand the bundled full text.
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = tertiary, fontWeight = FontWeight.Bold)) {
                                append(if (isOpen) "▾ " else "▸ ")
                            }
                            withStyle(SpanStyle(color = cyan, fontWeight = FontWeight.Bold)) {
                                append(license.spdx)
                            }
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded[license.spdx] = !isOpen }
                            .padding(vertical = 2.dp),
                    )
                    for (c in inGroup) {
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(color = cyan, fontWeight = FontWeight.Bold)) { append("• ") }
                                withStyle(SpanStyle(color = onSurface, fontWeight = FontWeight.SemiBold)) {
                                    append(c.name)
                                }
                                c.version?.let {
                                    withStyle(SpanStyle(color = outline)) { append("  $it") }
                                }
                                withStyle(SpanStyle(color = outline)) { append("\n  ${c.copyright}") }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (isOpen) {
                        // Canonical online source — handy, but the offline copy below
                        // is the one that ships and satisfies the license.
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(color = outline)) { append("canonical source: ") }
                                withStyle(
                                    SpanStyle(color = cyan, textDecoration = TextDecoration.Underline),
                                ) { append(license.url) }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .clickable {
                                    SilentlyTry.fired("SshAi-Licenses", "open license url") {
                                        uriHandler.openUri(license.url)
                                    }
                                },
                        )
                        val fullText = remember(license.asset) {
                            runCatching {
                                ctx.assets.open("licenses/${license.asset}")
                                    .bufferedReader().use { it.readText() }
                            }.getOrElse { "(bundled license text unavailable — see canonical source above)" }
                        }
                        Text(
                            fullText,
                            color = onSurface.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(12.dp),
                        )
                    }
                }
            }

            HorizontalDivider(color = outline.copy(alpha = 0.3f))
            Text(
                "// Trademarks of Anthropic, OpenAI and Google are the property of " +
                    "their respective owners; see About → trademarks. The CLIs " +
                    "themselves run on your own servers and are licensed to you by " +
                    "their respective vendors, not redistributed by Conch.",
                color = outline,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private data class OssLicense(val spdx: String, val url: String, val asset: String)

private val APACHE_2_0 = OssLicense(
    "Apache License 2.0", "https://www.apache.org/licenses/LICENSE-2.0", "Apache-2.0.txt",
)
private val MIT = OssLicense("MIT License", "https://opensource.org/license/mit", "MIT.txt")
private val BOUNCY_CASTLE = OssLicense(
    "Bouncy Castle License (MIT-style)", "https://www.bouncycastle.org/about/license/", "BouncyCastle.txt",
)
private val OFL_1_1 = OssLicense(
    "SIL Open Font License 1.1", "https://openfontlicense.org/open-font-license-official-text/", "OFL-1.1.txt",
)
private val UBUNTU_FONT = OssLicense(
    "Ubuntu Font Licence 1.0", "https://ubuntu.com/legal/font-licence", "UbuntuFontLicense-1.0.txt",
)

/** Render order — keeps code libraries first, then bundled fonts. */
private val licenseOrder = listOf(APACHE_2_0, MIT, BOUNCY_CASTLE, OFL_1_1, UBUNTU_FONT)

private data class OssComponent(
    val name: String,
    val copyright: String,
    val version: String?,
    val license: OssLicense,
)

/** Every component bundled into the shipped APK. Versions mirror
 *  `gradle/libs.versions.toml`; transitive but user-facing libraries
 *  (Google Tink, Kotlin stdlib) are listed under their parent's license. */
private val components = listOf(
    // ── Apache License 2.0 ──
    OssComponent(
        "AndroidX & Jetpack Compose",
        "© The Android Open Source Project — core, lifecycle, activity, compose UI/Material 3, navigation, window, adaptive, DataStore, Room, security-crypto, splashscreen",
        null, APACHE_2_0,
    ),
    OssComponent("Google Tink", "© Google LLC — cryptography, via androidx.security-crypto", null, APACHE_2_0),
    OssComponent(
        "Kotlin & kotlinx",
        "© JetBrains s.r.o. and contributors — stdlib, coroutines, serialization",
        null, APACHE_2_0,
    ),
    OssComponent("Haze", "© Chris Banes", "1.6.7", APACHE_2_0),
    OssComponent("Multiplatform Markdown Renderer", "© Mike Penz", "0.27.0", APACHE_2_0),
    OssComponent("sshj", "© sshj contributors (Jeroen van Erp et al.)", "0.39.0", APACHE_2_0),
    OssComponent("YubiKit for Android (android, fido)", "© Yubico AB", "2.7.0", APACHE_2_0),
    // ── MIT License ──
    OssComponent("Sentry SDK for Android", "© Functional Software, Inc. (Sentry)", "7.18.1", MIT),
    OssComponent("Shizuku API (api, provider)", "© 2021 RikkaW", "13.1.5", MIT),
    // ── Bouncy Castle License ──
    OssComponent(
        "Bouncy Castle (bcprov-jdk18on, bcpkix-jdk18on)",
        "© The Legion of the Bouncy Castle Inc.", "1.81", BOUNCY_CASTLE,
    ),
    // ── SIL Open Font License 1.1 (bundled coding fonts) ──
    OssComponent("JetBrains Mono", "© The JetBrains Mono Project Authors", null, OFL_1_1),
    OssComponent("Fira Code", "© The Fira Code Project Authors", null, OFL_1_1),
    OssComponent("Source Code Pro", "© Adobe (Reserved Font Name “Source”)", null, OFL_1_1),
    OssComponent("IBM Plex Mono", "© IBM Corp.", null, OFL_1_1),
    OssComponent("Space Mono", "© The Space Mono Project Authors", null, OFL_1_1),
    // ── Ubuntu Font Licence 1.0 ──
    OssComponent("Ubuntu Mono", "© Canonical Ltd.", null, UBUNTU_FONT),
)
