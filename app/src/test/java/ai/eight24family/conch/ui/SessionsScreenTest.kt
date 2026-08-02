package ai.eight24family.conch.ui

import ai.eight24family.conch.ui.screens.formatStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the helper(s) backing the sessions list rows.
 *
 * Compose semantic-tree assertions on the row layout itself were
 * attempted via Robolectric + `createComposeRule`, but the
 * Robolectric ↔ AGP/JDK17 path on this project trips on
 * `NoSuchFieldError: noncompatWidthPixels` inside
 * `DeviceConfig.applyToConfiguration` before any test code runs (a
 * bytecode/instrumented-android-jar mismatch — see Robolectric issue
 * #9630). Rather than burn time chasing a sandbox-classloader fix,
 * the high-value behaviour — the relative-time string the user
 * actually sees — is locked down here at the function level. Visual
 * regressions are caught by Phase-4 device QA against the real APK.
 */
class SessionsScreenTest {

    @Test
    fun `formatStamp returns just-now for very recent timestamps`() {
        val now = System.currentTimeMillis() / 1000L
        assertEquals("just now", formatStamp(now - 5))
        assertEquals("just now", formatStamp(now))
    }

    @Test
    fun `formatStamp uses minutes for sub-hour deltas`() {
        val now = System.currentTimeMillis() / 1000L
        val t = formatStamp(now - 600) // 10 min ago
        assertTrue("expected minutes, got '$t'", t.endsWith("m ago"))
    }

    @Test
    fun `formatStamp uses hours for sub-day deltas`() {
        val now = System.currentTimeMillis() / 1000L
        val t = formatStamp(now - 7200) // 2 h ago
        assertTrue("expected hours, got '$t'", t.endsWith("h ago"))
    }

    @Test
    fun `formatStamp uses days for sub-week deltas`() {
        val now = System.currentTimeMillis() / 1000L
        val t = formatStamp(now - 86400L * 3) // 3 d ago
        assertTrue("expected days, got '$t'", t.endsWith("d ago"))
    }

    @Test
    fun `formatStamp falls through to absolute date for older timestamps`() {
        val now = System.currentTimeMillis() / 1000L
        val t = formatStamp(now - 86400L * 30) // 30 d ago
        // YYYY-MM-DD shape from SimpleDateFormat
        assertTrue("expected YYYY-MM-DD, got '$t'",
            t.matches(Regex("""\d{4}-\d{2}-\d{2}""")))
    }

    @Test
    fun `formatStamp returns dash for non-positive timestamps`() {
        assertEquals("—", formatStamp(0))
        assertEquals("—", formatStamp(-1))
        assertEquals("—", formatStamp(Long.MIN_VALUE))
    }
}
