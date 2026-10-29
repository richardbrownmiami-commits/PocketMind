package com.pocketmind.remote

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Network transport for the explicit remote-test feature; it is never used by normal chat. */
internal class RemoteSessionClient(
    private val relay: HttpUrl,
    private val pairingCode: String,
    private val listener: Listener
) {
    interface Listener {
        fun onStatus(message: String)
        fun onControl(action: String, x: Double, y: Double, x2: Double, y2: Double, text: String)
        fun onClosed(message: String)
    }

    private val closed = AtomicBoolean(false)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var visible = true

    fun connect() {
        // OkHttp represents WSS upgrades as HTTPS HttpUrls, preserving TLS verification.
        val socketUrl = webSocketUrl(relay)
        val request = Request.Builder().url(socketUrl).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val auth = JSONObject().put("type", "host").put("code", pairingCode).put("paused", !visible).toString()
                if (!webSocket.send(auth)) fail("Could not start the remote session")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = JSONObject(text)
                    when (message.optString("type")) {
                        "ready" -> {
                            ready = true
                            webSocket.send(JSONObject().put("type", if (visible) "resumed" else "paused").toString())
                            listener.onStatus("Waiting for a viewer")
                        }
                        "viewer_joined" -> listener.onStatus("Viewer connected")
                        "viewer_left" -> listener.onStatus("Waiting for a viewer")
                        "control" -> {
                            val action = message.optString("action")
                            val x = message.optDouble("x", Double.NaN)
                            val y = message.optDouble("y", Double.NaN)
                            val x2 = message.optDouble("x2", Double.NaN)
                            val y2 = message.optDouble("y2", Double.NaN)
                            val textValue = message.optString("text", "")
                            if (action in setOf("tap", "swipe", "text") &&
                                (action == "text" || (x.isFinite() && y.isFinite() &&
                                    (action == "tap" || (x2.isFinite() && y2.isFinite()))))) {
                                listener.onControl(action, x, y, x2, y2, textValue)
                            }
                        }
                        "error" -> fail(message.optString("message", "Relay rejected the session"))
                    }
                } catch (_: Exception) {
                    fail("The relay sent an invalid message")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Host-to-viewer binary frames are not expected back from the relay.
                fail("Unexpected relay data")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail(t.message?.take(120) ?: "Relay connection failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closed.get()) fail(reason.ifBlank { "Relay connection closed" })
            }
        })
    }

    fun sendFrame(jpeg: ByteArray) {
        if (closed.get() || !ready || !visible || jpeg.isEmpty() || jpeg.size > MAX_FRAME_BYTES) return
        val activeSocket = socket ?: return
        if (activeSocket.queueSize() > MAX_QUEUED_BYTES) {
            fail("Relay is too slow; the remote session was stopped")
            return
        }
        activeSocket.send(ByteString.of(*jpeg))
    }

    fun setVisible(visible: Boolean) {
        this.visible = visible
        if (!closed.get() && ready) socket?.send(JSONObject().put("type", if (visible) "resumed" else "paused").toString())
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        ready = false
        socket?.close(1000, "PocketMind session stopped")
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun fail(message: String) {
        if (!closed.compareAndSet(false, true)) return
        ready = false
        socket?.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        listener.onClosed(message.take(160))
    }

    companion object {
        private const val MAX_FRAME_BYTES = 256 * 1024
        private const val MAX_QUEUED_BYTES = 512 * 1024L

        fun webSocketUrl(relay: HttpUrl): HttpUrl {
            require(relay.isHttps)
            return relay.newBuilder().addPathSegment("ws").build()
        }

        fun parseRelayUrl(value: String): HttpUrl? {
            val trimmed = value.trim()
            if (trimmed.length !in 9..200) return null
            val uri = try { URI(trimmed) } catch (_: Exception) { return null }
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
            if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
            if (uri.path.isNotEmpty() && uri.path != "/") return null
            if (uri.port == 0 || uri.port > 65535) return null
            return HttpUrl.Builder()
                .scheme("https")
                .host(uri.host)
                .apply { if (uri.port != -1) port(uri.port) }
                .build()
        }

        fun validPairingCode(code: String): Boolean = code.length == 10 && code.all {
            it in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        }
    }
}
