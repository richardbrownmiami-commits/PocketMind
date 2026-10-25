package com.pocketmind.tools

import android.app.AlarmManager
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
import com.pocketmind.memory.ActionLogStore
import com.pocketmind.memory.AppDatabase
import com.pocketmind.memory.NoteStore
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A failed side effect may already have happened; never replay it automatically. */
internal object ToolExecutionRetryPolicy {
    private val retrySafeTools = setOf(
        "clock.time", "clock.date", "math.calculate", "note.list", "note.search",
        "battery/status", "app.search"
    )

    fun maximumAttempts(step: PlanStep): Int = if (step.tool in retrySafeTools) 3 else 1
}

class ToolRuntime(
    private val context: Context,
    private val registry: ToolRegistry,
    private val validator: ToolValidator = ToolValidator(registry),
    private val sharedDatabase: AppDatabase? = null,
    private val databaseName: String = DEFAULT_DATABASE_NAME
) {
    private val app = context.applicationContext
    private val usesScratchDatabase = databaseName != DEFAULT_DATABASE_NAME
    init {
        require(usesScratchDatabase || sharedDatabase != null) {
            "The normal chat database must be shared with AgentController."
        }
    }
    private val databaseDelegate = lazy {
        if (usesScratchDatabase) AppDatabase(app, databaseName)
        else sharedDatabase!!
    }
    private val database by databaseDelegate
    private val notes by lazy { NoteStore(database) }
    private val actions by lazy { ActionLogStore(database) }

    fun close() {
        if (usesScratchDatabase && databaseDelegate.isInitialized()) database.close()
    }

    fun execute(steps: List<PlanStep>, originalRequest: String): List<ActionResult> {
        if (steps.size > 8) {
            return listOf(finish(PlanStep("action.plan", emptyMap()), originalRequest, ActionStatus.FAILED, "Plan exceeds 8 steps"))
        }
        return steps.map { step ->
            val invalid = validator.validate(step)
            if (invalid != null) return@map finish(step, originalRequest, ActionStatus.FAILED, invalid)
            if (registry.get(step.tool) == null) return@map finish(step, originalRequest, ActionStatus.FAILED, "Unknown tool")
            var result = perform(step)
            var attempts = 1
            val maximumAttempts = ToolExecutionRetryPolicy.maximumAttempts(step)
            while (result.status == ActionStatus.FAILED && attempts < maximumAttempts) {
                attempts++
                result = perform(step)
            }
            finish(step, originalRequest, result.status, result.message)
        }
    }

    private fun finish(step: PlanStep, originalRequest: String, status: ActionStatus, message: String): ActionResult {
        val result = ActionResult(step.tool, status, message, step.args)
        try {
            val safeArgs = step.args.entries.joinToString(prefix = "{", postfix = "}") { "${it.key}=${it.value}" }.take(500)
            actions.record(originalRequest, step.tool, safeArgs, status.name, message)
            return result
        } catch (e: Exception) {
            val reason = e.message?.take(120) ?: "database write failed"
            return result.copy(message = "$message SQL action log could not be saved: $reason.")
        }
    }

    private fun perform(step: PlanStep): ActionResult {
        val a = step.args
        return try {
            when (step.tool) {
                "clock.time" -> verified(step, LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a")))
                "clock.date" -> verified(step, LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")))
                "math.calculate" -> verified(step, SafeMath.evaluate(a["expression"] as String).toString())
                "note.save" -> {
                    val saved = notes.save(a["text"] as String)
                    val readBack = notes.find(saved.id)
                    if (readBack?.content == saved.content) verified(step, "Note saved and verified.")
                    else failed(step, "The note was not confirmed by read-back.")
                }
                "note.list" -> {
                    val found = notes.list(20)
                    verified(step, if (found.isEmpty()) "No saved notes." else found.joinToString("\n") { "• ${it.content}" })
                }
                "note.search" -> {
                    val found = notes.search(a["query"] as String)
                    verified(step, if (found.isEmpty()) "No matching notes." else found.joinToString("\n") { "• ${it.content}" })
                }
                "alarm.set" -> setAlarm(step)
                "timer.set" -> setTimer(step)
                "flashlight" -> setTorch(step, a["enabled"] as Boolean)
                "volume" -> setVolume(step)
                "brightness" -> setBrightness(step, (a["level"] as Number).toInt())
                "battery/status" -> batteryStatus(step)
                "app.open" -> openApp(step, a["package"] as String)
                "app.search" -> searchApps(step, a["query"] as String)
                "wifi.settings", "bluetooth.settings", "airplane.settings", "location.settings" -> openSettings(step)
                else -> failed(step, "Unsupported tool.")
            }
        } catch (e: Exception) {
            failed(step, e.message?.take(200) ?: "The action failed.")
        }
    }

    private fun setAlarm(step: PlanStep): ActionResult {
        val hour = (step.args["hour"] as Number).toInt()
        val minute = (step.args["minute"] as Number).toInt()
        val displayTime = LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
        val dayOffset = (step.args["daysFromToday"] as? Number)?.toInt()
        val label = (step.args["label"] as? String)?.take(80)
        if (dayOffset == 1) {
            val target = LocalDate.now().plusDays(1).atTime(hour, minute)
            val triggerAtMillis = target.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (!ScheduledAlarmScheduler.notificationsEnabled(app)) {
                val settings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (settings.resolveActivity(app.packageManager) != null) app.startActivity(settings)
                return handoff(step, "Enable notifications for PocketMind in Android settings, then send the alarm request again.")
            }
            ScheduledAlarmScheduler.schedule(app, triggerAtMillis, label)
            val displayDate = target.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))
            return verified(step, "PocketMind alarm scheduled for $displayDate at $displayTime. It will alert with an Android alarm notification.")
        }
        if (dayOffset == 0 && !LocalDate.now().atTime(hour, minute).isAfter(LocalDateTime.now())) {
            return failed(step, "That time has already passed today; choose a later time or tomorrow.")
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        if (intent.resolveActivity(app.packageManager) == null) return failed(step, "No Clock app can handle an alarm.")
        app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val target = LocalDate.now().plusDays((dayOffset ?: 0).toLong()).atTime(hour, minute)
            .let { if (dayOffset == null && !it.isAfter(LocalDateTime.now())) it.plusDays(1) else it }
        val expectedMillis = target.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val observed = (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextAlarmClock?.triggerTime
        return if (observed != null && kotlin.math.abs(observed - expectedMillis) <= 90_000L) {
            val day = if (dayOffset == 0) " today" else ""
            verified(step, "Alarm for $displayTime$day verified by the system.")
        } else {
            handoff(step, "The Clock app received the request for $displayTime; Android did not expose a matching next alarm for verification.")
        }
    }

    private fun setTimer(step: PlanStep): ActionResult {
        val seconds = (step.args["seconds"] as Number).toInt()
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (intent.resolveActivity(app.packageManager) == null) return failed(step, "No Clock app can handle a timer.")
        app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return handoff(step, "Android accepted ACTION_SET_TIMER with EXTRA_SKIP_UI=true, which requests immediate timer start; Android exposes no timer-state read-back here, so completion and cleanup remain user-assisted.")
    }

    private fun setTorch(step: PlanStep, enabled: Boolean): ActionResult {
        val manager = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camera = manager.cameraIdList.firstOrNull { id -> manager.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return failed(step, "No flashlight is available.")
        val latch = CountDownLatch(1)
        var observed = false
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, torchMode: Boolean) {
                if (cameraId == camera && torchMode == enabled) { observed = true; latch.countDown() }
            }
            override fun onTorchModeUnavailable(cameraId: String) { if (cameraId == camera) latch.countDown() }
        }
        manager.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
        try {
            manager.setTorchMode(camera, enabled)
            latch.await(1500, TimeUnit.MILLISECONDS)
        } finally { manager.unregisterTorchCallback(callback) }
        return if (observed) verified(step, if (enabled) "Flashlight is on (verified)." else "Flashlight is off (verified).")
        else handoff(step, "Android accepted the torch request, but its callback did not confirm the state.")
    }

    private fun setVolume(step: PlanStep): ActionResult {
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val relativeStep = (step.args["step"] as? Number)?.toInt()
        val target = if (relativeStep != null) (current + relativeStep).coerceIn(0, max)
            else (((step.args["level"] as Number).toInt()) * max / 100.0).toInt().coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        val actual = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val success = when {
            actual != target -> "Volume read-back did not match; Do Not Disturb or device policy may block it."
            relativeStep == 1 && target == current -> "Media volume was already at its maximum; no change was needed."
            relativeStep == -1 && target == current -> "Media volume was already at its minimum; no change was needed."
            relativeStep == 1 -> "Media volume raised by one step and verified."
            relativeStep == -1 -> "Media volume lowered by one step and verified."
            else -> "Media volume set to ${step.args["level"]}% and verified."
        }
        return if (actual == target) verified(step, success) else failed(step, success)
    }

    private fun setBrightness(step: PlanStep, level: Int): ActionResult {
        if (!Settings.System.canWrite(app)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${app.packageName}"))
            app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return handoff(step, "Opened the system screen to grant brightness access. Approve it, then try again.")
        }
        val wrote = Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)
        val actual = Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        return if (wrote && actual == level) verified(step, "Brightness updated and read back.") else failed(step, "Brightness could not be verified.")
    }

    private fun batteryStatus(step: PlanStep): ActionResult {
        val battery = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val memory = ActivityManager.MemoryInfo().also { (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
        val data = File(Environment.getDataDirectory().path)
        val text = "Battery ${battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%; RAM available ${memory.availMem / 1024 / 1024} MB; storage available ${data.usableSpace / 1024 / 1024} MB"
        return verified(step, text)
    }

    private fun openApp(step: PlanStep, packageName: String): ActionResult {
        val packageManager = app.packageManager
        var intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            val target = packageName.trim().removePrefix("the ").removeSuffix(" app")
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val matches = packageManager.queryIntentActivities(launcher, 0)
                .filter { it.loadLabel(packageManager).toString().equals(target, ignoreCase = true) }
                .distinctBy { it.activityInfo.packageName }
            if (matches.size > 1) return failed(step, "More than one installed app is named '$target'; use an exact app name.")
            val resolvedPackage = matches.singleOrNull()?.activityInfo?.packageName
            intent = resolvedPackage?.let(packageManager::getLaunchIntentForPackage)
        }
        if (intent == null) return failed(step, "No launchable installed app matched '$packageName'.")
        app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return handoff(step, "Opened the requested app. Android handed the screen to it.")
    }

    private fun searchApps(step: PlanStep, query: String): ActionResult {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = app.packageManager.queryIntentActivities(intent, 0)
            .map { it to it.loadLabel(app.packageManager).toString() }
            .filter { it.second.contains(query, ignoreCase = true) }
            .take(10)
        return verified(step, if (matches.isEmpty()) "No installed app matched '$query'." else matches.joinToString("\n") { "${it.second} (${it.first.activityInfo.packageName})" })
    }

    private fun openSettings(step: PlanStep): ActionResult {
        val action = when (step.tool) {
            "wifi.settings" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth.settings" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "airplane.settings" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            else -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
        }
        val intent = Intent(action)
        if (intent.resolveActivity(app.packageManager) == null) return failed(step, "No matching Android settings screen is available.")
        app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return handoff(step, "Opened Android settings. Please make the change there; Android 11 does not allow this app to toggle it silently.")
    }

    private fun verified(step: PlanStep, message: String) = ActionResult(step.tool, ActionStatus.VERIFIED, message, step.args)
    private fun handoff(step: PlanStep, message: String) = ActionResult(step.tool, ActionStatus.HANDED_OFF, message, step.args)
    private fun failed(step: PlanStep, message: String) = ActionResult(step.tool, ActionStatus.FAILED, message, step.args)

    companion object {
        private const val DEFAULT_DATABASE_NAME = "pocketmind_memory.db"
    }
}
