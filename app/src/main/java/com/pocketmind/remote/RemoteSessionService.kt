package com.pocketmind.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pocketmind.R
import com.pocketmind.ui.MainActivity

/** User-visible owner of a short-lived session; backgrounding pauses all app data/control. */
class RemoteSessionService : Service() {
    private var client: RemoteSessionClient? = null
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) stopSession()
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
        createNotificationChannel()
        ContextCompat.registerReceiver(this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSession()
            ACTION_START -> startSession(intent)
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startSession(intent: Intent) {
        val relayText = intent.getStringExtra(EXTRA_RELAY_URL)
        val code = intent.getStringExtra(EXTRA_PAIRING_CODE)
        if (!RemoteSessionManager.active) { stopSelf(); return }
        if (code != RemoteSessionManager.pairingCode) return
        val relay = relayText?.let { RemoteSessionClient.parseRelayUrl(it) }
        if (relay == null || code == null || !RemoteSessionClient.validPairingCode(code)) {
            RemoteSessionManager.onServiceClosed("Invalid relay setup")
            stopSelf()
            return
        }
        startForeground(NOTIFICATION_ID, notification("Starting secure connection"))
        client?.close()
        client = RemoteSessionClient(relay, code, object : RemoteSessionClient.Listener {
            override fun onStatus(message: String) = publishStatus(message)

            override fun onControl(action: String, x: Double, y: Double, x2: Double, y2: Double, text: String) {
                RemoteSessionManager.onControl(action, x, y, x2, y2, text)
            }

            override fun onClosed(message: String) {
                client?.close()
                client = null
                RemoteSessionManager.onServiceClosed(message)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }).also {
            it.setVisible(RemoteSessionManager.appForeground)
            it.connect()
        }
    }

    private fun publishStatus(message: String) {
        val status = if (RemoteSessionManager.appForeground) message else "Paused — open PocketMind to share"
        RemoteSessionManager.onServiceStatus(status)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(status))
    }

    private fun updateVisibility(visible: Boolean) {
        client?.setVisible(visible)
        publishStatus(if (visible) "PocketMind in foreground" else "Paused")
    }

    private fun stopSession() {
        client?.close()
        client = null
        RemoteSessionManager.onServiceClosed("Remote session stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(status: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, STOP_REQUEST_CODE,
            Intent(this, RemoteSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, OPEN_REQUEST_CODE,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pocketmind)
            .setContentTitle(getString(R.string.remote_notification_title))
            .setContentText(getString(R.string.remote_notification_text, status))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.remote_stop), stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.remote_notification_channel), NotificationManager.IMPORTANCE_LOW)
            .apply { description = getString(R.string.remote_notification_channel_description) }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        unregisterReceiver(screenOffReceiver)
        client?.close()
        client = null
        if (serviceInstance === this) serviceInstance = null
        if (RemoteSessionManager.active) RemoteSessionManager.onServiceClosed("Remote service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSession()
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        const val ACTION_START = "com.pocketmind.remote.START"
        const val ACTION_STOP = "com.pocketmind.remote.STOP"
        const val EXTRA_RELAY_URL = "relay_url"
        const val EXTRA_PAIRING_CODE = "pairing_code"
        private const val CHANNEL_ID = "remote_test"
        private const val NOTIFICATION_ID = 4207
        private const val STOP_REQUEST_CODE = 4208
        private const val OPEN_REQUEST_CODE = 4209
        @Volatile private var serviceInstance: RemoteSessionService? = null

        fun sendFrame(frame: ByteArray) { serviceInstance?.client?.sendFrame(frame) }
        fun setVisible(visible: Boolean) { serviceInstance?.updateVisibility(visible) }
        fun sendStop(context: Context?) {
            val app = context?.applicationContext ?: return
            app.stopService(Intent(app, RemoteSessionService::class.java))
        }
    }
}
