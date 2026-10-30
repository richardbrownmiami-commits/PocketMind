package com.pocketmind.engine

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.min

/** Prompt formats supported by the native inference bridge, plus a user-defined format. */
enum class PromptTemplate(val id: Int) {
    MODEL_DEFAULT(0),
    PLAIN_TEXT(1),
    CHATML(2),
    LLAMA_3(3),
    GEMMA(4),
    CUSTOM(5);

    companion object {
        fun fromId(id: Int): PromptTemplate = entries.firstOrNull { it.id == id } ?: MODEL_DEFAULT
    }
}

data class InferenceSettings(
    val temperature: Float = DEFAULT_TEMPERATURE,
    val topK: Int = DEFAULT_TOP_K,
    val topP: Float = DEFAULT_TOP_P,
    val contextTokens: Int = DEFAULT_CONTEXT_TOKENS,
    val cpuThreads: Int = defaultThreads(),
    val promptTemplate: PromptTemplate = PromptTemplate.MODEL_DEFAULT,
    val customPromptTemplate: String = DEFAULT_CUSTOM_PROMPT_TEMPLATE
) {
    companion object {
        const val DEFAULT_TEMPERATURE = 0.7f
        const val DEFAULT_TOP_K = 40
        const val DEFAULT_TOP_P = 0.9f
        const val DEFAULT_CONTEXT_TOKENS = 512
        const val MAX_CPU_THREADS = 8
        const val MAX_CUSTOM_PROMPT_TEMPLATE_CHARS = 12_000
        const val DEFAULT_CUSTOM_PROMPT_TEMPLATE = "{{system}}\n\n{{user}}"
        val CONTEXT_OPTIONS = listOf(256, 384, 512, 768, 1024)

        fun availableCpuThreads(): Int = min(Runtime.getRuntime().availableProcessors().coerceAtLeast(1), MAX_CPU_THREADS)

        fun isValidCustomPromptTemplate(template: String): Boolean =
            template.isNotBlank() &&
                template.length <= MAX_CUSTOM_PROMPT_TEMPLATE_CHARS &&
                template.contains("{{user}}")

        private fun defaultThreads(): Int = min(Runtime.getRuntime().availableProcessors().coerceAtLeast(1), 4)
    }
}

/** Keeps the user-selected inference configuration independent of privacy/memory preferences. */
class InferenceSettingsStore(context: Context) {
    private val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): InferenceSettings {
        val temperature = preferences.getFloat(KEY_TEMPERATURE, InferenceSettings.DEFAULT_TEMPERATURE)
            .takeIf(Float::isFinite)?.coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE)
            ?: InferenceSettings.DEFAULT_TEMPERATURE
        val topK = preferences.getInt(KEY_TOP_K, InferenceSettings.DEFAULT_TOP_K).coerceIn(MIN_TOP_K, MAX_TOP_K)
        val topP = preferences.getFloat(KEY_TOP_P, InferenceSettings.DEFAULT_TOP_P)
            .takeIf(Float::isFinite)?.coerceIn(MIN_TOP_P, MAX_TOP_P)
            ?: InferenceSettings.DEFAULT_TOP_P
        val contextTokens = preferences.getInt(KEY_CONTEXT, InferenceSettings.DEFAULT_CONTEXT_TOKENS)
            .takeIf { it in InferenceSettings.CONTEXT_OPTIONS }
            ?: InferenceSettings.DEFAULT_CONTEXT_TOKENS
        val cpuThreads = preferences.getInt(KEY_CPU_THREADS, InferenceSettings().cpuThreads)
            .coerceIn(1, InferenceSettings.MAX_CPU_THREADS)
        val promptTemplate = PromptTemplate.fromId(preferences.getInt(KEY_TEMPLATE, PromptTemplate.MODEL_DEFAULT.id))
        val customPromptTemplate = preferences.getString(KEY_CUSTOM_TEMPLATE, null)
            ?.take(InferenceSettings.MAX_CUSTOM_PROMPT_TEMPLATE_CHARS)
            ?.takeIf(InferenceSettings::isValidCustomPromptTemplate)
            ?: InferenceSettings.DEFAULT_CUSTOM_PROMPT_TEMPLATE
        return InferenceSettings(
            temperature = temperature,
            topK = topK,
            topP = topP,
            contextTokens = contextTokens,
            cpuThreads = cpuThreads,
            promptTemplate = promptTemplate,
            customPromptTemplate = customPromptTemplate
        )
    }

    fun save(settings: InferenceSettings) {
        val customTemplate = settings.customPromptTemplate
            .take(InferenceSettings.MAX_CUSTOM_PROMPT_TEMPLATE_CHARS)
            .takeIf(InferenceSettings::isValidCustomPromptTemplate)
            ?: InferenceSettings.DEFAULT_CUSTOM_PROMPT_TEMPLATE
        preferences.edit()
            .putFloat(KEY_TEMPERATURE, settings.temperature.coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE))
            .putInt(KEY_TOP_K, settings.topK.coerceIn(MIN_TOP_K, MAX_TOP_K))
            .putFloat(KEY_TOP_P, settings.topP.coerceIn(MIN_TOP_P, MAX_TOP_P))
            .putInt(KEY_CONTEXT, settings.contextTokens.takeIf { it in InferenceSettings.CONTEXT_OPTIONS }
                ?: InferenceSettings.DEFAULT_CONTEXT_TOKENS)
            .putInt(KEY_CPU_THREADS, settings.cpuThreads.coerceIn(1, InferenceSettings.MAX_CPU_THREADS))
            .putInt(KEY_TEMPLATE, settings.promptTemplate.id)
            .putString(KEY_CUSTOM_TEMPLATE, customTemplate)
            .apply()
    }

    companion object {
        const val MIN_TEMPERATURE = 0.1f
        const val MAX_TEMPERATURE = 2.0f
        const val MIN_TOP_K = 1
        const val MAX_TOP_K = 100
        const val MIN_TOP_P = 0.05f
        const val MAX_TOP_P = 1.0f

        private const val PREFERENCES_NAME = "inference_settings"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_TOP_K = "top_k"
        private const val KEY_TOP_P = "top_p"
        private const val KEY_CONTEXT = "context_tokens"
        private const val KEY_CPU_THREADS = "cpu_threads"
        private const val KEY_TEMPLATE = "prompt_template"
        private const val KEY_CUSTOM_TEMPLATE = "custom_prompt_template"
    }
}
