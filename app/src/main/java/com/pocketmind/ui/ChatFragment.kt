package com.pocketmind.ui

import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
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
        chatViews.status.text = controller.status()
        chatViews.send.isEnabled = false
        updateSendButton(generationActive)
        chatViews.send.setOnClickListener {
            if (generationActive) controller.stop() else submit()
        }
        chatViews.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                if (!generationActive && chatViews.send.isEnabled) submit()
                true
            } else false
        }
        loadHistory()
    }

    private fun loadHistory() {
        val context = requireContext().applicationContext
        viewScope?.launch {
            val history = withContext(Dispatchers.IO) {
                val db = AppDatabase(context)
                try { MessageStore(db).recent(60).map { ChatAdapter.Line(it.role, it.content, it.time) } }
                finally { db.close() }
            }
            val activeViews = views ?: return@launch
            adapter?.replace(history)
            activeViews.send.isEnabled = true
            updateSendButton(generationActive)
            scrollToBottom()
        }
    }

    private fun submit() {
        val activeViews = views ?: return
        val text = activeViews.input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() || !activeViews.send.isEnabled || generationActive) return
        activeViews.input.setText("")
        adapter?.add("user", text, animateOnInsert = true)
        val answerRow = adapter?.add("assistant", "", thinking = true) ?: return
        scrollToBottom()
        generationActive = true
        updateSendButton(true)
        startThinking(answerRow)
        val context = requireContext()
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GenerationService::class.java).setAction(GenerationService.ACTION_START)
            )
        } catch (_: Exception) { }

        val streamed = StringBuilder()
        val streamLock = Any()
        val uiUpdatePending = AtomicBoolean(false)
        var renderedLength = 0
        val firstToken = AtomicBoolean(false)

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
                    if (firstToken.compareAndSet(false, true)) handler.post { stopThinking() }
                    synchronized(streamLock) { streamed.append(chunk) }
                    scheduleStreamUpdate()
                }
                adapter?.update(answerRow, reply.text, reply.actions, time = System.currentTimeMillis())
            } catch (e: Exception) {
                adapter?.update(answerRow, "PocketMind could not finish: ${e.message ?: "unexpected error"}", time = System.currentTimeMillis())
            } finally {
                stopThinking()
                generationActive = false
                views?.let {
                    it.status.text = controller.status()
                    it.send.isEnabled = true
                    updateSendButton(false)
                }
                context.stopService(Intent(context, GenerationService::class.java))
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
        // The model call is owned by this view. If the view goes away, stop native
        // inference too; otherwise the cancelled coroutine can leave the JNI engine
        // running with no UI consumer, wasting CPU and delaying the next request.
        if (generationActive) {
            controller.stop()
            generationActive = false
        }
        stopThinking()
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
