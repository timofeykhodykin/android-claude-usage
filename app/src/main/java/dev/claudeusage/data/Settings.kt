package dev.claudeusage.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** What a widget shows where it has room for one or both limits. */
enum class Show { SESSION, WEEKLY, BOTH }

enum class WidgetPalette {
    /** Claude's ivory / warm charcoal with terracotta. */
    CLAUDE,
    /** The system's wallpaper colours (Material You). */
    DYNAMIC,
}

enum class BarColors {
    /** Pastel green → yellow → peach → rose as usage grows. */
    USAGE,
    /** Always the accent colour. */
    ACCENT,
}

enum class TapAction { OPEN_APP, REFRESH }

@Serializable
data class WidgetConfig(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val palette: WidgetPalette = WidgetPalette.CLAUDE,
    val barColors: BarColors = BarColors.USAGE,
    /** Background opacity, 0…100. */
    val opacity: Int = 100,
    val show: Show = Show.BOTH,
    val chart: Metric = Metric.SESSION,
    val showResets: Boolean = true,
    val showHeader: Boolean = true,
    val tapAction: TapAction = TapAction.OPEN_APP,
)

@Serializable
data class AppSettings(
    /** Minutes between background refreshes (widgets, notifications). */
    val backgroundMinutes: Int = 5,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val chartMetric: Metric = Metric.SESSION,
    /** Notify when the 5-hour limit resets after it was used. */
    val notifyReset: Boolean = false,
    /** Notify when the session reaches this percentage; 0 = off. */
    val notifyThreshold: Int = 0,
    /** Style for newly added widgets. */
    val widgetDefaults: WidgetConfig = WidgetConfig(),
) {
    companion object {
        val backgroundChoices = listOf(5, 10, 15, 30, 60)
    }
}

/** App settings and per-widget configuration, in SharedPreferences as JSON. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val widgetPrefs = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; coerceInputValues = true }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val value: AppSettings get() = _state.value

    private fun load(): AppSettings = prefs.getString(KEY, null)
        ?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() }
        ?: AppSettings()

    @Synchronized
    fun update(change: (AppSettings) -> AppSettings) {
        val next = change(_state.value)
        if (next == _state.value) return
        prefs.edit().putString(KEY, json.encodeToString(AppSettings.serializer(), next)).apply()
        _state.value = next
    }

    fun widget(id: Int): WidgetConfig = widgetPrefs.getString("w_$id", null)
        ?.let { runCatching { json.decodeFromString(WidgetConfig.serializer(), it) }.getOrNull() }
        ?: value.widgetDefaults

    fun hasWidgetConfig(id: Int): Boolean = widgetPrefs.contains("w_$id")

    fun setWidget(id: Int, config: WidgetConfig) {
        widgetPrefs.edit().putString("w_$id", json.encodeToString(WidgetConfig.serializer(), config)).apply()
    }

    fun removeWidget(id: Int) {
        widgetPrefs.edit().remove("w_$id").apply()
    }

    private companion object {
        const val KEY = "app"
    }
}
