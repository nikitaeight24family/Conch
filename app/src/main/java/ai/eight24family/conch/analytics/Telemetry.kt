package ai.eight24family.conch.analytics

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.data.prefs.AgentApprovalMode
import ai.eight24family.conch.ssh.FailureKind
import io.sentry.Breadcrumb
import io.sentry.ITransaction
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.TransactionOptions

/**
 * Anonymous, opt-out feature-usage telemetry on top of Sentry.
 *
 * Tier system to keep us inside Sentry free-tier quotas:
 *
 *  • **Breadcrumbs** — tier-A noise. Enrich crash context only; don't
 *    count toward the 5k event quota. Used for high-frequency flows
 *    (chat-session-started, attachment-uploaded).
 *  • **Info messages** — tier-B signal. Standalone events visible in
 *    the Issues tab. Burn quota at 1:1, so reserved for rarer-but-
 *    meaningful actions (subagent CRUD, approval-mode changes,
 *    connection failures).
 *  • **Transactions** — tier-C timing. Counted against the separate
 *    Performance quota, sampled by [tracesSampleRate] in Sentry init.
 *    Used for "how long does X take in the wild" — SSH handshake,
 *    chat first paint, agent bootstrap.
 *
 * All methods short-circuit to no-op when Sentry isn't initialised
 * (debug build without `-PsentryDsn=...`, or user opt-out flipped the
 * Settings → Privacy toggle to off).
 */
object Telemetry {

    // ──────────────── Tier A: breadcrumbs ────────────────

    /** A new chat session was opened or resumed. */
    fun chatSessionStarted(agent: Agent, isResume: Boolean) {
        if (!sentryAlive()) return
        Sentry.addBreadcrumb(Breadcrumb().apply {
            category = "feature"
            message = "chat_session_started"
            level = SentryLevel.INFO
            setData("agent", agent.cliCommand)
            setData("resume", isResume)
        })
    }

    /** User attached or sent something — file/photo/diff/init. */
    fun attachmentUploaded(kind: AttachmentKind) {
        if (!sentryAlive()) return
        Sentry.addBreadcrumb(Breadcrumb().apply {
            category = "feature"
            message = "attachment_uploaded"
            level = SentryLevel.INFO
            setData("kind", kind.label)
        })
    }

    enum class AttachmentKind(val label: String) {
        FILE("file"),
        PHOTO("photo"),
        GIT_DIFF("diff"),
        INIT_PROMPT("init"),
    }

    // ──────────────── Tier B: info-level captures ────────────────

    fun subagentCreated() = capture("subagent_created")
    fun subagentEdited() = capture("subagent_edited")
    fun subagentDeleted() = capture("subagent_deleted")

    fun approvalModeChanged(mode: AgentApprovalMode) {
        if (!sentryAlive()) return
        Sentry.captureMessage(
            "telemetry.approval_mode_changed",
            SentryLevel.INFO,
        ).also {
            // Tags applied via the running scope; configureScope ensures
            // they go on this single capture without persisting to others.
            Sentry.configureScope { scope -> scope.setTag("mode", mode.name.lowercase()) }
        }
    }

    fun connectionFailed(kind: FailureKind, agent: Agent) {
        if (!sentryAlive()) return
        Sentry.captureMessage(
            "telemetry.connection_failed",
            SentryLevel.INFO,
        )
        Sentry.configureScope { scope ->
            scope.setTag("failure_kind", kind.name.lowercase())
            scope.setTag("agent", agent.cliCommand)
        }
    }

    // ──────────────── Tier C: timed transactions ────────────────

    /**
     * Start a transaction measuring the SSH handshake. Caller must finish()
     * it on success or finishWithThrowable() on failure. Returns null when
     * Sentry isn't alive.
     */
    fun startSshHandshake(agent: Agent): ITransaction? {
        if (!sentryAlive()) return null
        val tx = Sentry.startTransaction(
            "ssh_handshake",
            "ssh",
            TransactionOptions().apply {
                isBindToScope = true
            },
        )
        tx.setTag("agent", agent.cliCommand)
        return tx
    }

    /** Time to first message visible after opening a chat (cache or wire). */
    fun startChatFirstPaint(agent: Agent, fromCache: Boolean): ITransaction? {
        if (!sentryAlive()) return null
        val tx = Sentry.startTransaction(
            "chat_first_paint",
            "ui.render",
            TransactionOptions().apply { isBindToScope = false },
        )
        tx.setTag("agent", agent.cliCommand)
        tx.setTag("from_cache", fromCache.toString())
        return tx
    }

    /** Bootstrapping → Running transition timing for a fresh AgentSession. */
    fun startAgentBootstrap(agent: Agent): ITransaction? {
        if (!sentryAlive()) return null
        val tx = Sentry.startTransaction(
            "agent_bootstrap",
            "agent.lifecycle",
            TransactionOptions().apply { isBindToScope = false },
        )
        tx.setTag("agent", agent.cliCommand)
        return tx
    }

    // ──────────────── internals ────────────────

    private fun capture(name: String) {
        if (!sentryAlive()) return
        Sentry.captureMessage("telemetry.$name", SentryLevel.INFO)
    }

    /** Cheap check: is Sentry initialised in the current process? When
     *  `BuildConfig.SENTRY_DSN` was blank or the user opted out, init is
     *  skipped and `Sentry.isEnabled()` returns false — every call here
     *  no-ops. */
    private fun sentryAlive(): Boolean = Sentry.isEnabled()
}
