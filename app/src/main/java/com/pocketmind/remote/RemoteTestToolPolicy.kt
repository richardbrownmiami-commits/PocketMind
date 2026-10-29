package com.pocketmind.remote

/** Preserve the normal PocketMind tool set during a live session; reject its delayed actions after Stop. */
internal object RemoteTestToolPolicy {
    fun allows(requestStartedInRemoteSession: Boolean, sessionActiveAtExecution: Boolean): Boolean =
        !requestStartedInRemoteSession || sessionActiveAtExecution
}
