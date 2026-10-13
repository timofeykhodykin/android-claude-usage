package dev.claudeusage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.claudeusage.auth.AuthState
import dev.claudeusage.data.MINUTE
import dev.claudeusage.data.SECOND
import dev.claudeusage.ui.HomeScreen
import dev.claudeusage.ui.SettingsScreen
import dev.claudeusage.ui.SignInScreen
import dev.claudeusage.ui.theme.ClaudeUsageTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by Graph.settings.state.collectAsStateWithLifecycle()
            ClaudeUsageTheme(settings.theme, settings.dynamicColor) {
                AppContent()
            }
        }
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("demo", false) == true) {
            Graph.tokens.fakeSignIn()
            lifecycleScope.launch { Graph.repository.loadDemo(dev.claudeusage.widget.SampleData.state()) }
        }
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("sheet", false) == true) {
            val base = Graph.settings.value.widgetDefaults
            dev.claudeusage.widget.DebugSheet.previews(this)
            dev.claudeusage.widget.DebugSheet.render(this, "claude", base)
            dev.claudeusage.widget.DebugSheet.render(this, "dynamic", base.copy(palette = dev.claudeusage.data.WidgetPalette.DYNAMIC, barColors = dev.claudeusage.data.BarColors.ACCENT, opacity = 85))
            dev.claudeusage.widget.DebugSheet.render(this, "session", base.copy(show = dev.claudeusage.data.Show.SESSION, chart = dev.claudeusage.data.Metric.WEEKLY, showHeader = false))
        }
        if (BuildConfig.DEBUG) intent?.getStringExtra("pin")?.let { kind ->
            val provider = dev.claudeusage.widget.Widgets.providers.getValue(dev.claudeusage.widget.WidgetKind.valueOf(kind))
            android.appwidget.AppWidgetManager.getInstance(this).requestPinAppWidget(android.content.ComponentName(this, provider), null, null)
        }
        // While the app is on screen, keep the numbers current: refresh when they're older than a minute.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    Graph.repository.refresh(maxAge = FOREGROUND_MAX_AGE)
                    delay(FOREGROUND_MAX_AGE + 2 * SECOND)
                }
            }
        }
    }

    companion object {
        const val FOREGROUND_MAX_AGE = MINUTE
    }
}

private enum class Screen { HOME, SETTINGS }

@Composable
private fun AppContent() {
    val auth by Graph.tokens.state.collectAsStateWithLifecycle()
    val usage by Graph.repository.state.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }

    // Signed out with nothing to show: the welcome screen. An expired login keeps the last numbers visible,
    // with a banner to sign in again.
    val showSignIn = auth is AuthState.SignedOut && usage.snapshot == null
    LaunchedEffect(showSignIn) { if (showSignIn) screen = Screen.HOME }

    BackHandler(enabled = screen == Screen.SETTINGS) { screen = Screen.HOME }
    AnimatedContent(
        targetState = if (showSignIn) null else screen,
        transitionSpec = {
            if (targetState == Screen.SETTINGS) {
                (slideInHorizontally { it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 8 } + fadeOut())
            } else {
                (slideInHorizontally { -it / 8 } + fadeIn()) togetherWith (slideOutHorizontally { it / 4 } + fadeOut())
            }
        },
        label = "screen",
    ) { target ->
        when (target) {
            null -> SignInScreen()
            Screen.HOME -> HomeScreen(onOpenSettings = { screen = Screen.SETTINGS })
            Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.HOME })
        }
    }
}
