package com.pocketmind.ui

import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.pocketmind.PocketMindApplication
import com.pocketmind.engine.ModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ModelsActivity : AppCompatActivity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store by lazy { ModelStore(this) }
    private lateinit var content: LinearLayout
    private lateinit var message: TextView
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> if (uri != null) import(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 16, 20, 16) }
        root.addView(TextView(this).apply { text = "On-device models"; textSize = 24f })
        message = TextView(this).apply { setPadding(0, 8, 0, 8) }
        root.addView(message)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(Button(this).apply { text = "Import GGUF"; setOnClickListener { picker.launch(arrayOf("application/octet-stream", "application/*", "*/*")) } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(Button(this).apply {
            text = "Load selected"
            setOnClickListener { loadSelected() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(controls)
        root.addView(Button(this).apply { text = "Unload model"; setOnClickListener { unload() } })
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        refresh()
    }

    private fun import(uri: Uri) {
        message.text = "Copying the model to private app storage…"
        uiScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { store.import(uri) }
                message.text = "Imported and selected ${file.name}."
                refresh()
            } catch (e: Exception) {
                message.text = e.message ?: "Model import failed."
            }
        }
    }

    private fun refresh() {
        if (!::content.isInitialized) return
        content.removeAllViews()
        val selected = store.selected()?.absolutePath
        val entries = store.list()
        if (entries.isEmpty()) content.addView(TextView(this).apply { text = "No GGUF models yet. Import a small Q4/Q5 model (up to 1.2 GB)." })
        entries.forEach { model ->
            content.addView(Button(this).apply {
                val size = "%.1f MB".format(model.bytes / 1024.0 / 1024.0)
                text = (if (model.file.absolutePath == selected) "Selected · " else "") + "${model.file.name} · $size"
                setOnClickListener { store.select(model.file); message.text = "Selected ${model.file.name}."; refresh() }
            })
        }
        message.text = "${entries.size} model(s) · ${((application as PocketMindApplication).agentController.status())}"
    }

    private fun loadSelected() {
        message.text = "Loading model with memory-safe settings…"
        uiScope.launch {
            message.text = (application as PocketMindApplication).agentController.loadSelectedModel()
            refresh()
        }
    }

    private fun unload() {
        uiScope.launch {
            message.text = (application as PocketMindApplication).agentController.unload()
            refresh()
        }
    }

    override fun onResume() { super.onResume(); refresh() }
    override fun onDestroy() { uiScope.cancel(); super.onDestroy() }
}
