package com.pocketmind.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.pocketmind.PocketMindApplication
import com.pocketmind.R
import com.pocketmind.engine.ModelStore
import com.pocketmind.engine.RwkvEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RwkvFragment : Fragment() {
    private val store by lazy(LazyThreadSafetyMode.NONE) { ModelStore(requireContext().applicationContext) }
    private val controller get() = (requireActivity().application as PocketMindApplication).agentController
    private var scope: CoroutineScope? = null
    private var status: TextView? = null
    private var loadButton: MaterialButton? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importRwkv(uri)
    }

    override fun onCreateView(inflater: android.view.LayoutInflater, container: android.view.ViewGroup?, savedInstanceState: Bundle?): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        root.addView(TextView(requireContext()).apply {
            text = getString(R.string.rwkv_title)
            textSize = 24f
        })
        root.addView(TextView(requireContext()).apply {
            text = getString(R.string.rwkv_subtitle)
            setPadding(0, 8, 0, 20)
        })

        val card = MaterialCardView(requireContext()).apply {
            radius = 20f
            setContentPadding(20, 20, 20, 20)
        }
        val cardContent = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        cardContent.addView(TextView(requireContext()).apply {
            text = getString(R.string.rwkv_info)
            textSize = 16f
        })
        status = TextView(requireContext()).apply {
            setPadding(0, 16, 0, 16)
        }
        cardContent.addView(status)

        val importButton = MaterialButton(requireContext()).apply {
            text = getString(R.string.rwkv_import)
            setOnClickListener { picker.launch(arrayOf("application/octet-stream", "application/*", "*/*")) }
        }
        cardContent.addView(importButton)

        loadButton = MaterialButton(requireContext()).apply {
            text = getString(R.string.load_model)
            setOnClickListener { loadSelected() }
        }
        cardContent.addView(loadButton)
        card.addView(cardContent)
        root.addView(card)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        refresh()
    }

    private fun importRwkv(uri: Uri) {
        status?.setText(R.string.rwkv_importing)
        scope?.launch {
            try {
                val file = withContext(Dispatchers.IO) { store.import(uri) }
                if (!RwkvEngine.isCompatible(file)) {
                    withContext(Dispatchers.IO) { store.delete(file) }
                    status?.setText(R.string.rwkv_wrong_model)
                } else {
                    status?.text = getString(R.string.rwkv_imported, file.name)
                }
            } catch (e: Exception) {
                status?.text = e.message ?: getString(R.string.rwkv_import_failed)
            }
            refresh()
        }
    }

    private fun loadSelected() {
        val file = store.selected()
        if (file == null || !RwkvEngine.isCompatible(file)) {
            status?.setText(R.string.rwkv_select_first)
            return
        }
        status?.setText(R.string.rwkv_loading)
        scope?.launch {
            status?.text = controller.loadSelectedModel()
            refresh()
        }
    }

    private fun refresh() {
        val file = store.selected()
        status?.text = when {
            file == null -> getString(R.string.rwkv_no_model)
            !RwkvEngine.isCompatible(file) -> getString(R.string.rwkv_wrong_selected, file.name)
            else -> getString(R.string.rwkv_selected, file.name) + "\n" + controller.status()
        }
        loadButton?.isEnabled = file != null && RwkvEngine.isCompatible(file)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroyView() {
        scope?.cancel()
        scope = null
        status = null
        loadButton = null
        super.onDestroyView()
    }
}
