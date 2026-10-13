package dev.claudeusage.auth

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class LoopbackResponse(
    val status: Int,
    val reason: String,
    val body: String = "",
    val headers: Map<String, String> = emptyMap(),
)

/**
 * A tiny HTTP server on the loopback interface for the OAuth redirect (RFC 8252 §7.3), the same way Claude Code
 * signs in on a computer: the browser is sent to `http://localhost:<port>/callback?code=…&state=…`.
 *
 * Bound to 127.0.0.1 and ::1 only (never reachable from the network), on a random port, GET only, small
 * requests only, and it stops as soon as the login attempt ends.
 */
class LoopbackServer(
    private val scope: CoroutineScope,
    private val handler: suspend (path: String, query: Uri) -> LoopbackResponse,
) {
    private val sockets = mutableListOf<ServerSocket>()
    @Volatile private var stopped = false

    /** Starts listening and returns the port. */
    fun start(): Int {
        val v4 = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        sockets += v4
        // Browsers may try ::1 first for "localhost"; listen there too when the port is free.
        runCatching { ServerSocket(v4.localPort, 8, InetAddress.getByName("::1")) }.onSuccess { sockets += it }
        for (socket in sockets) scope.launch(Dispatchers.IO) { acceptLoop(socket) }
        return v4.localPort
    }

    fun stop() {
        stopped = true
        for (socket in sockets) runCatching { socket.close() }
        sockets.clear()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!stopped) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            scope.launch(Dispatchers.IO) { serve(client) }
        }
    }

    private suspend fun serve(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = 10_000
                val input = socket.getInputStream().buffered()
                val requestLine = readLine(input) ?: return
                // Skip the headers; nothing in them matters, but read them so the browser sees a clean reply.
                var total = requestLine.length
                while (true) {
                    val line = readLine(input) ?: break
                    total += line.length
                    if (line.isEmpty() || total > MAX_REQUEST) break
                }
                val parts = requestLine.split(' ')
                val response = when {
                    parts.size < 3 || total > MAX_REQUEST -> LoopbackResponse(400, "Bad Request")
                    parts[0] != "GET" -> LoopbackResponse(405, "Method Not Allowed")
                    else -> {
                        val uri = Uri.parse("http://localhost" + parts[1])
                        handler(uri.path ?: "/", uri)
                    }
                }
                write(socket, response)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Loopback request failed: ${e.javaClass.simpleName}")
        }
    }

    /** Reads one CRLF-terminated line of at most [MAX_REQUEST] bytes. */
    private fun readLine(input: java.io.InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() <= MAX_REQUEST) {
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.ISO_8859_1.name())
            if (b == '\n'.code) return bytes.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
            bytes.write(b)
        }
        return bytes.toString(Charsets.ISO_8859_1.name())
    }

    private fun write(socket: Socket, response: LoopbackResponse) {
        val body = response.body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ${response.status} ${response.reason}\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; img-src data:\r\n")
            append("Connection: close\r\n")
            for ((k, v) in response.headers) append("$k: $v\r\n")
            append("\r\n")
        }
        val out = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    private companion object {
        const val TAG = "LoopbackServer"
        const val MAX_REQUEST = 8 * 1024
    }
}
