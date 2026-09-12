package ai.eight24family.conch

import ai.eight24family.conch.agent.UsageProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two agents that gained real plan windows on 2026-09-12, and the decoys
 * next to each of them.
 *
 * Both used to report nothing at all: `UsageProbe` returned null for Gemini,
 * Copilot and six others, so eight of the ten agents showed no limits on any
 * timescale. Gemini's comment even said "no machine-readable quota" — true of
 * an older build, wrong of the shipped 0.57.0 bundle.
 *
 * Payload shapes are taken from the CLIs' own code: gemini-cli 0.57.0
 * (`Config.refreshUserQuota`, `CodeAssistServer.retrieveUserQuota`) and
 * copilot 1.0.80 (`account.getCurrentAuth` -> `copilotUser.quota_snapshots`).
 */
class GeminiCopilotLimitsTest {

    // ── Gemini ───────────────────────────────────────────────────────────

    private val GEMINI =
        """{"buckets":[""" +
            """{"modelId":"gemini-2.5-pro","remainingFraction":0.42,""" +
            """"remainingAmount":"420","resetTime":"2099-01-01T00:00:00Z"},""" +
            """{"modelId":"gemini-2.5-flash","remainingFraction":0.9,""" +
            """"resetTime":"2099-01-01T00:00:00Z"}]}"""

    @Test
    fun `gemini buckets become one window per model, counted down like the CLI`() {
        val w = UsageProbe.reportFromGemini(GEMINI)?.windows.orEmpty()
        assertEquals(2, w.size)
        assertEquals("gemini-2.5-pro", w[0].label)
        // remainingFraction 0.42 reads as 42% LEFT, the direction the provider
        // states it in — not 42% spent.
        assertEquals(42, w[0].percent)
        assertEquals(90, w[1].percent)
        assertTrue("provider reset must be carried", w[0].resetAtEpochMs != null)
    }

    /**
     * ⛔ THE CLI SKIPS THESE AND SO MUST WE. `refreshUserQuota` bails on any
     * bucket missing `modelId` or `remainingFraction`. Drawing such a bucket
     * would mean inventing the number that is absent.
     */
    @Test
    fun `a bucket with no fraction is skipped, not drawn as zero`() {
        val partial =
            """{"buckets":[{"modelId":"gemini-2.5-pro","resetTime":"2099-01-01T00:00:00Z"},""" +
                """{"remainingFraction":0.5,"resetTime":"2099-01-01T00:00:00Z"}]}"""
        assertNull("nothing drawable here", UsageProbe.reportFromGemini(partial))
    }

    /**
     * ⛔ A WALLET IS NOT A WINDOW. `availableCredits` / `getG1CreditBalance`
     * carry a balance with no denominator and no reset; a percentage bar built
     * from one is invented. Exactly what the fabricated Codex "Credits" row
     * was, and it was removed for the same reason.
     */
    @Test
    fun `a credit balance alone produces no window`() {
        val credits = """{"paidTier":{"availableCredits":[{"creditAmount":250,"creditType":"G1"}]}}"""
        assertNull(UsageProbe.reportFromGemini(credits))
    }

    // ── Copilot ──────────────────────────────────────────────────────────

    /**
     * Note BOTH date fields, because telling them apart is the whole point of
     * the test below: `timestamp_utc` is when the snapshot was taken,
     * `quota_reset_date_utc` is when the quota actually rolls.
     */
    private val COPILOT =
        """{"authInfo":{"copilotUser":{"quota_reset_date_utc":"2099-02-01T00:00:00.000Z",""" +
            """"quota_snapshots":{""" +
            """"premium_interactions":{"percent_remaining":25.0,"unlimited":false,""" +
            """"timestamp_utc":"2026-09-12T17:00:00.000Z"},""" +
            """"chat":{"percent_remaining":100.0,"unlimited":true},""" +
            """"completions":{"percent_remaining":80.0,"unlimited":false}}}}}"""

    @Test
    fun `copilot snapshots become windows, premium first`() {
        val w = UsageProbe.reportFromCopilot(COPILOT)?.windows.orEmpty()
        // "chat" is unlimited → skipped entirely, so two rows, premium leading.
        assertEquals(2, w.size)
        assertEquals("Premium", w[0].label)
        assertEquals(25, w[0].percent)
        assertEquals("Completions", w[1].label)
        assertEquals(80, w[1].percent)
    }

    /**
     * ⛔ AN UNLIMITED POOL IS NOT A FULL ONE. Drawing it as 100% puts a bar on
     * screen that can never move and competes with the pool that actually
     * blocks a turn.
     */
    @Test
    fun `an unlimited quota draws no bar`() {
        val w = UsageProbe.reportFromCopilot(COPILOT)?.windows.orEmpty()
        assertTrue("unlimited must not appear", w.none { it.label == "Chat" })
    }

    /**
     * ⛔ THE RESET IS `quota_reset_date_utc`, NEVER `resetDate`.
     *
     * `account.getQuota` also returns a `resetDate`, and the CLI assigns it
     * `timestamp_utc` — the moment the snapshot was taken. It advances with the
     * wall clock, so a countdown anchored to it never counts down. This pins
     * that the parser reads the far-future reset, not the 2026 snapshot stamp
     * sitting three fields away from it.
     */
    @Test
    fun `the snapshot timestamp is never mistaken for the reset`() {
        val at = UsageProbe.reportFromCopilot(COPILOT)?.windows?.firstOrNull()?.resetAtEpochMs
        assertTrue("expected the provider's reset", at != null)
        val snapshotStamp = java.time.Instant.parse("2026-09-12T17:00:00.000Z").toEpochMilli()
        assertTrue("read timestamp_utc instead of the reset", at!! > snapshotStamp)
        assertEquals(java.time.Instant.parse("2099-02-01T00:00:00.000Z").toEpochMilli(), at)
    }

    /** A logged-out server answers without the snapshots; nothing is drawn. */
    @Test
    fun `no snapshots means no report`() {
        assertNull(UsageProbe.reportFromCopilot("""{"ok":true,"protocolVersion":3}"""))
    }
}
