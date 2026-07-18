// security/src/main/java/com/propdf/security/ui/fragment/SanitizationFragment.kt
package com.propdf.security.ui.fragment

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.propdf.security.R
import com.propdf.security.databinding.FragmentSanitizationBinding
import com.propdf.security.ui.viewmodel.SecurityViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SanitizationFragment : Fragment() {

    private var _binding: FragmentSanitizationBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SecurityViewModel by activityViewModels()
    private var documentUri: Uri? = null

    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let { outputUri ->
            when (currentOperation) {
                Operation.REMOVE_METADATA -> viewModel.removeMetadata(documentUri!!, outputUri)
                Operation.SANITIZE -> viewModel.sanitizeDocument(documentUri!!, outputUri)
                Operation.SECURE_DELETE -> viewModel.secureDelete(documentUri!!)
            }
        }
    }

    private var currentOperation: Operation = Operation.SANITIZE

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSanitizationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        documentUri = arguments?.getParcelable("document_uri")
        setupListeners()
    }

    private fun setupListeners() {
        binding.btnRemoveMetadata.setOnClickListener {
            currentOperation = Operation.REMOVE_METADATA
            showConfirmationDialog()
        }

        binding.btnSanitize.setOnClickListener {
            currentOperation = Operation.SANITIZE
            showConfirmationDialog()
        }

        binding.btnSecureDelete.setOnClickListener {
            currentOperation = Operation.SECURE_DELETE
            showSecureDeleteDialog()
        }
    }

    private fun showConfirmationDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_confirm_operation)
            .setMessage(R.string.dialog_sanitize_warning)
            .setPositiveButton(R.string.action_proceed) { _, _ ->
                createDocument.launch("sanitized_document.pdf")
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showSecureDeleteDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_secure_delete_title)
            .setMessage(R.string.dialog_secure_delete_warning)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                documentUri?.let { viewModel.secureDelete(it) }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private enum class Operation {
        REMOVE_METADATA, SANITIZE, SECURE_DELETE
    }

    companion object {
        fun newInstance(uri: Uri?) = SanitizationFragment().apply {
            arguments = Bundle().apply {
                putParcelable("document_uri", uri)
            }
        }
    }
}
