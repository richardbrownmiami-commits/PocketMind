package com.pocketmind.tools

import java.util.UUID

/** Unique alarm label shared by the optional real Tester path and its separately confirmed cleanup. */
internal object TesterAlarmLabel {
    private val pattern = Regex("pocketmind-qa-[a-f0-9]{8}")

    fun create(): String = "pocketmind-qa-${UUID.randomUUID().toString().take(8)}"

    fun isValid(label: String): Boolean = pattern.matches(label)
}
