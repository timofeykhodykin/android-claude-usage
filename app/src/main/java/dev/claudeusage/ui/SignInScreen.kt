package dev.claudeusage.ui

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.claudeusage.Graph
import dev.claudeusage.R
import dev.claudeusage.auth.AuthState
import dev.claudeusage.auth.SignInFlow

@Composable
fun SignInScreen() {
    val flow = Graph.signIn
    val state by flow.state.collectAsStateWithLifecycle()
    val auth by Graph.tokens.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current as Activity

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))
            AppMark(size = 84, animate = true)
            Spacer(Modifier.height(28.dp))
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 36.sp),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.signin_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 340.dp),
            )
            Spacer(Modifier.height(36.dp))
            Column(Modifier.widthIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Feature(Icons.Outlined.Widgets, R.string.signin_feature_widgets_title, R.string.signin_feature_widgets_text)
                Feature(Icons.Outlined.Lock, R.string.signin_feature_private_title, R.string.signin_feature_private_text)
                Feature(Icons.Outlined.Sync, R.string.signin_feature_fresh_title, R.string.signin_feature_fresh_text)
            }
            Spacer(Modifier.height(36.dp))

            (auth as? AuthState.SignedOut)?.expiredEmail?.let { email ->
                Text(
                    if (email.isNotEmpty()) stringResource(R.string.signin_expired_email, email) else stringResource(R.string.signin_expired),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }

            SignInControls(state, flow, activity)
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.signin_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 340.dp),
            )
        }
    }
}

/** The sign-in button and what happens while signing in; shared with the "sign in again" banner flow. */
@Composable
fun SignInControls(state: SignInFlow.State, flow: SignInFlow, activity: Activity) {
    Column(Modifier.fillMaxWidth().widthIn(max = 400.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val manual = (state as? SignInFlow.State.Waiting)?.manual == true || (state as? SignInFlow.State.Failed)?.manual == true

        AnimatedVisibility(state is SignInFlow.State.Failed) {
            val failure = (state as? SignInFlow.State.Failed)?.failure
            Text(
                failure?.let { stringResource(failureText(it)) }.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }

        if (manual && state !is SignInFlow.State.Exchanging) {
            var code by remember { mutableStateOf("") }
            Text(
                stringResource(R.string.signin_manual_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text(stringResource(R.string.signin_code_label)) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { if (code.isNotBlank()) flow.submitCode(code) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            PrimaryButton(stringResource(R.string.signin_code_submit), enabled = code.isNotBlank()) { flow.submitCode(code) }
            TextButton(onClick = { flow.cancel() }) { Text(stringResource(R.string.action_cancel)) }
            return@Column
        }

        when (state) {
            is SignInFlow.State.Waiting, SignInFlow.State.Exchanging -> {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 14.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                    Text(
                        stringResource(if (state is SignInFlow.State.Exchanging) R.string.signin_exchanging else R.string.signin_waiting),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (state is SignInFlow.State.Waiting) {
                    PrimaryButton(stringResource(R.string.signin_reopen)) { flow.start(activity) }
                    TextButton(onClick = { flow.cancel() }) { Text(stringResource(R.string.action_cancel)) }
                }
            }
            else -> {
                PrimaryButton(stringResource(R.string.signin_button)) { flow.start(activity) }
                TextButton(onClick = { flow.start(activity, manual = true) }) {
                    Text(stringResource(R.string.signin_with_code))
                }
            }
        }
    }
}

fun failureText(failure: SignInFlow.Failure): Int = when (failure) {
    SignInFlow.Failure.DENIED -> R.string.signin_failed_denied
    SignInFlow.Failure.INVALID_CODE -> R.string.signin_failed_invalid
    SignInFlow.Failure.WRONG_CODE -> R.string.signin_failed_wrong_code
    SignInFlow.Failure.NETWORK -> R.string.signin_failed_network
    SignInFlow.Failure.SERVER -> R.string.signin_failed_server
    SignInFlow.Failure.NO_BROWSER -> R.string.signin_failed_no_browser
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().height(54.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp))
    }
}

@Composable
private fun Feature(icon: ImageVector, title: Int, text: Int) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
                }
            }
        }
        Spacer(Modifier.size(14.dp))
        Column {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The app's mark, drawn like the launcher icon: two bars (session and week) on a terracotta tile. [animate] lets
 * the bars breathe (signing in, refreshing).
 */
@Composable
fun AppMark(size: Int, animate: Boolean = false) {
    val top: Float
    val bottom: Float
    if (animate) {
        val transition = rememberInfiniteTransition(label = "mark")
        top = transition.animateFloat(0.72f, 0.3f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "top").value
        bottom = transition.animateFloat(0.42f, 0.86f, infiniteRepeatable(tween(1700), RepeatMode.Reverse), label = "bottom").value
    } else {
        top = 0.72f
        bottom = 0.42f
    }
    androidx.compose.foundation.Canvas(Modifier.size(size.dp)) {
        val s = this.size.width
        drawRoundRect(androidx.compose.ui.graphics.Color(0xFFD97757), cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.3f))
        // Geometry of the launcher icon's visible 72×72 area.
        val left = s * 0.18f
        val width = s * 0.64f
        val h = s * 0.167f
        for ((y, f) in listOf(s * 0.278f to top, s * 0.556f to bottom)) {
            val r = androidx.compose.ui.geometry.CornerRadius(h / 2)
            drawRoundRect(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.3f), androidx.compose.ui.geometry.Offset(left, y),
                androidx.compose.ui.geometry.Size(width, h), r)
            drawRoundRect(androidx.compose.ui.graphics.Color.White, androidx.compose.ui.geometry.Offset(left, y),
                androidx.compose.ui.geometry.Size(maxOf(h, width * f), h), r)
        }
    }
}
