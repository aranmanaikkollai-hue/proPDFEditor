package com.propdfeditor.batch.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.propdfeditor.R
import com.propdfeditor.batch.data.util.BatchJobType
import com.propdfeditor.batch.viewmodel.BatchViewModel
import com.propdfeditor.databinding.ActivityBatchBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

@AndroidEntryPoint
class BatchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBatchBinding
    private val viewModel: BatchViewModel by viewModels()

    private lateinit var fileAdapter: BatchFileAdapter
    private lateinit var jobAdapter: BatchJobAdapter

    private val pickFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris?.let { viewModel.selectUris(it.toList()) }
    }

    private val pickOutputDirLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { handleOutputDirSelection(it) }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            openFilePicker()
        } else {
            Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBatchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupFileList()
        setupJobList()
        setupOperationButtons()
        setupObservers()
        handleIntent(intent)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.batch_operations)
    }

    private fun setupFileList() {
        fileAdapter = BatchFileAdapter(
            onRemoveClick = { viewModel.removeUri(it) },
            onItemClick = { /* Preview file */ }
        )

        binding.fileList.apply {
            layoutManager = LinearLayoutManager(this@BatchActivity)
            adapter = fileAdapter
            setHasFixedSize(true)
        }

        binding.addFilesButton.setOnClickListener {
            checkPermissionsAndPick()
        }

        binding.clearSelectionButton.setOnClickListener {
            viewModel.clearSelection()
        }
    }

    private fun setupJobList() {
        jobAdapter = BatchJobAdapter(
            onCancelClick = { viewModel.cancelJob(it.id) },
            onDeleteClick = { showDeleteConfirmDialog(it) },
            onRetryClick = { viewModel.retryFailedJob(it) }
        )

        binding.jobsList.apply {
            layoutManager = LinearLayoutManager(this@BatchActivity)
            adapter = jobAdapter
            setHasFixedSize(true)
        }
    }

    private fun setupOperationButtons() {
        binding.apply {
            mergeButton.setOnClickListener { showMergeDialog() }
            splitButton.setOnClickListener { showSplitDialog() }
            renameButton.setOnClickListener { showRenameDialog() }
            watermarkButton.setOnClickListener { showWatermarkDialog() }
            rotateButton.setOnClickListener { showRotateDialog() }
            compressButton.setOnClickListener { showCompressDialog() }
            ocrButton.setOnClickListener { showOcrDialog() }
            encryptButton.setOnClickListener { showEncryptDialog() }
            decryptButton.setOnClickListener { showDecryptDialog() }
            exportButton.setOnClickListener { showExportDialog() }
            deleteButton.setOnClickListener { showDeleteConfirmDialog() }
        }
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.selectedUris.collectLatest { uris ->
                    fileAdapter.submitList(uris)
                    updateSelectionUI(uris)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.jobs.collectLatest { jobs ->
                    jobAdapter.submitList(jobs)
                    binding.emptyJobsView.isVisible = jobs.isEmpty()
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isProcessing.collectLatest { isProcessing ->
                    binding.progressBar.isVisible = isProcessing
                }
            }
        }

        viewModel.operationResult.observe(this) { result ->
            when (result) {
                is BatchViewModel.OperationResult.Success -> {
                    Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                    viewModel.clearResult()
                }
                is BatchViewModel.OperationResult.Error -> {
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    viewModel.clearResult()
                }
                else -> {}
            }
        }
    }

    private fun updateSelectionUI(uris: List<Uri>) {
        binding.selectedCountText.text = getString(R.string.files_selected, uris.size)
        binding.emptySelectionView.isVisible = uris.isEmpty()
        binding.fileList.isVisible = uris.isNotEmpty()
        binding.clearSelectionButton.isVisible = uris.isNotEmpty()

        // Enable/disable operation buttons
        val hasSelection = uris.isNotEmpty()
        binding.operationGrid.children.forEach { it.isEnabled = hasSelection }
    }

    private fun checkPermissionsAndPick() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }

        if (permissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) {
            openFilePicker()
        } else {
            requestPermissionLauncher.launch(permissions)
        }
    }

    private fun openFilePicker() {
        pickFilesLauncher.launch(arrayOf("application/pdf"))
    }

    private fun handleOutputDirSelection(uri: Uri) {
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        // Store for use in operations
        pendingOutputDir = uri
        executePendingOperation()
    }

    private var pendingOutputDir: Uri? = null
    private var pendingOperation: (() -> Unit)? = null

    private fun requestOutputDirThen(operation: () -> Unit) {
        pendingOperation = operation
        pickOutputDirLauncher.launch(null)
    }

    // --- Operation Dialogs ---

    private fun showMergeDialog() {
        val uris = viewModel.selectedUris.value
        if (uris.size < 2) {
            Toast.makeText(this, R.string.merge_requires_two, Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, "merged_document.pdf")
        }

        createDocumentLauncher.launch(intent)
    }

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                viewModel.startBatchOperation(
                    type = BatchJobType.MERGE,
                    outputUri = uri
                )
            }
        }
    }

    private fun showSplitDialog() {
        if (viewModel.selectedUris.value.size != 1) {
            Toast.makeText(this, R.string.split_requires_one, Toast.LENGTH_SHORT).show()
            return
        }

        val options = arrayOf(
            getString(R.string.split_by_range),
            getString(R.string.split_every_n_pages)
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.split_options)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showSplitByRangeDialog()
                    1 -> showSplitEveryNDialog()
                }
            }
            .show()
    }

    private fun showSplitByRangeDialog() {
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = getString(R.string.range_hint)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.split_by_range)
            .setView(input)
            .setPositiveButton(R.string.start) { _, _ ->
                val ranges = input.text?.toString()?.split(",")?.map { it.trim() } ?: emptyList()
                val config = com.google.gson.JsonObject().apply {
                    add("pageRanges", com.google.gson.JsonArray().apply {
                        ranges.forEach { add(it) }
                    })
                }.toString()

                requestOutputDirThen {
                    viewModel.startBatchOperation(
                        type = BatchJobType.SPLIT,
                        configJson = config,
                        outputDirUri = pendingOutputDir
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSplitEveryNDialog() {
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = getString(R.string.pages_per_file_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.split_every_n)
            .setView(input)
            .setPositiveButton(R.string.start) { _, _ ->
                val n = input.text?.toString()?.toIntOrNull() ?: 1
                val config = com.google.gson.JsonObject().apply {
                    addProperty("splitEvery", n)
                }.toString()

                requestOutputDirThen {
                    viewModel.startBatchOperation(
                        type = BatchJobType.SPLIT,
                        configJson = config,
                        outputDirUri = pendingOutputDir
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showRenameDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_rename_config, null)
        val patternInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.patternInput)
        val prefixInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.prefixInput)
        val startIndexInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.startIndexInput)

        patternInput?.setText("{prefix}_{index}_{date}")

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_config)
            .setView(view)
            .setPositiveButton(R.string.start) { _, _ ->
                val config = com.google.gson.JsonObject().apply {
                    addProperty("pattern", patternInput?.text?.toString() ?: "{index}")
                    addProperty("prefix", prefixInput?.text?.toString() ?: "")
                    addProperty("startIndex", startIndexInput?.text?.toString()?.toIntOrNull() ?: 1)
                }.toString()

                viewModel.startBatchOperation(
                    type = BatchJobType.RENAME,
                    configJson = config
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showWatermarkDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_watermark_config, null)
        val textInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.watermarkText)
        val opacitySlider = view.findViewById<com.google.android.material.slider.Slider>(R.id.opacitySlider)
        val positionGroup = view.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.positionGroup)

        textInput?.setText("Confidential")

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.watermark_config)
            .setView(view)
            .setPositiveButton(R.string.start) { _, _ ->
                val positions = mapOf(
                    R.id.positionCenter to "CENTER",
                    R.id.positionTopLeft to "TOP_LEFT",
                    R.id.positionTopRight to "TOP_RIGHT",
                    R.id.positionBottomLeft to "BOTTOM_LEFT",
                    R.id.positionBottomRight to "BOTTOM_RIGHT"
                )

                val position = positions[positionGroup?.checkedButtonId] ?: "CENTER"

                val config = com.google.gson.JsonObject().apply {
                    addProperty("text", textInput?.text?.toString() ?: "Watermark")
                    addProperty("opacity", (opacitySlider?.value ?: 30f) / 100f)
                    addProperty("position", position)
                }.toString()

                requestOutputDirThen {
                    viewModel.startBatchOperation(
                        type = BatchJobType.WATERMARK,
                        configJson = config,
                        outputDirUri = pendingOutputDir
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showRotateDialog() {
        val rotations = arrayOf("90°", "180°", "270°")
        val rotationValues = arrayOf(90, 180, 270)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rotate_config)
            .setSingleChoiceItems(rotations, 0, null)
            .setPositiveButton(R.string.start) { dialog, _ ->
                val selected = (dialog as androidx.appcompat.app.AlertDialog).listView.checkedItemPosition
                val config = com.google.gson.JsonObject().apply {
                    addProperty("rotation", rotationValues[selected])
                }.toString()

                requestOutputDirThen {
                    viewModel.startBatchOperation(
                        type = BatchJobType.ROTATE,
                        configJson = config,
                        outputDirUri = pendingOutputDir
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showCompressDialog() {
        val qualities = arrayOf(
            getString(R.string.quality_low),
            getString(R.string.quality_medium),
            getString(R.string.quality_high)
        )
        val qualityValues = arrayOf("LOW", "MEDIUM", "HIGH")

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.compress_config)
            .setSingleChoiceItems(qualities, 1, null)
            .setPositiveButton(R.string.start) { dialog, _ ->
                val selected = (dialog as androidx.appcompat.app.AlertDialog).listView.checkedItemPosition
                val config = com.google.gson.JsonObject().apply {
                    addProperty("quality", qualityValues[selected])
                    addProperty("compressImages", true)
                }.toString()

                requestOutputDirThen {
                    viewModel.startBatchOperation(
                        type = BatchJobType.COMPRESS,
                        configJson = config,
                        outputDirUri = pendingOutputDir
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showOcrDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ocr_config)
            .setMessage(R.string.ocr_description)
            .setPositiveButton(R.string.start) { _, _ ->
                val config = com.google.gson.JsonObject().apply {
                    addProperty("language", "en")
                    addProperty("outputFormat", "PDF")
                }.toString()

                requestOutputDirThen {
                    viewModel.startBatchOperation(
                        type = BatchJobType.OCR,
                        configJson = config,
                        outputDirUri = pendingOutputDir
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showEncryptDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_encrypt_config, null)
        val passwordInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.passwordInput)
        val confirmInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.confirmPasswordInput)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.encrypt_config)
            .setView(view)
            .setPositiveButton(R.string.start) { _, _ ->
                val password = passwordInput?.text?.toString() ?: ""
                val confirm = confirmInput?.text?.toString() ?: ""

                if (password != confirm || password.isEmpty()) {
                    Toast.makeText(this, R.string.passwords_dont_match, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val config = com.google.gson.JsonObject().apply {
                    addProperty("password", password)
                    addProperty("encryptionLevel", "AES_256")
                    addProperty("allowPrinting", true)
                    addProperty("allowCopying", true)
                }.toString()

                requestOutputDirThen {
                    viewModel.startBatchOperation(
                        type = BatchJobType.ENCRYPT,
                        configJson = config,
                        outputDirUri = pendingOutputDir
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDecryptDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_decrypt_config, null)
        val passwordInput = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.passwordInput)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.decrypt_config)
            .setView(view)
            .setPositiveButton(R.string.start) { _, _ ->
                val password = passwordInput?.text?.toString() ?: ""

                val config = com.google.gson.JsonObject().apply {
                    addProperty("password", password)
                }.toString()

                requestOutputDirThen {
                    viewModel.startBatchOperation(
                        type = BatchJobType.DECRYPT,
                        configJson = config,
                        outputDirUri = pendingOutputDir
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showExportDialog() {
        val formats = arrayOf(
            getString(R.string.export_images),
            getString(R.string.export_text)
        )
        val formatValues = arrayOf("IMAGE", "TEXT")

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_config)
            .setSingleChoiceItems(formats, 0, null)
            .setPositiveButton(R.string.start) { dialog, _ ->
                val selected = (dialog as androidx.appcompat.app.AlertDialog).listView.checkedItemPosition
                val config = com.google.gson.JsonObject().apply {
                    addProperty("format", formatValues[selected])
                    addProperty("dpi", 300)
                }.toString()

                requestOutputDirThen {
                    viewModel.startBatchOperation(
                        type = BatchJobType.EXPORT,
                        configJson = config,
                        outputDirUri = pendingOutputDir
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeleteConfirmDialog() {
        val count = viewModel.selectedUris.value.size
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_delete)
            .setMessage(getString(R.string.delete_confirmation, count))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.startBatchOperation(
                    type = BatchJobType.DELETE
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeleteConfirmDialog(job: com.propdfeditor.batch.data.entity.BatchJobEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_delete_job)
            .setMessage(R.string.delete_job_confirmation)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteJob(job)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun handleIntent(intent: Intent) {
        val jobId = intent.getLongExtra("job_id", -1L)
        if (jobId != -1L) {
            // Scroll to job or show details
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_batch, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_clear_history -> {
                viewModel.deleteOldCompletedJobs()
                true
            }
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
