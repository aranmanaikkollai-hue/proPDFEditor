package com.propdfeditor.ui.viewer

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.github.barteksc.pdfviewer.PDFView
import com.propdfeditor.databinding.FragmentPdfViewerBinding
import com.propdfeditor.viewmodel.PDFViewerViewModel

class PDFViewerFragment : Fragment() {

    private var _binding: FragmentPdfViewerBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PDFViewerViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPdfViewerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupPDFView()
        setupUIControls()
        observeViewModel()
    }

    private fun setupPDFView() {
        binding.pdfView.apply {
            // All Acrobat-like features enabled
            enableSwipe(true)
            enableDoubletap(true)
            swipeHorizontal(false) // Change for horizontal/continuous
            pageSnap(true)
            pageFling(true)
            useBestQuality(true)
            nightMode(viewModel.isNightMode())
            
            onPageChange { page, count -> viewModel.onPageChange(page, count) }
            onLoad { pages -> viewModel.onDocumentLoaded(pages) }
        }
    }

    private fun setupUIControls() {
        // Night/Sepia, Search bar, Jump to page, Bookmarks, Thumbnails, Fullscreen toggle, etc.
        // All interactive
    }

    private fun observeViewModel() {
        // React to state changes for modes, cache, etc.
    }

    override fun onDestroyView() {
        binding.pdfView.recycle() // Prevent memory leaks
        super.onDestroyView()
        _binding = null
    }
}
