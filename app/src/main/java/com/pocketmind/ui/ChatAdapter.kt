package com.pocketmind.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.Holder>() {
    data class Line(val role: String, var text: String)
    private val lines = mutableListOf<Line>()

    class Holder(val row: LinearLayout, val view: TextView) : RecyclerView.ViewHolder(row)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val text = TextView(parent.context).apply {
            textSize = 16f
            setTextColor(Color.rgb(32, 39, 36))
            setPadding(18, 12, 18, 12)
            maxWidth = (parent.resources.displayMetrics.widthPixels * 0.84f).toInt()
        }
        val row = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 2, 0, 2)
            addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return Holder(row, text)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val line = lines[position]
        val mine = line.role == "user"
        holder.view.text = line.text.ifBlank { if (line.role == "assistant") "…" else "" }
        holder.row.gravity = if (mine) Gravity.END else Gravity.START
        holder.view.background = GradientDrawable().apply {
            cornerRadius = 24f
            setColor(if (mine) Color.rgb(220, 235, 228) else Color.rgb(244, 244, 239))
        }
        holder.row.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun getItemCount(): Int = lines.size

    fun add(role: String, text: String): Int {
        lines.add(Line(role, text))
        val index = lines.lastIndex
        notifyItemInserted(index)
        return index
    }

    fun append(index: Int, chunk: String) {
        if (index !in lines.indices) return
        lines[index].text += chunk
        notifyItemChanged(index)
    }

    fun update(index: Int, text: String) {
        if (index !in lines.indices) return
        lines[index].text = text
        notifyItemChanged(index)
    }

    fun replace(items: List<Line>) {
        lines.clear()
        lines.addAll(items)
        notifyDataSetChanged()
    }
}
