# Conch

[![tests](https://github.com/nikitaeight24family/Conch/actions/workflows/test.yml/badge.svg)](https://github.com/nikitaeight24family/Conch/actions/workflows/test.yml)
[![release](https://img.shields.io/badge/release-v0.1.0--beta-orange.svg)](https://github.com/nikitaeight24family/Conch/releases/latest)
[![play store](https://img.shields.io/badge/Play%20Store-coming%20soon-blue.svg)](#install)
[![license](https://img.shields.io/badge/license-PolyForm%20Noncommercial-blue.svg)](LICENSE)

> **Your phone, your server, your AI. Build, test, ship — on the train.**

Conch is a mobile-first, AI-native Android client. The phone is the tactile
interface; your own VPS is where code, builds and tests run; an AI agent
(**Claude Code**, **Codex CLI** or **Gemini CLI**) on that server does the
heavy lifting. Nothing is hosted by us — no proxies, no quotas, no cloud
middleman.

```
conch ▌ v0.1.0 Beta
// drive Claude Code, Codex or Gemini CLI on your own servers, from your phone.
```

## Install

- **Play Store** — coming soon (closed test in flight).
- **APK** — latest signed build on the
  [Releases](https://github.com/nikitaeight24family/Conch/releases/latest)
  page; sideload via Android's "install from unknown sources."

Once installed:

1. **Pair a server.** Add host / port / user, paste or generate an SSH key,
   accept the host fingerprint on first connect (TOFU).
2. **Pick an agent.** Tap the server, choose Claude / Codex / Gemini —
   whichever CLI is on your `$PATH`.
3. **Open a chat.** Type, attach files or images, watch the model stream
   its reply. Resume any prior session from the sessions list.

Nothing extra to install on the host. SSH access plus the CLI of your
choice on `$PATH` is everything you need.

## Highlights

- **Native chat UI** over SSH `exec` channels. Stream-json parsed for
  Claude; rollout JSONL parsed for Codex; Gemini events for Gemini.
- **Unified sessions home** — every chat across every server and agent,
  newest first, like a messenger; with full-text search across them.
- **Per-session cache** so reopening a chat paints prior turns instantly,
  with a tail-poll catching anything the agent wrote while the app was
  closed (useful when you're driving the same session from a laptop too).
- **Background prefetch** quietly fills the cache for every session on
  every authorized server while you're on the home screen.
- **Memory editor** for `CLAUDE.md` / `AGENTS.md` / `GEMINI.md`, both
  global and project scope.
- **Subagents browser/editor** (Claude only) — list, view, edit, delete,
  create from one of seven starter templates.
- **Approval modes** — SAFE / AUTO / YOLO mapped to per-CLI flags
  (`--permission-mode`, `--full-auto`, `--dangerously-bypass-...`).
- **File and image attachments** uploaded over SSH (faster than SFTP).
- **Slash commands** with inline autocomplete plus user-defined commands
  from `~/.claude/commands/*.md`.
- **Auto-reconnect** on dropped channels; **mid-turn queueing** so you
  can stack the next prompt while the current one finishes.
- **Inline file downloads** — tap a path the agent mentions, the file
  streams to your device's Downloads folder.

## Hardware security key support

Conch authenticates against any **FIDO2 / CTAP2.1 token** over **USB
or NFC** — no vendor lock-in. SK key types
(`sk-ssh-ed25519@openssh.com`, `sk-ecdsa-sha2-nistp256@openssh.com`)
are signed via a custom sshj auth method and a deferred-tap CTAP flow
that keeps the NFC tag's short lifetime aligned with the SSH userauth
moment. **One tap, one PIN per session** — additional servers reuse
the same in-session enumerate, so the second connection is touch-only.

## Tech stack

- **Kotlin** + **Jetpack Compose** + **Material 3**
- **sshj 0.39** (SSH transport, custom SK auth method)
- **Room** (sessions, servers, keys) with secret-at-rest backed by the
  Android Keystore via `androidx.security`
- **yubikit-android** (Yubico's FIDO2/CTAP2 client library — vendor-neutral,
  drives any FIDO2 token over USB-HID or NFC ISO-DEP)
- **Hilt-free** ServiceLocator pattern; Coroutines + Flow throughout

## Privacy

No hosted backend. SSH only. Optional crash reports via Sentry (off by
default in debug, opt-out toggle in Settings → Privacy).

Full policy: <https://nikitaeight24family.github.io/sshai-pages/privacy.html>

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

Nothing leaves the device except over SSH to the servers you've added.

## Status

- **v0.1.0 Beta** — early public beta; Play Store launch in flight.
- Solo-maintained. Issues / PRs welcome; expect human-speed responses.

## License

[PolyForm Noncommercial 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0/).
See [LICENSE](LICENSE).

- **Personal, hobby, research, education, government, charity** — free.
- **Commercial use** (selling forks, embedding in a paid product, putting
  it on a paid app-store listing under your own brand) needs a separate
  commercial license — contact the maintainer.
