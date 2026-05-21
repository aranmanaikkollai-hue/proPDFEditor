package com.propdf.viewer.pdf.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.slider.Slider
import com.propdf.viewer.databinding.BottomSheetPdfToolsBinding
import com.propdf.viewer.pdf.CompressionOptions
import com.propdf.viewer.pdf.CompressionQuality
import com.propdf.viewer.pdf.PdfToolsViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class PdfToolsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetPdfToolsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PdfToolsViewModel by viewModels()

    private var currentSourceUri: Uri? = null
    private var currentPageCount: Int = 0

    companion object {
        private const val ARG_SOURCE_URI = "source_uri"
        private const val ARG_PAGE_COUNT = "page_count"

        fun newInstance(sourceUri: Uri, pageCount: Int): PdfToolsBottomSheet {
            return PdfToolsBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_SOURCE_URI, sourceUri.toString())
                    putInt(ARG_PAGE_COUNT, pageCount)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentSourceUri = arguments?.getString(ARG_SOURCE_URI)?.let { Uri.parse(it) }
        currentPageCount = arguments?.getInt(ARG_PAGE_COUNT, 0) ?: 0
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetPdfToolsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupCompressTab()
        setupActions()
        observeViewModel()
    }

    private fun setupCompressTab() {
        binding.qualitySlider.addOnChangeListener(Slider.OnChangeListener { _, value, _ ->
            val quality = when (value.toInt()) {
                0 -> CompressionQuality.LOW
                1 -> CompressionQuality.MEDIUM
                else -> CompressionQuality.HIGH
            }
            binding.qualityLabel.text = when (quality) {
                CompressionQuality.LOW -> "Low quality (smallest file)"
                CompressionQuality.MEDIUM -> "Medium quality (balanced)"
                CompressionQuality.HIGH -> "High quality (minimal compression)"
            }
        })

        binding.btnCompress.setOnClickListener {
            val uri = currentSourceUri ?: return@setOnClickListener
            val quality = when (binding.qualitySlider.value.toInt()) {
                0 -> CompressionQuality.LOW
                1 -> CompressionQuality.MEDIUM
                else -> CompressionQuality.HIGH
            }
            val options = CompressionOptions(
                quality = quality,
                grayscale = binding.chkGrayscale.isChecked,
                optimizeFonts = binding.chkOptimizeFonts.isChecked
            )
            viewModel.compressPdf(uri, options)
        }
    }

    private fun setupActions() {
        binding.btnSplit.setOnClickListener {
            currentSourceUri?.let { viewModel.splitPdf(it) }
        }

        binding.btnExtract.setOnClickListener {
            currentSourceUri?.let { uri ->
                val pages = parsePageRange(binding.pageRangeInput.text.toString(), currentPageCount)
                if (pages.isNotEmpty()) viewModel.extractPages(uri, pages)
            }
        }

        binding.btnRotateLeft.setOnClickListener {
            currentSourceUri?.let { uri ->
                val pages = parsePageRange(binding.pageRangeInput.text.toString(), currentPageCount)
                    .ifEmpty { (0 until currentPageCount).toList() }
                viewModel.rotatePages(uri, pages, -90)
            }
        }

        binding.btnRotateRight.setOnClickListener {
            currentSourceUri?.let { uri ->
                val pages = parsePageRange(binding.pageRangeInput.text.toString(), currentPageCount)
                    .ifEmpty { (0 until currentPageCount).toList() }
                viewModel.rotatePages(uri, pages, 90)
            }
        }

        binding.btnDelete.setOnClickListener {
            currentSourceUri?.let { uri ->
                val pages = parsePageRange(binding.pageRangeInput.text.toString(), currentPageCount)
                if (pages.isNotEmpty()) viewModel.deletePages(uri, pages)
            }
        }

        binding.btnCancel.setOnClickListener {
            viewModel.cancelAll()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collectLatest { state ->
                        updateUi(state)
                    }
                }
                launch {
                    viewModel.activeOperations.collectLatest { operations ->
                        val active = operations.values.filter { it.isRunning }
                        binding.progressBar.isVisible = active.isNotEmpty()
                        binding.progressText.isVisible = active.isNotEmpty()

                        if (active.isNotEmpty()) {
                            val avgProgress = active.map { it.progressPercent }.average()
                            binding.progressBar.progress = avgProgress.toInt()
                            binding.progressText.text = "Processing... ${avgProgress.toInt()}%"
                        }

                        binding.btnCancel.isVisible = active.isNotEmpty()
                        binding.btnCompress.isEnabled = active.isEmpty()
                        binding.btnSplit.isEnabled = active.isEmpty()
                        binding.btnExtract.isEnabled = active.isEmpty()
                        binding.btnRotateLeft.isEnabled = active.isEmpty()
                        binding.btnRotateRight.isEnabled = active.isEmpty()
                        binding.btnDelete.isEnabled = active.isEmpty()
                    }
                }
            }
        }
    }

    private fun updateUi(state: PdfToolsViewModel.PdfToolsUiState) {
        if (state.error != null) {
            binding.errorText.text = state.error
            binding.errorText.isVisible = true
        } else {
            binding.errorText.isVisible = false
        }

        state.lastOutputFile?.let { file ->
            binding.resultText.text = "Saved: ${file.name} (${formatFileSize(file.length())})"
            binding.resultText.isVisible = true
            if (state.compressionRatio > 0) {
                binding.compressionText.text = "Reduced by ${(state.compressionRatio * 100).toInt()}%"
                binding.compressionText.isVisible = true
            }
        }
    }

    private fun parsePageRange(range: String, maxPages: Int): List<Int> {
        if (range.isBlank()) return emptyList()
        val result = mutableListOf<Int>()
        val parts = range.split(",", " ")
        parts.forEach { part ->
            when {
                part.contains("-") -> {
                    val (start, end) = part.split("-").map { it.trim().toIntOrNull() ?: 0 }
                    result.addAll((start - 1 until end).filter { it in 0 until maxPages })
                }
                else -> {
                    part.toIntOrNull()?.let { page ->
                        if (page in 1..maxPages) result.add(page - 1)
                    }
                }
            }
        }
        return result.distinct().sorted()
    }

    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> String.format("%.2f MB", size / (1024.0 * 1024.0))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
