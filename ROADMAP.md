# Conch roadmap

Product thesis lives in `CLAUDE.md` §11 — **mobile-first, AI-native IDE.
Phone is the tactile interface, the user's own VPS does the work, the
agent on that VPS does the thinking. No hosted backend.**

Score every candidate on two axes:

- **U** = solo dev needs it for daily personal use.
- **C** = widens the category vs Termius / Termux / Replit Mobile.

`P0` = both. `P1` = U only. `P2` = C only with clear ROI. Anything that
scores neither doesn't belong in this file.

---

## Now (in flight)

| Pri | Item | Notes |
|---|---|---|
| P0 | **Play Store launch** | Closed test in flight; store listing + Data Safety form ready in `docs/play-store/`. Privacy policy live. |
| P0 | **Agent ↔ phone bridge (own ADB + log capture)** | The category multiplier — turns the app from "AI you talk to" into "AI that can also OBSERVE." Server CLI `conch-bridge logs / screenshot / shell` writes requests through the existing pool SSH; the phone polls the inbox, captures at shell level over its own ADB connection (or own-uid as fallback), writes back. Spec in CLAUDE.md §11.5. |
| P0 | **Auto-install APK from chat** | FileProvider + `Intent.ACTION_VIEW` for `application/vnd.android.package-archive`. Closes the dogfooding loop: ship a release from a train without leaving the chat. |
| P0 | **`debug` `applicationIdSuffix = ".debug"`** | Safety-net so a debug build can sit alongside release for self-update testing. Tiny. |

## Next (1–2 releases out)

| Pri | Item | Notes |
|---|---|---|
| P0 | **Screen capture → chat** | One-tap MediaProjection grab attached to the current chat as a PNG. Big "AI sees what I see" win — pairs with the bridge for closed-loop debug. |
| P0 | **Live preview loop** | Agent edits → rebuild → auto-install → auto-screenshot → image back in chat. AI sees the result of its own changes. Bridge + auto-install + screen capture composed. |
| P0 | **Phone-side code editor** | Compose-based editor for in-place edits when delegating to the agent is overkill. `/edit <path>` opens it; syntax highlight; saves over SSH. |
| P1 | **Per-agent model selection persistence** | Currently `selectedModel` is a single string in prefs and leaks across agents. Make it `Map<Agent, String>`. |
| P1 | **Codex preview parser fix** | Filter session previews by `payload.type == "message"` AND `role == "user"`; right now shell function-call output leaks into the previews. |

## Later (post-1.x)

| Pri | Item | Notes |
|---|---|---|
| P1 | **Voice input** | Microphone → audio stream → Whisper running on the user's server → text into the chat input. No cloud transcription. |
| P1 | **Whole-device logcat** | Other apps' logs without root, at shell level over our own ADB. |
| P2 | **Clipboard bridge** | Phone clipboard ↔ `$CLIPBOARD` in agent commands. |
| P2 | **PR review in chat** | GitHub API → diff view → accept/reject from phone. |
| P2 | **Logcat tier 3 (root)** | Kernel / dmesg for users who already have root. Detect via `su -c id`. |

---

## Done (1.0.x and prior)

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
- Anonymous opt-in telemetry; GDPR data erasure; Terms of Service (1.0.2)
- ~~Sentry crash reporting with opt-out (1.0.1)~~ — removed entirely in 0.4.1
- Multi-server, multi-agent (Claude / Codex / Gemini) chat over SSH (1.0.0)
- FIDO2 / CTAP2 hardware-key auth (USB + NFC) with deferred-tap UX (1.0.0)
- Pool-based per-server SSH connection sharing (1.0.0)
- Background prefetch of session JSONLs (1.0.0)
- Memory editor, subagents browser, slash-command autocomplete (1.0.0)
- Signed AAB build via GitHub Actions; auto-incremented `versionCode` (1.0.0)

## Not building

These come up; the answer stays **no** until the thesis changes:

- **Local terminal emulator.** Termux owns that — we don't run code on
  the phone, we drive a real machine.
- **WebView IDE.** Replit owns that — we're the native, BYO-server
  antithesis.
- **Hosted AI proxy or our own inference.** Breaks the privacy and
  economics moat.
- **Cloud sync / backup of user state.** Same.
- **Multi-user collaboration.** Persona is one developer.
- **Subscriptions or ads.** One-time paid app on Play; revisit only if
  the category proves out.
- **A Vulkan GPU backend for the phone's engine.** Proposed as the fix for
  phones with no vendor OpenCL stack; built and measured on 2026-09-08 instead
  of argued about. On Adreno 830 it loses to the CPU on both axes (83 vs
  286 t/s prefill, 51.5 vs 62.4 generation) and SIGSEGVs inside Qualcomm's
  driver on a 512-token prompt (upstream #8743, open since 2024), while
  costing +37 MB — four times the app. The premise was wrong too: a CL-less
  phone is not crawling, its CPU backend is the fastest thing it has. Numbers,
  crash trace and the three conditions for a re-look:
  `docs/local-engine-benchmarks.md`. The GPU-coverage question was pointed at
  an NPU path (Hexagon / QNN / LiteRT) instead — see the next entry, which
  measured that too.
- **An NPU backend (Hexagon / QNN / LiteRT).** The one item that promised ×10
  and cold; measured on 2026-09-08 and the answer is no, for a deeper reason
  than Vulkan's. **The NPU does not run GGUF** — every shipping NPU LLM path
  runs a graph compiled ahead of time on a PC into a context binary pinned to
  one SoC generation, one context length and one batch shape (this phone's own
  artefacts are named `…ar128_ar32_cl4096_1_of_2.serialized.bin`). Supporting
  it means shipping a per-model per-SoC catalogue, i.e. a different product
  than "any GGUF off Hugging Face". And on the phone we have,
  `/dev/fastrpc-cdsp` is `0664 system:system`, so an app cannot open the DSP
  at all (one octal digit of OEM hardening — Qualcomm's reference ships it
  0666). Evidence, the OEM runtime that looks like a door and is not, and the
  three conditions for a re-look: `docs/npu-on-android.md`; re-measure any
  phone with `tools/probe-npu-android.sh`.
