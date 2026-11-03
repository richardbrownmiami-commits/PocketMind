package com.pocketmind

/** JNI entry points for the bundled CPU llama.cpp runtime. */
object NativeLlama {
    init {
        System.loadLibrary("pocketmind_jni")
    }

    external fun init(modelPath: String, nCtx: Int = 2048, nThreads: Int = 2): Long

    /** True only when the loaded GGUF declares a Llama 3 chat template. */
    external fun usesLlama3ChatFormat(handle: Long): Boolean

    /** True only for metadata-named Llama 3.2 1B/3B models using Meta's lightweight tool format. */
    external fun usesLlama32ToolFormat(handle: Long): Boolean

    /** Starts a fresh session or restores a compatible opaque sequence-state snapshot. */
    external fun startSession(handle: Long, systemPrompt: String, savedState: ByteArray? = null): Int

    private external fun generateWithRole(
        handle: Long,
        role: String,
        message: String,
        nPredict: Int,
        nThreads: Int
    ): String

    /** Appends only the new user message to the live context and generates a reply. */
    fun generate(handle: Long, userPrompt: String, nPredict: Int = 128, nThreads: Int = 2): String =
        generateWithRole(handle, "user", userPrompt, nPredict, nThreads)

    /** Appends a Llama 3.2 tool response using Meta's `ipython` role and generates the final answer. */
    fun generateToolResult(handle: Long, toolOutput: String, nPredict: Int = 128, nThreads: Int = 2): String =
        generateWithRole(handle, "ipython", toolOutput, nPredict, nThreads)

    /** Returns an opaque sequence-state snapshot, or null when it exceeds the native size cap. */
    external fun saveKvState(handle: Long): ByteArray?

    external fun unload(handle: Long)
}
