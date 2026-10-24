package com.pocketmind.diagnostics

/** Fixed synthetic prompts used only for local diagnostic inference. Never log or persist prompt/output text. */
internal data class SyntheticQaProbe(val prompt: String, val expectedAnswer: String)

internal object SyntheticModelQa {
    val probes = listOf(
        SyntheticQaProbe(
            "Synthetic offline QA. Answer only with a number. What is 17 + 25?",
            "42"
        ),
        SyntheticQaProbe(
            "Synthetic offline QA. Answer only with the city name. What is the capital of France?",
            "Paris"
        )
    )

    fun responseContainsExpectedAnswer(response: String, expectedAnswer: String): Boolean {
        if (response.isBlank() || expectedAnswer.isBlank()) return false
        val wholeToken = Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(expectedAnswer)}(?![\\p{L}\\p{N}])")
        return wholeToken.containsMatchIn(response)
    }

    fun asDiagnosticCheck(outcome: String): DiagnosticCheck = when {
        outcome.startsWith("PASS:") -> DiagnosticCheck("Model · loaded local synthetic Q&A correctness", DiagnosticStatus.PASS,
            "Both fixed synthetic answers matched expected content. Prompts and generated text were checked locally and were not saved to chat, memory, notes, action logs, or this report.")
        outcome.startsWith("FAIL:") -> DiagnosticCheck("Model · loaded local synthetic Q&A correctness", DiagnosticStatus.FAIL,
            "The loaded model did not return expected content for all fixed synthetic questions. Prompts and generated text were not saved or included in the report; check model compatibility and the local runtime.")
        else -> DiagnosticCheck("Model · loaded local synthetic Q&A correctness", DiagnosticStatus.NOT_RUN,
            "No model is loaded, so the synthetic Q&A check was not run. Load a compatible GGUF model in Models and rerun the tester.")
    }
}
