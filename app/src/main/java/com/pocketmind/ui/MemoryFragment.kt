package com.pocketmind.ui

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.pocketmind.R
import com.pocketmind.memory.ActionLogStore
import com.pocketmind.memory.ActionRecord
import com.pocketmind.memory.AppDatabase
import com.pocketmind.memory.Fact
import com.pocketmind.memory.FactStore
import com.pocketmind.memory.NoteStore
import com.pocketmind.memory.clearMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

class MemoryFragment : Fragment(R.layout.fragment_memory) {
    private data class MemoryViews(
        val factsSwitch: MaterialSwitch,
        val recallSwitch: MaterialSwitch,
        val facts: LinearLayout,
        val actions: LinearLayout,
        val notes: LinearLayout,
        val clearButton: MaterialButton
    )

    private var views: MemoryViews? = null
    private var viewScope: CoroutineScope? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val prefs = requireContext().getSharedPreferences("privacy", Context.MODE_PRIVATE)
        val memoryViews = MemoryViews(
            factsSwitch = view.findViewById(R.id.facts_switch),
            recallSwitch = view.findViewById(R.id.recall_switch),
            facts = view.findViewById(R.id.facts_container),
            actions = view.findViewById(R.id.actions_container),
            notes = view.findViewById(R.id.notes_container),
            clearButton = view.findViewById(R.id.clear_memory_button)
        )
        views = memoryViews
        memoryViews.factsSwitch.isChecked = prefs.getBoolean("facts_enabled", true)
        memoryViews.factsSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("facts_enabled", checked).apply()
        }
        memoryViews.recallSwitch.isChecked = prefs.getBoolean("recall_enabled", true)
        memoryViews.recallSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("recall_enabled", checked).apply()
        }
        memoryViews.clearButton.setOnClickListener {
            confirmClearMemory(requireContext()) { clearAll() }
        }
        refresh()
    }

    fun refresh() {
        if (views == null) return
        val context = requireContext().applicationContext
        viewScope?.launch {
            val data = withContext(Dispatchers.IO) {
                val db = AppDatabase(context)
                try {
                    Triple(FactStore(db).all(), ActionLogStore(db).recent(12), NoteStore(db).list(12))
                } finally {
                    db.close()
                }
            }
            val currentViews = views ?: return@launch
            currentViews.facts.removeAllViews()
            currentViews.actions.removeAllViews()
            currentViews.notes.removeAllViews()
            if (data.first.isEmpty()) currentViews.facts.addView(emptyState(R.string.no_saved_facts))
            data.first.forEach { fact -> currentViews.facts.addView(factCard(fact)) }
            if (data.second.isEmpty()) currentViews.actions.addView(emptyState(R.string.no_recorded_actions))
            data.second.forEach { action -> currentViews.actions.addView(actionCard(action)) }
            if (data.third.isEmpty()) currentViews.notes.addView(emptyState(R.string.no_saved_notes))
            data.third.forEach { note -> currentViews.notes.addView(noteCard(note)) }
        }
    }

    private fun factCard(fact: Fact): MaterialCardView {
        val row = inflateCard(R.layout.item_memory_fact)
        row.findViewById<TextView>(R.id.fact_key).text = fact.key
        row.findViewById<TextView>(R.id.fact_value).text = fact.value
        row.findViewById<ImageButton>(R.id.delete_fact_button).setOnClickListener { deleteFact(fact.key) }
        return row
    }

    private fun actionCard(action: ActionRecord): MaterialCardView {
        val row = inflateCard(R.layout.item_memory_action)
        row.findViewById<TextView>(R.id.action_tool_chip).text = action.tool
        row.findViewById<TextView>(R.id.action_message).text = action.message
        row.findViewById<TextView>(R.id.action_timestamp).text = DateFormat.getTimeFormat(requireContext()).format(Date(action.created))
        val dot = row.findViewById<View>(R.id.action_outcome_dot)
        val color = when {
            action.outcome.contains("HANDED_OFF", ignoreCase = true) || action.outcome.contains("HANDOFF", ignoreCase = true) -> R.color.secondary
            action.outcome.contains("FAILED", ignoreCase = true) || action.outcome.contains("FAIL", ignoreCase = true) -> R.color.error
            else -> R.color.primary
        }
        dot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), color))
        return row
    }

    private fun noteCard(note: NoteStore.Note): MaterialCardView {
        val row = inflateCard(R.layout.item_memory_note)
        row.findViewById<TextView>(R.id.note_message).text = note.content
        row.findViewById<TextView>(R.id.note_timestamp).text = DateFormat.getTimeFormat(requireContext()).format(Date(note.created))
        return row
    }

    private fun inflateCard(layoutId: Int): MaterialCardView =
        LayoutInflater.from(requireContext()).inflate(layoutId, views?.facts, false) as MaterialCardView

    private fun emptyState(stringId: Int): TextView = TextView(requireContext()).apply {
        setTextAppearance(R.style.TextAppearance_PocketMind_Caption)
        setTextColor(ContextCompat.getColor(requireContext(), R.color.muted))
        setText(stringId)
        gravity = android.view.Gravity.CENTER
        setPadding(0, resources.getDimensionPixelSize(R.dimen.space_12), 0, resources.getDimensionPixelSize(R.dimen.space_12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun deleteFact(key: String) {
        val context = requireContext().applicationContext
        viewScope?.launch {
            withContext(Dispatchers.IO) {
                val db = AppDatabase(context)
                try { FactStore(db).delete(key) } finally { db.close() }
            }
            refresh()
        }
    }

    private fun clearAll() {
        val context = requireContext().applicationContext
        viewScope?.launch {
            withContext(Dispatchers.IO) {
                val db = AppDatabase(context)
                try { clearMemory(db) } finally { db.close() }
            }
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroyView() {
        viewScope?.cancel()
        viewScope = null
        views = null
        super.onDestroyView()
    }

    companion object {
        fun confirmClearMemory(context: Context, onConfirm: () -> Unit) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.clear_memory_title)
                .setMessage(R.string.clear_memory_warning)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.clear) { _, _ -> onConfirm() }
                .show()
        }
    }
}
