package ai.eight24family.conch

import android.app.Application
import ai.eight24family.conch.data.prefs.isCrashReportingEnabled
import ai.eight24family.conch.di.ServiceLocator
import ai.eight24family.conch.util.SilentlyTry
import io.sentry.android.core.SentryAndroid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class SshAiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        installSshjBrokenTransportSwallow()
        ServiceLocator.init(this)
        // Warm the usage/limit cache from disk early so the bar shows the
        // last-known limit the INSTANT a chat opens — before the live fetch
        // (and before the user even taps-to-connect on SK servers).
        ai.eight24family.conch.agent.UsageProbe.preload()
        initSentryIfEnabled()
        // Battery temp + CPU usage sampled every 5s to logcat tag
        // `SshAi-Perf`. DEBUG-ONLY (BUG-2): nothing in the app collects
        // PerfMonitor.snapshot, so in release it was a forever-loop logging
        // INFO every 5s + a /proc/stat CPU metric that SELinux denies on
        // modern Android — i.e. dead numbers + breadcrumb spam + battery cost
        // for zero benefit. The on-device heat-tracking it was built for only
        // matters while we're actively profiling a debug build.
        if (BuildConfig.DEBUG) {
            ai.eight24family.conch.diagnostics.PerfMonitor.start(this)
        }
        // One-shot cleanup of the per-chat model keys polluted by a
        // broken backfill in an earlier 2026-05-22 build. Runs in a
        // background coroutine; gated by a marker pref so it's a
        // no-op after the first install of this version.
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            SilentlyTry.fired("SshAi-App", "run chat model keys cleanup") { ServiceLocator.preferences.runChatModelKeysCleanupIfNeeded() }
        }
        // Launch auto-connect is handled ENTIRELY by connectAllPossibleSilently()
        // below. It connects every server reachable without a tap — password /
        // plain-key via the stored secret, seamless FIDO via the device key —
        // which is a SUPERSET of last session's held set, so a separate "restore
        // held servers" pass is redundant. (It used to live here and dialed the
        // SAME servers concurrently with the call below; the two raced on the
        // per-server lock — the ~2.4s "Long monitor contention" at cold start that
        // left the app looking not-yet-connected. Removed: one guarded path.)
        // Connect EVERY server we can reach without a tap — password/plain-key
        // servers via their stored secret, and seamless FIDO servers via their
        // device key — even ones the user disconnected. App open + access exists →
        // just connect, no manual navigation anywhere. Idempotent — skips live.
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            SilentlyTry.fired("SshAi-App", "silent auto-connect all reachable servers") {
                ServiceLocator.sshConnectionPool.connectAllPossibleSilently()
            }
        }
    }

    /**
     * sshj runs its transport reader on background threads it manages
     * itself. When the user taps Stop and we close the SSH session
     * mid-IO, the reader's blocking `read()` returns -1 and sshj
     * throws `TransportException("Broken transport; encountered EOF")`
     * straight up the thread. Default Android handler treats that as
     * a fatal uncaught exception and kills the process — a benign
     * race becomes "stop button = app crash".
     *
     * We install a chained UncaughtExceptionHandler that swallows
     * exactly this one case and forwards everything else to whatever
     * was there before (Sentry, system default).
     */
    private fun installSshjBrokenTransportSwallow() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // sshj runs its transport Reader/Encoder on background threads it
            // manages itself. ANY sshj error there (broken transport, a
            // CHANNEL_OPEN_FAILURE = "Opening `session` channel failed", a
            // server-side MaxSessions refusal, etc.) is rethrown straight up
            // that thread; the default handler treats it as fatal and kills the
            // whole process. But our foreground code already handles connection
            // failures at every call site (try/catch + pool liveness checks) —
            // a background-thread sshj exception must NEVER crash the app. So we
            // swallow the whole `SSHException` family (covers TransportException
            // AND ConnectionException) and log it; the next operation re-detects
            // the dead/limited transport via the pool.
            val benign = throwable is net.schmizz.sshj.common.SSHException ||
                throwable.cause is net.schmizz.sshj.common.SSHException
            if (benign) {
                android.util.Log.w(
                    "SshAi",
                    "swallowed sshj ${throwable.javaClass.simpleName} on ${thread.name}: ${throwable.message}",
                )
                return@setDefaultUncaughtExceptionHandler
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Initialise Sentry crash reporting. Skipped when:
     *  - the build has no DSN (debug builds without `-PsentryDsn=...`)
     *  - the user opted out in Settings → Privacy
     *
     * Read order matters: we use a SharedPreferences-backed flag rather
     * than DataStore because Application.onCreate must NOT block on a
     * coroutine. The opt-out toggle in the Settings UI writes both
     * stores; the SharedPreferences copy is what we read here on next
     * launch. Trade-off: opting out takes effect on the NEXT launch, not
     * the current one — documented in the privacy policy.
     */
    private fun initSentryIfEnabled() {
        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isBlank()) return  // unsigned local debug build, etc.
        if (!isCrashReportingEnabled(this)) return  // user opted out
        SentryAndroid.init(this) { options ->
            options.dsn = dsn
            options.environment = if (BuildConfig.DEBUG) "debug" else "release"
            options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}"
            // Performance traces (Sentry's separate quota from error events).
            // Debug: 1.0 so every transaction fires while we're testing.
            // Release: 0.2 — 20% sample. Keeps us well inside free-tier
            // 10k transactions/month even with heavy users.
            options.tracesSampleRate = if (BuildConfig.DEBUG) 1.0 else 0.2
            options.isDebug = BuildConfig.DEBUG
            options.maxBreadcrumbs = 100
            // Don't send IP, headers, or anything PII-shaped by default.
            options.isSendDefaultPii = false
            // Strip the entire user object before sending. Sentry server
            // would otherwise resolve geo (city/country) from the IP at
            // ingestion time and store that, even with `scrubIPAddresses`
            // enabled at the project level. Setting `event.user = null`
            // here means the SDK never gives Sentry anything user-shaped
            // to derive geo from in the first place.
            // Strip the entire user object before sending. Sentry server
            // would otherwise resolve geo from the source IP at ingestion
            // time and store country/city even with `scrubIPAddresses`
            // enabled at the project level. Setting `event.user = null`
            // here gives the SDK nothing user-shaped to send. The
            // server-side relayPiiConfig also removes user.ip_address /
            // user.id as a belt-and-braces. Country/city geo is enriched
            // post-scrubbing by Sentry and we accept that — see the
            // privacy policy for disclosure.
            options.beforeSend = io.sentry.SentryOptions.BeforeSendCallback { event, _ ->
                event.user = null
                event
            }
        }
    }
}
