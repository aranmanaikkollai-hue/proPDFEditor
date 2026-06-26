package com.propdf.editor.ui.security

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.propdf.editor.databinding.ActivitySecurityBinding
import com.propdf.security.SecurityViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

@AndroidEntryPoint
class SecurityActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySecurityBinding
    private val viewModel: SecurityViewModel by viewModels()

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleGalleryResult(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySecurityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        observeViewModel()
    }

    private fun setupUI() {
        // Signature tab
        binding.btnClearSignature.setOnClickListener { binding.signatureDrawView.clear() }
        binding.btnSaveSignature.setOnClickListener {
            if (!binding.signatureDrawView.isEmpty()) {
                val bitmap = binding.signatureDrawView.getSignatureBitmap()
                val name = binding.signatureNameInput.text.toString().ifEmpty { null }
                viewModel.saveDrawnSignature(bitmap, name)
            } else {
                toast("Please draw a signature first")
            }
        }
        binding.btnImportSignature.setOnClickListener { galleryLauncher.launch("image/*") }

        // Encryption tab
        binding.btnPasswordProtect.setOnClickListener {
            val source = getCurrentPdfFile() ?: return@setOnClickListener
            val output = File(filesDir, "protected_${System.currentTimeMillis()}.pdf")
            viewModel.passwordProtectPdf(source, output, binding.passwordInput.text.toString())
        }

        // Watermark tab
        binding.btnAddWatermark.setOnClickListener {
            val source = getCurrentPdfFile() ?: return@setOnClickListener
            val output = File(filesDir, "watermarked_${System.currentTimeMillis()}.pdf")
            viewModel.addTextWatermark(source, output, binding.watermarkTextInput.text.toString())
        }

        // Redaction tab
        binding.btnRedact.setOnClickListener {
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
        val name = binding.signatureNameInput.text.toString().ifEmpty { null }
        viewModel.saveImageSignature(uri, name)
    }

    private fun getCurrentPdfFile(): File? {
        val uri: Uri? = intent.getParcelableExtra("pdf_uri")
        val path = uri?.path ?: return null
        val file = File(path)
        return if (file.exists()) file else null
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
