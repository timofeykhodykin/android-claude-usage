package dev.claudeusage.data

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** Locale-aware number formatting shared by the app and the widgets. Pure, so it's unit tested. */
object Format {
    fun percent(value: Double): String = value.roundToInt().toString()

    /** `18`, `2.5` (percentage points per hour). */
    fun rate(perHour: Double, locale: Locale = Locale.getDefault()): String =
        if (perHour >= 10) perHour.roundToInt().toString() else String.format(locale, "%.1f", perHour)

    /** A session count: whole numbers from 10 up, one decimal below. */
    fun sessions(value: Double, locale: Locale = Locale.getDefault()): String =
        if (value >= 10) value.roundToInt().toString() else String.format(locale, "%.1f", value)

    /** An error margin, rounded up so it never reads `± 0.0`. */
    fun sessionsError(value: Double, locale: Locale = Locale.getDefault()): String =
        if (value >= 10) ceil(value).toInt().toString()
        else String.format(locale, "%.1f", max(0.1, ceil(value * 10) / 10))

    /** What the sessions-left line should say for an estimate. */
    sealed interface SessionsText {
        data object Learning : SessionsText
        data object Estimating : SessionsText
        data class Precise(val left: String, val leftError: String, val week: String, val weekError: String) : SessionsText
        data class Range(val leftLow: String, val leftHigh: String, val weekLow: String, val weekHigh: String) : SessionsText
    }

    /**
     * `≈ 2.9 ± 0.3 of 7.4 ± 0.8`: full 5-hour sessions that fit in the rest of the week, with a ~95% range. A
     * range while the data is thin, nothing while it can't say much yet.
     */
    fun sessionsText(estimate: SessionsEstimate?, locale: Locale = Locale.getDefault()): SessionsText {
        estimate ?: return SessionsText.Learning
        val left = estimate.remaining
        val week = estimate.perWeek
        val e = estimate.relativeError
        val leftValue = left.value
        val weekValue = week.value
        if (leftValue != null && weekValue != null && e <= 0.35) {
            return SessionsText.Precise(
                sessions(leftValue, locale), sessionsError(leftValue * e, locale),
                sessions(weekValue, locale), sessionsError(weekValue * e, locale),
            )
        }
        val leftHigh = left.high
        val weekHigh = week.high
        if (leftHigh != null && weekHigh != null && e <= 0.5) {
            return SessionsText.Range(
                sessions(left.low, locale), sessions(leftHigh, locale),
                sessions(week.low, locale), sessions(weekHigh, locale),
            )
        }
        return SessionsText.Estimating
    }

    /** `7s`, `1m 05s`, `12m 30s`, `1h 05m`, `2d 3h` as (value, unit) pairs, for localized units. */
    fun ageParts(ms: Long): List<Pair<Int, Char>> {
        val s = max(0L, ms / 1000).toInt()
        return when {
            s < 60 -> listOf(s to 's')
            s < 3600 -> listOf(s / 60 to 'm', s % 60 to 's')
            s < 86400 -> listOf(s / 3600 to 'h', (s % 3600) / 60 to 'm')
            else -> listOf(s / 86400 to 'd', (s % 86400) / 3600 to 'h')
        }
    }

    /** Time until a reset as (hours, minutes), rounding minutes up so "0m" only shows when it's due. */
    fun untilParts(ms: Long): Pair<Int, Int> {
        val minutes = ceil(max(0L, ms) / 60_000.0).toInt()
        return minutes / 60 to minutes % 60
    }
}
