package dev.claudeusage.auth

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Html
import android.util.Log
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import dev.claudeusage.R
import dev.claudeusage.net.NetworkException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Signing in with a Claude account in the browser (a Custom Tab, never a WebView: the app never sees the
 * password, and the browser's existing claude.ai session can be reused).
 *
 * Normally the browser comes back to [LoopbackServer] on `localhost`. As a fallback the user can sign in with
 * the "copy this code" page and paste the code into the app.
 */
class SignInFlow(
    private val context: Context,
    private val tokens: TokenManager,
    private val scope: CoroutineScope,
    /** [sameAccount]: the account that was signed in before (its data can stay). */
    private val onSignedIn: suspend (sameAccount: Boolean) -> Unit,
) {
    enum class Failure { DENIED, INVALID_CODE, WRONG_CODE, NETWORK, SERVER, NO_BROWSER }

    sealed interface State {
        data object Idle : State
        /** The browser is open; [manual] when the user will paste a code. */
        data class Waiting(val manual: Boolean) : State
        data object Exchanging : State
        data class Failed(val failure: Failure, val manual: Boolean) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private class Attempt(val pkce: Pkce, val redirectUri: String, val server: LoopbackServer?, val manual: Boolean) {
        /** The code is single-use: a repeated callback waits for the first exchange instead of redoing it. */
        var result: CompletableDeferred<Boolean>? = null
        var timeout: Job? = null
    }

    @Volatile private var attempt: Attempt? = null

    fun start(activity: Activity, manual: Boolean = false) {
        cancel()
        val pkce = Pkce.create()
        val server: LoopbackServer?
        val redirect: String
        if (manual) {
            server = null
            redirect = OAuth.MANUAL_REDIRECT_URL
        } else {
            server = LoopbackServer(scope) { path, uri -> handle(path, uri) }
            redirect = OAuth.loopbackRedirect(server.start())
        }
        val next = Attempt(pkce, redirect, server, manual)
        // A login left half-done shouldn't keep a port open forever.
        next.timeout = scope.launch {
            delay(20 * 60_000L)
            if (attempt === next) cancel()
        }
        attempt = next
        _state.value = State.Waiting(manual)
        if (!openBrowser(activity, OAuth.authorizeUrl(pkce, redirect))) {
            cancel()
            _state.value = State.Failed(Failure.NO_BROWSER, manual)
        }
    }

    /** The manual fallback: the page shows `code#state`. */
    fun submitCode(pasted: String) {
        val current = attempt ?: return
        val text = pasted.trim()
        val code = text.substringBefore('#').trim()
        val state = text.substringAfter('#', "").trim()
        if (code.isEmpty() || (state.isNotEmpty() && !current.pkce.stateMatches(state))) {
            _state.value = State.Failed(Failure.WRONG_CODE, manual = true)
            return
        }
        scope.launch { complete(current, code) }
    }

    fun cancel() {
        attempt?.let {
            it.server?.stop()
            it.timeout?.cancel()
        }
        attempt = null
        _state.value = State.Idle
    }

    fun dismissError() {
        if (_state.value is State.Failed) _state.value = State.Idle
    }

    private suspend fun handle(path: String, uri: Uri): LoopbackResponse {
        val current = attempt ?: return page(404, "Not Found", R.string.signin_page_expired_title, R.string.signin_page_expired_text, false)
        return when (path) {
            "/callback" -> {
                uri.getQueryParameter("error")?.let { error ->
                    Log.w(TAG, "Authorization denied: $error")
                    if (current.pkce.stateMatches(uri.getQueryParameter("state"))) {
                        finish(current)
                        _state.value = State.Failed(Failure.DENIED, manual = false)
                    }
                    return page(200, "OK", R.string.signin_page_denied_title, R.string.signin_page_denied_text, false)
                }
                // Anything without our state is not the browser coming back from claude.ai: ignore it.
                if (!current.pkce.stateMatches(uri.getQueryParameter("state"))) {
                    return page(400, "Bad Request", R.string.signin_page_expired_title, R.string.signin_page_expired_text, false)
                }
                val code = uri.getQueryParameter("code")
                    ?: return page(400, "Bad Request", R.string.signin_page_expired_title, R.string.signin_page_expired_text, false)
                if (complete(current, code)) {
                    val port = uri.port.takeIf { it > 0 }
                    LoopbackResponse(302, "Found", headers = mapOf("Location" to backToApp(port)))
                } else {
                    page(200, "OK", R.string.signin_page_failed_title, R.string.signin_page_failed_text, false)
                }
            }
            "/done" -> page(200, "OK", R.string.signin_page_done_title, R.string.signin_page_done_text, true)
            else -> page(404, "Not Found", R.string.signin_page_expired_title, R.string.signin_page_expired_text, false)
        }
    }

    /** Exchanges [code] once per attempt; later calls get the first result. */
    private suspend fun complete(current: Attempt, code: String): Boolean {
        val pending = CompletableDeferred<Boolean>()
        val earlier = synchronized(current) {
            current.result.also { if (it == null) current.result = pending }
        }
        if (earlier != null) return earlier.await()

        _state.value = State.Exchanging
        var sameAccount = false
        val ok = try {
            val credentials = OAuth.exchange(code, current.pkce, current.redirectUri, System.currentTimeMillis())
            sameAccount = tokens.signIn(credentials)
            Log.i(TAG, "Signed in")
            true
        } catch (e: AuthException.InvalidCode) {
            _state.value = State.Failed(if (current.manual) Failure.WRONG_CODE else Failure.INVALID_CODE, current.manual)
            false
        } catch (e: NetworkException) {
            Log.w(TAG, "Sign-in failed: ${e.message}")
            _state.value = State.Failed(Failure.NETWORK, current.manual)
            false
        } catch (e: Exception) {
            Log.w(TAG, "Sign-in failed: ${e.javaClass.simpleName} ${e.message}")
            _state.value = State.Failed(Failure.SERVER, current.manual)
            false
        }
        if (ok) {
            if (attempt === current) _state.value = State.Idle
            // Keep the server up briefly so the "done" fallback page can still load.
            scope.launch {
                delay(60_000)
                current.server?.stop()
            }
            current.timeout?.cancel()
            if (attempt === current) attempt = null
            onSignedIn(sameAccount)
        } else {
            finish(current)
        }
        pending.complete(ok)
        return ok
    }

    private fun finish(current: Attempt) {
        current.timeout?.cancel()
        scope.launch {
            delay(5_000)
            current.server?.stop()
        }
        if (attempt === current) attempt = null
    }

    /**
     * Sends the browser back to the app; the activity is `singleTask`, so the Custom Tab on top of it closes.
     * If the browser won't open the app by itself, it shows the "done" page with a button instead.
     */
    private fun backToApp(port: Int?): String {
        val fallback = port?.let { ";S.browser_fallback_url=" + Uri.encode("http://localhost:$it/done") }.orEmpty()
        return "intent://signed-in#Intent;scheme=$APP_SCHEME;package=${context.packageName}$fallback;end"
    }

    private fun openBrowser(activity: Activity, url: Uri): Boolean {
        val toolbar = activity.getColor(R.color.surface)
        val params = CustomTabColorSchemeParams.Builder().setToolbarColor(toolbar).build()
        val customTab = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setDefaultColorSchemeParams(params)
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .build()
        // Prefer a browser that supports Custom Tabs (Chrome, Samsung Internet, …); else any browser.
        CustomTabsClient.getPackageName(activity, null)?.let { customTab.intent.setPackage(it) }
        return try {
            customTab.launchUrl(activity, url)
            true
        } catch (_: ActivityNotFoundException) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE))
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
        }
    }

    private fun page(status: Int, reason: String, titleRes: Int, textRes: Int, withButton: Boolean): LoopbackResponse {
        val title = Html.escapeHtml(context.getString(titleRes))
        val text = Html.escapeHtml(context.getString(textRes))
        val button = if (withButton) {
            val label = Html.escapeHtml(context.getString(R.string.signin_page_open_app))
            """<a class="btn" href="${backToApp(null)}">$label</a>"""
        } else ""
        val body = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>$title</title>
<style>
:root{color-scheme:light dark;--bg:#F4F3EE;--fg:#1F1E1D;--muted:#6B6A64;--accent:#C96442}
@media (prefers-color-scheme:dark){:root{--bg:#1F1E1D;--fg:#F5F4EE;--muted:#A3A199;--accent:#E08A6D}}
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:var(--bg);color:var(--fg);
font-family:system-ui,-apple-system,Roboto,sans-serif;text-align:center;padding:24px;box-sizing:border-box}
.spark{width:56px;height:56px;margin:0 auto 20px}
h1{font-weight:700;font-size:26px;margin:0 0 10px}
p{color:var(--muted);font-size:16px;line-height:1.5;margin:0 auto;max-width:340px}
.btn{display:inline-block;margin-top:28px;padding:14px 28px;border-radius:999px;background:var(--accent);color:#fff;
text-decoration:none;font-weight:600;font-size:16px}
</style></head><body><main>
<svg class="spark" viewBox="0 0 72 72" aria-hidden="true"><rect width="72" height="72" rx="21" fill="#D97757"/>
<g fill="#fff"><rect x="13" y="20" width="46" height="12" rx="6" opacity=".3"/><rect x="13" y="40" width="46" height="12" rx="6" opacity=".3"/>
<rect x="13" y="20" width="33" height="12" rx="6"/><rect x="13" y="40" width="19.3" height="12" rx="6"/></g></svg>
<h1>$title</h1><p>$text</p>$button</main></body></html>"""
        return LoopbackResponse(status, reason, body)
    }

    companion object {
        private const val TAG = "SignInFlow"
        const val APP_SCHEME = "claudeusage"
    }
}
