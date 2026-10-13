package dev.claudeusage.ui

import android.content.Context
import android.graphics.Color
import dev.claudeusage.data.BarColors
import dev.claudeusage.data.WidgetPalette

/** Colours for one look (light or dark) of the widgets, the chart and the bars, as ARGB ints. */
data class Palette(
    val dark: Boolean,
    val background: Int,
    val text: Int,
    val textSecondary: Int,
    val track: Int,
    val grid: Int,
    val accent: Int,
    val warning: Int,
    val error: Int,
    /** Usage scale from 0% to 100%, evenly spaced. */
    val scale: IntArray,
) {
    fun barColor(fraction: Double, mode: BarColors): Int =
        if (mode == BarColors.ACCENT) accent else colorAt(scale, fraction)

    override fun equals(other: Any?): Boolean = other is Palette && other.dark == dark && other.background == background &&
        other.accent == accent && other.text == text && other.scale.contentEquals(scale)

    override fun hashCode(): Int = background * 31 + accent

    companion object {
        /**
         * Pastel green → yellow → peach → rose (the macOS app's scale), picked at a similar lightness and low
         * chroma so no end looks louder than the others; for dark surfaces.
         */
        private val pastel = intArrayOf(0xFFB0E3BD.toInt(), 0xFFE8D996.toInt(), 0xFFF5BD94.toInt(), 0xFFF2A6A6.toInt())

        /** The same hues a step deeper, so they keep contrast on light surfaces. */
        private val deeper = intArrayOf(0xFF86CC9A.toInt(), 0xFFEBC764.toInt(), 0xFFF0A372.toInt(), 0xFFE5807F.toInt())

        val claudeLight = Palette(
            dark = false,
            background = 0xFFFAF9F5.toInt(),
            text = 0xFF1F1E1D.toInt(),
            textSecondary = 0xFF6B6A64.toInt(),
            track = 0x1A1F1E1D,
            grid = 0x171F1E1D,
            accent = 0xFFC96442.toInt(),
            warning = 0xFFB0731A.toInt(),
            error = 0xFFC2453A.toInt(),
            scale = deeper,
        )

        val claudeDark = Palette(
            dark = true,
            background = 0xFF262624.toInt(),
            text = 0xFFF5F4EE.toInt(),
            textSecondary = 0xFFA6A39A.toInt(),
            track = 0x24FFFFFF,
            grid = 0x1CFFFFFF,
            accent = 0xFFE08A6D.toInt(),
            warning = 0xFFE3B256.toInt(),
            error = 0xFFF08A80.toInt(),
            scale = pastel,
        )

        /** Material You: the system's wallpaper-based tones (Android 12+). */
        fun dynamic(context: Context, dark: Boolean): Palette {
            fun c(id: Int) = context.getColor(id)
            return if (dark) Palette(
                dark = true,
                background = c(android.R.color.system_neutral1_900),
                text = c(android.R.color.system_neutral1_50),
                textSecondary = c(android.R.color.system_neutral2_200),
                track = withAlpha(c(android.R.color.system_neutral1_50), 0x24),
                grid = withAlpha(c(android.R.color.system_neutral1_50), 0x1C),
                accent = c(android.R.color.system_accent1_200),
                warning = 0xFFE3B256.toInt(),
                error = 0xFFF08A80.toInt(),
                scale = pastel,
            ) else Palette(
                dark = false,
                background = c(android.R.color.system_neutral1_10),
                text = c(android.R.color.system_neutral1_900),
                textSecondary = c(android.R.color.system_neutral2_700),
                track = withAlpha(c(android.R.color.system_neutral1_900), 0x1A),
                grid = withAlpha(c(android.R.color.system_neutral1_900), 0x17),
                accent = c(android.R.color.system_accent1_600),
                warning = 0xFFB0731A.toInt(),
                error = 0xFFC2453A.toInt(),
                scale = deeper,
            )
        }

        fun of(context: Context, palette: WidgetPalette, dark: Boolean): Palette = when (palette) {
            WidgetPalette.CLAUDE -> if (dark) claudeDark else claudeLight
            WidgetPalette.DYNAMIC -> dynamic(context, dark)
        }

        fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

        /** The solid colour at [fraction] of a scale of evenly spaced stops. */
        fun colorAt(stops: IntArray, fraction: Double): Int {
            val t = fraction.coerceIn(0.0, 1.0) * (stops.size - 1)
            val i = minOf(t.toInt(), stops.size - 2)
            val f = (t - i).toFloat()
            val a = stops[i]
            val b = stops[i + 1]
            fun mix(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * f).toInt()
            return Color.argb(255, mix(16), mix(8), mix(0))
        }
    }
}
