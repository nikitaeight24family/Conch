package ai.eight24family.conch.ssh

import ai.eight24family.conch.util.SilentlyTry
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Read a remote command's output with a REAL deadline and a byte ceiling.
 *
 * ⛔ WHY THIS EXISTS — THE TIMEOUT WAS ON THE WRONG SIDE OF THE READ.
 *
 * Every exec site in this app was written as:
 *
 * ```
 * proc.inputStream.copyTo(out)          // no bound at all
 * proc.join(15, TimeUnit.SECONDS)       // "the timeout"
 * ```
 *
 * `join` only starts counting once `copyTo` has already returned, so the read
 * itself was unbounded: as long as the server kept trickling bytes, the app
 * kept reading. MEASURED on the user's phone (2026-08-27): the Claude session
 * listing on one server produced 7.9 MB, the link had collapsed to cwnd 1-4
 * (~7-40 KB/s), and two listings raced each other — `ps` on the server showed
 * the two `bash -lc` pipelines alive at **18 and 13 minutes**, both blocked in
 * `pipe_wait` writing output nobody could consume fast enough. The spinner in
 * the app never stopped, and `join(15s)` was never reached. Another server on
 * the SAME host (6 sessions, 132 KB) finished instantly, which is why it read
 * as "one server is broken" instead of "the read has no deadline".
 *
 * So the deadline has to wrap the READ. And it cannot be `withTimeout`:
 * cancelling a coroutine does not interrupt a thread parked in
 * `InputStream.read()` on a live socket. The only thing that unblocks it is
 * closing the channel — which is also what makes the SERVER stop: the remote
 * command gets its stdout closed and dies, instead of being left to grind for
 * another quarter of an hour (those two pipelines were still running long
 * after the app had given up on them).
 *
 * [maxBytes] is the second half of the same lesson: a String-shaped read of a
 * pathological file is an OOM waiting to happen (a 134 MB rollout already
 * killed a naive read once — see NO-REDOWNLOAD-ON-BENIGN-SHRINK-1). Past the
 * cap the channel is closed and [Outcome.truncated] says so, rather than
 * silently returning a prefix that looks complete.
 *
 * No defaults on purpose: a listing, a status probe and a session-body fetch
 * have nothing in common, and a default would quietly become the wrong bound
 * at the next call site.
 */
object BoundedExec {

    private const val TAG = "Conch-BoundedExec"

    /** What actually happened, so callers can say so instead of guessing. */
    data class Outcome(
        val bytes: Long,
        /** The deadline fired — the channel was closed under the reader. */
        val timedOut: Boolean,
        /** Hit [maxBytes]; the output is a prefix. */
        val truncated: Boolean,
    ) {
        val ok: Boolean get() = !timedOut && !truncated
    }

    /**
     * One daemon thread for every deadline in the process. The watchdog only
     * ever closes a channel, so it never blocks long enough to need more, and
     * a scheduled task is far cheaper than a thread per exec.
     */
    private val watchdog: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "conch-exec-deadline").apply { isDaemon = true }
        }

    /**
     * Drain [cmd]'s stdout into [sink], for at most [deadlineMs] wall-clock and
     * [maxBytes] bytes. Blocking (not `suspend`) so it drops straight into the
     * existing call sites, all of which are already on an IO dispatcher.
     *
     * On deadline or cap the command's channel is closed, which both unblocks
     * this read and kills the remote process. The caller's own
     * `join`/`exitStatus`/`session.close()` handling stays valid — a closed
     * channel simply reports no exit status.
     */
    fun drain(
        cmd: Session.Command,
        sink: OutputStream,
        deadlineMs: Long,
        maxBytes: Long,
        bufferSize: Int = 64 * 1024,
    ): Outcome = drainStream(cmd, cmd.inputStream, sink, deadlineMs, maxBytes, bufferSize)

    /** [drain]'s body, over an explicit stream so stderr can use it too. */
    private fun drainStream(
        cmd: Session.Command,
        source: java.io.InputStream,
        sink: OutputStream,
        deadlineMs: Long,
        maxBytes: Long,
        bufferSize: Int = 64 * 1024,
    ): Outcome {
        val timedOut = AtomicBoolean(false)
        val closing = AtomicBoolean(false)
        fun closeChannel(why: String) {
            if (closing.compareAndSet(false, true)) {
                SilentlyTry.fired(TAG, "close channel ($why)") { cmd.close() }
            }
        }
        val task = watchdog.schedule({
            timedOut.set(true)
            closeChannel("deadline ${deadlineMs}ms")
        }, deadlineMs, TimeUnit.MILLISECONDS)
        var total = 0L
        var truncated = false
        try {
            val buf = ByteArray(bufferSize)
            val ins = source
            while (true) {
                val n = try {
                    ins.read(buf)
                } catch (t: Throwable) {
                    // Our own close is the expected way out of a blocked read —
                    // it must not surface as a failure. Anything else is real.
                    if (closing.get()) break else throw t
                }
                if (n < 0) break
                val room = maxBytes - total
                if (room > 0) {
                    val take = minOf(n.toLong(), room).toInt()
                    sink.write(buf, 0, take)
                    total += take
                }
                if (total >= maxBytes) {
                    truncated = true
                    android.util.Log.w(TAG, "output hit the $maxBytes B cap — closing channel")
                    closeChannel("byte cap")
                    break
                }
            }
        } finally {
            task.cancel(false)
        }
        if (timedOut.get()) {
            android.util.Log.w(
                TAG,
                "read past deadline ${deadlineMs}ms after $total B — channel closed, remote command killed",
            )
        }
        return Outcome(total, timedOut.get(), truncated)
    }

    /**
     * Drain stdout AND stderr, CONCURRENTLY, under one shared deadline.
     *
     * ⛔ SEQUENTIAL IS A DEADLOCK WAITING FOR A CHATTY COMMAND. Reading stdout
     * to EOF and only then stderr means a command that fills the stderr pipe
     * mid-run blocks writing it, never closes stdout, and the stdout read never
     * returns. The on-device shell path already learned this and carries
     * the same comment; the SSH side kept the sequential shape. Two threads,
     * one deadline, and either one hitting the deadline closes the channel and
     * releases both.
     *
     * [maxBytes] applies to each stream separately, which is what callers mean:
     * a 32 MB stdout budget should not be eaten by a stderr warning flood.
     */
    fun drainBoth(
        cmd: Session.Command,
        stdout: OutputStream,
        stderr: OutputStream,
        deadlineMs: Long,
        maxBytes: Long,
    ): Outcome {
        // stderr on a helper thread, stdout on the caller's — one extra thread
        // per exec, only for as long as the exec.
        var errOutcome: Outcome? = null
        val t = Thread({
            errOutcome = SilentlyTry.loggedOrElse(TAG, "drain stderr", null) {
                drainStream(cmd, cmd.errorStream, stderr, deadlineMs, maxBytes)
            }
        }, "conch-exec-stderr").apply { isDaemon = true; start() }
        val outOutcome = drain(cmd, stdout, deadlineMs, maxBytes)
        // The channel is closed by whichever side finished/expired, so this
        // join cannot outlive the deadline by more than a read's worth.
        SilentlyTry.fired(TAG, "join stderr drainer") { t.join(2_000) }
        val e = errOutcome
        return Outcome(
            bytes = outOutcome.bytes + (e?.bytes ?: 0L),
            timedOut = outOutcome.timedOut || (e?.timedOut == true),
            truncated = outOutcome.truncated || (e?.truncated == true),
        )
    }

    /**
     * Deadlines, named. Call sites pass one of these rather than a bare number
     * so the intent is reviewable and one place changes them all.
     */
    object Deadline {
        /** Session listings, status probes, `stat`/`ls`-shaped questions: work
         *  the user is WAITING on behind a spinner. Long enough for a slow link
         *  and a big home directory, short enough that a stuck one becomes a
         *  visible error while the user is still looking at the screen. */
        const val INTERACTIVE_MS = 45_000L

        /** Generic one-shot `execute()` — may carry a session body. */
        const val COMMAND_MS = 90_000L

        /** Bulk transfers streamed to disk (session bodies, downloads). */
        const val TRANSFER_MS = 10 * 60_000L
    }

    /** Byte ceilings, same reasoning as [Deadline]. */
    object Cap {
        /** A listing or probe answer. The Claude listing measures ~450 KB after
         *  the per-candidate cut; 16 MB is a ceiling, not a target. */
        const val INTERACTIVE = 16L * 1024 * 1024

        /** Command output collected into a String. Above this, a caller should
         *  be streaming to disk instead. */
        const val COMMAND = 32L * 1024 * 1024

        /** Streamed to disk — rollouts over 100 MB are real. */
        const val TRANSFER = 512L * 1024 * 1024
    }
}
