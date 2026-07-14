package com.propdfeditor.ui.signature

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import com.propdf.editor.R
import com.propdf.editor.databinding.ActivitySignatureManagerBinding
import com.propdfeditor.core.database.entity.SignatureEntity
import com.propdfeditor.security.SignatureViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SignatureManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignatureManagerBinding
    private val viewModel: SignatureViewModel by viewModels()
    private lateinit var adapter: SignatureListAdapter

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { showImportDialog(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignatureManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupViewPager()
        setupRecyclerView()
        setupFab()
        observeViewModel()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.manage_signatures)
    }

    private fun setupViewPager() {
        val pagerAdapter = SignaturePagerAdapter(this)
        binding.viewPager.adapter = pagerAdapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.all_signatures)
                1 -> getString(R.string.favorites)
                2 -> getString(R.string.history)
                else -> ""
            }
        }.attach()
    }

    private fun setupRecyclerView() {
        adapter = SignatureListAdapter(
            onSignatureClick = { signature ->
                showSignatureOptions(signature)
            },
            onSignatureLongClick = { signature ->
                showSignatureOptions(signature)
            },
            onFavoriteClick = { signature ->
                viewModel.toggleFavorite(signature)
            }
        )

        binding.signatureRecyclerView.apply {
            layoutManager = GridLayoutManager(this@SignatureManagerActivity, calculateSpanCount())
            adapter = this@SignatureManagerActivity.adapter
        }
    }

    private fun calculateSpanCount(): Int {
        val displayMetrics = resources.displayMetrics
        val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
        return (screenWidthDp / 180).toInt().coerceAtLeast(2)
    }

    private fun setupFab() {
        binding.fabAddSignature.setOnClickListener {
            showAddSignatureOptions()
        }
    }

    private fun showAddSignatureOptions() {
        val options = arrayOf(
            getString(R.string.draw_signature),
            getString(R.string.type_signature),
            getString(R.string.import_image_signature)
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.create_signature)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, DrawSignatureActivity::class.java))
                    1 -> startActivity(Intent(this, TypeSignatureActivity::class.java))
                    2 -> imagePicker.launch("image/*")
                }
            }
            .show()
    }

    private fun showImportDialog(uri: android.net.Uri) {
        val editText = android.widget.EditText(this).apply {
            hint = getString(R.string.signature_name_hint)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_signature)
            .setView(editText.apply {
                setPadding(48, 24, 48, 24)
            })
            .setPositiveButton(R.string.import_btn) { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewModel.createImageSignature(name, uri)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSignatureOptions(signature: SignatureEntity) {
        val options = arrayOf(
            getString(R.string.use_signature),
            getString(R.string.share_signature),
            getString(R.string.delete_signature)
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(signature.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_SELECTED_SIGNATURE_ID, signature.id))
                        finish()
                    }
                    1 -> shareSignature(signature)
                    2 -> confirmDelete(signature)
                }
            }
            .show()
    }

    private fun shareSignature(signature: SignatureEntity) {
        signature.bitmapPath?.let { path ->
            val file = java.io.File(path)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_signature)))
        }
    }

    private fun confirmDelete(signature: SignatureEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_signature)
            .setMessage(getString(R.string.delete_signature_confirm, signature.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteSignature(signature)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.signatures.collect { signatures ->
                    adapter.submitList(signatures)
                    binding.emptyState.visibility = if (signatures.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiEvent.collect { event ->
                    when (event) {
                        is SignatureViewModel.UiEvent.SignatureDeleted -> {
                            Snackbar.make(binding.root, R.string.signature_deleted, Snackbar.LENGTH_SHORT).show()
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

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_signature_manager, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_import_certificate -> {
                startActivity(Intent(this, CertificateManagerActivity::class.java))
                true
            }
            R.id.action_verify_document -> {
                startActivity(Intent(this, SignatureVerificationActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        const val EXTRA_SELECTED_SIGNATURE_ID = "selected_signature_id"

        fun createIntent(context: Context): Intent {
            return Intent(context, SignatureManagerActivity::class.java)
        }
    }
}
