package com.propdf.editor.ui.viewer


import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView


class PdfPageAdapter(
    private val renderer: PdfRenderer
) : RecyclerView.Adapter<PdfPageAdapter.PageViewHolder>() {


    inner class PageViewHolder(val container: FrameLayout) :
        RecyclerView.ViewHolder(container)


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val container = FrameLayout(parent.context)
        container.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return PageViewHolder(container)
    }


    override fun getItemCount(): Int = renderer.pageCount


    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.container.removeAllViews()


        val pageView = AnnotatedPageView(holder.container.context)
        val page = renderer.openPage(position)


        val bitmap = Bitmap.createBitmap(
            page.width,
            page.height,
            Bitmap.Config.ARGB_8888
        )


        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()


        pageView.setPageBitmap(bitmap)


        holder.container.addView(pageView)
    }
}