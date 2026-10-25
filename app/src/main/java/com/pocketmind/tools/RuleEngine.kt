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

/** Deterministic direct-command path. Every returned step is checked against ToolRegistry. */
object RuleEngine {
    private val validator = ToolValidator(ToolRegistry())

    private val clockToken = Regex(
        """(?<![\d])(\d{1,2})(?::(\d{1,2}))?\s*(a\.?m\.?|p\.?m\.?)?(?![\p{L}\d])""",
        RegexOption.IGNORE_CASE
    )
    private val alarmTimeFollowUp = Regex(
        """^\s*(?:at\s+)?(\d{1,2})(?::(\d{1,2}))?\s*(a\.?m\.?|p\.?m\.?)?\s*(?:please)?[.!?]?\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val unsupportedAlarmDate = Regex(
        listOf(
            """\bday\s+after\s+tomorrow\b""",
            """\b(?:next|this)\s+(?:day|week|month|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""",
            """\b(?:on\s+)?(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""",
            """\b(?:in|after)\s+(?:\d+(?:\.\d+)?|[a-z]+(?:[-\s]+[a-z]+)?)\s+(?:days?|weeks?|months?)\b""",
            """\b(?:on\s+)?the\s+\d{1,2}(?:st|nd|rd|th)\b""",
            """\b\d{4}-\d{1,2}-\d{1,2}\b""",
            """\b\d{1,2}[/-]\d{1,2}(?:[/-]\d{2,4})?\b""",
            """\b(?:on\s+)?(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\.?\s+\d{1,2}(?:st|nd|rd|th)?(?:,\s*\d{4})?\b""",
            """\b(?:on\s+)?\d{1,2}(?:st|nd|rd|th)?\s+(?:of\s+)?(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\.?(?:\s+\d{4})?\b"""
        ).joinToString("|"),
        RegexOption.IGNORE_CASE
    )
    private val numberWords = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18,
        "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty" to 40,
        "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90
    )
    private val relativeAlarmDurationPart = Regex(
        """(?<![\p{L}\d])(\d+(?:\.\d+)?|a|an|[a-z]+(?:[- ][a-z]+)?)\s*(hours?|hrs?|h|minutes?|mins?|min|m)\b""",
        RegexOption.IGNORE_CASE
    )

    private data class ClockTime(val hour: Int, val minute: Int, val needsMeridiem: Boolean)

    fun hasUnsupportedAlarmDate(text: String): Boolean = unsupportedAlarmDate.containsMatchIn(text)

    private fun relativeAlarmTime(text: String): ClockTime? {
        val matches = relativeAlarmDurationPart.findAll(text).toList()
        if (matches.isEmpty()) return null
        val remainder = relativeAlarmDurationPart.replace(text, " ")
            .replace(Regex("(?i)\\b(?:in|after|for|and|from|now)\\b"), " ")
            .replace(Regex("[\\s,]+"), "")
        if (remainder.isNotEmpty()) return null
        var totalMinutes = 0.0
        for (part in matches) {
            val amountText = part.groupValues[1].lowercase(Locale.ROOT).replace('-', ' ').trim()
            val amount = when (amountText) {
                "a", "an" -> 1.0
                "forty five" -> 45.0
                else -> amountText.toDoubleOrNull()
                    ?: numberWords[amountText]?.toDouble()
                    ?: amountText.split(Regex("\\s+")).takeIf { words -> words.size == 2 }?.let { words ->
                        val tens = numberWords[words[0]]
                        val ones = numberWords[words[1]]
                        if (tens != null && tens in 20..90 && tens % 10 == 0 && ones != null && ones in 1..9) (tens + ones).toDouble() else null
                    }
                    ?: return null
            }
            val unit = part.groupValues[2].lowercase(Locale.ROOT)
            totalMinutes += amount * if (unit.startsWith("h")) 60.0 else 1.0
            if (!totalMinutes.isFinite() || totalMinutes > 1440.0) return null
        }
        val minutes = totalMinutes.roundToInt()
        if (minutes !in 1..1440) return null
        val target = java.time.ZonedDateTime.now().plusMinutes(minutes.toLong())
        val clockTarget = if (target.second != 0 || target.nano != 0) target.plusMinutes(1) else target
        val time = clockTarget.toLocalTime()
        return ClockTime(time.hour, time.minute, false)
    }

    fun alarmFollowUpPlan(text: String, label: String? = null): PlanStep? {
        val match = alarmTimeFollowUp.matchEntire(text) ?: return null
        val parsed = clockTime(match.groupValues[1], match.groupValues[2], match.groupValues[3]) ?: return null
        if (parsed.needsMeridiem) return null
        val args = mutableMapOf<String, Any?>("hour" to parsed.hour, "minute" to parsed.minute)
        label?.takeIf { it.isNotBlank() }?.let { args["label"] = it.take(80) }
        return checkedStep("alarm.set", args)
    }

    fun looksLikeAlarmTimeFollowUp(text: String): Boolean =
        alarmTimeFollowUp.matches(text) ||
            Regex("^\\s*(?:at\\s+)?\\d{1,2}:\\d{1,2}.*$", RegexOption.IGNORE_CASE).matches(text) ||
            Regex("^\\s*(?:at\\s+)?\\d{1,2}\\s*(?:a\\.?m\\.?|p\\.?m\\.?)\\b.*$", RegexOption.IGNORE_CASE).matches(text)

    fun isAlarmRequestMissingTime(text: String): Boolean {
        val raw = text.trim()
        if (isHowToQuestion(raw) || unsupportedAlarmDate.containsMatchIn(raw)) return false
        val clean = stripPolitePrefix(raw).trim()
        if (!isAlarmCommand(clean)) return false
        val clock = clockToken.findAll(clean).toList()
        if (clock.isEmpty()) return true
        if (clock.size != 1) return false
        val parsed = clockTime(clock[0].groupValues[1], clock[0].groupValues[2], clock[0].groupValues[3])
        return parsed?.needsMeridiem == true
    }

    fun clarificationTarget(text: String): String? {
        if (isHowToQuestion(text)) return null
        val clean = stripPolitePrefix(text.trim())
        if (isAlarmRequestMissingTime(text)) return "alarm.set"
        if (Regex("^(?:set|change|put|adjust)\\s+(?:the\\s+)?(?:(?:media|music|audio)\\s+)?volume[?.!]*$", RegexOption.IGNORE_CASE).matches(clean)) return "volume"
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
            val match = Regex("^\\s*(\\d+(?:\\.\\d+)?)\\s*(?:%|percent)?[.!]?\\s*$", RegexOption.IGNORE_CASE).matchEntire(text)
            val level = match?.groupValues?.get(1)?.toDoubleOrNull()
            if (level != null && level in 0.0..100.0) checkedStep("volume", mapOf("level" to level.roundToInt())) else null
        }
        else -> null
    }

    fun looksLikeClarificationFollowUp(tool: String, text: String): Boolean = when (tool) {
        "alarm.set" -> looksLikeAlarmTimeFollowUp(text)
        "volume" -> Regex("^\\s*\\d+(?:\\.\\d+)?\\s*(?:%|percent)?[.!]?\\s*$", RegexOption.IGNORE_CASE).matches(text)
        else -> false
    }

    fun match(text: String): List<PlanStep>? {
        val raw = text.trim()
        if (raw.isEmpty() || isHowToQuestion(raw)) return null
        if (isCancelledAction(raw)) return emptyList()
        val clean = stripPolitePrefix(raw).trim()
        val command = clean.trimEnd('?', '!', '.').trim()
            .replace(Regex("\\s+(?:please|thanks|thank you)$", RegexOption.IGNORE_CASE), "")
        if (command.isEmpty()) return null

        if (Regex("^(?:what(?:'s| is) (?:the )?(?:current )?time(?: now)?|what time(?: is it)?(?: now)?|tell me (?:the )?(?:current )?time(?: now)?|tell me what time (?:it is|it is now)|show me the time|check (?:the )?time|current time|get (?:the )?(?:current )?time|time now|give me the time)$", RegexOption.IGNORE_CASE).matches(command)) {
            return checkedPlan("clock.time", emptyMap())
        }
        if (Regex("^(?:what(?:'s| is) (?:(?:today's|the) )?(?:current )?date(?: today)?|what date is it(?: today)?|what day is it(?: today)?|today's date|current date|show me (?:today's )?date|tell me (?:today's |current )?date|tell me what (?:today's )?date it is|get (?:today's |current )?date)$", RegexOption.IGNORE_CASE).matches(command)) {
            return checkedPlan("clock.date", emptyMap())
        }

        if (isBatteryStatusRequest(command)) return checkedPlan("battery/status", emptyMap())

        alarmPlan(command)?.let { return listOf(it) }

        timerPlan(command)?.let { return listOf(it) }

        if (Regex("^(?:list|show|read|display)\\s+(?:(?:me)\\s+)?(?:(?:all|my|saved)\\s+)*(?:notes|memos)(?:\\s+please)?$|^(?:what|which) notes? (?:have i saved|did i save|are saved)(?:\\s+please)?$", RegexOption.IGNORE_CASE).matches(command)) {
            return checkedPlan("note.list", emptyMap())
        }
        val noteSearch = Regex(
            "^(?:search|find|look up|look for|read)\\s+(?:(?:my|saved|for)\\s+)*(?:notes?|memos)\\s+(?:for|about|on|containing|that mention)\\s+(.+?)[?.!]*$|^(?:do i have|are there)\\s+(?:any )?(?:saved )?notes?\\s+(?:for|about|on)\\s+(.+?)[?.!]*$",
            RegexOption.IGNORE_CASE
        ).matchEntire(command)
        if (noteSearch != null) {
            val query = noteSearch.groupValues[1].ifBlank { noteSearch.groupValues[2] }.trim().take(200)
            if (query.isNotBlank()) return checkedPlan("note.search", mapOf("query" to query))
        }
        val noteSave = Regex(
            "^(?:(?:save|store|write|create|add|make|take|jot down)\\s+(?:(?:a|the|quick)\\s+)?(?:note|memo)(?:\\s+(?:that|saying|about))?|write down|note\\s+down|remember\\s+to)\\s*[:,-]?\\s*(.+)$",
            RegexOption.IGNORE_CASE
        ).matchEntire(clean)
        if (noteSave != null) {
            val body = noteSave.groupValues[1].trim().take(4000)
            if (body.isNotBlank()) return checkedPlan("note.save", mapOf("text" to body))
        }

        appSearchPlan(command)?.let { return listOf(it) }

        torchPlan(command)?.let { return listOf(it) }

        brightnessPlan(command)?.let { return listOf(it) }

        volumePlan(command)?.let { return listOf(it) }

        settingsPlan(command)?.let { return listOf(it) }

        appOpenPlan(command)?.let { return listOf(it) }

        mathPlan(command)?.let { return listOf(it) }

        return null
    }

    private fun isBatteryStatusRequest(command: String): Boolean = Regex(
            "^(?:how much battery (?:is )?(?:left|remaining|available)|show me how much battery (?:is )?(?:left|remaining|available)|tell me how much battery (?:is )?(?:left|remaining|available)|how much (?:free )?(?:ram|storage)(?: (?:is )?(?:available|free|left)| remains)?|battery(?: level| status| percentage| percent| life| remaining)?|what(?:'s| is) (?:my )?(?:current )?battery(?: level| status| percentage)?|check (?:my )?(?:battery|device status|system status|ram|storage|storage space)|show (?:(?:me|my) )?(?:battery(?: status| percentage| percent| level| life)?|remaining battery|device status|system status|ram|storage|storage space)|tell me (?:my )?battery status|get (?:my )?battery status|(?:available|free) ram|free storage|(?:device|system) status|status of (?:my )?(?:device|phone|battery)|how much charge is left)$", RegexOption.IGNORE_CASE
    ).matches(command)

    private fun alarmPlan(clean: String): PlanStep? {
        if (!isAlarmCommand(clean)) return null
        if (unsupportedAlarmDate.containsMatchIn(clean)) return null
        val dayOffset = when {
            Regex("\\btomorrow\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean) -> 1
            Regex("\\btoday\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean) -> 0
            else -> null
        }

        val labelMatch = Regex("\\b(?:called|labelled|labeled|named)\\s+(.+)$", RegexOption.IGNORE_CASE).find(clean)
        val label = labelMatch?.groupValues?.get(1)?.trim()?.trimEnd('.', '!', '?')?.take(80)
        var timeText = if (labelMatch == null) clean else clean.substring(0, labelMatch.range.first)
        timeText = Regex("(?i)\\b(?:today|tomorrow)\\b").replace(timeText, " ")
        timeText = Regex("(?i)\\b(?:set|create|add|make|wake|me|up|alarm|clock|please|a|an|the|my)\\b").replace(timeText, " ")
        timeText = Regex("(?i)\\b(?:for|at|to|of)\\b").replace(timeText, " ")
        val relativeTime = relativeAlarmTime(timeText)
        if (relativeTime != null) {
            val args = mutableMapOf<String, Any?>("hour" to relativeTime.hour, "minute" to relativeTime.minute)
            dayOffset?.let { args["daysFromToday"] = it }
            label?.takeIf { it.isNotBlank() }?.let { args["label"] = it }
            return checkedStep("alarm.set", args)
        }
        val found = clockToken.findAll(timeText).toList()
        if (found.size != 1) return null
        val timeMatch = found.single()
        val remaining = timeText.removeRange(timeMatch.range).replace(Regex("[\\s,;:-]+"), "").trim()
        if (remaining.isNotEmpty()) return null
        val parsed = clockTime(timeMatch.groupValues[1], timeMatch.groupValues[2], timeMatch.groupValues[3]) ?: return null
        if (parsed.needsMeridiem) return null
        val args = mutableMapOf<String, Any?>("hour" to parsed.hour, "minute" to parsed.minute)
        dayOffset?.let { args["daysFromToday"] = it }
        label?.takeIf { it.isNotBlank() }?.let { args["label"] = it }
        return checkedStep("alarm.set", args)
    }

    private fun isAlarmCommand(text: String): Boolean = Regex(
        "^(?:(?:set|create|add|make)\\s+(?:(?:me|an?|the|my)\\s+)*alarm\\b|wake\\s+me(?:\\s+up)?\\b|alarm\\s+(?:at|for|in|after)\\b)",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(text.trim())

    private fun clockTime(hourText: String, minuteText: String, meridiemText: String): ClockTime? {
        val hourValue = hourText.toIntOrNull() ?: return null
        val minute = if (minuteText.isBlank()) 0 else minuteText.toIntOrNull() ?: return null
        if (minute !in 0..59) return null
        val meridiem = meridiemText.lowercase(Locale.ROOT).replace(".", "").trim()
        if (meridiem.isNotBlank()) {
            if (hourValue !in 1..12) return null
            val hour = (hourValue % 12) + if (meridiem == "pm") 12 else 0
            return ClockTime(hour, minute, false)
        }
        if (hourValue !in 0..23) return null
        return ClockTime(hourValue, minute, hourValue in 1..12)
    }

    private fun timerPlan(command: String): PlanStep? {
        val match = Regex(
            "^(?:(?:set|start|run)\\s+(?:(?:a|the|my)\\s+)?timer(?:\\s+(?:for|of|in|after))?|(?:(?:a|the|my)\\s+)?timer\\s+(?:for|of|in)?|start\\s+(?:a\\s+)?countdown(?:\\s+(?:for|in))?)\\s*[:,-]?\\s*(.+)$|^(?:set|start|run)\\s+(?:(?:a|the|my)\\s+)?(.+?)\\s+timer$",
            RegexOption.IGNORE_CASE
        ).matchEntire(command) ?: return null
        val duration = (match.groupValues[1].ifBlank { match.groupValues[2] }).trim()
        val durationToken = Regex(
            "(\\d+(?:\\.\\d+)?|a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty|forty-five|forty five|sixty)[-\\s]*(hours?|hrs?|h|minutes?|mins?|min|m|seconds?|secs?|sec|s)\\b",
            RegexOption.IGNORE_CASE
        )
        var totalSeconds = 0.0
        val matches = durationToken.findAll(duration).toList()
        if (matches.isEmpty()) return null
        for (part in matches) {
            val countText = part.groupValues[1].lowercase(Locale.ROOT).replace('-', ' ').trim()
            val amount = when (countText) {
                "a", "an" -> 1.0
                "forty five" -> 45.0
                else -> numberWords[countText]?.toDouble() ?: countText.toDoubleOrNull() ?: return null
            }
            val unit = part.groupValues[2].lowercase(Locale.ROOT)
            val multiplier = when {
                unit.startsWith("h") -> 3600.0
                unit.startsWith("m") -> 60.0
                else -> 1.0
            }
            totalSeconds += amount * multiplier
        }
        val remainder = durationToken.replace(duration, " ")
            .replace(Regex("(?i)\\b(?:and|for|in|of)\\b"), " ")
            .replace(Regex("[\\s,]+"), "")
        if (remainder.isNotEmpty() || !totalSeconds.isFinite()) return null
        val seconds = totalSeconds.roundToInt()
        if (seconds !in 1..86400) return null
        return checkedStep("timer.set", mapOf("seconds" to seconds))
    }

    private fun appSearchPlan(command: String): PlanStep? {
        val match = Regex(
            "^(?:search|find|look for|look up|show|display)\\s+(?:me\\s+)?(?:for\\s+)?(?:(?:my|installed|available|the)\\s+)?apps?\\s+(?:for|about|named|called|matching|with)\\s+(.+?)[?.!]*$|^(?:search|find|look for|look up|show|display)\\s+(?:me\\s+)?(?:for\\s+)?(?:(?:the|an?|installed)\\s+)?app\\s+(.+?)[?.!]*$",
            RegexOption.IGNORE_CASE
        ).matchEntire(command) ?: return null
        val query = match.groupValues[1].ifBlank { match.groupValues[2] }.trim()
            .replace(Regex("^(?:called|named|about|for|with|matching)\\s+", RegexOption.IGNORE_CASE), "")
            .trim().take(100)
        if (query.isBlank()) return null
        return checkedStep("app.search", mapOf("query" to query))
    }

    private fun torchPlan(command: String): PlanStep? {
        val match = Regex(
            "^(?:(?:turn|switch)\\s+(on|off)\\s+(?:(?:the|my)\\s+)?(?:flashlight|torch)|(?:turn|switch)\\s+(?:(?:the|my)\\s+)?(?:flashlight|torch)\\s+(on|off)|(?:set|put)\\s+(?:(?:the|my)\\s+)?(?:flashlight|torch)\\s+(?:to\\s+)?(on|off)|(?:enable|disable)\\s+(?:(?:the|my)\\s+)?(?:flashlight|torch)|(?:flashlight|torch)\\s+(on|off))$",
            RegexOption.IGNORE_CASE
        ).matchEntire(command) ?: return null
        val state = (1..4).map { match.groupValues[it] }.firstOrNull { it.isNotBlank() }
            ?: if (Regex("^(?:enable)", RegexOption.IGNORE_CASE).containsMatchIn(command)) "on" else "off"
        return checkedStep("flashlight", mapOf("enabled" to state.equals("on", ignoreCase = true)))
    }

    private fun brightnessPlan(command: String): PlanStep? {
        val amountPattern = "(?:maximum|the maximum|max|minimum|the minimum|min|full|half|a quarter|quarter|three quarters|\\d+(?:\\.\\d+)?\\s*(?:%|percent)?(?:\\s+out\\s+of\\s+255)?|\\d+(?:\\.\\d+)?\\s+on\\s+(?:a\\s+)?(?:0\\s*[-–]\\s*255|255)\\s+scale)"
        val match = Regex(
            "^(?:(?:set|change|adjust|put|dim|brighten|increase|decrease|raise|lower)\\s+(?:the\\s+)?(?:(?:screen|display)\\s+)?brightness(?:\\s+(?:to|at|level))?\\s+($amountPattern)|(?:screen|display)\\s+brightness\\s+(?:(?:to|at|level)\\s+)?($amountPattern)|brightness\\s+(?:(?:to|at|level)\\s+)?($amountPattern)|(?:set|change|adjust|put|dim|brighten)\\s+(?:the\\s+)?(?:screen|display)(?:\\s+brightness)?(?:\\s+(?:to|at))?\\s+($amountPattern))$",
            RegexOption.IGNORE_CASE
        ).matchEntire(command) ?: return null
        val amount = (1..4).map { match.groupValues[it] }.firstOrNull { it.isNotBlank() }?.trim() ?: return null
        val normalized = amount.lowercase(Locale.ROOT)
        val level = when {
            normalized == "maximum" || normalized == "the maximum" || normalized == "max" -> 255
            normalized == "minimum" || normalized == "the minimum" || normalized == "min" -> 0
            normalized == "full" -> 255
            normalized == "half" -> (50.0 * 255.0 / 100.0).roundToInt()
            normalized == "quarter" || normalized == "a quarter" -> (25.0 * 255.0 / 100.0).roundToInt()
            normalized == "three quarters" -> (75.0 * 255.0 / 100.0).roundToInt()
            normalized.contains("on") && normalized.contains("scale") -> {
                val value = Regex("\\d+(?:\\.\\d+)?").find(normalized)?.value?.toDoubleOrNull() ?: return null
                if (value !in 0.0..255.0) return null
                value.roundToInt()
            }
            else -> {
                val value = Regex("\\d+(?:\\.\\d+)?").find(normalized)?.value?.toDoubleOrNull() ?: return null
                if (normalized.contains("out of 255")) {
                    if (value !in 0.0..255.0) return null
                    value.roundToInt()
                } else if (normalized.contains('%') || normalized.contains("percent")) {
                    if (value !in 0.0..100.0) return null
                    (value * 255.0 / 100.0).roundToInt()
                } else if (value > 100.0 && value <= 255.0) {
                    value.roundToInt()
                } else {
                    if (value !in 0.0..100.0) return null
                    (value * 255.0 / 100.0).roundToInt()
                }
            }
        }
        return checkedStep("brightness", mapOf("level" to level))
    }

    private fun volumePlan(command: String): PlanStep? {
        val target = "(?:(?:the|my)\\s+)?(?:(?:media|music|audio)\\s+)?volume"
        val setMatch = Regex(
            "^(?:set|change|adjust|put)\\s+$target(?:\\s+(?:(?:to|at|level)(?:\\s+to)?))?\\s+(mute|muted|maximum|max|minimum|min|\\d+(?:\\.\\d+)?\\s*(?:%|percent)?)(?:\\s+please)?$",
            RegexOption.IGNORE_CASE
        ).matchEntire(command)
        if (setMatch != null) {
            val value = setMatch.groupValues[1].lowercase(Locale.ROOT).trim()
            val level = when (value) {
                "mute", "muted", "minimum", "min" -> 0
                "maximum", "max" -> 100
                else -> {
                    val amount = Regex("\\d+(?:\\.\\d+)?").find(value)?.value?.toDoubleOrNull() ?: return null
                    if (amount !in 0.0..100.0) return null
                    amount.roundToInt()
                }
            }
            return checkedStep("volume", mapOf("level" to level))
        }

        val mute = Regex("^(?:(?:mute|silence)(?:\\s+(?:(?:the|my)\\s+)?(?:media|music|audio))?\\s+$target|$target\\s+(?:off|muted))$|^(?:mute|silence)$", RegexOption.IGNORE_CASE).matches(command)
        if (mute) return checkedStep("volume", mapOf("level" to 0))

        val maxMin = Regex("^$target\\s+(maximum|max|minimum|min|full|lowest|highest)$", RegexOption.IGNORE_CASE).matchEntire(command)
        if (maxMin != null) {
            val high = maxMin.groupValues[1].lowercase(Locale.ROOT) in setOf("maximum", "max", "full", "highest")
            return checkedStep("volume", mapOf("level" to if (high) 100 else 0))
        }

        val oneStep = Regex(
            "^(?:(?:raise|increase|boost|lower|decrease|reduce)\\s+$target(?:\\s+by\\s+(?:one|1)(?:\\s+(?:step|notch|level))?)?|(?:turn|switch)\\s+(?:$target\\s+(?:up|down)|(?:up|down)\\s+$target)|make\\s+$target\\s+(?:louder|quieter|higher|lower)|$target\\s+(?:up|down|louder|quieter|higher|lower))(?:\\s+(?:by\\s+)?(?:one|1)(?:\\s+(?:step|notch|level))?)?$",
            RegexOption.IGNORE_CASE
        ).matchEntire(command)
        if (oneStep != null) {
            val lower = Regex("(?i)\\b(?:lower|decrease|reduce|down|quieter|lower)\\b").containsMatchIn(command)
            return checkedStep("volume", mapOf("step" to if (lower) -1 else 1))
        }
        return null
    }

    private fun settingsPlan(command: String): PlanStep? {
        val target = when {
            Regex("\\b(?:wi-?fi|wireless)\\b", RegexOption.IGNORE_CASE).containsMatchIn(command) -> "wifi.settings"
            Regex("\\bbluetooth\\b", RegexOption.IGNORE_CASE).containsMatchIn(command) -> "bluetooth.settings"
            Regex("\\b(?:airplane(?:\\s+mode)?|flight\\s+mode)\\b", RegexOption.IGNORE_CASE).containsMatchIn(command) -> "airplane.settings"
            Regex("\\b(?:location(?:\\s+services?)?|gps)\\b", RegexOption.IGNORE_CASE).containsMatchIn(command) -> "location.settings"
            else -> return null
        }
        val explicitAction = Regex(
            "^(?:(?:open|show|display|launch|bring up|pull up|go to|take me to)\\b|(?:turn|switch|toggle|set|enable|disable|activate|deactivate|connect|disconnect)\\b)",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(command) || Regex(
            "^(?:wi-?fi|wireless|bluetooth|airplane(?:\\s+mode)?|flight\\s+mode|location(?:\\s+services?)?|gps)\\s+(?:on|off|settings?|enabled|disabled)$",
            RegexOption.IGNORE_CASE
        ).matches(command)
        if (!explicitAction) return null
        return checkedStep(target, emptyMap())
    }

    private fun appOpenPlan(command: String): PlanStep? {
        val match = Regex("^(?:open|launch|start)\\s+(?:(?:the|my)\\s+)?(.+?)(?:\\s+(?:app|application))?$", RegexOption.IGNORE_CASE).matchEntire(command)
            ?: return null
        val target = match.groupValues[1].trim().trimEnd('.', '!', '?').take(80)
        if (target.isBlank()) return null
        return checkedStep("app.open", mapOf("package" to target))
    }

    private fun mathPlan(command: String): PlanStep? {
        val percentageOf = Regex(
            "^(?:(?:what is|what's|how much(?: is)?)\\s+)?(.+?)\\s*%\\s+of\\s+(.+?)[?.!]*$",
            RegexOption.IGNORE_CASE
        ).matchEntire(command)
        val arithmetic = Regex("^(add|sum|subtract|multiply|divide)\\s+(.+?)\\s+(and|to|from|by)\\s+(.+?)[?.!]*$", RegexOption.IGNORE_CASE).matchEntire(command)
        var expression = when {
            percentageOf != null -> "(${percentageOf.groupValues[1]}) / 100 * (${percentageOf.groupValues[2]})"
            arithmetic != null -> {
                val verb = arithmetic.groupValues[1].lowercase(Locale.ROOT)
                val first = arithmetic.groupValues[2].trim()
                val connector = arithmetic.groupValues[3].lowercase(Locale.ROOT)
                val second = arithmetic.groupValues[4].trim()
                when {
                    verb in setOf("add", "sum") && connector in setOf("and", "to") -> "$first + $second"
                    verb == "subtract" && connector == "from" -> "$second - $first"
                    verb == "multiply" && connector in setOf("and", "by") -> "$first * $second"
                    verb == "divide" && connector == "by" -> "$first / $second"
                    else -> return null
                }
            }
            else -> {
                val match = Regex(
                    "^(?:calculate|compute|work out|solve|what is|what's|how much(?: is)?)\\s+(.+?)[?.!]*$|^what does\\s+(.+?)\\s+equal[?.!]*$",
                    RegexOption.IGNORE_CASE
                ).matchEntire(command)
                if (match != null) {
                    match.groupValues[1].ifBlank { match.groupValues[2] }.trim()
                } else if (isBareMathExpression(command)) {
                    command
                } else {
                    return null
                }
            }
        }
        if (expression.isBlank()) return null
        expression = expression.replace("×", "*").replace("÷", "/")
            .replace(Regex("\\b(?:plus|added to)\\b", RegexOption.IGNORE_CASE), "+")
            .replace(Regex("\\b(?:minus|subtract)\\b", RegexOption.IGNORE_CASE), "-")
            .replace(Regex("(?i)\\b(?:times|multiplied by)\\b"), "*")
            .replace(Regex("\\b(?:divided by|over)\\b", RegexOption.IGNORE_CASE), "/")
            .replace(Regex("(?<=\\d)\\s*[xX]\\s*(?=[\\d(])"), "*")
        val tensAndOnes = Regex("(?i)\\b(twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)\\s+(one|two|three|four|five|six|seven|eight|nine)\\b")
        expression = tensAndOnes.replace(expression) { result ->
            ((numberWords[result.groupValues[1].lowercase(Locale.ROOT)] ?: 0) + (numberWords[result.groupValues[2].lowercase(Locale.ROOT)] ?: 0)).toString()
        }
        numberWords.forEach { (word, value) -> expression = Regex("(?i)\\b${Regex.escape(word)}\\b").replace(expression, value.toString()) }
        expression = expression.replace(Regex("(?i)\\b(?:what is|what's)\\b"), "").trim()
        if (expression.isBlank() || !Regex("^[\\d\\s()+\\-*/%.]+$").matches(expression)) return null
        return try {
            SafeMath.evaluate(expression)
            checkedStep("math.calculate", mapOf("expression" to expression))
        } catch (_: Exception) {
            null
        }
    }

    private fun isBareMathExpression(command: String): Boolean =
        Regex("^[\\d\\s().+\\-*/%xX×÷]+$").matches(command) &&
            Regex("[+*/%xX×÷]|\\d\\s*-").containsMatchIn(command)

    private fun checkedPlan(tool: String, args: Map<String, Any?>): List<PlanStep>? =
        checkedStep(tool, args)?.let(::listOf)

    private fun checkedStep(tool: String, args: Map<String, Any?>): PlanStep? {
        val step = PlanStep(tool, args)
        return step.takeIf { validator.validate(it) == null }
    }

    private fun isCancelledAction(text: String): Boolean {
        val clean = stripPolitePrefix(text.trim())
        val target = "(?:alarm|timer|flashlight|torch|note|memo|volume|brightness|battery|storage|ram|app|wi-?fi|wireless|bluetooth|airplane(?:\\s+mode)?|flight\\s+mode|location)"
        return Regex(
            "^(?:please\\s+)?(?:don't|do not|dont|never)\\s+(?:(?:please\\s+)?(?:set|start|run|turn|switch|open|launch|change|save|create|add|adjust|enable|disable|raise|lower|increase|decrease|mute|dim|brighten|search|find|check|show|list|connect|disconnect)\\b|(?:$target)\\b).*\\b$target\\b|^(?:please\\s+)?cancel\\s+(?:(?:my|the)\\s+)?$target\\b.*|^(?:i\\s+(?:don't|do not|dont)\\s+(?:want|need)\\s+(?:you\\s+)?to)\\s+(?:set|start|run|turn|switch|open|launch|change|save|create|add|adjust|enable|disable|raise|lower|increase|decrease|mute|dim|brighten|search|find|check|show|list|connect|disconnect)\\b.*\\b$target\\b",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(clean)
    }

    private fun isHowToQuestion(text: String): Boolean {
        if (Regex("^\\s*(?:show|tell) me how much battery\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) return false
        val arithmeticQuestion = Regex("^\\s*what does\\s+(.+?)\\s+equal[?.!]*$", RegexOption.IGNORE_CASE).matchEntire(text)
        if (arithmeticQuestion != null && Regex("\\d|\\b(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|twenty|thirty|forty|fifty|sixty)\\b", RegexOption.IGNORE_CASE).containsMatchIn(arithmeticQuestion.groupValues[1])) return false
        return Regex(
            "^\\s*(?:how (?:do|can|would) i|what does|what if|why did|why should(?:n't| not)|should i|can i|could i|is it possible|explain|tell me how|show me how)\\b",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(text) || Regex(
            "^\\s*(?:tell|show)\\s+me\\s+how\\b",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(text)
    }

    private fun stripPolitePrefix(text: String): String = text.replaceFirst(
        Regex("^(?:no,?\\s+(?:(?:actually|wait)\\s+)?|actually,?\\s+|please\\s+|(?:can|could|would|will) you\\s+(?:please\\s+)?|i want you to\\s+|i need you to\\s+|i'd like you to\\s+|i would like you to\\s+|i want to\\s+|i need to\\s+|i'd like to\\s+)", RegexOption.IGNORE_CASE),
        ""
    )
}
