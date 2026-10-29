package com.pocketmind.remote

/** Also applied to delayed model plans after their originating remote session has ended. */
internal object RemoteTestToolPolicy {
    private val appOnlyTools = setOf(
        "clock.time", "clock.date", "math.calculate", "note.save", "note.list", "note.search",
        "action.history", "web.search", "api.discover", "api.get"
    )

    fun allows(tool: String, restrictedAtRequest: Boolean, sessionActiveAtExecution: Boolean = false): Boolean {
        val restricted = restrictedAtRequest || sessionActiveAtExecution
        return !restricted || tool in appOnlyTools
    }
}
