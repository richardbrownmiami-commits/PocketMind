package com.pocketmind.tools

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pocketmind.R
import com.pocketmind.ui.MainActivity

internal object ScheduledAlarmScheduler {
    private const val PREFERENCES = "scheduled_pocketmind_alarms"
    private const val PREFIX = "alarm_"
    private const val CHANNEL_ID = "scheduled_alarms"

    fun notificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun schedule(context: Context, triggerAtMillis: Long, label: String?) {
        val app = context.applicationContext
        AppLog.info(app, "ALARM_SCHEDULE_STARTED", "delayMs=${(triggerAtMillis - System.currentTimeMillis()).coerceAtLeast(0)}")
        val requestCode = requestCode(triggerAtMillis)
        val key = "$PREFIX$requestCode"
        val preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        try {
            check(preferences.edit()
                .putLong("$key.trigger", triggerAtMillis)
                .putString("$key.label", label.orEmpty())
                .commit()) { "Could not persist the alarm schedule" }
        } catch (e: Exception) {
            AppLog.error(app, "ALARM_SCHEDULE_PERSIST_FAILED", e)
            throw e
        }
        try {
            ensureChannel(app)
            setAlarm(app, requestCode, triggerAtMillis)
            AppLog.info(app, "ALARM_SCHEDULED")
        } catch (e: Exception) {
            AppLog.error(app, "ALARM_SCHEDULE_FAILED", e)
            preferences.edit().remove("$key.trigger").remove("$key.label").apply()
            throw e
        }
    }

    fun restoreAfterBoot(context: Context) {
        val app = context.applicationContext
        AppLog.info(app, "ALARM_RESTORE_AFTER_BOOT_STARTED")
        val preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        var restored = 0
        var expired = 0
        try {
            preferences.all.keys.filter { it.startsWith(PREFIX) && it.endsWith(".trigger") }.forEach { triggerKey ->
                val requestCode = triggerKey.removePrefix(PREFIX).removeSuffix(".trigger").toIntOrNull() ?: return@forEach
                val triggerAt = preferences.getLong(triggerKey, 0L)
                if (triggerAt <= now) {
                    val prefix = "$PREFIX$requestCode"
                    preferences.edit().remove("$prefix.trigger").remove("$prefix.label").apply()
                    expired++
                } else {
                    ensureChannel(app)
                    setAlarm(app, requestCode, triggerAt)
                    restored++
                }
            }
            AppLog.info(app, "ALARM_RESTORE_AFTER_BOOT_FINISHED", "restored=$restored expired=$expired")
        } catch (e: Exception) {
            AppLog.error(app, "ALARM_RESTORE_AFTER_BOOT_FAILED", e, "restored=$restored expired=$expired")
            throw e
        }
    }

    fun deliver(context: Context, requestCode: Int) {
        val app = context.applicationContext
        AppLog.info(app, "ALARM_TRIGGER_RECEIVED")
        val preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val key = "$PREFIX$requestCode"
        val triggerAt = preferences.getLong("$key.trigger", 0L)
        if (triggerAt == 0L) {
            AppLog.warn(app, "ALARM_TRIGGER_IGNORED", "reason=unknown_schedule")
            return
        }
        val label = preferences.getString("$key.label", null)?.takeIf { it.isNotBlank() }
        AppLog.info(app, "ALARM_DELIVERY_STARTED", "scheduled=true")
        try {
        ensureChannel(app)
        if (notificationsEnabled(app)) {
            val openApp = PendingIntent.getActivity(
                app,
                requestCode,
                Intent(app, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_pocketmind)
                .setContentTitle(label ?: "PocketMind alarm")
                .setContentText("It's time.")
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(app).notify(requestCode, notification)
            AppLog.info(app, "ALARM_NOTIFICATION_POSTED")
        } else {
            AppLog.warn(app, "ALARM_NOTIFICATION_SKIPPED", "reason=notifications_disabled")
        }
        preferences.edit().remove("$key.trigger").remove("$key.label").apply()
        AppLog.info(app, "ALARM_DELIVERY_FINISHED", "outcome=handled notificationEnabled=${notificationsEnabled(app)}")
        } catch (e: Exception) {
            AppLog.error(app, "ALARM_DELIVERY_FAILED", e)
            throw e
        }
    }

    private fun setAlarm(context: Context, requestCode: Int, triggerAtMillis: Long) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val showIntent = PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val fireIntent = Intent(context, ScheduledAlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_REQUEST_CODE, requestCode)
        val operation = PendingIntent.getBroadcast(
            context,
            requestCode,
            fireIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent), operation)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(CHANNEL_ID, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "PocketMind scheduled alarms"
            enableVibration(true)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: Settings.System.DEFAULT_ALARM_ALERT_URI, attributes)
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun requestCode(triggerAtMillis: Long): Int =
        ((triggerAtMillis xor (triggerAtMillis ushr 32)).toInt() and Int.MAX_VALUE).coerceAtLeast(1)

    const val ACTION_FIRE = "com.pocketmind.action.FIRE_SCHEDULED_ALARM"
    const val EXTRA_REQUEST_CODE = "request_code"
}

class ScheduledAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ScheduledAlarmScheduler.ACTION_FIRE) {
            AppLog.info(context, "ALARM_RECEIVER_IGNORED", "receiver=scheduled_alarm reason=unexpected_action")
            return
        }
        val requestCode = intent.getIntExtra(ScheduledAlarmScheduler.EXTRA_REQUEST_CODE, -1)
        if (requestCode >= 0) ScheduledAlarmScheduler.deliver(context, requestCode)
        else AppLog.warn(context, "ALARM_RECEIVER_IGNORED", "receiver=scheduled_alarm reason=invalid_code")
    }
}

class ScheduledAlarmBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            AppLog.info(context, "ALARM_BOOT_EVENT_RECEIVED")
            ScheduledAlarmScheduler.restoreAfterBoot(context)
        } else AppLog.info(context, "ALARM_BOOT_EVENT_IGNORED", "reason=unexpected_action")
    }
}
