package ai.eight24family.conch.agent.spec

import java.security.MessageDigest

/**
 * Content-addressed message-ID helper for JSONL parsers.
 *
 * Why this exists (Durov critique #3): every JSONL parser used to mint a
 * fresh `UUID.randomUUID()` for messages whose source didn't carry a
 * stable id (Codex/Gemini text events, Claude synthetic-user blocks,
 * etc.). Re-parsing the same JSONL line then produced **different**
 * `AgentMessage.id`s on every run, so the search indexer (which walks
 * the file from disk in the background) and the live chat (which
 * tailed stdout) diverged on IDs for byte-identical lines — and we had
 * to paper over the gap with a fragile ordinal-anchor matcher.
 *
 * Hash the raw JSONL line (plus a per-call-site salt so two parsers
 * producing different `AgentMessage` subtypes from the same line don't
 * collide) and use the first 80 bits as a stable id. SHA-1 is fine
 * here — we need collision resistance for non-adversarial inputs, not
 * cryptographic strength. 80 bits gives ~10^12 messages before a
 * birthday collision becomes plausible; we expect ≤10^6 messages per
 * session, so headroom is enormous.
 *
 * Output: 20 lowercase hex chars (10 bytes × 2).
 *
 * @param input The raw JSONL line (or any other content that uniquely
 *              identifies the message within its session).
 * @param salt  Disambiguator for multiple emissions from one line —
 *              e.g. `"a"` for assistant, `"u"` for user, `"sys"` for
 *              system, `"u_$blockIdx"` for the Nth content block of a
 *              multi-block Claude user turn.
 */
internal fun stableId(input: String, salt: String = ""): String {
    val bytes = MessageDigest.getInstance("SHA-1")
        .digest((salt + input).toByteArray(Charsets.UTF_8))
    return buildString(20) { for (i in 0 until 10) append("%02x".format(bytes[i])) }
}
