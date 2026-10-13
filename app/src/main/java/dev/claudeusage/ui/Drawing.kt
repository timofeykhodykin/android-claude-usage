package dev.claudeusage.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import dev.claudeusage.data.HOUR
import dev.claudeusage.data.Metric
import dev.claudeusage.data.UsageChartModel
import kotlin.math.max
import kotlin.math.min

/**
 * Drawing shared by the widgets (into bitmaps) and the app (into Compose's canvas): progress rings, capsule
 * bars and the usage chart. Sizes are in pixels; callers scale by density.
 */
object Drawing {
    @Volatile private var ptSans: Typeface? = null

    /** The app's font for text drawn into bitmaps (chart labels). */
    fun typeface(context: android.content.Context): Typeface =
        ptSans ?: (androidx.core.content.res.ResourcesCompat.getFont(context, dev.claudeusage.R.font.pt_sans_regular) ?: Typeface.DEFAULT)
            .also { ptSans = it }

    private fun paint(color: Int, style: Paint.Style = Paint.Style.FILL) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.style = style
    }

    // region Rings

    class Ring(val fraction: Double, val color: Int)

    /**
     * Concentric progress rings, outermost first, starting at 12 o'clock. Round caps, but a cap never sticks out
     * past the start for tiny values.
     */
    fun rings(size: Int, stroke: Float, gap: Float, rings: List<Ring>, track: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        var inset = stroke / 2 + 1
        for (ring in rings) {
            val rect = RectF(inset, inset, size - inset, size - inset)
            canvas.drawArc(rect, 0f, 360f, false, paint(track, Paint.Style.STROKE).apply { strokeWidth = stroke })
            val fraction = ring.fraction.coerceIn(0.0, 1.0).toFloat()
            if (fraction > 0) {
                val p = paint(ring.color, Paint.Style.STROKE).apply {
                    strokeWidth = stroke
                    strokeCap = Paint.Cap.ROUND
                }
                // Keep at least a dot's worth so 1% is visible, and leave the round cap room at 100%.
                val sweep = max(fraction * 360f, 0.5f)
                if (fraction >= 0.999f) canvas.drawArc(rect, 0f, 360f, false, p.apply { strokeCap = Paint.Cap.BUTT })
                else canvas.drawArc(rect, -90f, sweep, false, p)
            }
            inset += stroke + gap
        }
        return bitmap
    }

    // endregion

    // region Bars

    /**
     * A capsule bar. A capsule narrower than it is tall loses its rounding, so the fill is never narrower than
     * the bar's height: it slides in from the left with its right end at [fraction]; the track clips it.
     * [marker] (0…1) draws a small notch, e.g. how much of the week has passed.
     */
    fun drawBar(canvas: Canvas, left: Float, top: Float, width: Float, height: Float, fraction: Double,
                fill: Int, track: Int, marker: Double? = null, markerColor: Int = 0) {
        val r = height / 2
        val bounds = RectF(left, top, left + width, top + height)
        canvas.save()
        val clip = Path().apply { addRoundRect(bounds, r, r, Path.Direction.CW) }
        canvas.clipPath(clip)
        canvas.drawRoundRect(bounds, r, r, paint(track))
        val filled = fraction.coerceIn(0.0, 1.0).toFloat() * width
        if (fraction > 0) {
            val fillWidth = max(filled, height)
            val fillLeft = left + filled - fillWidth
            canvas.drawRoundRect(RectF(fillLeft, top, fillLeft + fillWidth, top + height), r, r, paint(fill))
        }
        canvas.restore()
        if (marker != null && marker in 0.0..1.0) {
            val x = left + (marker * width).toFloat()
            val w = max(1.5f, height / 4)
            canvas.drawRoundRect(RectF(x - w / 2, top - height * 0.35f, x + w / 2, top + height * 1.35f), w / 2, w / 2, paint(markerColor))
        }
    }

    fun bar(width: Int, height: Int, barHeight: Float, fraction: Double, fill: Int, track: Int,
            marker: Double? = null, markerColor: Int = 0): Bitmap {
        val bitmap = Bitmap.createBitmap(max(1, width), max(1, height), Bitmap.Config.ARGB_8888)
        drawBar(Canvas(bitmap), 0f, (height - barHeight) / 2, width.toFloat(), barHeight, fraction, fill, track, marker, markerColor)
        return bitmap
    }

    // endregion

    // region Chart

    class ChartStyle(
        val density: Float,
        val palette: Palette,
        /** Under the end dot, so it stays legible on top of the lines. */
        val surface: Int = palette.background,
        val yLabels: Boolean = true,
        val xLabels: Boolean = true,
        val trends: Boolean = true,
        val labelSize: Float = 10f * density,
        val typeface: Typeface = Typeface.DEFAULT,
        val lineWidth: Float = 2f * density,
        val hourLabel: (Long) -> String,
        val dayLabel: (Long) -> String,
    )

    /** The plot area inside a chart of the given size, leaving room for the axis labels. */
    fun plotRect(width: Float, height: Float, style: ChartStyle): RectF {
        val d = style.density
        val right = if (style.yLabels) width - style.labelSize * 3.1f else width - 5 * d
        val bottom = if (style.xLabels) height - style.labelSize * 1.6f else height - 5 * d
        return RectF(4 * d, 6 * d, right, bottom)
    }

    fun chartBitmap(width: Int, height: Int, model: UsageChartModel, style: ChartStyle): Bitmap {
        val bitmap = Bitmap.createBitmap(max(1, width), max(1, height), Bitmap.Config.ARGB_8888)
        drawChart(Canvas(bitmap), width.toFloat(), height.toFloat(), model, style)
        return bitmap
    }

    fun drawChart(canvas: Canvas, width: Float, height: Float, model: UsageChartModel, style: ChartStyle) {
        val p = style.palette
        val d = style.density
        val plot = plotRect(width, height, style)
        val yMax = max(100.0, model.samples.maxOfOrNull { it.value } ?: 0.0)
        val start = model.windowStart
        val span = (model.windowEnd - start).toFloat()
        fun x(t: Long) = plot.left + (t - start) / span * plot.width()
        fun y(v: Double) = plot.bottom - (v / yMax).toFloat() * plot.height()

        // Grid and labels.
        val grid = paint(p.grid, Paint.Style.STROKE).apply { strokeWidth = max(1f, 0.75f * d) }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.textSecondary
            textSize = style.labelSize
            typeface = style.typeface
            fontFeatureSettings = "tnum"
        }
        // A low chart has room for the extremes only.
        val roomy = plot.height() >= style.labelSize * 4.5f
        for (v in listOf(0.0, 50.0, 100.0)) {
            canvas.drawLine(plot.left, y(v), plot.right, y(v), grid)
            if (style.yLabels && (roomy || v != 50.0)) {
                label.textAlign = Paint.Align.LEFT
                val text = "${v.toInt()}%"
                canvas.drawText(text, plot.right + 5 * d, y(v) + style.labelSize * 0.35f, label)
            }
        }
        if (style.xLabels) {
            label.textAlign = Paint.Align.CENTER
            val baseline = height - style.labelSize * 0.35f
            when (model.metric) {
                Metric.SESSION -> {
                    val roomFor = (plot.width() / (style.labelSize * 2.6f)).toInt()
                    val step = if (roomFor >= 5) 1 else 2
                    for (t in UsageChartModel.hourMarks(model.windowStart, model.windowEnd, step = step)) {
                        canvas.drawLine(x(t), plot.top, x(t), plot.bottom, grid)
                        canvas.drawText(style.hourLabel(t), x(t), baseline, label)
                    }
                }
                Metric.WEEKLY -> {
                    val days = UsageChartModel.dayMarks(model.windowStart, model.windowEnd)
                    for (t in days) canvas.drawLine(x(t), plot.top, x(t), plot.bottom, grid)
                    // Weekday names centred in each whole day.
                    val edges = listOf(model.windowStart) + days + listOf(model.windowEnd)
                    for (i in 0 until edges.size - 1) {
                        if (edges[i + 1] - edges[i] < 16 * HOUR) continue
                        val mid = (edges[i] + edges[i + 1]) / 2
                        canvas.drawText(style.dayLabel(mid), x(mid), baseline, label)
                    }
                }
            }
        }

        // Even pace through the week.
        if (model.metric == Metric.WEEKLY) {
            val pace = paint(Palette.withAlpha(p.textSecondary, 0x70), Paint.Style.STROKE).apply { strokeWidth = max(1f, d) }
            canvas.drawLine(x(model.windowStart), y(0.0), x(model.windowEnd), y(100.0), pace)
        }

        val samples = model.samples
        if (samples.size >= 2) {
            // Area under the line.
            val area = Path().apply {
                moveTo(x(samples.first().date), y(0.0))
                for (s in samples) lineTo(x(s.date), y(s.value))
                lineTo(x(samples.last().date), y(0.0))
                close()
            }
            canvas.drawPath(area, paint(Palette.withAlpha(p.accent, 0x24)))
        }

        // Under the usage line: a wide soft band per stretch of active use; its slope is that stretch's pace.
        if (style.trends) {
            val band = paint(Palette.withAlpha(p.text, if (p.dark) 0x38 else 0x24), Paint.Style.STROKE).apply {
                strokeWidth = style.lineWidth * 3.5f
                strokeCap = Paint.Cap.ROUND
            }
            for (t in model.trends) canvas.drawLine(x(t.start.date), y(t.start.value), x(t.end.date), y(t.end.value), band)
        }

        for (line in model.lines) {
            val path = Path()
            line.samples.forEachIndexed { i, s -> if (i == 0) path.moveTo(x(s.date), y(s.value)) else path.lineTo(x(s.date), y(s.value)) }
            // Not watched: only both ends are known, so draw it faint.
            val stroke = paint(if (line.watched) p.accent else Palette.withAlpha(p.accent, 0x5A), Paint.Style.STROKE).apply {
                strokeWidth = if (line.watched) style.lineWidth else style.lineWidth * 0.75f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            canvas.drawPath(path, stroke)
        }

        model.projection?.let { proj ->
            val dash = paint(Palette.withAlpha(p.accent, 0xCC), Paint.Style.STROKE).apply {
                strokeWidth = style.lineWidth * 0.75f
                strokeCap = Paint.Cap.ROUND
                pathEffect = DashPathEffect(floatArrayOf(4 * d, 3 * d), 0f)
            }
            val toY = y(min(proj.to.value, yMax))
            canvas.drawLine(x(proj.from.date), y(proj.from.value), x(proj.to.date), toY, dash)
        }

        // Now.
        val nowX = x(model.now)
        canvas.drawLine(nowX, plot.top, nowX, plot.bottom, paint(Palette.withAlpha(p.textSecondary, 0x60), Paint.Style.STROKE).apply { strokeWidth = max(1f, d) })

        model.current?.let { c ->
            val cx = x(c.date)
            val cy = y(c.value)
            canvas.drawCircle(cx, cy, style.lineWidth * 2.6f, paint(style.surface))
            canvas.drawCircle(cx, cy, style.lineWidth * 1.75f, paint(p.accent))
        }
    }

    // endregion
}
