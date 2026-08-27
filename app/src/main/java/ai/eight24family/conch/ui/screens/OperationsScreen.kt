package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Static disclosure of every category of shell command the app can
 * execute on the user's own SSH-accessible servers. Linked from
 * About → "Operations & Commands". Mirrored verbatim in the public
 * privacy policy so Play-store reviewers and security-conscious users
 * see exactly the same surface.
 *
 * Anything we run on a remote host should fit one of these buckets —
 * if a future feature wouldn't, the page (and policy) must be updated
 * BEFORE shipping. Treat this file as the canonical contract.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperationsScreen(onBack: () -> Unit) {
    val cyan = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val outline = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Operations & Commands") },
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
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                "// what runs on your server",
                color = tertiary,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = onSurface)) {
                        append(
                            "Every command this app executes on a connected server falls into one of " +
                                "the categories below. The Activity Log (server long-press → \"Activity log\") " +
                                "records each invocation with timestamp + exit code + a short stdout tail so " +
                                "you can verify it in real time."
                        )
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
            )

            OpSection("Probing — read-only status checks") {
                OpBullet("which claude / codex / gemini / grok / copilot — find the CLI on PATH.")
                OpBullet("<cli> --version — read the installed version.")
                OpBullet("npm view @openai/codex / @google/gemini-cli / @xai-official/grok / @github/copilot version — read the latest published version.")
                OpBullet("curl -sI https://claude.ai/install.sh | grep ETag — best-effort latest-Claude marker.")
                OpBullet("grok models — Grok's own login + model-catalog check (local state, no generation).")
                OpBullet("stat -c %s <path> / stat -f %z <path> — file size for download icons + tail-poll detection.")
                OpBullet("pgrep -f <session-uuid> — is THIS chat's CLI still running on the host? Drives the spinner.")
                OpBullet("ls / cat for the agent's own JSONL session files under ~/.claude, ~/.codex, ~/.gemini, ~/.grok, ~/.copilot.")
            }

            OpSection("Install / update agents") {
                OpBullet("curl -fsSL https://claude.ai/install.sh | bash — Anthropic's official installer for Claude Code.")
                OpBullet("npm install -g @openai/codex / @google/gemini-cli / @xai-official/grok / @github/copilot — global CLI install (per-user prefix).")
                OpBullet("sudo -n npm install -g … — passwordless-sudo retry when the npm prefix needs root. SKIPPED if sudo prompts for password.")
                OpBullet("apt-get / dnf / pacman / apk / brew install nodejs npm — only when npm is missing entirely. SKIPPED if you don't have admin access.")
                OpBullet("curl -fsSL https://deb.nodesource.com/setup_22.x | bash — Node 22 channel when distro npm is too old for Codex.")
                OpBullet("npm install -g <pkg>@latest — used by the [ update ] button after a version mismatch is detected.")
            }

            OpSection("Run agents") {
                OpBullet("claude --print --output-format json [--resume <uuid>] [--model …] [--permission-mode …] \"<user-prompt>\" — Claude Code one-shot.")
                OpBullet("codex exec [resume <uuid>] [--ask-for-approval …] [--sandbox …] \"<user-prompt>\" — Codex one-shot.")
                OpBullet("gemini [--yolo] \"<user-prompt>\" — Gemini one-shot.")
                OpBullet("grok -p \"<user-prompt>\" --output-format streaming-messages-json [-r <uuid>] [--permission-mode …] — Grok Build one-shot.")
                OpBullet("copilot -p \"<user-prompt>\" --output-format json [--resume=<uuid>] [--allow-all-tools | --yolo] — Copilot CLI one-shot.")
                OpBullet("Prompts come directly from the chat input you typed — nothing is added behind your back.")
            }

            OpSection("File operations") {
                OpBullet("cat <path> — read file content (download icon, in-chat viewer, memory editor).")
                OpBullet("cat > <path> — write file content (memory save, attachment upload to /tmp/conch_uploads/, text-editor save-back).")
                OpBullet("test -f / mkdir -p / stat — exist-checks before reads + writes.")
                OpBullet("sha256sum <path> — content hash for download dedup (downloads cached by SHA, not by name).")
                OpBullet("rm /tmp/conch_uploads/<file> — only triggered when you tap the X next to an attached file in chat.")
            }

            OpSection("Approval / login helpers") {
                OpBullet("claude /permission-mode plan|acceptEdits|bypassPermissions — flips the per-server approval mode.")
                OpBullet("Various agent-specific OAuth callback inserts — codex post-sign-in URL, claude/gemini paste-the-code dialogs.")
                OpBullet("All credentials stay on the server; the app only passes the code/URL you paste into the dialog.")
            }

            OpSection("Out of scope — NOT executed") {
                OpBullet("rm -rf, kill -9, anything destructive on system paths — never issued by the app.")
                OpBullet("sudo without passwordless sudoers entry — we don't ask for your sudo password, ever.")
                OpBullet("Outbound network from your server (the app doesn't tell your server to call out to anything other than the agent's own update channel).")
                OpBullet("Modifying your shell rc files, cron, systemd units — we don't touch them.")
            }

            Text(
                "// the receipts",
                color = tertiary,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = onSurface)) {
                        append(
                            "Long-press a server on the home screen → \"Activity log\" — the app keeps an " +
                                "in-memory log of the last 500 commands run on that server, with timestamp, " +
                                "exit code and a short stdout tail. The log is local to the device and is " +
                                "cleared when the app is uninstalled."
                        )
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun OpSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "// $title",
            color = MaterialTheme.colorScheme.tertiary,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
        content()
    }
}

@Composable
private fun OpBullet(text: String) {
    val cyan = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = cyan, fontWeight = FontWeight.Bold)) { append("• ") }
            withStyle(SpanStyle(color = onSurface)) { append(text) }
        },
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
    )
}
