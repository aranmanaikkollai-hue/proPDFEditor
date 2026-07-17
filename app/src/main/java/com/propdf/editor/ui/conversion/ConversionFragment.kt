package com.propdf.editor.ui.conversion

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
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.propdf.editor.R
import com.propdf.editor.databinding.FragmentConversionBinding
import com.propdf.editor.domain.model.ConversionType
import com.propdf.editor.ui.conversion.adapter.ConversionHistoryAdapter
import com.propdf.editor.ui.conversion.adapter.SelectedFileAdapter
import com.propdf.editor.utils.FileUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@AndroidEntryPoint
class ConversionFragment : Fragment() {

    private var _binding: FragmentConversionBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ConversionViewModel by viewModels()

    private lateinit var selectedFileAdapter: SelectedFileAdapter
    private lateinit var historyAdapter: ConversionHistoryAdapter

    private var currentConversionType: ConversionType? = null

    private val pickPdfLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.addFile(it) }
    }

    private val pickMultipleImagesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris?.let { viewModel.selectFiles(it) }
    }

    private val pickHtmlLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.addFile(it) }
    }

    private val pickTextLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.addFile(it) }
    }

    private val pickMarkdownLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.addFile(it) }
    }

    private val pickMultipleFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris?.let { viewModel.selectFiles(it) }
    }

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        // Not used directly - output goes to app directory
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConversionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerViews()
        setupConversionTypeSelector()
        setupButtons()
        observeViewModel()
    }

    private fun setupRecyclerViews() {
        selectedFileAdapter = SelectedFileAdapter(
            onRemove = { uri -> viewModel.removeFile(uri) }
        )

        binding.selectedFilesRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = selectedFileAdapter
        }

        historyAdapter = ConversionHistoryAdapter(
            onOpen = { task ->
                task.outputUri?.let { uriString ->
                    FileUtils.openFile(requireContext(), uriString.toUri())
                }
            },
            onDelete = { task ->
                showDeleteConfirmDialog(task.id)
            },
            onRetry = { task ->
                viewModel.retryTask(task.id)
            }
        )

        binding.historyRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = historyAdapter
        }
    }

    private fun setupConversionTypeSelector() {
        binding.conversionTypeChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            when (checkedIds.firstOrNull()) {
                R.id.chipPdfToImages -> setConversionType(ConversionType.PDF_TO_IMAGES)
                R.id.chipImagesToPdf -> setConversionType(ConversionType.IMAGES_TO_PDF)
                R.id.chipPdfToTxt -> setConversionType(ConversionType.PDF_TO_TXT)
                R.id.chipTxtToPdf -> setConversionType(ConversionType.TXT_TO_PDF)
                R.id.chipHtmlToPdf -> setConversionType(ConversionType.HTML_TO_PDF)
                R.id.chipMarkdownToPdf -> setConversionType(ConversionType.MARKDOWN_TO_PDF)
                R.id.chipMergeImages -> setConversionType(ConversionType.MERGE_IMAGES)
                R.id.chipSplitImage -> setConversionType(ConversionType.SPLIT_IMAGE)
                R.id.chipZipExport -> setConversionType(ConversionType.ZIP_EXPORT)
                else -> currentConversionType = null
            }
            updateUIForConversionType()
        }
    }

    private fun setConversionType(type: ConversionType) {
        currentConversionType = type
        viewModel.clearSelection()
        updateUIForConversionType()
    }

    private fun updateUIForConversionType() {
        val type = currentConversionType ?: return
        
        binding.apply {
            when (type) {
                ConversionType.PDF_TO_IMAGES -> {
                    selectFilesButton.text = getString(R.string.select_pdf)
                    selectFilesButton.isVisible = true
                    outputNameHint.hint = getString(R.string.output_name_hint_images)
                }
                ConversionType.IMAGES_TO_PDF -> {
                    selectFilesButton.text = getString(R.string.select_images)
                    selectFilesButton.isVisible = true
                    outputNameHint.hint = getString(R.string.output_name_hint_pdf)
                }
                ConversionType.PDF_TO_TXT -> {
                    selectFilesButton.text = getString(R.string.select_pdf)
                    selectFilesButton.isVisible = true
                    outputNameHint.hint = getString(R.string.output_name_hint_txt)
                }
                ConversionType.TXT_TO_PDF -> {
                    selectFilesButton.text = getString(R.string.select_txt)
                    selectFilesButton.isVisible = true
                    outputNameHint.hint = getString(R.string.output_name_hint_pdf)
                }
                ConversionType.HTML_TO_PDF -> {
                    selectFilesButton.text = getString(R.string.select_html)
                    selectFilesButton.isVisible = true
                    outputNameHint.hint = getString(R.string.output_name_hint_pdf)
                }
                ConversionType.MARKDOWN_TO_PDF -> {
                    selectFilesButton.text = getString(R.string.select_markdown)
                    selectFilesButton.isVisible = true
                    outputNameHint.hint = getString(R.string.output_name_hint_pdf)
                }
                ConversionType.MERGE_IMAGES -> {
                    selectFilesButton.text = getString(R.string.select_images_merge)
                    selectFilesButton.isVisible = true
                    outputNameHint.hint = getString(R.string.output_name_hint_merged)
                }
                ConversionType.SPLIT_IMAGE -> {
                    selectFilesButton.text = getString(R.string.select_image_split)
                    selectFilesButton.isVisible = true
                    outputNameHint.hint = getString(R.string.output_name_hint_split)
                }
                ConversionType.ZIP_EXPORT -> {
                    selectFilesButton.text = getString(R.string.select_files_zip)
                    selectFilesButton.isVisible = true
                    outputNameHint.hint = getString(R.string.output_name_hint_zip)
                }
            }
        }
    }

    private fun setupButtons() {
        binding.selectFilesButton.setOnClickListener {
            when (currentConversionType) {
                ConversionType.PDF_TO_IMAGES, ConversionType.PDF_TO_TXT -> {
                    pickPdfLauncher.launch(arrayOf("application/pdf"))
                }
                ConversionType.IMAGES_TO_PDF, ConversionType.MERGE_IMAGES -> {
                    pickMultipleImagesLauncher.launch(arrayOf("image/*"))
                }
                ConversionType.TXT_TO_PDF -> {
                    pickTextLauncher.launch(arrayOf("text/plain"))
                }
                ConversionType.HTML_TO_PDF -> {
                    pickHtmlLauncher.launch(arrayOf("text/html", "application/xhtml+xml"))
                }
                ConversionType.MARKDOWN_TO_PDF -> {
                    pickMarkdownLauncher.launch(arrayOf("text/markdown", "text/plain", "text/x-markdown"))
                }
                ConversionType.SPLIT_IMAGE -> {
                    pickMultipleImagesLauncher.launch(arrayOf("image/*"))
                }
                ConversionType.ZIP_EXPORT -> {
                    pickMultipleFilesLauncher.launch(arrayOf("*/*"))
                }
                else -> {
                    Toast.makeText(requireContext(), R.string.select_conversion_type_first, Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.convertButton.setOnClickListener {
            startConversion()
        }

        binding.cancelButton.setOnClickListener {
            viewModel.currentTask.value?.id?.let { taskId ->
                viewModel.cancelConversion(taskId)
            }
        }

        binding.clearHistoryButton.setOnClickListener {
            showClearHistoryDialog()
        }
    }

    private fun startConversion() {
        val type = currentConversionType
        if (type == null) {
            Toast.makeText(requireContext(), R.string.select_conversion_type, Toast.LENGTH_SHORT).show()
            return
        }

        val files = viewModel.selectedFiles.value
        if (files.isEmpty() && type != ConversionType.ZIP_EXPORT) {
            Toast.makeText(requireContext(), R.string.select_files_first, Toast.LENGTH_SHORT).show()
            return
        }

        val outputName = binding.outputNameEditText.text?.toString()?.trim()
        if (outputName.isNullOrEmpty()) {
            binding.outputNameEditText.error = getString(R.string.output_name_required)
            return
        }

        binding.outputNameEditText.error = null
        viewModel.startConversion(type, outputName)
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.selectedFiles.collect { files ->
                        selectedFileAdapter.submitList(files)
                        binding.selectedFilesRecyclerView.isVisible = files.isNotEmpty()
                        binding.selectedFilesLabel.isVisible = files.isNotEmpty()
                        binding.convertButton.isEnabled = files.isNotEmpty() && currentConversionType != null
                    }
                }

                launch {
                    viewModel.conversionState.collect { state ->
                        handleConversionState(state)
                    }
                }

                launch {
                    viewModel.allTasks.collect { tasks ->
                        historyAdapter.submitList(tasks)
                        binding.historyRecyclerView.isVisible = tasks.isNotEmpty()
                        binding.emptyHistoryView.isVisible = tasks.isEmpty()
                    }
                }

                launch {
                    viewModel.currentTask.collect { task ->
                        binding.cancelButton.isVisible = task?.status == com.propdf.editor.domain.model.ConversionStatus.RUNNING
                    }
                }
            }
        }
    }

    private fun handleConversionState(state: ConversionUiState) {
        binding.apply {
            when (state) {
                is ConversionUiState.Idle -> {
                    progressBar.isVisible = false
                    progressText.isVisible = false
                    convertButton.isEnabled = selectedFilesRecyclerView.isVisible
                    cancelButton.isVisible = false
                }
                is ConversionUiState.Preparing -> {
                    progressBar.isVisible = true
                    progressBar.isIndeterminate = true
                    progressText.isVisible = true
                    progressText.text = getString(R.string.preparing)
                    convertButton.isEnabled = false
                    cancelButton.isVisible = true
                }
                is ConversionUiState.Processing -> {
                    progressBar.isVisible = true
                    progressBar.isIndeterminate = true
                    progressText.isVisible = true
                    progressText.text = getString(R.string.processing)
                    convertButton.isEnabled = false
                    cancelButton.isVisible = true
                }
                is ConversionUiState.Progress -> {
                    progressBar.isVisible = true
                    progressBar.isIndeterminate = false
                    progressBar.progress = state.percent
                    progressText.isVisible = true
                    progressText.text = getString(R.string.progress_percent, state.percent)
                    convertButton.isEnabled = false
                    cancelButton.isVisible = true
                }
                is ConversionUiState.Success -> {
                    progressBar.isVisible = false
                    progressText.isVisible = true
                    progressText.text = getString(R.string.conversion_success, state.message)
                    convertButton.isEnabled = true
                    cancelButton.isVisible = false
                    
                    Snackbar.make(
                        root,
                        getString(R.string.conversion_completed, state.fileCount),
                        Snackbar.LENGTH_LONG
                    ).setAction(R.string.open) {
                        state.outputUri?.let { FileUtils.openFile(requireContext(), it) }
                    }.show()
                    
                    viewModel.resetState()
                }
                is ConversionUiState.Error -> {
                    progressBar.isVisible = false
                    progressText.isVisible = true
                    progressText.text = getString(R.string.conversion_error, state.message)
                    convertButton.isEnabled = true
                    cancelButton.isVisible = false
                    
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.error)
                        .setMessage(state.message)
                        .setPositiveButton(R.string.ok) { dialog, _ -> dialog.dismiss() }
                        .show()
                    
                    viewModel.resetState()
                }
                is ConversionUiState.Cancelled -> {
                    progressBar.isVisible = false
                    progressText.isVisible = true
                    progressText.text = getString(R.string.conversion_cancelled)
                    convertButton.isEnabled = true
                    cancelButton.isVisible = false
                    viewModel.resetState()
                }
            }
        }
    }

    private fun showDeleteConfirmDialog(taskId: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_task)
            .setMessage(R.string.delete_task_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteTask(taskId)
            }
            .setNegativeButton(R.string.cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showClearHistoryDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.clear_history)
            .setMessage(R.string.clear_history_confirm)
            .setPositiveButton(R.string.clear) { _, _ ->
                viewModel.cleanup()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
