package com.bio.bioplayer.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.bio.bioplayer.R

class NodeAdapter(
    private val originalNames: List<String>,
    private val originalImages: List<String>,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<NodeAdapter.VH>() {

    var isGrid: Boolean = false
    private var filteredNames = originalNames.toMutableList()
    private var filteredImages = originalImages.toMutableList()
    private var originalIndices = originalNames.indices.toMutableList()

    override fun getItemViewType(position: Int): Int {
        return if (isGrid) 1 else 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == 1) R.layout.item_channel_grid else R.layout.item_channel
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(position)

    override fun getItemCount() = filteredNames.size

    fun filter(query: String) {
        filteredNames.clear()
        filteredImages.clear()
        originalIndices.clear()

        if (query.isEmpty()) {
            filteredNames.addAll(originalNames)
            filteredImages.addAll(originalImages)
            originalIndices.addAll(originalNames.indices)
        } else {
            originalNames.forEachIndexed { index, name ->
                if (name.contains(query, ignoreCase = true)) {
                    filteredNames.add(name)
                    filteredImages.add(originalImages[index])
                    originalIndices.add(index) // เก็บ index ต้นฉบับไว้เพื่อไม่ให้คลิกแล้วลำดับเพี้ยน
                }
            }
        }
        notifyDataSetChanged()
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val channelName: TextView = itemView.findViewById(R.id.channelName)
        private val channelLogo: ImageView = itemView.findViewById(R.id.channelLogo)

        fun bind(position: Int) {
            channelName.text = filteredNames[position]
            val img = filteredImages.getOrNull(position).orEmpty()
            channelLogo.load(if (img.isBlank()) null else img) {
                placeholder(R.drawable.ic_placeholder)
                error(R.drawable.ic_placeholder)
                fallback(R.drawable.ic_placeholder)
            }

            // ส่งคืนค่า index ของข้อมูลต้นฉบับ
            itemView.setOnClickListener { onClick(originalIndices[position]) }
        }
    }
}
