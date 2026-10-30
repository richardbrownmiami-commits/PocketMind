package com.pocketmind.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pocketmind.R
import com.pocketmind.tools.AppLog

class GenerationService : Service() {
    companion object {
        const val ACTION_START = "com.pocketmind.action.GENERATION_START"
        const val ACTION_STOP = "com.pocketmind.action.GENERATION_STOP"
        private const val CHANNEL = "generation"
        private const val NOTIFICATION_ID = 30
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.info(this, "GENERATION_SERVICE_CREATE")
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "On-device generation", NotificationManager.IMPORTANCE_LOW))
            AppLog.info(this, "GENERATION_SERVICE_CHANNEL_READY")
        } catch (e: Exception) {
            AppLog.error(this, "GENERATION_SERVICE_CHANNEL_FAILED", e)
            throw e
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val actionType = when (intent?.action) {
            ACTION_START -> "start"
            ACTION_STOP -> "stop"
            null -> "none"
            else -> "other"
        }
        AppLog.info(this, "GENERATION_SERVICE_START", "actionType=$actionType startId=$startId flags=$flags")
        if (intent?.action == ACTION_STOP) {
            AppLog.info(this, "GENERATION_SERVICE_STOP_REQUEST")
            stopForeground(true)
            stopSelf(startId)
            AppLog.info(this, "GENERATION_SERVICE_STOP_ACCEPTED", "startId=$startId")
            return START_NOT_STICKY
        }
        return try {
            startForeground(NOTIFICATION_ID, notification())
            AppLog.info(this, "GENERATION_SERVICE_FOREGROUND", "outcome=started startId=$startId")
            START_NOT_STICKY
        } catch (e: Exception) {
            AppLog.error(this, "GENERATION_SERVICE_FOREGROUND_FAILED", e, "startId=$startId")
            throw e
        }
    }

    override fun onDestroy() {
        AppLog.info(this, "GENERATION_SERVICE_DESTROY")
        super.onDestroy()
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_pocketmind)
        .setContentTitle(getString(R.string.app_name))
        .setContentText("Generating on device")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    override fun onBind(intent: Intent?): IBinder? {
        AppLog.info(this, "GENERATION_SERVICE_BIND_REQUESTED", "outcome=not_bound")
        return null
    }
}
