package com.propdf.editor.ui.viewer

import android.graphics.pdf.PdfRenderer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.propdf.editor.R
import kotlinx.coroutines.CoroutineScope

// STUB: Not currently used by ViewerActivity.
// ViewerActivity uses its own renderPageBitmap() + LruCache pipeline.
// This file compiles but is inactive. Delete if not needed for future RecyclerView migration.
class PdfPageAdapter(
    private val renderer: PdfRenderer,
    private val scope: CoroutineScope
) : RecyclerView.Adapter<PdfPageAdapter.PageViewHolder>() {

    override fun getItemCount(): Int = renderer.pageCount.coerceAtLeast(0)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pdf_page, parent, false)
        return PageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.bind(position)
    }

    inner class PageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.pageImage)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.pageProgress)
        private val pageNumberLabel: TextView = itemView.findViewById(R.id.pageNumber)

        fun bind(pageIndex: Int) {
            pageNumberLabel.text = "${pageIndex + 1}"
            progressBar.visibility = View.GONE
        }
    }

    companion object {
        const val PAYLOAD_ZOOM = "zoom"
        const val PAYLOAD_THEME = "theme"
    }
}
