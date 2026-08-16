package ai.eight24family.conch

import ai.eight24family.conch.agent.UsageProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CLI's on-disk usage cache (~/.claude.json → cachedUsageUtilization) is
 * the no-live-process source for the usage bar (Workstream B, 2026-08-17).
 * The fixture is a REAL capture from the dev server (2026-08-17, account uuid
 * scrubbed) — shapes here are what the CLI actually writes, not what docs say.
 */
class UsageProbeCliCacheTest {

    // Verbatim capture, trimmed to the cachedUsageUtilization block plus the
    // surrounding-file shape the extractor has to dig through.
    private val fixture = """
    {
      "numStartups": 1390,
      "installMethod": "native",
      "cachedUsageUtilization": {
        "fetchedAtMs": 1786820012581,
        "accountUuid": "00000000-0000-0000-0000-000000000000",
        "utilization": {
          "five_hour": {
            "utilization": 7,
            "resets_at": "2026-08-15T20:30:00.480325+00:00",
            "limit_dollars": null,
            "used_dollars": null,
            "remaining_dollars": null
          },
          "seven_day": {
            "utilization": 98,
            "resets_at": "2026-08-15T22:00:00.480345+00:00",
            "limit_dollars": null,
            "used_dollars": null,
            "remaining_dollars": null
          },
          "seven_day_oauth_apps": null,
          "seven_day_opus": null,
          "cinder_cove": null,
          "extra_usage": {
            "is_enabled": false,
            "monthly_limit": null,
            "used_credits": null,
            "utilization": null,
            "currency": null,
            "user_disabled": false
          },
          "limits": [
            {
              "kind": "session",
              "group": "session",
              "percent": 7,
              "severity": "normal",
              "resets_at": "2026-08-15T20:30:00.480325+00:00",
              "scope": null,
              "is_active": false
            },
            {
              "kind": "weekly_all",
              "group": "weekly",
              "percent": 98,
              "severity": "critical",
              "resets_at": "2026-08-15T22:00:00.480345+00:00",
              "scope": null,
              "is_active": false
            },
            {
              "kind": "weekly_scoped",
              "group": "weekly",
              "percent": 99,
              "severity": "critical",
              "resets_at": "2026-08-15T22:00:00.480591+00:00",
              "scope": {
                "model": {
                  "id": null,
                  "display_name": "Fable"
                },
                "surface": null
              },
              "is_active": true
            }
          ],
          "spend": {
            "used": { "amount_minor": 0, "currency": "USD", "exponent": 2 },
            "limit": null,
            "percent": 0,
            "severity": "normal",
            "enabled": false,
            "disclaimer": "Usage credits cover you when you hit your plan limits. [Learn more](https://support.claude.com/articles/12429409)",
            "can_purchase_credits": false
          },
          "member_dashboard_available": false
        }
      },
      "hasCompletedOnboarding": true
    }
    """.trimIndent()

    @Test
    fun `real capture parses - aggregates, per-model row, fetchedAt`() {
        val rep = UsageProbe.reportFromCliCacheJson(fixture)!!
        assertEquals(1786820012581L, rep.fetchedAtEpochMs)
        val labels = rep.windows.map { it.label }
        assertTrue("5-hour aggregate", labels.contains("5-hour · all models"))
        assertTrue("weekly aggregate", labels.contains("Weekly · all models"))
        assertEquals(7, rep.windows.first { it.label == "5-hour · all models" }.percent)
        assertEquals(98, rep.windows.first { it.label == "Weekly · all models" }.percent)
        // The per-model layer comes from limits[] (weekly_scoped → Fable).
        val fable = rep.windows.first { it.label == "Fable · weekly" }
        assertEquals(99, fable.percent)
        assertTrue(fable.perModel)
    }

    @Test
    fun `unscoped limits entries never duplicate the aggregates`() {
        val rows = UsageProbe.parseScopedLimits(fixture)
        assertEquals(1, rows.size)
        assertEquals("Fable · weekly", rows.first().label)
    }

    /** Null windows (seven_day_opus: null) and the null-utilization
     *  extra_usage block must not produce rows or crash the parse. */
    @Test
    fun `null windows and extra_usage are skipped`() {
        val rep = UsageProbe.reportFromCliCacheJson(fixture)!!
        assertTrue(rep.windows.none { it.label.contains("oauth", ignoreCase = true) })
        assertTrue(rep.windows.none { it.label.contains("opus", ignoreCase = true) })
        assertNull(rep.extraUsedUsd)
    }

    /** A torn mid-write read (the CLI does not write the file atomically for
     *  our benefit) must degrade to null, never to a garbage report. */
    @Test
    fun `truncated json returns null`() {
        val torn = fixture.substring(0, fixture.indexOf("\"limits\"") + 40)
        assertNull(UsageProbe.reportFromCliCacheJson(torn))
    }

    @Test
    fun `file without the cache block returns null`() {
        assertNull(UsageProbe.reportFromCliCacheJson("""{"numStartups": 3}"""))
    }

    /** Braces inside string values (a disclaimer, a path) must not unbalance
     *  the block scanner. */
    @Test
    fun `brace inside a string does not break extraction`() {
        val tricky = fixture.replace(
            "Usage credits cover you",
            "Usage {credits} } cover { you",
        )
        val rep = UsageProbe.reportFromCliCacheJson(tricky)
        assertEquals(1786820012581L, rep?.fetchedAtEpochMs)
    }

    @Test
    fun `read command is mtime-gated, size-capped, and marker-prefixed`() {
        val cmd = UsageProbe.cliCacheCmd("1786906400")
        assertTrue(cmd.contains("CONCH_UMT:"))
        assertTrue("mtime gate", cmd.contains("\"\$m\" != \"1786906400\""))
        assertTrue("size cap", cmd.contains("-le 2097152"))
        assertTrue("compressed body with plain fallback", cmd.contains("gzip -c") && cmd.contains("|| cat"))
    }
}
