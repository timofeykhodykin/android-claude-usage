package dev.claudeusage.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Thrown for connection problems (no network, DNS, TLS, timeouts). Never carries request details. */
class NetworkException(message: String) : IOException(message)

class HttpResponse(val code: Int, val body: String, private val headers: Map<String, List<String>>) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

/**
 * The app's only way to the network. HTTPS only, and only to the hosts Claude Code itself talks to for sign-in
 * and usage, so a bug can't send a token anywhere else. Redirects are not followed (a redirect would carry the
 * Authorization header to wherever it points).
 */
object Http {
    val allowedHosts = setOf("api.anthropic.com", "platform.claude.com")

    private const val USER_AGENT = "ClaudeUsage-Android/1.0"

    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 20_000): HttpResponse =
        request("GET", url, headers, null, timeoutMs)

    suspend fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 30_000): HttpResponse =
        request("POST", url, headers + ("Content-Type" to "application/json"), body, timeoutMs)

    private suspend fun request(
        method: String, url: String, headers: Map<String, String>, body: String?, timeoutMs: Int,
    ): HttpResponse = withContext(Dispatchers.IO) {
        val target = URL(url)
        require(target.protocol == "https" && target.host in allowedHosts) { "Blocked request to a host outside the allowlist" }
        val connection = try {
            target.openConnection() as HttpsURLConnection
        } catch (e: IOException) {
            throw NetworkException(e.javaClass.simpleName)
        }
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            for ((k, v) in headers) connection.setRequestProperty(k, v)
            if (body != null) {
                connection.doOutput = true
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            // Responses are small JSON documents; cap them so a broken proxy can't exhaust memory.
            val text = stream?.use { input ->
                val bytes = input.readNBytesCompat(1 shl 20)
                String(bytes, Charsets.UTF_8)
            }.orEmpty()
            HttpResponse(code, text, connection.headerFields.filterKeys { it != null })
        } catch (e: IOException) {
            throw NetworkException(e.javaClass.simpleName + (e.message?.let { ": " + it.take(120) } ?: ""))
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
