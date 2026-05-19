package com.lanchat.android.ui.groups

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lanchat.android.databinding.ItemGroupBinding

/**
 * RecyclerView adapter for the Groups screen.
 * Each item shows a group as a terminal directory entry:
 *   drwxr-xr-x  user  4096  #GROUP-NAME
 */
class GroupsAdapter(
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<GroupsAdapter.GroupViewHolder>() {

    private val groups = mutableListOf<GroupEntry>()

    data class GroupEntry(
        val name: String,
        val permissions: String = "drwxr-xr-x",
        val user: String = "user",
        val size: String = "4096"
    )

    fun setGroups(list: List<String>) {
        groups.clear()
        groups.addAll(list.mapIndexed { i, name ->
            val sizes = listOf("4096", "2048", "1024", "512", "128")
            val users = listOf("root", "dev", "admin", "user", "ext")
            val perms = listOf("drwxr-xr-x", "drwxr-xr-x", "drw-------", "drwxr-xr-x", "drwxr-xr-x")
            GroupEntry(name, perms[i % perms.size], users[i % users.size], sizes[i % sizes.size])
        })
        notifyDataSetChanged()
    }

    fun addGroup(name: String) {
        groups.add(GroupEntry(name))
        notifyItemInserted(groups.size - 1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val b = ItemGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return GroupViewHolder(b)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        holder.bind(groups[position], position % 2 == 0)
    }

    override fun getItemCount() = groups.size

    inner class GroupViewHolder(private val b: ItemGroupBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(entry: GroupEntry, isAlt: Boolean) {
            b.tvPermissions.text = entry.permissions
            b.tvUser.text        = entry.user
            b.tvSize.text        = entry.size
            b.tvIdentifier.text  = "#${entry.name}"

            // Alternate row background: surface_container_low vs transparent
            b.root.setBackgroundColor(
                if (isAlt) itemView.context.getColor(com.lanchat.android.R.color.surface_container_low)
                else 0x00000000
            )

            b.root.setOnClickListener { onClick(entry.name) }
        }
    }
}
