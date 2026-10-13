package dev.claudeusage.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.SizeF
import android.view.View
import android.widget.FrameLayout
import dev.claudeusage.BuildConfig
import dev.claudeusage.data.WidgetConfig
import java.io.File
import kotlin.math.roundToInt

/**
 * Debug builds only: draws every widget at many sizes, in light and dark, into one PNG
 * (`adb shell run-as dev.claudeusage cat cache/sheet-<name>.png > sheet.png`), to check layouts without resizing
 * widgets by hand.
 */
object DebugSheet {
    private val sizes = mapOf(
        WidgetKind.RING to listOf(SizeF(76f, 76f), SizeF(110f, 110f), SizeF(184f, 240f), SizeF(150f, 90f)),
        WidgetKind.BARS to listOf(SizeF(160f, 56f), SizeF(250f, 60f), SizeF(384f, 112f), SizeF(384f, 200f), SizeF(180f, 180f)),
        WidgetKind.CHART to listOf(SizeF(170f, 140f), SizeF(384f, 160f), SizeF(384f, 240f)),
        WidgetKind.DASHBOARD to listOf(SizeF(280f, 200f), SizeF(384f, 369f)),
    )

    fun render(context: Context, name: String, config: WidgetConfig, data: WidgetData = SampleData.widgetData()): File {
        check(BuildConfig.DEBUG)
        val d = context.resources.displayMetrics.density
        val gap = (12 * d).roundToInt()
        val looks = listOf(false, true)
        val rows = sizes.values.flatten()
        val width = looks.size * (rows.maxOf { it.width } * d).roundToInt() + (looks.size + 1) * gap
        val height = rows.sumOf { (it.height * d).roundToInt() + gap } + gap
        val sheet = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.rgb(120, 136, 150))
        looks.forEachIndexed { col, dark ->
            val configuration = Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val themed = context.createConfigurationContext(configuration)
            var y = gap
            val x = gap + col * ((rows.maxOf { it.width } * d).roundToInt() + gap)
            for ((kind, list) in sizes) for (size in list) {
                val views = WidgetRenderer(themed, data).render(kind, config, size)
                val frame = FrameLayout(themed)
                val view = views.apply(themed, frame)
                val w = (size.width * d).roundToInt()
                val h = (size.height * d).roundToInt()
                view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, w, h)
                canvas.save()
                canvas.translate(x.toFloat(), y.toFloat())
                canvas.clipRect(0, 0, w, h)
                view.draw(canvas)
                canvas.restore()
                canvas.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(),
                    Paint().apply { style = Paint.Style.STROKE; color = Color.argb(60, 255, 0, 0) })
                y += h + gap
            }
        }
        val file = File(context.cacheDir, "sheet-$name.png")
        file.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    /** Picker previews: each widget at its default size, light and dark, on transparent. */
    fun previews(context: Context) {
        check(BuildConfig.DEBUG)
        val d = context.resources.displayMetrics.density
        val defaults = mapOf(
            WidgetKind.RING to SizeF(150f, 150f), WidgetKind.BARS to SizeF(340f, 96f),
            WidgetKind.CHART to SizeF(340f, 190f), WidgetKind.DASHBOARD to SizeF(340f, 300f),
        )
        for (dark in listOf(false, true)) {
            val configuration = Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val themed = context.createConfigurationContext(configuration)
            for ((kind, size) in defaults) {
                val views = WidgetRenderer(themed, SampleData.widgetData()).render(kind, WidgetConfig(), size)
                val view = views.apply(themed, FrameLayout(themed))
                val w = (size.width * d).roundToInt()
                val h = (size.height * d).roundToInt()
                view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, w, h)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                // Round the corners like a launcher would.
                val path = android.graphics.Path().apply {
                    addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), 22 * d, 22 * d, android.graphics.Path.Direction.CW)
                }
                canvas.clipPath(path)
                view.draw(canvas)
                File(context.cacheDir, "preview_${kind.name.lowercase()}${if (dark) "_dark" else ""}.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }
}
