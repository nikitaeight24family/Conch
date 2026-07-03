**Last updated: 2026-05-11**

Conch is a native Android client that connects to **your own** SSH servers and runs your chosen AI agent (Claude Code, Codex CLI, or Gemini CLI) on them. Most of the data the app handles never leaves your device or your server. The exceptions — crash reports and feature-usage events — are described below in detail and can be turned off.

This document covers Conch for Android, version 0.2.x, distributed by **Conch Labs**.

## Data stored on this device

The app keeps the following on your phone, in encrypted local storage:

- **Server records**: host, port, username, last-known agent. Stored in a Room database encrypted at rest via androidx.security AES-256.
- **SSH credentials**: passwords (when used), private keys (PEM), optional passphrases. Encrypted via Android Keystore + EncryptedSharedPreferences. Never copied to anywhere else.
- **Session cache**: per-CLI-session JSONL bodies and listings, so reopening a chat paints instantly. Stored under the app's private cache directory; cleared on uninstall or via the in-app data deletion control.
- **Preferences**: theme, accent color, default agent, model choice, approval mode, crash-reporting opt-out flag.

Nothing in this list is transmitted to Conch or to a third-party backend. The app has no backend service of its own.

## Data sent to your SSH servers

When you start a chat, the app opens an SSH connection (sshj 0.39) to a server you configured and runs the CLI of your choice (`claude` / `codex` / `gemini`) over that channel. From your server's perspective, the traffic is identical to you running the CLI yourself over `ssh user@host`.

Files and images you attach are uploaded to `/tmp/conch_uploads/` on the server via SSH `cat > path`. They live there until the server reboots or you delete them.

Memory editor changes (`CLAUDE.md` / `AGENTS.md` / `GEMINI.md`) and subagent files are written to standard locations under `~/.claude` / `~/.codex` / `~/.gemini` on your server, the same paths the CLI itself uses.

The traffic between the phone and your servers is encrypted by SSH end-to-end. We do not proxy or inspect it.

## Shell commands the app runs on your server

The app executes shell commands on the SSH-accessible server you connect to. Every command it issues falls into one of the categories below. None of these run on Conch Labs infrastructure or on a third-party backend — they run on **your** server, against **your** credentials, while the app is in the foreground.

A complete, verbatim list lives in the app at **About → Operations & Commands**.

- **Probing — read-only status checks.** `which claude` / `which codex` / `which gemini`, `<cli> --version`, `npm view @openai/codex version`, `npm view @google/gemini-cli version`, `curl -sI https://claude.ai/install.sh`, `stat`, `pgrep`, `ls` / `cat` of the agent's own session files under `~/.claude` / `~/.codex` / `~/.gemini`.
- **Install / update agents.** `curl -fsSL https://claude.ai/install.sh | bash` (Anthropic's official installer for Claude Code), `npm install -g @openai/codex` / `@google/gemini-cli`, `sudo -n npm install -g <pkg>` retry when the npm prefix needs root (only succeeds if you have passwordless sudo — the app does not handle password prompts), `apt-get`/`dnf`/`pacman`/`apk`/`brew install nodejs npm` as a last-resort bootstrap when npm is missing. Only triggered when you tap **[ install ]** or **[ update ]**.
- **Run agents.** `claude --print --output-format json [--resume <uuid>] [--model …] [--permission-mode …] "<user-prompt>"`, `codex exec [resume <uuid>] [--ask-for-approval …] [--sandbox …] "<user-prompt>"`, `gemini [--yolo] "<user-prompt>"`. The `<user-prompt>` is exactly the text you typed into the chat — the app does not inject hidden instructions.
- **File operations.** `cat <path>` to read, `cat > <path>` to write (memory editor save, attachment upload, in-app text-editor save-back), `sha256sum <path>` for download deduplication, `test -f` / `mkdir -p` / `stat` for path checks, `rm /tmp/conch_uploads/<file>` only when you tap the X next to an attached file in chat.
- **Approval / login helpers.** `claude /permission-mode plan|acceptEdits|bypassPermissions` flips the per-server approval mode. Agent OAuth flows pass the code or callback URL you pasted into the corresponding dialog — credentials stay on your server.

**Out of scope — the app does NOT run.** `rm -rf` or any destructive command on system paths. `sudo` with a password prompt. Any command that modifies your shell rc files, cron jobs, or systemd units. Outbound network requests from your server beyond the agent's own update channels.

The app maintains an **in-memory Activity Log** of the last 500 commands issued per server, viewable at the server's long-press menu → **Activity log**. The log includes timestamp, exit code, and the last ~200 characters of combined stdout / stderr. It is **not** persisted to disk and clears on app restart.

## Crash reports and telemetry (Sentry)

To know when the app breaks for someone, Conch sends crash reports and a small set of feature-usage events to **Sentry** (sentry.io), a third-party error-tracking provider.

**This is on by default.** You can turn it off in **Settings → Privacy → Crash reporting**. The toggle takes effect on the next app launch.

What we send to Sentry:

- Crash and exception stack traces, deobfuscated using the R8 mapping uploaded at release time.
- App version, Android version, device model.
- Recent breadcrumbs of feature usage: which CLI you opened a chat with, what kind of attachment you sent (file / photo / git-diff / init-prompt), whether you created / edited / deleted a subagent, whether your approval mode changed and to what, when an SSH connection failed and what kind of failure.
- Performance traces (sampled, 20% in production): timing of SSH handshake, agent bootstrap, chat first-paint.

What we do **not** send to Sentry:

- Message contents of your chats with the AI.
- Server hostnames or IP addresses of your servers.
- Subagent body text, memory file contents, attached file contents.
- Your IP address (scrubbed at Sentry's ingestion edge before storage).
- Your user-agent identifier (stripped by the SDK before send).

What Sentry derives server-side that we cannot prevent:

- **Approximate geographic region** (country and city, based on the source IP of the request). Sentry resolves this at ingestion time and stores it. The IP itself is then dropped. We recognise this is technically location data; it is country/city level, not GPS, and is the only piece of geolocation Sentry holds.

If you opt out, none of the above is sent.

## Permissions

- **Internet** — to reach the SSH servers you add.
- **Foreground service** — to keep agent sessions alive while the screen is locked or the app is backgrounded.
- **Notifications** — to show the persistent "Connected" notification and approval prompts.

## Your rights

- **Right to opt out**: turn off Settings → Privacy → Crash reporting.
- **Right to erasure (GDPR Art. 17)**: tap **Settings → Privacy → Delete all my data**. This wipes the local Room database, encrypted shared preferences, DataStore preferences, and all caches, then restarts the app. To request deletion of crash reports already received by Sentry, email the address below; we'll forward your Sentry user-id (if any) to them for removal.
- **Right of access (GDPR Art. 15)**: email below. Note that with all PII strippings in place, we typically have nothing user-identifying to return.

## Third-party services used by the app

| Service | Purpose | Privacy policy |
|---|---|---|
| Sentry (sentry.io) | Crash reporting + performance monitoring | https://sentry.io/privacy/ |
| Anthropic / OpenAI / Google AI Studio | If you add API keys via the in-app browser, those requests go to those providers, governed by their policies. The app never sees your prompts to them | provider-specific |

## Contact

Questions, opt-out requests, or data-subject requests: [nikita@eight24family.ai](mailto:nikita@eight24family.ai)

## Changes

Material changes to this policy will be reflected in the date at the top and in the in-app **About** screen. We do not push notifications for policy updates.
