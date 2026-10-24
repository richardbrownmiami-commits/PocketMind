package com.pocketmind.ui

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.pocketmind.memory.ActionLogStore
import com.pocketmind.memory.AppDatabase
import com.pocketmind.memory.FactStore
import com.pocketmind.memory.NoteStore
import com.pocketmind.memory.clearMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MemoryActivity : AppCompatActivity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs by lazy { getSharedPreferences("privacy", MODE_PRIVATE) }
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 16, 20, 16) }
        root.addView(TextView(this).apply { text = "Memory"; textSize = 24f })
        root.addView(SwitchCompat(this).apply {
            text = "Remember facts from chat"
            isChecked = prefs.getBoolean("facts_enabled", true)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("facts_enabled", checked).apply() }
        })
        root.addView(SwitchCompat(this).apply {
            text = "Use past conversation recall"
            isChecked = prefs.getBoolean("recall_enabled", true)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("recall_enabled", checked).apply() }
        })
        val scroll = ScrollView(this)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(Button(this).apply {
            text = "Clear memory"
            setOnClickListener {
                AlertDialog.Builder(this@MemoryActivity)
                    .setTitle("Clear saved memory?")
                    .setMessage("This deletes saved messages, recall snippets, summaries, topics, auto-created facts, and the action log. Imported models and notes are kept.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear") { _, _ -> clearAll() }
                    .show()
            }
        })
        setContentView(root)
        refresh()
    }

    private fun refresh() {
        if (!::body.isInitialized) return
        body.removeAllViews()
        uiScope.launch {
            val data = withContext(Dispatchers.IO) {
                val db = AppDatabase(this@MemoryActivity)
                try {
                    Triple(FactStore(db).all(), ActionLogStore(db).recent(12), NoteStore(db).list(12))
                } finally { db.close() }
            }
            body.addView(TextView(this@MemoryActivity).apply { text = "Saved facts"; textSize = 18f; setPadding(0, 12, 0, 4) })
            if (data.first.isEmpty()) body.addView(TextView(this@MemoryActivity).apply { text = "No saved facts." })
            data.first.forEach { fact ->
                val row = LinearLayout(this@MemoryActivity).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(TextView(this@MemoryActivity).apply { text = "${fact.key}: ${fact.value}"; setPadding(4, 8, 4, 8) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(Button(this@MemoryActivity).apply {
                    text = "Delete"
                    setOnClickListener { deleteFact(fact.key) }
                })
                body.addView(row)
            }
            body.addView(TextView(this@MemoryActivity).apply { text = "Recent actions"; textSize = 18f; setPadding(0, 12, 0, 4) })
            if (data.second.isEmpty()) body.addView(TextView(this@MemoryActivity).apply { text = "No recorded actions." })
            data.second.forEach { entry -> body.addView(TextView(this@MemoryActivity).apply { text = "${entry.tool} · ${entry.outcome}: ${entry.message}"; setPadding(4, 6, 4, 6) }) }
            body.addView(TextView(this@MemoryActivity).apply { text = "Saved notes (kept when memory is cleared)"; textSize = 18f; setPadding(0, 12, 0, 4) })
            if (data.third.isEmpty()) body.addView(TextView(this@MemoryActivity).apply { text = "No saved notes." })
            data.third.forEach { note -> body.addView(TextView(this@MemoryActivity).apply { text = "• ${note.content}"; setPadding(4, 6, 4, 6) }) }
        }
    }

    private fun deleteFact(key: String) {
        uiScope.launch(Dispatchers.IO) { val db = AppDatabase(this@MemoryActivity); try { FactStore(db).delete(key) } finally { db.close() }; runOnUiThread { refresh() } }
    }

    private fun clearAll() {
        uiScope.launch(Dispatchers.IO) {
            val db = AppDatabase(this@MemoryActivity)
            try { clearMemory(db) } finally { db.close() }
            runOnUiThread { refresh() }
        }
    }

    override fun onResume() { super.onResume(); refresh() }
    override fun onDestroy() { uiScope.cancel(); super.onDestroy() }
}
