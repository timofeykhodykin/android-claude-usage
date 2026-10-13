package dev.claudeusage.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.claudeusage.BuildConfig
import dev.claudeusage.Graph
import dev.claudeusage.R
import dev.claudeusage.auth.AuthState
import dev.claudeusage.data.AppSettings
import dev.claudeusage.data.ThemeMode
import dev.claudeusage.widget.Widgets
import dev.claudeusage.work.Notifier
import dev.claudeusage.work.Scheduler
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    val auth by Graph.tokens.state.collectAsStateWithLifecycle()
    val usage by Graph.repository.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmSignOut by remember { mutableStateOf(false) }
    var showGallery by remember { mutableStateOf(false) }

    // Re-read things the user may change in system settings while away (battery, notifications).
    var resumes by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { resumes++ } }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) }
                    Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp))
                }

                // Account
                SectionTitle(stringResource(R.string.settings_account))
                AppCard {
                    val signedIn = auth as? AuthState.SignedIn
                    val email = signedIn?.email ?: usage.profile?.email
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    (usage.profile?.name ?: email ?: "?").take(1).uppercase(),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(usage.profile?.name ?: email ?: stringResource(R.string.account_unknown), style = MaterialTheme.typography.titleMedium)
                            val line = listOfNotNull(email.takeIf { usage.profile?.name != null }, usage.profile?.plan).joinToString(" · ")
                            if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(stringResource(R.string.account_scope), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_sign_out), color = MaterialTheme.colorScheme.error)
                    }
                }

                // Updates
                SectionTitle(stringResource(R.string.settings_updates))
                AppCard {
                    ChipChoice(
                        stringResource(R.string.background_refresh), settings.backgroundMinutes,
                        AppSettings.backgroundChoices.map { it to stringResource(R.string.minutes_short, it) },
                    ) { minutes ->
                        Graph.settings.update { it.copy(backgroundMinutes = minutes) }
                        Scheduler.reschedule(context)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.background_refresh_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(14.dp))
                    BatteryRow(resumes)
                }

                // Notifications
                SectionTitle(stringResource(R.string.settings_notifications))
                AppCard {
                    NotificationOptions(settings, resumes)
                }

                // Widgets
                SectionTitle(stringResource(R.string.settings_widgets))
                AppCard {
                    Text(stringResource(R.string.widget_defaults_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                    WidgetPreview(dev.claudeusage.widget.WidgetKind.BARS, settings.widgetDefaults, android.util.SizeF(340f, 150f), Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                    WidgetOptions(settings.widgetDefaults, null) { cfg -> Graph.settings.update { it.copy(widgetDefaults = cfg) } }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(onClick = { showGallery = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_add_widget)) }
                        val ids = remember(resumes) { Widgets.allIds(context) }
                        OutlinedButton(
                            onClick = {
                                ids.forEach { (_, id) -> Graph.settings.setWidget(id, Graph.settings.value.widgetDefaults) }
                                scope.launch { Widgets.updateAll(context) }
                            },
                            enabled = ids.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.action_apply_all)) }
                    }
                }

                // Appearance
                SectionTitle(stringResource(R.string.settings_appearance))
                AppCard {
                    Choice(
                        stringResource(R.string.option_app_theme), settings.theme,
                        listOf(ThemeMode.SYSTEM to R.string.theme_system, ThemeMode.LIGHT to R.string.theme_light, ThemeMode.DARK to R.string.theme_dark),
                    ) { mode -> Graph.settings.update { it.copy(theme = mode) } }
                    Spacer(Modifier.height(16.dp))
                    Toggle(stringResource(R.string.option_dynamic), stringResource(R.string.option_dynamic_hint), settings.dynamicColor) { on ->
                        Graph.settings.update { it.copy(dynamicColor = on) }
                    }
                }

                // Privacy & about
                SectionTitle(stringResource(R.string.settings_privacy))
                AppCard {
                    Text(stringResource(R.string.privacy_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE) + "\n" + stringResource(R.string.about_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                )
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.sign_out_title)) },
            text = { Text(stringResource(R.string.sign_out_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    Graph.signOut()
                    onBack()
                }) { Text(stringResource(R.string.action_sign_out), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (showGallery) WidgetGallery(onDismiss = { showGallery = false })
}

/** Battery optimisation delays background work a lot on some phones (Samsung especially). */
@SuppressLint("BatteryLife")
@Composable
private fun BatteryRow(resumes: Int) {
    val context = LocalContext.current
    val power = context.getSystemService(PowerManager::class.java)
    val unrestricted = remember(resumes) { power.isIgnoringBatteryOptimizations(context.packageName) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (unrestricted) Icons.Outlined.CheckCircle else Icons.Outlined.BatteryAlert, null,
            tint = if (unrestricted) Color(0xFF6DBA85) else MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(if (unrestricted) R.string.battery_ok else R.string.battery_restricted), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(if (unrestricted) R.string.battery_ok_hint else R.string.battery_restricted_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (!unrestricted) {
        Spacer(Modifier.height(10.dp))
        FilledTonalButton(onClick = {
            val intent = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            runCatching { context.startActivity(intent) }
                .onFailure { runCatching { context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.battery_allow)) }
    }
}

@Composable
private fun NotificationOptions(settings: AppSettings, resumes: Int) {
    val context = LocalContext.current
    val permitted = remember(resumes) { Notifier.canPost(context) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pending?.invoke()
        pending = null
    }
    fun withPermission(action: () -> Unit) {
        if (Notifier.canPost(context)) action() else {
            pending = action
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Toggle(stringResource(R.string.notify_reset), stringResource(R.string.notify_reset_hint), settings.notifyReset && permitted) { on ->
            if (on) withPermission {
                Graph.settings.update { it.copy(notifyReset = true) }
                Scheduler.ensure(context)
            } else {
                Graph.settings.update { it.copy(notifyReset = false) }
                Scheduler.ensure(context)
            }
        }
        val off = stringResource(R.string.off)
        ChipChoice(
            stringResource(R.string.notify_threshold), if (permitted) settings.notifyThreshold else 0,
            listOf(0, 75, 90, 95).map { it to if (it == 0) off else "$it%" },
        ) { value ->
            val apply = {
                Graph.settings.update { it.copy(notifyThreshold = value) }
                Scheduler.ensure(context)
            }
            if (value == 0) apply() else withPermission(apply)
        }
    }
}
