package dev.claudeusage.data

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max

data class UsageSample(val date: Long, val value: Double)

/**
 * A run of the chart's line: either watched (consecutive observations close together) or a stretch nobody was
 * watching, where only the values at both ends are known.
 */
data class UsageLine(val id: Int, val watched: Boolean, val samples: List<UsageSample>)

/** A least-squares line through one stretch of active use. */
data class TrendLine(val start: UsageSample, val end: UsageSample, /** Percentage points per hour. */ val perHour: Double)

/** Where the current stretch of use leads if it keeps its pace. */
data class Projection(
    val from: UsageSample,
    val to: UsageSample,
    val perHour: Double,
    /** True when the limit is hit before the window resets ([to] is then at 100%). */
    val hitsLimit: Boolean,
)

/** One line that says what the chart means right now. */
sealed interface ChartCaption {
    data class HitsLimit(val perHour: Double, val at: Long) : ChartCaption
    data class ByReset(val perHour: Double, val value: Double) : ChartCaption
    data class LastStretch(val perHour: Double) : ChartCaption
    data class WeekPace(val used: Double, val elapsedPercent: Double) : ChartCaption
}

/** Everything a chart draws for one window of one limit, computed from the history. */
data class UsageChartModel(
    val metric: Metric,
    val windowStart: Long,
    val windowEnd: Long,
    /**
     * Observations in this window, oldest first: starts with 0% at the window start and, while the window is
     * open, ends with the latest value carried to [now].
     */
    val samples: List<UsageSample>,
    val lines: List<UsageLine>,
    val trends: List<TrendLine>,
    val projection: Projection?,
    val now: Long,
) {
    val current: UsageSample? get() = samples.lastOrNull()
    val duration: Long get() = windowEnd - windowStart

    /** The charted value at [date] (linear between samples). */
    fun valueAt(date: Long): Double? {
        val first = samples.firstOrNull() ?: return null
        val last = samples.last()
        if (date < first.date || date > last.date) return null
        val upper = samples.indexOfFirst { it.date >= date }
        if (upper < 0) return last.value
        if (upper == 0) return first.value
        val a = samples[upper - 1]
        val b = samples[upper]
        val span = b.date - a.date
        if (span <= 0) return b.value
        return a.value + (b.value - a.value) * (date - a.date).toDouble() / span
    }

    /** Value of the dashed projection at [date], if it covers that moment. */
    fun projectedAt(date: Long): Double? {
        val p = projection ?: return null
        if (date <= p.from.date || date > p.to.date) return null
        val span = (p.to.date - p.from.date).toDouble()
        return p.from.value + (p.to.value - p.from.value) * (date - p.from.date) / span
    }

    /** The pace of the stretch of use around [date], if any. */
    fun trendAt(date: Long): TrendLine? = trends.firstOrNull { date in it.start.date..it.end.date }

    val caption: ChartCaption?
        get() = when (metric) {
            Metric.SESSION -> projection?.let {
                if (it.hitsLimit) ChartCaption.HitsLimit(it.perHour, it.to.date) else ChartCaption.ByReset(it.perHour, it.to.value)
            } ?: trends.lastOrNull()?.let { ChartCaption.LastStretch(it.perHour) }
            Metric.WEEKLY -> current?.let {
                val elapsed = (now - windowStart).toDouble() / duration
                ChartCaption.WeekPace(it.value, elapsed * 100)
            }
        }

    companion object {
        /** Observations further apart than this leave a stretch we didn't see. */
        const val GAP_THRESHOLD = 45 * MINUTE
        /** A stretch of use ends after this long without the percentage going up. */
        const val IDLE_BREAK = 20 * MINUTE

        /** The window ending at [resetsAt], or null when there's no open window to chart. */
        fun make(points: List<UsagePoint>, metric: Metric, resetsAt: Long?, now: Long): UsageChartModel? {
            val end = resetsAt ?: return null
            if (end <= now) return null
            val start = end - metric.windowLength
            val samples = samples(points, metric, start, end, now)
            val trends = trends(samples)
            return UsageChartModel(
                metric, start, end, samples, lines(samples), trends,
                projection = if (metric == Metric.SESSION) projection(samples, trends, end, now) else null,
                now = now,
            )
        }

        /** A window starts at 0% with the first message, so that's a known point even before we saw anything. */
        fun samples(points: List<UsagePoint>, metric: Metric, start: Long, end: Long, now: Long): List<UsageSample> {
            val samples = mutableListOf(UsageSample(start, 0.0))
            for (point in points) {
                if (point.date <= start || point.date >= end || point.date > now) continue
                // Points from another window (or "not started", which can't be true inside one).
                val value = point.utilization(metric) ?: continue
                val reset = point.resetsAt(metric) ?: continue
                if (abs(reset - end) >= UsageHistory.RESET_TOLERANCE) continue
                samples += UsageSample(point.date, value)
            }
            val last = samples.last()
            if (now > last.date && now < end) samples += UsageSample(now, last.value)
            return samples
        }

        /**
         * Splits the samples into watched and unwatched runs, so the chart can draw stretches we didn't see
         * differently instead of pretending the line between them is known.
         */
        fun lines(samples: List<UsageSample>): List<UsageLine> {
            val lines = mutableListOf<UsageLine>()
            var run = mutableListOf<UsageSample>()
            var watched = true
            for (i in 0 until samples.size - 1) {
                val a = samples[i]
                val b = samples[i + 1]
                val edgeWatched = b.date - a.date <= GAP_THRESHOLD
                when {
                    run.isEmpty() -> {
                        run = mutableListOf(a, b)
                        watched = edgeWatched
                    }
                    edgeWatched == watched -> run += b
                    else -> {
                        lines += UsageLine(lines.size, watched, run)
                        run = mutableListOf(a, b)
                        watched = edgeWatched
                    }
                }
            }
            if (run.isNotEmpty()) lines += UsageLine(lines.size, watched, run)
            return lines
        }

        /**
         * Stretches of active use: the percentage keeps going up with no pause longer than [IDLE_BREAK]. Each
         * gets a least-squares line, whose slope is the burn rate.
         */
        fun trends(samples: List<UsageSample>): List<TrendLine> {
            val result = mutableListOf<TrendLine>()
            var i = 0
            while (i + 1 < samples.size) {
                if (samples[i + 1].value <= samples[i].value) {
                    i++
                    continue
                }
                // If the rise happened while nobody watched, its start time is unknown: begin after it.
                var first = i
                if (samples[i + 1].date - samples[i].date > GAP_THRESHOLD) first = i + 1
                var last = i + 1
                var k = i + 1
                while (k + 1 < samples.size) {
                    val next = samples[k + 1]
                    if (next.date - samples[last].date > IDLE_BREAK || next.date - samples[k].date > GAP_THRESHOLD) break
                    if (next.value > samples[k].value) last = k + 1
                    k++
                }
                fit(samples.subList(first, last + 1))?.let { result += it }
                i = last
            }
            return result
        }

        /** Least squares over the samples, or null if too few, too short or too flat (one 1% step isn't a rate). */
        fun fit(samples: List<UsageSample>): TrendLine? {
            if (samples.size < 2) return null
            val first = samples.first()
            val last = samples.last()
            if (last.date - first.date < 3 * MINUTE || last.value - first.value < 2) return null
            val xs = samples.map { (it.date - first.date).toDouble() / HOUR }
            val ys = samples.map { it.value }
            val n = samples.size.toDouble()
            val meanX = xs.sum() / n
            val meanY = ys.sum() / n
            var sxx = 0.0
            var sxy = 0.0
            for (j in xs.indices) {
                sxx += (xs[j] - meanX) * (xs[j] - meanX)
                sxy += (xs[j] - meanX) * (ys[j] - meanY)
            }
            if (sxx <= 0) return null
            val slope = sxy / sxx
            val intercept = meanY - slope * meanX
            return TrendLine(
                UsageSample(first.date, max(0.0, intercept)),
                UsageSample(last.date, max(0.0, intercept + slope * xs.last())),
                slope,
            )
        }

        /** Continues the latest stretch of use from the current value, if it's still going. */
        fun projection(samples: List<UsageSample>, trends: List<TrendLine>, end: Long, now: Long): Projection? {
            val trend = trends.lastOrNull() ?: return null
            val current = samples.lastOrNull() ?: return null
            if (now - trend.end.date > IDLE_BREAK || trend.perHour < 1 || current.value >= 100 || now >= end) return null
            val from = UsageSample(now, current.value)
            val perMs = trend.perHour / HOUR
            val hit = now + ((100 - current.value) / perMs).toLong()
            if (hit < end) return Projection(from, UsageSample(hit, 100.0), trend.perHour, hitsLimit = true)
            val atReset = current.value + perMs * (end - now)
            return Projection(from, UsageSample(end, atReset), trend.perHour, hitsLimit = false)
        }

        /** Whole hours inside the window, minus ones so close to an edge that the label would be clipped. */
        fun hourMarks(start: Long, end: Long, zone: ZoneId = ZoneId.systemDefault(), step: Int = 1): List<Long> {
            val margin = 30 * MINUTE
            var hour = Instant.ofEpochMilli(start).atZone(zone).truncatedTo(ChronoUnit.HOURS).plusHours(1)
            val marks = mutableListOf<Long>()
            while (hour.toInstant().toEpochMilli() < end - margin) {
                val t = hour.toInstant().toEpochMilli()
                if (t >= start + margin && hour.hour % step == 0) marks += t
                hour = hour.plusHours(1)
            }
            return marks
        }

        /** Local midnights inside the window, for the weekly chart's day labels. */
        fun dayMarks(start: Long, end: Long, zone: ZoneId = ZoneId.systemDefault()): List<Long> {
            var day = Instant.ofEpochMilli(start).atZone(zone).truncatedTo(ChronoUnit.DAYS).plusDays(1)
            val marks = mutableListOf<Long>()
            while (day.toInstant().toEpochMilli() < end) {
                marks += day.toInstant().toEpochMilli()
                day = day.plusDays(1)
            }
            return marks
        }
    }
}
