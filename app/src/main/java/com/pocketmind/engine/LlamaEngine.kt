package com.pocketmind.engine

/** One native instance; callers must serialize load/generate/unload on the model queue. */
class LlamaEngine {
    companion object {
        init { System.loadLibrary("pocketmind") }
    }

    fun load(path: String, nCtx: Int = 512, nBatch: Int = 256, nUbatch: Int = 128, threads: Int = 2) {
        require(nCtx in 128..1024) { "Context must be between 128 and 1024 tokens" }
        require(nBatch in 32..512 && nUbatch in 16..nBatch) { "Invalid batch settings" }
        require(threads in 1..4) { "Threads must be between 1 and 4" }
        nativeLoad(path, nCtx, nBatch, nUbatch, threads)
    }

    fun generate(prompt: String, maxTokens: Int = 128, grammar: String? = null, onToken: (String) -> Unit = {}): String {
        require(maxTokens in 1..512) { "maxTokens must be between 1 and 512" }
        return nativeGenerate(prompt, maxTokens, grammar, TokenSink(onToken))
    }

    fun countTokens(text: String): Int = nativeCountTokens(text)
    fun cancel() = nativeCancel()
    fun unload() = nativeUnload()
    fun stats(): String = nativeStats()

    private fun interface TokenSink { fun onToken(chunk: String) }

    private external fun nativeLoad(path: String, nCtx: Int, nBatch: Int, nUbatch: Int, threads: Int)
    private external fun nativeGenerate(prompt: String, maxTokens: Int, grammar: String?, sink: TokenSink): String
    private external fun nativeCountTokens(text: String): Int
    private external fun nativeCancel()
    private external fun nativeUnload()
    private external fun nativeStats(): String
}
