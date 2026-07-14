package com.propdfeditor.ui.signature

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.propdf.editor.R
import com.propdf.editor.databinding.DialogSignaturePickerBinding
import com.propdfeditor.core.database.entity.SignatureEntity
import com.propdfeditor.security.SignatureViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SignaturePickerDialog : DialogFragment() {

    private var _binding: DialogSignaturePickerBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SignatureViewModel by viewModels()
    private var onSignatureSelected: ((SignatureEntity) -> Unit)? = null

    private lateinit var adapter: SignatureListAdapter

    fun setOnSignatureSelectedListener(listener: (SignatureEntity) -> Unit) {
        onSignatureSelected = listener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return MaterialAlertDialogBuilder(requireContext())
            .setView(onCreateView(layoutInflater, null, savedInstanceState))
            .create()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogSignaturePickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        observeViewModel()
        setupButtons()
    }

    private fun setupRecyclerView() {
        adapter = SignatureListAdapter(
            onSignatureClick = { signature ->
                onSignatureSelected?.invoke(signature)
                dismiss()
            },
            onSignatureLongClick = { signature ->
                showSignatureOptions(signature)
            },
            onFavoriteClick = { signature ->
                viewModel.toggleFavorite(signature)
            }
        )

        binding.signatureRecyclerView.apply {
            layoutManager = GridLayoutManager(requireContext(), 2)
            adapter = this@SignaturePickerDialog.adapter
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.signatures.collect { signatures ->
                    adapter.submitList(signatures)
                    binding.emptyState.visibility = if (signatures.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun setupButtons() {
        binding.createNewButton.setOnClickListener {
            // Launch signature creation flow
            dismiss()
            // Navigate to signature creation
        }
    }

    private fun showSignatureOptions(signature: SignatureEntity) {
        val options = arrayOf(
            getString(R.string.use_signature),
            getString(R.string.edit_signature),
            getString(R.string.delete_signature)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(signature.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        onSignatureSelected?.invoke(signature)
                        dismiss()
                    }
                    1 -> {
                        // Edit - navigate to editor
                    }
                    2 -> viewModel.deleteSignature(signature)
                }
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SignaturePickerDialog"
    }
}
