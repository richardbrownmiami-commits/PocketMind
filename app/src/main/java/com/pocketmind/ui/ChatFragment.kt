package com.pocketmind.ui

import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.pocketmind.PocketMindApplication
import com.pocketmind.R
import com.pocketmind.core.GenerationService
import com.pocketmind.memory.AppDatabase
import com.pocketmind.memory.MessageStore
import com.pocketmind.tools.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class ChatFragment : Fragment(R.layout.fragment_chat) {
    private data class ChatViews(
        val list: RecyclerView,
        val status: TextView,
        val input: TextInputEditText,
        val send: FloatingActionButton
    )

    private var views: ChatViews? = null
    private var adapter: ChatAdapter? = null
    private var viewScope: CoroutineScope? = null
    private var thinkingJob: Job? = null
    private var statusJob: Job? = null
    private var thinkingIndex = 0
    private var generationActive = false
    private val handler = Handler(Looper.getMainLooper())
    private val thinkingMessages = listOf(
        "Sooch raha hoon…",
        "Aur zor se sooch raha hoon…",
        "Dimag ki nassein phad raha hoon…",
        "Kya pooch liya yaar…",
        "Abhi batata hoon, ruk…",
        "Ek second, dimag daud raha hai…",
        "Bahut gehri baat hai yeh…",
        "Processing kar raha hoon…"
    )

    private val controller get() = (requireActivity().application as PocketMindApplication).agentController

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val chatAdapter = ChatAdapter()
        val chatViews = ChatViews(
            list = view.findViewById<RecyclerView>(R.id.chat_list).apply {
                layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
                adapter = chatAdapter
            },
            status = view.findViewById(R.id.status_bar),
            input = view.findViewById(R.id.message_input),
            send = view.findViewById(R.id.send_button)
        )
        views = chatViews
        adapter = chatAdapter
        AppLog.info(requireContext(), "CHAT_SCREEN_OPENED")
        chatViews.status.text = controller.status()
        chatViews.send.isEnabled = false
        updateSendButton(generationActive)
        chatViews.send.setOnClickListener {
            if (generationActive) {
                AppLog.info(requireContext(), "UI_CONTROL_TAPPED", "screen=chat control=stop_button")
                AppLog.info(requireContext(), "CHAT_STOP_REQUESTED", "source=stop_button")
                controller.stop("stop_button")
            } else {
                AppLog.info(requireContext(), "UI_CONTROL_TAPPED", "screen=chat control=send_button")
                submit("send_button")
            }
        }
        chatViews.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                AppLog.info(requireContext(), "UI_CONTROL_TAPPED", "screen=chat control=keyboard_send")
                if (!generationActive && chatViews.send.isEnabled) submit("keyboard_send")
                else AppLog.info(requireContext(), "CHAT_SUBMISSION_REJECTED", "source=keyboard_send reason=${if (generationActive) "generation_active" else "button_disabled"}")
                true
            } else false
        }
        loadHistory()
    }

    private fun loadHistory() {
        val context = requireContext().applicationContext
        AppLog.info(context, "CHAT_HISTORY_LOAD_STARTED")
        viewScope?.launch {
            try {
                val history = withContext(Dispatchers.IO) {
                    val db = AppDatabase(context)
                    try { MessageStore(db).recent(60).map { ChatAdapter.Line(it.role, it.content, it.time) } }
                    finally { db.close() }
                }
                val activeViews = views
                if (activeViews == null) {
                    AppLog.info(context, "CHAT_HISTORY_LOAD_FINISHED", "outcome=cancelled reason=view_destroyed")
                    return@launch
                }
                adapter?.replace(history)
                AppLog.info(context, "CHAT_HISTORY_LOADED", "messages=${history.size}")
                AppLog.info(context, "CHAT_HISTORY_LOAD_FINISHED", "outcome=success messages=${history.size}")
                activeViews.send.isEnabled = true
                updateSendButton(generationActive)
                scrollToBottom()
            } catch (e: CancellationException) {
                AppLog.info(context, "CHAT_HISTORY_LOAD_FINISHED", "outcome=cancelled")
                throw e
            } catch (e: Exception) {
                AppLog.error(context, "CHAT_HISTORY_LOAD_FAILED", e)
                throw e
            }
        }
    }

    private fun submit(source: String) {
        val logContext = context?.applicationContext
        val activeViews = views
        if (activeViews == null) {
            logContext?.let { AppLog.info(it, "CHAT_SUBMISSION_REJECTED", "source=$source reason=view_unavailable") }
            return
        }
        val text = activeViews.input.text?.toString()?.trim().orEmpty()
        val rejectionReason = when {
            text.isEmpty() -> "empty_message"
            !activeViews.send.isEnabled -> "button_disabled"
            generationActive -> "generation_active"
            else -> null
        }
        if (rejectionReason != null) {
            logContext?.let { AppLog.info(it, "CHAT_SUBMISSION_REJECTED", "source=$source reason=$rejectionReason") }
            return
        }
        AppLog.info(requireContext(), "CHAT_MESSAGE_SUBMITTED", "source=$source characters=${text.length}")
        activeViews.input.setText("")
        adapter?.add("user", text, animateOnInsert = true)
        val answerRow = adapter?.add("assistant", "", thinking = true, timestampFinalized = false) ?: return
        scrollToBottom()
        generationActive = true
        updateSendButton(true)
        startStatusUpdates()
        startThinking(answerRow)
        val context = requireContext()
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GenerationService::class.java).setAction(GenerationService.ACTION_START)
            )
        } catch (e: Exception) {
            AppLog.error(context, "GENERATION_SERVICE_START_FAILED", e)
        }

        val streamed = StringBuilder()
        val streamLock = Any()
        val uiUpdatePending = AtomicBoolean(false)
        var renderedLength = 0
        val firstToken = AtomicBoolean(false)
        val requestStartedAt = SystemClock.elapsedRealtime()
        var requestOutcome = "success"

        fun scheduleStreamUpdate() {
            if (!uiUpdatePending.compareAndSet(false, true)) return
            handler.postDelayed({
                val snapshot: String
                synchronized(streamLock) { snapshot = streamed.toString() }
                if (views != null && snapshot.length > renderedLength) {
                    renderedLength = snapshot.length
                    adapter?.update(answerRow, snapshot)
                    scrollToBottom()
                }
                uiUpdatePending.set(false)
                val needsAnotherUpdate = synchronized(streamLock) { streamed.length > renderedLength }
                if (needsAnotherUpdate && views != null) scheduleStreamUpdate()
            }, STREAM_UPDATE_MS)
        }

        viewScope?.launch {
            try {
                val reply = controller.send(text) { chunk ->
                    if (firstToken.compareAndSet(false, true)) {
                        AppLog.info(context, "CHAT_FIRST_TOKEN", "durationMs=${SystemClock.elapsedRealtime() - requestStartedAt}")
                        handler.post { stopThinking() }
                    }
                    synchronized(streamLock) { streamed.append(chunk) }
                    scheduleStreamUpdate()
                }
                adapter?.update(answerRow, reply.text, reply.actions, time = reply.completedAtMillis)
                requestOutcome = if (reply.text == "Generation was stopped or failed.") "generation_stopped_or_failed" else "success"
                AppLog.info(context, "CHAT_REPLY_READY", "actions=${reply.actions.size} responseCharacters=${reply.text.length} outcome=$requestOutcome")
            } catch (e: Exception) {
                requestOutcome = if (e is CancellationException) "cancelled" else "failed"
                AppLog.error(context, "CHAT_REQUEST_FAILED", e, "outcome=$requestOutcome")
                adapter?.update(answerRow, "PocketMind could not finish: ${e.message ?: "unexpected error"}", time = System.currentTimeMillis())
            } finally {
                stopThinking()
                stopStatusUpdates()
                generationActive = false
                views?.let {
                    it.status.text = controller.status()
                    it.send.isEnabled = true
                    updateSendButton(false)
                }
                val serviceWasRunning = context.stopService(Intent(context, GenerationService::class.java))
                AppLog.info(context, "GENERATION_SERVICE_STOP_REQUESTED", "serviceWasRunning=$serviceWasRunning")
                AppLog.info(context, "CHAT_REQUEST_FINISHED", "outcome=$requestOutcome durationMs=${SystemClock.elapsedRealtime() - requestStartedAt}")
                scrollToBottom()
            }
        }
    }

    private fun updateSendButton(stopping: Boolean) {
        val button = views?.send ?: return
        val background = if (stopping) R.color.error else R.color.primary
        val targetColor = ContextCompat.getColor(requireContext(), background)
        val startColor = button.backgroundTintList?.defaultColor ?: targetColor
        button.setImageResource(if (stopping) R.drawable.ic_stop else R.drawable.ic_arrow_upward)
        ValueAnimator.ofArgb(startColor, targetColor).apply {
            duration = 200L
            addUpdateListener { button.backgroundTintList = ColorStateList.valueOf(it.animatedValue as Int) }
            start()
        }
        button.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.on_primary))
        button.contentDescription = getString(if (stopping) R.string.chat_stop else R.string.chat_send)
    }

    private fun startThinking(answerRow: Int) {
        thinkingIndex = 0
        thinkingJob?.cancel()
        thinkingJob = viewScope?.launch {
            while (views != null) {
                adapter?.update(answerRow, thinkingMessages[thinkingIndex % thinkingMessages.size], thinking = true)
                scrollToBottom()
                thinkingIndex++
                delay(1800)
            }
        }
    }

    private fun stopThinking() {
        thinkingJob?.cancel()
        thinkingJob = null
    }

    private fun startStatusUpdates() {
        statusJob?.cancel()
        statusJob = viewScope?.launch {
            while (generationActive && views != null) {
                views?.status?.text = controller.status()
                delay(250)
            }
        }
    }

    private fun stopStatusUpdates() {
        statusJob?.cancel()
        statusJob = null
    }

    private fun scrollToBottom() {
        val activeViews = views ?: return
        val count = adapter?.itemCount ?: 0
        if (count > 0) activeViews.list.post { activeViews.list.scrollToPosition(count - 1) }
    }

    override fun onResume() {
        super.onResume()
        views?.status?.text = controller.status()
    }

    override fun onDestroyView() {
        AppLog.info(requireContext(), "CHAT_SCREEN_DESTROYED", "generationActive=$generationActive")
        // The model call is owned by this view. If the view goes away, stop native
        // inference too; otherwise the cancelled coroutine can leave the JNI engine
        // running with no UI consumer, wasting CPU and delaying the next request.
        if (generationActive) {
            AppLog.info(requireContext(), "CHAT_REQUEST_CANCELLED", "reason=view_destroyed")
            controller.stop("view_destroyed")
            generationActive = false
        }
        stopThinking()
        stopStatusUpdates()
        viewScope?.cancel()
        viewScope = null
        views = null
        adapter = null
        super.onDestroyView()
    }

    companion object {
        private const val STREAM_UPDATE_MS = 50L
    }
}
