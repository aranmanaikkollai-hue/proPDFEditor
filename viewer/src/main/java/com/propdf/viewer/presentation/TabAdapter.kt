package com.propdf.viewer.presentation

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.propdf.viewer.databinding.ItemTabBinding
import com.propdf.viewer.model.PdfTab

class TabAdapter(
    private val onTabSelected: (Int) -> Unit,
    private val onTabClose: (Int) -> Unit
) : ListAdapter<PdfTab, TabAdapter.TabViewHolder>(TabDiffCallback()) {

    private var selectedPosition: Int = 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
        val binding = ItemTabBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return TabViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        holder.bind(getItem(position), position == selectedPosition)
    }

    fun setSelectedPosition(position: Int) {
        val previousPosition = selectedPosition
        selectedPosition = position
        if (previousPosition in 0 until itemCount) {
            notifyItemChanged(previousPosition)
        }
        if (position in 0 until itemCount) {
            notifyItemChanged(position)
        }
    }

    inner class TabViewHolder(
        private val binding: ItemTabBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onTabSelected(pos)
                }
            }
        }

        fun bind(tab: PdfTab, isSelected: Boolean) {
            binding.tabTitle.text = tab.title
            binding.tabTitle.isSelected = isSelected

            binding.tabClose.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onTabClose(pos)
                }
            }

            binding.activeIndicator.isVisible = isSelected
        }
    }

    class TabDiffCallback : DiffUtil.ItemCallback<PdfTab>() {
        override fun areItemsTheSame(oldItem: PdfTab, newItem: PdfTab): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: PdfTab, newItem: PdfTab): Boolean {
            return oldItem == newItem
        }
    }
}
