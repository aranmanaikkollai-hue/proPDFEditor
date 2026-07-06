package com.propdf.editor.ui.ocr

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.propdf.core.domain.model.OcrTextBlock
import com.propdf.editor.databinding.ItemOcrBlockBinding

class OcrResultAdapter(
    private val onBlockClick: (OcrTextBlock) -> Unit
) : ListAdapter<OcrTextBlock, OcrResultAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(ItemOcrBlockBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) { holder.bind(getItem(position)) }

    inner class ViewHolder(private val binding: ItemOcrBlockBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(block: OcrTextBlock) {
            binding.blockText.text = block.text
            binding.blockConfidence.text = "${(block.confidence * 100).toInt()}%"
            binding.blockLanguage.text = block.language
            val confidenceColor = when {
                block.confidence >= 0.9f -> android.graphics.Color.GREEN
                block.confidence >= 0.7f -> android.graphics.Color.YELLOW
                else -> android.graphics.Color.RED
            }
            binding.confidenceIndicator.setBackgroundColor(confidenceColor)
            binding.root.setOnClickListener { onBlockClick(block) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<OcrTextBlock>() {
        override fun areItemsTheSame(oldItem: OcrTextBlock, newItem: OcrTextBlock) =
            oldItem.text == newItem.text && oldItem.boundingBox == newItem.boundingBox
        override fun areContentsTheSame(oldItem: OcrTextBlock, newItem: OcrTextBlock) = oldItem == newItem
    }
}
