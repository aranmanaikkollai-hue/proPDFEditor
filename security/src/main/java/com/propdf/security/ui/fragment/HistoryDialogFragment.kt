// security/src/main/java/com/propdf/security/ui/fragment/HistoryDialogFragment.kt
package com.propdf.security.ui.fragment

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.propdf.security.R
import com.propdf.security.databinding.DialogHistoryBinding
import com.propdf.security.ui.adapter.HistoryAdapter
import com.propdf.security.ui.viewmodel.SecurityViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class HistoryDialogFragment : DialogFragment() {

    private var _binding: DialogHistoryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SecurityViewModel by activityViewModels()
    private lateinit var historyAdapter: HistoryAdapter

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogHistoryBinding.inflate(layoutInflater)
        
        setupRecyclerView()
        setupObservers()

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.history_title)
            .setView(binding.root)
            .setPositiveButton(R.string.action_cancel) { dialog, _ -> dialog.dismiss() }
            .create()
    }

    private fun setupRecyclerView() {
        historyAdapter = HistoryAdapter()
        binding.rvHistory.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = historyAdapter
        }
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.operations.collect { operations ->
                    historyAdapter.submitList(operations)
                    binding.tvEmptyState.visibility = 
                        if (operations.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = HistoryDialogFragment()
    }
}
