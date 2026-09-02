# support

Conch has no telemetry and no crash reporting — that is deliberate, and it means
**nothing reaches us unless you send it.** Here is where to send what.

---

### Something is broken, or the app crashed

[Open a bug report.](https://github.com/nikitaeight24family/Conch/issues/new?template=1_bug_report.yml)
The form asks for the Conch version, the phone, and the agent CLI on the server,
because those three answer most questions before anyone has to ask.

### An agent CLI updated and Conch stopped understanding it

[Use the agent-compatibility form.](https://github.com/nikitaeight24family/Conch/issues/new?template=2_agent_compatibility.yml)
Claude Code, Codex, Gemini, Grok and Copilot ship fast; a renamed flag or a new
session-file layout breaks the app without anything changing here. These are
usually fixed within the week.

### An idea

[Feature request.](https://github.com/nikitaeight24family/Conch/issues/new?template=3_feature_request.yml)
[ROADMAP.md](../ROADMAP.md) shows what is already queued and what is
deliberately out of scope.

### A security vulnerability

**Never in a public issue.** Use
[private reporting](https://github.com/nikitaeight24family/Conch/security/advisories/new)
or email `nikita@eight24family.ai` with `SECURITY` in the subject.
[SECURITY.md](SECURITY.md) has the scope and the response times.

### A question, or you cannot get connected

Email **[nikita@eight24family.ai](mailto:nikita@eight24family.ai)**.

Setup trouble is usually one of three things, and these are worth checking
first:

- **The server needs to be reachable by SSH from the phone's network.** That is
  the entire prerequisite. Conch installs Node and the agent CLI itself, and
  runs the provider sign-in from the phone — but a bare VPS needs root or sudo
  for that first install.
- **The agent CLI must be signed in on that server.** If it will not answer in
  its own terminal over SSH, it will not answer in Conch either.
- **Host key changed?** Conch pins on first connect and will refuse a changed
  key until you clear it in the server's settings — which is the feature working,
  not a bug, unless you know why it changed.

### A commercial licence, or a security questionnaire

Conch is free for personal, hobby, research, educational, charitable and
government use. Company use is licensed separately by Eight 24 Family LLC —
**[contact@eight24family.ai](mailto:contact@eight24family.ai)**, and the
[for companies](https://github.com/nikitaeight24family/Conch#for-companies)
section of the README covers what a security review will find.

---

**Response times:** one maintainer, alongside a day job. Issues are usually
answered within a few days and fixes ship in the next release. Security reports
get a first reply within 72 hours. If a thread looks forgotten for a couple of
weeks, a nudge is fine.
