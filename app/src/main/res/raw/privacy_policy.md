**Last updated: 2026-08-27**

Conch is a native Android client that connects to **your own** SSH servers and runs your chosen AI agent (Claude Code, Codex CLI, Gemini CLI, Grok Build, or GitHub Copilot CLI) on them. **No data the app handles leaves your device or your servers.** There is no analytics, no crash reporting and no telemetry of any kind, and Conch has no backend to send anything to. The only network connections the app opens are the SSH connections to the servers you add yourself.

This document covers Conch for Android, version 0.4.x, published by **Eight 24 Family LLC** (trading as Conch Labs).

## Data stored on this device

The app keeps the following on your phone, in encrypted local storage:

- **Server records**: host, port, username, last-known agent. Stored in a Room database encrypted at rest via androidx.security AES-256.
- **SSH credentials**: passwords (when used), private keys (PEM), optional passphrases. Encrypted via Android Keystore + EncryptedSharedPreferences. Never copied to anywhere else.
- **Session cache**: per-CLI-session JSONL bodies and listings, so reopening a chat paints instantly. Stored under the app's private cache directory; cleared on uninstall or via the in-app data deletion control.
- **Preferences**: theme, accent color, default agent, model choice, approval mode.

Nothing in this list is transmitted to Conch or to a third-party backend. The app has no backend service of its own.

## Data sent to your SSH servers

When you start a chat, the app opens an SSH connection (sshj 0.39) to a server you configured and runs the CLI of your choice (`claude` / `codex` / `gemini` / `grok` / `copilot`) over that channel. From your server's perspective, the traffic is identical to you running the CLI yourself over `ssh user@host`.

Files and images you attach are uploaded to `/tmp/conch_uploads/` on the server via SSH `cat > path`. They live there until the server reboots or you delete them.

Memory editor changes (`CLAUDE.md` / `AGENTS.md` / `GEMINI.md` / `AGENT.md` / `copilot-instructions.md`) and subagent files are written to standard locations under `~/.claude` / `~/.codex` / `~/.gemini` / `~/.grok` / `~/.copilot` on your server, the same paths the CLI itself uses.

The traffic between the phone and your servers is encrypted by SSH end-to-end. We do not proxy or inspect it.

## Shell commands the app runs on your server

The app executes shell commands on the SSH-accessible server you connect to. Every command it issues falls into one of the categories below. None of these run on our infrastructure or on a third-party backend — they run on **your** server, against **your** credentials, while the app is in the foreground.

A complete, verbatim list lives in the app at **About → Operations & Commands**.

- **Probing — read-only status checks.** `which claude` / `codex` / `gemini` / `grok` / `copilot`, `<cli> --version`, `npm view @openai/codex version`, `npm view @google/gemini-cli version`, `npm view @xai-official/grok version`, `npm view @github/copilot version`, `curl -sI https://claude.ai/install.sh`, `stat`, `pgrep`, `ls` / `cat` of the agent's own session files under `~/.claude` / `~/.codex` / `~/.gemini` / `~/.grok` / `~/.copilot`.
- **Install / update agents.** `curl -fsSL https://claude.ai/install.sh | bash` (Anthropic's official installer for Claude Code), `npm install -g @openai/codex` / `@google/gemini-cli` / `@xai-official/grok` / `@github/copilot`, `sudo -n npm install -g <pkg>` retry when the npm prefix needs root (only succeeds if you have passwordless sudo — the app does not handle password prompts), `apt-get`/`dnf`/`pacman`/`apk`/`brew install nodejs npm` as a last-resort bootstrap when npm is missing. Only triggered when you tap **[ install ]** or **[ update ]**.
- **Run agents.** `claude --print --output-format json [--resume <uuid>] [--model …] [--permission-mode …] "<user-prompt>"`, `codex exec [resume <uuid>] [--ask-for-approval …] [--sandbox …] "<user-prompt>"`, `gemini [--yolo] "<user-prompt>"`, `grok -p "<user-prompt>" --output-format streaming-messages-json [-r <uuid>] [--permission-mode …]`, `copilot -p "<user-prompt>" --output-format json [--resume=<uuid>] [--allow-all-tools | --yolo]`. The `<user-prompt>` is exactly the text you typed into the chat — the app does not inject hidden instructions.
- **File operations.** `cat <path>` to read, `cat > <path>` to write (memory editor save, attachment upload, in-app text-editor save-back), `sha256sum <path>` for download deduplication, `test -f` / `mkdir -p` / `stat` for path checks, `rm /tmp/conch_uploads/<file>` only when you tap the X next to an attached file in chat.
- **Approval / login helpers.** `claude /permission-mode plan|acceptEdits|bypassPermissions` flips the per-server approval mode. Agent OAuth flows pass the code or callback URL you pasted into the corresponding dialog — credentials stay on your server.

**Out of scope — the app does NOT run.** `rm -rf` or any destructive command on system paths. `sudo` with a password prompt. Any command that modifies your shell rc files, cron jobs, or systemd units. Outbound network requests from your server beyond the agent's own update channels.

The app maintains an **in-memory Activity Log** of the last 500 commands issued per server, viewable at the server's long-press menu → **Activity log**. The log includes timestamp, exit code, and the last ~200 characters of combined stdout / stderr. It is **not** persisted to disk and clears on app restart.

## Crash reports and telemetry

**There are none.** Conch collects no analytics, sends no crash reports, and contains no telemetry, tracking or advertising SDK of any kind.

Earlier versions (up to and including 0.4.0) sent crash reports and a small set of feature-usage events to Sentry, with an opt-out toggle in Settings. That was removed entirely in 0.4.1: the SDK, the Gradle plugin, the initialisation code and the setting are all gone from the source. Nothing replaced it.

The practical consequence, stated plainly: when Conch crashes on your device, we do not find out. If something breaks, please tell us at the contact address at the bottom of this page — that is now the only channel there is.

Two things follow from this that are worth being explicit about:

- **No approximate location.** Previous versions had to declare "approximate location" on the Play Store, because Sentry derived a country and city from the request IP at ingestion. With Sentry gone there is no request, no IP and no geolocation of any kind. Conch holds no location permission and cannot read your GPS.
- **No device or user identifiers** are generated, stored or transmitted. The app never assigns you an id.

## Permissions

- **Internet** — to reach the SSH servers you add.
- **Foreground service** — to keep agent sessions alive while the screen is locked or the app is backgrounded.
- **Notifications** — to show the persistent "Connected" notification and approval prompts.

## Your rights

- **Right to erasure (GDPR Art. 17)**: tap **Settings → Privacy → Delete all my data**. This wipes the local Room database, encrypted shared preferences, DataStore preferences, and all caches, then restarts the app. Because nothing is ever sent off the device, there is no copy anywhere else for us to delete.
- **Right of access (GDPR Art. 15)**: email below. We hold no data about you at all, so there is nothing to return.

## Third-party services used by the app

| Service | Purpose | Privacy policy |
|---|---|---|
| Anthropic / OpenAI / Google AI Studio | If you sign in or add API keys via the in-app browser, those requests go to those providers, governed by their policies. The app never sees your prompts to them | provider-specific |

No other third-party service receives anything from the app.

## Contact

Questions, bug reports, or data-subject requests: [nikita@eight24family.ai](mailto:nikita@eight24family.ai)

## Changes

Material changes to this policy will be reflected in the date at the top and in the in-app **About** screen. We do not push notifications for policy updates.
