package dev.claudeusage.data

import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** How many full 5-hour sessions the weekly limit holds, learned from history. */
data class SessionsEstimate(
    /** Weekly percentage points that one percentage point of the session limit costs. */
    val cost: Double,
    /** Half-width of the ~95% range of [cost] (2σ). */
    val costError: Double,
    /** Weekly percentage points left. */
    val weeklyLeft: Double,
    /** How many session windows the estimate rests on, and since when. */
    val sessions: Int,
    val since: Long,
    /** Set when older history was set aside because the limits seem to have changed then. */
    val limitsChangedAt: Long? = null,
) {
    /**
     * An estimate with its ~95% range. [value] and [high] are null while the data can't rule out
     * "practically unlimited" (the weekly percentage has barely moved yet).
     */
    data class Bounds(val value: Double?, val low: Double, val high: Double?)

    /** Full sessions (0 → 100% of the 5-hour limit) that fit in a whole weekly limit. */
    val perWeek: Bounds get() = bounds(100.0)

    /** Full sessions that fit in what's left of the current weekly limit. */
    val remaining: Bounds get() = bounds(weeklyLeft)

    /** Relative uncertainty of [cost], which carries over unchanged to both counts. */
    val relativeError: Double get() = if (cost > 0) costError / cost else Double.POSITIVE_INFINITY

    private fun bounds(weeklyPoints: Double): Bounds {
        val sessions = weeklyPoints / 100
        return Bounds(
            value = if (cost > 0) sessions / cost else null,
            low = sessions / (cost + costError),
            high = if (cost > costError) sessions / (cost - costError) else null,
        )
    }
}

/** Usage in one unbroken run of observations inside the same session and weekly window. */
data class UsageChunk(
    val start: Long,
    val end: Long,
    /** Growth of the session and weekly percentages over the run. */
    val session: Double,
    val weekly: Double,
    /**
     * Variance of the measurement error in [session] and [weekly]. By default both are differences of two
     * values rounded to whole percent: 1/12 + 1/12.
     */
    val sessionVariance: Double = 1.0 / 6,
    val weeklyVariance: Double = 1.0 / 6,
) {
    /** Relative variance of `weekly / session`: lower is a sharper measurement. */
    val relativeVariance: Double
        get() = if (session <= 0 || weekly <= 0) Double.POSITIVE_INFINITY
        else weeklyVariance / (weekly * weekly) + sessionVariance / (session * session)
}

/**
 * Usage history (a port of the macOS app's): what gets recorded, and the sessions-per-week estimate.
 * Storage is in [HistoryStore]; everything here is pure so it can be unit tested.
 */
object UsageHistory {
    /** Long enough for four full weekly windows plus the current one. */
    const val RETENTION = 35 * DAY
    /** While usage doesn't change, record it again this often, to show we were watching. */
    const val HEARTBEAT = 10 * MINUTE
    /**
     * Sources report the same reset time with different precision (microseconds vs whole seconds), so reset
     * times this close mean the same window.
     */
    const val RESET_TOLERANCE = 10 * MINUTE
    /** How far back the sessions estimate looks at most; see [ESTIMATE_HALF_LIFE]. */
    const val ESTIMATE_LOOKBACK = 28 * DAY
    /** Recent data counts more: a session from a week ago weighs half as much as today's. */
    const val ESTIMATE_HALF_LIFE = 7 * DAY
    /** How many standard errors apart two halves of the history must be to call it a change. */
    const val LIMIT_CHANGE_THRESHOLD = 3.0

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    // region Recording

    /** Whether [point] adds anything after [last]: a changed percentage, a new window, or a heartbeat. */
    fun shouldRecord(point: UsagePoint, last: UsagePoint?): Boolean {
        if (last == null) return point.session != null || point.weekly != null
        if (point.date <= last.date) return false
        for (metric in Metric.entries) {
            val a = last.utilization(metric)
            val b = point.utilization(metric)
            if ((a == null) != (b == null)) return true
            if (a != null && b != null && abs(a - b) >= 0.05) return true
            if (!sameReset(last.resetsAt(metric), point.resetsAt(metric))) return true
        }
        return point.date - last.date >= HEARTBEAT
    }

    /** Both "not started", or both the same reset time within [RESET_TOLERANCE]. */
    fun sameReset(a: Long?, b: Long?): Boolean = when {
        a == null && b == null -> true
        a != null && b != null -> abs(a - b) < RESET_TOLERANCE
        else -> false
    }

    /**
     * Drops entries past [RETENTION], and ones from the future (a clock that was off): new points must come
     * after the last one, so those would block recording until their date.
     */
    fun pruned(points: List<UsagePoint>, now: Long): List<UsagePoint> {
        val cutoff = now - RETENTION
        val latest = now + MINUTE
        return points.filter { it.date in cutoff..latest }
    }

    fun encode(point: UsagePoint): String = json.encodeToString(UsagePoint.serializer(), point)

    /** One JSON object per line; lines that don't parse are skipped. Sorted by date. */
    fun decode(text: String): List<UsagePoint> = text.lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { runCatching { json.decodeFromString(UsagePoint.serializer(), it) }.getOrNull() }
        .sortedBy { it.date }
        .toList()

    // endregion

    // region Sessions per week

    /**
     * Compares how much the weekly and the 5-hour percentages grew inside the same windows. Gaps don't
     * matter (usage while we weren't watching still shows up in both), and resets never look like usage.
     *
     * Limits can change, so recent data counts more ([ESTIMATE_HALF_LIFE]), and when the cost of a session
     * percent clearly differs before and after some point, everything before it is set aside.
     */
    fun sessionsEstimate(points: List<UsagePoint>, currentWeekly: Double?, now: Long): SessionsEstimate? {
        var chunks = chunks(points, since = now - ESTIMATE_LOOKBACK)
        var changedAt: Long? = null
        limitChange(chunks)?.let { split ->
            changedAt = chunks[split].start
            chunks = chunks.subList(split, chunks.size)
        }
        val weights = chunks.map { 0.5.pow(max(0L, now - it.end).toDouble() / ESTIMATE_HALF_LIFE) }
        val first = chunks.firstOrNull() ?: return null
        val fit = fitCost(chunks, weights) ?: return null
        return SessionsEstimate(
            cost = fit.first, costError = 2 * fit.second,
            weeklyLeft = max(0.0, 100 - (currentWeekly ?: 0.0)),
            sessions = chunks.size, since = first.start, limitsChangedAt = changedAt,
        )
    }

    private data class Step(val start: Long, val end: Long, val session: Double, val weekly: Double)

    /**
     * Splits the history into runs of consecutive observations within the same windows (in practice, one per
     * session). Percentages are rounded, but deltas telescope, so each run carries the rounding error of its
     * two ends only.
     */
    fun chunks(points: List<UsagePoint>, since: Long): List<UsageChunk> {
        val result = mutableListOf<UsageChunk>()
        val run = mutableListOf<Step>()
        fun flush() {
            chunk(run)?.let { result += it }
            run.clear()
        }
        for (i in 0 until points.size - 1) {
            val previous = points[i]
            val next = points[i + 1]
            val ds = growth(Metric.SESSION, previous, next)
            val dw = growth(Metric.WEEKLY, previous, next)
            if (previous.date < since || ds == null || dw == null) {
                flush()
                continue
            }
            run += Step(previous.date, next.date, ds, dw)
        }
        flush()
        return result
    }

    /**
     * One run as a measurement, the sharper of two:
     *
     * - **End to end**: total growth of both; the weekly side is off by up to a point from rounding, which
     *   dominates while the weekly percentage has moved only a few points.
     * - **Between weekly ticks**: when the weekly percentage steps up, it has just crossed a rounding
     *   boundary. From the first such step to the last, the week grew by an exact number of points, and only
     *   the session side is uncertain (by how far the session moved between the observations around each step).
     */
    private fun chunk(run: List<Step>): UsageChunk? {
        val first = run.firstOrNull() ?: return null
        val last = run.last()
        var endToEnd = UsageChunk(first.start, last.end, run.sumOf { it.session }, run.sumOf { it.weekly })
        if (endToEnd.session <= 0 && endToEnd.weekly <= 0) return null

        var session = 0.0
        var weekly = 0.0
        data class Tick(val session: Double, val weekly: Double, val variance: Double)
        val ticks = mutableListOf<Tick>()
        for (step in run) {
            if (step.weekly > 0) {
                // Somewhere in this step; the midpoint is off by a uniform error over its width, plus the
                // rounding of the session values themselves.
                ticks += Tick(session + step.session / 2, weekly + step.weekly, step.session * step.session / 12 + 1.0 / 12)
            }
            session += step.session
            weekly += step.weekly
        }
        val a = ticks.firstOrNull()
        val b = ticks.lastOrNull()
        if (a != null && b != null && b.weekly > a.weekly && b.session > a.session) {
            val betweenTicks = UsageChunk(
                first.start, last.end, session = b.session - a.session, weekly = b.weekly - a.weekly,
                sessionVariance = a.variance + b.variance, weeklyVariance = 0.0,
            )
            if (betweenTicks.relativeVariance < endToEnd.relativeVariance) endToEnd = betweenTicks
        }
        return endToEnd
    }

    /**
     * Weighted ratio estimate of weekly points per session point, with its standard error: the spread between
     * runs, but never less than the runs' own measurement error explains. Returns (cost, sigma).
     */
    fun fitCost(chunks: List<UsageChunk>, weights: List<Double>): Pair<Double, Double>? {
        val sessionTotal = chunks.indices.sumOf { weights[it] * chunks[it].session }
        if (sessionTotal <= 0) return null
        val cost = chunks.indices.sumOf { weights[it] * chunks[it].weekly } / sessionTotal
        val sumW = weights.sum()
        val sumW2 = weights.sumOf { it * it }
        val residuals = chunks.indices.sumOf {
            val r = chunks[it].weekly - cost * chunks[it].session
            weights[it] * weights[it] * r * r
        }
        val effectiveCount = sumW * sumW / sumW2
        val spread = if (effectiveCount > 1.5) residuals * effectiveCount / (effectiveCount - 1) else 0.0
        val measurement = chunks.indices.sumOf {
            val c = chunks[it]
            weights[it] * weights[it] * (c.weeklyVariance + cost * cost * c.sessionVariance)
        }
        return cost to sqrt(max(spread, measurement)) / sessionTotal
    }

    /**
     * The run where the limits seem to have changed: the split with the most significant difference in cost
     * between before and after, if it's beyond [LIMIT_CHANGE_THRESHOLD]. Each side needs half a session of
     * data to be compared at all.
     */
    fun limitChange(chunks: List<UsageChunk>): Int? {
        var bestIndex = -1
        var bestZ = 0.0
        for (split in 1 until chunks.size) {
            val before = chunks.subList(0, split)
            val after = chunks.subList(split, chunks.size)
            if (before.sumOf { it.session } < 50 || after.sumOf { it.session } < 50) continue
            val a = fitCost(before, before.map { 1.0 }) ?: continue
            val b = fitCost(after, after.map { 1.0 }) ?: continue
            val z = abs(a.first - b.first) / sqrt(a.second * a.second + b.second * b.second)
            if (z > bestZ) {
                bestZ = z
                bestIndex = split
            }
        }
        return if (bestIndex >= 0 && bestZ > LIMIT_CHANGE_THRESHOLD) bestIndex else null
    }

    /**
     * Growth of [metric] from one observation to the next, or null if they're in different windows (a window
     * that hadn't started yet counts as the start of the next one).
     */
    fun growth(metric: Metric, a: UsagePoint, b: UsagePoint): Double? {
        val before = a.utilization(metric) ?: return null
        val after = b.utilization(metric) ?: return null
        val resetA = a.resetsAt(metric)
        if (resetA != null) {
            val resetB = b.resetsAt(metric) ?: return null
            if (abs(resetA - resetB) >= RESET_TOLERANCE || resetA <= a.date) return null
        }
        val delta = after - before
        return if (delta >= 0) delta else null
    }

    // endregion
}
