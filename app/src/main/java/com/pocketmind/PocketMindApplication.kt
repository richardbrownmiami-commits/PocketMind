package com.pocketmind

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.content.res.Configuration
import com.pocketmind.core.AgentController
import com.pocketmind.tools.AppLog

class PocketMindApplication : Application() {
    val agentController: AgentController by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AgentController(this) }
    private var startedActivityCount = 0

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.info(this, "APP_PROCESS_CREATED")
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                AppLog.info(this@PocketMindApplication, "APP_ACTIVITY_CREATED", "activity=${activity::class.java.simpleName}")
            }
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
                AppLog.info(this@PocketMindApplication, "APP_ACTIVITY_STARTED", "activity=${activity::class.java.simpleName} startedCount=$startedActivityCount")
                if (startedActivityCount == 1) AppLog.info(this@PocketMindApplication, "APP_FOREGROUND_STATE", "state=foreground")
            }
            override fun onActivityResumed(activity: Activity) {
                AppLog.info(this@PocketMindApplication, "APP_ACTIVITY_RESUMED", "activity=${activity::class.java.simpleName}")
            }
            override fun onActivityPaused(activity: Activity) {
                AppLog.info(this@PocketMindApplication, "APP_ACTIVITY_PAUSED", "activity=${activity::class.java.simpleName}")
            }
            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                AppLog.info(this@PocketMindApplication, "APP_ACTIVITY_STOPPED", "activity=${activity::class.java.simpleName} startedCount=$startedActivityCount")
                if (startedActivityCount == 0) AppLog.info(this@PocketMindApplication, "APP_FOREGROUND_STATE", "state=background")
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                AppLog.info(this@PocketMindApplication, "APP_ACTIVITY_DESTROYED", "activity=${activity::class.java.simpleName}")
            }
        })
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLog.error(this, "UNCAUGHT_EXCEPTION", throwable, "thread=${thread.name}")
            previous?.uncaughtException(thread, throwable)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLog.info(this, "CONFIG_CHANGED", "orientation=${newConfig.orientation} uiMode=${newConfig.uiMode}")
    }
}
