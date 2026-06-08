<p align="center">
  <img src="assets/banner.png" alt="Conch" />
</p>

<h1 align="center">Conch</h1>

[![tests](https://github.com/nikitaeight24family/Conch/actions/workflows/test.yml/badge.svg)](https://github.com/nikitaeight24family/Conch/actions/workflows/test.yml)
[![release](https://img.shields.io/badge/release-v0.1.0--beta-orange.svg)](https://github.com/nikitaeight24family/Conch/releases/latest)
[![play store](https://img.shields.io/badge/Play%20Store-closed%20beta-blue.svg)](https://play.google.com/apps/testing/ai.eight24family.conch)
[![license](https://img.shields.io/badge/license-PolyForm%20Noncommercial-blue.svg)](LICENSE)

> **Your phone, your server, your AI. Build, test, ship — on the train.**

> ### 🧪 Closed beta — testers wanted
> Conch is in closed testing on Google Play and I'm looking for testers (solo dev — every opt-in genuinely helps). Two taps, keep it as long as you like:
>
> 1. **Join the testers group:** https://groups.google.com/g/conch-beta
> 2. **Opt in to the test:** https://play.google.com/apps/testing/ai.eight24family.conch
> 3. **Install from Play:** https://play.google.com/store/apps/details?id=ai.eight24family.conch
>
> You must join the group first (step 1) or the Play links show "app not available". An Android phone is enough to explore the UI; a server with Claude Code / Codex / Gemini on `$PATH` to use it for real. Bugs and feedback very welcome.

Conch is a mobile-first, AI-native Android client. The phone is the tactile
interface; your own VPS is where code, builds and tests run; an AI agent
(**Claude Code**, **Codex CLI** or **Gemini CLI**) on that server does the
heavy lifting. Nothing is hosted by us — no proxies, no quotas, no cloud
middleman. It's a terminal in your pocket, not a hosted AI service.

```
conch ▌ v0.1.0 Beta
// drive Claude Code, Codex or Gemini CLI on your own servers, from your phone.
```

## Install

- **Play Store (closed beta)** — [join the testers group](https://groups.google.com/g/conch-beta),
  then [opt in](https://play.google.com/apps/testing/ai.eight24family.conch) and
  [install](https://play.google.com/store/apps/details?id=ai.eight24family.conch).
- **APK** — latest signed build on the
  [Releases](https://github.com/nikitaeight24family/Conch/releases/latest)
  page; sideload via Android's "install from unknown sources."

Once installed:

1. **Pair a server.** Add host / port / user, paste or generate an SSH key
   (or tap a hardware security key), accept the host fingerprint on first
   connect (TOFU).
2. **Pick an agent.** Tap the server, choose Claude / Codex / Gemini —
   whichever CLI is on your `$PATH`.
3. **Open a chat.** Type, attach files or images, watch the model stream
   its reply. Resume any prior session from the sessions list.

Nothing extra to install on the host. SSH access plus the CLI of your
choice on `$PATH` is everything you need.

---

## Features

### Multiple agents, many parallel chats
- Drives **Claude Code**, **OpenAI Codex** and **Google Gemini** CLIs, each
  with its own parser (stream-json for Claude, rollout JSONL for Codex,
  Gemini events for Gemini).
- Per-CLI **model picker** in the top bar (Opus / Sonnet / Haiku for Claude,
  gpt-5 / gpt-5-codex for Codex, …), probed live from the agent.
- Open **as many parallel chats as you want, across as many servers as you
  want** — each chat is a real CLI process on your server, resumable across
  days. Files the agent writes land on your real filesystem.

### Sessions, search & offline cache
- **Unified sessions home** — every chat across every server and agent,
  newest first, like a messenger. Each row shows the session's own title,
  its server, and its last message; last-activity is tracked so the order
  stays right even offline.
- **Full-text search across every session** you've opened — find a
  conversation by what was said inside it and jump straight to the matching
  message (line-precise scroll to the hit).
- **Per-session disk cache** so reopening a chat paints prior turns
  instantly; a **background tail-poll** catches anything the agent wrote
  while the app was closed (e.g. you driving the same session from a laptop),
  surfaced with a `● remote · listening` banner.
- **Background prefetch** quietly fills the cache for every session on every
  authorized server while you're on the home screen.

### Chat
- **Live token streaming** — assistant bubbles grow in place as the model
  writes (stable per-block ids, upsert-by-id), not a wall of text at the end.
- **Markdown rendering**, code-block copy, per-message copy, link detection,
  and an honest status indicator distinguishing "working on the server" vs
  "working on your phone".
- **Mid-turn queueing** — type and send while the agent is still working; the
  prompt is queued FIFO and runs the moment the current turn finishes. The
  action button swaps Stop ↔ Send so you never have to abort to add a thought.
- **Buffered sends** — send while the SSH session is still bootstrapping and
  it flushes the instant the session is ready (returned to your input box if
  it times out — never silently lost).
- **Stop** sends a real signal to the remote agent process (SIGINT, then a
  hard channel close) — it actually stops thinking and writing files.
- **In-chat search** + a scrollbar for long conversations.
- **Attachments over SSH** — photos and arbitrary files streamed via `cat`
  (faster than SFTP), image clipboard paste, plus git-diff and per-CLI
  `init` from the attach sheet.
- **Inline file downloads** — tap a file path the agent mentions and it
  streams from the server to your device's Downloads over a fresh channel.

### Built-in viewers & terminal
- A real **VT terminal** to your server for when you'd rather drive it by hand.
- In-app viewers for **diffs, PDF, Markdown, and plain text**, plus an
  **image viewer/annotator** for screenshots and attachments.

### Connection & authentication
- **Per-server pooled SSH** — one authenticated transport is shared by every
  chat, the sessions refresh, agent-status probes and prefetch on that host;
  no extra handshakes.
- **Auto-reconnect** on dropped channels with backoff; **seamless reconnect**
  on launch and network changes after the first connect — no extra tap until
  you actually send.
- **TOFU host-key pinning** with an audit trail (first-accept + any later
  mismatch recorded with timestamp and fingerprints).
- A foreground service keeps live sessions resident; a battery-optimisation
  banner helps keep them alive when you switch apps.

### Hardware security keys (FIDO2 / CTAP2)
- Any authenticator that speaks **USB-HID FIDO or NFC ISO-DEP** — no vendor
  lock-in. SK key types (`sk-ssh-ed25519@openssh.com`,
  `sk-ecdsa-sha2-nistp256@openssh.com`) are signed via a custom sshj auth
  method and a deferred-tap CTAP flow that keeps the NFC tag's short lifetime
  aligned with the SSH userauth moment.
- **One tap, one PIN per session** — enroll any number of keys per server;
  additional servers in the same session are touch-only.

### Software SSH keys
- **Import** existing Ed25519, RSA, ECDSA or DSA private keys (OpenSSH-v1,
  PEM, PKCS#8) from device storage, USB-OTG, SD card or any DocumentsProvider;
  passphrase only prompted when the key is actually encrypted.
- **Generate** a fresh Ed25519 keypair on device; copy the `authorized_keys`
  public line in one tap. Secrets are encrypted at rest via the Android
  Keystore.

### Agent control
- **Approval modes** — SAFE / AUTO / YOLO — mapped to each CLI's native
  sandbox / permission flags (`--permission-mode`, `--full-auto`,
  `--dangerously-bypass-...`).
- **Memory editor** for `CLAUDE.md` / `AGENTS.md` / `GEMINI.md`, both global
  and per-project, edited directly on the server.
- **Subagents browser/editor** (Claude) — list, view, edit, delete, create
  from starter templates; tool-selector chips against the canonical tool list.
- **Slash commands** with inline autocomplete, including user-defined commands
  from `~/.claude/commands/*.md`.

### Phone bridge — let the agent observe the device (optional)
With [Shizuku](https://shizuku.rikka.app/) installed and a one-time grant, a
coding agent on your server can — through the same SSH connection — **read
your phone's logcat, take a screenshot, or run a shell command** at adb level,
so it can debug what it's building without you copy-pasting. Defence-in-depth:
per-chat opt-in, a warning on root@/shared hosts, an append-only audit log on
your server, and a **"Run shell from server" kill-switch** in Settings →
Security. Without Shizuku the bridge is unavailable.

### Mobile-native niceties
- **Picture-in-Picture** — minimise mid-turn and a floating window keeps
  showing the agent's live progress while you use other apps.
- **Live server stats** (CPU / memory / load) and a per-server activity log.
- Adaptive layout for landscape / tablets / foldables / DeX.
- **Cyberpunk-CLI** dark theme with a configurable neon accent, plus light and
  system themes.

### Privacy & data control
- **No hosted backend.** Your prompts go straight from the phone to your SSH
  server — never proxied.
- Credentials (SSH passwords, private keys, FIDO handles) are encrypted with
  the Android Keystore and never copied off the device.
- Crash / performance reporting via **Sentry is opt-out** (Settings → Privacy)
  and carries no chat content or identifiers.
- **No ads, no accounts, no quotas.** One-time GDPR "delete all my data" wipes
  every local store.

---

## Tech stack

- **Kotlin** + **Jetpack Compose** + **Material 3**
- **sshj 0.39** (SSH transport, custom SK auth method)
- **Room** (sessions, servers, keys) with secret-at-rest backed by the
  Android Keystore via `androidx.security`
- **yubikit-android** (Yubico's FIDO2/CTAP2 client library — vendor-neutral,
  drives any FIDO2 token over USB-HID or NFC ISO-DEP)
- **Hilt-free** ServiceLocator pattern; Coroutines + Flow throughout

## What's stored where

**On this device** (encrypted):
- Server records (host / port / user)
- SSH private keys (passphrase-protected, never copied unencrypted)
- Session listings + cached JSONL bodies
- Preferences (theme, accent, model, approval mode)

**On your servers** (read/written the same way the CLI itself does):
- Claude Code — `~/.claude/projects/.../*.jsonl`, `~/.claude/CLAUDE.md`,
  `~/.claude/agents/*.md`
- Codex — `~/.codex/sessions/.../*.jsonl`, `~/.codex/AGENTS.md`
- Gemini — rollouts under `~/.gemini`, `~/.gemini/GEMINI.md`

Nothing leaves the device except over SSH to the servers you've added (plus
opt-out Sentry).

## Status

- **v0.1.0 Beta** — early public beta; Play Store launch in flight.
- Solo-maintained. Issues / PRs welcome; expect human-speed responses.

## License

[PolyForm Noncommercial 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0/).
See [LICENSE](LICENSE).

- **Personal, hobby, research, education, government, charity** — free.
- **Commercial use** (selling forks, embedding in a paid product, putting it
  on a paid app-store listing under your own brand) needs a separate
  commercial license — contact the maintainer.
