package com.propdfeditor.compression

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.propdf.core.domain.model.CompressionConfig
import com.propdf.core.domain.model.CompressionStrategy
import com.propdf.core.domain.model.QualityPreset
import com.propdf.editor.databinding.FragmentCompressionBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.DecimalFormat

@AndroidEntryPoint
class CompressionFragment : Fragment() {

    private var _binding: FragmentCompressionBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CompressionViewModel by viewModels()

    private val pickPdf = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val size = getFileSize(it)
            val pages = getPageCount(it)
            viewModel.selectFile(it.toString(), size, pages)
        }
    }

    private val savePdf = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        uri?.let {
            viewModel.startCompression(it.toString())
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCompressionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUi()
        observeState()
    }

    private fun setupUi() {
        binding.apply {
            // File selection
            selectFileButton.setOnClickListener {
                pickPdf.launch("application/pdf")
            }

            // Preset chips
            presetGroup.setOnCheckedStateChangeListener { _, checkedIds ->
                val preset = when (checkedIds.firstOrNull()) {
                    chipScreen.id -> QualityPreset.SCREEN
                    chipEbook.id -> QualityPreset.EBOOK
                    chipPrinter.id -> QualityPreset.PRINTER
                    chipPrepress.id -> QualityPreset.PREPRESS
                    chipArchive.id -> QualityPreset.ARCHIVE
                    else -> return@setOnCheckedStateChangeListener
                }
                viewModel.selectPreset(preset)
            }

            // Custom controls
            qualitySlider.addOnChangeListener(Slider.OnChangeListener { _, value, fromUser ->
                if (fromUser) updateCustomConfig()
            })

            maxDimensionSlider.addOnChangeListener(Slider.OnChangeListener { _, value, fromUser ->
                if (fromUser) updateCustomConfig()
            })

            removeMetadataSwitch.setOnCheckedChangeListener { _, _ -> updateCustomConfig() }
            optimizeFontsSwitch.setOnCheckedChangeListener { _, _ -> updateCustomConfig() }
            removeUnusedSwitch.setOnCheckedChangeListener { _, _ -> updateCustomConfig() }
            linearizeSwitch.setOnCheckedChangeListener { _, _ -> updateCustomConfig() }

            // Action buttons
            compressButton.setOnClickListener {
                val state = viewModel.uiState.value
                if (state.sourceUri == null) {
                    Toast.makeText(requireContext(), "Select a file first", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                savePdf.launch("compressed_${System.currentTimeMillis()}.pdf")
            }

            cancelButton.setOnClickListener {
                viewModel.cancelCompression()
            }

            // Result actions
            shareButton.setOnClickListener {
                shareResult()
            }

            openButton.setOnClickListener {
                openResult()
            }
        }
    }

    private fun updateCustomConfig() {
        binding.apply {
            val config = CompressionConfig(
                strategy = CompressionStrategy.CUSTOM,
                imageQuality = qualitySlider.value.toInt(),
                maxImageDimension = maxDimensionSlider.value.toInt(),
                downsampleDpi = when (maxDimensionSlider.value) {
                    in 0f..1200f -> 72f
                    in 1200f..2000f -> 150f
                    else -> 300f
                },
                removeMetadata = removeMetadataSwitch.isChecked,
                removeUnusedObjects = removeUnusedSwitch.isChecked,
                optimizeFonts = optimizeFontsSwitch.isChecked,
                linearize = linearizeSwitch.isChecked,
                compressStreams = true
            )
            viewModel.updateConfig(config)
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderState(state)
                }
            }
        }
    }

    private fun renderState(state: CompressionUiState) {
        binding.apply {
            // File info
            fileInfoCard.isVisible = state.sourceUri != null
            if (state.sourceUri != null) {
                fileNameText.text = Uri.parse(state.sourceUri).lastPathSegment
                fileSizeText.text = formatBytes(state.originalSizeBytes)
                pageCountText.text = "${state.pageCount} pages"
            }

            // Preset selection
            when (state.selectedPreset) {
                QualityPreset.SCREEN -> chipScreen.isChecked = true
                QualityPreset.EBOOK -> chipEbook.isChecked = true
                QualityPreset.PRINTER -> chipPrinter.isChecked = true
                QualityPreset.PREPRESS -> chipPrepress.isChecked = true
                QualityPreset.ARCHIVE -> chipArchive.isChecked = true
                null -> presetGroup.clearCheck()
            }

            // Custom controls reflect current config
            qualitySlider.value = state.config.imageQuality.toFloat()
            maxDimensionSlider.value = state.config.maxImageDimension.toFloat()
            removeMetadataSwitch.isChecked = state.config.removeMetadata
            optimizeFontsSwitch.isChecked = state.config.optimizeFonts
            removeUnusedSwitch.isChecked = state.config.removeUnusedObjects
            linearizeSwitch.isChecked = state.config.linearize

            // Preview
            previewCard.isVisible = state.preview != null
            previewLoading.isVisible = state.isPreviewLoading
            state.preview?.let { preview ->
                estimatedSizeText.text = formatBytes(preview.estimatedSizeBytes)
                estimatedSavingsText.text = "-${(preview.estimatedRatio * 100).toInt()}%"
                estimatedQualityText.text = "${(preview.estimatedQualityScore * 100).toInt()}% quality"
                
                warningsText.isVisible = preview.warnings.isNotEmpty()
                warningsText.text = preview.warnings.joinToString("\n")
            }

            // Progress
            progressCard.isVisible = state.isCompressing
            if (state.isCompressing) {
                progressIndicator.setProgress((state.progress * 100).toInt(), true)
                progressText.text = "${(state.progress * 100).toInt()}%"
            }

            // Result
            resultCard.isVisible = state.result != null
            state.result?.let { result ->
                originalSizeResult.text = formatBytes(result.originalSizeBytes)
                compressedSizeResult.text = formatBytes(result.compressedSizeBytes)
                spaceSavedResult.text = formatBytes(result.spaceSavedBytes)
                compressionRatioResult.text = "${result.compressionPercentage}%"
                durationText.text = "${result.durationMs / 1000}s"
                imagesProcessedText.text = "${result.imagesProcessed} images optimized"
                fontsOptimizedText.text = "${result.fontsOptimized} fonts optimized"
            }

            // Error
            state.error?.let { error ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Compression Failed")
                    .setMessage(error)
                    .setPositiveButton("OK") { _, _ -> viewModel.clearError() }
                    .show()
            }

            // Button states
            compressButton.isEnabled = !state.isCompressing && state.sourceUri != null
            cancelButton.isEnabled = state.isCompressing
        }
    }

    private fun shareResult() {
        val uri = viewModel.uiState.value.result?.compressedUri ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
        }
        startActivity(Intent.createChooser(intent, "Share Compressed PDF"))
    }

    private fun openResult() {
        val uri = viewModel.uiState.value.result?.compressedUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(uri), "application/pdf")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        startActivity(intent)
    }

    private fun getFileSize(uri: Uri): Long {
        return try {
            requireContext().contentResolver.openFileDescriptor(uri, "r")?.use {
                it.statSize
            } ?: 0
        } catch (e: Exception) { 0 }
    }

    private fun getPageCount(uri: Uri): Int {
        // Would use PdfRenderer or PDFBox quick load
        return 0 // Simplified
    }

    private fun formatBytes(bytes: Long): String {
        val df = DecimalFormat("#.00")
        return when {
            bytes >= 1024 * 1024 * 1024 -> "${df.format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
            bytes >= 1024 * 1024 -> "${df.format(bytes / (1024.0 * 1024.0))} MB"
            bytes >= 1024 -> "${df.format(bytes / 1024.0)} KB"
            else -> "$bytes B"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
