# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **A model on this phone answers straight away — no Linux, no bridge, no
  CLI.** Tapping a downloaded model opens a chat with it inside the app: the
  words go to the engine already serving on this device, and the answer streams
  back. Getting a model was already one tap; TALKING to one used to go through
  a CLI inside the phone's Linux, which meant wireless debugging and developer
  options first. The chat shows what a local turn actually costs — the engine's
  live cpu / ram / heat where a cloud chat shows a quota — plus the window it
  really got and the measured speed of the last answer. Pictures go to models
  whose vision pack is installed; thinking is a switch in the top bar; Stop
  stops, and keeps whatever the model already said. Conversations are kept on
  the phone and listed under the models, so you can come back to them. The real
  agent — a genuine CLI with its tools, shell and sessions on the same local
  model — is one tap further, from that chat's `[ agent ]`.
- **The store says where its speed number came from.** "measured on this
  device", "measured on N phones like this one", "SoC-class estimate", or
  "generic estimate — this chip is unknown to us". A guess and a measurement
  used to look identical, and the guess prices every Snapdragon 8 alike — an
  8 Gen 1 and an 8 Elite — so now it can be replaced per chip by real
  measurements as they come in.
- **Share your measurements, by hand, reading them first.** The device sheet can
  show exactly what a contribution would say and hand it to an app you pick.
  Conch itself still sends nothing anywhere.
- **Dictate to a local model.** `[ dictate ]` in a local chat records you
  and turns it into text ON the phone (Whisper, 60 MB, 99 languages), then
  drops the words in the composer so you can fix them before sending.
  Nothing is uploaded, no Google speech service is involved, and the
  recording is deleted the moment it has become text.
- **Search your local chats by meaning.** Local models now come with an
  optional search model (0.6 GB, 100+ languages): tap index once and the
  conversations on your phone become searchable by what they were ABOUT -
  ask "lighthouse keeper storm" and get the chat where that story was
  written, ask in Russian and still find the English answer. It runs as its
  own small engine on this device, it never touches the network, and nothing
  is indexed until you ask for it.
- **A model's real context, not the one Conch asked for.** Some models are
  trained on a smaller window than the app requests, and the engine quietly
  caps it — so a chat could trim its history to twice the room it really had,
  and an agent could plan for space that did not exist (and then fail every
  send). The number now comes back from the engine: the chat header shows what
  you actually have.
- **A loaded model stays loaded while you are away — and says so.** While a
  model is in memory Conch keeps a quiet notification with its name and one
  button to unload it, which also stops Android from killing the model the
  moment you switch apps. It still frees itself after two minutes idle unless
  you tap "keep" — the memory is yours, and the choice is visible either way.
- **A hot phone gets a gentler launch.** Conch asks the system how close the
  phone is to throttling and starts the model with fewer threads when it is
  already warm: a slightly slower answer instead of the "device is too hot"
  overlay.
- **Downloaded models are checked against their published checksum.** Every
  model file now arrives with the SHA-256 its repository publishes, and Conch
  hashes what it downloaded before the file counts as ready — a transfer that
  ends the right length with the wrong bytes is deleted and says so, instead
  of becoming a model that quietly misbehaves.
- **Your own Hugging Face account, if you want one.** Connect a read token in
  the model store and the gated weights — Llama, Gemma and the rest that need
  a licence click-through — become ordinary one-tap downloads. The token is
  stored encrypted on the phone, sent only to huggingface.co (never to the
  download CDN), shown only as "connected", and forgotten on one tap.
- **Import a model you already have.** `[ import ]` in local models takes a
  `.gguf` off this phone, an SD card or a USB stick and makes it a model like
  any other — it reads the file's own header for its name and its memory cost,
  so the "fits / tight / short" verdict is as honest for your file as for the
  store's.
- **Models the store did not curate are sized from their own header.** A model
  found by search used to be priced by a flat guess, which on a small phone is
  the difference between being offered and being hidden. Its real architecture
  is read from the file after the first download.
- **The model on this phone is now behind a key — and other apps can ask for
  one.** The engine's port was reachable by every app on the device (Android
  does not isolate 127.0.0.1), and it answered anyone. It now requires a key,
  and Conch holds its own. Another app can ask for access with a single
  intent: you see who is asking and what it will be able to do, and if you
  allow it, it gets a key of its own — prompts and answers from a model that
  runs on your phone, nothing else, nothing leaving the device. Every grant is
  listed under `local models → api access` with one tap to revoke, which takes
  effect immediately. For whoever writes the other app: `docs/local-model-api.md`.
- **The store learns this phone's speed from ordinary use.** Every answer
  carries the engine's own timings, so a model's measured tok/s (and every
  estimate re-derived from it) now comes from real work instead of waiting for
  someone to press the verify button.
- **Local models run on the phone's GPU.** The app carries llama.cpp's OpenCL
  backend and offloads the whole model to the graphics chip when the phone has
  a loadable vendor OpenCL stack (Adreno, most Mali) — prompt digestion, the
  part an agent turn is mostly made of, gets many times faster (measured ×7.5
  on a 1.7B and ×16 on a 4B on Adreno 830; the first Codex turn on the 4B
  drops from ~13 minutes to ~1.5). The telemetry bar and the engine row say
  `gpu` only when layers actually landed there; phones without a usable GPU
  stack run exactly as before, and a GPU that fails or keeps dying demotes to
  CPU on its own — slower beats dead.

- **The phone's Linux is a machine on the list, like any other.** Installing it
  adds an ordinary server row, reached through an ssh endpoint the environment
  raises on `127.0.0.1:8022` (OpenSSH, key-only, loopback-only, brought up by
  the connection itself). Agents install, log in and chat there through exactly
  the same screens a server uses — there is no phone-specific flow to learn, and
  no second copy of those screens to keep correct. Its page keeps only what it
  is for: the size, and install / remove.

- **Local models drive a real agent, offline.** Downloaded models live where
  agents are installed — the phone's agent panel — and a downloaded model is
  a model choice for the genuine Codex CLI: tap it and a Codex chat opens
  already set to it, with Codex's real tools, sessions and approval modes,
  while every token is generated on the phone. No account, no network, no
  quota.
- **Model downloads keep going with the phone in your pocket.** A quiet
  foreground notification shows live progress and disappears when the
  download ends; interrupted downloads still resume from where they stopped.
- **Local models, on the phone itself — with nothing to set up.** The
  "this phone" page grows a `// local models` section: four small open
  models (0.8–2.3 GB), each one tap to download, resumable if interrupted,
  one tap to run, one to delete. No Linux environment, no phone bridge, no
  account — the inference engine (llama.cpp's official Android arm64 build)
  ships inside the app, and a running model serves an OpenAI-compatible API
  on 127.0.0.1, reachable only from this phone. The section shows live free
  ram and storage, and against them an honest per-model verdict: fits now,
  tight, or short by how much. Model downloads are the one network call the
  app makes beyond your servers, only when you tap download, from Hugging
  Face.

### Fixed
- **The phone no longer accuses itself of a man-in-the-middle.** Swapping the
  environment's ssh daemon (dropbear → OpenSSH) changed its host key, and every
  connect then refused with a security warning about the owner's own phone,
  demanding the forget ritual for a change the app made itself. The pin now
  follows the environment's own key file, read off the rootfs over the phone
  shell — never learned from the port, so a foreign process squatting the
  loopback port still hits the refusal.
- **The host-key-changed message arrives whole.** The connect dialog cut it at
  120 characters — it ended mid-word at "Expect", with the fingerprints and the
  recovery path ("fingerprint → forget") in the part that was thrown away, and
  chat screens would have degraded the same message to "IllegalStateException".
- **Signing in to Claude Code works on a machine it never ran on.** A fresh
  install asks for the login method right after the theme picker, before the
  composer exists; the sign-in flow only answered that menu after typing
  `/login` into the composer, so on a virgin machine (the phone's Linux) the
  dialog spun until the watchdog gave up.
- **A Claude chat works on hosts without GNU coreutils.** The send command
  hardcoded `stdbuf`, which BusyBox userlands (Alpine, minimal servers, the
  phone's Linux) do not have — the whole turn died on
  `stdbuf: command not found`. The wrapper now applies only where the host has
  it, and the phone's environment gets real coreutils with the agents.
- **The phone's Linux could not run node at all, and that is why nothing
  installed.** Its `proot` was 5.1.0, which has no handler for the `statx`
  syscall — so every path node looked up was answered by Android's real root
  (`ENOENT: lstat '/usr'`) and npm was dead on arrival. The bundled runtime is
  now one that translates it, and an environment created by an older Conch is
  upgraded to it instead of keeping the one that cannot work.
- **Two agents installed at once no longer knock each other out.** The
  environment has one package database with one lock; the second tap used to
  lose it, continue without node, and fail in two seconds — the row simply
  going back to `[ install ]`. Installs are queued now, and a failure shows what
  it said with `[ retry ]`.
- **Agents no longer install broken on Alpine — on the phone OR on a server.**
  `apk add nodejs npm` produces a Claude Code that installs cleanly and then
  crashes: on musl it needs bash, libgcc, libstdc++, a real ripgrep and
  `USE_BUILTIN_RIPGREP=0`. Both install paths now provide all five (measured
  end-to-end: `2.1.251` on the bundled Alpine 3.21).
- **An install in the phone's Linux survives the screen.** It runs detached with
  its own log, so leaving the page — or the app being killed — no longer kills a
  100 MB package install; coming back re-attaches to it, and says whether it is
  installing or removing rather than always the former.

## [0.5.1] — 2026-08-30

### Fixed
- **The model chip no longer speaks for a server it never asked.** What a CLI
  starts on with no `--model` is read from that machine's own settings, and it
  was kept in one slot for the whole app — so whichever server answered last
  named the model for every other one. A brand-new chat on a box configured for
  Sonnet opened wearing an **Opus 5** label, launched (correctly) without a
  model flag, and answered as Sonnet; nothing on screen admitted the swap until
  the session reported itself. Each server now keeps its own answer, chip and
  command line read the same record, and a server that has never been asked
  shows no model rather than borrowing one. Since the app is already connected
  when a chat opens, it now asks that machine instead of reusing a stale global.
- **A dropped connection inside a tunnel no longer kills the app.** Both
  directions of a forwarded connection close together; the one that lost the
  race then reached for a socket the winner had already closed, and the
  resulting crash took the whole process down — every SSH connection and any
  upload in flight with it.
- **Uploads stop piling up on the server.** Every file a phone ever sent stayed
  in the staging folder forever, so a server that receives photos fills its
  `/tmp` over weeks — and a full disk surfaces as the same unexplaining
  "Stream closed". Staged files older than a week are now swept with each
  upload, and a failure that looks like a full disk sweeps harder, re-checks
  the free space and tries again before giving up. Nothing breaks when a file
  goes: a re-send is verified and repeated automatically.
- **An attachment survives the connection being rebuilt, and says what really
  went wrong.** A transfer holds its own channel for its whole duration, so it
  could not use the reconnect every other command gets: one rebuilt connection
  and the upload died with `Stream closed` while the chat around it kept
  working. It now retries once on a fresh connection, never retries something
  the server actually refused, and checks free space before blaming the
  network — a full `/tmp` now reads as "out of space" with the numbers.

---

## [0.4.8] — 2026-08-29

### Fixed
- **The limit percentage stopped walking backwards.** The usage bar is painted by
  a ladder of sources on every refresh, and one of them reads the CLI's persisted
  state, which is trusted up to an hour old. It landed between two live readings,
  so the same window rendered 87 → 86 → 87 → 86 on a loop every eight seconds.
  A reading may no longer replace a fresher one; captured in logcat before the
  fix and verified gone after it.
- **Work that has finished stops claiming to be alive.** A subagent was retired
  only by its completion event, so a disconnect, a reinstall or a killed process
  left the roster saying "5 agents · 5 live" under a turn that had already
  printed its final summary. An agent from an earlier turn is now retired once a
  later turn ends, whether or not its completion ever reached us — backgrounded
  agents, which are launched to outlive their turn, still keep their row. The
  same applies to a background task the CLI never confirmed.

### Changed
- **The agent picker leads with three names again.** Claude, Codex and Gemini
  stay in view; the other seven fold behind a `[ more · 7 ]` row, which also
  says how many of them are installed and signed in on that server — burying a
  working agent behind a silent toggle would be the picker lying by omission.

### Added
- A credit in About for AndroidHarness, whose author's project is where the idea
  of an on-device Linux environment came from. None of its code is used here.

---

## [0.4.7] — 2026-08-29

### Fixed
- **The session list is instant again.** Opening the app showed nothing for
  seconds before the chats appeared. The data was on disk the whole time: the
  first paint was waiting on the last-message preview for every cached session,
  and those are read by parsing the tail of each chat body — 380 sessions x
  128 KB of JSONL through the agent's own stream parser, ~48 MB of work, before
  a single row could be drawn. Rows now paint from the cache immediately (with
  the listing's own preview) and the bodies are read afterwards, newest first,
  repainting as they land.
- **Opening a chat no longer waits on a full-file scan.** A diagnostic counter
  walked the entire cached body and hashed every line on the open path. It is
  compiled out of release builds but always on in debug, and it skips only past
  32 MB — so the biggest chats were read end to end, on the main thread, every
  single open. It now runs after the chat is on screen, off the main thread.
- **The unread watermark stopped hammering the disk.** It was read from its own
  file for every session on every list refresh — hundreds of file lookups every
  2.5 seconds — and is now memoized, with every writer going through one place
  so the memo cannot drift from disk.

---

## [0.4.6] — 2026-08-29

### Added
- **Five more agents: Qwen Code, Cursor CLI, opencode, Crush and Continue CLI**
  — ten in total. Each arrives with its own streaming parser, session listing
  and history replay, model picker, approval-mode mapping replayed through the
  real binary, brand mark and working spinner. Two of them keep history in
  SQLite rather than files, so a session can now be read back through the CLI's
  own export command instead of a file path.
- **The shield tells the truth about CLIs that never ask.** Crush executes every
  tool unprompted in headless mode and Continue decides its toolset up front;
  both now say so above the mode rows instead of showing a shield that implies
  a prompt that never comes.
- **Installer and sign-in are no longer agent-specific code.** A CLI that ships
  outside npm (Cursor) declares its vendor installer on its spec, and a CLI with
  no headless sign-in says so and steers to an API key rather than opening a
  login that would hang on a server with no browser.
- **The picker shows when a third-party guard on your server is protecting a
  CLI.** Guards like HOL Guard hook each CLI through the CLI's own hook system,
  so one installed on your box already covers the turns Conch drives. The app
  installs nothing, launches nothing and adds nothing to the launch path — it
  reads the state and shows a `Guard` chip on the agents it actually covers. The
  read is cached against the guard's own state directory, because asking costs
  ~6.6 s.

### Fixed
- The flag audit no longer reports "verified" for CLIs whose parser ignores flag
  values when `--help` is present (yargs — Gemini, Qwen). A probe that cannot
  fail is not evidence, and it now says so.
- **The phone bridge answered nothing while the app was off screen.** Polling
  paused entirely when the app was backgrounded, so a `conch-bridge ping` issued
  by an agent with the phone in a pocket died on the CLI's own 30 s deadline —
  in exactly the situation the product is built for. It now keeps answering off
  screen at a fifth of the rate, and stays fully idle only on servers whose
  chats were never wired to the phone.
- **A rate-limit warning could report 0%.** The pushed limit event carries
  utilization as a fraction (0.25), while the usage endpoint reports a percent;
  truncating the fraction printed "seven day · 0%" next to a usage bar that
  said 25%. The two conventions are normalized, and the window is named the way
  the usage bar names it. A percentage that rounds away is now left out rather
  than printed as zero.
- **Unused rate-limit windows no longer appear in the usage panel.** Claude's
  payload ships internal model codenames sitting at 0% with no reset time
  ("Nimbus quill"); an unrecognized window in that state carries no information
  and is dropped. Any window with usage or a live reset still surfaces on its
  own, so a genuinely new model still appears the moment it costs something.
- **The input row could be pushed off screen.** Expanding both the subagent
  roster and the usage panel — both pinned above the prompt — overflowed the
  column. Each is now bounded to a fraction of the screen and scrolls inside
  itself; the composer can no longer be squeezed out.
- **"connecting phone… 95%" is gone.** The phone-connect row counted up a
  percentage that measured nothing. It now says what it is doing, and still
  flips to a quiet "couldn't connect" when a handshake stalls.

---

## [0.4.4] — 2026-08-28

### Added
- **Two new agents: xAI Grok Build and GitHub Copilot CLI.** Full first-class
  integrations alongside Claude/Codex/Gemini: streaming chat (Grok speaks the
  Anthropic wire format by its own contract; Copilot emits first-class JSONL),
  session listing/resume/fork with the CLI's own titles, per-agent brand marks
  and spinners (Grok's ◆ pulse, Copilot's blinking mascot eyes — nobody
  inherits anyone else's look), device-code sign-in for both, model catalogs
  from each CLI's own registry, reasoning-effort control (Grok), plan mode
  (Grok `--permission-mode plan`, Copilot `--plan`), turn-state mirroring off
  each CLI's own session files, and install/update via npm like the others.
- **Approval modes are version-pinned and audited.** Every agent's
  SAFE/AUTO/YOLO(/PLAN) flag mapping is recorded against the CLI version it
  was tested on (`CliContract`), installs pin that version (`@latest` only on
  an explicit Update tap), and after every install the flags are replayed
  through the CLI's own parser — a mode the installed binary rejects is
  marked in the shield sheet instead of failing silently at send time.

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
  the process. `ConchApp` now installs a chained
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
  disabled in `AndroidManifest.xml`; init lives in `ConchApp.onCreate`
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

[Unreleased]: https://github.com/nikitaeight24family/Conch/compare/v1.0.9...HEAD
[1.0.9]: https://github.com/nikitaeight24family/Conch/releases/tag/v1.0.9
[1.0.7]: https://github.com/nikitaeight24family/Conch/releases/tag/v1.0.7
[1.0.6]: https://github.com/nikitaeight24family/Conch/releases/tag/v1.0.6
[1.0.5]: https://github.com/nikitaeight24family/Conch/releases/tag/v1.0.5
[1.0.4]: https://github.com/nikitaeight24family/Conch/releases/tag/v1.0.4
[1.0.3]: https://github.com/nikitaeight24family/Conch/releases/tag/v1.0.3
[1.0.2]: https://github.com/nikitaeight24family/Conch/releases/tag/v1.0.2
[1.0.1]: https://github.com/nikitaeight24family/Conch/releases/tag/v1.0.1
[1.0.0]: https://github.com/nikitaeight24family/Conch/releases/tag/v1.0.0
