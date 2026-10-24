package com.pocketmind.diagnostics

import android.app.AlarmManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Read-only device snapshots used by the opt-in Tester. Values never include personal network names. */
internal object DeviceStateReader {
    fun nextAlarmClockTriggerMillis(context: Context): Long? = runCatching {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextAlarmClock?.triggerTime
    }.getOrNull()

    fun mediaVolume(context: Context): Pair<Int, Int> {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC) to audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }

    fun brightness(context: Context): Int? {
        if (!Settings.System.canWrite(context)) return null
        return runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull()
    }

    fun brightnessWriteGranted(context: Context): Boolean = runCatching { Settings.System.canWrite(context) }.getOrDefault(false)

    /** Returns null when no flash or an authoritative torch callback is unavailable. */
    fun flashlightEnabled(context: Context, timeoutMs: Long = 1_500L): Boolean? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        val cameraId = runCatching {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull() ?: return null
        val observed = AtomicReference<Boolean?>(null)
        val latch = CountDownLatch(1)
        val callbackThread = HandlerThread("PocketMind-torch-read").apply { start() }
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(id: String, enabled: Boolean) {
                if (id == cameraId) {
                    observed.set(enabled)
                    latch.countDown()
                }
            }

            override fun onTorchModeUnavailable(id: String) {
                if (id == cameraId) latch.countDown()
            }
        }
        return try {
            manager.registerTorchCallback(callback, Handler(callbackThread.looper))
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            observed.get()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { manager.unregisterTorchCallback(callback) }
            callbackThread.quitSafely()
        }
    }

    /** Reports only the Boolean radio/location state; never reads SSIDs, addresses, or paired-device names. */
    fun settingsState(context: Context, toolId: String): String = when (toolId) {
        "wifi.settings" -> runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            "Wi-Fi ${if (wifi.isWifiEnabled) "on" else "off"}"
        }.getOrElse { "Wi-Fi state unavailable (${it.javaClass.simpleName})" }
        "bluetooth.settings" -> runCatching {
            val bluetooth = context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val enabled = bluetooth.adapter?.isEnabled ?: return@runCatching "Bluetooth state unavailable"
            "Bluetooth ${if (enabled) "on" else "off"}"
        }.getOrElse { "Bluetooth state unavailable (${it.javaClass.simpleName})" }
        "airplane.settings" -> runCatching {
            val enabled = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
            "Airplane mode ${if (enabled) "on" else "off"}"
        }.getOrElse { "Airplane-mode state unavailable (${it.javaClass.simpleName})" }
        "location.settings" -> runCatching {
            val location = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            "Location ${if (location.isLocationEnabled) "on" else "off"}"
        }.getOrElse { "Location state unavailable (${it.javaClass.simpleName})" }
        else -> "No radio state applies"
    }
}
