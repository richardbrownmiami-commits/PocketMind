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

class GenerationService : Service() {
    companion object {
        const val ACTION_START = "com.pocketmind.action.GENERATION_START"
        const val ACTION_STOP = "com.pocketmind.action.GENERATION_STOP"
        private const val CHANNEL = "generation"
        private const val NOTIFICATION_ID = 30
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "On-device generation", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(true)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification())
        return START_NOT_STICKY
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_pocketmind)
        .setContentTitle(getString(R.string.app_name))
        .setContentText("Generating on device")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null
}
