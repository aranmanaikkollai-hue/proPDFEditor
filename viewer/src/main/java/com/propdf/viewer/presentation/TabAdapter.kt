package com.propdf.viewer.presentation

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.propdf.viewer.R
import com.propdf.viewer.model.PdfTab

/**
 * Adapter for multi-tab viewing bar.
 * Shows document name with close button and active state highlighting.
 */
class TabAdapter(
    private val onTabClick: (Int) -> Unit,
    private val onTabClose: (Int) -> Unit
) : ListAdapter<PdfTab, TabAdapter.TabViewHolder>(DiffCallback()) {

    private var activeIndex = 0

    fun submitList(list: List<PdfTab>, activeIndex: Int) {
        this.activeIndex = activeIndex
        submitList(list)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tab, parent, false)
        return TabViewHolder(view, onTabClick, onTabClose)
    }

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        holder.bind(getItem(position), position == activeIndex)
    }

    class TabViewHolder(
        itemView: View,
        private val onClick: (Int) -> Unit,
        private val onClose: (Int) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val tabName: TextView = itemView.findViewById(R.id.tabName)
        private val closeButton: ImageView = itemView.findViewById(R.id.closeButton)

        init {
            itemView.setOnClickListener { onClick(bindingAdapterPosition) }
            closeButton.setOnClickListener { onClose(bindingAdapterPosition) }
        }

        fun bind(tab: PdfTab, isActive: Boolean) {
            tabName.text = tab.documentName
            itemView.isSelected = isActive
            val backgroundRes = if (isActive) R.drawable.bg_tab_active else R.drawable.bg_tab_inactive
            itemView.setBackgroundResource(backgroundRes)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<PdfTab>() {
        override fun areItemsTheSame(oldItem: PdfTab, newItem: PdfTab): Boolean {
            return oldItem.id == newItem.id
        }
        override fun areContentsTheSame(oldItem: PdfTab, newItem: PdfTab): Boolean {
            return oldItem == newItem
        }
    }
}
