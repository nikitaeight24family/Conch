package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.BuildConfig
import ai.eight24family.conch.util.SilentlyTry

/**
 * Surfaces what the app is, what it does, where data lives, and how
 * to reach the publisher. Accurate against the shipped feature set —
 * not marketing copy.
 *
 * Version is read from [BuildConfig.VERSION_NAME] so this screen
 * never lies about the build the user is looking at.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onOpenTermsOfService: () -> Unit = {},
    onOpenOperations: () -> Unit = {},
    onOpenLicenses: () -> Unit = {},
) {
    val cyan = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val outline = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface
    val uriHandler = LocalUriHandler.current
    val ctx = LocalContext.current

    // PackageManager gives us the source of truth for versionName.
    // BuildConfig works too, but PackageInfo also surfaces the
    // versionCode if we ever want to show "1.0.9 (215)" style.
    val versionLabel = remember {
        runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "v" + pi.versionName.orEmpty()
        }.getOrElse { "v" + BuildConfig.VERSION_NAME }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Title block
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = cyan, fontWeight = FontWeight.Bold)) { append("Conch") }
                        withStyle(SpanStyle(color = outline)) { append(" ▌ ") }
                        withStyle(SpanStyle(color = tertiary, fontWeight = FontWeight.Bold)) { append(versionLabel) }
                    },
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    "// drive Claude Code, Codex or Gemini CLI on your own servers, from your phone.",
                    color = outline,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
            }

            Section("thanks") {
                Text(
                    "to my friend and comrade, PhD Nguyen Tu De Tran — without his support and help this project would not have come to be.",
                    color = onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Section("fonts") {
                Bullet("bundled coding fonts — JetBrains Mono, Fira Code, Source Code Pro, IBM Plex Mono, Space Mono (SIL Open Font License 1.1); Ubuntu Mono (Ubuntu Font Licence). pick one in Settings → custom theme → font.")
            }

            Section("what it is") {
                Bullet("a thin native Android client over SSH — your servers stay your servers.")
                Bullet("speaks each CLI you already have installed on the host: claude / codex / gemini.")
                Bullet("nothing extra to install on the host. just SSH access and the CLI binary.")
                Bullet("no hosted backend on our side. no AI quotas, no cloud middleman.")
            }

            Section("sessions & search") {
                Bullet("one unified home — every chat across all your servers × agents, newest first, like a messenger.")
                Bullet("each row shows the session's own name (Claude's title; first message for Codex/Gemini), its server, and the last message.")
                Bullet("full-text search across every cached session — tap a hit to jump straight to the matching message.")
                Bullet("last-activity time is tracked per session and persists across restarts, so the order stays honest even offline.")
            }

            Section("authentication") {
                Bullet("SSH password — stored encrypted via androidx.security.")
                Bullet("SSH private key (PEM) — passphrase-protected, decrypted in-memory only.")
                Bullet("hardware security key (FIDO2 / CTAP2) — USB or NFC. multiple keys per server, any enrolled key works (primary + backups).")
                Bullet("NFC flow uses deferred-tap: handshake starts first, the tap is held only for the ~300ms signature.")
                Bullet("opt-in hardware-backed device key reconnects silently on launch / network change — no extra tap until you actually send.")
            }

            Section("per-chat isolation") {
                Bullet("model + reasoning effort are stored per chat, not per agent — switching models in one chat doesn't leak into the next.")
                Bullet("new chats start from the agent's config.toml / equivalent default; mid-conversation switch is fine.")
                Bullet("resume id, working directory, approval mode — all per chat.")
            }

            Section("transport") {
                Bullet("one pooled SSH client per server (sshj 0.39), parallel channels per chat — slow auth on host A never blocks host B.")
                Bullet("runs `<cli> --print --output-format=json` over the channel; parses stream-json into the chat.")
                Bullet("file uploads use the same channel via `cat > path` — faster than SFTP on real networks.")
                Bullet("git diffs / init prompts / approval-relax prompts piggyback the live channel — no extra handshake.")
            }

            Section("background") {
                Bullet("foreground service keeps active chat sessions alive while the screen is locked.")
                Bullet("persistent notification shows the connected server + an End-now button — kill a runaway agent from the shade.")
            }

            Section("files in chat") {
                Bullet("any path the agent prints becomes a tappable disk icon with the real file size.")
                Bullet("dedup is keyed by SHA-256 of the remote content — same file isn't redownloaded, same name with different content isn't mistaken for the cached one.")
                Bullet("downloads folder is configurable in Settings (SAF tree URI); defaults to the system Downloads.")
                Bullet("built-in viewer/editor for text — syntax highlighting (Kotlin, Rust, JS/TS, Python, Bash, Swift, Dart, etc.), shebang fallback for extensionless files. save writes back via `cat > path`.")
                Bullet("open-with chooser: view here, open in another app, or share (which surfaces 'Save to device' on most launchers).")
            }

            Section("what's stored on this device") {
                Bullet("server records (host / port / user) — encrypted at rest via androidx.security AES-256.")
                Bullet("SSH private keys + passphrases — Android Keystore + EncryptedSharedPreferences, never copied unencrypted.")
                Bullet("session listings + per-session JSONL bodies — local cache for instant reopen, cleared on uninstall or via Settings → Privacy → Delete all my data.")
                Bullet("preferences (theme, accent, model, approval mode, downloads folder, open-with picks).")
                Bullet("nothing leaves the device except over SSH to your own servers — and opt-in crash reports (see Privacy Policy).")
            }

            Section("what's stored on YOUR servers") {
                Bullet("Claude Code: ~/.claude/projects/.../*.jsonl  +  ~/.claude/CLAUDE.md (memory)  +  ~/.claude/agents/*.md (subagents).")
                Bullet("Codex: ~/.codex/sessions/.../*.jsonl  +  ~/.codex/AGENTS.md.")
                Bullet("Gemini: rollouts under ~/.gemini  +  ~/.gemini/GEMINI.md.")
                Bullet("file uploads land in /tmp/conch_uploads/ until the server reboots or you delete them.")
                Bullet("we read/write these the same paths the CLI itself uses — nothing proprietary.")
            }

            Section("approval modes (the shield icon)") {
                Bullet("safe — CLI defaults; tool writes may stall in headless mode.")
                Bullet("auto — auto-approve edits, escalate on failure. usually what you want.")
                Bullet("yolo — bypass sandbox + approvals. only on hosts you trust. one tap from the agent's prompt disables approvals on the server itself, too.")
            }

            Section("subagents") {
                Bullet("Claude Code only — defined as markdown + YAML frontmatter under ~/.claude/agents/.")
                Bullet("create / edit / delete from the 🤖 icon. seven starter templates included (code-reviewer, test-writer, bug-hunter, etc).")
                Bullet("tool selector lists the canonical Claude Code tools — chip toggles, no CSV typing.")
            }

            Section("privacy") {
                Bullet("crash reports + a small set of feature-usage events go to Sentry — opt-out toggle in Settings → Privacy.")
                Bullet("no chat contents, no file contents, no server hostnames or IPs leave the device.")
                Bullet("full breakdown of what gets sent and what doesn't lives in the Privacy Policy below.")
            }

            Section("trademarks") {
                Bullet("\"Claude\" and the Claude mark are trademarks of Anthropic PBC.")
                Bullet("\"OpenAI\", \"Codex\" and the OpenAI mark are trademarks of OpenAI L.L.C.")
                Bullet("\"Gemini\" and the Google mark are trademarks of Google LLC.")
                Bullet("Conch is not affiliated with, endorsed by, or sponsored by Anthropic, OpenAI, or Google. Marks are used here solely to identify which CLI the user is driving.")
            }

            HorizontalDivider(color = outline.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 4.dp))

            OutlinedButton(
                onClick = onOpenOperations,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Operations & Commands",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start
                )
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
            OutlinedButton(
                onClick = onOpenPrivacyPolicy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Privacy Policy",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start
                )
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
            OutlinedButton(
                onClick = onOpenTermsOfService,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Terms of Service",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start
                )
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
            OutlinedButton(
                onClick = onOpenLicenses,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Open source licenses",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start
                )
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }

            HorizontalDivider(color = outline.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 4.dp))

            // Publisher + contact. Email is a tappable mailto: — most
            // Android launchers route this straight into the default
            // mail client with the To: field prefilled.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "// published by",
                    color = tertiary,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Conch Labs",
                    color = onSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = buildAnnotatedString {
                        withStyle(
                            SpanStyle(
                                color = cyan,
                                textDecoration = TextDecoration.Underline
                            )
                        ) { append("nikita@eight24family.ai") }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable {
                        SilentlyTry.fired("SshAi-About", "open mailto link") {
                            uriHandler.openUri("mailto:nikita@eight24family.ai")
                        }
                    }
                )
            }

            HorizontalDivider(color = outline.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 4.dp))
            // Community / source / socials — brand logos only, the link IS the logo.
            SocialLinksRow()
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "// $title",
            color = MaterialTheme.colorScheme.tertiary,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        content()
    }
}

@Composable
private fun Bullet(text: String) {
    val cyan = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = cyan, fontWeight = FontWeight.Bold)) { append("• ") }
            withStyle(SpanStyle(color = onSurface)) { append(text) }
        },
        style = MaterialTheme.typography.bodyMedium
    )
}
