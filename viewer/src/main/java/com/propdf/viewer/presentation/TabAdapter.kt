package com.propdf.viewer.presentation

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.propdf.viewer.R
import com.propdf.viewer.model.PdfTab

/**
 * Horizontal RecyclerView adapter for multi-tab PDF viewing.
 * Shows document name with close button and active state indicator.
 */
class TabAdapter(
    private val onTabSelected: (Int) -> Unit,
    private val onTabClose: (Int) -> Unit
) : ListAdapter<PdfTab, TabAdapter.TabViewHolder>(DiffCallback()) {

    private var activeIndex = 0

    fun setActiveIndex(index: Int) {
        val oldIndex = activeIndex
        activeIndex = index
        notifyItemChanged(oldIndex)
        notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pdf_tab, parent, false)
        return TabViewHolder(view, onTabSelected, onTabClose)
    }

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        holder.bind(getItem(position), position == activeIndex, position)
    }

    class TabViewHolder(
        itemView: View,
        private val onTabSelected: (Int) -> Unit,
        private val onTabClose: (Int) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val tabName: TextView = itemView.findViewById(R.id.tabName)
        private val closeButton: ImageButton = itemView.findViewById(R.id.closeButton)
        private val activeIndicator: View = itemView.findViewById(R.id.activeIndicator)

        fun bind(tab: PdfTab, isActive: Boolean, position: Int) {
            tabName.text = tab.documentName
            tabName.isSelected = isActive
            activeIndicator.isVisible = isActive

            itemView.setOnClickListener { onTabSelected(position) }
            closeButton.setOnClickListener { onTabClose(position) }
            closeButton.isVisible = currentList.size > 1
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
