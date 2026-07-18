// security/src/main/java/com/propdf/security/ui/fragment/RedactionFragment.kt
package com.propdf.security.ui.fragment

import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.propdf.security.databinding.FragmentRedactionBinding
import com.propdf.security.ui.adapter.RedactionAdapter
import com.propdf.security.ui.viewmodel.SecurityViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class RedactionFragment : Fragment() {

    private var _binding: FragmentRedactionBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SecurityViewModel by activityViewModels()
    private var documentUri: Uri? = null
    private lateinit var redactionAdapter: RedactionAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRedactionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        documentUri = arguments?.getParcelable("document_uri")
        documentUri?.let { viewModel.loadRedactions(it.toString()) }

        setupRecyclerView()
        setupListeners()
        setupObservers()
    }

    private fun setupRecyclerView() {
        redactionAdapter = RedactionAdapter(
            onDelete = { viewModel.removeRedaction(it) }
        )
        binding.rvRedactions.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = redactionAdapter
        }
    }

    private fun setupListeners() {
        binding.btnAddRedaction.setOnClickListener {
            showAddRedactionDialog()
        }

        binding.btnApplyRedactions.setOnClickListener {
            documentUri?.let { uri ->
                // Launch output picker
            }
        }

        binding.btnApplyPermanent.setOnClickListener {
            documentUri?.let { uri ->
                // Launch output picker for permanent redaction
            }
        }
    }

    private fun showAddRedactionDialog() {
        // Implementation for adding redaction area
        // This would integrate with PDF viewer to select area
    }

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.redactions.collect { redactions ->
                    redactionAdapter.submitList(redactions)
                    binding.tvEmptyState.visibility = 
                        if (redactions.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(uri: Uri?) = RedactionFragment().apply {
            arguments = Bundle().apply {
                putParcelable("document_uri", uri)
            }
        }
    }
}
