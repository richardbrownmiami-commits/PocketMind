package com.pocketmind

/** JNI entry points for the bundled CPU llama.cpp runtime. */
object NativeLlama {
    init {
        System.loadLibrary("pocketmind_jni")
    }

    external fun init(modelPath: String, nCtx: Int = 2048, nThreads: Int = 2): Long

    /** Starts a fresh session or restores a compatible opaque sequence-state snapshot. */
    external fun startSession(handle: Long, systemPrompt: String, savedState: ByteArray? = null): Int

    /** Appends only the new user message to the live context and generates a reply. */
    external fun generate(handle: Long, userPrompt: String, nPredict: Int = 128, nThreads: Int = 2): String

    /** Returns an opaque sequence-state snapshot, or null when it exceeds the native size cap. */
    external fun saveKvState(handle: Long): ByteArray?

    external fun unload(handle: Long)
}
