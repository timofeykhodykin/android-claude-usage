package dev.claudeusage.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import dev.claudeusage.MainActivity
import dev.claudeusage.R
import dev.claudeusage.auth.AuthState
import dev.claudeusage.data.ErrorKind
import dev.claudeusage.data.MINUTE
import dev.claudeusage.data.Metric
import dev.claudeusage.data.SessionsEstimate
import dev.claudeusage.data.Show
import dev.claudeusage.data.TapAction
import dev.claudeusage.data.ThemeMode
import dev.claudeusage.data.UsageChartModel
import dev.claudeusage.data.UsageState
import dev.claudeusage.data.UsageWindow
import dev.claudeusage.data.WidgetConfig
import dev.claudeusage.ui.Drawing
import dev.claudeusage.ui.Palette
import dev.claudeusage.ui.Texts
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class WidgetKind { RING, BARS, CHART, DASHBOARD }

/** Everything a widget shows, captured once per update so all widgets agree. */
class WidgetData(
    val state: UsageState,
    val auth: AuthState,
    val now: Long,
    val refreshing: Boolean,
    /** Background refresh interval, to judge when data has gone stale. */
    val backgroundMinutes: Int,
) {
    val estimate: SessionsEstimate? by lazy { state.sessionsEstimate(now) }
    private val charts = HashMap<Metric, UsageChartModel?>()

    fun chart(metric: Metric): UsageChartModel? = charts.getOrPut(metric) {
        UsageChartModel.make(state.history, metric, state.snapshot?.window(metric)?.resetsAt, now)
    }

    val signedOut: Boolean get() = auth is AuthState.SignedOut
    val dataAge: Long get() = state.dataAge(now)

    /** Older than a few missed background refreshes: worth flagging. */
    val stale: Boolean get() = dataAge > max(3L * backgroundMinutes, 30L) * MINUTE

    /** The numbers can't be trusted any more: the last attempt failed and the data is old. */
    val dimmed: Boolean get() = (state.error != null || signedOut) && stale
}

/**
 * Builds the RemoteViews for one widget at one size. Layouts are composed from small blocks, chosen by the space
 * there is; colours come in a day and a night variant (Android 12+ switches between them by itself when the
 * system theme changes), and so do the bitmaps for rings, bars and charts.
 */
class WidgetRenderer(private val context: Context, private val data: WidgetData) {
    private val texts = Texts(context)
    private val res = context.resources
    private val density = res.displayMetrics.density
    private val fontScale = res.configuration.fontScale.coerceIn(0.85f, 1.6f)

    private lateinit var config: WidgetConfig
    private lateinit var day: Palette
    private lateinit var night: Palette
    private val twoLooks: Boolean get() = day != night

    fun render(kind: WidgetKind, config: WidgetConfig, size: SizeF): RemoteViews {
        this.config = config
        when (config.theme) {
            ThemeMode.SYSTEM -> {
                day = Palette.of(context, config.palette, dark = false)
                night = Palette.of(context, config.palette, dark = true)
            }
            ThemeMode.LIGHT -> Palette.of(context, config.palette, dark = false).let { day = it; night = it }
            ThemeMode.DARK -> Palette.of(context, config.palette, dark = true).let { day = it; night = it }
        }
        val w = size.width
        val h = size.height
        if (kind == WidgetKind.RING && !data.signedOut && data.state.snapshot != null) return ring(w, h)

        val views = RemoteViews(context.packageName, R.layout.widget_root)
        background(views)
        val compact = min(w, h) < 100
        val padH = if (compact) 10f else 14f
        val padV = if (h < 90) 8f else 12f
        views.setViewPadding(R.id.content, px(padH), px(padV), px(padH), px(padV))
        views.removeAllViews(R.id.content)
        views.setOnClickPendingIntent(android.R.id.background, tapIntent())

        val inner = SizeF(w - 2 * padH, h - 2 * padV)
        when {
            data.signedOut && data.state.snapshot == null -> message(views, R.string.widget_sign_in_title, R.string.widget_sign_in_text, inner)
            data.state.snapshot == null -> message(
                views, R.string.widget_loading_title, R.string.widget_loading_text, inner,
                errorText = data.state.error?.let { texts.error(it, data.state.errorStatus, data.state.backoffUntil, data.now) },
            )
            else -> when (kind) {
                WidgetKind.RING, WidgetKind.BARS -> bars(views, inner)
                WidgetKind.CHART -> chart(views, inner)
                WidgetKind.DASHBOARD -> dashboard(views, inner)
            }
        }
        return views
    }

    // region Kinds

    private fun ring(w: Float, h: Float): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_ring)
        background(views)
        views.setOnClickPendingIntent(android.R.id.background, tapIntent())
        val snapshot = data.state.snapshot!!
        val metrics = metrics()
        val pad = if (min(w, h) < 90) 6f else 10f
        val showFooter = config.showResets && h - w >= 14 && h >= 96
        val footerHeight = if (showFooter) 18f * fontScale else 0f
        val ringDp = max(24f, min(w - 2 * pad, h - 2 * pad - footerHeight))
        views.setViewPadding(R.id.content, px(pad), px(pad), px(pad), px(pad))
        views.setViewLayoutWidth(R.id.ring_box, ringDp, TypedValue.COMPLEX_UNIT_DIP)
        views.setViewLayoutHeight(R.id.ring_box, ringDp, TypedValue.COMPLEX_UNIT_DIP)

        val both = metrics.size == 2
        val strokeDp = ringDp * if (both) 0.085f else 0.105f
        val ringPx = px(ringDp)
        image(views, R.id.ring) { p ->
            Drawing.rings(
                ringPx, strokeDp * density, strokeDp * density * 0.4f,
                metrics.mapIndexed { i, m ->
                    val window = snapshot.window(m)
                    val fraction = (window?.current(data.now) ?: 0.0) / 100
                    val color = p.barColor(fraction, config.barColors)
                    // One accent for both rings: the inner one lighter, so they're told apart.
                    Drawing.Ring(fraction, if (i > 0 && config.barColors == dev.claudeusage.data.BarColors.ACCENT) Palette.withAlpha(color, 0x99) else color)
                },
                p.track,
            )
        }
        val primary = metrics.first()
        val primaryWindow = snapshot.window(primary)
        views.setTextViewText(R.id.ring_value, texts.percent(primaryWindow, data.now))
        views.setTextViewTextSize(R.id.ring_value, TypedValue.COMPLEX_UNIT_DIP, (ringDp * if (both) 0.2f else 0.23f).coerceIn(11f, 40f))
        text(views, R.id.ring_value) { if (data.dimmed) it.textSecondary else it.text }
        val label = if (both) {
            texts.metricShort(Metric.WEEKLY) + " " + texts.percent(snapshot.weekly, data.now)
        } else texts.metricShort(primary)
        views.setTextViewText(R.id.ring_label, label)
        views.setTextViewTextSize(R.id.ring_label, TypedValue.COMPLEX_UNIT_DIP, (ringDp * 0.095f).coerceIn(8f, 13f))
        text(views, R.id.ring_label) { it.textSecondary }
        views.setViewVisibility(R.id.ring_label, if (ringDp >= 48) View.VISIBLE else View.GONE)

        if (showFooter) {
            val footer = when {
                data.state.error == ErrorKind.SIGNED_OUT -> texts.error(ErrorKind.SIGNED_OUT, 0, null, data.now)
                else -> texts.resetShort(primaryWindow, data.now)?.let { "↻ $it" }
            }
            if (footer != null) {
                views.setViewVisibility(R.id.ring_footer, View.VISIBLE)
                views.setTextViewText(R.id.ring_footer, footer)
                text(views, R.id.ring_footer) { if (data.state.error == ErrorKind.SIGNED_OUT) it.error else if (data.stale) it.warning else it.textSecondary }
            }
        }
        views.setContentDescription(android.R.id.background, describe())
        return views
    }

    private fun bars(views: RemoteViews, size: SizeF) {
        val metrics = metrics()
        val n = metrics.size
        val stacked = n * stackedMetricHeight() + (n - 1) * 10f
        when {
            config.showHeader && size.height >= headerHeight() + 8 + stacked -> {
                add(views, header(size.width), 0f)
                views.addView(R.id.content, RemoteViews(context.packageName, R.layout.w_spacer))
                metrics.forEachIndexed { i, m -> add(views, metricStacked(m, size.width), if (i == 0) 0f else 10f) }
                views.addView(R.id.content, RemoteViews(context.packageName, R.layout.w_spacer))
                val left = size.height - headerHeight() - 8 - stacked
                if (Metric.WEEKLY in metrics && left >= lineHeight(11f) + 6) {
                    texts.sessionsShort(data.estimate)?.let { add(views, note(it), 0f) }
                }
            }
            size.height >= stacked -> {
                views.addView(R.id.content, RemoteViews(context.packageName, R.layout.w_spacer))
                metrics.forEachIndexed { i, m -> add(views, metricStacked(m, size.width), if (i == 0) 0f else 10f) }
                views.addView(R.id.content, RemoteViews(context.packageName, R.layout.w_spacer))
            }
            else -> {
                // Rows spread evenly over the height, larger where there's room.
                val g = inlineGeometry(metrics, size.width, large = size.height >= n * 34f + 12)
                views.addView(R.id.content, RemoteViews(context.packageName, R.layout.w_spacer))
                metrics.forEachIndexed { i, m ->
                    if (i > 0) views.addView(R.id.content, RemoteViews(context.packageName, R.layout.w_spacer))
                    add(views, metricInline(m, size.width, g), 0f)
                }
                views.addView(R.id.content, RemoteViews(context.packageName, R.layout.w_spacer))
            }
        }
        views.setContentDescription(android.R.id.background, describe())
    }

    private fun chart(views: RemoteViews, size: SizeF) {
        val metric = config.chart
        val snapshot = data.state.snapshot!!
        val window = snapshot.window(metric)
        val top = RemoteViews(context.packageName, R.layout.w_value_header)
        top.setTextViewText(R.id.value, texts.percent(window, data.now))
        top.setTextViewTextSize(R.id.value, TypedValue.COMPLEX_UNIT_SP, if (size.height >= 120) 22f else 17f)
        text(top, R.id.value) { if (data.dimmed) it.textSecondary else it.text }
        top.setTextViewText(R.id.label, texts.metricTitle(metric))
        text(top, R.id.label) { it.textSecondary }
        val sub = when {
            data.state.error == ErrorKind.SIGNED_OUT -> texts.error(ErrorKind.SIGNED_OUT, 0, null, data.now)
            config.showResets -> texts.resetShort(window, data.now)?.let { "↻ $it" }
            else -> null
        }
        top.setTextViewText(R.id.sub, sub ?: "")
        text(top, R.id.sub) { if (data.state.error == ErrorKind.SIGNED_OUT) it.error else if (data.stale) it.warning else it.textSecondary }
        val topHeight = lineHeight(if (size.height >= 120) 22f else 17f)
        add(views, top, 0f)

        val model = data.chart(metric)
        val caption = texts.caption(model?.caption)?.takeIf { size.height >= 140 }
            ?: texts.captionShort(model?.caption)?.takeIf { size.height >= 110 }
        val captionHeight = if (caption != null) lineHeight(11f) + 4 else 0f
        val chartHeight = size.height - topHeight - 8 - captionHeight
        if (model != null && chartHeight >= 36) {
            add(views, chartBlock(model, size.width, chartHeight), 8f)
            caption?.let { add(views, note(it), 4f) }
        } else {
            views.addView(R.id.content, RemoteViews(context.packageName, R.layout.w_spacer))
            add(views, note(texts.resetRelative(window, data.now)), 6f)
        }
        views.setContentDescription(android.R.id.background, describe())
    }

    private fun dashboard(views: RemoteViews, size: SizeF) {
        var left = size.height
        if (config.showHeader) {
            add(views, header(size.width), 0f)
            left -= headerHeight() + 8
        }
        val metrics = metrics()
        val n = metrics.size
        val stacked = n * stackedMetricHeight() + (n - 1) * 10f
        val inline = n * inlineHeight() + (n - 1) * 8f
        val useStacked = left - stacked >= 90 || left - inline < 56
        val g = inlineGeometry(metrics, size.width, large = false)
        metrics.forEachIndexed { i, m ->
            val block = if (useStacked) metricStacked(m, size.width) else metricInline(m, size.width, g)
            add(views, block, if (i == 0) (if (config.showHeader) 8f else 0f) else if (useStacked) 10f else 8f)
        }
        left -= if (useStacked) stacked else inline

        val sessions = texts.sessionsShort(data.estimate)?.takeIf { Metric.WEEKLY in metrics }
        if (sessions != null && left >= lineHeight(11f) + 6) {
            add(views, note(sessions), 6f)
            left -= lineHeight(11f) + 6
        }
        val model = data.chart(config.chart)
        val captionText = texts.caption(model?.caption)
        val captionHeight = lineHeight(11f) + 4
        val withCaption = captionText != null && left - 12 - captionHeight >= 70
        val chartHeight = left - 12 - if (withCaption) captionHeight else 0f
        if (model != null && chartHeight >= 48) {
            add(views, chartBlock(model, size.width, chartHeight), 12f)
            if (withCaption) add(views, note(captionText!!), 4f)
        }
        views.setContentDescription(android.R.id.background, describe())
    }

    // endregion

    // region Blocks

    private fun header(width: Float): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.w_header)
        tint(v, R.id.spark) { it.accent }
        text(v, R.id.title) { it.text }
        val plan = data.state.profile?.plan
        v.setTextViewText(R.id.plan, plan ?: "")
        v.setViewVisibility(R.id.plan, if (plan != null && width >= 200) View.VISIBLE else View.GONE)
        text(v, R.id.plan) { it.textSecondary }
        val (updated, role) = when {
            data.state.error == ErrorKind.SIGNED_OUT || data.signedOut ->
                texts.error(ErrorKind.SIGNED_OUT, 0, null, data.now) to { p: Palette -> p.error }
            data.stale -> "⚠ " + (texts.updatedShort(data.state.snapshot?.fetchedAt) ?: "") to { p: Palette -> p.warning }
            else -> (texts.updatedShort(data.state.snapshot?.fetchedAt) ?: "") to { p: Palette -> p.textSecondary }
        }
        v.setTextViewText(R.id.updated, updated)
        text(v, R.id.updated, role)
        tint(v, R.id.refresh_icon) { it.textSecondary }
        v.setViewVisibility(R.id.refresh_icon, if (data.refreshing) View.GONE else View.VISIBLE)
        v.setViewVisibility(R.id.refresh_progress, if (data.refreshing) View.VISIBLE else View.GONE)
        if (data.refreshing) {
            v.setColorStateList(R.id.refresh_progress, "setIndeterminateTintList",
                android.content.res.ColorStateList.valueOf(day.accent), android.content.res.ColorStateList.valueOf(night.accent))
        }
        v.setOnClickPendingIntent(R.id.refresh, refreshIntent(context))
        return v
    }

    private fun metricStacked(metric: Metric, width: Float): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.w_metric)
        val window = data.state.snapshot?.window(metric)
        v.setTextViewText(R.id.label, texts.metricTitle(metric))
        text(v, R.id.label) { it.text }
        v.setTextViewText(R.id.value, texts.percent(window, data.now))
        text(v, R.id.value) { if (data.dimmed) it.textSecondary else it.text }
        bar(v, metric, window, width)
        if (config.showResets) {
            v.setTextViewText(R.id.sub, texts.resetAbsolute(window, data.now))
            text(v, R.id.sub) { it.textSecondary }
        } else {
            v.setViewVisibility(R.id.sub, View.GONE)
        }
        return v
    }

    /** Column widths shared by all inline rows, so the bars line up. */
    private class Inline(val labelSp: Float, val valueSp: Float, val label: Float, val value: Float, val sub: Float, val barDp: Float, val shortLabels: Boolean)

    private fun inlineGeometry(metrics: List<Metric>, width: Float, large: Boolean): Inline {
        val labelSp = if (large) 14f else 12f
        val valueSp = if (large) 17f else 14f
        val shortLabels = width < 180
        val label = metrics.maxOf { measure(if (shortLabels) texts.metricShort(it) else texts.metricTitle(it), labelSp) }
        val value = measure("100%", valueSp, bold = true)
        val wide = width >= 230 && config.showResets
        val sub = if (wide) metrics.maxOf { m -> texts.resetShort(data.state.snapshot?.window(m), data.now)?.let { measure(it, 11f) } ?: 0f } else 0f
        return Inline(labelSp, valueSp, label, value, sub, if (large) 12f else 10f, shortLabels)
    }

    /**
     * Width of [text] in dp at [sp], as a TextView would draw it. Some launchers ignore the font resources and draw
     * widgets in the system sans (wider than PT Sans), so this is the wider of the two.
     */
    private fun measure(text: String, sp: Float, bold: Boolean = false, medium: Boolean = false): Float {
        val px = sp * fontScale * density // outside apply: TextPaint has a `density` field of its own
        val font = androidx.core.content.res.ResourcesCompat.getFont(context,
            if (bold) R.font.pt_sans_bold else if (medium) R.font.pt_serif_bold else R.font.pt_sans_regular)
        val system = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF,
            if (bold || medium) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        val widest = listOf(font, system).maxOf { face ->
            android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = px
                typeface = face
                fontFeatureSettings = "tnum"
            }.measureText(text)
        }
        return kotlin.math.ceil(widest / density) + 1
    }

    private fun metricInline(metric: Metric, width: Float, g: Inline): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.w_metric_inline)
        val window = data.state.snapshot?.window(metric)
        v.setTextViewText(R.id.label, if (g.shortLabels) texts.metricShort(metric) else texts.metricTitle(metric))
        v.setTextViewTextSize(R.id.label, TypedValue.COMPLEX_UNIT_SP, g.labelSp)
        v.setViewLayoutWidth(R.id.label, g.label, TypedValue.COMPLEX_UNIT_DIP)
        text(v, R.id.label) { it.textSecondary }
        v.setTextViewText(R.id.value, texts.percent(window, data.now))
        v.setTextViewTextSize(R.id.value, TypedValue.COMPLEX_UNIT_SP, g.valueSp)
        v.setViewLayoutWidth(R.id.value, g.value, TypedValue.COMPLEX_UNIT_DIP)
        text(v, R.id.value) { if (data.dimmed) it.textSecondary else it.text }
        v.setViewLayoutHeight(R.id.bar, g.barDp, TypedValue.COMPLEX_UNIT_DIP)
        val reset = texts.resetShort(window, data.now)
        if (g.sub > 0) {
            v.setViewVisibility(R.id.sub, View.VISIBLE)
            v.setTextViewText(R.id.sub, reset ?: "")
            v.setViewLayoutWidth(R.id.sub, g.sub, TypedValue.COMPLEX_UNIT_DIP)
            text(v, R.id.sub) { it.textSecondary }
        }
        // Label, value and reset have fixed widths; the bar gets the rest (8dp margins on both sides, 8 before the reset).
        val barWidth = width - g.label - g.value - 16f - (if (g.sub > 0) g.sub + 8f else 0f)
        bar(v, metric, window, max(20f, barWidth), g.barDp)
        return v
    }

    private fun bar(v: RemoteViews, metric: Metric, window: UsageWindow?, widthDp: Float, heightDp: Float = 10f) {
        val fraction = (window?.current(data.now) ?: 0.0) / 100
        val wPx = px(widthDp)
        val hPx = px(heightDp)
        image(v, R.id.bar) { p ->
            Drawing.bar(wPx, hPx, heightDp * 0.7f * density, fraction, p.barColor(fraction, config.barColors), p.track,
            )
        }
    }

    private fun chartBlock(model: UsageChartModel, widthDp: Float, heightDp: Float): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.w_chart)
        val wPx = px(widthDp)
        val hPx = px(heightDp)
        val hourFormat = SimpleDateFormat(if (android.text.format.DateFormat.is24HourFormat(context)) "H" else "ha", res.configuration.locales[0])
        val dayFormat = SimpleDateFormat(if (widthDp >= 260) "EEE" else "EEEEE", res.configuration.locales[0])
        image(v, R.id.chart) { p ->
            Drawing.chartBitmap(wPx, hPx, model, Drawing.ChartStyle(
                density = density, palette = p,
                yLabels = widthDp >= 200, xLabels = heightDp >= 64, trends = widthDp >= 200,
                labelSize = 10f * density, lineWidth = 1.8f * density, typeface = Drawing.typeface(context),
                hourLabel = { hourFormat.format(Date(it)).lowercase() },
                dayLabel = { dayFormat.format(Date(it)) },
            ))
        }
        v.setContentDescription(R.id.chart, texts.caption(model.caption) ?: texts.metricTitle(model.metric))
        return v
    }

    private fun note(text: String): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.w_text)
        v.setTextViewText(R.id.text, text)
        text(v, R.id.text) { it.textSecondary }
        return v
    }

    private fun message(views: RemoteViews, title: Int, text: Int?, size: SizeF, errorText: String? = null) {
        val v = RemoteViews(context.packageName, R.layout.w_message)
        tint(v, R.id.spark) { it.accent }
        v.setViewVisibility(R.id.spark, if (size.height >= 70) View.VISIBLE else View.GONE)
        v.setTextViewText(R.id.title, context.getString(title))
        text(v, R.id.title) { it.text }
        val body = errorText ?: text?.let { context.getString(it) }
        if (body != null && size.height >= 50) {
            v.setTextViewText(R.id.text, body)
            text(v, R.id.text) { if (errorText != null) it.warning else it.textSecondary }
        } else {
            v.setViewVisibility(R.id.text, View.GONE)
        }
        views.addView(R.id.content, v)
        // Signing in happens in the app, whatever the tap action is.
        views.setOnClickPendingIntent(android.R.id.background, openAppIntent(context))
    }

    // endregion

    // region Helpers

    private fun metrics(): List<Metric> = when (config.show) {
        Show.SESSION -> listOf(Metric.SESSION)
        Show.WEEKLY -> listOf(Metric.WEEKLY)
        Show.BOTH -> listOf(Metric.SESSION, Metric.WEEKLY)
    }

    /** Adds a block with [top] dp above it. */
    private fun add(views: RemoteViews, block: RemoteViews, top: Float) {
        if (top > 0) block.setViewPadding(R.id.block, 0, px(top), 0, 0)
        views.addView(R.id.content, block)
    }

    private fun lineHeight(sp: Float): Float = sp * fontScale * 1.32f
    private fun headerHeight(): Float = max(26f, lineHeight(13f))
    private fun stackedMetricHeight(): Float = lineHeight(15f) + 5 + 10 + (if (config.showResets) 4 + lineHeight(11f) else 0f)
    private fun inlineHeight(): Float = max(14f, lineHeight(14f))

    private fun px(dp: Float): Int = (dp * density).roundToInt().coerceAtLeast(1)

    private fun background(views: RemoteViews) {
        views.setColorInt(R.id.bg, "setColorFilter", day.background, night.background)
        views.setInt(R.id.bg, "setImageAlpha", (config.opacity.coerceIn(0, 100) * 255 / 100))
    }

    private fun text(views: RemoteViews, id: Int, role: (Palette) -> Int) {
        views.setColorInt(id, "setTextColor", role(day), role(night))
    }

    private fun tint(views: RemoteViews, id: Int, role: (Palette) -> Int) {
        views.setColorInt(id, "setColorFilter", role(day), role(night))
    }

    private fun image(views: RemoteViews, id: Int, draw: (Palette) -> Bitmap) {
        if (twoLooks) {
            views.setIcon(id, "setImageIcon", Icon.createWithBitmap(draw(day)), Icon.createWithBitmap(draw(night)))
        } else {
            views.setImageViewBitmap(id, draw(day))
        }
    }

    private fun tapIntent(): PendingIntent =
        if (config.tapAction == TapAction.REFRESH) refreshIntent(context) else openAppIntent(context)

    /** What a screen reader says for the whole widget. */
    private fun describe(): String {
        val snapshot = data.state.snapshot ?: return context.getString(R.string.widget_title)
        return metrics().joinToString(". ") { m ->
            val window = snapshot.window(m)
            "${texts.metricTitle(m)} ${texts.percent(window, data.now)}, ${texts.resetAbsolute(window, data.now)}"
        }
    }

    // endregion

    companion object {
        fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun refreshIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, WidgetActionReceiver::class.java).setAction(WidgetActionReceiver.ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
