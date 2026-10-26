package com.pocketmind.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
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
        val activeViews = views ?: return
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
            if (data.first.isEmpty()) currentViews.facts.addView(emptyText(R.string.no_saved_facts))
            data.first.forEach { fact -> currentViews.facts.addView(factCard(fact)) }
            if (data.second.isEmpty()) currentViews.actions.addView(emptyText(R.string.no_recorded_actions))
            data.second.forEach { entry ->
                currentViews.actions.addView(contentCard("${entry.tool} · ${entry.outcome}: ${entry.message}"))
            }
            if (data.third.isEmpty()) currentViews.notes.addView(emptyText(R.string.no_saved_notes))
            data.third.forEach { note -> currentViews.notes.addView(contentCard(note.content)) }
        }
    }

    private fun factCard(fact: Fact): MaterialCardView {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val label = TextView(context).apply {
            setTextAppearance(R.style.TextAppearance_PocketMind_Body)
            text = "${fact.key}: ${fact.value}"
        }
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(MaterialButton(context).apply {
            text = getString(R.string.delete)
            setOnClickListener { deleteFact(fact.key) }
        })
        return card(row)
    }

    private fun contentCard(text: String): MaterialCardView {
        val label = TextView(requireContext()).apply {
            setTextAppearance(R.style.TextAppearance_PocketMind_Body)
            this.text = text
        }
        return card(label)
    }

    private fun emptyText(stringId: Int): TextView = TextView(requireContext()).apply {
        setTextAppearance(R.style.TextAppearance_PocketMind_Caption)
        setTextColor(ContextCompat.getColor(requireContext(), R.color.muted))
        setText(stringId)
        setPadding(0, resources.getDimensionPixelSize(R.dimen.space_8), 0, resources.getDimensionPixelSize(R.dimen.space_8))
    }

    private fun card(content: View): MaterialCardView {
        val context = requireContext()
        val card = MaterialCardView(context).apply {
            radius = resources.getDimension(R.dimen.radius_action_card)
            cardElevation = resources.getDimension(R.dimen.elevation_card)
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.surface_variant))
            setContentPadding(
                resources.getDimensionPixelSize(R.dimen.space_12),
                resources.getDimensionPixelSize(R.dimen.space_8),
                resources.getDimensionPixelSize(R.dimen.space_12),
                resources.getDimensionPixelSize(R.dimen.space_8)
            )
            addView(content)
        }
        return card.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = resources.getDimensionPixelSize(R.dimen.space_8)
            }
        }
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
