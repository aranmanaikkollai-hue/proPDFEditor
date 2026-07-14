package com.propdfeditor.ui.signature

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.propdf.editor.R
import com.propdf.editor.databinding.ActivityApplySignatureBinding
import com.propdfeditor.core.database.entity.CertificateEntity
import com.propdfeditor.core.database.entity.SignatureEntity
import com.propdfeditor.core.util.FileUtils
import com.propdfeditor.security.SignatureViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File

@AndroidEntryPoint
class ApplySignatureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityApplySignatureBinding
    private val viewModel: SignatureViewModel by viewModels()

    private var selectedSignature: SignatureEntity? = null
    private var selectedCertificate: CertificateEntity? = null
    private var documentUri: Uri? = null
    private var currentPage = 1
    private var totalPages = 1

    private val signaturePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getLongExtra(SignatureManagerActivity.EXTRA_SELECTED_SIGNATURE_ID, -1)?.let { id ->
                lifecycleScope.launch {
                    viewModel.signatures.value.find { it.id == id }?.let {
                        selectSignature(it)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityApplySignatureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        documentUri = intent.getParcelableExtra(EXTRA_DOCUMENT_URI)
        if (documentUri == null) {
            finish()
            return
        }

        setupToolbar()
        setupPdfViewer()
        setupSignatureOverlay()
        setupButtons()
        observeViewModel()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.apply_signature)
    }

    private fun setupPdfViewer() {
        // Initialize PDF viewer with document
        documentUri?.let { uri ->
            // Load PDF and display current page
            // This would integrate with the existing PDF viewer module
            binding.pageIndicator.text = getString(R.string.page_of, currentPage, totalPages)

            binding.previousPage.setOnClickListener {
                if (currentPage > 1) {
                    currentPage--
                    updatePageDisplay()
                }
            }

            binding.nextPage.setOnClickListener {
                if (currentPage < totalPages) {
                    currentPage++
                    updatePageDisplay()
                }
            }
        }
    }

    private fun updatePageDisplay() {
        binding.pageIndicator.text = getString(R.string.page_of, currentPage, totalPages)
        // Update PDF viewer to show current page
    }

    private fun setupSignatureOverlay() {
        binding.signatureOverlay.onSignaturePositionChanged = { rect ->
            // Update position indicator
        }

        binding.signatureOverlay.onSignaturePlaced = { rect ->
            // Signature placed on document
        }
    }

    private fun setupButtons() {
        binding.selectSignatureButton.setOnClickListener {
            signaturePicker.launch(SignatureManagerActivity.createIntent(this))
        }

        binding.applyVisualSignatureButton.setOnClickListener {
            applyVisualSignature()
        }

        binding.applyDigitalSignatureButton.setOnClickListener {
            showDigitalSignatureDialog()
        }
    }

    private fun selectSignature(signature: SignatureEntity) {
        selectedSignature = signature
        binding.selectedSignatureName.text = signature.name

        // Load and display signature bitmap on overlay
        lifecycleScope.launch {
            val bitmap = viewModel.getSignatureBitmap(signature)
            bitmap?.let {
                binding.signatureOverlay.setSignatureBitmap(it)
                binding.signatureOverlay.visibility = View.VISIBLE
            }
        }
    }

    private fun applyVisualSignature() {
        val signature = selectedSignature ?: run {
            Snackbar.make(binding.root, R.string.select_signature_first, Snackbar.LENGTH_SHORT).show()
            return
        }

        val rect = binding.signatureOverlay.getSignatureRect()
        if (rect.isEmpty) {
            Snackbar.make(binding.root, R.string.position_signature_first, Snackbar.LENGTH_SHORT).show()
            return
        }

        val documentName = FileUtils.getFileNameFromUri(this, documentUri!!) ?: "document.pdf"
        val outputDir = File(cacheDir, "signed_documents").apply { mkdirs() }
        val outputFile = FileUtils.getUniqueFileName(outputDir, "signed_$documentName", "pdf")

        binding.applyProgress.visibility = View.VISIBLE

        viewModel.applyVisualSignature(
            documentUri = documentUri!!,
            outputFile = outputFile,
            signatureId = signature.id,
            pageNumber = currentPage,
            rect = rect,
            documentName = documentName
        )
    }

    private fun showDigitalSignatureDialog() {
        val signature = selectedSignature ?: run {
            Snackbar.make(binding.root, R.string.select_signature_first, Snackbar.LENGTH_SHORT).show()
            return
        }

        val certificates = viewModel.certificates.value
        if (certificates.isEmpty()) {
            Snackbar.make(binding.root, R.string.no_certificates_available, Snackbar.LENGTH_LONG)
                .setAction(R.string.import_certificate) {
                    startActivity(Intent(this, CertificateManagerActivity::class.java))
                }
                .show()
            return
        }

        val certificateNames = certificates.map { it.displayName }.toTypedArray()
        var selectedIndex = 0

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_certificate)
            .setSingleChoiceItems(certificateNames, 0) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton(R.string.continue_btn) { _, _ ->
                selectedCertificate = certificates[selectedIndex]
                showPasswordDialogAndApply()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showPasswordDialogAndApply() {
        val passwordInput = android.widget.EditText(this).apply {
            hint = getString(R.string.keystore_password)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.enter_password)
            .setView(passwordInput.apply {
                setPadding(48, 24, 48, 24)
            })
            .setPositiveButton(R.string.sign) { _, _ ->
                applyDigitalSignature(passwordInput.text.toString())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyDigitalSignature(password: String) {
        val signature = selectedSignature ?: return
        val certificate = selectedCertificate ?: return
        val rect = binding.signatureOverlay.getSignatureRect()

        if (rect.isEmpty) {
            Snackbar.make(binding.root, R.string.position_signature_first, Snackbar.LENGTH_SHORT).show()
            return
        }

        val documentName = FileUtils.getFileNameFromUri(this, documentUri!!) ?: "document.pdf"
        val outputDir = File(cacheDir, "signed_documents").apply { mkdirs() }
        val outputFile = FileUtils.getUniqueFileName(outputDir, "signed_$documentName", "pdf")

        binding.applyProgress.visibility = View.VISIBLE

        viewModel.applyDigitalSignature(
            documentUri = documentUri!!,
            outputFile = outputFile,
            signatureId = signature.id,
            certificateId = certificate.id,
            pageNumber = currentPage,
            rect = rect,
            documentName = documentName,
            keystorePassword = password,
            keyPassword = password
        )
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isLoading.collect { isLoading ->
                    binding.applyProgress.visibility = if (isLoading) View.VISIBLE else View.GONE
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiEvent.collect { event ->
                    when (event) {
                        is SignatureViewModel.UiEvent.SignatureApplied -> {
                            Snackbar.make(
                                binding.root,
                                getString(R.string.signature_applied_success, event.outputPath),
                                Snackbar.LENGTH_LONG
                            ).setAction(R.string.open) {
                                // Open signed document
                            }.show()
                            binding.signatureOverlay.clearSignature()
                        }
                        is SignatureViewModel.UiEvent.Error -> {
                            Snackbar.make(binding.root, event.message, Snackbar.LENGTH_LONG).show()
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        const val EXTRA_DOCUMENT_URI = "document_uri"

        fun createIntent(context: Context, documentUri: Uri): Intent {
            return Intent(context, ApplySignatureActivity::class.java).apply {
                putExtra(EXTRA_DOCUMENT_URI, documentUri)
            }
        }
    }
}
