package com.pocketmind.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.pocketmind.R
import com.pocketmind.engine.ModelStore

class ModelAdapter(
    private val onSelect: (ModelStore.Model) -> Unit,
    private val onOverflow: (ModelStore.Model) -> Unit
) : RecyclerView.Adapter<ModelAdapter.Holder>() {
    private var models: List<ModelStore.Model> = emptyList()
    private var selectedPath: String? = null

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.model_card)
        val dot: View = view.findViewById(R.id.model_dot)
        val name: TextView = view.findViewById(R.id.model_name)
        val size: TextView = view.findViewById(R.id.model_size)
        val selected: TextView = view.findViewById(R.id.model_selected_chip)
        val overflow: ImageButton = view.findViewById(R.id.model_overflow)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_model_card, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val model = models[position]
        val context = holder.itemView.context
        val isSelected = model.file.absolutePath == selectedPath
        holder.dot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, if (isSelected) R.color.primary else R.color.muted)
        )
        holder.name.text = model.file.name
        holder.size.text = context.getString(R.string.model_size_mb, model.bytes / 1024.0 / 1024.0)
        holder.selected.visibility = if (isSelected) View.VISIBLE else View.GONE
        holder.card.setCardBackgroundColor(ContextCompat.getColor(context, R.color.surface))
        holder.card.strokeColor = ContextCompat.getColor(context, if (isSelected) R.color.primary_dim else R.color.divider)
        holder.card.strokeWidth = context.resources.getDimensionPixelSize(R.dimen.stroke_width)
        holder.card.setOnClickListener { onSelect(model) }
        holder.overflow.setOnClickListener { onOverflow(model) }
    }

    override fun getItemCount(): Int = models.size

    fun submit(items: List<ModelStore.Model>, selectedPath: String?) {
        models = items
        this.selectedPath = selectedPath
        notifyDataSetChanged()
    }
}
