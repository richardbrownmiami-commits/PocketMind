package com.pocketmind.remote

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.pocketmind.remote.RemoteSessionService
import java.security.SecureRandom

/** Process-local state for a user-started session. No pairing credentials are persisted. */
object RemoteSessionManager {
    interface UiListener {
        fun onRemoteSessionChanged(active: Boolean, status: String)
    }

    interface ControlTarget {
        fun remoteTap(x: Double, y: Double)
        fun remoteSwipe(x: Double, y: Double, x2: Double, y2: Double)
        fun remoteText(text: String)
    }

    @Volatile var active: Boolean = false
        private set
    @Volatile var appForeground: Boolean = false
        private set
    @Volatile var status: String = ""
        private set
    @Volatile var pairingCode: String? = null
        private set

    @Volatile private var uiListener: UiListener? = null
    @Volatile private var controlTarget: ControlTarget? = null
    @Volatile private var appContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start(context: Context, relayUrl: String): String {
        check(!active) { "A remote test session is already active" }
        val relay = RemoteSessionClient.parseRelayUrl(relayUrl)
            ?: throw IllegalArgumentException("Enter a valid HTTPS relay address, such as https://relay.example.com")
        val random = SecureRandom()
        val code = buildString(PAIRING_CODE_LENGTH) {
            repeat(PAIRING_CODE_LENGTH) { append(PAIRING_ALPHABET[random.nextInt(PAIRING_ALPHABET.length)]) }
        }
        val app = context.applicationContext
        appContext = app
        pairingCode = code
        appForeground = true
        active = true
        setStatus("Connecting to relay")
        try {
            ContextCompat.startForegroundService(
                app,
                Intent(app, RemoteSessionService::class.java)
                    .setAction(RemoteSessionService.ACTION_START)
                    .putExtra(RemoteSessionService.EXTRA_RELAY_URL, relay.toString())
                    .putExtra(RemoteSessionService.EXTRA_PAIRING_CODE, code)
            )
        } catch (e: Exception) {
            clearState("Could not start remote session")
            throw e
        }
        return code
    }

    fun stop(context: Context? = null) {
        if (!active) return
        active = false
        appForeground = false
        controlTarget = null
        pairingCode = null
        setStatus("Remote session stopped")
        val app = context?.applicationContext ?: appContext
        if (app != null) app.stopService(Intent(app, RemoteSessionService::class.java))
    }

    fun bindUi(listener: UiListener?, target: ControlTarget?) {
        uiListener = listener
        controlTarget = target
    }

    fun setAppForeground(foreground: Boolean) {
        appForeground = foreground && active
        if (!appForeground) controlTarget = null
        if (active) RemoteSessionService.setVisible(appForeground)
    }

    fun sendFrame(jpeg: ByteArray) {
        if (!active || !appForeground || jpeg.isEmpty()) return
        RemoteSessionService.sendFrame(jpeg)
    }

    internal fun onServiceStatus(message: String) {
        if (active) setStatus(message)
    }

    internal fun onServiceClosed(message: String) {
        if (active) clearState(message.ifBlank { "Remote session ended" })
    }

    internal fun onControl(action: String, x: Double, y: Double, x2: Double, y2: Double, text: String) {
        if (!active || !appForeground) return
        if (action !in setOf("tap", "swipe", "text") ||
            (action != "text" && (!validPoint(x, y) || (action == "swipe" && !validPoint(x2, y2)))) ||
            (action == "text" && text.length > MAX_REMOTE_TEXT_LENGTH)) return
        mainHandler.post {
            if (!active || !appForeground) return@post
            val target = controlTarget ?: return@post
            when (action) {
                "tap" -> target.remoteTap(x, y)
                "swipe" -> target.remoteSwipe(x, y, x2, y2)
                "text" -> target.remoteText(text)
            }
        }
    }

    internal fun setStatus(message: String) {
        status = message.take(160)
        uiListener?.onRemoteSessionChanged(active, status)
    }

    private fun clearState(message: String) {
        active = false
        appForeground = false
        pairingCode = null
        controlTarget = null
        status = message.take(160)
        RemoteSessionService.sendStop(appContext)
        uiListener?.onRemoteSessionChanged(false, status)
    }

    private fun validPoint(x: Double, y: Double) =
        x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0

    private const val MAX_REMOTE_TEXT_LENGTH = 1000
    private const val PAIRING_CODE_LENGTH = 10
    private const val PAIRING_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
}
