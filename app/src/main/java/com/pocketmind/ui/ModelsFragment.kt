package com.pocketmind.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.pocketmind.PocketMindApplication
import com.pocketmind.R
import com.pocketmind.engine.ModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModelsFragment : Fragment(R.layout.fragment_models) {
    private data class ModelViews(
        val list: RecyclerView,
        val emptyState: TextView,
        val message: TextView,
        val status: TextView,
        val progress: LinearProgressIndicator,
        val importButton: MaterialButton,
        val loadButton: MaterialButton
    )

    private var views: ModelViews? = null
    private var adapter: ModelAdapter? = null
    private var viewScope: CoroutineScope? = null
    private val store by lazy(LazyThreadSafetyMode.NONE) { ModelStore(requireContext().applicationContext) }
    private val controller get() = (requireActivity().application as PocketMindApplication).agentController
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) import(uri)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val modelAdapter = ModelAdapter(
            onSelect = { model ->
                store.select(model.file)
                views?.message?.text = getString(R.string.model_selected, model.file.name)
                refresh()
            },
            onOverflow = ::showModelOptions
        )
        adapter = modelAdapter
        views = ModelViews(
            list = view.findViewById<RecyclerView>(R.id.model_list).apply {
                layoutManager = LinearLayoutManager(requireContext())
                adapter = modelAdapter
            },
            emptyState = view.findViewById(R.id.empty_model_state),
            message = view.findViewById(R.id.models_message),
            status = view.findViewById(R.id.models_status),
            progress = view.findViewById(R.id.import_progress),
            importButton = view.findViewById(R.id.import_model_button),
            loadButton = view.findViewById(R.id.load_model_button)
        )
        views?.importButton?.setOnClickListener {
            picker.launch(arrayOf("application/octet-stream", "application/*", "*/*"))
        }
        views?.loadButton?.setOnClickListener {
            if (controller.status().startsWith("Model loaded")) unload() else loadSelected()
        }
        refresh()
    }

    private fun import(uri: Uri) {
        views?.message?.text = getString(R.string.importing_model)
        views?.progress?.visibility = View.VISIBLE
        val context = requireContext().applicationContext
        viewScope?.launch {
            try {
                val file = withContext(Dispatchers.IO) { store.import(uri) }
                views?.message?.text = getString(R.string.model_imported, file.name)
            } catch (e: Exception) {
                views?.message?.text = e.message ?: getString(R.string.model_import_failed)
            } finally {
                views?.progress?.visibility = View.GONE
                refresh()
            }
        }
    }

    private fun refresh() {
        val activeViews = views ?: return
        val entries = store.list()
        val selectedPath = store.selected()?.absolutePath
        adapter?.submit(entries, selectedPath)
        activeViews.emptyState.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        activeViews.status.text = controller.status()
        activeViews.loadButton.text = getString(
            if (controller.status().startsWith("Model loaded")) R.string.unload_model else R.string.load_model
        )
        activeViews.loadButton.isEnabled = selectedPath != null || controller.status().startsWith("Model loaded")
    }

    private fun loadSelected() {
        if (store.selected() == null) {
            views?.message?.setText(R.string.select_model_first)
            return
        }
        views?.message?.setText(R.string.loading_model)
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

    private fun showModelOptions(model: ModelStore.Model) {
        val dialog = BottomSheetDialog(requireContext())
        val content = layoutInflater.inflate(R.layout.bottom_sheet_model_actions, null)
        content.findViewById<MaterialButton>(R.id.delete_model_button).setOnClickListener {
            dialog.dismiss()
            confirmDelete(model.file)
        }
        dialog.setContentView(content)
        dialog.show()
    }

    private fun confirmDelete(file: java.io.File) {
        if (controller.isModelLoaded(file.absolutePath)) {
            showLoadedDeleteError()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_model_title)
            .setMessage(getString(R.string.delete_model_message, file.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (controller.isModelLoaded(file.absolutePath)) {
                    showLoadedDeleteError()
                } else {
                    viewScope?.launch {
                        val deleted = withContext(Dispatchers.IO) { store.delete(file) }
                        views?.message?.text = getString(
                            if (deleted) R.string.model_deleted else R.string.model_delete_failed,
                            file.name
                        )
                        refresh()
                    }
                }
            }
            .show()
    }

    private fun showLoadedDeleteError() {
        views?.let { Snackbar.make(it.list, R.string.model_loaded_cannot_delete, Snackbar.LENGTH_LONG).show() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroyView() {
        viewScope?.cancel()
        viewScope = null
        views = null
        adapter = null
        super.onDestroyView()
    }
}
