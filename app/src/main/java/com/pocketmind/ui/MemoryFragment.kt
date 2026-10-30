package com.pocketmind.ui

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
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
import com.pocketmind.tools.AppLog
import kotlinx.coroutines.CancellationException
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
        AppLog.info(requireContext(), "MEMORY_SCREEN_OPENED")
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
            AppLog.info(requireContext(), "FACT_MEMORY_SETTING_CHANGED", "enabled=$checked")
        }
        memoryViews.recallSwitch.isChecked = prefs.getBoolean("recall_enabled", true)
        memoryViews.recallSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("recall_enabled", checked).apply()
            AppLog.info(requireContext(), "CONVERSATION_RECALL_SETTING_CHANGED", "enabled=$checked")
        }
        memoryViews.clearButton.setOnClickListener {
            AppLog.info(requireContext(), "UI_CONTROL_TAPPED", "screen=memory control=clear_memory")
            confirmClearMemory(requireContext(), source = "memory_screen") { clearAll() }
        }
        refresh("screen_open")
    }

    fun refresh(source: String = "manual") {
        if (views == null) return
        val context = requireContext().applicationContext
        AppLog.info(context, "MEMORY_OVERVIEW_REFRESH_STARTED", "source=$source")
        viewScope?.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    val db = AppDatabase(context)
                    try {
                        Triple(FactStore(db).all(), ActionLogStore(db).recent(12), NoteStore(db).list(12))
                    } finally {
                        db.close()
                    }
                }
                val currentViews = views
                if (currentViews == null) {
                    AppLog.info(context, "MEMORY_OVERVIEW_REFRESH_FINISHED", "source=$source outcome=cancelled reason=view_destroyed")
                    return@launch
                }
                AppLog.info(context, "MEMORY_OVERVIEW_LOADED", "facts=${data.first.size} actions=${data.second.size} notes=${data.third.size}")
                AppLog.info(context, "MEMORY_OVERVIEW_REFRESH_FINISHED", "source=$source outcome=success facts=${data.first.size} actions=${data.second.size} notes=${data.third.size}")
                currentViews.facts.removeAllViews()
                currentViews.actions.removeAllViews()
                currentViews.notes.removeAllViews()
                if (data.first.isEmpty()) currentViews.facts.addView(emptyState(R.string.no_saved_facts))
                data.first.forEach { fact -> currentViews.facts.addView(factCard(fact)) }
                if (data.second.isEmpty()) currentViews.actions.addView(emptyState(R.string.no_recorded_actions))
                data.second.forEach { action -> currentViews.actions.addView(actionCard(action)) }
                if (data.third.isEmpty()) currentViews.notes.addView(emptyState(R.string.no_saved_notes))
                data.third.forEach { note -> currentViews.notes.addView(noteCard(note)) }
            } catch (e: CancellationException) {
                AppLog.info(context, "MEMORY_OVERVIEW_REFRESH_FINISHED", "source=$source outcome=cancelled")
                throw e
            } catch (e: Exception) {
                AppLog.error(context, "MEMORY_OVERVIEW_REFRESH_FAILED", e, "source=$source")
                throw e
            }
        }
    }

    private fun factCard(fact: Fact): MaterialCardView {
        val row = inflateCard(R.layout.item_memory_fact)
        row.findViewById<TextView>(R.id.fact_key).text = fact.key
        row.findViewById<TextView>(R.id.fact_value).text = fact.value
        row.findViewById<ImageButton>(R.id.delete_fact_button).setOnClickListener {
            AppLog.info(requireContext(), "UI_CONTROL_TAPPED", "screen=memory control=delete_fact")
            deleteFact(fact.key)
        }
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

    private fun emptyState(stringId: Int): TextView =
        (LayoutInflater.from(requireContext()).inflate(R.layout.item_memory_empty, views?.facts, false) as TextView).apply {
            setText(stringId)
        }

    private fun deleteFact(key: String) {
        val context = requireContext().applicationContext
        AppLog.info(context, "FACT_DELETE_REQUESTED")
        viewScope?.launch {
            try {
                val deleted = withContext(Dispatchers.IO) {
                    val db = AppDatabase(context)
                    try { FactStore(db).delete(key) } finally { db.close() }
                }
                AppLog.info(context, "FACT_DELETE_FINISHED", "outcome=${if (deleted) "deleted" else "not_found"}")
                refresh("after_fact_delete")
            } catch (e: CancellationException) {
                AppLog.info(context, "FACT_DELETE_FINISHED", "outcome=cancelled")
                throw e
            } catch (e: Exception) {
                AppLog.error(context, "FACT_DELETE_FAILED", e)
                throw e
            }
        }
    }

    private fun clearAll() {
        val context = requireContext().applicationContext
        AppLog.info(context, "MEMORY_CLEAR_STARTED", "source=memory_screen")
        viewScope?.launch {
            try {
                withContext(Dispatchers.IO) {
                    val db = AppDatabase(context)
                    try { clearMemory(db) } finally { db.close() }
                }
                AppLog.info(context, "MEMORY_CLEAR_FINISHED", "source=memory_screen outcome=success")
                refresh("after_clear")
            } catch (e: CancellationException) {
                AppLog.info(context, "MEMORY_CLEAR_FINISHED", "source=memory_screen outcome=cancelled")
                throw e
            } catch (e: Exception) {
                AppLog.error(context, "MEMORY_CLEAR_FAILED", e, "source=memory_screen")
                throw e
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh("resume")
    }

    override fun onDestroyView() {
        AppLog.info(requireContext(), "MEMORY_SCREEN_TASKS_CANCELLED", "reason=view_destroyed")
        viewScope?.cancel()
        viewScope = null
        views = null
        super.onDestroyView()
    }

    companion object {
        fun confirmClearMemory(context: Context, source: String = "unknown", onConfirm: () -> Unit) {
            AppLog.info(context, "MEMORY_CLEAR_CONFIRMATION_SHOWN", "source=$source")
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.clear_memory_title)
                .setMessage(R.string.clear_memory_warning)
                .setNegativeButton(R.string.cancel) { _, _ -> AppLog.info(context, "MEMORY_CLEAR_CONFIRMATION_RESULT", "source=$source outcome=cancelled") }
                .setPositiveButton(R.string.clear) { _, _ ->
                    AppLog.info(context, "MEMORY_CLEAR_CONFIRMATION_RESULT", "source=$source outcome=confirmed")
                    onConfirm()
                }
                .setOnCancelListener { AppLog.info(context, "MEMORY_CLEAR_CONFIRMATION_RESULT", "source=$source outcome=dismissed") }
                .show()
        }
    }
}
