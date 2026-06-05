package com.propdf.viewer.presentation

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.propdf.viewer.R
import com.propdf.viewer.model.PdfTab

class TabAdapter(
    private val onTabClick: (Int) -> Unit,
    private val onTabClose: (Int) -> Unit
) : ListAdapter<PdfTab, TabAdapter.TabViewHolder>(DiffCallback()) {

    private var activeIndex: Int = 0

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

        private val titleView: TextView = itemView.findViewById(R.id.tab_title)
        private val closeView: ImageView = itemView.findViewById(R.id.tab_close)
        private val indicatorView: View = itemView.findViewById(R.id.active_indicator)

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(pos)
            }
            closeView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClose(pos)
            }
        }

        fun bind(tab: PdfTab, isActive: Boolean) {
            titleView.text = tab.documentName
            indicatorView.isVisible = isActive
            itemView.isSelected = isActive
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<PdfTab>() {
        override fun areItemsTheSame(oldItem: PdfTab, newItem: PdfTab): Boolean =
            oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: PdfTab, newItem: PdfTab): Boolean =
            oldItem == newItem
    }
}
