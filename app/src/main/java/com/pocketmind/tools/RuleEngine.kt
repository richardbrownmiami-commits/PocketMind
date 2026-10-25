package com.pocketmind.tools

import java.util.Locale
import kotlin.math.roundToInt

object SafeMath {
    fun evaluate(input: String): Double = Expr(input).run()

    private class Expr(private val source: String) {
        private var index = 0
        private fun skipSpace() { while (index < source.length && source[index].isWhitespace()) index++ }
        fun run(): Double {
            require(source.length <= 500) { "Expression is too long" }
            val value = add()
            skipSpace()
            require(index == source.length) { "Unexpected token" }
            require(value.isFinite()) { "Result is not finite" }
            return value
        }
        private fun add(): Double {
            var value = multiply()
            while (true) {
                skipSpace()
                if (index >= source.length || source[index] !in "+-") return value
                val operator = source[index++]
                val right = multiply()
                value = if (operator == '+') value + right else value - right
            }
        }
        private fun multiply(): Double {
            var value = unary()
            while (true) {
                skipSpace()
                if (index >= source.length || source[index] !in "*/%") return value
                val operator = source[index++]
                val right = unary()
                require(right != 0.0) { "Division by zero" }
                value = when (operator) { '*' -> value * right; '/' -> value / right; else -> value % right }
            }
        }
        private fun unary(): Double {
            skipSpace()
            if (index < source.length && source[index] in "+-") {
                val operator = source[index++]
                val number = unary()
                return if (operator == '-') -number else number
            }
            if (index < source.length && source[index] == '(') {
                index++
                val number = add()
                skipSpace()
                require(index < source.length && source[index++] == ')') { "Missing closing parenthesis" }
                return number
            }
            val start = index
            while (index < source.length && (source[index].isDigit() || source[index] == '.')) index++
            require(index > start) { "Number expected" }
            return source.substring(start, index).toDouble()
        }
    }
}

object RuleEngine {
    private val alarmTimeFollowUp = Regex(
        "^\\s*(?:at\\s+)?(\\d{1,2}):(\\d{2})\\s*(a\\.?m\\.?|p\\.?m\\.?)?\\s*(?:please)?[.!?]?\\s*$",
        RegexOption.IGNORE_CASE
    )

    fun alarmFollowUpPlan(text: String, label: String? = null): PlanStep? {
        val match = alarmTimeFollowUp.matchEntire(text) ?: return null
        val hour12 = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        val meridiem = match.groupValues[3]
        if (hour12 !in 1..12 || minute !in 0..59 || meridiem.isBlank()) return null

        val hour = (hour12 % 12) + if (meridiem.startsWith("p", ignoreCase = true)) 12 else 0
        val args = mutableMapOf<String, Any>("hour" to hour, "minute" to minute)
        label?.takeIf { it.isNotBlank() }?.let { args["label"] = it.take(80) }
        return PlanStep("alarm.set", args)
    }

    fun looksLikeAlarmTimeFollowUp(text: String): Boolean =
        alarmTimeFollowUp.matches(text) ||
            Regex("^\\s*(?:at\\s+)?\\d{1,2}:\\d{1,2}.*$", RegexOption.IGNORE_CASE).matches(text) ||
            Regex("^\\s*(?:at\\s+)?\\d{1,2}\\s*(?:a\\.?m\\.?|p\\.?m\\.?)\\b.*$", RegexOption.IGNORE_CASE).matches(text)

    fun isAlarmRequestMissingTime(text: String): Boolean {
        val input = text.trim().lowercase(Locale.ROOT)
        if (isHowToQuestion(input)) return false
        val clean = stripPolitePrefix(input)
        val asksForAlarm = Regex("^(?:set|create|add)\\s+(?:an?\\s+)?alarm\\b|^wake\\s+me(?:\\s+up)?\\b").containsMatchIn(clean)
        if (!asksForAlarm) return false
        val time = Regex("\\b(?:at|for)\\s+(\\d{1,2}):(\\d{2})\\s*(am|pm)?\\b").find(clean)
        if (time == null) return true
        val hour = time.groupValues[1].toIntOrNull() ?: return true
        return time.groupValues[3].isBlank() && hour in 1..12
    }

    fun clarificationTarget(text: String): String? {
        val input = text.trim().lowercase(Locale.ROOT)
        if (isHowToQuestion(input)) return null
        val clean = stripPolitePrefix(input)
        if (isAlarmRequestMissingTime(text)) return "alarm.set"
        if (Regex("^(?:(?:please|can you|could you)\\s+)?(?:set|change|put)\\s+(?:the\\s+)?(?:(?:media|music)\\s+)?volume[?.!]*$").matches(clean)) return "volume"
        return null
    }

    fun clarification(text: String): String? = when (clarificationTarget(text)) {
        "alarm.set" -> "What time should I set the alarm for? Please include minutes and AM/PM, for example 6:30 pm."
        "volume" -> "What media volume level should I use, from 0 to 100 percent?"
        else -> null
    }

    fun clarificationFollowUpPlan(tool: String, text: String, label: String? = null): PlanStep? = when (tool) {
        "alarm.set" -> alarmFollowUpPlan(text, label)
        "volume" -> {
            val match = Regex("^\\s*(\\d+)\\s*(?:%|percent)?[.!]?\\s*$", RegexOption.IGNORE_CASE).matchEntire(text)
            val level = match?.groupValues?.get(1)?.toIntOrNull()
            if (level != null && level in 0..100) PlanStep("volume", mapOf("level" to level)) else null
        }
        else -> null
    }

    fun looksLikeClarificationFollowUp(tool: String, text: String): Boolean = when (tool) {
        "alarm.set" -> looksLikeAlarmTimeFollowUp(text)
        "volume" -> Regex("^\\s*\\d+\\s*(?:%|percent)?[.!]?\\s*$", RegexOption.IGNORE_CASE).matches(text)
        else -> false
    }

    fun match(text: String): List<PlanStep>? {
        val input = text.lowercase(Locale.ROOT).trim()
        if (input.isEmpty() || isHowToQuestion(input)) return null
        if (isCancelledAction(input)) return emptyList()
        val clean = stripPolitePrefix(input)

        if (Regex("\\b(?:what (?:is )?the time|what(?:'s| is) the time|what time(?: is it)?|current time|tell me the time)\\b").containsMatchIn(clean)) {
            return listOf(PlanStep("clock.time", emptyMap()))
        }
        if (Regex("\\b(?:today's date|what (?:is )?the date|what's the date|what date|what day is it(?: today)?|current date)\\b").containsMatchIn(clean)) {
            return listOf(PlanStep("clock.date", emptyMap()))
        }

        if (Regex("\\b(?:how much battery (?:is )?(?:left|remaining)|how much (?:free )?(?:ram|storage)|battery (?:level|status|percentage)|what(?:'s| is) my battery|check (?:my )?battery|show (?:(?:me|my) )?(?:battery|device status|system status)|check (?:device|system) status|available ram|free storage)\\b").containsMatchIn(clean)) {
            return listOf(PlanStep("battery/status", emptyMap()))
        }

        val alarm = Regex("^(?:set|create|add)\\s+(?:an?\\s+)?alarm(?:\\s+(?:for|at))?\\s+(\\d{1,2}):(\\d{2})(?:\\s*(am|pm))?(?:\\s+(?:called|labelled|labeled)\\s+(.+))?(?:\\s+please)?[?.!]*$").find(clean)
        val wakeAlarm = Regex("^wake\\s+me(?:\\s+up)?\\s+(?:at|for)\\s+(\\d{1,2}):(\\d{2})(?:\\s*(am|pm))?(?:\\s+please)?[?.!]*$").find(clean)
        val alarmMatch = alarm ?: wakeAlarm
        if (alarmMatch != null) {
            var hour = alarmMatch.groupValues[1].toIntOrNull() ?: return null
            val minute = alarmMatch.groupValues[2].toIntOrNull() ?: return null
            val meridiem = alarmMatch.groupValues[3]
            if (meridiem.isNotEmpty()) {
                if (hour !in 1..12) return null
                hour = (hour % 12) + if (meridiem == "pm") 12 else 0
            } else if (hour in 1..12) {
                return null
            }
            if (hour !in 0..23 || minute !in 0..59) return null
            val args = mutableMapOf<String, Any>("hour" to hour, "minute" to minute)
            alarm?.groupValues?.get(4)?.takeIf { it.isNotBlank() }?.let { args["label"] = it.take(80) }
            return listOf(PlanStep("alarm.set", args))
        }

        val timer = Regex("^(?:set|start)\\s+(?:a\\s+)?timer\\s+(?:for\\s+)?(\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?)\\b|^timer\\s+for\\s+(\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?)\\b").find(clean)
        if (timer != null) {
            val count = (timer.groupValues[1].ifBlank { timer.groupValues[3] }).toLongOrNull() ?: return null
            val unit = timer.groupValues[2].ifBlank { timer.groupValues[4] }
            val seconds = count * when { unit.startsWith("hour") -> 3600L; unit.startsWith("min") -> 60L; else -> 1L }
            if (seconds !in 1L..86400L) return null
            return listOf(PlanStep("timer.set", mapOf("seconds" to seconds.toInt())))
        }

        if (Regex("\\b(list|show)\\s+(?:(?:me)\\s+)?(?:all\\s+)?(?:(?:my|saved)\\s+)?notes\\b|\\bwhat notes? (?:have i saved|did i save)\\b").containsMatchIn(clean)) return listOf(PlanStep("note.list", emptyMap()))
        val searchNote = Regex("\\b(search|find)\\s+(?:my\\s+)?notes?\\s+(?:for|about)\\s+(.+)").find(clean)
        if (searchNote != null) return listOf(PlanStep("note.search", mapOf("query" to searchNote.groupValues[2].take(200))))
        val reminder = Regex("^remember to\\s+(.+)$").find(clean)
        if (reminder != null) return listOf(PlanStep("note.save", mapOf("text" to "Reminder: ${reminder.groupValues[1].take(3900)}")))
        val note = Regex("^(?:please\\s+)?(?:save|create)\\s+(?:a\\s+)?note(?:\\s+(?:that|saying))?\\s*[:,-]?\\s*(.+)$|^(?:save|note)\\s+(?:that\\s+)?(.+)$").find(clean)
        if (note != null) {
            val body = note.groupValues[1].ifBlank { note.groupValues[2] }.trim().take(4000)
            if (body.isNotBlank()) return listOf(PlanStep("note.save", mapOf("text" to body)))
        }

        val appSearch = Regex("^(?:please\\s+)?(?:search|find)\\s+(?:(?:my|installed)\\s+)?apps?\\s+(?:for|about|named|called)\\s+(.+?)[?.!]*$").find(clean)
        if (appSearch != null) return listOf(PlanStep("app.search", mapOf("query" to appSearch.groupValues[1].trim().take(100))))

        val torchOn = Regex("^(?:please\\s+)?(?:turn|switch)\\s+(?:on\\s+)?(?:the\\s+)?(?:flashlight|torch)(?:\\s+on)?[?.!]*$").matches(clean) ||
            Regex("^(?:please\\s+)?(?:enable)\\s+(?:the\\s+)?(?:flashlight|torch)[?.!]*$").matches(clean)
        val torchOff = Regex("^(?:please\\s+)?(?:turn|switch)\\s+(?:off\\s+)?(?:the\\s+)?(?:flashlight|torch)(?:\\s+off)?[?.!]*$").matches(clean) ||
            Regex("^(?:please\\s+)?(?:disable)\\s+(?:the\\s+)?(?:flashlight|torch)[?.!]*$").matches(clean)
        val torchSetOn = Regex("^(?:please\\s+)?set\\s+(?:the\\s+)?(?:flashlight|torch)\\s+on[?.!]*$").matches(clean)
        val torchSetOff = Regex("^(?:please\\s+)?set\\s+(?:the\\s+)?(?:flashlight|torch)\\s+off[?.!]*$").matches(clean)
        val torchOnInverted = Regex("^(?:please\\s+)?(?:turn|switch)\\s+on\\s+(?:the\\s+)?(?:flashlight|torch)[?.!]*$").matches(clean)
        val torchOffInverted = Regex("^(?:please\\s+)?(?:turn|switch)\\s+off\\s+(?:the\\s+)?(?:flashlight|torch)[?.!]*$").matches(clean)
        if (torchOn || torchOnInverted || torchSetOn) return listOf(PlanStep("flashlight", mapOf("enabled" to true)))
        if (torchOff || torchOffInverted || torchSetOff) return listOf(PlanStep("flashlight", mapOf("enabled" to false)))

        val brightness = Regex("^(?:please\\s+)?(?:set|change|adjust|dim)\\s+(?:the\\s+)?(?:(?:screen|display)\\s+)?brightness(?:\\s+(?:to|at))?\\s+(\\d{1,3})\\s*(?:%|percent)?[?.!]*$").find(clean)
        if (brightness != null) {
            val percent = brightness.groupValues[1].toIntOrNull() ?: return null
            if (percent !in 0..100) return null
            val level = (percent * 255.0 / 100.0).roundToInt()
            return listOf(PlanStep("brightness", mapOf("level" to level)))
        }

        val volume = Regex("^(?:(?:please|can you|could you)\\s+)?(set|raise|increase|turn up|lower|decrease|turn down|mute)\\s+(?:the\\s+)?(?:(?:media|music)\\s+)?volume(?:\\s+(?:(?:to|at)\\s+)?(\\d{1,3})\\s*(?:%|percent)?)?(?:[,\\s]+please)?[?.!]*$").find(clean)
        val reverseVolume = Regex("^(?:(?:please|can you|could you)\\s+)?(?:turn|make)\\s+(?:the\\s+)?(?:(?:media|music)\\s+)?volume\\s+(up|down|louder|quieter|higher|lower)[?.!]*$").find(clean)
        if (volume != null) {
            val verb = volume.groupValues[1]
            val level = volume.groupValues[2].toIntOrNull()
            if (verb == "mute") return listOf(PlanStep("volume", mapOf("level" to 0)))
            if (level != null) {
                if (level !in 0..100) return null
                return listOf(PlanStep("volume", mapOf("level" to level)))
            }
            val step = when (verb) {
                "raise", "increase", "turn up" -> 1
                "lower", "decrease", "turn down" -> -1
                else -> return null
            }
            return listOf(PlanStep("volume", mapOf("step" to step)))
        }
        if (reverseVolume != null) {
            val step = if (reverseVolume.groupValues[1] in setOf("up", "louder", "higher")) 1 else -1
            return listOf(PlanStep("volume", mapOf("step" to step)))
        }

        val settingTarget = when {
            Regex("\\b(?:wi-?fi|wireless)\\b").containsMatchIn(clean) -> "wifi.settings"
            Regex("\\bbluetooth\\b").containsMatchIn(clean) -> "bluetooth.settings"
            Regex("\\b(?:airplane(?: mode)?|flight mode)\\b").containsMatchIn(clean) -> "airplane.settings"
            Regex("\\blocation\\b").containsMatchIn(clean) -> "location.settings"
            else -> null
        }
        val settingsAction = Regex("^(?:please\\s+)?(?:open|show|go to|take me to)\\b").containsMatchIn(clean) ||
            Regex("^(?:please\\s+)?(?:turn|switch)\\b.*\\b(?:on|off)\\b").containsMatchIn(clean) ||
            Regex("^(?:please\\s+)?(?:enable|disable)\\b").containsMatchIn(clean) ||
            Regex("^(?:please\\s+)?set\\b.*\\b(?:on|off)\\b").containsMatchIn(clean)
        if (settingTarget != null && settingsAction) return listOf(PlanStep(settingTarget, emptyMap()))

        val openApp = Regex("^(?:please\\s+)?(?:open|launch)\\s+(?:the\\s+)?(.+?)(?:\\s+app)?[?.!]*$").find(clean)
        if (openApp != null) {
            val appName = openApp.groupValues[1].trim().removePrefix("the ").removeSuffix(" app").take(80)
            if (appName.isNotBlank()) return listOf(PlanStep("app.open", mapOf("package" to appName)))
        }

        if (Regex("\\b(?:calculate|compute|what is|what's)\\b").containsMatchIn(clean)) {
            var expression = clean.substringAfter("calculate", clean.substringAfter("compute", clean.substringAfter("what is", clean.substringAfter("what's", "")))).trim().trimEnd('?')
            expression = expression.replace(Regex("\\bplus\\b"), "+")
                .replace(Regex("\\bminus\\b"), "-")
                .replace(Regex("\\b(?:times|multiplied by)\\b"), "*")
                .replace(Regex("\\bdivided by\\b"), "/")
            if (expression.isNotBlank()) try {
                SafeMath.evaluate(expression)
                return listOf(PlanStep("math.calculate", mapOf("expression" to expression)))
            } catch (_: Exception) { return null }
        }
        return null
    }

    private fun isCancelledAction(clean: String): Boolean =
        Regex("^\\s*(?:please\\s+)?(?:don't|do not|dont|never|cancel|no)\\b.*\\b(alarm|timer|flashlight|torch|note|volume|brightness|battery|storage|ram|app|wi-?fi|bluetooth|airplane|location)\\b|^\\s*i\\s+(?:don't|do not|dont)\\s+(?:want|need)\\s+(?:you\\s+)?to\\b.*\\b(alarm|timer|flashlight|torch|note|volume|brightness|battery|storage|ram|app|wi-?fi|bluetooth|airplane|location)\\b").containsMatchIn(clean)

    private fun isHowToQuestion(clean: String): Boolean =
        Regex("^\\s*(how (?:do|can) i|what does|what if|why did|should i|can i|could i|is it possible|explain|tell me how)\\b").containsMatchIn(clean)

    private fun stripPolitePrefix(text: String): String =
        text.replaceFirst(Regex("^(?:please\\s+|(?:can|could|would) you\\s+(?:please\\s+)?|i want you to\\s+|i need you to\\s+|i'd like you to\\s+|i want to\\s+|i need to\\s+|i'd like to\\s+)"), "")
}
