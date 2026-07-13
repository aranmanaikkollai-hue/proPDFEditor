package com.propdf.editor.ui.files

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.propdf.editor.R
import com.propdf.editor.domain.model.PdfDocument
import com.propdf.editor.utils.formatFileSize

class DocumentListAdapter(
    private val onItemClick: (PdfDocument) -> Unit,
    private val onFavoriteClick: (PdfDocument) -> Unit,
    private val onMoreClick: (PdfDocument) -> Unit
) : ListAdapter<PdfDocument, DocumentListAdapter.ViewHolder>(DiffCallback()) {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val documentIcon: ImageView = itemView.findViewById(R.id.documentIcon)
        val documentName: TextView = itemView.findViewById(R.id.documentName)
        val documentInfo: TextView = itemView.findViewById(R.id.documentInfo)
        val favoriteIcon: ImageView = itemView.findViewById(R.id.favoriteIcon)
        val moreButton: ImageView = itemView.findViewById(R.id.moreButton)
        val selectionOverlay: View = itemView.findViewById(R.id.selectionOverlay)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_document_list, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val doc = getItem(position)

        holder.documentName.text = doc.displayName
        holder.documentInfo.text = "${formatFileSize(doc.fileSize)} · ${doc.category.displayName}"
        holder.favoriteIcon.setImageResource(
            if (doc.isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_border
        )

        holder.itemView.setOnClickListener { onItemClick(doc) }
        holder.favoriteIcon.setOnClickListener { onFavoriteClick(doc) }
        holder.moreButton.setOnClickListener { onMoreClick(doc) }
    }

    class DiffCallback : DiffUtil.ItemCallback<PdfDocument>() {
        override fun areItemsTheSame(old: PdfDocument, new: PdfDocument) = old.id == new.id
        override fun areContentsTheSame(old: PdfDocument, new: PdfDocument) = old == new
    }
}
