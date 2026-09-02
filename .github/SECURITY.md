# security policy

Conch holds SSH private keys, provider tokens and a live shell on machines
people care about. Security reports are the most valuable thing anyone can send,
and they are read the day they arrive.

---

## reporting a vulnerability

**Do not open a public issue.**

Two private channels, both fine:

- **GitHub → [Security tab → *Report a vulnerability*](https://github.com/nikitaeight24family/Conch/security/advisories/new)** — preferred. It gives us a private thread, a draft advisory and a CVE if one is warranted.
- **Email [nikita@eight24family.ai](mailto:nikita@eight24family.ai)** with `SECURITY` in the subject.

### what to put in it

The more of this you have, the faster it closes:

- What an attacker can do, and what they need first (physical access? the same
  Wi-Fi? a malicious server? a hostile app on the phone?)
- Steps to reproduce, or a proof of concept
- Conch version (Settings → About) and how it was installed — Play or the
  GitHub APK
- Android version, and the device if it matters
- Affected area: SSH transport, host-key pinning, secrets at rest, FIDO2/SK
  auth, the phone bridge, the local-model runtime, port forwarding, the proxy,
  file handling, or the app's own UI surfaces
- Whether you intend to publish, and when

Please **do not** include real keys, passwords or tokens in the report. A
description of where the leak happens is enough; we will reproduce it with our
own.

---

## response

| | |
|---|---|
| First reply | within **72 hours** |
| Triage and severity assessment | within **7 days** |
| Fix for a critical issue | next release, typically **days** |
| Fix for low and moderate issues | folded into the next scheduled release |
| Credit | your name or handle in the advisory, the changelog and the release notes — say if you would rather not be named |

This is a solo-maintained free app. There is **no bug bounty and no payment**;
what there is, is a fast, honest response and public credit.

---

## supported versions

| Version | Supported |
|---|---|
| Latest release ([Play](https://play.google.com/store/apps/details?id=ai.eight24family.conch) or [Releases](https://github.com/nikitaeight24family/Conch/releases/latest)) | ✅ |
| Anything older | ❌ — fixes ship forward only |

There are no backported patches. If you are running an old build, updating is
the fix.

---

## what the attack surface actually is

Worth knowing before you spend time on it: **there is no server of ours.** No
backend, no relay, no account system, no user database, no telemetry endpoint.
Nothing to test on our side, because it does not exist.

Everything security-relevant is therefore either on the phone or on the wire:

**In scope**

- The Android app in this repository, and the signed APK on Releases
- The SSH transport: host-key pinning and TOFU handling, session and channel
  management, key parsing, agent-command construction
- Secrets at rest: SSH keys, passphrases, provider tokens, FIDO handles, and
  everything the Android Keystore is asked to protect
- FIDO2 / CTAP2 hardware-key auth over USB-HID and NFC, and the SK signing path
- The phone bridge — its loopback ADB shell, pairing, per-chat opt-in, audit log
  and kill-switches — including anything that lets a *server* exceed what the
  user granted it
- Local model execution and the model store, the bundled Linux runtime, port
  forwarding and the system proxy
- File handling: attachments, downloads, the built-in viewers, `FileProvider`
  paths, and anything reachable by another app on the device
- Any path where a credential is logged, cached in the clear, sent somewhere it
  should not go, or exposed to another process (`ps`, world-readable files,
  intents)

**Out of scope**

- The agent CLIs themselves — Claude Code, Codex, Gemini CLI, Grok, Copilot.
  Report those to their vendors. What *is* in scope is Conch mishandling their
  output.
- The security of a server you chose to connect to, or its configuration
- An agent doing damage on a machine where the user granted it AUTO or YOLO.
  That is the feature. A path where those approval modes are *bypassed* is very
  much in scope.
- Attacks requiring a rooted or already-compromised device, or a physical
  unlocked-device scenario, unless they cross a boundary Conch specifically
  claims to hold
- Social engineering, phishing, or anything aimed at the maintainer
- Missing hardening with no demonstrated exploit path, and raw scanner output
- Reports about `conch-labs.com` content, or the Play Store listing — those go
  to the same address, they just are not vulnerabilities in the app

---

## safe harbour

Test against **your own devices and your own servers**. Within that, research
here is welcome: we will not pursue or support legal action over a good-faith
report that stays inside your own infrastructure, avoids other people's data and
gives us a reasonable window before publication.

That protection ends where someone else's device, server or data begins.

---

## disclosure

Coordinated. Report privately, we fix it, and the advisory goes out once a
release carrying the fix is available — with credit to you. Ninety days is the
outside window; in practice it is much shorter, and if a fix is going to take
longer than that we will say so on the thread rather than go quiet.

---

For anything that is not a vulnerability — a bug, a crash, a question — use the
[issue templates](https://github.com/nikitaeight24family/Conch/issues/new/choose)
instead.
