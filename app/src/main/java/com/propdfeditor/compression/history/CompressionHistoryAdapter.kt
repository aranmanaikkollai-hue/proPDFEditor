package com.propdfeditor.compression.history

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.propdf.core.data.local.CompressionHistoryEntity
import com.propdf.editor.databinding.ItemCompressionHistoryBinding
import java.text.SimpleDateFormat
import java.util.Locale

class CompressionHistoryAdapter(
    private val onItemClick: (CompressionHistoryEntity) -> Unit
) : ListAdapter<CompressionHistoryEntity, CompressionHistoryAdapter.ViewHolder>(DiffCallback()) {

    private val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCompressionHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemCompressionHistoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CompressionHistoryEntity) {
            binding.apply {
                fileNameText.text = item.fileName
                dateText.text = dateFormat.format(item.timestamp)
                
                val ratio = (item.compressionRatio * 100).toInt()
                ratioText.text = "$ratio% smaller"
                ratioText.setTextColor(
                    if (ratio > 50) 
                        androidx.core.content.ContextCompat.getColor(root.context, android.R.color.holo_green_dark)
                    else 
                        androidx.core.content.ContextCompat.getColor(root.context, android.R.color.holo_orange_dark)
                )
                
                sizesText.text = "${formatBytes(item.originalSizeBytes)} → ${formatBytes(item.compressedSizeBytes)}"
                
                root.setOnClickListener { onItemClick(item) }
            }
        }

        private fun formatBytes(bytes: Long): String {
            return when {
                bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
                bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
                else -> "$bytes B"
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<CompressionHistoryEntity>() {
        override fun areItemsTheSame(
            oldItem: CompressionHistoryEntity,
            newItem: CompressionHistoryEntity
        ): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(
            oldItem: CompressionHistoryEntity,
            newItem: CompressionHistoryEntity
        ): Boolean = oldItem == newItem
    }
}
