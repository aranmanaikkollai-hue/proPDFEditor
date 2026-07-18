// security/src/main/java/com/propdf/security/ui/fragment/PermissionsFragment.kt
package com.propdf.security.ui.fragment

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
import com.itextpdf.kernel.pdf.EncryptionConstants
import com.propdf.security.R
import com.propdf.security.databinding.FragmentPermissionsBinding
import com.propdf.security.ui.viewmodel.SecurityViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class PermissionsFragment : Fragment() {

    private var _binding: FragmentPermissionsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SecurityViewModel by activityViewModels()
    private var documentUri: Uri? = null

    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let { applyPermissions(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPermissionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        documentUri = arguments?.getParcelable("document_uri")
        setupListeners()
        setupObservers()
    }

    private fun setupListeners() {
        binding.btnApplyPermissions.setOnClickListener {
            validateAndApply()
        }

        binding.btnSelectAll.setOnClickListener {
            setAllPermissions(true)
        }

        binding.btnDeselectAll.setOnClickListener {
            setAllPermissions(false)
        }
    }

    private fun setAllPermissions(checked: Boolean) {
        binding.cbPrint.isChecked = checked
        binding.cbModify.isChecked = checked
        binding.cbCopy.isChecked = checked
        binding.cbAnnotate.isChecked = checked
        binding.cbFillForms.isChecked = checked
        binding.cbAssembly.isChecked = checked
        binding.cbDegradedPrint.isChecked = checked
    }

    private fun validateAndApply() {
        val ownerPassword = binding.etOwnerPassword.text?.toString()

        if (ownerPassword.isNullOrBlank()) {
            binding.tilOwnerPassword.error = getString(R.string.error_password_required)
            return
        }

        binding.tilOwnerPassword.error = null
        documentUri?.let { createDocument.launch("protected_document.pdf") }
            ?: Snackbar.make(binding.root, R.string.error_no_document, Snackbar.LENGTH_SHORT).show()
    }

    private fun applyPermissions(outputUri: Uri) {
        val ownerPassword = binding.etOwnerPassword.text?.toString() ?: return
        val permissions = calculatePermissions()

        documentUri?.let { uri ->
            viewModel.setPermissions(uri, ownerPassword, permissions, outputUri)
        }
    }

    private fun calculatePermissions(): Int {
        var permissions = 0
        if (binding.cbPrint.isChecked) permissions = permissions or EncryptionConstants.ALLOW_PRINTING
        if (binding.cbModify.isChecked) permissions = permissions or EncryptionConstants.ALLOW_MODIFY_CONTENTS
        if (binding.cbCopy.isChecked) permissions = permissions or EncryptionConstants.ALLOW_COPY
        if (binding.cbAnnotate.isChecked) permissions = permissions or EncryptionConstants.ALLOW_MODIFY_ANNOTATIONS
        if (binding.cbFillForms.isChecked) permissions = permissions or EncryptionConstants.ALLOW_FILL_IN
        if (binding.cbAssembly.isChecked) permissions = permissions or EncryptionConstants.ALLOW_ASSEMBLY
        if (binding.cbDegradedPrint.isChecked) permissions = permissions or EncryptionConstants.ALLOW_DEGRADED_PRINTING
        return permissions
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isProcessing.collect { isProcessing ->
                    binding.btnApplyPermissions.isEnabled = !isProcessing
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(uri: Uri?) = PermissionsFragment().apply {
            arguments = Bundle().apply {
                putParcelable("document_uri", uri)
            }
        }
    }
}
