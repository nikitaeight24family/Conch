# Conch roadmap

Product thesis: **mobile-first, AI-native IDE.
Phone is the tactile interface, the user's own VPS does the work, the
agent on that VPS does the thinking. No hosted backend.**

Score every candidate on two axes:

- **U** = solo dev needs it for daily personal use.
- **C** = widens the category vs Termius / Termux / Replit Mobile.

`P0` = both. `P1` = U only. `P2` = C only with clear ROI. Anything that
scores neither doesn't belong in this file.

Shipping now: **0.6.0**, live on Google Play.
[CHANGELOG.md](CHANGELOG.md) is the record of what each release carried.

---

## Open

| Pri | Item | Notes |
|---|---|---|
| P0 | **Auto-install APK from chat** | FileProvider + `Intent.ACTION_VIEW` for `application/vnd.android.package-archive`. Closes the dogfooding loop: ship a release from a train without leaving the chat. |
| P0 | **Screen capture → chat** | One-tap MediaProjection grab attached to the current chat as a PNG. The bridge already screenshots the phone when the *server* asks; this is the same picture, taken by the person holding it. |
| P0 | **Live preview loop** | Agent edits → rebuild → auto-install → auto-screenshot → image back in chat. The AI sees the result of its own change. Bridge + auto-install + screen capture composed. |
| P0 | **Phone-side code editor** | Compose editor for in-place edits when delegating to the agent is overkill. `/edit <path>`, syntax highlight, saves over SSH. The built-in viewers — diff, PDF, Markdown, images, text — are read-only today. |
| P1 | **Voice input transcribed on your server** | Whisper on the user's own machine, speech becoming chat text. Voice *messages* already ship (record in the composer, review, send as audio); this is the transcription half, and it stays off anyone else's infrastructure. |
| P1 | **Per-agent model selection persistence** | `selectedModel` is one string in prefs and leaks across agents. Make it `Map<Agent, String>`. |
| P1 | **Codex preview parser fix** | Filter session previews by `payload.type == "message"` **and** `role == "user"`; shell function-call output leaks into the previews. |
| P2 | **Clipboard bridge** | Phone clipboard ↔ `$CLIPBOARD` in agent commands. Pasting an image into the composer already works; this is the shell-side half. |
| P2 | **PR review in chat** | GitHub API → diff view → accept or reject from the phone. |
| P2 | **Logcat tier 3 (root)** | Kernel and dmesg for people who already have root. Detect with `su -c id`. |

---

## Shipped

Roadmap items that closed, and the ones that were never on the list because
they had not been imagined yet. Version numbers point at
[CHANGELOG.md](CHANGELOG.md).

**The category multipliers — things a mobile SSH client structurally cannot do**

- **The agent can observe the phone.** `conch-bridge logs / screenshot / shell`
  rides the SSH pool already open; the phone polls the inbox and answers at adb
  level. Per-chat opt-in, an audit log on your server, a kill-switch in
  Settings → Security, and an `audio` verb that ships disabled because a
  microphone records the room and not just the device. (0.4.8, hardened
  through 0.5.0)
- **Conch obtains that shell itself**, over the device's own loopback — no
  second app to install. This is what "logcat tier 2 without root" turned out
  to be. (0.4.8)
- **A real Linux on the phone**, for someone with neither a server nor a PC.
  (0.4.9)
- **The server's own ports, reachable from the phone** — a dev server on its
  `localhost:3000`, a database, an admin page bound to `127.0.0.1` — over the
  connection already open, with no second login and nothing installed on the
  server. (0.5.0)
- **Models that run on the phone itself.** A store that reads your RAM, chip
  and GPU and tells you what will actually run (0.5.2), then those models
  driving the shell as real agents, each on the chat template it expects
  (0.6.0). Offline, through the bundled llama.cpp engine.

**The product**

- Live on **Google Play**, free — no ads, no in-app purchases, nothing owed to
  us. (0.2.2)
- **Five agent CLIs**: Claude Code, Codex, Gemini, Grok, GitHub Copilot.
  (Grok and Copilot in 0.4.4)
- **Telemetry, analytics and crash reporting removed outright** — SDK and all,
  not made opt-out. (0.4.1)
- **Voice messages** recorded in the composer, played back before sending.
  (0.2.11)
- **A debug build that installs beside the release one**
  (`applicationIdSuffix ".debug"`), so self-update testing does not cost you
  the working app.
- **16 KB page alignment verified in the build itself**, after Play rejected a
  release over a 4 KB-aligned third-party `.so`. (0.2.12)
- Full-text search across every session on every server; a unified home across
  agents and machines; picture-in-picture that keeps showing progress.

## Done (1.0.x, before the version reset)

The app shipped under an earlier name and an earlier numbering; these entries
predate this repository.

- One-tap, one-PIN hardware-key auth across all paired servers (1.0.9)
- Flattened multi-key SK schema — removed primary/additional split (1.0.9)
- Security hardening: shell-injection, PIN lifecycle, ECDSA wire format,
  PEM / SK size bounds, TOFU audit log (1.0.9)
- Import existing SSH keys from storage / USB-OTG / DocumentsProviders (1.0.9)
- Inline tap-to-download for files mentioned in agent replies (1.0.7)
- Mid-turn prompt queueing; Stop ↔ Send swap (1.0.6)
- Smooth message streaming; real Stop (server-side signal, not just channel
  close); zombie-session fixes (1.0.5)
- Buffered sends during handshake; battery-whitelist banner; persistent
  navigation; lifecycle-aware tail-poll (1.0.5)
- ~~Anonymous opt-in telemetry~~ — removed entirely in 0.4.1; GDPR data
  erasure and Terms of Service (1.0.2)
- ~~Sentry crash reporting with opt-out~~ — removed entirely in 0.4.1 (1.0.1)
- Multi-server, multi-agent (Claude / Codex / Gemini) chat over SSH (1.0.0)
- FIDO2 / CTAP2 hardware-key auth (USB + NFC) with deferred-tap UX (1.0.0)
- Pool-based per-server SSH connection sharing (1.0.0)
- Background prefetch of session JSONLs (1.0.0)
- Memory editor, subagents browser, slash-command autocomplete (1.0.0)
- Signed AAB build via GitHub Actions; auto-incremented `versionCode` (1.0.0)

## Not building

These come up; the answer stays **no** until the thesis changes:

- **Local terminal emulator.** Termux owns that — we drive a real machine
  rather than becoming one. (There *is* a VT terminal for driving your server
  by hand; that is not the same product.)
- **WebView IDE.** Replit owns that — we're the native, BYO-server antithesis.
- **A hosted AI proxy, or inference of ours.** Breaks the privacy and economics
  moat. Models running on *your* phone or *your* server are the opposite of
  that, and are why local models were built the way they were.
- **Cloud sync or backup of user state.** Same reason.
- **Multi-user collaboration.** The persona is one developer.
- **Subscriptions, ads or in-app purchases.** Conch is free on Play and stays
  that way; the only money in this project is the commercial licence a company
  buys to use it at work.
