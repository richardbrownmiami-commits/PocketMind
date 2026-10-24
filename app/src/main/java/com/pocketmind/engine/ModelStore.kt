package com.pocketmind.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

class ModelStore(context: Context) {
    companion object { const val MAX_MODEL_BYTES: Long = 1_200L * 1024L * 1024L }
    private val app = context.applicationContext
    private val directory = File(app.filesDir, "models").apply { mkdirs() }
    private val prefs = app.getSharedPreferences("models", Context.MODE_PRIVATE)

    data class Model(val file: File, val bytes: Long)

    fun list(): List<Model> = directory.listFiles { f -> f.isFile && f.extension.equals("gguf", true) }
        .orEmpty().sortedBy { it.name.lowercase() }.map { Model(it, it.length()) }

    fun selected(): File? = prefs.getString("selected", null)?.let(::File)?.takeIf { it.isFile }

    fun select(file: File) {
        require(file.parentFile?.canonicalFile == directory.canonicalFile && file.extension.equals("gguf", true))
        prefs.edit().putString("selected", file.absolutePath).apply()
    }

    @Throws(Exception::class)
    fun import(uri: Uri): File {
        val resolver = app.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "model.gguf"
        if (!displayName.endsWith(".gguf", true)) throw IllegalArgumentException("Choose a .gguf model file")
        val knownSize = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (knownSize > MAX_MODEL_BYTES) throw IllegalArgumentException("Model exceeds the 1.2 GB limit")

        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "model.gguf" }
        val target = File(directory, "${System.currentTimeMillis()}_$safeName")
        var copied = 0L
        try {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > MAX_MODEL_BYTES) throw IllegalArgumentException("Model exceeds the 1.2 GB limit")
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            } ?: throw IllegalArgumentException("Could not read the selected file")
            if (copied == 0L) throw IllegalArgumentException("The selected model file is empty")
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        select(target)
        return target
    }
}
