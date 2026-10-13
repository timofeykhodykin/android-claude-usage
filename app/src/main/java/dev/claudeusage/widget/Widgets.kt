package dev.claudeusage.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.widget.RemoteViews
import dev.claudeusage.Graph
import dev.claudeusage.work.Scheduler
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** All home-screen widgets: which exist, and redrawing them. */
object Widgets {
    private const val TAG = "Widgets"
    private val mutex = Mutex()

    /** Set while a refresh asked for from a widget is running, to show a spinner in the header. */
    @Volatile var refreshing = false

    val providers: Map<WidgetKind, Class<out AppWidgetProvider>> = linkedMapOf(
        WidgetKind.RING to RingWidget::class.java,
        WidgetKind.BARS to BarsWidget::class.java,
        WidgetKind.CHART to ChartWidget::class.java,
        WidgetKind.DASHBOARD to DashboardWidget::class.java,
    )

    fun ids(context: Context, kind: WidgetKind): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, providers.getValue(kind)))

    fun allIds(context: Context): List<Pair<WidgetKind, Int>> =
        providers.keys.flatMap { kind -> ids(context, kind).map { kind to it } }

    fun hasAny(context: Context): Boolean = providers.keys.any { ids(context, it).isNotEmpty() }

    fun kindOf(context: Context, id: Int): WidgetKind? {
        val provider = AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider ?: return null
        if (provider.packageName != context.packageName) return null
        return providers.entries.firstOrNull { it.value.name == provider.className }?.key
    }

    fun data(now: Long = System.currentTimeMillis()) = WidgetData(
        Graph.repository.state.value, Graph.tokens.state.value, now, refreshing, Graph.settings.value.backgroundMinutes,
    )

    /** Redraws every widget (or just [only]) from the current data. */
    suspend fun updateAll(context: Context, only: Collection<Int>? = null): Unit = mutex.withLock {
        val manager = AppWidgetManager.getInstance(context)
        val data = data()
        for ((kind, id) in allIds(context)) {
            if (only != null && id !in only) continue
            try {
                manager.updateAppWidget(id, build(context, kind, id, manager.getAppWidgetOptions(id), data))
            } catch (e: Exception) {
                // One broken widget must not keep the others stale.
                Log.e(TAG, "Couldn't update widget $id", e)
            }
        }
    }

    fun build(context: Context, kind: WidgetKind, id: Int, options: Bundle?, data: WidgetData): RemoteViews {
        val config = Graph.settings.widget(id)
        val renderer = WidgetRenderer(context, data)
        val sizes = sizes(context, kind, options)
        Log.d(TAG, "Widget $id ($kind): $sizes")
        if (sizes.size == 1) return renderer.render(kind, config, sizes.first())
        // One layout per size the launcher may show (portrait and landscape); it picks the right one itself.
        return RemoteViews(sizes.associateWith { renderer.render(kind, config, it) })
    }

    /** The sizes the launcher reports for this widget, in dp; at most two to keep bitmap memory in check. */
    private fun sizes(context: Context, kind: WidgetKind, options: Bundle?): List<SizeF> {
        @Suppress("DEPRECATION")
        val reported = options?.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            ?.filter { it.width > 0 && it.height > 0 }
            ?.distinctBy { it.width.toInt() to it.height.toInt() }
            .orEmpty()
        if (reported.isNotEmpty()) {
            val portrait = reported.minBy { it.width }
            val landscape = reported.maxBy { it.width }
            return if (portrait == landscape) listOf(portrait) else listOf(portrait, landscape)
        }
        val minW = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
        val maxH = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
        if (minW > 0 && maxH > 0) return listOf(SizeF(minW.toFloat(), maxH.toFloat()))
        return listOf(defaultSize(context, kind))
    }

    fun defaultSize(context: Context, kind: WidgetKind): SizeF {
        val d = context.resources.displayMetrics.density
        val info = AppWidgetManager.getInstance(context).installedProviders
            .firstOrNull { it.provider.className == providers.getValue(kind).name && it.provider.packageName == context.packageName }
        return if (info != null) SizeF(info.minWidth / d, info.minHeight / d) else when (kind) {
            WidgetKind.RING -> SizeF(100f, 100f)
            WidgetKind.BARS -> SizeF(300f, 80f)
            WidgetKind.CHART -> SizeF(300f, 170f)
            WidgetKind.DASHBOARD -> SizeF(300f, 260f)
        }
    }

    /**
     * Android 15+ shows generated previews in the widget picker: real widgets drawn with sample data. The system
     * rate-limits this, so it's done once per app version.
     */
    fun publishPreviews(context: Context) {
        if (Build.VERSION.SDK_INT < 35) return
        val prefs = context.getSharedPreferences("widgets_meta", Context.MODE_PRIVATE)
        val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        if (prefs.getLong("previewVersion", 0) == version) return
        val manager = AppWidgetManager.getInstance(context)
        val sample = SampleData.widgetData()
        try {
            for ((kind, cls) in providers) {
                val views = WidgetRenderer(context, sample).render(kind, Graph.settings.value.widgetDefaults, defaultSize(context, kind))
                manager.setWidgetPreview(ComponentName(context, cls), AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN, views)
            }
            prefs.edit().putLong("previewVersion", version).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't publish widget previews: ${e.message}")
        }
    }
}

/** Shared behaviour of the four widget providers. */
abstract class UsageWidgetProvider(private val kind: WidgetKind) : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        Graph.appScope.launch {
            try {
                Widgets.updateAll(context, ids.toList())
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        // Resized: redraw at the new size.
        val pending = goAsync()
        Graph.appScope.launch {
            try {
                Widgets.updateAll(context, listOf(id))
            } finally {
                pending.finish()
            }
        }
    }

    override fun onEnabled(context: Context) {
        Scheduler.ensure(context)
    }

    override fun onDisabled(context: Context) {
        Scheduler.ensure(context)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        for (id in ids) Graph.settings.removeWidget(id)
    }

    override fun onRestored(context: Context, oldIds: IntArray, newIds: IntArray) {
        for ((old, new) in oldIds.zip(newIds)) {
            Graph.settings.setWidget(new, Graph.settings.widget(old))
            Graph.settings.removeWidget(old)
        }
    }
}

class RingWidget : UsageWidgetProvider(WidgetKind.RING)
class BarsWidget : UsageWidgetProvider(WidgetKind.BARS)
class ChartWidget : UsageWidgetProvider(WidgetKind.CHART)
class DashboardWidget : UsageWidgetProvider(WidgetKind.DASHBOARD)

/**
 * The refresh button. Not exported: only our own PendingIntents reach it, so other apps can't make us hammer
 * the rate-limited API.
 */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REFRESH) return
        val pending = goAsync()
        Graph.appScope.launch {
            try {
                Widgets.refreshing = true
                Widgets.updateAll(context)
                Scheduler.refreshNow(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "dev.claudeusage.action.REFRESH"
    }
}
