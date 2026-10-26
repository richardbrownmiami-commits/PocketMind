package com.pocketmind.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pocketmind.PocketMindApplication
import com.pocketmind.core.GenerationService
import com.pocketmind.memory.AppDatabase
import com.pocketmind.memory.MessageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller get() = (application as PocketMindApplication).agentController
    private lateinit var adapter: ChatAdapter
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var stop: Button
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
    private var thinkingJob: Job? = null
    private var thinkingIndex = 0
    @Volatile private var activityDestroyed = false

    private fun startThinking(answerRow: Int) {
        thinkingIndex = 0
        thinkingJob?.cancel()
        thinkingJob = uiScope.launch {
            while (true) {
                if (activityDestroyed) break
                adapter.update(answerRow, thinkingMessages[thinkingIndex % thinkingMessages.size])
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildView()
        status.text = controller.status()
        loadHistory()
    }

    private fun loadHistory() {
        uiScope.launch {
            val history = withContext(Dispatchers.IO) {
                val helper = AppDatabase(this@MainActivity)
                try { MessageStore(helper).recent(60).map { ChatAdapter.Line(it.role, it.content) } }
                finally { helper.close() }
            }
            adapter.replace(history)
            scrollToBottom()
        }
    }

    private fun buildView() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 8)
            setBackgroundColor(Color.WHITE)
        }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        nav.addView(Button(this).apply {
            text = "Models"
            setOnClickListener { startActivity(Intent(this@MainActivity, ModelsActivity::class.java)) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(Button(this).apply {
            text = "Memory"
            setOnClickListener { startActivity(Intent(this@MainActivity, MemoryActivity::class.java)) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(nav)
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(8, 0, 8, 8)
        }
        root.addView(status)
        adapter = ChatAdapter()
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply { stackFromEnd = true }
            this.adapter = this@MainActivity.adapter
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        input = EditText(this).apply {
            hint = "Ask or type a command"
            minLines = 1
            maxLines = 4
            setPadding(12, 10, 12, 10)
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        send = Button(this).apply {
            text = "Send"
            setOnClickListener { submit() }
        }
        actions.addView(send)
        root.addView(actions)
        stop = Button(this).apply {
            text = "Stop generation"
            isEnabled = false
            setOnClickListener {
                controller.stop()
            }
        }
        root.addView(stop)
        setContentView(root)
    }

    private fun submit() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || !send.isEnabled) return
        input.setText("")
        adapter.add("user", text)
        val answerRow = adapter.add("assistant", "")
        scrollToBottom()
        send.isEnabled = false
        stop.isEnabled = true
        startThinking(answerRow)
        try { ContextCompat.startForegroundService(this, Intent(this, GenerationService::class.java).setAction(GenerationService.ACTION_START)) }
        catch (_: Exception) { }
        val streamed = StringBuilder()
        val firstToken = AtomicBoolean(false)
        uiScope.launch {
            try {
                val reply = controller.send(text) { chunk ->
                    if (firstToken.compareAndSet(false, true)) handler.post { if (!activityDestroyed) stopThinking() }
                    handler.post {
                        if (!activityDestroyed) {
                            streamed.append(chunk)
                            adapter.update(answerRow, streamed.toString())
                            scrollToBottom()
                        }
                    }
                }
                if (!activityDestroyed) adapter.update(answerRow, reply.text)
            } catch (e: Exception) {
                if (!activityDestroyed) adapter.update(answerRow, "PocketMind could not finish: ${e.message ?: "unexpected error"}")
            } finally {
                stopThinking()
                if (!activityDestroyed) status.text = controller.status()
                if (!activityDestroyed) {
                    send.isEnabled = true
                    stop.isEnabled = false
                }
                stopService(Intent(this@MainActivity, GenerationService::class.java))
                if (!activityDestroyed) scrollToBottom()
            }
        }
    }

    private fun scrollToBottom() {
        if (::adapter.isInitialized && adapter.itemCount > 0) list.post { list.scrollToPosition(adapter.itemCount - 1) }
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) { status.text = controller.status(); loadHistory() }
    }

    override fun onDestroy() {
        activityDestroyed = true
        stopThinking()
        uiScope.cancel()
        super.onDestroy()
    }
}
