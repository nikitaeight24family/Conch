## what this changes

<!-- One paragraph in plain sentences: what it does and why. -->

Fixes #

## how it was tested

<!-- Device and Android version, the server and agent CLI you ran it against,
     and what you actually watched happen. "Tests pass" is not testing. -->

- [ ] `./gradlew testDebugUnitTest` passes locally
- [ ] Ran on a real device against a real server

## screenshots

<!-- UI change? Before and after. Both themes if it touches colour. Delete this
     section otherwise. -->

## the gates

- [ ] **No telemetry, analytics, crash reporting or ad SDK** is added — not even behind a flag
- [ ] **No backend, proxy or relay** of ours enters the data path
- [ ] **No credential** is logged, printed, cached in the clear, or passed on a command line where `ps` can read it
- [ ] New UI strings are **English, in `strings.xml`**, with no hardcoded literals
- [ ] No `release.keystore`, `keystore.properties`, `local.properties`, APK/AAB, or real hostname, IP, username or path from my own infrastructure is committed
- [ ] Any new native dependency is **16 KB page-aligned** (`python tools/elfalign.py <aar|aab>`) — or this PR adds none
- [ ] Anything that reads the device or its surroundings is **opt-in, auditable and reversible**
- [ ] I agree my contribution is licensed under **PolyForm Noncommercial 1.0.0**, the same terms as the project ([CONTRIBUTING](https://github.com/nikitaeight24family/Conch/blob/main/.github/CONTRIBUTING.md#licensing-of-what-you-send))

## anything else

<!-- Trade-offs you made, things you were unsure about, follow-ups you are
     deliberately leaving out. -->
