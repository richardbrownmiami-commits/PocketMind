package com.pocketmind.diagnostics

import com.pocketmind.tools.PlanStep
import com.pocketmind.tools.ToolValidator

/** Synthetic user requests for end-to-end routing checks. The returned plans are never executed here. */
internal data class SyntheticFeatureRouteProbe(
    val toolId: String,
    val prompt: String,
    val expectedArguments: Map<String, Any?> = emptyMap()
)

internal data class SyntheticFeatureRouteResult(
    val status: DiagnosticStatus,
    val plan: PlanStep? = null
)

internal object SyntheticFeatureRouteQa {
    val probes = listOf(
        SyntheticFeatureRouteProbe("clock.time", "What time is it?"),
        SyntheticFeatureRouteProbe("clock.date", "What is today's date?"),
        SyntheticFeatureRouteProbe("math.calculate", "Calculate 17 + 25", mapOf("expression" to "17 + 25")),
        SyntheticFeatureRouteProbe("note.save", "Remember to buy a synthetic QA item", mapOf("text" to "synthetic QA item")),
        SyntheticFeatureRouteProbe("note.list", "Show my notes"),
        SyntheticFeatureRouteProbe("note.search", "Search my notes for orchids", mapOf("query" to "orchids")),
        SyntheticFeatureRouteProbe("alarm.set", "Set an alarm for 6:30 pm", mapOf("hour" to 18, "minute" to 30)),
        SyntheticFeatureRouteProbe("timer.set", "Set a timer for 5 minutes", mapOf("seconds" to 300)),
        SyntheticFeatureRouteProbe("flashlight", "Turn on the flashlight", mapOf("enabled" to true)),
        SyntheticFeatureRouteProbe("volume", "Raise the media volume", mapOf("step" to 1)),
        SyntheticFeatureRouteProbe("brightness", "Set display brightness to 128", mapOf("level" to 128)),
        SyntheticFeatureRouteProbe("battery/status", "Check battery status"),
        SyntheticFeatureRouteProbe("app.open", "Open the app with package com.pocketmind", mapOf("package" to "com.pocketmind")),
        SyntheticFeatureRouteProbe("app.search", "Search for the installed app named PocketMind", mapOf("query" to "PocketMind")),
        SyntheticFeatureRouteProbe("wifi.settings", "Open Wi-Fi settings"),
        SyntheticFeatureRouteProbe("bluetooth.settings", "Open Bluetooth settings"),
        SyntheticFeatureRouteProbe("airplane.settings", "Open airplane mode settings"),
        SyntheticFeatureRouteProbe("location.settings", "Open location settings")
    )

    fun matches(probe: SyntheticFeatureRouteProbe, steps: List<PlanStep>, validator: ToolValidator = ToolValidator()): Boolean {
        val step = steps.singleOrNull() ?: return false
        if (step.tool != probe.toolId || validator.validate(step) != null) return false
        return probe.expectedArguments.all { (name, expected) ->
            val actual = step.args[name] ?: return@all false
            when (expected) {
                is String -> actual is String && actual.contains(expected, ignoreCase = true)
                is Number -> actual is Number && actual.toDouble() == expected.toDouble()
                else -> actual == expected
            }
        }
    }
}
