<p align="center">
  <img src="assets/banner.png" alt="Conch" />
</p>

<h1 align="center">Conch</h1>

[![tests](https://github.com/nikitaeight24family/Conch/actions/workflows/test.yml/badge.svg)](https://github.com/nikitaeight24family/Conch/actions/workflows/test.yml)
[![release](https://img.shields.io/badge/release-v0.3.8-brightgreen.svg)](https://github.com/nikitaeight24family/Conch/releases/latest)
[![Google Play](https://img.shields.io/badge/Google%20Play-Live-brightgreen?logo=google-play&logoColor=white)](https://play.google.com/store/apps/details?id=ai.eight24family.conch)
[![license](https://img.shields.io/badge/license-PolyForm%20Noncommercial-blue.svg)](LICENSE)

> **Your phone, your server, your AI. Build, test, ship — on the train.**

Conch is a mobile-first, AI-native Android client. The phone is the tactile
interface; your own VPS is where code, builds and tests run; an AI agent
(**Claude Code**, **Codex CLI** or **Gemini CLI**) on that server does the
heavy lifting. Nothing is hosted by us — no proxies, no quotas, no cloud
middleman. It's a terminal in your pocket, not a hosted AI service.

```
conch ▌ v0.3.8
// drive Claude Code, Codex or Gemini CLI on your own servers, from your phone.
```

<p align="center">
  <img src="screenshots/conch_01.jpg" width="22%" />
  <img src="screenshots/conch_02.jpg" width="22%" />
  <img src="screenshots/conch_03.jpg" width="22%" />
  <img src="screenshots/conch_04.jpg" width="22%" />
</p>

<p align="center">
  <a href="https://play.google.com/store/apps/details?id=ai.eight24family.conch">
    <img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" height="64" />
  </a>
</p>

---

## Now on Google Play

**Conch is live on Google Play** → **[play.google.com/store/apps/details?id=ai.eight24family.conch](https://play.google.com/store/apps/details?id=ai.eight24family.conch)**

- **Listing** — *Conch — AI agents over SSH*
- **Tagline** — *Drive Claude Code, Codex and Gemini CLI on your own servers, from your phone.*
- Install from Play for automatic updates, or sideload the signed APK from
  [Releases](https://github.com/nikitaeight24family/Conch/releases/latest) — same
  signed build, your choice.

---

## What's new in 0.3.8

**The limit bar answers for the chat you are in.** 0.3.7 taught it to show the
window that actually constrains the account rather than always the 5-hour one —
and then it over-corrected: a chat on one model could read a flat **100%**
because a *different* model's weekly cap was spent, a wall that cannot stop a
single turn of the model you are on. Per-model caps now count only for their own
model, the bar rests on your 5-hour window, and it steps off that window only
when another limit is genuinely at the wall — where it also says **which** one,
because a bare "100%" that happens to mean "weekly" reads as "all of it is gone".

---

## What's new in 0.3.7

**Every server gets its own colour.** Each host is assigned a random accent when
you add it, and its name is drawn in that colour everywhere in the app — the
sessions list, the chat header, search results, the terminal. On a list that
mixes machines you can now tell them apart without reading a word. The colour is
yours to change in the server's settings (swatch, hex field, and a die for a new
random one), and it adapts to the light theme instead of washing out: the hue
stays, the lightness is re-picked so the name always clears a readable contrast.

**Filter the list by server.** Long-press any agent chip — or the title — and
tick the hosts you want to see. The count next to each server tells you what
hiding it costs, and if a filter ever leaves the list empty it says so and offers
one tap back, instead of looking like your sessions disappeared.

**Work happening on the server shows up at once.** A long tool — an rsync, a
build, a 47-minute hash — writes nothing to the session file while it runs, and
the app used to read that silence as "the turn is over": the spinner went out and
the phone buzzed "done" while the agent was still working. It now asks the server
whether the CLI process is actually alive, so the truth comes from the machine
instead of a timestamp. A session you are driving from your laptop lights up in
the list too, which it never did before.

**The limit indicator stops lying.** It always showed the 5-hour window, so it
could read a comfortable 15% while the CLI in the same chat was refusing turns
because a *different* window was exhausted. It now shows the window that actually
constrains you, and when the CLI reports a limit the app believes it immediately
instead of waiting for its next poll.

**Faster everywhere you are looking.** Sessions you have open or that are moving
are checked every couple of seconds, the full list refresh dropped from thirty
seconds to ten, and returning to the app refreshes immediately instead of waiting
out a tick. All of it is gated on the app being on screen and on an unmetered
link, so a phone in your pocket still costs nothing.

---

## What's new in 0.3.6

**A vibration that would not stop.** The "answer finished" buzz could repeat at
full amplitude every couple of seconds until the app was force-stopped, and it
also fired the moment you pressed send while the reply was still coming. Both
came from the same mistake — treating "a row arrived" and "the working flag
dipped" as an answer landing — and both are gone: the buzz is capped at once per
turn, it waits for the state to settle, and the row it keys off can no longer be
mistaken for a new one.

**The agents' tasks belong to the agents.** With a fan-out running, the CLI
reports every background command from its one session-wide task registry on the
main stream, so the conversation filled with `task · completed · Background
command "…"` rows plus a "background tasks changed" line on every change. The
transcript now keeps only its OWN task rows; the agents' are counted with the
agents.

**A queued message cannot be lost.** Releasing the queue into a session that had
already died destroyed all three copies of the text — the queue, its saved
mirror, and the crash-insurance draft — while the send itself was silently
swallowed. A drain now refuses a session that cannot take it, and the rows stay
in the queue with their ✕.

**Fixes.** "+ new chat" opens a new chat from the agent switcher too (and no
longer leaves the abandoned one running). Agent rows can no longer be created by a
shell command, nor double-count their tokens. The floating window opens for a
connecting session and an in-flight upload as well, not only a generating turn.
Subagent text is searchable from the chat again.

## What's new in 0.3.5

**The floating window tells you what is happening.** Picture-in-Picture was
showing a reply from an old turn with no sign of whether anything was running —
and it opened on every swipe home, whether or not there was work to watch. It now
opens only while a turn is actually in flight, follows the live tail of *that*
turn, and carries the real status: the working verb, the elapsed timer, the token
count, how many agents are running and how many messages are queued. An idle
chat's last reply is labelled as such instead of impersonating live output.

**Nothing in that window can cancel your turn.** The Stop action in the PiP
header sat under the same tap that expands the window and fired with no
confirmation, so a reflex mis-tap destroyed a running turn. It is gone; Stop
lives in the prompt bar, where pressing it is a decision.

**"+ new chat" opens a new chat.** It used to hand back the previous
never-used chat — and if that one had wedged while connecting, every "fresh" chat
inherited the same hang. Abandoned brand-new chats are now closed instead of
left holding a CLI process and an SSH channel.

**The model in the top bar is the chat's model.** During a fan-out it followed
whichever subagent last spoke, flipping between models while the conversation
itself had not moved. Subagent turns no longer touch it, and no longer land in
the transcript.

**Agents report themselves.** Each row in the agents panel now shows its status,
its model, how long it has run, how many tools it has called and what it cost,
with the result, the failure or the tool it is running right now underneath — and
the collapsed header adds up the whole fan-out's tokens.

**Queued messages always go out.** A queue released only on certain turn-end
signals, and Stop consumed the one it needed, so follow-ups typed during a turn
could sit there unsent. Release is now based on the session being idle, however
the turn ended.

**Fixes.** One Conch entry in recents instead of one per return from the floating
window. The reply-landed vibration no longer depends on the chat being on screen
— it works with the app backgrounded — and the end of an answer is now three long
pulses you can feel without looking.

## What's new in 0.3.4

**A session running on the server comes back whole.** Close the app mid-turn,
reopen, and the chat is live again — the Stop button, the elapsed timer and the
token count all driven from the session file, and the answer painting straight
from it. Stop reaches a turn this process no longer owns. A read-only chat
opened while the link was down upgrades itself to a live mirror the moment the
transport returns.

**Truthful limits without a live process.** The usage bar reads the CLI's own
on-disk usage cache, so it stays accurate in an idle chat instead of showing
whatever the last turn left behind.

**Big sessions open instantly.** The background now mirrors only a session's
recent tail regardless of its total size, so any chat opens at once from cache
while traffic stays bounded.

**Runs on more servers.** No-bash, BusyBox, BSD and macOS hosts are handled at
every remote call; a Windows OpenSSH server is now labelled honestly instead of
reading as "agent not installed".

**Fixes.** Your prompt shows with the answer on reopen, not a beat behind it.
Stop reliably halts an owned turn instead of interrupting and cold-restarting.
A message sent into a running turn waits in the visible queue instead of cutting
it off. A session's place in the list is its last message, not a stray file
touch, so idle chats no longer fly to the top or spin a false "working". The
attach panel closes without attaching, and is compact.

## What's new in 0.3.3

**A dropped link recovers instead of looping.** Reconnect used to evict the
transport it had just rebuilt, and a chat could adopt a session that was already
dead — so the app reconnected every few seconds forever, and a message you had
typed sat there undeliverable. Both are fixed, and a prompt the CLI already took
is never re-sent: when a process dies mid-turn what is lost is the answer, not
the turn.

**Servers remember their host key.** The pooled connection accepted a "new" key
on every connect and never wrote it down. It pins on first use now, refuses a
changed key with an explanation, and Server detail can forget a pin when you
rebuilt the machine yourself.

**Task board.** The CLI's own checklist, folded from its task tool and pinned
above the prompt bar, rendered the way the terminal renders it.

**Subagents and background workflows.** A roster for the agents a turn spawns,
and a live `name · N/M agents · elapsed` line for a background workflow, read
from the run's own journal.

**Attachments.** The camera and the full-screen system picker, without the
embedded-picker header that could not be removed.

**Terminal.** Its own font, pinch to zoom, and history you can scroll.

**Restart the CLI from the chat** when it wedges, without leaving the session.

## What's new in 0.3.0

- **Switch model or effort mid-chat — the running session just changes.** The
  chat used to restart its CLI process and re-read the whole conversation to
  honour a pick; now the change is applied to the live process, so it lands at
  once and the session keeps its place. A pick the wire can't express still
  falls back to the old restart, silently.
- **Rewind.** Beside each message you sent there is a quiet ⟲ handle. Tap it and
  the conversation returns to just before that message, with its text back in
  the composer to edit and resend. If that turn changed files on your server,
  the sheet names every one of them and the ± lines first — only then does it
  offer to restore them. Long-press keeps belonging to the text: selecting and
  copying work exactly as before.
- **@ finds files on your server.** Type `@` and a few characters; the
  suggestions come from the machine the agent runs on, not from a guess.
- **Rename a session** from the chat's own menu — the name is the one Claude
  Code itself will show.
- **Plan limits and context usage come straight from the CLI.** The usage bar
  and the context breakdown now read the agent's own numbers over the same
  channel that carries the conversation, instead of a separate probe that could
  disagree with it.

Under the hood this release *removed* four subsystems: a hand-written terminal
emulator that scraped the `/model` menu, a second CLI process spawned just to
read `/context`, a `curl` that handled a raw OAuth token, and the process
restart behind every model switch. The model catalog now comes from the CLI's
own registry, so a new model family appears with no code change at all.

---

## What's new in 0.2.7

**Honest agent status, a smoother sign-in, and live limits everywhere.**

- **Signing in is one clean, animated flow.** Paste your code and it's picked up
  automatically; the window verifies your subscription inside and goes straight to
  "ready" — no leftover refresh spinner, no name prompt.
- **Claude shows its real status everywhere** — ready, no subscription, or sign-in
  expired — instead of a misleading "ready" on a dead login.
- **The usage/limit bar works for more sign-in types** (including `setup-token`
  logins), reading the limit from the account's own rate-limit signals.
- **Pull down on the Agents tab to refresh.**
- **Updating a server-installed CLI now works reliably**, even when a user
  `~/.npmrc` prefix would otherwise misdirect the install.

---

## What's new in 0.2.6

**Sessions load fast and from every agent at once; signing in finally shows progress.**

- **Faster, complete session lists.** Titles now appear almost immediately and from
  **all agents together** instead of trickling in one agent at a time — the
  background prefetch lists everything first, then fetches bodies. The slow OAuth
  liveness check no longer blocks the first listing on a freshly-added server.
- **Clearer sign-in.** After you paste your login code/URL, the agent row shows
  **"signing in…"** while the exchange finishes, instead of a stale "[ log in ]"
  that looked like it was asking you to start over.
- **Reliable Codex login detection.** A real ChatGPT login is no longer misread as
  "not logged in" (reads the CLI's status from stdout+stderr, checks the on-disk
  credential, and trusts the exit code).
- **Device-key lifetime matches your pick.** Choosing 1/3/7 days now actually mints
  the key for that long (it used to default to 30 while the selector showed 7), and
  changing it re-mints immediately so the countdown reflects your choice.
- **Steadier file uploads** with an honest message if the server can't be reached.

---

## What's new in 0.2.5

**A hard crash on the sessions list is gone, and the floating bar reads as glass everywhere.**

- **No more crash on the sessions list.** A Claude session can live in more than
  one file on the server (after a resume or compaction), which made it show up
  twice and crash the app the moment the list drew. It now appears exactly once —
  the whole unified list and the per-agent lists are crash-proof against duplicates.
- **Glass navigation bar, on every screen.** The floating tab bar now reads as
  frosted glass even over Settings and short lists (not just long scrolling chats):
  content scrolls cleanly beneath it and the capsule stops rendering as a flat
  solid block.

---

## What's new in 0.2.4

**The usage bar tells the truth in real time, and reasoning-effort stops lying.**

- **Live plan-limit bar.** The 5h / weekly usage bar now refreshes while the chat
  is on screen (every 30s, turn or idle) and the instant you return to it — the
  windows are account-wide, so it no longer freezes at a stale number while other
  sessions or the CLI move the limit. The reset countdown shows hours **and**
  minutes ("2h40m", not a floored "2h").
- **Real reasoning-effort.** The effort shown is the raw level the CLI actually
  uses (`xhigh` / `high` / `medium` / `ultracode`), never an invented label. For
  Codex the "default" level now follows your `config.toml`
  `model_reasoning_effort` — what codex really runs — instead of the model's
  catalog default.
- **Server-made sessions surface on their own.** The list re-checks connected
  servers every 30s, so a chat you start directly on the server appears without
  reopening the app.

---

## What's new in 0.2.3

**The model in the top bar is the model that's actually answering.**

- When a session is **force-switched mid-turn** — e.g. Claude Code's safeguard
  fallback swaps the model after flagging a message — the chat top bar now shows
  the model that's **actually running** (e.g. Opus 4.8) instead of staying on the
  one you originally picked. Before a switch (and when the pick and the running
  model are the same) it still shows your pick, with no flicker.

---

## What's new in 0.2.2

**Chat display gets honest: no phantom messages, Codex/Gemini stop cosplaying Claude, and your server-made sessions actually show up.**

### 💬 Messages
- Fixed a **phantom copy of your just-sent prompt** that could pin itself to the
  bottom of the chat and survive re-entering the session — an offline/reconnect
  echo that landed *after* the reply instead of collapsing onto your message.
- A message you send **no longer lights the "new" badge** on its own session row.

### 🗂️ Sessions list
- **Codex and Gemini sessions created directly on the server now appear** in the
  list, even when the live login check is momentarily unsure. An agent that has
  sessions always shows up (and gets a filter chip) instead of being hidden with
  no way to reveal it. Starting a *new* chat still needs a real login.

### 🎨 Per-agent polish
- The working indicator is **per-agent**: Claude keeps its own status vocabulary;
  **Codex and Gemini show a plain spinner and "Working"** rather than Claude's
  wording.
- The **welcome banner is pinned to the top** of every chat.
- Internal noise no longer leaks into the conversation: Codex's bubblewrap
  sandbox warning, a permissions-config startup notice, and Claude's image
  coordinate-mapping annotation are hidden.

### 🏷️ About
- Publisher is shown as **Conch Labs**.

---

## What's new in 0.2.1-beta

**The phone bridge stops lying, the phone glyph tells the truth, and Stop does what you'd expect.**

### 📱 Phone glyph — three honest states
- The phone glyph is now **tri-state**: **lit** when the bridge to your phone is
  actually live, **dimmed** when a session was wired but is offline now, and
  **absent** when it was never connected. The same glyph shows in the session
  list and in the chat title (moved next to the session name), so the two can
  never disagree.

### 🔌 Phone bridge no longer wedges a turn
- Fixed a stall where, after the agent used the on-device `conch-bridge` tool,
  the chat could hang on "thinking…" forever even though the reply had already
  landed. Root cause was an SSH receive-window starvation on the shared
  connection; the turn stream now keeps its window open, with a file-truth
  reconcile as a backstop.

### ⏹️ Stop, queue, and errors
- **Stop now advances the queue**: pressing Stop interrupts the running turn and
  immediately sends the next message you queued, instead of discarding it.
  (Use the ✕ on a queued message to drop it instead.)
- A turn you **stop yourself** now shows a calm "stopped", not a red error.
- Fixed: a message you sent could **vanish** from the chat while the agent still
  answered it (a dedup bug that bit large sessions).

### ➕ Small things
- The **new-session** button now also appears under the "All" tab, not just on a
  specific agent's tab.

---

## What's new in 0.2.0-beta

**Faster everywhere, and the phone bridge disappears.**

### ⚡ Speed
- **Sessions list** loads fast: servers with a saved key reuse the live SSH
  connection instead of a fresh handshake on every refresh, and the listing no
  longer reads whole multi-MB session files end to end.
- **Chat opens instantly** even for huge sessions: recent messages paint right
  away while the full history loads in the background (a 90+ MB chat used to
  block on the full download).
- **Usage / limit bar** fills the moment the connection is up — and re-checks
  after a turn — instead of sitting stale.

### 📱 Phone bridge → invisible
- Connecting your phone to a server is now silent plumbing: no technical
  handshake in the chat, just a quiet "phone connected" with a connecting-%
  indicator and a phone glyph that appears only once the link is confirmed.

### 🗑️ Deletes that stick
- Deleting a session on the phone now actually removes it on the server — even
  if nothing was connected at the time (it reconnects silently and reconciles
  on the next sync).

### 🤖 Models & fixes
- A chat with no explicit pick uses Claude's own recommended available model
  (no hardcoded names), and never shows or runs a model your plan has suspended.
- Fixed: new-chat crash, tab corruption after chat → Settings, duplicate
  question cards, typing over an open question now cancels it cleanly.
- conch sessions show up in the native `claude --resume` picker; added
  Codex `/review`.

See the [full release notes](https://github.com/nikitaeight24family/Conch/releases/latest)
and [CHANGELOG](CHANGELOG.md).

---

## Install

- **APK** — grab the latest signed build from the
  [Releases](https://github.com/nikitaeight24family/Conch/releases/latest)
  page and sideload it (enable Android's "install from unknown sources").
- **Google Play** — **[Conch is live on Google Play](https://play.google.com/store/apps/details?id=ai.eight24family.conch)**; install from there for automatic updates.

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
- Per-CLI **model picker** in the top bar, probed live from the agent.
- Open **as many parallel chats as you want, across as many servers as you
  want** — each chat is a real CLI process on your server, resumable across
  days. Files the agent writes land on your real filesystem.

### Sessions, search & offline cache
- **Unified sessions home** — every chat across every server and agent,
  newest first, like a messenger. Each row shows the session's own title,
  its server, and its last message.
- **Full-text search across every session** you've opened — find a
  conversation by what was said inside it and jump straight to the matching
  message (line-precise scroll to the hit).
- **Per-session disk cache** so reopening a chat paints prior turns
  instantly; a **background tail-poll** catches anything the agent wrote
  while the app was closed (e.g. you driving the same session from a laptop).
- **Background prefetch** quietly fills the cache for every session on every
  authorized server while you're on the home screen.

### Chat
- **Live token streaming** — assistant bubbles grow in place as the model
  writes, not a wall of text at the end.
- **Markdown rendering**, code-block copy, per-message copy, link detection,
  and an honest status indicator distinguishing "working on the server" vs
  "working on your phone".
- **Mid-turn queueing** — type and send while the agent is still working; the
  prompt is queued FIFO and runs the moment the current turn finishes.
- **Buffered sends** — send while the SSH session is still bootstrapping and
  it flushes the instant the session is ready (never silently lost).
- **Stop** sends a real signal to the remote agent process (SIGINT, then a
  hard channel close) — it actually stops thinking and writing files.
- **In-chat search** + a scrollbar for long conversations.
- **Attachments over SSH** — photos and arbitrary files streamed via `cat`,
  image clipboard paste, plus git-diff and per-CLI `init` from the attach sheet.
- **Inline file downloads** — tap a file path the agent mentions and it
  streams from the server to your device's Downloads.

### Built-in viewers & terminal
- A real **VT terminal** to your server for when you'd rather drive it by hand.
- In-app viewers for **diffs, PDF, Markdown, and plain text**, plus an
  **image viewer/annotator** for screenshots and attachments.

### Connection & authentication
- **Per-server pooled SSH** — one authenticated transport is shared by every
  chat, the sessions refresh, agent-status probes and prefetch on that host.
- **Auto-reconnect** on dropped channels with backoff; **seamless reconnect**
  on launch and network changes after the first connect — no extra tap until
  you actually send.
- **TOFU host-key pinning** with an audit trail.

### Hardware security keys (FIDO2 / CTAP2)
- Any authenticator that speaks **USB-HID FIDO or NFC ISO-DEP** — no vendor
  lock-in. SK key types (`sk-ssh-ed25519@openssh.com`,
  `sk-ecdsa-sha2-nistp256@openssh.com`) are signed via a custom sshj auth
  method and a deferred-tap CTAP flow.
- **One tap, one PIN per session** — enroll any number of keys per server.

### Software SSH keys
- **Import** existing Ed25519, RSA, ECDSA or DSA private keys (OpenSSH-v1,
  PEM, PKCS#8); passphrase only prompted when the key is actually encrypted.
- **Generate** a fresh Ed25519 keypair on device. Secrets are encrypted at
  rest via the Android Keystore.

### Agent control
- **Approval modes** — SAFE / AUTO / YOLO — mapped to each CLI's native
  sandbox / permission flags.
- **Memory editor** for `CLAUDE.md` / `AGENTS.md` / `GEMINI.md`, global and
  per-project, edited directly on the server.
- **Subagents browser/editor** (Claude) and **slash commands** with inline
  autocomplete, including user-defined commands from `~/.claude/commands/*.md`.

### Phone bridge — let the agent observe the device (optional)
With [Shizuku](https://shizuku.rikka.app/) installed and a one-time grant, a
coding agent on your server can — through the same SSH connection — **read
your phone's logcat, take a screenshot, or run a shell command** at adb level,
so it can debug what it's building without you copy-pasting. Defence-in-depth:
per-chat opt-in, a warning on root@/shared hosts, an append-only audit log on
your server, and a **"Run shell from server" kill-switch** in Settings →
Security. Without Shizuku the bridge is unavailable.

Since 0.2.11 the bridge also has **`conch-bridge audio`**, which records the
phone's microphone for a fixed number of seconds and writes an `.m4a` next to
the other bridge files on your server. It is the only bridge verb that ships
**disabled**: `shell` and `logs` read a device you handed over, a microphone
records the room you are sitting in and the people in it. Turn it on yourself in
Settings → Security → "Record audio from server", or it refuses and tells the
agent where the switch is.

### Mobile-native niceties
- **Picture-in-Picture** — minimise mid-turn and a floating window keeps
  showing the agent's live progress while you use other apps.
- **Live server stats** (CPU / memory / load) and a per-server activity log.
- Adaptive layout for landscape / tablets / foldables / DeX.
- **Cyberpunk-CLI** dark theme with a configurable neon accent, plus light and
  system themes.

---

## Build from source

```bash
git clone https://github.com/nikitaeight24family/Conch
cd Conch
./gradlew assembleDebug          # debug APK — no signing setup needed
./gradlew testDebugUnitTest      # run the unit test suite
```

The debug APK installs alongside a release build (`applicationIdSuffix
".debug"`). Release builds expect your own `release.keystore` +
`keystore.properties` at the repo root (both gitignored — never committed).
Requires JDK 17 and the Android SDK (compileSdk 37, minSdk 26).

## Tech stack

- **Kotlin** + **Jetpack Compose** + **Material 3**
- **sshj** (SSH transport, custom SK auth method)
- **Room** for sessions/servers/keys, secret-at-rest via the Android Keystore
  (`androidx.security`)
- **yubikit-android** (vendor-neutral FIDO2/CTAP2 over USB-HID or NFC)
- **Hilt-free** ServiceLocator pattern; Coroutines + Flow throughout

## What's stored where

**On this device** (encrypted): server records, SSH private keys
(passphrase-protected, never copied unencrypted), session listings + cached
JSONL bodies, preferences.

**On your servers** (read/written the same way the CLI itself does): Claude
Code `~/.claude/projects/.../*.jsonl`; Codex `~/.codex/sessions/.../*.jsonl`;
Gemini rollouts under `~/.gemini`. Nothing leaves the device except over SSH
to the servers you've added (plus opt-out Sentry crash reporting — no chat
content, no identifiers).

**Media you record** stays on the device until you send it. A voice message and
a photo taken in the composer are written to the app's cache, attached to the
chat, and swept after a day; a bridge `audio` capture is written to
`~/.conch-bridge/audio-*.m4a` on the server that asked for it. Both travel over
your own SSH connection and nowhere else — there is no backend to send them to.

## Known limitations

- **You bring the compute.** No hosted backend by design — you need your own
  SSH server with Claude Code / Codex / Gemini installed and logged in.
- **Phone bridge needs Shizuku** and only polls while Conch is in the
  foreground; backgrounded, it pauses (Android background-I/O limits) and
  surfaces a clear timeout.
- **Google Play** — Conch is live in production; the signed APK in Releases is
  the same build, for sideloading if you prefer.
- **Solo-maintained.** Issues / PRs welcome; expect human-speed responses.

## License

[PolyForm Noncommercial 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0/).
See [LICENSE](LICENSE).

- **Personal, hobby, research, education, government, charity** — free.
- **Commercial use** (selling forks, embedding in a paid product, listing it
  on a store under your own brand) needs a separate commercial license —
  contact the maintainer.
