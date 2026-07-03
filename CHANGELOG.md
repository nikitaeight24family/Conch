# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

_Nothing yet — see ROADMAP for what's next._

---

## [0.2.3] — 2026-07-03

### Fixed
- When a Claude session is force-switched mid-turn (its safeguard fallback records
  a different model in `message.model`), the chat top bar now shows the model that
  is **actually running** — e.g. Opus 4.8 — instead of the model you originally
  picked. Before a switch, and when the pick and the running model are the same,
  your pick still shows (no flicker).

---

## [0.2.2] — 2026-06-30

Now live on Google Play.

### Fixed
- A phantom copy of a just-sent prompt could pin itself to the bottom of the
  chat and survive re-entering the session — an offline/reconnect echo appended
  *after* the reply instead of collapsing onto the optimistic message.
- Sending a message no longer marks its own session "new" in the sessions list.
- Codex and Gemini sessions created directly on the server now appear in the
  list even when the live login check is momentarily unsure; an agent that has
  sessions is never hidden (and keeps its filter chip). Starting a new chat is
  still login-gated.

### Changed
- The working-status indicator is per-agent: Claude keeps its status vocabulary
  and sparkle glyphs; Codex/Gemini show a plain spinner and "Working".
- The welcome banner is pinned to the top of every chat.
- Publisher shown in About is "Conch Labs".

### Hidden noise
- Codex's "could not find bubblewrap on PATH" degraded-sandbox warning, Claude
  Code's "Ignoring N permissions.allow entries" startup notice, and Claude's
  image coordinate-mapping annotation no longer appear as chat messages.

---

## [0.2.1-beta] — 2026-06-29

### Phone glyph
- The phone glyph is now tri-state — lit when the bridge is live, dimmed when a
  session was wired but is offline now, absent when never connected — and shows
  the same state in the session list and the chat title (moved next to the
  session name) so the two can't disagree.

### Fixed
- The chat could hang on "thinking…" forever after the agent used the on-device
  `conch-bridge` tool, even though the reply had already arrived. Root cause was
  an SSH receive-window starvation on the shared connection (the turn stream's
  window was never replenished while bridge traffic shared the transport); the
  turn stream now auto-expands its window, with a file-truth reconcile as a
  backstop.
- A message you sent could vanish from the chat while the agent still answered
  it — a deduplication bug that surfaced on large sessions.
- Stopping a turn yourself showed a red error; it now shows a calm "stopped".

### Changed
- Stop now interrupts the running turn **and** immediately sends the next queued
  message, instead of discarding the queue. Use the ✕ on a queued message to
  drop it instead.
- The new-session button also appears under the "All" tab, not only on a
  specific agent's tab.

---

## [0.2.0-beta] — 2026-06-27

### Performance
- Sessions list reuses the pooled SSH connection for saved-key servers (no
  fresh handshake on every refresh), and the listing no longer reads whole
  multi-MB session files end to end.
- Chat opens paint from the recent tail immediately while the full history
  loads in the background — huge (90+ MB) sessions no longer block the UI.
- Usage / limit bar fills as soon as the connection is up and re-checks after
  a turn, instead of staying stale.

### Changed
- A chat with no explicit model pick now uses Claude's own recommended
  available model (no hardcoded names), and never shows or runs a model the
  plan has suspended.
- Connecting the phone bridge is now invisible: hidden handshake, a quiet
  "phone connected" state with a connecting-% indicator and a phone glyph that
  appears only once the link is confirmed.

### Fixed
- Session deletes on the phone now propagate to the server even when no
  connection was live at delete time (silent reconnect + reconcile on sync).
- New-chat crash; tab corruption after navigating chat → Settings; duplicate
  AskUserQuestion cards in mirrored sessions; typing over an open question now
  cancels it cleanly instead of erroring.
- conch's headless sessions appear in the native `claude --resume` picker.

### Added
- Codex `/review` slash command.

---

## [1.0.9] — 2026-05-11

### Added
- **One-tap, one-PIN security-key auth.** Touch the FIDO2 token once,
  enter the PIN once — every server with a security key pairs in the
  same session uses the cached CTAP enumerate. Second server is
  touch-only.
- **Import existing SSH keys** from device storage, USB-OTG, SD card or
  any DocumentsProvider (Drive, etc.). Auto-detects OpenSSH-v1, PEM
  RSA / DSA / EC and PKCS#8 formats. Encrypted keys only prompt for a
  passphrase if sshj reports `BAD_PASSPHRASE` — most keys aren't, so
  the dialog stays out of the way. Real SHA-256 fingerprint and
  `authorized_keys`-shaped public line are derived from the imported
  bytes; pasting the public half on the server is a one-tap copy.
- **Retry escape hatches** on every long-running screen — pull-to-refresh
  on sessions / agent-picker / memory, plus a manual retry button when a
  request fails instead of a dead spinner.
- **Persistent refresh spinners** during background prefetch so it's
  clear when the cache is still filling.
- **TOFU host-key audit log.** First-connect host-key acceptances and
  any subsequent mismatch alerts are recorded locally for review.

### Changed
- **Flattened security-key schema.** The old "primary key + additional
  keys" split is gone — a server now just has a list of permitted
  credentials, no special-cased first one. Storage migrated from schema
  v6 → v7 in place; no user action required.
- **Recovery-on-connect prompts removed.** They added friction and the
  same recovery flow lives in the keychain screen, where it belongs.
- **Aggressive auto-copy softened.** Auto-paste / auto-copy behaviour
  in chat and the keychain is opt-in rather than the default; long
  pastes no longer steal focus.
- **Brand-neutral hardware-key copy.** All user-visible strings refer
  to "security key" or "FIDO2 token" rather than any specific vendor.
- **Agent-picker auth timeout** capped at 90 s — earlier the picker
  could hang indefinitely if the token went idle mid-handshake.

### Fixed
- **Shell-injection hardening** in the agent bridge and log-capture
  paths — every shell argument constructed from agent or system input
  is now quoted or passed via argv arrays, never string-concatenated
  into a `bash -c`.
- **PIN lifecycle hardening.** The CTAP PIN is held in a `CharArray`,
  zeroed immediately after use, and never written to logs or
  serialised in error reports.
- **ECDSA SK signatures** now use the correct on-wire format
  (`mpint r || mpint s` inside the SSH signature blob) — earlier
  builds emitted DER, which OpenSSH 9.x servers rejected.
- **PEM import size bound.** Imported private-key files are capped at
  64 KB before parsing; SK public-key blobs are capped at 4 KB. Stops
  a malformed picker pick from running away in memory.

### Security
- See **Fixed** above — shell-injection, PIN lifecycle, ECDSA wire
  format, PEM / SK size bounds.
- TOFU mismatch is now surfaced with a full audit row (timestamp,
  expected vs received fingerprint, server) so a stolen-server scenario
  has a paper trail.

---

## [1.0.7] — 2026-05-01

### Added
- **Inline file downloads in agent replies.** When the agent mentions
  a concrete file path in its response (`/tmp/foo.json`,
  `~/.claude/agents/code-reviewer.md`, etc.), a small clickable disk
  icon now appears right after the path. Tap → the file streams from
  the server straight to the phone's `Download/sshai/` folder over a
  fresh `cat` exec channel (mirroring how uploads work). The icon is
  outlined with a thin shimmering neon gradient so it reads as
  interactive, not decorative.
  - **Detection scope:** only the agent's textual replies
    (`AssistantText`) — tool invocations and tool outputs are
    deliberately ignored, so the chat doesn't get dotted with disks
    next to every transient `Read` / `Write` toolcall path mid-turn.
  - **States:** idle → spinner with progress (determinate when
    `stat` returns the byte count) → cyan checkmark on success.
    Failure paints the icon red — re-tap to retry. Tap on a
    completed download opens the file via the system viewer.
  - **Storage:** Android 10+ uses MediaStore so the file is visible
    in Files / Downloads with no runtime permission. Older devices
    get the app-private `Download/sshai/` dir.
  - **Pre-flight check:** an `[ -f ] && [ -r ]` + `stat -c %s`
    happens before opening the channel, so a tap on a stale path
    surfaces "file not found or not readable" without writing a
    zero-byte placeholder.

---

## [1.0.6] — 2026-05-01

### Added
- **Mid-turn prompts** — type into the prompt bar while the agent is
  still working and tap send; the new prompt is queued FIFO and runs
  the moment the current turn finishes, no need to wait for the
  result first. Implemented inside `AgentSession` as a coroutine-safe
  `ArrayDeque<String>` drained by a single drainer job —
  `claude --print` (and the codex/gemini equivalents) never run
  concurrently on the same session JSONL, so messages can't race or
  interleave.
- **Stop ↔ Send swap during work.** While a turn is in flight the
  action button is Stop; the moment the user starts drafting (text
  or attachments), it flips to Send so they can mid-turn-queue. An
  empty draft brings Stop back. No need to abort an in-flight turn
  just to add a thought.
- **Settings in chat & sessions overflow `⋮`** — last item in each
  menu, opens the same screen as the home cog. Shorter trip back
  from a deep chat.

### Fixed
- **`stop` no longer crashes the app.** When the user tapped Stop
  mid-IO, sshj's transport-reader thread threw
  `TransportException("Broken transport; encountered EOF")` straight
  up an unmanaged thread — Android treated it as fatal and killed
  the process. `SshAiApp` now installs a chained
  `UncaughtExceptionHandler` that swallows exactly this benign
  shutdown race and forwards everything else to the previous handler
  (Sentry / system default).
- **Session title is correct on cold open.** Tapping into an existing
  session used to show `// new chat` in the topbar for ~1 s while
  `remoteSessions` was being fetched. Title resolution now falls back
  to the first `UserText` in the message stream (Claude Code's de
  facto session title), so the bar reads the real prompt
  immediately. `// new chat` only appears when there is genuinely no
  resumeId — i.e. the user really did start a new session.
- **Working spinner is honest about node-spawned CLIs.** The remote
  liveness probe tightened from a strict `awk '$2 ~ /^claude$/'` to
  `awk '$2 != "bash" && $2 != "sh" && /(claude|codex|gemini)/'` —
  the previous version returned false negatives whenever Claude was
  running as `node /path/cli.js` (i.e. always on the official
  install). Conversely, the same filter excludes the probe's own
  bash invocation, fixing the false-positive where the spinner
  refused to stop after the agent finished.
- **Send during work no longer cancels the in-flight turn.**
  `AgentSession.send` used to call `currentMessageJob?.cancel()` if
  invoked while busy — silently terminating the agent mid-thought.
  It now appends to the FIFO queue and lets the drainer pick it up;
  the running turn finishes, results land in chat, then the queued
  prompt runs.
- **`═══ session id=…` banner spam.** With
  `--include-partial-messages`, Claude emits a system event with
  `sessionId` for every partial-message tick. The chat now hides
  the entire `═══ session …` block on those events; init events
  still render once.

### Changed
- **Light-theme system bars.** The status / nav bar icons are now
  driven by `WindowCompat.isAppearanceLightStatusBars = !useDark`,
  so the clock / battery / signal indicators stay legible regardless
  of OS theme — earlier they could vanish into a white background
  on Samsung devices when the phone ran dark mode but the app was in
  light mode.
- **Sessions list topbar** collapsed into a single `⋮` overflow with
  host / approval / memory / subagents / settings entries, matching
  the chat topbar. The agent-picker screen kept its server pill but
  now drops the redundant "Pick an agent" header and the
  `(checked … ago)` clutter.
- **Topbar working ✦** moved to the right of the model-picker label
  on the same line (was rendering on a second line of the column);
  visible whenever a turn is in flight, ours or a sibling
  instance's.

### Internals
- `AgentSession.pendingPrompts: ArrayDeque<String>` + `queueLock`
  guarding it, drained serially by `drainPromptQueue`. `cancelCurrent`
  clears the queue AND signals the active turn — Stop means stop
  everything.
- `cancelCurrent` falls back to `killZombieRemoteTurn` (`pgrep -af
  $sid` → SIGINT, SIGTERM after 800 ms) when there is no local
  `Session.Command` handle — covers the case where the app was
  force-stopped mid-turn previously and the resume now points at an
  orphan claude process.

---

## [1.0.5] — 2026-05-01

### Fixed
- **Send button silently dropped messages on a "zombie" session.** When
  the user backgrounded then reopened a chat during a transient
  reconnect, `activeResumeIds()` and `reapDeadSessions()` were
  destructively closing AgentSessions whose state momentarily looked
  Bootstrapping — cancelling the coroutine scope but leaving
  `state=Running` and `sshClient.isConnected=true` intact. Subsequent
  `s.send()`s emitted UserText to history but `scope.launch` returned
  an already-cancelled Job, so `runOneShot` never started. Both code
  paths are now read-only filters; sessions only ever leave the
  manager through explicit user actions.
- **Stop in chat actually stops the agent now.** The button used to
  cancel the local coroutine (which left `cmd.join` blocked on a
  non-coroutine sshj wait) AND merely close the SSH channel — the
  agent kept thinking and writing files on the server. Now Stop sends
  SSH `signal(INT)` to the remote `claude` / `codex` / `gemini`
  process, gracefully terminates the turn, and force-closes the
  channel after 800 ms if the signal was ignored.
- **Live messages now actually stream.** Added
  `--include-partial-messages` to the `claude --print` command, taught
  the parser to use Claude's stable `msg_xxx#blockIndex` ids instead
  of fresh UUIDs per chunk, and changed `AgentSession.emitMsg` to
  upsert by id — so a single assistant bubble grows in place as the
  model writes, rather than the whole reply slamming in at the end.
- **LIVE-marker truthiness.** Added 30 s SSH-level keepalive (sshj
  `keepAliveInterval`); after ~2 minutes of unanswered keepalives the
  socket flips to `isConnected=false` and `isAlive()` reports the
  truth. Earlier the marker stayed on indefinitely after Doze killed
  the underlying TCP.
- **Working spinner doesn't lie when Claude thinks for 30 minutes
  silently.** The "remote turn in flight" detector switched from a
  10 s no-growth timeout to an `lsof -t` probe on the session JSONL
  every poll tick — if any process is still holding the file open for
  writing, the spinner stays on; the moment that process exits (clean
  Result OR Ctrl+C), it turns off.
- **`isAlive()` no longer marks bootstrapping sessions as dead.**
  Now keys on `scope.isActive && sshClient.isConnected`, ignoring
  transient `Bootstrapping` state.
- **Chat opens at the very bottom now.** First composition uses
  `scrollToItem(last, scrollOffset = MAX)` (instant); subsequent
  message arrivals still animate. Earlier the chat opened, then
  visibly scrolled through 300 messages.
- **Keyboard pinning.** When the IME opens or closes the chat list
  re-pins to the bottom — the last messages stay visible above the
  prompt bar instead of sliding behind the keyboard.
- **`-r`-installable signed releases via local keystore.** Pulled the
  release keystore out of GitHub Secrets to the maintainer's local
  machine via a one-shot workflow; subsequent fixes can now be
  built locally with `./gradlew assembleRelease` (~1.5 min) and
  installed straight on top of an existing release without
  uninstalling — i.e. without wiping the user's data.

### Added
- **Buffered sends.** Tap send while the SSH session is still
  bootstrapping and the text disappears into a queue; as soon as the
  session reaches `Running` it's flushed in order. If 30 s pass
  without the session coming up, the text comes back to the input
  field automatically — never silently lost. Status sub-line under
  the prompt bar reads `// queued — sending when session is ready`
  while there's something in flight.
- **Battery-whitelist banner** on the servers list, OEM-agnostic
  (`Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with the
  `package:` deeplink). Drops the moment the user grants the
  exemption. Without it Doze can kill the foreground service while
  the user is on YouTube for 5 minutes.
- **Persistent navigation route.** `AppNav` now writes the current
  destination + arguments to DataStore on every change, and on cold
  start walks the natural back-stack to it (`agents → sessions →
  chat`) so swipe-out-of-recents → re-tap-icon doesn't dump the user
  on the servers list when they were mid-chat.
- **Lifecycle-aware tail-poll.** Foreground polls 5 s, with
  exponential back-off to 10 s and 30 s when the chat is idle. After
  5 minutes in background drops to 60 s. SSH keepalive keeps the
  socket alive in all paths so resume is instant.
- **Live spinner.** Three-dot bouncing animation right above the
  prompt bar, visible whenever a turn is in flight (local Working OR
  remote-side `lsof` says the JSONL is open). No more top progress
  bar, no more "agent working…" text underneath the input.
- **`[ end ]` button** on each LIVE row in the sessions list — closes
  the SSH channel for that session without leaving the list.

### Changed
- **Compact prompt-bar action button.** `[ send ↵ ]` / `[ ■ stop ]`
  shrunk to `[ ↵ ]` / `[ ■ ]` (~144 dp → ~36 dp).
- **`canSend = !anyUploading`** — typing is allowed even mid-handshake;
  the buffer takes care of the rest.
- **AddServer form** is ~1.5× more compact (`label` → `placeholder`,
  spacing 12 → 8 dp, vertical page padding 16 → 12 dp).
- **Topbar in chat:** title goes a single full-width line, with model
  picker as the only persistent affordance; host info, approval
  mode, memory, subagents and custom slash commands all moved into a
  single `⋮` overflow.
- **Server name affordance** in the chat overflow uses a 48 dp touch
  target (was ~20 dp); the old bracket-pill was inflexible for long
  names and cluttered the header.
- **`reapDeadSessions()` is now a no-op shim** — kept only for
  source-compat with old callers. See the zombie-scope fix above.
- **Find-by-resume reuse** in `ChatViewModel.startNewChat`: when the
  caller passes a `resumeId`, we reuse an alive AgentSession from
  the manager rather than spinning up a fresh handshake.

### Internals
- Diagnostic `Log.d`/`Log.w` in `AgentSession.send`, `runOneShot` and
  `ChatViewModel.send` — survive R8 in release builds, surface
  exactly why a tap-into-void happened in logcat.
- `runOneShot` no longer returns silently on `sshClient == null` —
  now emits an `Error("SSH not connected — tap refresh.")` and flips
  state to `Failed` so the auto-reconnect watcher takes over.
- `AgentSessionManager.findByResume` + `findByResumeIncludingDead`
  added for chat-VM reuse path.
- `ChatViewModel.killLive(resumeId)` lets the sessions list close the
  matching AgentSession on demand.

---

## [1.0.4] — 2026-05-01

### Fixed
- **`runOneShot` no longer silently returns when `sshClient` is null.**
  Previous behaviour: socket dropped between turns, the next send
  added a UserText to the chat and then nothing happened — no error,
  no spinner, no response. Now the same path emits an "SSH not
  connected — tap refresh" Error and flips state to `Failed`, so
  the user can see what's wrong and retry.

### Added
- **Diagnostic `Log.d`/`Log.w` in the turn pipeline** —
  `AgentSession.send` and `runOneShot` log start/finish/exit/exception,
  `ChatViewModel.send` logs when it drops a request because the local
  session id or the active AgentSession went missing. Survives R8
  in release builds.

### Changed
- **AddServer form is ~1.5× more compact**: floating `label` swapped
  for `placeholder` so each row drops from ~80 dp to ~56 dp,
  inter-row spacing 12 → 8 dp, vertical page padding 16 → 12 dp.

---

## [1.0.3] — 2026-05-01

### Fixed

- **Lost messages on chat re-open**: tapping into a session you had
  already opened (and that the `AgentSessionManager` still held alive
  in memory) used to spawn a brand-new `AgentSession` because the
  manager's cache key included a randomly-generated per-`ChatViewModel`
  `localId`. The new VM picked a fresh id, missed the existing session,
  and started a second one — losing every UserText that hadn't yet
  been ack'd by the CLI (saved into the server-side JSONL). Now
  `ChatViewModel.startNewChat()` first asks the manager for a live
  session matching `(serverId, agent, resumeId)` and adopts it
  instead of duplicating; the in-memory `_history` (with pending
  pre-CLI-ack UserTexts) survives the round-trip through the back
  stack.
- **Compact send/stop button**: the prompt-bar action button shrunk
  from `[ send ↵ ]` / `[ ■ stop ]` (~144 dp) to `[ ↵ ]` / `[ ■ ]`
  (~36 dp), giving the input field the room it needs on phones.
- **Status hint under the prompt bar**: a one-line
  `// agent: <state>` comment now reads "connecting…", "working…",
  "reconnecting (attempt N)…", "failed — pull-down to retry", or
  "idle · waiting for session to start" — the user is no longer
  staring at a silently-disabled outlined send button.

### Added

- **Room migration tests** (issue #9). Direct
  `Migration.migrate(JdbcSupportDb)` exercises against an in-memory
  Xerial SQLite — bypasses Robolectric so tests run in ~50 ms each
  and don't depend on schema-export JSON we never enabled.
- **SSH transport integration tests** (issue #10). In-process Apache
  MINA SSHD on a random localhost port covers TOFU host-key capture,
  host-key mismatch, password-auth fail-fast, public-key auth, and
  exec stdout/stderr/exit-code composition. RSA test key pair
  generated per run via BouncyCastle PEM writer.
- **Form-validation tests for `AddServerViewModel`** (issue #5)
  covering mandatory field gating and per-auth-method credential
  requirements.
- **`ChatViewModel` helper tests** (issue #6) for `parseCustomCommands`
  (custom slash-command discovery) and `computeCostStats` (token
  accounting across the chat).
- **`SessionsScreen` helper tests** (issue #7) locking down
  `formatStamp` relative-time strings.
- 17 existing `AgentEditViewModelTest` cases satisfy the AgentEdit
  coverage goal (issue #8).

### Changed

- Test infra: Robolectric 4.15.1, Apache MINA SSHD 2.13.2, Xerial
  sqlite-jdbc 3.46, Compose UI test artifacts, and Room testing
  added on `testImplementation`. Compose-UI semantic assertions are
  on hold pending a working Robolectric/AGP/JDK17 path (see README).
- `SshClient` now `open` so unit tests can subclass with fakes.
- `ServiceLocator` setters relaxed to `internal` and a
  `resetForTest()` is exposed for test isolation.
- `AppDatabase.resetForTest()` drops the cached singleton between
  Robolectric application contexts.
- Test count: 160 → 203.

---

## [1.0.2] — 2026-04-30

### Added

- **Anonymous telemetry** layered on top of Sentry: feature-usage
  breadcrumbs (chat-session-started, attachment-uploaded), info-level
  events (subagent CRUD, approval-mode changes, connection failures
  with FailureKind), and 20%-sampled performance traces (SSH
  handshake, agent bootstrap, chat first paint). Same Settings →
  Privacy → Crash reporting toggle gates everything; opt-out
  short-circuits all telemetry calls to no-ops.
- **GDPR data erasure**: Settings → Privacy → **Delete all my data**.
  Wipes Room DB (+ shm/wal/journal), DataStore, SharedPreferences,
  HistoryCache, EncryptedSharedPreferences master keys, then
  `ActivityManager.clearApplicationUserData()` for the final hammer.
- **Terms of Service** screen, rendered from `res/raw/terms_of_service.md`.
  Linked from About alongside Privacy Policy.
- **`docs/play-console-data-safety.md`** — copy-paste-ready answers
  for the Google Play Console Data Safety questionnaire when we get
  to a paid release.

### Changed

- **Privacy Policy** rewritten end-to-end to match what the app
  actually does as of 1.0.2. Discloses Sentry exact data shapes,
  acknowledges Sentry-server-side geo enrichment from source IP,
  spells out GDPR Art. 15 / Art. 17 routes.
- Sentry init reads opt-out from a SharedPreferences-backed flag
  (sync from `Application.onCreate`), with a DataStore mirror for the
  UI. Toggle takes effect on next launch.
- Sentry SDK now strips the entire `event.user` before send via a
  `beforeSend` callback — defense-in-depth against IP/geo leakage.
- IP scrubbing + relayPiiConfig rules enabled at Sentry org and
  project level; user.id and user.ip_address are redacted server-side
  on top of the SDK strip.

### Fixed

- Sentry's auto-init ContentProviders crashed the app on launch when
  `BuildConfig.SENTRY_DSN` was blank (debug builds). Auto-init is now
  disabled in `AndroidManifest.xml`; init lives in `SshAiApp.onCreate`
  guarded by DSN-not-blank and the user opt-out.

---

## [1.0.1] — 2026-04-29

### Added

- **Crash reporting** via Sentry. Errors are sent anonymized — no message
  contents, no host names, no user IDs. Build env (`debug`/`release`)
  and version are tagged for filtering. R8 mapping is uploaded by the
  Sentry Gradle plugin so stacktraces deobfuscate in the dashboard.
- **Settings → Privacy → Crash reporting** toggle. Default **on**.
  Stored in both DataStore (UI) and SharedPreferences (so
  `Application.onCreate()` can read synchronously); change takes effect
  on next app launch.
- Local debug builds (without `-PsentryDsn=...`) skip Sentry entirely —
  no phoning home from your dev installs.

---

## [1.0.0] — 2026-04-29

First public release.

### Added

**Core chat:**
- Native Android chat UI driven over SSH `exec` channels with stream-json output.
- Support for **Claude Code**, **Codex CLI**, and **Gemini CLI** with per-CLI parsers.
- Per-CLI model picker in the topbar (Opus / Sonnet / Haiku for Claude; gpt-5 / gpt-5-codex for Codex; etc.).

**Caching & offline-friendly behaviour:**
- Per-session disk cache of JSONL bodies for instant reopen.
- Background tail-poll on the remote session file detects external growth (you typing on your laptop into the same session) and a `● remote · listening` banner surfaces it.
- Background prefetch across all authorized servers from the home screen.
- Sessions list cache with silent background refresh.
- Agent install/auth-status cache to avoid re-probing on every Agent Picker open.

**Memory & subagents:**
- Memory editor for per-CLI instruction files (`CLAUDE.md` / `AGENTS.md` / `GEMINI.md`), both global and project scope.
- Subagents browser/editor for `~/.claude/agents/*.md` (Claude only): list, view, edit, delete, create. Seven starter templates included (code-reviewer, test-writer, refactor-helper, bug-hunter, doc-writer, release-notes, blank).
- Tool selector in the subagent editor uses chips against a canonical list of Claude Code tools.

**Approval / sandbox controls:**
- Three-level approval mode (SAFE / AUTO / YOLO) shared across CLIs, mapped to the right per-CLI flags (`--permission-mode acceptEdits` / `--full-auto` / `--dangerously-bypass-approvals-and-sandbox`, etc.).
- "Ask agent to drop its own limits" action sends a per-CLI prompt that writes the right config file and resumes whatever was in flight.

**Attachments:**
- Photos and arbitrary files uploaded over SSH via `cat > path` (faster than SFTP on real networks).
- Git diff and per-CLI `init <FILENAME>` available from the attach sheet.
- Image clipboard paste.

**Slash commands:**
- Inline `/` autocomplete in the prompt bar.
- Built-ins: `/clear`, `/new`, `/diff`, `/init`, `/memory`, `/agents`, `/model`.
- User-defined commands discovered from `~/.claude/commands/*.md`.

**Connection lifecycle:**
- Auto-reconnect on dropped SSH channels with exponential backoff.
- Auto-mirroring foreground service keeps live sessions resident.
- Live indicator on session rows shows which sessions are held by an open chat.

**Look and feel:**
- Cyberpunk-CLI dark theme with configurable neon accent.
- Light theme.
- System / Light / Dark theme toggle in Settings.

**Distribution:**
- Apache `sshj` for SSH, `androidx.security` for at-rest secret encryption.
- Signed release APK published via GitHub Actions on tag push.
- 160 unit tests, no device required to run them.
- Release builds use R8 + resource shrinking (~5.5 MiB APK vs ~24 MiB debug).

[Unreleased]: https://github.com/nikitaeight24family/Conch/compare/v0.2.3...HEAD
[0.2.3]: https://github.com/nikitaeight24family/Conch/releases/tag/v0.2.3
[0.2.2]: https://github.com/nikitaeight24family/Conch/releases/tag/v0.2.2
[0.2.1-beta]: https://github.com/nikitaeight24family/Conch/releases/tag/v0.2.1-beta
[0.2.0-beta]: https://github.com/nikitaeight24family/Conch/releases/tag/v0.2.0-beta
[1.0.9]: https://github.com/nikitaeight24family/Conch/releases/tag/v1.0.9
[1.0.7]: https://github.com/nikitaeight24family/Conch/releases/tag/v1.0.7
[1.0.6]: https://github.com/nikitaeight24family/Conch/releases/tag/v1.0.6
[1.0.5]: https://github.com/nikitaeight24family/Conch/releases/tag/v1.0.5
[1.0.4]: https://github.com/nikitaeight24family/Conch/releases/tag/v1.0.4
[1.0.3]: https://github.com/nikitaeight24family/Conch/releases/tag/v1.0.3
[1.0.2]: https://github.com/nikitaeight24family/Conch/releases/tag/v1.0.2
[1.0.1]: https://github.com/nikitaeight24family/Conch/releases/tag/v1.0.1
[1.0.0]: https://github.com/nikitaeight24family/Conch/releases/tag/v1.0.0
