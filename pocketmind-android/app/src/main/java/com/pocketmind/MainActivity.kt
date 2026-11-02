package com.pocketmind

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private var modelHandle: Long = 0L
    private lateinit var agent: LocalAgent
    private lateinit var memoryStore: MemoryStore
    private val fileExecutor = Executors.newSingleThreadExecutor()
    private val requestModel = 42

    private lateinit var statusView: TextView
    private lateinit var loadButton: Button
    private lateinit var unloadButton: Button
    private lateinit var clearMemoryButton: Button
    private lateinit var promptBox: EditText
    private lateinit var allowWebBox: CheckBox
    private lateinit var sendButton: Button
    private lateinit var outputView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        memoryStore = MemoryStore(applicationContext)
        agent = LocalAgent(memoryStore)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        statusView = TextView(this).apply { text = "Checking saved model…" }
        loadButton = Button(this).apply { text = "Import GGUF model"; isEnabled = false }
        unloadButton = Button(this).apply { text = "Unload model"; isEnabled = false }
        clearMemoryButton = Button(this).apply { text = "Clear saved memory" }
        promptBox = EditText(this).apply {
            hint = "Ask your local model"
            minLines = 3
            maxLines = 8
            gravity = android.view.Gravity.TOP
        }
        allowWebBox = CheckBox(this).apply {
            text = "Allow web search/fetch for this reply"
            isChecked = false
        }
        sendButton = Button(this).apply { text = "Send"; isEnabled = false }
        outputView = TextView(this).apply {
            text = "Prior turns are kept in the live KV cache; no plaintext transcript is saved."
            setPadding(0, 16, 0, 0)
            setTextIsSelectable(true)
        }

        content.addView(statusView)
        content.addView(loadButton)
        content.addView(unloadButton)
        content.addView(clearMemoryButton)
        content.addView(promptBox)
        content.addView(allowWebBox)
        content.addView(sendButton)
        content.addView(outputView)
        setContentView(ScrollView(this).apply { addView(content) })

        loadButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, requestModel)
        }
        unloadButton.setOnClickListener { unloadCurrentModel() }
        clearMemoryButton.setOnClickListener { clearSavedMemory() }
        sendButton.setOnClickListener {
            val prompt = promptBox.text.toString()
            val handle = modelHandle
            val allowWeb = allowWebBox.isChecked
            setBusy(true)
            statusView.text = "Running locally${if (allowWeb) " (web allowed for this reply)" else ""}…"
            agent.execute(handle, prompt, allowWeb) { result ->
                if (isFinishing || isDestroyed) return@execute
                outputView.text = when {
                    result.error != null -> "Error: ${result.error}"
                    result.sources.isNotEmpty() -> result.text + "\n\nSources:\n" + result.sources.joinToString("\n")
                    else -> result.text
                }
                statusView.text = when (result.memorySaved) {
                    true -> "Model: loaded • KV memory saved locally"
                    false -> "Model: loaded • live KV only (snapshot exceeds 64 MiB or save failed)"
                    null -> if (modelHandle != 0L) "Model: loaded" else "Model: not loaded"
                }
                setBusy(false)
            }
        }

        restoreLastModel()
    }

    @Deprecated("Use Activity Result APIs when adding AndroidX Activity dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != requestModel || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val displayName = queryDisplayName(uri)
        if (displayName == null || !displayName.endsWith(".gguf", ignoreCase = true)) {
            Toast.makeText(this, "Choose a GGUF model file", Toast.LENGTH_LONG).show()
            return
        }

        val previousHandle = modelHandle
        modelHandle = 0L
        setBusy(true)
        statusView.text = "Copying and loading model…"
        try {
            fileExecutor.execute {
                var destination: File? = null
                try {
                    val modelsDirectory = File(filesDir, "models")
                    if (!modelsDirectory.exists() && !modelsDirectory.mkdirs()) {
                        throw IllegalStateException("Cannot create model storage")
                    }
                    val modelFile = File(modelsDirectory, "model-${UUID.randomUUID()}.gguf")
                    destination = modelFile
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Cannot read selected file")
                    input.use { source ->
                        FileOutputStream(modelFile).use { target -> copyStream(source, target) }
                    }
                    if (modelFile.length() == 0L) throw IllegalStateException("Selected model file is empty")
                    agent.loadModel(modelFile.absolutePath, previousHandle) { handle, resumed, error ->
                        if (isFinishing || isDestroyed) return@loadModel
                        if (handle != 0L && error == null) {
                            modelHandle = handle
                            outputView.text = if (resumed) {
                                "Saved local model context restored. Ask a follow-up without re-entering earlier turns."
                            } else {
                                "Model loaded. Enter a prompt and tap Send."
                            }
                            statusView.text = if (resumed) "Model: loaded • saved KV memory restored" else "Model: loaded"
                            setBusy(false)
                        } else {
                            modelFile.delete()
                            modelHandle = 0L
                            statusView.text = "Model: not loaded"
                            outputView.text = error ?: "Model could not be loaded"
                            setBusy(false)
                        }
                    }
                } catch (_: Exception) {
                    destination?.delete()
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        modelHandle = previousHandle
                        statusView.text = if (previousHandle != 0L) "Model: loaded" else "Model: not loaded"
                        outputView.text = "Import failed. Check that the selected file is a readable GGUF model."
                        setBusy(false)
                    }
                }
            }
        } catch (_: Exception) {
            modelHandle = previousHandle
            statusView.text = if (previousHandle != 0L) "Model: loaded" else "Model: not loaded"
            setBusy(false)
        }
    }

    private fun restoreLastModel() {
        try {
            fileExecutor.execute {
                val saved = runCatching { memoryStore.lastModel() }.getOrNull()
                if (saved == null) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            statusView.text = "Model: not loaded"
                            loadButton.isEnabled = true
                        }
                    }
                    return@execute
                }
                val modelFile = File(saved.path)
                val modelsRoot = File(filesDir, "models").canonicalFile
                val canonical = modelFile.canonicalFile
                val insideAppModels = canonical.path.startsWith(modelsRoot.path + File.separator)
                if (!insideAppModels || !canonical.isFile || !canonical.name.endsWith(".gguf", ignoreCase = true)) {
                    memoryStore.clearLastModel()
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            statusView.text = "Saved model file not found"
                            outputView.text = "Import the GGUF model again to restore local memory."
                            loadButton.isEnabled = true
                        }
                    }
                    return@execute
                }
                agent.loadModel(canonical.absolutePath, 0L) { handle, resumed, error ->
                    if (isFinishing || isDestroyed) return@loadModel
                    if (handle != 0L && error == null) {
                        modelHandle = handle
                        statusView.text = if (resumed) "Model: loaded • saved KV memory restored" else "Model: loaded • fresh session"
                        outputView.text = if (resumed) {
                            "Previous local context restored from private storage. No plaintext transcript was stored."
                        } else {
                            "Model loaded with a fresh local context."
                        }
                        loadButton.isEnabled = true
                        unloadButton.isEnabled = true
                        sendButton.isEnabled = true
                    } else {
                        statusView.text = "Saved model could not be loaded"
                        outputView.text = error ?: "Import a compatible GGUF model."
                        loadButton.isEnabled = true
                    }
                }
            }
        } catch (_: Exception) {
            statusView.text = "Model: not loaded"
            loadButton.isEnabled = true
        }
    }

    private fun unloadCurrentModel() {
        val handle = modelHandle
        if (handle == 0L) return
        modelHandle = 0L
        setBusy(true)
        statusView.text = "Saving KV memory and unloading…"
        agent.unloadModel(handle) {
            if (isFinishing || isDestroyed) return@unloadModel
            statusView.text = "Model: not loaded • saved memory kept"
            outputView.text = "Model unloaded. The imported GGUF and compatible KV memory remain in app-private storage."
            setBusy(false)
        }
    }

    private fun clearSavedMemory() {
        setBusy(true)
        statusView.text = "Clearing saved and live conversation memory…"
        agent.clearMemory(modelHandle) { ok ->
            if (isFinishing || isDestroyed) return@clearMemory
            outputView.text = if (ok) {
                "Saved KV state cleared. Web results are not stored; the imported model file remains."
            } else {
                "Memory clear did not finish; try again."
            }
            statusView.text = if (modelHandle != 0L) "Model: loaded • fresh context" else "Model: not loaded • memory cleared"
            setBusy(false)
        }
    }

    private fun setBusy(busy: Boolean) {
        loadButton.isEnabled = !busy
        unloadButton.isEnabled = !busy && modelHandle != 0L
        sendButton.isEnabled = !busy && modelHandle != 0L
        clearMemoryButton.isEnabled = !busy
    }

    override fun onDestroy() {
        fileExecutor.shutdownNow()
        agent.close(modelHandle)
        modelHandle = 0L
        super.onDestroy()
    }

    private fun queryDisplayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            } else null
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    private fun copyStream(input: InputStream, output: FileOutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        output.fd.sync()
    }
}
