package com.bio.bioplayer.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.bio.bioplayer.R
import com.bio.bioplayer.databinding.ItemSavedPlaylistBinding
import com.bio.bioplayer.databinding.ItemSavedPlaylistGridBinding
import com.bio.bioplayer.model.SavedPlaylist

class SavedPlaylistAdapter(
    private val onClick: (SavedPlaylist) -> Unit,
    private val onLongClick: (SavedPlaylist) -> Unit
) : RecyclerView.Adapter<SavedPlaylistAdapter.VH>() {

    private val originalItems = mutableListOf<SavedPlaylist>()
    private val items = mutableListOf<SavedPlaylist>()

    // true = ตาราง (ชื่ออยู่ใต้รูป) / false = แถว (ชื่ออยู่ข้างรูป + ปุ่ม ⋮)
    var isGrid = true

    fun submit(list: List<SavedPlaylist>) {
        originalItems.clear()
        originalItems.addAll(list)
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        items.clear()
        if (query.isBlank()) {
            items.addAll(originalItems)
        } else {
            items.addAll(originalItems.filter { it.name.contains(query, ignoreCase = true) })
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = if (isGrid) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 1) {
            val b = ItemSavedPlaylistGridBinding.inflate(inf, parent, false)
            VH(b.root, b.logo, b.name, b.author, null)
        } else {
            val b = ItemSavedPlaylistBinding.inflate(inf, parent, false)
            VH(b.root, b.logo, b.name, b.author, b.btnMenu)
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    inner class VH(
        root: View,
        private val logo: ImageView,
        private val name: TextView,
        private val author: TextView,
        private val btnMenu: View?
    ) : RecyclerView.ViewHolder(root) {
        fun bind(p: SavedPlaylist) {
            name.text = p.name
            author.text = p.author
            author.visibility = if (p.author.isBlank()) View.GONE else View.VISIBLE
            logo.load(if (p.image.isBlank()) null else p.image) {
                placeholder(R.drawable.ic_placeholder)
                error(R.drawable.ic_placeholder)
                fallback(R.drawable.ic_placeholder)
            }

            // แตะที่รายการเพื่อเปิดเพลย์ลิสต์ / กดค้างหรือกด ⋮ เพื่อเปิดเมนู (เปลี่ยนชื่อ/ลบ)
            itemView.setOnClickListener { onClick(p) }
            itemView.setOnLongClickListener { onLongClick(p); true }
            btnMenu?.setOnClickListener { onLongClick(p) }
        }
    }
}
