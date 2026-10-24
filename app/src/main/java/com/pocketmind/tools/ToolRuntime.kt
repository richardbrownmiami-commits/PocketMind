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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ToolRuntime(
    private val context: Context,
    private val registry: ToolRegistry = ToolRegistry(),
    private val validator: ToolValidator = ToolValidator(registry)
) {
    private val app = context.applicationContext
    private val database by lazy { AppDatabase(app) }
    private val notes by lazy { NoteStore(database) }
    private val actions by lazy { ActionLogStore(database) }

    fun execute(steps: List<PlanStep>, approved: Set<String> = emptySet()): List<ActionResult> {
        if (steps.size > 8) return listOf(ActionResult("", ActionStatus.FAILED, "Plan exceeds 8 steps"))
        return steps.map { step ->
            val invalid = validator.validate(step)
            if (invalid != null) return@map finish(step, ActionStatus.FAILED, invalid)
            val spec = registry.get(step.tool) ?: return@map finish(step, ActionStatus.FAILED, "Unknown tool")
            if (spec.risk != RiskLevel.LOW && step.tool !in approved) {
                return@map finish(step, ActionStatus.PENDING_CONFIRMATION, "Explicit approval is required before this action.")
            }
            var result = perform(step)
            var retries = 0
            while (result.status == ActionStatus.FAILED && retries < 2) {
                retries++
                result = perform(step)
            }
            finish(step, result.status, result.message)
        }
    }

    private fun finish(step: PlanStep, status: ActionStatus, message: String): ActionResult {
        val result = ActionResult(step.tool, status, message, step.args)
        try {
            val safeArgs = step.args.entries.joinToString(prefix = "{", postfix = "}") { "${it.key}=${it.value}" }.take(500)
            // Never persist the raw user utterance; request is only a registry tool id.
            actions.record(step.tool, step.tool, safeArgs, status.name, message)
        } catch (_: Exception) { }
        return result
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
                "volume" -> setVolume(step, (a["level"] as Number).toInt())
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
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        (step.args["label"] as? String)?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it.take(80)) }
        if (intent.resolveActivity(app.packageManager) == null) return handoff(step, "No Clock app accepted the alarm request.")
        app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val target = LocalDate.now().atTime(hour, minute).let { if (!it.isAfter(LocalDateTime.now())) it.plusDays(1) else it }
        val expectedMillis = target.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val observed = (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextAlarmClock?.triggerTime
        return if (observed != null && kotlin.math.abs(observed - expectedMillis) <= 90_000L) {
            verified(step, "Alarm for ${"%02d:%02d".format(hour, minute)} verified by the system.")
        } else {
            handoff(step, "The Clock app received the alarm request; Android did not expose a matching next alarm for verification.")
        }
    }

    private fun setTimer(step: PlanStep): ActionResult {
        val seconds = (step.args["seconds"] as Number).toInt()
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (intent.resolveActivity(app.packageManager) == null) return failed(step, "No Clock app can handle a timer.")
        app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return handoff(step, "Opened the Clock app with the timer request. Please check or start it there.")
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

    private fun setVolume(step: PlanStep, percent: Int): ActionResult {
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (percent * max / 100.0).toInt().coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        val actual = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (actual == target) verified(step, "Media volume set to $percent% (verified).") else failed(step, "Volume read-back did not match; Do Not Disturb or device policy may block it.")
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
        val intent = app.packageManager.getLaunchIntentForPackage(packageName) ?: return failed(step, "No launchable app matched that package name.")
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
}
