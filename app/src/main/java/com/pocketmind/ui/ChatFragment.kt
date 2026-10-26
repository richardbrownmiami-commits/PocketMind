package com.pocketmind.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
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
        val input: EditText,
        val send: MaterialButton,
        val stop: MaterialButton
    )

    private var views: ChatViews? = null
    private var adapter: ChatAdapter? = null
    private var viewScope: CoroutineScope? = null
    private var thinkingJob: Job? = null
    private var thinkingIndex = 0
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
            send = view.findViewById(R.id.send_button),
            stop = view.findViewById(R.id.stop_button)
        )
        views = chatViews
        adapter = chatAdapter
        chatViews.status.text = controller.status()
        chatViews.send.isEnabled = false
        chatViews.send.setOnClickListener { submit() }
        chatViews.input.setOnEditorActionListener { _, _, _ ->
            if (chatViews.send.isEnabled) submit()
            true
        }
        chatViews.stop.setOnClickListener { controller.stop() }
        loadHistory()
    }

    private fun loadHistory() {
        val context = requireContext().applicationContext
        viewScope?.launch {
            val history = withContext(Dispatchers.IO) {
                val db = AppDatabase(context)
                try { MessageStore(db).recent(60).map { ChatAdapter.Line(it.role, it.content) } }
                finally { db.close() }
            }
            val activeViews = views ?: return@launch
            adapter?.replace(history)
            activeViews.send.isEnabled = true
            scrollToBottom()
        }
    }

    private fun submit() {
        val activeViews = views ?: return
        val text = activeViews.input.text.toString().trim()
        if (text.isEmpty() || !activeViews.send.isEnabled) return
        activeViews.input.setText("")
        adapter?.add("user", text)
        val answerRow = adapter?.add("assistant", "") ?: return
        scrollToBottom()
        activeViews.send.isEnabled = false
        activeViews.stop.isEnabled = true
        startThinking(answerRow)
        val context = requireContext()
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GenerationService::class.java).setAction(GenerationService.ACTION_START)
            )
        } catch (_: Exception) { }

        val streamed = StringBuilder()
        val firstToken = AtomicBoolean(false)
        viewScope?.launch {
            try {
                val reply = controller.send(text) { chunk ->
                    if (firstToken.compareAndSet(false, true)) handler.post { stopThinking() }
                    handler.post {
                        if (views == null) return@post
                        streamed.append(chunk)
                        adapter?.update(answerRow, streamed.toString())
                        scrollToBottom()
                    }
                }
                adapter?.update(answerRow, reply.text)
            } catch (e: Exception) {
                adapter?.update(answerRow, "PocketMind could not finish: ${e.message ?: "unexpected error"}")
            } finally {
                stopThinking()
                views?.let {
                    it.status.text = controller.status()
                    it.send.isEnabled = true
                    it.stop.isEnabled = false
                }
                context.stopService(Intent(context, GenerationService::class.java))
                scrollToBottom()
            }
        }
    }

    private fun startThinking(answerRow: Int) {
        thinkingIndex = 0
        thinkingJob?.cancel()
        thinkingJob = viewScope?.launch {
            while (views != null) {
                adapter?.update(answerRow, thinkingMessages[thinkingIndex % thinkingMessages.size])
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
        stopThinking()
        viewScope?.cancel()
        viewScope = null
        views = null
        adapter = null
        super.onDestroyView()
    }
}
