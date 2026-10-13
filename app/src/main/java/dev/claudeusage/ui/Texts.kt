package dev.claudeusage.ui

import android.content.Context
import android.text.format.DateFormat
import dev.claudeusage.R
import dev.claudeusage.data.ChartCaption
import dev.claudeusage.data.ErrorKind
import dev.claudeusage.data.Format
import dev.claudeusage.data.Metric
import dev.claudeusage.data.SessionsEstimate
import dev.claudeusage.data.UsageWindow
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

/** User-facing text built from resources, shared by the app screens and the widgets. */
class Texts(private val context: Context) {
    private val locale: Locale get() = context.resources.configuration.locales[0]

    private fun s(id: Int, vararg args: Any): String = context.getString(id, *args)

    fun percent(window: UsageWindow?, now: Long): String =
        window?.let { s(R.string.percent, Format.percent(it.current(now))) } ?: "–"

    fun metricTitle(metric: Metric): String =
        s(if (metric == Metric.SESSION) R.string.session_title else R.string.weekly_title)

    fun metricShort(metric: Metric): String =
        s(if (metric == Metric.SESSION) R.string.session_short else R.string.weekly_short)

    /** "Resets in 2h 35m", or why there's no reset time. */
    fun resetRelative(window: UsageWindow?, now: Long): String {
        window ?: return s(R.string.no_data_yet)
        val resetsAt = window.resetsAt ?: return s(R.string.not_started)
        if (resetsAt <= now) return s(R.string.reset_happened)
        val (h, m) = Format.untilParts(resetsAt - now)
        return when {
            h >= 24 -> s(R.string.resets_in_dh, h / 24, h % 24)
            h > 0 -> s(R.string.resets_in_hm, h, m)
            else -> s(R.string.resets_in_m, m)
        }
    }

    /** "in 2h 35m" without the verb. */
    fun until(resetsAt: Long, now: Long): String {
        val (h, m) = Format.untilParts(resetsAt - now)
        return if (h >= 24) s(R.string.in_dh, h / 24, h % 24)
        else if (h > 0) s(R.string.in_hm, h, m) else s(R.string.in_m, m)
    }

    /** "Resets today 17:40", "Resets tomorrow 9:00", "Resets Thu 15:10", "Resets 16 Oct 15:10". */
    fun resetAbsolute(window: UsageWindow?, now: Long): String {
        window ?: return s(R.string.no_data_yet)
        val resetsAt = window.resetsAt ?: return s(R.string.not_started)
        if (resetsAt <= now) return s(R.string.reset_happened)
        return s(R.string.resets_at, day(resetsAt, now))
    }

    /** Compact reset time for widgets: "17:40" today, otherwise "Thu 15:10". */
    fun resetShort(window: UsageWindow?, now: Long): String? {
        val resetsAt = window?.resetsAt ?: return null
        if (resetsAt <= now) return s(R.string.reset_short_done)
        return if (daysBetween(now, resetsAt) == 0) time(resetsAt) else weekdayTime(resetsAt)
    }

    fun time(at: Long): String = DateFormat.getTimeFormat(context).format(Date(at))

    private fun weekdayTime(at: Long): String {
        val skeleton = if (DateFormat.is24HourFormat(context)) "EEEHHmm" else "EEEhmma"
        return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(Date(at))
    }

    private fun dayMonthTime(at: Long): String {
        val skeleton = if (DateFormat.is24HourFormat(context)) "dMMMHHmm" else "dMMMhmma"
        return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(Date(at))
    }

    /** "today 17:40", "tomorrow 9:00", "Thu 15:10", "16 Oct 15:10". */
    fun day(at: Long, now: Long): String = when (val days = daysBetween(now, at)) {
        0 -> s(R.string.today_at, time(at))
        1 -> s(R.string.tomorrow_at, time(at))
        in 2..6 -> weekdayTime(at)
        else -> dayMonthTime(at)
    }

    private fun daysBetween(from: Long, to: Long): Int {
        val zone = ZoneId.systemDefault()
        val a = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        val b = Instant.ofEpochMilli(to).atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(a, b).toInt()
    }

    /** "42s", "1m 05s", "1h 05m", "2d 3h". */
    fun age(ms: Long): String = Format.ageParts(ms).mapIndexed { i, (value, unit) ->
        val number = if (i > 0 && unit != 'h' && unit != 'd') "%02d".format(locale, value) else value.toString()
        when (unit) {
            's' -> s(R.string.unit_s, number)
            'm' -> s(R.string.unit_m, number)
            'h' -> s(R.string.unit_h, number)
            else -> s(R.string.unit_d, number)
        }
    }.joinToString(" ")

    fun updated(fetchedAt: Long?, now: Long): String =
        fetchedAt?.let { s(R.string.updated_ago, time(it), age(now - it)) } ?: s(R.string.never_updated)

    fun updatedShort(fetchedAt: Long?): String? = fetchedAt?.let { time(it) }

    fun rate(perHour: Double): String = s(R.string.rate_per_hour, Format.rate(perHour, locale))

    fun caption(caption: ChartCaption?): String? = when (caption) {
        null -> null
        is ChartCaption.HitsLimit -> s(R.string.caption_hits_limit, rate(caption.perHour), time(caption.at))
        is ChartCaption.ByReset -> s(R.string.caption_by_reset, rate(caption.perHour), Format.percent(caption.value))
        is ChartCaption.LastStretch -> s(R.string.caption_last_stretch, rate(caption.perHour))
        is ChartCaption.WeekPace -> s(R.string.caption_week_pace, Format.percent(caption.used), Format.percent(caption.elapsedPercent))
    }

    /** Short caption for small widgets. */
    fun captionShort(caption: ChartCaption?): String? = when (caption) {
        null -> null
        is ChartCaption.HitsLimit -> s(R.string.caption_short_hits, time(caption.at))
        is ChartCaption.ByReset -> s(R.string.caption_short_by_reset, Format.percent(caption.value))
        is ChartCaption.LastStretch -> rate(caption.perHour)
        is ChartCaption.WeekPace -> s(R.string.caption_short_week, Format.percent(caption.elapsedPercent))
    }

    fun sessions(estimate: SessionsEstimate?): String = when (val t = Format.sessionsText(estimate, locale)) {
        Format.SessionsText.Learning -> s(R.string.sessions_learning)
        Format.SessionsText.Estimating -> s(R.string.sessions_estimating)
        is Format.SessionsText.Precise -> s(R.string.sessions_precise, t.left, t.leftError, t.week, t.weekError)
        is Format.SessionsText.Range -> s(R.string.sessions_range, t.leftLow, t.leftHigh, t.weekLow, t.weekHigh)
    }

    /** Compact form for widgets: "≈ 3.5 of 7.5 sessions left", or null while there's nothing useful to say. */
    fun sessionsShort(estimate: SessionsEstimate?): String? = when (val t = Format.sessionsText(estimate, locale)) {
        is Format.SessionsText.Precise -> s(R.string.sessions_short, t.left, t.week)
        is Format.SessionsText.Range -> s(R.string.sessions_short_range, t.leftLow, t.leftHigh)
        else -> null
    }

    fun error(kind: ErrorKind, status: Int, backoffUntil: Long?, now: Long): String = when (kind) {
        ErrorKind.NETWORK -> s(R.string.error_network)
        ErrorKind.RATE_LIMITED -> backoffUntil?.takeIf { it > now }
            ?.let { s(R.string.error_rate_limited_until, time(it)) } ?: s(R.string.error_rate_limited)
        ErrorKind.FORBIDDEN -> s(R.string.error_forbidden)
        ErrorKind.SERVER -> s(R.string.error_server, status)
        ErrorKind.UNEXPECTED -> s(R.string.error_unexpected)
        ErrorKind.SIGNED_OUT -> s(R.string.error_signed_out)
    }
}
