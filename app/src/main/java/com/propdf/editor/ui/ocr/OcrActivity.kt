package com.propdf.editor.ui.ocr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.propdf.core.domain.model.*
import com.propdf.editor.R
import com.propdf.editor.databinding.ActivityOcrBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

@AndroidEntryPoint
class OcrActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOcrBinding
    private val viewModel: OcrViewModel by viewModels()

    private lateinit var languageAdapter: OcrLanguageAdapter
    private lateinit var resultAdapter: OcrResultAdapter

    private var currentBitmap: Bitmap? = null
    private var croppedBitmap: Bitmap? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.recognizeImageUri(it) }
    }

    private val pickMultipleImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) viewModel.recognizeBatch(uris)
    }

    private val cropImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.getParcelableExtra<Uri>("cropped_uri")
            uri?.let { viewModel.recognizeImageUri(it) }
        }
    }

    private val saveDocument = registerForActivityResult(ActivityResultContracts.CreateDocument()) { uri ->
        uri?.let { outputUri ->
            val format = when (binding.exportFormatGroup.checkedRadioButtonId) {
                R.id.radioPdf -> OcrOutputFormat.PDF
                R.id.radioTxt -> OcrOutputFormat.TXT
                R.id.radioDocx -> OcrOutputFormat.DOCX
                else -> OcrOutputFormat.TXT
            }
            viewModel.exportResults(outputUri, format)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOcrBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupToolbar()
        setupLanguageSelector()
        setupPreprocessOptions()
        setupActionButtons()
        setupResultView()
        setupSearch()
        setupExport()
        observeViewModel()
        handleIntent(intent)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.ocr_title)
    }

    private fun setupLanguageSelector() {
        languageAdapter = OcrLanguageAdapter { language, isSelected ->
            val current = viewModel.selectedLanguages.value.toMutableList()
            if (isSelected) { if (!current.contains(language)) current.add(language) }
            else { current.remove(language) }
            if (current.isEmpty()) current.add(OcrLanguage.AUTO)
            viewModel.setLanguages(current)
        }
        binding.languageRecycler.apply {
            layoutManager = LinearLayoutManager(this@OcrActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = languageAdapter
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.availableLanguages.collectLatest { languages ->
                    languageAdapter.submitList(languages)
                }
            }
        }
    }

    private fun setupPreprocessOptions() {
        binding.deskewSwitch.setOnCheckedChangeListener { _, _ -> updatePreprocessConfig() }
        binding.denoiseSwitch.setOnCheckedChangeListener { _, _ -> updatePreprocessConfig() }
        binding.perspectiveSwitch.setOnCheckedChangeListener { _, _ -> updatePreprocessConfig() }
        binding.contrastSwitch.setOnCheckedChangeListener { _, _ -> updatePreprocessConfig() }
    }

    private fun updatePreprocessConfig() {
        viewModel.setPreprocessConfig(OcrPreprocessConfig(
            enableDeskew = binding.deskewSwitch.isChecked,
            enableDenoise = binding.denoiseSwitch.isChecked,
            enablePerspectiveCorrection = binding.perspectiveSwitch.isChecked,
            enableContrastEnhance = binding.contrastSwitch.isChecked
        ))
    }

    private fun setupActionButtons() {
        binding.btnPickImage.setOnClickListener { pickImage.launch("image/*") }
        binding.btnPickMultiple.setOnClickListener { pickMultipleImages.launch("image/*") }
        binding.btnCamera.setOnClickListener {
            val intent = Intent(this, Class.forName("com.propdf.editor.ui.scanner.ScannerActivity"))
            startActivityForResult(intent, REQUEST_CAMERA)
        }
        binding.btnPreprocess.setOnClickListener { currentBitmap?.let { viewModel.preprocessImage(it) } }
        binding.btnCorrect.setOnClickListener { viewModel.correctText() }
        binding.btnCopy.setOnClickListener { copyToClipboard(viewModel.getCurrentText()) }
        binding.btnCopyAll.setOnClickListener { copyToClipboard(viewModel.getAllText()) }
    }

    private fun setupResultView() {
        resultAdapter = OcrResultAdapter { block -> highlightBlock(block) }
        binding.resultRecycler.apply {
            layoutManager = LinearLayoutManager(this@OcrActivity)
            adapter = resultAdapter
        }
    }

    private fun setupSearch() {
        binding.searchInput.setOnEditorActionListener { _, _, _ ->
            viewModel.searchText(binding.searchInput.text?.toString() ?: "", binding.caseSensitiveSwitch.isChecked)
            true
        }
        binding.btnSearch.setOnClickListener {
            viewModel.searchText(binding.searchInput.text?.toString() ?: "", binding.caseSensitiveSwitch.isChecked)
        }
        binding.btnClearSearch.setOnClickListener {
            binding.searchInput.text?.clear()
            viewModel.clearSearch()
        }
    }

    private fun setupExport() {
        binding.btnExport.setOnClickListener {
            val ext = when (binding.exportFormatGroup.checkedRadioButtonId) {
                R.id.radioPdf -> "pdf"; R.id.radioTxt -> "txt"; R.id.radioDocx -> "docx"; else -> "txt"
            }
            saveDocument.launch("ocr_export_${System.currentTimeMillis()}.$ext")
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
                    when (state) {
                        is OcrUiState.Idle -> showIdleState()
                        is OcrUiState.Processing -> showProcessing(state.message)
                        is OcrUiState.Success -> showResult(state.result)
                        is OcrUiState.BatchComplete -> showBatchComplete(state.pageCount)
                        is OcrUiState.Preprocessed -> showPreprocessed(state.bitmap)
                        is OcrUiState.Exported -> showExported(state.uri)
                        is OcrUiState.ModelDownloaded -> showModelDownloaded(state.language)
                        is OcrUiState.Error -> showError(state.message)
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ocrResults.collectLatest { results ->
                    updateNavigation(results.size)
                    if (results.isNotEmpty()) displayResult(results[viewModel.currentPageIndex.value])
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.currentPageIndex.collectLatest { index ->
                    binding.pageIndicator.text = getString(R.string.page_indicator, index + 1, viewModel.ocrResults.value.size)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.progress.collectLatest { binding.progressBar.progress = it; binding.progressText.text = "$it%" }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isHandwritingDetected.collectLatest { binding.handwritingIndicator.isVisible = it }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.detectedTables.collectLatest { binding.tableIndicator.isVisible = it.isNotEmpty() }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.searchResults.collectLatest { highlightSearchResults(it) }
            }
        }
    }

    private fun showIdleState() {
        binding.progressContainer.isVisible = false
        binding.resultContainer.isVisible = false
        binding.emptyState.isVisible = true
        binding.preprocessCard.isVisible = true
    }

    private fun showProcessing(message: String) {
        binding.progressContainer.isVisible = true
        binding.progressMessage.text = message
        binding.emptyState.isVisible = false
        binding.resultContainer.isVisible = false
    }

    private fun showResult(result: OcrPageResult) {
        binding.progressContainer.isVisible = false
        binding.emptyState.isVisible = false
        binding.resultContainer.isVisible = true
        displayResult(result)
    }

    private fun displayResult(result: OcrPageResult) {
        binding.ocrTextView.text = result.fullText
        resultAdapter.submitList(result.blocks)
        binding.detectedLanguages.text = result.detectedLanguages.joinToString(", ")
        val avgConfidence = if (result.blocks.isNotEmpty()) result.blocks.map { it.confidence }.average().toFloat() else 0f
        binding.confidenceBar.progress = (avgConfidence * 100).toInt()
        binding.confidenceText.text = getString(R.string.confidence_format, avgConfidence * 100)
    }

    private fun showBatchComplete(pageCount: Int) {
        binding.progressContainer.isVisible = false
        binding.emptyState.isVisible = false
        binding.resultContainer.isVisible = true
        Toast.makeText(this, getString(R.string.batch_complete, pageCount), Toast.LENGTH_LONG).show()
    }

    private fun showPreprocessed(bitmap: Bitmap) {
        croppedBitmap = bitmap
        binding.previewImage.setImageBitmap(bitmap)
        Toast.makeText(this, R.string.preprocess_complete, Toast.LENGTH_SHORT).show()
    }

    private fun showExported(uri: Uri) {
        Toast.makeText(this, getString(R.string.exported_to, uri.toString()), Toast.LENGTH_LONG).show()
    }

    private fun showModelDownloaded(language: OcrLanguage) {
        Toast.makeText(this, getString(R.string.model_downloaded, language.displayName), Toast.LENGTH_SHORT).show()
    }

    private fun showError(message: String) {
        binding.progressContainer.isVisible = false
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.error_title).setMessage(message)
            .setPositiveButton(R.string.ok, null).show()
    }

    private fun updateNavigation(totalPages: Int) {
        binding.btnPrevious.isEnabled = viewModel.currentPageIndex.value > 0
        binding.btnNext.isEnabled = viewModel.currentPageIndex.value < totalPages - 1
        binding.pageIndicator.isVisible = totalPages > 1
    }

    private fun highlightBlock(block: OcrTextBlock) {
        val overlay = Bitmap.createBitmap(binding.previewImage.width, binding.previewImage.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(overlay)
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.RED; style = android.graphics.Paint.Style.STROKE; strokeWidth = 3f }
        canvas.drawRect(block.boundingBox, paint)
        binding.previewImage.setImageBitmap(overlay)
    }

    private fun highlightSearchResults(ranges: List<IntRange>) {
        val text = binding.ocrTextView.text?.toString() ?: return
        val spannable = android.text.SpannableString(text)
        ranges.forEach { range ->
            spannable.setSpan(
                android.text.style.BackgroundColorSpan(android.graphics.Color.YELLOW),
                range.first.coerceIn(0, text.length), range.last.coerceIn(0, text.length),
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        binding.ocrTextView.text = spannable
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("OCR Text", text))
        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { viewModel.recognizeImageUri(it) }
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { viewModel.recognizeBatch(it) }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_ocr, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            R.id.action_crop -> { currentBitmap?.let { launchCrop(it) }; true }
            R.id.action_reset -> { viewModel.reset(); true }
            R.id.action_settings -> { showSettingsDialog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun launchCrop(bitmap: Bitmap) {
        val file = File(cacheDir, "crop_temp.jpg")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out) }
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        cropImage.launch(Intent(this, OcrCropActivity::class.java).putExtra("image_uri", uri))
    }

    private fun showSettingsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ocr_settings)
            .setItems(R.array.ocr_settings_options) { _, which ->
                when (which) { 0 -> showLanguageDownloadDialog(); 1 -> showConfidenceThresholdDialog() }
            }.show()
    }

    private fun showLanguageDownloadDialog() {
        val languages = OcrLanguage.values().filter { it != OcrLanguage.AUTO }
        val items = languages.map { it.displayName }.toTypedArray()
        val checked = BooleanArray(languages.size) { viewModel.selectedLanguages.value.contains(languages[it]) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.download_languages)
            .setMultiChoiceItems(items, checked) { _, which, isChecked -> if (isChecked) viewModel.downloadModel(languages[which]) }
            .setPositiveButton(R.string.ok, null).show()
    }

    private fun showConfidenceThresholdDialog() {}

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CAMERA && resultCode == RESULT_OK) {
            data?.getParcelableExtra<Uri>("scanned_image_uri")?.let { viewModel.recognizeImageUri(it) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentBitmap?.recycle()
        croppedBitmap?.recycle()
    }

    companion object {
        private const val REQUEST_CAMERA = 1001
        fun newIntent(context: Context, uri: Uri? = null): Intent {
            return Intent(context, OcrActivity::class.java).apply { uri?.let { putExtra("image_uri", it) } }
        }
    }
}
