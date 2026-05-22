package com.propdf.editor.ui.security

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.propdf.editor.R
import com.propdf.editor.databinding.ActivitySecurityBinding
import com.propdf.security.SecurityViewModel
import com.propdf.security.di.SecurityModule
import com.propdf.security.signature.SignatureDrawView
import com.propdf.security.watermark.WatermarkOptions
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class SecurityActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySecurityBinding
    private lateinit var viewModel: SecurityViewModel

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleGalleryResult(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySecurityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Manual DI
        viewModel = SecurityViewModel(
            SecurityModule.provideSignatureManager(this),
            SecurityModule.provideEncryptionManager(this),
            SecurityModule.provideWatermarkEngine(this),
            SecurityModule.provideRedactionEngine(this)
        )

        setupUI()
        observeViewModel()
    }

    private fun setupUI() {
        // Signature tab
        binding.btnClearSignature.setOnClickListener { binding.signatureDrawView.clear() }
        binding.btnSaveSignature.setOnClickListener {
            if (!binding.signatureDrawView.isEmpty()) {
                val bitmap = binding.signatureDrawView.getSignatureBitmap()
                viewModel.saveDrawnSignature(bitmap, binding.signatureNameInput.text.toString().ifEmpty { null })
            } else {
                toast("Please draw a signature first")
            }
        }
        binding.btnImportSignature.setOnClickListener { galleryLauncher.launch("image/*") }

        // Encryption tab
        binding.btnPasswordProtect.setOnClickListener {
            val source = getCurrentPdfUri() ?: return@setOnClickListener
            val output = File(filesDir, "protected_${System.currentTimeMillis()}.pdf")
            viewModel.passwordProtectPdf(source, output, binding.passwordInput.text.toString())
        }

        // Watermark tab
        binding.btnAddWatermark.setOnClickListener {
            val source = getCurrentPdfUri() ?: return@setOnClickListener
            val output = File(filesDir, "watermarked_${System.currentTimeMillis()}.pdf")
            viewModel.addTextWatermark(source, output, binding.watermarkTextInput.text.toString())
        }

        // Redaction tab
        binding.btnRedact.setOnClickListener {
            val source = getCurrentPdfUri() ?: return@setOnClickListener
            val output = File(filesDir, "redacted_${System.currentTimeMillis()}.pdf")
            // TODO: Get redaction regions from user selection
            toast("Redaction: select regions first")
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isProcessing.collectLatest { processing ->
                        binding.progressBar.visibility = if (processing) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.signatures.collectLatest { signatures ->
                        // Update signature list adapter
                        binding.signatureCount.text = "${signatures.size} signatures"
                    }
                }
                launch {
                    viewModel.uiState.collectLatest { state ->
                        state.error?.let { toast(it) }
                        state.lastOutputFile?.let { file ->
                            toast("Saved: ${file.name}")
                        }
                    }
                }
            }
        }
    }

    private fun handleGalleryResult(uri: Uri) {
        viewModel.saveImageSignature(uri, binding.signatureNameInput.text.toString().ifEmpty { null })
    }

    private fun getCurrentPdfUri(): Uri? {
        // TODO: Get from intent or ViewModel
        return intent.getParcelableExtra("pdf_uri")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        SecurityModule.release()
    }
}
