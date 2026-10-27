package com.pocketmind.engine

import java.io.File

/**
 * RWKV engine policy for the dedicated RWKV page.
 *
 * Inference is provided by the pinned llama.cpp native backend already used by
 * PocketMind. The backend has native RWKV-6/RWKV-7 GGUF architecture support;
 * this class keeps RWKV model selection separate from the general Models page.
 */
object RwkvEngine {
    fun isCompatible(file: File): Boolean {
        val name = file.name.lowercase()
        return name.endsWith(".gguf") && name.contains("rwkv")
    }
}
