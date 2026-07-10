package com.propdf.editor.ui.files

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.propdf.core.domain.model.PdfDocument
import com.propdf.editor.R
import com.propdf.editor.utils.formatFileSize
import java.text.SimpleDateFormat
import java.util.*

class DocumentListAdapter(
    private val onItemClick: (PdfDocument) -> Unit,
    private val onItemLongClick: (PdfDocument) -> Unit,
    private val onFavoriteClick: (PdfDocument) -> Unit,
    private val onMoreClick: (PdfDocument) -> Unit
) : ListAdapter<PdfDocument, DocumentListAdapter.ViewHolder>(DiffCallback()) {

    private val selectedItems = mutableSetOf<Long>()
    var isSelectionMode = false
        set(value) {
            field = value
            if (!value) selectedItems.clear()
            notifyDataSetChanged()
        }

    fun toggleSelection(documentId: Long) {
        if (documentId in selectedItems) {
            selectedItems.remove(documentId)
        } else {
            selectedItems.add(documentId)
        }
        notifyItemChanged(currentList.indexOfFirst { it.id == documentId })
    }

    fun getSelectedItems(): Set<Long> = selectedItems.toSet()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_document_list, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconView: ImageView = itemView.findViewById(R.id.documentIcon)
        private val nameView: TextView = itemView.findViewById(R.id.documentName)
        private val infoView: TextView = itemView.findViewById(R.id.documentInfo)
        private val favoriteView: ImageView = itemView.findViewById(R.id.favoriteIcon)
        private val moreView: ImageView = itemView.findViewById(R.id.moreButton)
        private val selectionView: View = itemView.findViewById(R.id.selectionOverlay)

        fun bind(document: PdfDocument) {
            nameView.text = document.displayName
            infoView.text = "${formatFileSize(document.fileSize)} · ${formatDate(document.dateModified)}"
            
            iconView.setImageResource(
                when {
                    document.isDeleted -> R.drawable.ic_delete
                    document.isFavorite -> R.drawable.ic_favorite
                    else -> R.drawable.ic_pdf
                }
            )

            favoriteView.setImageResource(
                if (document.isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_border
            )
            favoriteView.setOnClickListener { onFavoriteClick(document) }

            moreView.setOnClickListener { onMoreClick(document) }

            val isSelected = document.id in selectedItems
            selectionView.visibility = if (isSelected) View.VISIBLE else View.GONE
            itemView.alpha = if (isSelected) 0.7f else 1f

            itemView.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(document.id)
                } else {
                    onItemClick(document)
                }
            }

            itemView.setOnLongClickListener {
                if (!isSelectionMode) {
                    isSelectionMode = true
                    toggleSelection(document.id)
                    onItemLongClick(document)
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    class DiffCallback : DiffUtil.ItemCallback<PdfDocument>() {
        override fun areItemsTheSame(oldItem: PdfDocument, newItem: PdfDocument): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: PdfDocument, newItem: PdfDocument): Boolean {
            return oldItem == newItem
        }
    }
}
