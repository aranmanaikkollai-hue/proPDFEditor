package com.propdfeditor.ui.signature

import android.content.Intent
import android.os.Bundle
import android.security.KeyChain
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.propdf.editor.R
import com.propdf.editor.databinding.ActivityCertificateManagerBinding
import com.propdfeditor.core.database.entity.CertificateEntity
import com.propdfeditor.security.SignatureViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

@AndroidEntryPoint
class CertificateManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCertificateManagerBinding
    private val viewModel: SignatureViewModel by viewModels()
    private lateinit var adapter: CertificateListAdapter

    private val p12Picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { importP12Certificate(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCertificateManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupFab()
        observeViewModel()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.manage_certificates)
    }

    private fun setupRecyclerView() {
        adapter = CertificateListAdapter(
            onCertificateClick = { certificate ->
                showCertificateDetails(certificate)
            },
            onCertificateLongClick = { certificate ->
                showCertificateOptions(certificate)
            },
            onSetDefaultClick = { certificate ->
                viewModel.setDefaultCertificate(certificate.id)
            }
        )

        binding.certificateRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@CertificateManagerActivity)
            adapter = this@CertificateManagerActivity.adapter
        }
    }

    private fun setupFab() {
        binding.fabAddCertificate.setOnClickListener {
            showAddCertificateOptions()
        }
    }

    private fun showAddCertificateOptions() {
        val options = arrayOf(
            getString(R.string.import_p12_file),
            getString(R.string.import_from_system)
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_certificate)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> p12Picker.launch("application/x-pkcs12")
                    1 -> importFromSystem()
                }
            }
            .show()
    }

    private fun importP12Certificate(uri: android.net.Uri) {
        val passwordInput = android.widget.EditText(this).apply {
            hint = getString(R.string.keystore_password)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val aliasInput = android.widget.EditText(this).apply {
            hint = getString(R.string.certificate_alias)
        }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
            addView(aliasInput)
            addView(passwordInput)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_certificate)
            .setView(layout)
            .setPositiveButton(R.string.import_btn) { _, _ ->
                val alias = aliasInput.text.toString().trim()
                val password = passwordInput.text.toString()

                if (alias.isNotEmpty() && password.isNotEmpty()) {
                    lifecycleScope.launch {
                        runCatching {
                            val bytes = contentResolver.openInputStream(uri)?.use { stream ->
                                ByteArrayOutputStream().apply {
                                    stream.copyTo(this)
                                }.toByteArray()
                            } ?: throw IllegalArgumentException("Cannot read file")

                            viewModel.importCertificate(alias, alias, bytes, password)
                        }.onFailure {
                            Snackbar.make(binding.root, it.message ?: "Import failed", Snackbar.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun importFromSystem() {
        KeyChain.choosePrivateKeyAlias(this, { alias ->
            alias?.let {
                lifecycleScope.launch {
                    // Handle system certificate import
                }
            }
        }, null, null, null, -1, null)
    }

    private fun showCertificateDetails(certificate: CertificateEntity) {
        val validStatus = if (viewModel.isCertificateValid(certificate)) {
            getString(R.string.valid)
        } else {
            getString(R.string.expired)
        }

        val daysUntil = viewModel.getDaysUntilExpiry(certificate)

        val message = buildString {
            appendLine("Subject: ${certificate.subjectDn ?: "Unknown"}")
            appendLine("Issuer: ${certificate.issuerDn ?: "Unknown"}")
            appendLine("Serial: ${certificate.serialNumber ?: "Unknown"}")
            appendLine("Valid From: ${certificate.validFrom}")
            appendLine("Valid Until: ${certificate.validUntil}")
            appendLine("Algorithm: ${certificate.algorithm ?: "Unknown"}")
            appendLine("Status: $validStatus")
            daysUntil?.let { appendLine("Days until expiry: $it") }
            appendLine("Self-signed: ${certificate.isSelfSigned}")
            appendLine("Uses: ${certificate.useCount}")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(certificate.displayName)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showCertificateOptions(certificate: CertificateEntity) {
        val options = arrayOf(
            getString(R.string.set_as_default),
            getString(R.string.delete_certificate)
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(certificate.displayName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> viewModel.setDefaultCertificate(certificate.id)
                    1 -> confirmDeleteCertificate(certificate)
                }
            }
            .show()
    }

    private fun confirmDeleteCertificate(certificate: CertificateEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_certificate)
            .setMessage(getString(R.string.delete_certificate_confirm, certificate.displayName))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteCertificate(certificate)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.certificates.collect { certificates ->
                    adapter.submitList(certificates)
                    binding.emptyState.visibility = if (certificates.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiEvent.collect { event ->
                    when (event) {
                        is SignatureViewModel.UiEvent.CertificateImported -> {
                            Snackbar.make(binding.root, R.string.certificate_imported, Snackbar.LENGTH_SHORT).show()
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
}
