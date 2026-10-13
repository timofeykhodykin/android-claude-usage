package dev.claudeusage.ui

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.claudeusage.Graph
import dev.claudeusage.R
import dev.claudeusage.auth.AuthState
import dev.claudeusage.data.ErrorKind
import dev.claudeusage.data.Format
import dev.claudeusage.data.Metric
import dev.claudeusage.data.SECOND
import dev.claudeusage.data.SessionsEstimate
import dev.claudeusage.data.UsageChartModel
import dev.claudeusage.data.UsageState
import dev.claudeusage.data.UsageWindow
import dev.claudeusage.ui.theme.NumberStyle
import dev.claudeusage.widget.Widgets
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenSettings: () -> Unit) {
    val state by Graph.repository.state.collectAsStateWithLifecycle()
    val auth by Graph.tokens.state.collectAsStateWithLifecycle()
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    // Cards and the chart move on twice a minute; only the "updated … ago" line ticks every second.
    val now by rememberNow(30 * SECOND)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val texts = remember(context) { Texts(context) }
    var showGallery by remember { mutableStateOf(false) }
    val refresh: () -> Unit = { scope.launch { Graph.repository.refresh(maxAge = 0, manual = true) } }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = refresh,
            modifier = Modifier.fillMaxSize().systemBarsPadding(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TopBar(state, onOpenSettings, refresh)
                    StatusBanner(state, auth, texts, now)
                    LimitsCard(state, now, texts)
                    val snapshot = state.snapshot
                    if (snapshot != null && (snapshot.extras.isNotEmpty() || snapshot.extraUsage != null)) OtherLimitsCard(state, now, texts)
                    ChartCard(state, settings.chartMetric, now, texts)
                    WidgetsRow(onAdd = { showGallery = true })
                    UpdatedFooter(state, texts)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
    if (showGallery) WidgetGallery(onDismiss = { showGallery = false })
}

@Composable
private fun TopBar(state: UsageState, onOpenSettings: () -> Unit, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        AppMark(size = 28, animate = state.refreshing)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp))
        state.profile?.plan?.let { plan ->
            Spacer(Modifier.width(8.dp))
            Text(
                plan,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 9.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onRefresh, enabled = !state.refreshing) {
            val rotation = if (state.refreshing) {
                rememberInfiniteTransition(label = "refresh").animateFloat(
                    0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spin",
                ).value
            } else 0f
            Icon(Icons.Outlined.Refresh, stringResource(R.string.action_refresh), Modifier.size(22.dp).rotate(rotation))
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Outlined.Settings, stringResource(R.string.settings_title), Modifier.size(22.dp))
        }
    }
}

@Composable
private fun StatusBanner(state: UsageState, auth: AuthState, texts: Texts, now: Long) {
    val expired = auth is AuthState.SignedOut
    val error = state.error
    val visible = expired || (error != null && (error != ErrorKind.RATE_LIMITED || state.dataAge(now) > 5 * 60 * SECOND))
    AnimatedVisibility(visible) {
        val activity = LocalContext.current as Activity
        val (icon: ImageVector, text: String) = when {
            expired -> Icons.Outlined.ErrorOutline to stringResource(R.string.banner_signed_out)
            error == ErrorKind.NETWORK -> Icons.Outlined.WifiOff to texts.error(error, state.errorStatus, state.backoffUntil, now)
            error == ErrorKind.RATE_LIMITED -> Icons.Outlined.HourglassTop to texts.error(error, state.errorStatus, state.backoffUntil, now)
            else -> Icons.Outlined.ErrorOutline to (error?.let { texts.error(it, state.errorStatus, state.backoffUntil, now) } ?: "")
        }
        val serious = expired || error == ErrorKind.FORBIDDEN || error == ErrorKind.SIGNED_OUT
        val fg = if (serious) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(if (serious) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = fg, modifier = Modifier.weight(1f))
            if (expired) {
                TextButton(onClick = { Graph.signIn.start(activity) }) { Text(stringResource(R.string.action_sign_in)) }
            }
        }
    }
}

/** Both limits in one card: name and percentage, the bar, when it resets. */
@Composable
private fun LimitsCard(state: UsageState, now: Long, texts: Texts) {
    var showHelp by remember { mutableStateOf(false) }
    val estimate = state.sessionsEstimate(now)
    AppCard(Modifier.animateContentSize()) {
        LimitRow(Metric.SESSION, state.snapshot?.session, now, texts)
        Spacer(Modifier.height(16.dp))
        LimitRow(Metric.WEEKLY, state.snapshot?.weekly, now, texts)
        if (state.snapshot?.weekly != null) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { showHelp = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(texts.sessions(estimate), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Outlined.Info, stringResource(R.string.sessions_help_title), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (showHelp) SessionsHelpDialog(estimate, texts) { showHelp = false }
}

@Composable
private fun LimitRow(metric: Metric, window: UsageWindow?, now: Long, texts: Texts) {
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(texts.metricTitle(metric), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(if (metric == Metric.SESSION) R.string.session_subtitle else R.string.weekly_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(bottom = 1.dp),
            )
            Text(texts.percent(window, now), style = NumberStyle)
        }
        Spacer(Modifier.height(6.dp))
        UsageBar((window?.current(now) ?: 0.0) / 100, height = 8.dp)
        Spacer(Modifier.height(4.dp))
        Row {
            Text(
                texts.resetRelative(window, now),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            window?.resetsAt?.takeIf { it > now }?.let {
                Text(texts.day(it, now), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SessionsHelpDialog(estimate: SessionsEstimate?, texts: Texts, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lines = buildList {
        add(context.getString(R.string.sessions_help_what))
        if (estimate == null) {
            add(context.getString(R.string.sessions_help_learning))
        } else {
            val since = texts.day(estimate.since, System.currentTimeMillis())
            add(context.resources.getQuantityString(R.plurals.sessions_help_learned, estimate.sessions, estimate.sessions, since))
            if (estimate.relativeError > 0.5) {
                fun rough(b: SessionsEstimate.Bounds) = b.high?.let { "${Format.sessions(b.low)}–${Format.sessions(it)}" }
                    ?: context.getString(R.string.sessions_at_least, Format.sessions(b.low))
                add(context.getString(R.string.sessions_help_rough, rough(estimate.remaining), rough(estimate.perWeek)))
            }
            estimate.limitsChangedAt?.let { add(context.getString(R.string.sessions_help_changed, texts.day(it, System.currentTimeMillis()))) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
        title = { Text(stringResource(R.string.sessions_help_title)) },
        text = { Text(lines.joinToString("\n\n")) },
    )
}

@Composable
private fun OtherLimitsCard(state: UsageState, now: Long, texts: Texts) {
    val snapshot = state.snapshot ?: return
    AppCard(title = stringResource(R.string.other_limits)) {
        snapshot.extras.forEachIndexed { i, extra ->
            if (i > 0) Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(stringResource(R.string.model_weekly, extra.name), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(texts.percent(extra.window, now), style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"))
            }
            Spacer(Modifier.height(4.dp))
            UsageBar(extra.window.current(now) / 100, height = 6.dp)
            Text(texts.resetAbsolute(extra.window, now), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        snapshot.extraUsage?.let { extra ->
            if (snapshot.extras.isNotEmpty()) Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(stringResource(R.string.extra_usage), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                extra.utilization?.let { Text("${Format.percent(it)}%", style = MaterialTheme.typography.titleSmall) }
            }
            extra.utilization?.let {
                Spacer(Modifier.height(4.dp))
                UsageBar(it / 100, height = 6.dp)
            }
        }
    }
}

@Composable
private fun ChartCard(state: UsageState, metric: Metric, now: Long, texts: Texts) {
    var readout by remember { mutableStateOf<String?>(null) }
    var showLegend by remember { mutableStateOf(false) }
    AppCard(
        title = stringResource(R.string.chart_title),
        header = {
            Icon(
                Icons.Outlined.Info, stringResource(R.string.chart_legend_title),
                Modifier.size(18.dp).clip(RoundedCornerShape(50)).clickable { showLegend = true },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            for (m in Metric.entries) {
                if (m == Metric.WEEKLY) Spacer(Modifier.width(6.dp))
                Chip(
                    stringResource(if (m == Metric.SESSION) R.string.chart_session else R.string.chart_week),
                    selected = metric == m, compact = true,
                ) { Graph.settings.update { it.copy(chartMetric = m) } }
            }
        },
    ) {
        val window = state.snapshot?.window(metric)
        val model = remember(state.history, window, metric, now) {
            UsageChartModel.make(state.history, metric, window?.resetsAt, now)
        }
        if (model != null) {
            UsageChart(model, Modifier.height(180.dp)) { readout = it }
            Spacer(Modifier.height(6.dp))
            Text(
                readout ?: texts.caption(model.caption) ?: " ",
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = if (readout != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(if (window == null) R.string.no_data_yet else R.string.chart_not_started),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
    if (showLegend) {
        AlertDialog(
            onDismissRequest = { showLegend = false },
            confirmButton = { TextButton(onClick = { showLegend = false }) { Text(stringResource(R.string.action_ok)) } },
            title = { Text(stringResource(R.string.chart_legend_title)) },
            text = { Text(stringResource(if (metric == Metric.SESSION) R.string.chart_legend_session else R.string.chart_legend_week)) },
        )
    }
}

/** A list row like borderless's settings rows: tinted icon tile, title and subtitle, action. */
@Composable
private fun WidgetsRow(onAdd: () -> Unit) {
    val context = LocalContext.current
    val count = remember { Widgets.allIds(context).size }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onAdd)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Widgets, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.widgets_title), style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp))
            Text(
                if (count == 0) stringResource(R.string.widgets_none)
                else context.resources.getQuantityString(R.plurals.widgets_count, count, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Chip(stringResource(R.string.action_add), selected = true, compact = true, onClick = onAdd)
    }
}

@Composable
private fun UpdatedFooter(state: UsageState, texts: Texts) {
    val now by rememberNow(SECOND)
    val source = stringResource(R.string.source_api)
    Text(
        texts.updated(state.snapshot?.fetchedAt, now) + if (state.snapshot != null) " · $source" else "",
        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
    )
}
