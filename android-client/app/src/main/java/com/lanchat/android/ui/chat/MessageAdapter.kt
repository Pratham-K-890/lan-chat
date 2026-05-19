package com.lanchat.android.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lanchat.android.databinding.ItemMessageBinding
import com.lanchat.client.MessageListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Terminal-style message adapter for the LOG (broadcast) screen.
 *
 * Two item types:
 *   TYPE_SEPARATOR  — a dim "──── HISTORY ────" header row
 *   TYPE_MESSAGE    — a normal [timestamp] > sender: text row
 *
 * History messages are prepended at position 0 with a separator so the user
 * can visually distinguish persisted history from live messages.
 */
class MessageAdapter(private val myUsername: String) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_SEPARATOR = 0
        private const val TYPE_MESSAGE   = 1
    }

    data class ChatMessage(
        val sender: String,
        val text: String,
        val timestamp: Long = System.currentTimeMillis(),
        val isSeparator: Boolean = false
    )

    private val items = mutableListOf<ChatMessage>()
    private val timeFmt = SimpleDateFormat("[HH:mm:ss]", Locale.getDefault())

    // ── Live messages (appended at bottom) ────────────────────────────

    fun addMessage(msg: ChatMessage) {
        items.add(msg)
        notifyItemInserted(items.size - 1)
    }

    fun addMessage(text: String) {
        val colonIdx = text.indexOf(':')
        val sender = if (colonIdx > 0) text.substring(0, colonIdx).trim() else "SYSTEM"
        val body   = if (colonIdx > 0) text.substring(colonIdx + 1).trim() else text
        addMessage(ChatMessage(sender, body))
    }

    // ── History (prepended at top with separator) ─────────────────────

    /**
     * Insert a separator + all historical messages at position 0.
     * Called once on login when server delivers HISTORY_RESPONSE.
     * Only inserts once — silently ignores subsequent calls for the same channel.
     */
    fun prependHistory(channel: String, history: List<MessageListener.HistoryMessage>) {
        // Don't insert twice if already have history
        if (items.any { it.isSeparator }) return
        if (history.isEmpty()) return

        val channelLabel = when {
            channel == "broadcast"        -> "BROADCAST"
            channel.startsWith("group:") -> "GROUP: ${channel.removePrefix("group:")}"
            channel.startsWith("dm:")    -> "DM: ${channel.removePrefix("dm:")}"
            else                          -> channel.uppercase()
        }

        val toInsert = mutableListOf<ChatMessage>()

        // Separator row
        toInsert.add(ChatMessage(
            sender      = "──── HISTORY: $channelLabel ────",
            text        = "",
            isSeparator = true
        ))

        // Historical messages (server sends oldest-first)
        history.forEach { h ->
            toInsert.add(ChatMessage(h.sender, h.text, h.timestamp))
        }

        // End-of-history separator
        toInsert.add(ChatMessage(
            sender      = "──── LIVE ────",
            text        = "",
            isSeparator = true
        ))

        items.addAll(0, toInsert)
        notifyItemRangeInserted(0, toInsert.size)
    }

    // ── RecyclerView overrides ────────────────────────────────────────

    override fun getItemViewType(position: Int) =
        if (items[position].isSeparator) TYPE_SEPARATOR else TYPE_MESSAGE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val b = ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MsgViewHolder(b)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        (holder as MsgViewHolder).bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class MsgViewHolder(private val b: ItemMessageBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(msg: ChatMessage) {
            if (msg.isSeparator) {
                // Render as a dimmed centre-aligned separator row
                b.tvTimestamp.text = ""
                b.tvSender.text    = msg.sender
                b.tvMessage.text   = ""
                b.tvSender.alpha   = 0.35f
                b.tvMessage.alpha  = 0.35f
                b.root.alpha       = 0.6f
            } else {
                b.tvTimestamp.text = timeFmt.format(Date(msg.timestamp))
                b.tvSender.text    = "> ${msg.sender}:"
                b.tvMessage.text   = msg.text
                b.tvSender.alpha   = 1f
                b.tvMessage.alpha  = if (msg.sender == myUsername) 1.0f else 0.85f
                b.root.alpha       = 1f
            }
        }
    }
}
