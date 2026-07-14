package com.propdfeditor.ui.signature

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.propdf.editor.databinding.FragmentSignatureListBinding
import com.propdfeditor.security.SignatureViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SignatureListFragment : Fragment() {
    private var _binding: FragmentSignatureListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SignatureViewModel by activityViewModels()
    private lateinit var adapter: SignatureListAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSignatureListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = SignatureListAdapter(
            onSignatureClick = { sig ->
                (activity as? SignatureManagerActivity)?.let { 
                    it.setResult(android.app.Activity.RESULT_OK, 
                        android.content.Intent().putExtra(
                            SignatureManagerActivity.EXTRA_SELECTED_SIGNATURE_ID, sig.id))
                    it.finish()
                }
            },
            onSignatureLongClick = { },
            onFavoriteClick = { sig -> viewModel.toggleFavorite(sig) }
        )
        binding.recyclerView.apply {
            layoutManager = GridLayoutManager(requireContext(), calculateSpanCount())
            adapter = this@SignatureListFragment.adapter
        }
    }

    private fun calculateSpanCount(): Int {
        val dm = resources.displayMetrics
        return ((dm.widthPixels / dm.density) / 180).coerceAtLeast(2)
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.signatures.collect { sigs ->
                    adapter.submitList(sigs)
                    binding.emptyState.visibility = if (sigs.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
