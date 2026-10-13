package dev.claudeusage

import dev.claudeusage.data.DAY
import dev.claudeusage.data.Format
import dev.claudeusage.data.HOUR
import dev.claudeusage.data.MINUTE
import dev.claudeusage.data.Metric
import dev.claudeusage.data.UsageChunk
import dev.claudeusage.data.UsageHistory
import dev.claudeusage.data.UsagePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.roundToLong

/** Ported from the macOS app's UsageHistoryTests. */
class UsageHistoryTest {
    private val t0 = 1_790_000_000_000L
    private val sessionReset = t0 + 4 * HOUR
    private val weekReset = t0 + 3 * DAY
    private val en = Locale.US

    private fun point(minutes: Double, s: Double?, sr: Long? = null, w: Double?, wr: Long? = null) =
        UsagePoint(t0 + (minutes * MINUTE).roundToLong(), s, sr, w, wr)

    @Test fun recordsChangesAndHeartbeats() {
        val last = point(0.0, 10.0, sessionReset, 40.0, weekReset)
        assertTrue(UsageHistory.shouldRecord(last, null))
        assertFalse(UsageHistory.shouldRecord(point(1.0, 10.0, sessionReset, 40.0, weekReset), last))
        assertTrue(UsageHistory.shouldRecord(point(1.0, 11.0, sessionReset, 40.0, weekReset), last))
        assertTrue(UsageHistory.shouldRecord(point(1.0, 10.0, sessionReset, 41.0, weekReset), last))
        // Same reset reported with different precision: not a change.
        assertFalse(UsageHistory.shouldRecord(point(1.0, 10.0, sessionReset + 300, 40.0, weekReset), last))
        // A new window is a change even at the same percentage.
        assertTrue(UsageHistory.shouldRecord(point(1.0, 10.0, sessionReset + 5 * HOUR, 40.0, weekReset), last))
        assertTrue(UsageHistory.shouldRecord(point(10.0, 10.0, sessionReset, 40.0, weekReset), last))
        assertFalse(UsageHistory.shouldRecord(point(-1.0, 50.0, sessionReset, 40.0, weekReset), last))
        assertFalse(UsageHistory.shouldRecord(point(0.0, null, w = null), null))
    }

    @Test fun fileFormatRoundTrips() {
        val points = listOf(point(0.0, 10.0, sessionReset, 40.0, weekReset), point(5.0, 12.5, w = null))
        val text = points.reversed().joinToString("") { UsageHistory.encode(it) + "\nnot json\n" }
        assertEquals(points, UsageHistory.decode(text)) // garbage skipped, sorted by date
        assertTrue(UsageHistory.encode(points[1]).contains("\"s\":12.5"))
        assertFalse(UsageHistory.encode(points[1]).contains("\"w\""))
    }

    @Test fun pruneKeepsRetention() {
        val old = point(-36.0 * 24 * 60, 1.0, w = 1.0)
        val recent = point(-34.0 * 24 * 60, 1.0, w = 1.0)
        val future = point(2.0 * 60, 1.0, w = 1.0)
        assertEquals(listOf(recent), UsageHistory.pruned(listOf(old, recent, future), t0))
    }

    @Test fun sessionsPerWeekFromDeltas() {
        val r1 = t0 + 5 * HOUR
        val r2 = t0 + 11 * HOUR
        val points = listOf(
            // Session not started yet, then the first window: 0 → 60%, weekly 10 → 18%.
            point(0.0, 0.0, w = 10.0, wr = weekReset),
            point(30.0, 20.0, r1, 13.0, weekReset),
            point(120.0, 60.0, r1, 18.0, weekReset),
            // Next window, seen first at 5% (that pair crosses a reset and is skipped).
            point(400.0, 5.0, r2, 19.0, weekReset),
            point(500.0, 65.0, r2, 27.0, weekReset),
        )
        val estimate = UsageHistory.sessionsEstimate(points, 27.0, points[4].date)!!
        // Session grew 60 + 60, weekly 8 + 8: 7.5 full sessions per week, 73 / 13.33 left.
        assertEquals(2, estimate.sessions)
        assertEquals(7.5, estimate.perWeek.value!!, 1e-9)
        assertEquals(73 / (100 / 7.5), estimate.remaining.value!!, 1e-9)
        // Both sessions agree exactly, so only rounding to whole percent is left: a few percent.
        assertTrue(estimate.relativeError > 0.03 && estimate.relativeError < 0.1)
        val text = Format.sessionsText(estimate, en) as Format.SessionsText.Precise
        assertEquals("5.5", text.left)
        assertTrue(text.leftError.startsWith("0."))
        assertEquals("7.5", text.week)
        assertEquals("0.6", text.weekError)
    }

    @Test fun estimateShowsUpWithLittleData() {
        val r = sessionReset
        // Weekly moved 2 points: a range.
        val some = listOf(point(0.0, 0.0, r, 10.0, weekReset), point(30.0, 20.0, r, 12.0, weekReset))
        val ranged = UsageHistory.sessionsEstimate(some, 12.0, some[1].date)!!
        assertTrue(ranged.relativeError > 0.35 && ranged.relativeError <= 0.5)
        assertTrue(Format.sessionsText(ranged, en) is Format.SessionsText.Range)

        // Weekly moved a single point: too rough to show.
        val one = listOf(point(0.0, 11.0, r, 2.0, weekReset), point(14.0, 15.0, r, 3.0, weekReset))
        val rough = UsageHistory.sessionsEstimate(one, 3.0, one[1].date)!!
        assertEquals(Format.SessionsText.Estimating, Format.sessionsText(rough, en))

        // Weekly hasn't moved yet: only a lower bound so far.
        val tiny = listOf(point(0.0, 0.0, r, 14.0, weekReset), point(30.0, 3.0, r, 14.0, weekReset))
        val bound = UsageHistory.sessionsEstimate(tiny, 14.0, tiny[1].date)!!
        assertNull(bound.perWeek.value)
        assertNull(bound.perWeek.high)
        assertEquals("3.2", Format.sessions(bound.remaining.low, en))
        assertEquals("3.7", Format.sessions(bound.perWeek.low, en))

        // Nothing used at all: nothing to say.
        val idle = listOf(point(0.0, 5.0, r, 14.0, weekReset), point(30.0, 5.0, r, 14.0, weekReset))
        assertNull(UsageHistory.sessionsEstimate(idle, 14.0, idle[1].date))
        assertEquals(Format.SessionsText.Learning, Format.sessionsText(null, en))
    }

    @Test fun weeklyTicksSharpenTheEstimate() {
        // Reported after every reply, a session point at a time; the weekly percentage steps up at 3, 10 and 17.
        // Between the first and last step the week grew exactly 2 points while the session grew 16.5 - 2.5 = 14.
        val points = (0..19).map { s ->
            val w = 10 + (if (s >= 3) 1 else 0) + (if (s >= 10) 1 else 0) + (if (s >= 17) 1 else 0)
            point(s.toDouble(), s.toDouble(), sessionReset, w.toDouble(), weekReset)
        }
        val chunk = UsageHistory.chunks(points, t0).first()
        assertEquals(14.0, chunk.session, 0.0)
        assertEquals(2.0, chunk.weekly, 0.0)
        assertEquals(0.0, chunk.weeklyVariance, 0.0)
        val estimate = UsageHistory.sessionsEstimate(points, 13.0, points[19].date)!!
        assertEquals(7.0, estimate.perWeek.value!!, 1e-9)
        assertTrue(estimate.relativeError < 0.1)
        val text = Format.sessionsText(estimate, en) as Format.SessionsText.Precise
        assertEquals("6.1", text.left)
    }

    @Test fun coarseTicksFallBackToEndToEnd() {
        val points = listOf(
            point(0.0, 0.0, sessionReset, 10.0, weekReset),
            point(30.0, 20.0, sessionReset, 13.0, weekReset),
            point(120.0, 60.0, sessionReset, 18.0, weekReset),
        )
        val chunk = UsageHistory.chunks(points, t0).first()
        assertEquals(60.0, chunk.session, 0.0)
        assertEquals(8.0, chunk.weekly, 0.0)
    }

    /** One session window [daysAgo] before t0, in which the session grew by [ds] and the week by [dw]. */
    private fun session(daysAgo: Double, ds: Double, dw: Double): List<UsagePoint> {
        val start = t0 - (daysAgo * DAY).roundToLong()
        val reset = start + 5 * HOUR
        return listOf(
            UsagePoint(start, 0.0, reset, 20.0, reset + DAY),
            UsagePoint(start + 3 * HOUR, ds, reset, 20 + dw, reset + DAY),
        )
    }

    @Test fun recentSessionsWeighMore() {
        val chunks = listOf(UsageChunk(t0, t0, 60.0, 12.0), UsageChunk(t0, t0, 60.0, 6.0))
        val even = UsageHistory.fitCost(chunks, listOf(1.0, 1.0))!!
        val recent = UsageHistory.fitCost(chunks, listOf(0.25, 1.0))!!
        assertEquals(0.15, even.first, 1e-9)
        assertEquals((0.25 * 12 + 6) / (0.25 * 60 + 60), recent.first, 1e-9)
    }

    @Test fun limitChangeDropsOlderData() {
        // Two weeks at 12% of the week per full session, then the limits halve it to 6%.
        val points = mutableListOf<UsagePoint>()
        var day = 20.0
        while (day >= 8) {
            points += session(day, 60.0, 7.0)
            day -= 2
        }
        for (d in listOf(5.0, 3.0, 1.0)) points += session(d, 60.0, 4.0)
        val estimate = UsageHistory.sessionsEstimate(points, 20.0, t0)!!
        assertEquals(3, estimate.sessions)
        assertEquals(t0 - 5 * DAY, estimate.limitsChangedAt)
        assertEquals(4.0 / 60, estimate.cost, 1e-9)
    }

    @Test fun roundingNoiseIsNotALimitChange() {
        // Same limits; the weekly growth per session just rounds to 7 or 8.
        val points = mutableListOf<UsagePoint>()
        var day = 20.0
        var i = 0
        while (day >= 1) {
            points += session(day, 55.0 + (i % 3) * 5, listOf(7.0, 8.0, 8.0, 7.0, 9.0)[i % 5])
            day -= 1.5
            i++
        }
        val estimate = UsageHistory.sessionsEstimate(points, 40.0, t0)!!
        assertNull(estimate.limitsChangedAt)
        assertEquals(13, estimate.sessions)
        assertTrue(estimate.relativeError < 0.15)
    }

    @Test fun weeklyResetBreaksThePair() {
        val a = point(0.0, 10.0, sessionReset, 90.0, t0 + MINUTE)
        val b = point(5.0, 20.0, sessionReset, 2.0, weekReset)
        assertEquals(10.0, UsageHistory.growth(Metric.SESSION, a, b)!!, 0.0)
        assertNull(UsageHistory.growth(Metric.WEEKLY, a, b))
    }

    @Test fun sessionCountFormatting() {
        assertEquals("12", Format.sessions(11.6, en))
        assertEquals("3.5", Format.sessions(3.46, en))
        assertEquals("3,5", Format.sessions(3.46, Locale.forLanguageTag("ru")))
        assertEquals("0.1", Format.sessionsError(0.001, en))
        assertEquals("0.4", Format.sessionsError(0.31, en))
        assertEquals("11", Format.sessionsError(10.2, en))
        assertNotNull(Format.sessionsText(null, en))
    }
}
