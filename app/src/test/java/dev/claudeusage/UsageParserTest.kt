package dev.claudeusage

import dev.claudeusage.data.UnexpectedResponseException
import dev.claudeusage.data.UsageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.abs

class UsageParserTest {
    private val now = 1_790_000_000_000L

    @Test fun parsesLegacyShape() {
        val json = """
            {"five_hour":{"utilization":39.0,"resets_at":"2026-06-11T18:00:00.308440+00:00"},
             "seven_day":{"utilization":44.0,"resets_at":"2026-06-16T18:00:00+00:00"},
             "seven_day_sonnet":null}
        """
        val snapshot = UsageParser.parseUsage(json, now)
        assertEquals(39.0, snapshot.session!!.utilization, 0.0)
        assertEquals(44.0, snapshot.weekly!!.utilization, 0.0)
        assertNotNull(snapshot.session!!.resetsAt)
        assertEquals(Instant.parse("2026-06-16T18:00:00Z").toEpochMilli(), snapshot.weekly!!.resetsAt)
        assertTrue(snapshot.extras.isEmpty())
        assertEquals(now, snapshot.fetchedAt)
    }

    @Test fun fallsBackToLimitsArray() {
        val json = """
            {"five_hour":null,"limits":[
              {"kind":"session","percent":12,"resets_at":"2026-06-11T18:00:00Z"},
              {"kind":"weekly_all","percent":57,"resets_at":null}]}
        """
        val snapshot = UsageParser.parseUsage(json, now)
        assertEquals(12.0, snapshot.session!!.utilization, 0.0)
        assertEquals(57.0, snapshot.weekly!!.utilization, 0.0)
        assertNull(snapshot.weekly!!.resetsAt)
    }

    @Test fun modelLimitsAndExtraUsage() {
        val json = """
            {"five_hour":{"utilization":5,"resets_at":null},
             "seven_day":{"utilization":20,"resets_at":1790600000},
             "seven_day_sonnet":{"utilization":7,"resets_at":"2026-06-16T18:00:00Z"},
             "seven_day_opus":{"utilization":null,"resets_at":null},
             "limits":[{"kind":"weekly_scoped","percent":3,"resets_at":null,"scope":{"model":{"display_name":"Fable"}}},
                       {"kind":"weekly_scoped","percent":9,"scope":{"model":{"display_name":"sonnet"}}}],
             "extra_usage":{"is_enabled":true,"utilization":12.5,"used_credits":250,"monthly_limit":2000}}
        """
        val snapshot = UsageParser.parseUsage(json, now)
        assertEquals(1_790_600_000_000L, snapshot.weekly!!.resetsAt)
        // Sonnet from the field, Fable from limits; the scoped Sonnet duplicate is dropped, Opus had no value.
        assertEquals(listOf("Sonnet", "Fable"), snapshot.extras.map { it.name })
        assertEquals(7.0, snapshot.extras[0].window.utilization, 0.0)
        assertEquals(12.5, snapshot.extraUsage!!.utilization!!, 0.0)
    }

    @Test fun extraUsageOnlyWhenEnabled() {
        val snapshot = UsageParser.parseUsage("""{"five_hour":{"utilization":1},"extra_usage":{"is_enabled":false,"utilization":3}}""", now)
        assertNull(snapshot.extraUsage)
    }

    @Test(expected = UnexpectedResponseException::class)
    fun garbageIsRejected() {
        UsageParser.parseUsage("<html>nope</html>", now)
    }

    @Test fun retryAfterFormats() {
        val base = 1_000_000_000L
        assertEquals(120_000L, UsageParser.parseRetryAfter("120", base))
        assertNull(UsageParser.parseRetryAfter(null, base))
        assertNull(UsageParser.parseRetryAfter("  ", base))
        assertEquals(60_000L, UsageParser.parseRetryAfter("Mon, 12 Jan 1970 13:47:40 GMT", base))
    }

    @Test fun microsecondTimestamps() {
        val date = UsageParser.parseIsoDate("2026-06-11T18:00:00.508711+00:00")!!
        val expected = Instant.parse("2026-06-11T18:00:00Z").toEpochMilli() + 508
        assertTrue(abs(date - expected) <= 1)
    }

    @Test fun profilePlans() {
        val max = UsageParser.parseProfile(
            """{"account":{"email":"a@b.c","display_name":"Ann"},"organization":{"organization_type":"claude_max","rate_limit_tier":"default_claude_max_20x"}}""",
            now,
        )
        assertEquals("a@b.c", max.email)
        assertEquals("Ann", max.name)
        assertEquals("Max 20x", max.plan)
        val pro = UsageParser.parseProfile("""{"account":{"has_claude_pro":true}}""", now)
        assertEquals("Pro", pro.plan)
        assertNull(UsageParser.parseProfile("""{}""", now).plan)
    }
}
