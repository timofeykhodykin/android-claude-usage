package dev.claudeusage.data

import android.util.Log
import dev.claudeusage.auth.AuthException
import dev.claudeusage.auth.TokenManager
import dev.claudeusage.net.Http
import dev.claudeusage.net.HttpResponse
import dev.claudeusage.net.NetworkException

/** What went wrong with the last attempt, in a form that can be stored and shown. */
enum class ErrorKind {
    /** No connection, DNS, TLS or timeout. */
    NETWORK,
    /** HTTP 429: the usage endpoint is rate-limited hard. */
    RATE_LIMITED,
    /** HTTP 403: usually the region or a VPN, not the account. */
    FORBIDDEN,
    /** Another HTTP error. */
    SERVER,
    /** The response didn't look like usage data. */
    UNEXPECTED,
    /** The login was revoked or expired: sign in again. */
    SIGNED_OUT,
}

class UsageException(val kind: ErrorKind, val status: Int = 0, val retryAfter: Long? = null) :
    Exception("$kind${if (status != 0) " (HTTP $status)" else ""}")

/** `GET api.anthropic.com/api/oauth/usage` (what Claude Code's `/usage` shows) and the account profile. */
class UsageApi(private val tokens: TokenManager) {
    suspend fun fetchUsage(now: Long): UsageSnapshot {
        val response = authorizedGet(USAGE_URL, now)
        return try {
            UsageParser.parseUsage(response.body, now)
        } catch (_: UnexpectedResponseException) {
            throw UsageException(ErrorKind.UNEXPECTED)
        }
    }

    suspend fun fetchProfile(now: Long): Profile {
        val response = authorizedGet(PROFILE_URL, now)
        return try {
            UsageParser.parseProfile(response.body, now)
        } catch (_: UnexpectedResponseException) {
            throw UsageException(ErrorKind.UNEXPECTED)
        }
    }

    private suspend fun authorizedGet(url: String, now: Long): HttpResponse {
        val token = try {
            tokens.accessToken()
        } catch (e: AuthException) {
            throw authError(e)
        } catch (e: NetworkException) {
            throw UsageException(ErrorKind.NETWORK)
        }
        var response = get(url, token)
        if (response.code == 401) {
            // Revoked early or rotated: refresh once and retry.
            val fresh = try {
                tokens.recover(token)
            } catch (e: AuthException) {
                throw authError(e)
            } catch (e: NetworkException) {
                throw UsageException(ErrorKind.NETWORK)
            }
            response = get(url, fresh)
            if (response.code == 401) {
                tokens.invalidate()
                throw UsageException(ErrorKind.SIGNED_OUT, 401)
            }
        }
        when (response.code) {
            200 -> return response
            429 -> {
                val retryAfter = UsageParser.parseRetryAfter(response.header("Retry-After"), now)
                Log.i(TAG, "429, Retry-After: ${response.header("Retry-After") ?: "none"}")
                throw UsageException(ErrorKind.RATE_LIMITED, 429, retryAfter)
            }
            403 -> throw UsageException(ErrorKind.FORBIDDEN, 403)
            else -> {
                Log.w(TAG, "HTTP ${response.code} from ${url.substringAfterLast('/')}: ${response.body.take(200)}")
                throw UsageException(ErrorKind.SERVER, response.code)
            }
        }
    }

    private suspend fun get(url: String, token: String): HttpResponse = try {
        Http.get(url, mapOf("Authorization" to "Bearer $token", "anthropic-beta" to BETA))
    } catch (e: NetworkException) {
        Log.w(TAG, "Network error: ${e.message}")
        throw UsageException(ErrorKind.NETWORK)
    }

    private fun authError(e: AuthException): UsageException = when (e) {
        is AuthException.NotSignedIn, is AuthException.Expired, is AuthException.InvalidCode -> UsageException(ErrorKind.SIGNED_OUT)
        is AuthException.Failed -> UsageException(if (e.status == 403) ErrorKind.FORBIDDEN else ErrorKind.SERVER, e.status)
    }

    companion object {
        private const val TAG = "UsageApi"
        const val USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
        const val PROFILE_URL = "https://api.anthropic.com/api/oauth/profile"
        const val BETA = "oauth-2025-04-20"
    }
}
