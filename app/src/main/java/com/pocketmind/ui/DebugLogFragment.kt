package com.pocketmind.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pocketmind.R
import com.pocketmind.tools.AppLog

class DebugLogFragment : Fragment(R.layout.fragment_debug_log) {
    private val handler = Handler(Looper.getMainLooper())
    private var logText: TextView? = null
    private var scroll: NestedScrollView? = null
    private var viewScope: CoroutineScope? = null
    private var refreshPending = false

    private val refreshTask = object : Runnable {
        override fun run() {
            refreshLog(keepPosition = true)
            if (isAdded && view != null) handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        logText = view.findViewById(R.id.debug_log_text)
        scroll = view.findViewById(R.id.debug_log_scroll)
        AppLog.info(requireContext(), "DEBUG_LOG_SCREEN_OPENED")
        view.findViewById<MaterialButton>(R.id.debug_log_refresh).setOnClickListener {
            AppLog.info(requireContext(), "DEBUG_LOG_REFRESH_REQUESTED")
            refreshLog(keepPosition = false)
        }
        view.findViewById<MaterialButton>(R.id.debug_log_clear).setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.debug_log_clear_title)
                .setMessage(R.string.debug_log_clear_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.clear) { _, _ ->
                    val appContext = requireContext().applicationContext
                    viewScope?.launch {
                        withContext(Dispatchers.IO) { AppLog.clear(appContext) }
                        AppLog.info(appContext, "DEBUG_LOG_CLEARED")
                        refreshLog(keepPosition = false)
                    }
                }
                .show()
        }
        refreshLog(keepPosition = false)
    }

    override fun onResume() {
        super.onResume()
        refreshLog(keepPosition = true)
        handler.removeCallbacks(refreshTask)
        handler.postDelayed(refreshTask, REFRESH_INTERVAL_MS)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshTask)
        super.onPause()
    }

    private fun refreshLog(keepPosition: Boolean) {
        val context = context?.applicationContext ?: return
        val textView = logText ?: return
        if (refreshPending) return
        val wasAtBottom = scroll?.let { container ->
            container.getChildAt(0)?.let { child ->
                container.scrollY + container.height >= child.height - SCROLL_TOLERANCE_PX
            }
        }
        refreshPending = true
        viewScope?.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { AppLog.recentText(context) }
                if (logText !== textView) return@launch
                if (textView.text.toString() != snapshot) {
                    textView.text = snapshot
                    if (!keepPosition || wasAtBottom == true) {
                        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
                    }
                }
            } finally {
                refreshPending = false
            }
        }
    }

    override fun onDestroyView() {
        handler.removeCallbacks(refreshTask)
        viewScope?.cancel()
        viewScope = null
        refreshPending = false
        logText = null
        scroll = null
        super.onDestroyView()
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 2000L
        private const val SCROLL_TOLERANCE_PX = 24
    }
}
