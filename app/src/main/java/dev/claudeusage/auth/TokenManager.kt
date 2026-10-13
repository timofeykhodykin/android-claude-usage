package dev.claudeusage.auth

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface AuthState {
    data class SignedIn(val email: String?) : AuthState
    /** [expiredEmail] is set when the login stopped working (revoked or expired) rather than signed out. */
    data class SignedOut(val expiredEmail: String? = null) : AuthState
}

/**
 * Supplies a valid access token, refreshing it when it has less than 5 minutes left. Refreshes are
 * serialized: the refresh token is single-use, so two concurrent refreshes would sign the user out.
 */
class TokenManager(context: Context) {
    private val store = CredentialStore(context)
    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    @Volatile private var credentials: Credentials? = store.load()

    private val _state = MutableStateFlow(
        credentials?.let { AuthState.SignedIn(it.email) }
            ?: AuthState.SignedOut(prefs.getString(KEY_EXPIRED_EMAIL, null))
    )
    val state: StateFlow<AuthState> = _state.asStateFlow()

    val isSignedIn: Boolean get() = credentials != null

    suspend fun accessToken(now: () -> Long = System::currentTimeMillis): String = mutex.withLock {
        val current = credentials ?: throw AuthException.NotSignedIn()
        if (!current.needsRefresh(now())) return current.accessToken
        refreshLocked(current, now())
    }

    /** After the API rejected [token] with 401: refresh once, unless that already happened meanwhile. */
    suspend fun recover(token: String, now: () -> Long = System::currentTimeMillis): String = mutex.withLock {
        val current = credentials ?: throw AuthException.NotSignedIn()
        if (current.accessToken != token) return current.accessToken
        refreshLocked(current, now())
    }

    private suspend fun refreshLocked(current: Credentials, now: Long): String {
        Log.i(TAG, "Refreshing access token")
        val fresh = try {
            OAuth.refresh(current, now)
        } catch (e: AuthException.Expired) {
            Log.w(TAG, "Refresh token rejected; sign-in needed")
            expire(current.email)
            throw e
        }
        // Store before use: the old refresh token is spent now.
        store.save(fresh)
        credentials = fresh
        Log.i(TAG, "Token refreshed")
        return fresh.accessToken
    }

    /** Stores a new login. Returns true when it's the same account as the one signed in before. */
    suspend fun signIn(fresh: Credentials): Boolean = mutex.withLock {
        val previousAccount = prefs.getString(KEY_ACCOUNT, null)
        store.save(fresh)
        credentials = fresh
        prefs.edit().remove(KEY_EXPIRED_EMAIL).putString(KEY_ACCOUNT, fresh.accountUuid ?: fresh.email).apply()
        _state.value = AuthState.SignedIn(fresh.email)
        previousAccount != null && previousAccount == (fresh.accountUuid ?: fresh.email)
    }

    /** Revokes the login on the server (best effort) and deletes the tokens. */
    suspend fun signOut() = mutex.withLock {
        val current = credentials
        credentials = null
        store.clear()
        prefs.edit().remove(KEY_EXPIRED_EMAIL).remove(KEY_ACCOUNT).apply()
        _state.value = AuthState.SignedOut()
        if (current != null) OAuth.revoke(current.refreshToken)
    }

    /** Debug builds only: pretend to be signed in, for the demo data. */
    fun fakeSignIn() {
        check(dev.claudeusage.BuildConfig.DEBUG)
        _state.value = AuthState.SignedIn("demo@example.com")
    }

    /** The API keeps answering 401 even with a fresh token: the login is gone. */
    suspend fun invalidate() = mutex.withLock { credentials?.let { expire(it.email) } }

    private fun expire(email: String?) {
        credentials = null
        store.clear()
        prefs.edit().putString(KEY_EXPIRED_EMAIL, email ?: "").apply()
        _state.value = AuthState.SignedOut(email ?: "")
    }

    private companion object {
        const val TAG = "TokenManager"
        const val KEY_EXPIRED_EMAIL = "expiredEmail"
        /** The account last signed in, kept after the login expires so signing in again keeps its history. */
        const val KEY_ACCOUNT = "account"
    }
}
