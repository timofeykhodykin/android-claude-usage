package dev.claudeusage.ui

import android.text.format.DateFormat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.claudeusage.data.BarColors
import dev.claudeusage.data.UsageChartModel
import dev.claudeusage.ui.theme.LocalPalette
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date

/** The current time, ticking every [periodMs] on the boundary (so all clocks on screen agree). */
@Composable
fun rememberNow(periodMs: Long): State<Long> = produceState(System.currentTimeMillis(), periodMs) {
    while (true) {
        val now = System.currentTimeMillis()
        delay(periodMs - now % periodMs + 5)
        value = System.currentTimeMillis()
    }
}

/**
 * A flat card like borderless's: warm fill, 20dp corners, 16dp padding, and an optional serif title on the first
 * line with [header] items (chips, an info button) aligned to its right.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    header: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp),
    ) {
        if (title != null || header != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 24.dp)) {
                // Optically centred on the items beside it: line box trimmed to the glyphs, nudged to the cap height.
                Text(
                    title.orEmpty(),
                    style = MaterialTheme.typography.titleMedium.copy(
                        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                    ),
                    modifier = Modifier.weight(1f).offset(y = (-1).dp),
                )
                header?.invoke(this)
            }
            Spacer(Modifier.height(10.dp))
        }
        content()
    }
}

/** A filled pill, borderless-style; [onCard] uses the stronger fill so it shows on a card. */
@Composable
fun Chip(label: String, selected: Boolean, onCard: Boolean = true, compact: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) colors.primaryContainer else if (onCard) colors.surfaceContainerHighest else colors.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 11.dp else 14.dp, vertical = if (compact) 5.dp else 8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = if (compact) 13.sp else 13.5.sp, lineHeight = 17.sp),
            color = if (selected) colors.onPrimaryContainer else colors.onSurface,
            maxLines = 1,
        )
    }
}

/** A labelled single choice as a row of pills. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipChoice(title: String?, selected: T, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    Column {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((value, label) in options) Chip(label, selected == value) { onSelect(value) }
        }
    }
}

/** A capsule progress bar drawn like the widgets' (same colours and shape), animated. */
@Composable
fun UsageBar(
    fraction: Double,
    modifier: Modifier = Modifier,
    height: Dp = 12.dp,
    marker: Double? = null,
    colors: BarColors = BarColors.USAGE,
) {
    val palette = LocalPalette.current
    val animated by animateFloatAsState(fraction.toFloat().coerceIn(0f, 1f), tween(600), label = "bar")
    Canvas(modifier.fillMaxWidth().height(height + 6.dp)) {
        val barHeight = height.toPx()
        drawIntoCanvas { canvas ->
            Drawing.drawBar(
                canvas.nativeCanvas, 0f, (size.height - barHeight) / 2, size.width, barHeight,
                animated.toDouble(), palette.barColor(animated.toDouble(), colors), palette.track,
                marker, Palette.withAlpha(palette.textSecondary, 0xB0),
            )
        }
    }
}

/**
 * The usage chart, drawn by the same code as the widgets'. Drag across it (or tap) for the value and pace at
 * that moment; [onReadout] receives the text while a finger is on it.
 */
@Composable
fun UsageChart(model: UsageChartModel, modifier: Modifier = Modifier, onReadout: (String?) -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val texts = remember(context) { Texts(context) }
    val locale = context.resources.configuration.locales[0]
    val hourFormat = remember(locale) { SimpleDateFormat(if (DateFormat.is24HourFormat(context)) "H" else "ha", locale) }
    val dayFormat = remember(locale) { SimpleDateFormat("EEE", locale) }
    val style = remember(palette, density) {
        Drawing.ChartStyle(
            density = density, palette = palette,
            labelSize = 11.5f * density, lineWidth = 2.2f * density, typeface = Drawing.typeface(context),
            hourLabel = { hourFormat.format(Date(it)).lowercase() },
            dayLabel = { dayFormat.format(Date(it)) },
        )
    }
    var scrubX by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableStateOf(1f) }
    var heightPx by remember { mutableStateOf(1f) }

    fun dateAt(x: Float): Long {
        val plot = Drawing.plotRect(widthPx, heightPx, style)
        val f = ((x - plot.left) / plot.width()).coerceIn(0f, 1f)
        return model.windowStart + (f * model.duration).toLong()
    }

    fun readout(date: Long): String? {
        val time = if (model.metric == dev.claudeusage.data.Metric.SESSION) texts.time(date) else texts.day(date, model.now)
        model.valueAt(date)?.let { value ->
            val pace = model.trendAt(date)?.let { " · " + texts.rate(it.perHour) }.orEmpty()
            return "$time · ${dev.claudeusage.data.Format.percent(value)}%$pace"
        }
        model.projectedAt(date)?.let { value ->
            return context.getString(dev.claudeusage.R.string.chart_readout_projected, time, dev.claudeusage.data.Format.percent(value))
        }
        return null
    }

    LaunchedEffect(scrubX, model) { onReadout(scrubX?.let { readout(dateAt(it)) }) }

    Canvas(
        modifier
            .fillMaxWidth()
            .semantics { contentDescription = texts.caption(model.caption) ?: texts.metricTitle(model.metric) }
            .pointerInput(model) {
                detectHorizontalDragGestures(
                    onDragStart = { scrubX = it.x },
                    onDragEnd = { scrubX = null },
                    onDragCancel = { scrubX = null },
                    onHorizontalDrag = { change, _ -> scrubX = change.position.x },
                )
            }
            .pointerInput(model) {
                detectTapGestures(onPress = {
                    scrubX = it.x
                    tryAwaitRelease()
                    scrubX = null
                })
            }
    ) {
        widthPx = size.width
        heightPx = size.height
        drawIntoCanvas { canvas -> Drawing.drawChart(canvas.nativeCanvas, size.width, size.height, model, style) }
        scrubX?.let { x ->
            val date = dateAt(x)
            val plot = Drawing.plotRect(size.width, size.height, style)
            val px = plot.left + (date - model.windowStart).toFloat() / model.duration * plot.width()
            drawLine(
                androidx.compose.ui.graphics.Color(Palette.withAlpha(palette.text, 0x55)),
                androidx.compose.ui.geometry.Offset(px, plot.top), androidx.compose.ui.geometry.Offset(px, plot.bottom),
                strokeWidth = 1.dp.toPx(),
            )
            val value = model.valueAt(date) ?: model.projectedAt(date)
            if (value != null) {
                val yMax = maxOf(100.0, model.samples.maxOfOrNull { it.value } ?: 0.0)
                val py = plot.bottom - (value / yMax).toFloat() * plot.height()
                drawCircle(androidx.compose.ui.graphics.Color(palette.background), 6.dp.toPx(), androidx.compose.ui.geometry.Offset(px, py))
                drawCircle(androidx.compose.ui.graphics.Color(palette.accent), 4.dp.toPx(), androidx.compose.ui.geometry.Offset(px, py))
            }
        }
    }
}

/** A heading between cards (serif, like borderless's section titles). */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp),
        modifier = modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp),
    )
}
