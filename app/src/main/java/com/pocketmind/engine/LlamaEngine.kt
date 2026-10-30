package com.pocketmind.engine

import com.pocketmind.tools.LiveSearchPlannerContext

/** One native instance; callers must serialize load/generate/unload on the model queue. */
class LlamaEngine {
    companion object {
        init { System.loadLibrary("pocketmind") }
    }

    fun load(path: String, nCtx: Int = 512, nBatch: Int = 256, nUbatch: Int = 128, threads: Int = 2) {
        require(nCtx in 128..1024) { "Context must be between 128 and 1024 tokens" }
        require(nBatch in 32..512 && nUbatch in 16..nBatch) { "Invalid batch settings" }
        require(threads in 1..InferenceSettings.MAX_CPU_THREADS) { "Threads must be between 1 and ${InferenceSettings.MAX_CPU_THREADS}" }
        nativeLoad(path, nCtx, nBatch, nUbatch, threads)
    }

    fun formatPrompt(system: String, user: String, template: PromptTemplate): String =
        nativeFormatPrompt(system, user, template.id)

    fun generate(
        prompt: String,
        maxTokens: Int = 128,
        grammar: String? = null,
        temperature: Float = InferenceSettings.DEFAULT_TEMPERATURE,
        topK: Int = InferenceSettings.DEFAULT_TOP_K,
        topP: Float = InferenceSettings.DEFAULT_TOP_P,
        onToken: (String) -> Unit = {}
    ): String {
        require(maxTokens in 1..512) { "maxTokens must be between 1 and 512" }
        require(temperature.isFinite() && temperature in 0.1f..2.0f) { "Temperature must be between 0.1 and 2.0" }
        require(topK in 1..100) { "Top-k must be between 1 and 100" }
        require(topP.isFinite() && topP in 0.05f..1.0f) { "Top-p must be between 0.05 and 1.0" }

        // Explicit web-search requests are already unambiguous at the router boundary.
        // Do not spend native inference tokens asking the small local model to emit a
        // tool-call JSON envelope; return the validated deterministic plan immediately.
        LiveSearchPlannerContext.takeQuery()?.let { query ->
            val plan = "{\"steps\":[{\"tool\":\"web.search\",\"args\":{\"query\":\"${escapeJson(query)}\",\"limit\":2}}]}"
            onToken(plan)
            return plan
        }

        return nativeGenerate(prompt, maxTokens, grammar, temperature, topK, topP, TokenSink(onToken))
    }

    fun countTokens(text: String): Int = nativeCountTokens(text)
    fun cancel() = nativeCancel()
    fun unload() = nativeUnload()
    fun stats(): String = nativeStats()

    private fun escapeJson(value: String): String = buildString(value.length) {
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }

    private fun interface TokenSink { fun onToken(chunk: String) }

    private external fun nativeLoad(path: String, nCtx: Int, nBatch: Int, nUbatch: Int, threads: Int)
    private external fun nativeFormatPrompt(system: String, user: String, templateId: Int): String
    private external fun nativeGenerate(
        prompt: String,
        maxTokens: Int,
        grammar: String?,
        temperature: Float,
        topK: Int,
        topP: Float,
        sink: TokenSink
    ): String
    private external fun nativeCountTokens(text: String): Int
    private external fun nativeCancel()
    private external fun nativeUnload()
    private external fun nativeStats(): String
}
