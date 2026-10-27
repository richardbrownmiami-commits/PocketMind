package com.pocketmind

import android.app.Application
import android.content.res.Configuration
import com.pocketmind.core.AgentController
import com.pocketmind.tools.AppLog

class PocketMindApplication : Application() {
    val agentController: AgentController by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AgentController(this) }

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLog.error(this, "UNCAUGHT_EXCEPTION", throwable, "thread=${thread.name}")
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            if (previous != null && previous !== Thread.currentThread().uncaughtExceptionHandler) {
                previous.uncaughtException(thread, throwable)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLog.info(this, "CONFIG_CHANGED", "orientation=${newConfig.orientation} uiMode=${newConfig.uiMode}")
    }
}
