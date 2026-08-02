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
 * Stopping is the CLI's own path, not ours: an interrupt cancels every pending
 * wakeup (`onInterrupt → cancelled N pending loop wakeup(s) on user abort` in
 * the binary), which is the same control our Stop button already sends.
 */
object LoopWatch {

    /** The CLI's tool name. The model calls it; we only read it. */
    const val TOOL = "ScheduleWakeup"

    /** The CLI's own clamp — a delay outside this is not what will happen. */
    const val MIN_DELAY_S = 60
    const val MAX_DELAY_S = 3600

    /** A loop that will wake itself up again. */
    data class Armed(
        val delaySeconds: Int,
        /** The model's one-line why, shown under the countdown. */
        val reason: String?,
        /** Wall clock the tick is due, for the countdown. */
        val dueAtMs: Long,
    )

    private val DELAY = Regex("\"delaySeconds\"\\s*:\\s*(-?\\d+)")
    private val STOP = Regex("\"stop\"\\s*:\\s*true")
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
}
