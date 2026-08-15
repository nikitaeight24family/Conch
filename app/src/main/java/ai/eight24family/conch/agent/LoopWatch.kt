package ai.eight24family.conch.agent

/**
 * A `/loop` armed by the CLI, read off the live stream.
 *
 * `/loop` runs a prompt again and again — either on a cron interval or, with no
 * interval, at delays the model picks itself. The dynamic form is the one that
 * needs surfacing: the model calls the CLI's own **ScheduleWakeup** tool with a
 * delay (clamped by the CLI to 60…3600s), the turn ends, and some minutes later
 * a whole turn arrives that the user never asked for. Verified end to end
 * against the CLI on the user's own box (2026-08-03): the wakeup fires INSIDE
 * the same live process — a second `system init` and a full turn, no input.
 *
 * Which is exactly why it has to be visible. An armed loop keeps spending
 * tokens on its own schedule; a chat that shows nothing between ticks is a
 * background cost with no off switch. [Armed] drives a chip with the countdown
 * and a stop.
 *
 * Stopping ends the PROCESS. The binary does cancel every pending wakeup on
 * abort — but only on the REPL's own path; the stream-json `interrupt` control
 * aborts the in-flight turn and the queued prompts and nothing else. 0.3.1
 * shipped the interrupt as "stop" and the button lied: pressed at 18:48, the
 * wakeup still fired at 19:18:00 and ran a whole turn. The wakeups are
 * in-process timers, so AgentSessionPersistentStream.cancelIdleLoop tears the
 * process down — verified the honest way, by waiting past a deadline that then
 * did not fire (2026-08-03).
 */
object LoopWatch {

    /** Self-paced loop: the model picks each delay itself. */
    const val TOOL = "ScheduleWakeup"

    /** INTERVAL loop. `/loop 30m …` takes a completely different route — the
     * skill calls `CronCreate` with a cron expression and `recurring: true`,
     * and ScheduleWakeup is never involved. Watching only the self-paced tool
     * meant an interval loop armed nothing on screen at all. */
    const val CRON_TOOL = "CronCreate"

    /** The model cancelling its own recurring job. */
    const val CRON_STOP_TOOL = "CronDelete"

    /** The CLI's own clamp — a delay outside this is not what will happen. */
    const val MIN_DELAY_S = 60
    const val MAX_DELAY_S = 3600

    /** A loop that will run again. */
    data class Armed(
        val delaySeconds: Int,
        /** The model's one-line why, shown under the countdown. */
        val reason: String?,
        /** Wall clock the tick is due, for the countdown. 0 for an interval
         *  loop — a cron job has a cadence, not a single next moment we can
         *  count down to honestly. */
        val dueAtMs: Long,
        /** Human cadence of an INTERVAL loop ("every 30 min"); null for a
         *  self-paced one. Which of the two this is decides what the panel can
         *  truthfully say, and how it can be stopped. */
        val cadence: String? = null,
    )

    private val DELAY = Regex("\"delaySeconds\"\\s*:\\s*(-?\\d+)")
    private val STOP = Regex("\"stop\"\\s*:\\s*true")
    private val CRON = Regex(""""cron"\s*:\s*"([^"]+)"""")
    private val REASON = Regex("\"reason\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    /**
     * Read one `ScheduleWakeup` call. Returns the new loop state: an [Armed]
     * when the loop continues, `null` when it ends (`stop: true`) — the same
     * two outcomes the tool itself has.
     *
     * `stop` is checked FIRST: the stopping call carries no other field, and
     * reading a delay out of a stop would leave a countdown running over a loop
     * that is already over.
     */
    fun read(input: String, nowMs: Long): Armed? {
        if (STOP.containsMatchIn(input)) return null
        val raw = DELAY.find(input)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val clamped = raw.coerceIn(MIN_DELAY_S, MAX_DELAY_S)
        val reason = REASON.find(input)?.groupValues?.get(1)
            ?.replace("\\\"", "\"")?.replace("\\n", " ")?.trim()?.takeIf { it.isNotBlank() }
        return Armed(delaySeconds = clamped, reason = reason, dueAtMs = nowMs + clamped * 1000L)
    }

    /**
     * Read a `CronCreate` call — the INTERVAL form of `/loop`. There is no
     * single "next run" to count down to, so the panel gets the cadence
     * instead, in the words the user asked for it in.
     */
    fun readCron(input: String, reason: String? = null): Armed? {
        val expr = CRON.find(input)?.groupValues?.get(1)?.trim() ?: return null
        val why = reason ?: REASON.find(input)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
        return Armed(delaySeconds = 0, reason = why, dueAtMs = 0L, cadence = cadenceOf(expr))
    }

    /**
     * A cron expression as the user would say it. Only the shapes the loop
     * skill actually emits are spelled out; anything else is shown verbatim
     * rather than mistranslated — a wrong cadence on screen is worse than a
     * raw one.
     */
    fun cadenceOf(expr: String): String {
        val f = expr.trim().split(Regex("""\s+"""))
        if (f.size < 5) return expr
        val (min, hour, dom) = Triple(f[0], f[1], f[2])
        val every = Regex("""^\*/(\d+)$""")
        val everyMin = every.find(min)?.groupValues?.get(1)?.toIntOrNull()
        val everyHour = every.find(hour)?.groupValues?.get(1)?.toIntOrNull()
        val everyDay = every.find(dom)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            everyMin != null && hour == "*" -> "every $everyMin min"
            everyHour != null && min == "0" -> if (everyHour == 1) "hourly" else "every $everyHour h"
            everyDay != null && min == "0" && hour == "0" ->
                if (everyDay == 1) "daily" else "every $everyDay days"
            min.toIntOrNull() != null && hour.toIntOrNull() != null && dom == "*" ->
                "daily at %02d:%02d".format(hour.toInt(), min.toInt())
            else -> expr
        }
    }
}
