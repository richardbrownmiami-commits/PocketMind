package com.pocketmind

import android.app.Application
import android.content.res.Configuration
import com.pocketmind.core.AgentController

class PocketMindApplication : Application() {
    val agentController: AgentController by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AgentController(this) }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig) }
}
