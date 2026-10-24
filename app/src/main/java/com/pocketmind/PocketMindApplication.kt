package com.pocketmind

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import com.pocketmind.core.AgentController

class PocketMindApplication : Application() {
    val agentController: AgentController by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AgentController(this) }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) agentController.trimMemory()
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig) }
}
