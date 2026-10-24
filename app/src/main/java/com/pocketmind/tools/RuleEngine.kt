package com.pocketmind.tools

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    fun clarification(text: String): String? {
        val clean = text.trim().lowercase(Locale.ROOT)
        if (Regex("^\\s*(how (?:do|can) i|what does|why did|explain|tell me how)\\b").containsMatchIn(clean)) return null
        val asksForAlarm = Regex("\\b(?:set|create|add)\\s+(?:an?\\s+)?alarm\\b").containsMatchIn(clean)
        val hasClockTime = Regex("\\b(?:at|for)\\s+\\d{1,2}:\\d{2}(?:\\s*(?:am|pm))?\\b").containsMatchIn(clean)
        if (asksForAlarm && !hasClockTime) return "What time should I set the alarm for? Please include minutes and AM/PM, for example 6:30 pm."
        val incompleteVolume = Regex("^(?:please\\s+)?(?:set|change|put)\\s+(?:the\\s+)?(?:(?:media|music)\\s+)?volume[?.!]*$").matches(clean)
        if (incompleteVolume) return "What media volume level should I use, from 0 to 100 percent?"
        return null
    }

    fun match(text: String): List<PlanStep>? {
        val clean = text.lowercase(Locale.ROOT).trim()
        if (Regex("\\b(don't|do not|dont|never|cancel|no)\\b.*\\b(alarm|timer|flashlight|torch|note)\\b").containsMatchIn(clean)) return emptyList()

        if (Regex("\\b(what (?:is )?the time|what time|current time|tell me the time)\\b").containsMatchIn(clean)) return listOf(PlanStep("clock.time", emptyMap()))
        if (Regex("\\b(today's date|what (?:is )?the date|what date|what day is it|current date)\\b").containsMatchIn(clean)) return listOf(PlanStep("clock.date", emptyMap()))

        val alarm = Regex("\\bset\\s+(?:an?\\s+)?alarm(?:\\s+(?:for|at))?\\s+(\\d{1,2}):(\\d{2})(?:\\s*(am|pm))?(?:\\s+(?:called|labelled|labeled)\\s+(.+))?$").find(clean)
        if (alarm != null) {
            var hour = alarm.groupValues[1].toIntOrNull() ?: return null
            val minute = alarm.groupValues[2].toIntOrNull() ?: return null
            val meridiem = alarm.groupValues[3]
            if (meridiem.isNotEmpty()) {
                if (hour !in 1..12) return null
                hour = (hour % 12) + if (meridiem == "pm") 12 else 0
            }
            if (hour !in 0..23 || minute !in 0..59) return null
            val args = mutableMapOf<String, Any>("hour" to hour, "minute" to minute)
            alarm.groupValues[4].takeIf { it.isNotBlank() }?.let { args["label"] = it.take(80) }
            return listOf(PlanStep("alarm.set", args))
        }

        val timer = Regex("(?:set|start) (?:a )?timer for (\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?)").find(clean)
        if (timer != null) {
            val count = timer.groupValues[1].toLongOrNull() ?: return null
            val unit = timer.groupValues[2]
            val seconds = count * when { unit.startsWith("hour") -> 3600L; unit.startsWith("min") -> 60L; else -> 1L }
            if (seconds !in 1L..86400L) return null
            return listOf(PlanStep("timer.set", mapOf("seconds" to seconds.toInt())))
        }

        if (Regex("\\b(list|show)\\s+(my )?notes\\b").containsMatchIn(clean)) return listOf(PlanStep("note.list", emptyMap()))
        val searchNote = Regex("\\b(search|find)\\s+(?:my )?notes?\\s+(?:for|about)\\s+(.+)").find(clean)
        if (searchNote != null) return listOf(PlanStep("note.search", mapOf("query" to searchNote.groupValues[2].take(200))))
        val reminder = Regex("^remember to\\s+(.+)$").find(clean)
        if (reminder != null) return listOf(PlanStep("note.save", mapOf("text" to "Reminder: ${reminder.groupValues[1].take(3900)}")))
        val note = Regex("(?:save|note) (?:that )?(.+)").find(clean)
        if (note != null) return listOf(PlanStep("note.save", mapOf("text" to note.groupValues[1].take(4000))))

        if (clean.contains("turn on flashlight") || clean.contains("turn on torch")) return listOf(PlanStep("flashlight", mapOf("enabled" to true)))
        if (clean.contains("turn off flashlight") || clean.contains("turn off torch")) return listOf(PlanStep("flashlight", mapOf("enabled" to false)))

        val volume = Regex("^(?:(?:please|can you|could you)\\s+)?(set|raise|increase|turn up|lower|decrease|turn down)\\s+(?:the\\s+)?(?:(?:media|music)\\s+)?volume(?:\\s+(?:(?:to|at)\\s+)?(\\d{1,3})\\s*(?:%|percent)?)?(?:[,\\s]+please)?[?.!]*$").find(clean)
        if (volume != null) {
            val verb = volume.groupValues[1]
            val level = volume.groupValues[2].toIntOrNull()
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

        if (Regex("\\b(calculate|compute|what is)\\b").containsMatchIn(clean)) {
            val expression = clean.substringAfter("calculate", clean.substringAfter("compute", clean.substringAfter("what is", ""))).trim().trimEnd('?')
            if (expression.isNotBlank()) try {
                SafeMath.evaluate(expression)
                return listOf(PlanStep("math.calculate", mapOf("expression" to expression)))
            } catch (_: Exception) { return null }
        }
        return null
    }
}
