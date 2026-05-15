package com.propdf.viewer.presentation

import android.graphics.Bitmap
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

/**
 * Adapter for thumbnail sidebar showing page previews.
 * Supports selection highlighting and async bitmap loading.
 */
class ThumbnailAdapter(
    private val onThumbnailClick: (Int) -> Unit
) : ListAdapter<ThumbnailItem, ThumbnailAdapter.ThumbnailViewHolder>(DiffCallback()) {

    private var selectedPosition = 0

    fun setSelectedPosition(position: Int) {
        val oldPosition = selectedPosition
        selectedPosition = position
        notifyItemChanged(oldPosition)
        notifyItemChanged(position)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThumbnailViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_thumbnail, parent, false)
        return ThumbnailViewHolder(view, onThumbnailClick)
    }

    override fun onBindViewHolder(holder: ThumbnailViewHolder, position: Int) {
        holder.bind(getItem(position), position == selectedPosition)
    }

    class ThumbnailViewHolder(
        itemView: View,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val imageView: ImageView = itemView.findViewById(R.id.thumbnailImage)
        private val pageNumber: TextView = itemView.findViewById(R.id.pageNumber)
        private val selectedIndicator: View = itemView.findViewById(R.id.selectedIndicator)

        init {
            itemView.setOnClickListener {
                onClick(bindingAdapterPosition)
            }
        }

        fun bind(item: ThumbnailItem, isSelected: Boolean) {
            pageNumber.text = (item.pageIndex + 1).toString()
            selectedIndicator.isVisible = isSelected

            if (item.bitmap != null && !item.bitmap.isRecycled) {
                imageView.setImageBitmap(item.bitmap)
                imageView.isVisible = true
            } else {
                imageView.isVisible = false
            }

            itemView.isSelected = isSelected
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ThumbnailItem>() {
        override fun areItemsTheSame(oldItem: ThumbnailItem, newItem: ThumbnailItem): Boolean {
            return oldItem.pageIndex == newItem.pageIndex
        }
        override fun areContentsTheSame(oldItem: ThumbnailItem, newItem: ThumbnailItem): Boolean {
            return oldItem == newItem
        }
    }

    data class ThumbnailItem(
        val pageIndex: Int,
        val bitmap: Bitmap? = null,
        val isLoading: Boolean = false
    )
}
