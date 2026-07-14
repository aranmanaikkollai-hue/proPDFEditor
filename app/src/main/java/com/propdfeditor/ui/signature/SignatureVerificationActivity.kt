package com.propdfeditor.ui.signature

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
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.propdf.editor.R
import com.propdf.editor.databinding.ActivitySignatureVerificationBinding
import com.propdfeditor.security.SignatureViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SignatureVerificationActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignatureVerificationBinding
    private val viewModel: SignatureViewModel by viewModels()
    private lateinit var adapter: VerificationResultAdapter

    private val documentPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { verifyDocument(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignatureVerificationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupButtons()
        observeViewModel()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.verify_signatures)
    }

    private fun setupRecyclerView() {
        adapter = VerificationResultAdapter()
        binding.verificationRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@SignatureVerificationActivity)
            adapter = this@SignatureVerificationActivity.adapter
        }
    }

    private fun setupButtons() {
        binding.selectDocumentButton.setOnClickListener {
            documentPicker.launch("application/pdf")
        }
    }

    private fun verifyDocument(uri: Uri) {
        binding.selectedDocumentName.text = uri.lastPathSegment ?: getString(R.string.unknown_document)
        binding.verificationProgress.visibility = View.VISIBLE
        binding.verificationStatus.visibility = View.GONE
        adapter.submitList(emptyList())
        viewModel.verifyDocumentSignatures(uri)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiEvent.collect { event ->
                    when (event) {
                        is SignatureViewModel.UiEvent.VerificationComplete -> {
                            binding.verificationProgress.visibility = View.GONE
                            adapter.submitList(event.results)

                            val allValid = event.results.all { it.isValid }
                            binding.verificationStatus.apply {
                                visibility = View.VISIBLE
                                text = if (event.results.isEmpty()) {
                                    getString(R.string.no_signatures_found)
                                } else if (allValid) {
                                    getString(R.string.all_signatures_valid)
                                } else {
                                    getString(R.string.some_signatures_invalid)
                                }
                                setTextColor(
                                    if (allValid && event.results.isNotEmpty())
                                        getColor(android.R.color.holo_green_dark)
                                    else
                                        getColor(android.R.color.holo_red_dark)
                                )
                            }
                        }
                        is SignatureViewModel.UiEvent.Error -> {
                            binding.verificationProgress.visibility = View.GONE
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
