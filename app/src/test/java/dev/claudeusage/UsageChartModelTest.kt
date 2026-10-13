package dev.claudeusage

import dev.claudeusage.auth.Pkce
import dev.claudeusage.data.ChartCaption
import dev.claudeusage.data.DAY
import dev.claudeusage.data.Format
import dev.claudeusage.data.HOUR
import dev.claudeusage.data.MINUTE
import dev.claudeusage.data.Metric
import dev.claudeusage.data.UsageChartModel
import dev.claudeusage.data.UsagePoint
import dev.claudeusage.data.UsageSample
import dev.claudeusage.data.UsageWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.time.ZoneOffset
import java.util.Base64
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Ported from the macOS app's UsageChartModelTests, plus formatting and PKCE. */
class UsageChartModelTest {
    private val end = 1_790_018_000_000L
    private val start = end - 5 * HOUR

    private fun at(minutes: Double) = start + (minutes * MINUTE).roundToLong()

    private fun points(values: List<Pair<Double, Double>>) = values.map { (m, v) ->
        UsagePoint(at(m), v, end, 50.0, end + DAY)
    }

    @Test fun samplesStayInTheWindow() {
        val history = mutableListOf<UsagePoint>()
        // From the previous window: left out.
        history += UsagePoint(at(-30.0), 90.0, start, 40.0, null)
        history += points(listOf(10.0 to 5.0, 20.0 to 8.0))
        // From a different reset: left out.
        history += UsagePoint(at(25.0), 3.0, end + HOUR, 50.0, null)
        val model = UsageChartModel.make(history, Metric.SESSION, end, at(30.0))!!
        assertEquals(listOf(0.0, 5.0, 8.0, 8.0), model.samples.map { it.value })
        assertEquals(start, model.samples.first().date)
        assertEquals(at(30.0), model.samples.last().date)
        assertNull(UsageChartModel.make(history, Metric.SESSION, at(-1.0), at(30.0)))
        assertNull(UsageChartModel.make(history, Metric.SESSION, null, at(30.0)))
    }

    @Test fun unwatchedStretchesSplitTheLine() {
        val samples = listOf(0.0 to 0.0, 5.0 to 4.0, 10.0 to 6.0, 120.0 to 30.0, 125.0 to 32.0).map { UsageSample(at(it.first), it.second) }
        val lines = UsageChartModel.lines(samples)
        assertEquals(listOf(true, false, true), lines.map { it.watched })
        assertEquals(listOf(3, 2, 2), lines.map { it.samples.size })
    }

    @Test fun trendsPerStretchOfUse() {
        // 12%/h for 30 min, an idle half hour (heartbeats), then 30%/h for 20 min.
        val model = UsageChartModel.make(
            points(listOf(0.0 to 0.0, 10.0 to 2.0, 20.0 to 4.0, 30.0 to 6.0, 40.0 to 6.0, 50.0 to 6.0, 60.0 to 6.0, 70.0 to 11.0, 80.0 to 16.0)),
            Metric.SESSION, end, at(82.0),
        )!!
        assertEquals(2, model.trends.size)
        val first = model.trends.first()
        val second = model.trends.last()
        assertEquals(12.0, first.perHour, 1e-6)
        assertEquals(at(0.0), first.start.date)
        assertEquals(at(30.0), first.end.date)
        assertEquals(30.0, second.perHour, 1e-6)
        assertEquals(at(60.0), second.start.date)

        // Still going: 16% + 30%/h hits 100% after 2h 48m, before the reset 3h 38m away.
        val projection = model.projection!!
        assertTrue(projection.hitsLimit)
        assertTrue(abs(projection.to.date - at(82.0 + 168)) < 1000)
        val caption = model.caption as ChartCaption.HitsLimit
        assertEquals("30", Format.rate(caption.perHour, Locale.US))
    }

    @Test fun projectionStopsAtResetAndWhenIdle() {
        val slow = UsageChartModel.make(points(listOf(200.0 to 0.0, 210.0 to 1.0, 220.0 to 2.0, 230.0 to 3.0)), Metric.SESSION, end, at(230.0))!!
        val projection = slow.projection!!
        assertFalse(projection.hitsLimit)
        assertEquals(end, projection.to.date)
        assertEquals(3 + 6 * 70.0 / 60, projection.to.value, 1e-6)
        val caption = slow.caption as ChartCaption.ByReset
        assertEquals("6.0", Format.rate(caption.perHour, Locale.US))
        assertEquals("10", Format.percent(caption.value))

        val idle = UsageChartModel.make(points(listOf(200.0 to 0.0, 210.0 to 1.0, 220.0 to 2.0, 230.0 to 3.0)), Metric.SESSION, end, at(260.0))!!
        assertNull(idle.projection)
        assertTrue(idle.caption is ChartCaption.LastStretch)
    }

    @Test fun noTrendFromASingleStep() {
        // First seen long after the window started: the rise before that wasn't watched.
        val model = UsageChartModel.make(points(listOf(100.0 to 1.0, 102.0 to 2.0)), Metric.SESSION, end, at(103.0))!!
        assertTrue(model.trends.isEmpty())
        assertNull(model.caption)
    }

    @Test fun hourMarksAvoidEdges() {
        val base = 1_790_000_000_000L
        val windowStart = base - base % HOUR + 50 * MINUTE
        val marks = UsageChartModel.hourMarks(windowStart, windowStart + 5 * HOUR, ZoneOffset.UTC)
        // Window 12:50–17:50: 13:00 is 10 min from the start, so 14:00…17:00 (17:00 is 50 min from the end).
        assertEquals(4, marks.size)
        assertEquals(windowStart + 70 * MINUTE, marks.first())
    }

    @Test fun valueInterpolates() {
        val model = UsageChartModel.make(points(listOf(10.0 to 10.0, 20.0 to 20.0)), Metric.SESSION, end, at(30.0))!!
        assertEquals(15.0, model.valueAt(at(15.0))!!, 1e-9)
        assertEquals(20.0, model.valueAt(at(25.0))!!, 1e-9)
        assertNull(model.valueAt(at(31.0)))
    }

    @Test fun weeklyCaptionComparesWithElapsedTime() {
        val weekEnd = start + 7 * DAY
        val history = listOf(UsagePoint(start + HOUR, 5.0, null, 30.0, weekEnd))
        val model = UsageChartModel.make(history, Metric.WEEKLY, weekEnd, start + (3.5 * DAY).toLong())!!
        val caption = model.caption as ChartCaption.WeekPace
        assertEquals("30", Format.percent(caption.used))
        assertEquals("50", Format.percent(caption.elapsedPercent))
        assertNull(model.projection)
    }

    @Test fun windowResetsToZero() {
        val window = UsageWindow(40.0, 1000)
        assertEquals(40.0, window.current(999), 0.0)
        assertEquals(0.0, window.current(1000), 0.0)
        assertEquals(40.0, UsageWindow(40.0, null).current(5000), 0.0)
    }

    @Test fun ageAndUntil() {
        assertEquals(listOf(7 to 's'), Format.ageParts(7_000))
        assertEquals(listOf(1 to 'm', 5 to 's'), Format.ageParts(65_000))
        assertEquals(listOf(1 to 'h', 5 to 'm'), Format.ageParts(3_900_000))
        assertEquals(listOf(2 to 'd', 3 to 'h'), Format.ageParts(2 * DAY + 3 * HOUR + 5))
        assertEquals(2 to 35, Format.untilParts(2 * HOUR + 34 * MINUTE + 1))
        assertEquals(0 to 0, Format.untilParts(-5))
    }

    @Test fun pkceIsS256AndUnique() {
        val a = Pkce.create()
        val b = Pkce.create()
        val expected = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(a.verifier.toByteArray()))
        assertEquals(expected, a.challenge)
        assertEquals(43, a.verifier.length)
        assertNotEquals(a.state, b.state)
        assertTrue(a.stateMatches(a.state))
        assertFalse(a.stateMatches(b.state))
        assertFalse(a.stateMatches(null))
    }
}
