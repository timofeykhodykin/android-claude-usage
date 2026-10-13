package dev.claudeusage.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.util.SizeF
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.claudeusage.Graph
import dev.claudeusage.R
import dev.claudeusage.data.BarColors
import dev.claudeusage.data.Metric
import dev.claudeusage.data.Show
import dev.claudeusage.data.TapAction
import dev.claudeusage.data.ThemeMode
import dev.claudeusage.data.WidgetConfig
import dev.claudeusage.data.WidgetPalette
import dev.claudeusage.widget.SampleData
import dev.claudeusage.widget.WidgetData
import dev.claudeusage.widget.WidgetKind
import dev.claudeusage.widget.WidgetRenderer
import dev.claudeusage.widget.Widgets
import kotlin.math.min
import kotlin.math.roundToInt

/** The real widget, rendered from its RemoteViews, on a wallpaper-like backdrop. Scaled down to fit if needed. */
@Composable
fun WidgetPreview(kind: WidgetKind, config: WidgetConfig, size: SizeF, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val usage by Graph.repository.state.collectAsStateWithLifecycle()
    val auth by Graph.tokens.state.collectAsStateWithLifecycle()
    val now by rememberNow(30_000)
    val data = remember(usage, auth, now) {
        if (usage.snapshot == null) SampleData.widgetData(now)
        else WidgetData(usage, auth, now, refreshing = false, backgroundMinutes = Graph.settings.value.backgroundMinutes)
    }
    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF8FA8B8), Color(0xFFC9B7A0), Color(0xFF6E7F73))))
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        val scale = min(1f, maxWidth.value / size.width)
        val shown = SizeF(size.width * scale, size.height * scale)
        AndroidView(
            factory = { FrameLayout(it) },
            update = { frame ->
                val views = WidgetRenderer(context, data).render(kind, config, shown)
                frame.removeAllViews()
                frame.addView(views.apply(context, frame), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            },
            modifier = Modifier.size(shown.width.dp, shown.height.dp),
        )
    }
}

/** All options of a widget's look; [kind] hides the ones that don't apply (null: defaults for any widget). */
@Composable
fun WidgetOptions(config: WidgetConfig, kind: WidgetKind?, onChange: (WidgetConfig) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (kind != WidgetKind.CHART) {
            Choice(
                stringResource(R.string.option_show), config.show,
                listOf(Show.SESSION to R.string.option_show_session, Show.WEEKLY to R.string.option_show_week, Show.BOTH to R.string.option_show_both),
            ) { onChange(config.copy(show = it)) }
        }
        if (kind == null || kind == WidgetKind.CHART || kind == WidgetKind.DASHBOARD) {
            Choice(
                stringResource(R.string.option_chart), config.chart,
                listOf(Metric.SESSION to R.string.chart_session, Metric.WEEKLY to R.string.chart_week),
            ) { onChange(config.copy(chart = it)) }
        }
        Choice(
            stringResource(R.string.option_theme), config.theme,
            listOf(ThemeMode.SYSTEM to R.string.theme_system, ThemeMode.LIGHT to R.string.theme_light, ThemeMode.DARK to R.string.theme_dark),
        ) { onChange(config.copy(theme = it)) }
        Choice(
            stringResource(R.string.option_palette), config.palette,
            listOf(WidgetPalette.CLAUDE to R.string.palette_claude, WidgetPalette.DYNAMIC to R.string.palette_dynamic),
        ) { onChange(config.copy(palette = it)) }
        Choice(
            stringResource(R.string.option_bars), config.barColors,
            listOf(BarColors.USAGE to R.string.bars_usage, BarColors.ACCENT to R.string.bars_accent),
        ) { onChange(config.copy(barColors = it)) }
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.option_opacity), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("${config.opacity}%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SoftSlider(config.opacity.toFloat(), 0f..100f) { onChange(config.copy(opacity = (it / 5).roundToInt() * 5)) }
        }
        Toggle(stringResource(R.string.option_resets), null, config.showResets) { onChange(config.copy(showResets = it)) }
        if (kind == null || kind == WidgetKind.BARS || kind == WidgetKind.DASHBOARD) {
            Toggle(stringResource(R.string.option_header), stringResource(R.string.option_header_hint), config.showHeader) {
                onChange(config.copy(showHeader = it))
            }
        }
        Choice(
            stringResource(R.string.option_tap), config.tapAction,
            listOf(TapAction.OPEN_APP to R.string.tap_open, TapAction.REFRESH to R.string.tap_refresh),
        ) { onChange(config.copy(tapAction = it)) }
    }
}

@Composable
fun <T> Choice(title: String, selected: T, options: List<Pair<T, Int>>, onSelect: (T) -> Unit) =
    ChipChoice(title, selected, options.map { it.first to stringResource(it.second) }, onSelect)

@Composable
fun Toggle(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

private class GalleryItem(val kind: WidgetKind, val title: Int, val text: Int, val size: SizeF)

/** Every widget with a live preview and a button that pins it to the home screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetGallery(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { AppWidgetManager.getInstance(context) }
    val canPin = remember { manager.isRequestPinAppWidgetSupported }
    val defaults = Graph.settings.value.widgetDefaults
    val items = listOf(
        GalleryItem(WidgetKind.RING, R.string.widget_ring_name, R.string.widget_ring_description, SizeF(150f, 150f)),
        GalleryItem(WidgetKind.BARS, R.string.widget_bars_name, R.string.widget_bars_description, SizeF(330f, 100f)),
        GalleryItem(WidgetKind.CHART, R.string.widget_chart_name, R.string.widget_chart_description, SizeF(330f, 190f)),
        GalleryItem(WidgetKind.DASHBOARD, R.string.widget_dashboard_name, R.string.widget_dashboard_description, SizeF(330f, 320f)),
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.gallery_title), style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp))
            Text(
                stringResource(if (canPin) R.string.gallery_hint else R.string.gallery_hint_manual),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (item in items) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    WidgetPreview(item.kind, defaults, item.size, Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(item.title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(item.text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (canPin) {
                            Spacer(Modifier.size(12.dp))
                            FilledTonalButton(onClick = {
                                manager.requestPinAppWidget(ComponentName(context, Widgets.providers.getValue(item.kind)), null, null)
                            }) { Text(stringResource(R.string.action_add)) }
                        }
                    }
                }
            }
            Box(Modifier.height(16.dp))
        }
    }
}

/** A calm slider: thin rounded track, round thumb (instead of Material 3's bar thumb with a gap). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoftSlider(value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val knob = MaterialTheme.colorScheme.surfaceContainerLowest
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        thumb = {
            Box(
                Modifier
                    .size(24.dp)
                    .shadow(3.dp, CircleShape)
                    .background(knob, CircleShape)
                    .padding(5.dp)
                    .background(accent, CircleShape)
            )
        },
        track = { state ->
            val fraction = ((state.value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
            Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(track, cornerRadius = r)
                drawRoundRect(accent, size = Size(maxOf(size.height, size.width * fraction), size.height), cornerRadius = r)
            }
        },
    )
}
