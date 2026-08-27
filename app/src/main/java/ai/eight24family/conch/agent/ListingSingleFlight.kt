package ai.eight24family.conch.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.util.concurrent.ConcurrentHashMap

/**
 * Collapse concurrent identical listings into ONE pass.
 *
 * The session listing is the most expensive routine question the app asks a
 * server, and the cost is paid per pass, not per caller: the sessions screen,
 * the home reload, the indexer and the prefetcher can all decide to relist the
 * same (server, agent) within the same second.
 *
 * MEASURED on the user's phone (2026-08-27): a refresh landed while the first
 * pass was still streaming and the server showed two `bash -lc` pipelines of
 * the same script running at 18 and 13 minutes, splitting a link already down
 * to ~20 KB/s between them. The second pass did not make the list arrive
 * sooner — it halved the speed of the one that was already in flight.
 *
 * Followers RIDE the in-flight pass and get its result, so nobody waits longer
 * than they would have alone. A follower whose leader was cancelled (screen
 * left, chat closed) gets an empty list rather than the cancellation: it is a
 * cache-refresh path, and the next tick will ask again.
 */
internal object ListingSingleFlight {

    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<List<RemoteSession>>>()

    /** How many passes are in flight — for logs and tests, not for control flow. */
    val inFlightCount: Int get() = inFlight.size

    suspend fun run(
        key: String,
        block: suspend () -> List<RemoteSession>,
    ): List<RemoteSession> {
        val mine = CompletableDeferred<List<RemoteSession>>()
        val leader = inFlight.putIfAbsent(key, mine)
        if (leader != null) {
            android.util.Log.d("SshAi-Listing", "riding the in-flight listing for $key")
            return try {
                leader.await()
            } catch (c: CancellationException) {
                // Two very different things throw this here. Our OWN coroutine
                // being cancelled MUST propagate (structured concurrency). The
                // LEADER dying — its screen closed, its chat left — must NOT
                // take us with it: report "nothing this time" and let the next
                // tick relist.
                if (!currentCoroutineContext().isActive) throw c
                android.util.Log.d("SshAi-Listing", "listing leader for $key went away — empty this pass")
                emptyList()
            } catch (t: Throwable) {
                android.util.Log.w("SshAi-Listing", "in-flight listing for $key failed: ${t.message}")
                emptyList()
            }
        }
        return try {
            val result = block()
            mine.complete(result)
            result
        } catch (t: Throwable) {
            mine.completeExceptionally(t)
            throw t
        } finally {
            // Remove only OUR entry: a later pass may already own the key.
            inFlight.remove(key, mine)
        }
    }
}
