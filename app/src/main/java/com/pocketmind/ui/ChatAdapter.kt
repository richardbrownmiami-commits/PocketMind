package com.pocketmind.ui

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.pocketmind.R
import com.pocketmind.tools.ActionResult
import com.pocketmind.tools.ActionStatus
import java.util.Date

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.Holder>() {
    data class Line(
        val role: String,
        var text: String,
        var time: Long = System.currentTimeMillis(),
        var actions: List<ActionResult> = emptyList(),
        var thinking: Boolean = false,
        var timestampFinalized: Boolean = true,
        var animateOnInsert: Boolean = false
    )

    private val lines = mutableListOf<Line>()

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val row: LinearLayout = itemView.findViewById(R.id.chat_row)
        val bubble: LinearLayout = itemView.findViewById(R.id.chat_bubble)
        val badge: TextView = itemView.findViewById(R.id.assistant_badge)
        val message: TextView = itemView.findViewById(R.id.message_text)
        val actions: LinearLayout = itemView.findViewById(R.id.action_cards)
        val timestamp: TextView = itemView.findViewById(R.id.message_timestamp)
        var boundMessage: String? = null
        var boundThinking: Boolean = false
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val line = lines[position]
        val context = holder.itemView.context
        val isUser = line.role == "user"
        val maxWidth = (context.resources.displayMetrics.widthPixels * if (isUser) 0.80f else 0.88f).toInt()
        val firstInGroup = position == 0 || lines[position - 1].role != line.role

        holder.row.gravity = Gravity.TOP or if (isUser) Gravity.END else Gravity.START
        holder.bubble.setBackgroundResource(if (isUser) R.drawable.bg_chat_user_bubble else R.drawable.bg_chat_assistant_bubble)
        holder.message.maxWidth = maxWidth
        val displayText = line.text.ifBlank { if (isUser) "" else "…" }
        if (line.thinking && holder.boundThinking && holder.boundMessage != displayText) {
            holder.message.animate().cancel()
            holder.message.animate().alpha(0f).setDuration(100L).withEndAction {
                holder.message.text = displayText
                holder.message.alpha = 0f
                holder.message.animate().alpha(1f).setDuration(150L).start()
            }.start()
        } else {
            holder.message.animate().cancel()
            holder.message.alpha = 1f
            holder.message.text = displayText
        }
        holder.boundMessage = displayText
        holder.boundThinking = line.thinking
        holder.message.setTextColor(ContextCompat.getColor(context, if (isUser) R.color.on_background else R.color.on_surface))
        holder.message.typeface = Typeface.create("sans-serif", if (line.thinking) Typeface.ITALIC else Typeface.NORMAL)

        if (line.animateOnInsert) {
            line.animateOnInsert = false
            holder.row.animate().cancel()
            holder.row.alpha = 0f
            holder.row.translationX = context.resources.getDimension(R.dimen.space_16)
            holder.row.animate().alpha(1f).translationX(0f).setDuration(250L).start()
        } else {
            holder.row.alpha = 1f
            holder.row.translationX = 0f
        }

        holder.badge.visibility = if (!isUser && firstInGroup) View.VISIBLE else View.GONE
        holder.timestamp.visibility = if (firstInGroup && line.timestampFinalized) View.VISIBLE else View.GONE
        holder.timestamp.text = DateFormat.getTimeFormat(context).format(Date(line.time))
        (holder.timestamp.layoutParams as LinearLayout.LayoutParams).gravity = if (isUser) Gravity.END else Gravity.START

        holder.actions.removeAllViews()
        holder.actions.visibility = if (line.actions.isEmpty()) View.GONE else View.VISIBLE
        line.actions.forEach { result -> holder.actions.addView(actionCard(holder, result, maxWidth)) }
    }

    private fun actionCard(holder: Holder, result: ActionResult, bubbleMaxWidth: Int): MaterialCardView {
        val context = holder.itemView.context
        val card = LayoutInflater.from(context)
            .inflate(R.layout.item_action_card, holder.actions, false) as MaterialCardView
        val (colorId, iconId, labelId) = when (result.status) {
            ActionStatus.VERIFIED -> Triple(R.color.primary, R.drawable.ic_check_circle_outline, R.string.action_verified)
            ActionStatus.HANDED_OFF -> Triple(R.color.secondary, R.drawable.ic_open_in_new, R.string.action_handed_off)
            ActionStatus.FAILED -> Triple(R.color.error, R.drawable.ic_error_outline, R.string.action_failed)
        }
        val color = ContextCompat.getColor(context, colorId)
        card.findViewById<View>(R.id.action_status_border).setBackgroundColor(color)
        val icon = card.findViewById<ImageView>(R.id.action_status_icon)
        icon.setImageResource(iconId)
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(color))
        val status = card.findViewById<TextView>(R.id.action_status_label)
        status.setText(labelId)
        status.setTextColor(color)
        val tool = card.findViewById<TextView>(R.id.action_tool_name)
        tool.text = result.tool
        val message = card.findViewById<TextView>(R.id.action_message)
        message.text = result.message
        val contentMaxWidth = (bubbleMaxWidth - context.resources.getDimensionPixelSize(R.dimen.space_32)).coerceAtLeast(0)
        tool.maxWidth = contentMaxWidth
        message.maxWidth = contentMaxWidth
        return card
    }

    override fun getItemCount(): Int = lines.size

    override fun onViewRecycled(holder: Holder) {
        holder.row.animate().cancel()
        holder.message.animate().cancel()
        holder.row.alpha = 1f
        holder.row.translationX = 0f
        holder.message.alpha = 1f
        holder.boundMessage = null
        holder.boundThinking = false
        super.onViewRecycled(holder)
    }

    fun add(
        role: String,
        text: String,
        thinking: Boolean = false,
        animateOnInsert: Boolean = false,
        timestampFinalized: Boolean = true
    ): Int {
        lines.add(Line(
            role = role,
            text = text,
            thinking = thinking,
            timestampFinalized = timestampFinalized,
            animateOnInsert = animateOnInsert
        ))
        val index = lines.lastIndex
        notifyItemInserted(index)
        return index
    }

    fun append(index: Int, chunk: String) {
        if (index !in lines.indices) return
        update(index, lines[index].text + chunk)
    }

    fun update(index: Int, text: String, actions: List<ActionResult> = emptyList(), thinking: Boolean = false, time: Long? = null) {
        if (index !in lines.indices) return
        lines[index].text = text
        lines[index].actions = actions
        lines[index].thinking = thinking
        if (time != null) {
            lines[index].time = time
            lines[index].timestampFinalized = true
        }
        notifyItemChanged(index)
    }

    fun replace(items: List<Line>) {
        lines.clear()
        lines.addAll(items)
        notifyDataSetChanged()
    }
}
