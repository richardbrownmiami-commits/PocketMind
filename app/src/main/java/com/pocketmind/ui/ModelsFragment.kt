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
import com.pocketmind.tools.AppLog
import kotlinx.coroutines.CancellationException
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
        if (uri != null) {
            AppLog.info(requireContext(), "MODEL_IMPORT_PICKER_RESULT", "type=GGUF outcome=selected")
            import(uri)
        } else AppLog.info(requireContext(), "MODEL_IMPORT_PICKER_CANCELLED", "type=GGUF")
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        AppLog.info(requireContext(), "MODELS_SCREEN_OPENED", "type=GGUF")
        viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val modelAdapter = ModelAdapter(
            onSelect = { model ->
                AppLog.info(requireContext(), "UI_CONTROL_TAPPED", "screen=models control=select_model")
                store.select(model.file)
                AppLog.info(requireContext(), "MODEL_SELECTED", "type=GGUF")
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
            AppLog.info(requireContext(), "MODEL_IMPORT_PICKER_OPENED", "type=GGUF")
            picker.launch(arrayOf("application/octet-stream", "application/*", "*/*"))
        }
        views?.loadButton?.setOnClickListener {
            val loaded = isLoadedSelection()
            AppLog.info(requireContext(), "UI_CONTROL_TAPPED", "screen=models control=${if (loaded) "unload_model" else "load_model"}")
            if (loaded) unload() else loadSelected()
        }
        refresh()
    }

    private fun import(uri: Uri) {
        AppLog.info(requireContext(), "MODEL_IMPORT_STARTED", "type=GGUF")
        views?.message?.text = getString(R.string.importing_model)
        views?.progress?.visibility = View.VISIBLE
        val context = requireContext().applicationContext
        viewScope?.launch {
            try {
                val file = withContext(Dispatchers.IO) { store.import(uri) }
                AppLog.info(context, "MODEL_IMPORT_COMPLETED", "type=GGUF success=true sizeBytes=${file.length()}")
                views?.message?.text = getString(R.string.model_imported, file.name)
            } catch (e: Exception) {
                val outcome = if (e is CancellationException) "cancelled" else "failed"
                AppLog.error(context, "MODEL_IMPORT_FAILED", e, "type=GGUF outcome=$outcome")
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
        AppLog.info(requireContext(), "MODEL_LIST_REFRESHED", "count=${entries.size} selected=${selectedPath != null} loaded=${isLoadedSelection()}")
        adapter?.submit(entries, selectedPath)
        activeViews.emptyState.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        activeViews.status.text = controller.status()
        activeViews.loadButton.text = getString(
            if (isLoadedSelection()) R.string.unload_model else R.string.load_model
        )
        activeViews.loadButton.isEnabled = selectedPath != null || controller.status().startsWith("Model loaded")
    }

    private fun isLoadedSelection(): Boolean {
        val selectedPath = store.selected()?.absolutePath
        return if (selectedPath == null) {
            controller.status().startsWith("Model loaded")
        } else {
            controller.isModelLoaded(selectedPath)
        }
    }

    private fun loadSelected() {
        if (store.selected() == null) {
            views?.message?.setText(R.string.select_model_first)
            AppLog.info(requireContext(), "MODEL_LOAD_IGNORED", "reason=no_selection")
            return
        }
        views?.message?.setText(R.string.loading_model)
        AppLog.info(requireContext(), "MODEL_LOAD_REQUESTED", "type=GGUF")
        val selectedPath = store.selected()?.absolutePath
        viewScope?.launch {
            try {
                val result = controller.loadSelectedModel()
                val loaded = selectedPath?.let(controller::isModelLoaded) == true
                AppLog.info(requireContext(), "MODEL_LOAD_FINISHED", "type=GGUF outcome=${if (loaded) "success" else "failed"}")
                views?.message?.text = result
                refresh()
            } catch (e: CancellationException) {
                AppLog.info(requireContext(), "MODEL_LOAD_FINISHED", "type=GGUF outcome=cancelled")
                throw e
            } catch (e: Exception) {
                AppLog.error(requireContext(), "MODEL_LOAD_FAILED", e, "type=GGUF")
                throw e
            }
        }
    }

    private fun unload() {
        AppLog.info(requireContext(), "MODEL_UNLOAD_REQUESTED_FROM_UI", "type=GGUF")
        viewScope?.launch {
            try {
                val result = controller.unload()
                AppLog.info(requireContext(), "MODEL_UNLOAD_FINISHED", "type=GGUF loaded=${controller.status().startsWith("Model loaded")}")
                views?.message?.text = result
                refresh()
            } catch (e: CancellationException) {
                AppLog.info(requireContext(), "MODEL_UNLOAD_FINISHED", "type=GGUF outcome=cancelled")
                throw e
            } catch (e: Exception) {
                AppLog.error(requireContext(), "MODEL_UNLOAD_FAILED", e, "type=GGUF")
                throw e
            }
        }
    }

    private fun showModelOptions(model: ModelStore.Model) {
        AppLog.info(requireContext(), "MODEL_OPTIONS_OPENED", "type=GGUF")
        val dialog = BottomSheetDialog(requireContext())
        val content = layoutInflater.inflate(R.layout.bottom_sheet_model_actions, null)
        dialog.setOnDismissListener { AppLog.info(requireContext(), "MODEL_OPTIONS_DISMISSED", "type=GGUF") }
        content.findViewById<MaterialButton>(R.id.delete_model_button).setOnClickListener {
            AppLog.info(requireContext(), "UI_CONTROL_TAPPED", "screen=models control=delete_model")
            dialog.dismiss()
            confirmDelete(model.file)
        }
        dialog.setContentView(content)
        dialog.show()
    }

    private fun confirmDelete(file: java.io.File) {
        if (controller.isModelLoaded(file.absolutePath)) {
            AppLog.warn(requireContext(), "MODEL_DELETE_BLOCKED", "type=GGUF reason=model_loaded")
            showLoadedDeleteError()
            return
        }
        AppLog.info(requireContext(), "MODEL_DELETE_CONFIRMATION_SHOWN", "type=GGUF")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_model_title)
            .setMessage(getString(R.string.delete_model_message, file.name))
            .setNegativeButton(R.string.cancel) { _, _ -> AppLog.info(requireContext(), "MODEL_DELETE_CONFIRMATION_RESULT", "type=GGUF outcome=cancelled") }
            .setPositiveButton(R.string.delete) { _, _ ->
                AppLog.info(requireContext(), "MODEL_DELETE_CONFIRMATION_RESULT", "type=GGUF outcome=confirmed")
                if (controller.isModelLoaded(file.absolutePath)) {
                    AppLog.warn(requireContext(), "MODEL_DELETE_BLOCKED", "type=GGUF reason=model_loaded_at_confirmation")
                    showLoadedDeleteError()
                } else {
                    AppLog.info(requireContext(), "MODEL_DELETE_REQUESTED", "type=GGUF")
                    val appContext = requireContext().applicationContext
                    viewScope?.launch {
                        try {
                            val deleted = withContext(Dispatchers.IO) { store.delete(file) }
                            AppLog.info(appContext, "MODEL_DELETE_FINISHED", "type=GGUF outcome=${if (deleted) "deleted" else "not_found"}")
                            views?.message?.text = getString(
                                if (deleted) R.string.model_deleted else R.string.model_delete_failed,
                                file.name
                            )
                            refresh()
                        } catch (e: CancellationException) {
                            AppLog.info(appContext, "MODEL_DELETE_FINISHED", "type=GGUF outcome=cancelled")
                            throw e
                        } catch (e: Exception) {
                            AppLog.error(appContext, "MODEL_DELETE_FAILED", e, "type=GGUF")
                            throw e
                        }
                    }
                }
            }
            .setOnCancelListener { AppLog.info(requireContext(), "MODEL_DELETE_CONFIRMATION_RESULT", "type=GGUF outcome=dismissed") }
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
        AppLog.info(requireContext(), "MODEL_SCREEN_TASKS_CANCELLED", "reason=view_destroyed")
        viewScope?.cancel()
        viewScope = null
        views = null
        adapter = null
        super.onDestroyView()
    }
}
