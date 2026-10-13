package dev.claudeusage.auth

import android.net.Uri
import dev.claudeusage.net.Http
import dev.claudeusage.net.NetworkException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.security.SecureRandom

/** Errors from signing in or refreshing. */
sealed class AuthException(message: String) : Exception(message) {
    /** No tokens stored. */
    class NotSignedIn : AuthException("Not signed in")
    /** The refresh token was rejected: only signing in again helps. */
    class Expired : AuthException("Sign-in expired")
    /** The authorization code was rejected (expired, already used, or a different login attempt). */
    class InvalidCode : AuthException("Invalid authorization code")
    class Failed(val status: Int, val error: String) : AuthException("HTTP $status $error")
}

/** One login attempt's secrets (RFC 7636 PKCE + state). */
class Pkce private constructor(val verifier: String, val challenge: String, val state: String) {
    companion object {
        fun create(): Pkce {
            val random = SecureRandom()
            fun token(): String = ByteArray(32).also(random::nextBytes).base64Url()
            val verifier = token()
            val challenge = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)).base64Url()
            return Pkce(verifier, challenge, token())
        }

        private fun ByteArray.base64Url(): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(this)
    }

    /** Constant-time, so a probe on the loopback port learns nothing from timing. */
    fun stateMatches(other: String?): Boolean =
        other != null && MessageDigest.isEqual(other.toByteArray(), state.toByteArray())
}

/**
 * The OAuth client Claude Code uses (it is a public client: PKCE, no secret). We ask for **only**
 * `user:profile`, the scope `api/oauth/usage` needs: the resulting token can read the account's profile and
 * usage, but can't send messages, read chats or touch files. The login is our own, separate from Claude
 * Code's, so the two never invalidate each other's refresh tokens.
 */
object OAuth {
    const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    const val AUTHORIZE_URL = "https://claude.com/cai/oauth/authorize"
    const val TOKEN_URL = "https://platform.claude.com/v1/oauth/token"
    const val REVOKE_URL = "$TOKEN_URL/revoke"
    /** Shows the code to copy, for the manual fallback. */
    const val MANUAL_REDIRECT_URL = "https://platform.claude.com/oauth/code/callback"
    const val SCOPE = "user:profile"

    private val json = Json { ignoreUnknownKeys = true }

    fun loopbackRedirect(port: Int) = "http://localhost:$port/callback"

    fun authorizeUrl(pkce: Pkce, redirectUri: String): Uri = Uri.parse(AUTHORIZE_URL).buildUpon()
        .appendQueryParameter("code", "true")
        .appendQueryParameter("client_id", CLIENT_ID)
        .appendQueryParameter("response_type", "code")
        .appendQueryParameter("redirect_uri", redirectUri)
        .appendQueryParameter("scope", SCOPE)
        .appendQueryParameter("code_challenge", pkce.challenge)
        .appendQueryParameter("code_challenge_method", "S256")
        .appendQueryParameter("state", pkce.state)
        .build()

    /** Exchanges an authorization code for tokens. */
    suspend fun exchange(code: String, pkce: Pkce, redirectUri: String, now: Long): Credentials {
        val body = buildJsonObject {
            put("grant_type", "authorization_code")
            put("code", code)
            put("redirect_uri", redirectUri)
            put("client_id", CLIENT_ID)
            put("code_verifier", pkce.verifier)
            put("state", pkce.state)
        }
        val response = Http.postJson(TOKEN_URL, body.toString())
        if (response.code == 400 || response.code == 401) {
            val error = errorCode(response.body)
            if (response.code == 401 || error == "invalid_grant") throw AuthException.InvalidCode()
            throw AuthException.Failed(response.code, error)
        }
        if (response.code != 200) throw AuthException.Failed(response.code, errorCode(response.body))
        return parseTokens(response.body, now, previous = null)
    }

    /** Refreshes; the refresh token rotates, so the result must be stored before it's used. */
    suspend fun refresh(credentials: Credentials, now: Long): Credentials {
        val body = buildJsonObject {
            put("grant_type", "refresh_token")
            put("refresh_token", credentials.refreshToken)
            put("client_id", CLIENT_ID)
            put("scope", SCOPE)
        }
        val response = Http.postJson(TOKEN_URL, body.toString())
        if (response.code != 200) {
            val error = errorCode(response.body)
            if ((response.code == 400 || response.code == 401) && error == "invalid_grant") throw AuthException.Expired()
            throw AuthException.Failed(response.code, error)
        }
        return parseTokens(response.body, now, previous = credentials)
    }

    /** Best effort: tells the server to forget the login. Signing out locally doesn't depend on it. */
    suspend fun revoke(refreshToken: String) {
        val body = buildJsonObject {
            put("token", refreshToken)
            put("token_type_hint", "refresh_token")
            put("client_id", CLIENT_ID)
        }
        try {
            Http.postJson(REVOKE_URL, body.toString(), timeoutMs = 5_000)
        } catch (_: NetworkException) {
        }
    }

    private fun parseTokens(text: String, now: Long, previous: Credentials?): Credentials {
        val root = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull()
            ?: throw AuthException.Failed(200, "malformed response")
        fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val access = root.str("access_token") ?: throw AuthException.Failed(200, "malformed response")
        val expiresIn = (root["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600
        val account = root["account"] as? JsonObject
        val organization = root["organization"] as? JsonObject
        return Credentials(
            accessToken = access,
            refreshToken = root.str("refresh_token") ?: previous?.refreshToken
                ?: throw AuthException.Failed(200, "no refresh token"),
            expiresAt = now + expiresIn * 1000,
            scopes = root.str("scope")?.split(' ')?.filter { it.isNotEmpty() } ?: previous?.scopes ?: listOf(SCOPE),
            accountUuid = account?.str("uuid") ?: previous?.accountUuid,
            email = account?.str("email_address") ?: previous?.email,
            organizationUuid = organization?.str("uuid") ?: previous?.organizationUuid,
        )
    }

    private fun errorCode(text: String): String {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return ""
        val error = root["error"]
        return when (error) {
            is JsonPrimitive -> error.content
            is JsonObject -> (error["type"] as? JsonPrimitive)?.content.orEmpty()
            else -> ""
        }
    }
}
