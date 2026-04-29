 * Uses ARGB_8888 for full-quality rendering and annotation compositing.
        ))
    )

    // ---- ViewHolder ------------------------------------------------

    inner class PageVH(val view: AnnotatedPageView) : RecyclerView.ViewHolder(view) {
        var job: Job? = null

        fun bind(pos: Int) {
            job?.cancel()
            job = null
            view.setTool(activeTool, activeColor)
            applyNight()
            job = scope.launch {
                val bmp = render(pos)
                if (isActive) {
                    withContext(Dispatchers.Main) {
                        view.showBitmap(bmp)
                        applyNight()
                    }
                } else {
                    bmp.recycle()
                }
            }
        }

        fun applyNight() {
            view.pageImageView.colorFilter = if (nightMode) nightFilter else null
        }
    }

    // ---- Adapter ---------------------------------------------------

    override fun getItemCount() = renderer.pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        PageVH(AnnotatedPageView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(-1, -2)
        })

    override fun onBindViewHolder(holder: PageVH, position: Int) =
        holder.bind(position)

    override fun onBindViewHolder(holder: PageVH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
class PdfPageAdapter(
 val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val strokes = holder.view.getAnnotationStrokes()
        if (strokes.isEmpty()) return null
        val scale = synchronized(renderer) {
            val page = renderer.openPage(pageIdx)
            val s    = screenWidth.toFloat() / page.width
            page.close()
            s
        }
        return Pair(strokes, scale)
    }
}
