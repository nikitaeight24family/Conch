package ai.eight24family.conch.util

import android.os.Trace

/**
 * Thin wrapper around `android.os.Trace` for the six hottest code
 * paths (Durov critique #5).
 *
 * Without instrumentation, every claim about latency is an oral
 * tradition — "should be fast", "feels instant", "<50 ms maybe".
 * With sections, the same paths show up in `adb shell perfetto` /
 * `dumpsys gfxinfo` / Android Studio Profiler as named timeline
 * blocks; you can prove behaviour rather than asserting it.
 *
 * **Cost.** Each [section] call is one JNI hop into the ATrace
 * native side, which fast-paths to a no-op when no tracer is
 * attached (see `system/core/libcutils/trace-dev.cpp` — the
 * `atrace_is_tracing_enabled` check is a relaxed atomic read).
 * On the order of tens of nanoseconds when off, microseconds when
 * a tracer is recording. Safe to leave on in release builds.
 *
 * **Section names.** ATrace caps section names at 127 bytes. Keep
 * them ASCII, prefer `SshAi.<Subsystem>.<Op>` for grep-ability in
 * Perfetto JSON exports. The prefix makes it cheap to filter
 * everything Conch-related against the kernel/framework noise.
 *
 * **Pairing.** `Trace.beginSection` MUST be matched by
 * `Trace.endSection` on the same thread (it's a thread-local
 * stack). [section] uses `try / finally` so an exception thrown
 * inside the block still pops. Don't call `Trace.beginSection`
 * directly — go through this helper or you risk a stack-imbalance
 * that takes hours to diagnose in Perfetto traces.
 */
object Tracing {

    /** Inline so the lambda has no allocation cost when traces are
     *  off. The compiler folds the try/finally into the caller.
     *
     *  `runCatching` around the begin/end calls so plain-JVM unit
     *  tests (which don't have Android's native ATrace lib loaded)
     *  don't blow up the moment a traced function runs. On real
     *  devices the calls succeed and behave normally; on Robolectric
     *  / `testReleaseUnitTest` they silently no-op. */
    inline fun <R> section(name: String, block: () -> R): R {
        runCatching { Trace.beginSection(name) }
        return try {
            block()
        } finally {
            runCatching { Trace.endSection() }
        }
    }

    /** Standard section names. Keep them here so a) names stay
     *  consistent across modules and b) renaming one updates every
     *  caller via the IDE. */
    object Names {
        const val PARSER_FAST_PATH = "SshAi.Parser.parseFast"
        const val PARSER_SLOW_PATH = "SshAi.Parser.parseTree"
        const val HISTORY_CACHE_LOAD = "SshAi.HistoryCache.load"
        const val HISTORY_CACHE_MERGE = "SshAi.HistoryCache.mergeServer"
        const val FTS_SEARCH = "SshAi.Search.fts"
        const val FTS_EXPAND_HITS = "SshAi.Search.expandRowToHits"
        const val FLUSH_STREAMING = "SshAi.Session.flushStreamingBuffer"
        const val APPLY_TO_HISTORY = "SshAi.Session.applyToHistory"
        const val INDEX_SESSION = "SshAi.Indexer.indexSession"
        const val RECONCILE_SCAN = "SshAi.Indexer.reconcileScan"
    }
}
