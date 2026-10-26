package com.pocketmind.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.pocketmind.PocketMindApplication
import com.pocketmind.R
import com.pocketmind.engine.ModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ModelsFragment : Fragment(R.layout.fragment_models) {
    private data class ModelViews(
        val list: LinearLayout,
        val emptyState: LinearLayout,
        val count: TextView,
        val message: TextView,
        val status: TextView,
        val progress: ProgressBar,
        val importButton: MaterialCardView,
        val loadButton: MaterialButton,
        val unloadButton: MaterialButton
    )

    private var views: ModelViews? = null
    private var viewScope: CoroutineScope? = null
    private val store by lazy(LazyThreadSafetyMode.NONE) { ModelStore(requireContext().applicationContext) }
    private val controller get() = (requireActivity().application as PocketMindApplication).agentController
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) import(uri)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        views = ModelViews(
            list = view.findViewById(R.id.model_list),
            emptyState = view.findViewById(R.id.empty_model_state),
            count = view.findViewById(R.id.model_count),
            message = view.findViewById(R.id.models_message),
            status = view.findViewById(R.id.models_status),
            progress = view.findViewById(R.id.import_progress),
            importButton = view.findViewById(R.id.import_model_button),
            loadButton = view.findViewById(R.id.load_model_button),
            unloadButton = view.findViewById(R.id.unload_model_button)
        )
        views?.importButton?.setOnClickListener {
            picker.launch(arrayOf("application/octet-stream", "application/*", "*/*"))
        }
        views?.loadButton?.setOnClickListener { loadSelected() }
        views?.unloadButton?.setOnClickListener { unload() }
        refresh()
    }

    private fun import(uri: Uri) {
        val activeViews = views ?: return
        activeViews.message.text = "Copying the model to private app storage…"
        activeViews.progress.visibility = View.VISIBLE
        viewScope?.launch {
            try {
                val file = withContext(Dispatchers.IO) { store.import(uri) }
                views?.message?.text = "Imported and selected ${file.name}."
            } catch (e: Exception) {
                views?.message?.text = e.message ?: "Model import failed."
            } finally {
                views?.progress?.visibility = View.GONE
                refresh()
            }
        }
    }

    private fun refresh() {
        val activeViews = views ?: return
        activeViews.list.removeAllViews()
        val selected = store.selected()?.absolutePath
        val entries = store.list()
        activeViews.count.text = getString(R.string.model_count, entries.size)
        activeViews.emptyState.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        activeViews.list.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        if (entries.isEmpty()) {
            activeViews.message.text = ""
        }
        entries.forEach { model ->
            activeViews.list.addView(modelCard(model.file, model.bytes, model.file.absolutePath == selected))
        }
        activeViews.status.text = controller.status()
        if (entries.isNotEmpty() && activeViews.message.text.isNullOrBlank()) {
            activeViews.message.text = "${entries.size} model(s) available."
        }
    }

    private fun modelCard(file: File, bytes: Long, selected: Boolean): MaterialCardView {
        val context = requireContext()
        val card = MaterialCardView(context).apply {
            radius = resources.getDimension(R.dimen.radius_card)
            cardElevation = resources.getDimension(R.dimen.elevation_card)
            setCardBackgroundColor(ContextCompat.getColor(context, if (selected) R.color.surface_variant else R.color.surface))
            setContentPadding(
                resources.getDimensionPixelSize(R.dimen.space_12),
                resources.getDimensionPixelSize(R.dimen.space_12),
                resources.getDimensionPixelSize(R.dimen.space_12),
                resources.getDimensionPixelSize(R.dimen.space_12)
            )
        }
        val size = "%.1f MB".format(bytes / 1024.0 / 1024.0)
        val label = TextView(context).apply {
            setTextAppearance(R.style.TextAppearance_PocketMind_Body)
            text = (if (selected) "Selected · " else "") + "${file.name} · $size"
        }
        card.addView(label, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.setOnClickListener {
            store.select(file)
            views?.message?.text = "Selected ${file.name}."
            refresh()
        }
        return card.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = resources.getDimensionPixelSize(R.dimen.space_8)
            }
        }
    }

    private fun loadSelected() {
        views?.message?.text = "Loading model with memory-safe settings…"
        viewScope?.launch {
            val result = controller.loadSelectedModel()
            views?.message?.text = result
            refresh()
        }
    }

    private fun unload() {
        viewScope?.launch {
            val result = controller.unload()
            views?.message?.text = result
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroyView() {
        viewScope?.cancel()
        viewScope = null
        views = null
        super.onDestroyView()
    }
}
