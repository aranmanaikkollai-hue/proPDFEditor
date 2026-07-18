// security/src/main/java/com/propdf/security/ui/activity/SecurityActivity.kt
package com.propdf.security.ui.activity

import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import com.propdf.security.R
import com.propdf.security.databinding.ActivitySecurityBinding
import com.propdf.security.ui.adapter.SecurityPagerAdapter
import com.propdf.security.ui.viewmodel.SecurityViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SecurityActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySecurityBinding
    private val viewModel: SecurityViewModel by viewModels()
    private var documentUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySecurityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        documentUri = intent.getParcelableExtra("document_uri")

        setupToolbar()
        setupViewPager()
        setupObservers()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.security_title)
    }

    private fun setupViewPager() {
        val adapter = SecurityPagerAdapter(this, documentUri)
        binding.viewPager.adapter = adapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_encryption)
                1 -> getString(R.string.tab_permissions)
                2 -> getString(R.string.tab_redaction)
                3 -> getString(R.string.tab_sanitization)
                else -> ""
            }
        }.attach()
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isProcessing.collect { isProcessing ->
                    binding.progressOverlay.visibility = if (isProcessing) 
                        android.view.View.VISIBLE else android.view.View.GONE
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.error.collect { error ->
                    error?.let {
                        Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
                        viewModel.clearError()
                    }
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_security, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_history -> {
                showHistoryDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showHistoryDialog() {
        // Implementation for showing security operation history
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up sensitive data
        documentUri = null
    }
}
