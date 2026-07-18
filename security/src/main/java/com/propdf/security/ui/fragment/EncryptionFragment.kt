// security/src/main/java/com/propdf/security/ui/fragment/EncryptionFragment.kt
package com.propdf.security.ui.fragment

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.propdf.security.R
import com.propdf.security.data.entity.EncryptionType
import com.propdf.security.databinding.FragmentEncryptionBinding
import com.propdf.security.ui.viewmodel.SecurityViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class EncryptionFragment : Fragment() {

    private var _binding: FragmentEncryptionBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SecurityViewModel by activityViewModels()
    private var documentUri: Uri? = null

    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let { applyEncryption(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEncryptionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        documentUri = arguments?.getParcelable("document_uri")

        setupEncryptionOptions()
        setupListeners()
        setupObservers()
    }

    private fun setupEncryptionOptions() {
        binding.encryptionTypeGroup.check(R.id.radioAes256)
    }

    private fun setupListeners() {
        binding.btnEncrypt.setOnClickListener {
            validateAndEncrypt()
        }

        binding.btnDecrypt.setOnClickListener {
            validateAndDecrypt()
        }
    }

    private fun validateAndEncrypt() {
        val userPassword = binding.etUserPassword.text?.toString()
        val ownerPassword = binding.etOwnerPassword.text?.toString()
        val confirmPassword = binding.etConfirmPassword.text?.toString()

        if (userPassword.isNullOrBlank() && ownerPassword.isNullOrBlank()) {
            binding.tilUserPassword.error = getString(R.string.error_password_required)
            return
        }

        if (!userPassword.isNullOrBlank() && userPassword != confirmPassword) {
            binding.tilConfirmPassword.error = getString(R.string.error_passwords_dont_match)
            return
        }

        binding.tilUserPassword.error = null
        binding.tilConfirmPassword.error = null

        documentUri?.let { uri ->
            createDocument.launch("encrypted_document.pdf")
        } ?: run {
            Snackbar.make(binding.root, R.string.error_no_document, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun applyEncryption(outputUri: Uri) {
        val userPassword = binding.etUserPassword.text?.toString()
        val ownerPassword = binding.etOwnerPassword.text?.toString()
        
        val permissions = calculatePermissions()
        val encryptionType = when (binding.encryptionTypeGroup.checkedRadioButtonId) {
            R.id.radioAes256 -> EncryptionType.AES_256
            R.id.radioAes128 -> EncryptionType.AES_128
            else -> EncryptionType.STANDARD_128
        }

        documentUri?.let { uri ->
            viewModel.applyPasswordProtection(
                uri,
                userPassword,
                ownerPassword,
                permissions,
                encryptionType,
                outputUri
            )
        }
    }

    private fun calculatePermissions(): Int {
        var permissions = 0
        if (binding.cbPrint.isChecked) permissions = permissions or 
            com.itextpdf.kernel.pdf.EncryptionConstants.ALLOW_PRINTING
        if (binding.cbModify.isChecked) permissions = permissions or 
            com.itextpdf.kernel.pdf.EncryptionConstants.ALLOW_MODIFY_CONTENTS
        if (binding.cbCopy.isChecked) permissions = permissions or 
            com.itextpdf.kernel.pdf.EncryptionConstants.ALLOW_COPY
        if (binding.cbAnnotate.isChecked) permissions = permissions or 
            com.itextpdf.kernel.pdf.EncryptionConstants.ALLOW_MODIFY_ANNOTATIONS
        if (binding.cbFillForms.isChecked) permissions = permissions or 
            com.itextpdf.kernel.pdf.EncryptionConstants.ALLOW_FILL_IN
        if (binding.cbAssembly.isChecked) permissions = permissions or 
            com.itextpdf.kernel.pdf.EncryptionConstants.ALLOW_ASSEMBLY
        if (binding.cbDegradedPrint.isChecked) permissions = permissions or 
            com.itextpdf.kernel.pdf.EncryptionConstants.ALLOW_DEGRADED_PRINTING
        return permissions
    }

    private fun validateAndDecrypt() {
        // Implementation for decryption
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isProcessing.collect { isProcessing ->
                    binding.btnEncrypt.isEnabled = !isProcessing
                    binding.btnDecrypt.isEnabled = !isProcessing
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(uri: Uri?) = EncryptionFragment().apply {
            arguments = Bundle().apply {
                putParcelable("document_uri", uri)
            }
        }
    }
}
