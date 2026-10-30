package com.pocketmind.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CancellationException
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
            refreshLog(keepPosition = true, source = "periodic")
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
            AppLog.info(requireContext(), "UI_CONTROL_TAPPED", "screen=debug_logger control=refresh")
            AppLog.info(requireContext(), "DEBUG_LOG_REFRESH_REQUESTED")
            refreshLog(keepPosition = false, source = "manual")
        }
        view.findViewById<MaterialButton>(R.id.debug_log_clear).setOnClickListener {
            AppLog.info(requireContext(), "UI_CONTROL_TAPPED", "screen=debug_logger control=clear_logs")
            AppLog.info(requireContext(), "DEBUG_LOG_CLEAR_CONFIRMATION_SHOWN")
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.debug_log_clear_title)
                .setMessage(R.string.debug_log_clear_message)
                .setNegativeButton(R.string.cancel) { _, _ -> AppLog.info(requireContext(), "DEBUG_LOG_CLEAR_CONFIRMATION_RESULT", "outcome=cancelled") }
                .setPositiveButton(R.string.clear) { _, _ ->
                    AppLog.info(requireContext(), "DEBUG_LOG_CLEAR_CONFIRMATION_RESULT", "outcome=confirmed")
                    val appContext = requireContext().applicationContext
                    AppLog.info(appContext, "DEBUG_LOG_CLEAR_STARTED")
                    viewScope?.launch {
                        try {
                            withContext(Dispatchers.IO) { AppLog.clear(appContext) }
                            AppLog.info(appContext, "DEBUG_LOG_CLEARED", "outcome=success")
                            refreshLog(keepPosition = false, source = "after_clear")
                        } catch (e: CancellationException) {
                            AppLog.info(appContext, "DEBUG_LOG_CLEAR_FINISHED", "outcome=cancelled")
                            throw e
                        } catch (e: Exception) {
                            AppLog.error(appContext, "DEBUG_LOG_CLEAR_FAILED", e)
                            throw e
                        }
                    }
                }
                .setOnCancelListener { AppLog.info(requireContext(), "DEBUG_LOG_CLEAR_CONFIRMATION_RESULT", "outcome=dismissed") }
                .show()
        }
        refreshLog(keepPosition = false, source = "screen_open")
    }

    override fun onResume() {
        super.onResume()
        AppLog.info(requireContext(), "DEBUG_LOG_SCREEN_RESUMED")
        refreshLog(keepPosition = true, source = "resume")
        handler.removeCallbacks(refreshTask)
        handler.postDelayed(refreshTask, REFRESH_INTERVAL_MS)
    }

    override fun onPause() {
        AppLog.info(requireContext(), "DEBUG_LOG_SCREEN_PAUSED")
        handler.removeCallbacks(refreshTask)
        super.onPause()
    }

    private fun refreshLog(keepPosition: Boolean, source: String = "manual") {
        val context = context?.applicationContext ?: return
        val textView = logText ?: return
        if (refreshPending) return
        val wasAtBottom = scroll?.let { container ->
            container.getChildAt(0)?.let { child ->
                container.scrollY + container.height >= child.height - SCROLL_TOLERANCE_PX
            }
        }
        refreshPending = true
        if (source != "periodic") AppLog.info(context, "DEBUG_LOG_REFRESH_STARTED", "source=$source")
        viewScope?.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { AppLog.recentText(context) }
                if (logText !== textView) {
                    if (source != "periodic") AppLog.info(context, "DEBUG_LOG_REFRESH_FINISHED", "source=$source outcome=cancelled reason=view_destroyed")
                    return@launch
                }
                if (textView.text.toString() != snapshot) {
                    textView.text = snapshot
                    if (!keepPosition || wasAtBottom == true) {
                        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
                    }
                }
                if (source != "periodic") AppLog.info(context, "DEBUG_LOG_REFRESH_FINISHED", "source=$source outcome=success characters=${snapshot.length}")
            } catch (e: CancellationException) {
                if (source != "periodic") AppLog.info(context, "DEBUG_LOG_REFRESH_FINISHED", "source=$source outcome=cancelled")
                throw e
            } catch (e: Exception) {
                AppLog.error(context, "DEBUG_LOG_REFRESH_FAILED", e, "source=$source")
                throw e
            } finally {
                refreshPending = false
            }
        }
    }

    override fun onDestroyView() {
        AppLog.info(requireContext(), "DEBUG_LOG_SCREEN_TASKS_CANCELLED", "reason=view_destroyed")
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
