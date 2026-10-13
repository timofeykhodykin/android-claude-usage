package dev.claudeusage.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.util.SizeF
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.claudeusage.Graph
import dev.claudeusage.R
import dev.claudeusage.data.WidgetConfig
import dev.claudeusage.ui.AppCard
import dev.claudeusage.ui.WidgetOptions
import dev.claudeusage.ui.WidgetPreview
import dev.claudeusage.ui.theme.ClaudeUsageTheme
import dev.claudeusage.work.Scheduler
import kotlinx.coroutines.launch

/**
 * Customizing one widget: opened by the launcher (long-press → Settings / Customize), with a live preview of
 * that widget at its real size. Adding a widget doesn't require it (`configuration_optional`): new widgets
 * start with the defaults from the app's settings.
 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        // Only our own widgets: the activity is exported for the launcher, so check what the id is.
        val kind = Widgets.kindOf(this, id)
        if (kind == null) {
            finish()
            return
        }
        val options = AppWidgetManager.getInstance(this).getAppWidgetOptions(id)
        val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        val size = if (minW > 0 && maxH > 0) SizeF(minW.toFloat(), maxH.toFloat()) else Widgets.defaultSize(this, kind)

        setContent {
            val settings by Graph.settings.state.collectAsStateWithLifecycle()
            ClaudeUsageTheme(settings.theme, settings.dynamicColor) {
                ConfigScreen(kind, size, Graph.settings.widget(id),
                    onSave = { config ->
                        Graph.settings.setWidget(id, config)
                        lifecycleScope.launch {
                            Widgets.updateAll(this@WidgetConfigActivity, listOf(id))
                            Scheduler.ensure(this@WidgetConfigActivity)
                            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                            finish()
                        }
                    },
                    onMakeDefault = { config -> Graph.settings.update { it.copy(widgetDefaults = config) } },
                )
            }
        }
    }
}

@Composable
private fun ConfigScreen(
    kind: WidgetKind, size: SizeF, initial: WidgetConfig,
    onSave: (WidgetConfig) -> Unit, onMakeDefault: (WidgetConfig) -> Unit,
) {
    var config by remember { mutableStateOf(initial) }
    var madeDefault by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.widget_config_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp, start = 4.dp),
                )
                WidgetPreview(kind, config, size, Modifier.fillMaxWidth())
                AppCard(Modifier.widthIn(max = 560.dp)) {
                    WidgetOptions(config, kind) { config = it; madeDefault = false }
                }
                TextButton(onClick = { onMakeDefault(config); madeDefault = true }, enabled = !madeDefault) {
                    Text(stringResource(if (madeDefault) R.string.widget_config_default_done else R.string.widget_config_make_default))
                }
            }
            Row(Modifier.padding(16.dp)) {
                Button(
                    onClick = { onSave(config) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) { Text(stringResource(R.string.action_save), style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp)) }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
