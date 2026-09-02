# contributing to Conch

Thank you for being here. Conch is maintained by one person alongside a day
job, so this document is mostly about spending your time where it lands.

---

## first, the thing that surprises people

**This repository is the published source of the app, and development happens in
a private tree.** Every release is built from what you see here, and the source
here is complete — but the day-to-day history, the signing setup and the store
tooling live in a private repo.

What that means for you:

- **Issues are the front door.** They are read here, and this is where the app's
  bugs are tracked. Open them freely.
- **Pull requests are welcome and are genuinely merged** — they are replayed
  into the private tree and land in the next release commit, with you credited
  in [CHANGELOG.md](../CHANGELOG.md) and in the release notes. Your PR is then
  closed with a link to the commit that carries it. GitHub will say *closed*
  rather than *merged*; that is the mechanics, not a rejection.
- Because of that replay, **small, focused patches land quickly and large
  refactors land slowly or not at all.** Open an issue before starting anything
  big, and we will figure out whether it fits.

There is no telemetry, no crash reporting and no analytics in this app — that is
a deliberate design decision, and its honest cost is that **a bug you don't
report is a bug nobody knows about.** Reporting one is a real contribution.

---

## what helps most

Roughly in order of how much difference it makes:

1. **Bug reports with enough detail to reproduce.** Conch talks to five agent
   CLIs across arbitrary servers and phones; the failures worth fixing are
   almost always in the seams.
2. **Agent-CLI compatibility breakage.** Claude Code, Codex, Gemini CLI, Grok
   and Copilot ship often, and a changed session-file layout or a renamed flag
   breaks parsing. A report that names the CLI version is the fastest fix in
   the project — use the *agent compatibility* issue template.
3. **Device and OEM reports.** Foldables, tablets, DeX, aggressive
   battery-killers, odd NFC stacks. One line from a device nobody here owns is
   worth a lot.
4. **Small, surgical patches**: a crash fix, a wrong string, a layout that
   breaks in landscape, a parser that mishandles one shape of output.
5. **Feature ideas scored against the project's frame** — see
   [ROADMAP.md](../ROADMAP.md). The question is not "would this be nice", it is
   *"does this close a moment where you had to reach for a laptop?"*

---

## building it

```bash
git clone https://github.com/nikitaeight24family/Conch
cd Conch
./gradlew assembleDebug          # debug APK — no signing setup needed
./gradlew testDebugUnitTest      # the unit suite; keep it green
```

JDK 17 and the Android SDK. `compileSdk 37`, `targetSdk 36`, `minSdk 26`. The
debug build installs alongside a release build (`applicationIdSuffix ".debug"`),
so you can keep the Play version on the same phone.

Release builds expect your own `release.keystore` and `keystore.properties` at
the repo root. Both are gitignored and **must never be committed** — see below.

To exercise it properly you need what the app itself needs: a machine you can
SSH into, with one of the agent CLIs on it.

---

## house rules a patch has to respect

These are not style preferences. A patch that breaks one of them cannot be
taken, however good it is otherwise.

- **No telemetry, analytics, crash reporting or ad SDKs.** Not opt-out, not
  "anonymous", not behind a flag. They were removed outright in 0.4.1 and the
  absence is a promise made in the store listing and the privacy policy.
- **No backend, proxy or relay of ours in the data path.** Phone → the user's
  own SSH server, and nothing in between. This is the whole product.
- **No credential ever leaves the device, and none is ever logged.** SSH
  passwords, private keys, passphrases, FIDO handles and provider tokens are
  encrypted at rest via the Android Keystore. Do not print one, do not put one
  in a crash message, and do not pass one on a command line where `ps` can read
  it — stdin exists.
- **UI strings are English, and they live in `strings.xml`.** No hardcoded
  literals in composables, and no other language in the app's interface.
- **The suite stays green.** `./gradlew testDebugUnitTest` before you push. New
  behaviour that can be tested off-device gets a test.
- **New native dependencies must be 16 KB page-aligned.** Play rejects builds
  that are not. Check an `.aar` or `.aab` with `python tools/elfalign.py <file>`
  before pinning anything that ships an `.so`.
- **Never commit** `release.keystore`, `keystore.properties`, `local.properties`,
  APKs/AABs, or a real hostname, IP, username or path from your own
  infrastructure. Tests use RFC 5737 documentation addresses; keep it that way.
- **Anything that reads the user's device or room is opt-in and reversible.**
  The phone bridge's `audio` verb ships disabled on purpose. If you add a
  capability of that shape, it gets its own switch, its own audit trail, and a
  kill-switch in Settings → Security.

---

## sending a change

1. Open an issue first for anything beyond a small fix, so we can agree on the
   shape before you spend an evening on it.
2. One concern per pull request. A crash fix and a refactor in one branch is two
   PRs' worth of review and lands as neither.
3. Match the surrounding code — Kotlin, Compose, Material 3, coroutines and
   Flow, a Hilt-free ServiceLocator. Read the neighbours before inventing a
   pattern.
4. Write the commit message for someone reading it in a year: what changed and
   *why*, in plain sentences. The history here is prose, not `fix: stuff`.
5. Fill in the pull-request template. It is short and every line of it is a gate
   that has actually caught something.
6. UI change? Attach a screenshot, in both the dark and light theme if it
   touches colour.

---

## licensing of what you send

Conch is published under
[PolyForm Noncommercial 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0).

**By opening a pull request you agree that your contribution is licensed under
those same terms** — inbound equals outbound — and that Eight 24 Family LLC may
also distribute it under the commercial licence it issues to companies. You keep
the copyright in what you wrote. There is no CLA to sign.

If that arrangement doesn't work for you, an issue describing the fix is still
very welcome, and it will be credited.

---

## security issues do not go here

Do not open a public issue for a vulnerability. See
[SECURITY.md](SECURITY.md) — private reporting is on the Security tab, or
**nikita@eight24family.ai**.

---

## what to expect

One maintainer, human-speed. Issues are usually answered within a few days;
a fix ships in the next release rather than immediately. If something looks
ignored for a couple of weeks, a nudge on the thread is fine and not rude.

Everyone taking part is expected to follow the
[Code of Conduct](CODE_OF_CONDUCT.md).
