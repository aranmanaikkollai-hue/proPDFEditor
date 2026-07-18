// security/src/main/java/com/propdf/security/ui/adapter/RedactionAdapter.kt
package com.propdf.security.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.propdf.security.data.entity.RedactionEntity
import com.propdf.security.databinding.ItemRedactionBinding

class RedactionAdapter(
    private val onDelete: (RedactionEntity) -> Unit
) : ListAdapter<RedactionEntity, RedactionAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRedactionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemRedactionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(redaction: RedactionEntity) {
            binding.tvPageNumber.text = "Page ${redaction.pageNumber + 1}"
            binding.tvCoordinates.text = 
                "(${redaction.rect.left.toInt()}, ${redaction.rect.top.toInt()}) - " +
                "(${redaction.rect.right.toInt()}, ${redaction.rect.bottom.toInt()})"
            binding.tvOverlayText.text = redaction.overlayText ?: "No overlay text"
            
            binding.btnDelete.setOnClickListener {
                onDelete(redaction)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<RedactionEntity>() {
        override fun areItemsTheSame(old: RedactionEntity, new: RedactionEntity): Boolean {
            return old.id == new.id
        }

        override fun areContentsTheSame(old: RedactionEntity, new: RedactionEntity): Boolean {
            return old == new
        }
    }
}
