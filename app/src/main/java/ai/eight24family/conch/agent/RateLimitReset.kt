package ai.eight24family.conch.agent

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Extracts the **reset moment** from a CLI rate-limit message so the usage bar
 * can show the authoritative "resets 8:30pm" the CLI itself printed — instead
 * of a stale server-probe value that decays into a lying "resets now".
 *
 * The CLI is the source of truth here. When an inference-only account
 * (`claude setup-token`, scope `user:inference`) is rate-limited, our
 * server-side [UsageProbe] cannot read the reset — the usage endpoint 403s on
 * that scope — so the bar would freeze on whatever it last had. But the CLI's
 * own turn output carries the truth verbatim:
 *
 *   "You've hit your session limit · resets 8:30pm (America/Los_Angeles)"
 *   "Claude usage limit reached. Your limit will reset at 3pm (America/New_York)"
 *
 * Pure + fully injectable ([nowMs], [deviceZone]) so it unit-tests on the JVM.
 */
object RateLimitReset {

    // Anchored on "reset"/"resets"/"reset at" so it can't match an "at 5"
    // buried in an ordinary reply. Captures: hour, optional :minute, optional
    // am/pm (with or without dots), optional "(Zone/Name)".
    private val ABSOLUTE = Regex(
        """reset(?:s|ting)?(?:\s+at)?\s+(\d{1,2})(?::(\d{2}))?\s*([ap]\.?m\.?)?\s*(?:\(([^)]{1,40})\))?""",
        RegexOption.IGNORE_CASE,
    )

    // "resets in 3h 20m" / "resets in 45m" / "resets in 2h".
    private val RELATIVE = Regex(
        """reset(?:s|ting)?\s+in\s+(?:(\d+)\s*h)?\s*(?:(\d+)\s*m)?""",
        RegexOption.IGNORE_CASE,
    )

    /** Human "resets …" clause verbatim from [text], for a message the user
     *  reads (e.g. the humanized fallback error) — null if none present. */
    fun resetPhrase(text: String?): String? {
        if (text.isNullOrBlank() || !text.contains("reset", ignoreCase = true)) return null
        RELATIVE.find(text)?.let { m ->
            if (m.groupValues[1].isNotEmpty() || m.groupValues[2].isNotEmpty()) {
                return m.value.trim().replace(Regex("\\s+"), " ")
            }
        }
        val m = ABSOLUTE.find(text) ?: return null
        // Reject a bare "reset 5" with no minute / am-pm / zone — too weak to
        // be a real clock time (see [parse]).
        if (m.groupValues[2].isEmpty() && m.groupValues[3].isEmpty() && m.groupValues[4].isEmpty()) return null
        return m.value.trim().replace(Regex("\\s+"), " ")
    }

    /** Parse the reset epoch (millis) from [text]; null when no reset is
     *  expressible (no "reset" token, weekly/date form we don't parse, …). */
    fun parse(text: String?, nowMs: Long, deviceZone: ZoneId): Long? {
        if (text.isNullOrBlank() || !text.contains("reset", ignoreCase = true)) return null

        // Relative first — "resets in 3h" is unambiguous, no zone needed.
        RELATIVE.find(text)?.let { m ->
            val h = m.groupValues[1].toLongOrNull() ?: 0L
            val mi = m.groupValues[2].toLongOrNull() ?: 0L
            if (h > 0 || mi > 0) return nowMs + (h * 3600 + mi * 60) * 1000
        }

        val m = ABSOLUTE.find(text) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: 0
        val ampm = m.groupValues[3].lowercase().replace(".", "")
        val zoneStr = m.groupValues[4].trim()

        // Require a real clock signal: minutes, am/pm, or an explicit zone. A
        // lone "reset 5" is too weak and would false-positive on prose.
        if (m.groupValues[2].isEmpty() && ampm.isEmpty() && zoneStr.isEmpty()) return null
        if (hour !in 0..23 || minute !in 0..59) return null
        when {
            ampm.startsWith("p") && hour < 12 -> hour += 12
            ampm.startsWith("a") && hour == 12 -> hour = 0
        }
        if (hour !in 0..23) return null

        val zone = zoneStr.takeIf { it.isNotEmpty() }
            ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: deviceZone

        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        var reset = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        // A limit that "resets 8:30pm" while it's already 9pm means TOMORROW's
        // 8:30pm — roll forward to the next occurrence so the reset is future.
        if (!reset.isAfter(now)) reset = reset.plusDays(1)
        return reset.toInstant().toEpochMilli()
    }
}
