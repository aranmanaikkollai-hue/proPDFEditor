package com.propdfeditor.ui.signature

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.propdf.editor.R
import com.propdf.editor.databinding.FragmentTypedSignatureBinding
import com.propdfeditor.security.SignatureViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class TypedSignatureFragment : Fragment() {

    private var _binding: FragmentTypedSignatureBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SignatureViewModel by activityViewModels()

    private var currentTextColor = Color.BLACK
    private var currentFontFamily = "cursive"
    private var currentFontSize = 48f

    private val fontOptions = listOf(
        "cursive" to "Script",
        "serif" to "Serif",
        "sans-serif" to "Sans Serif",
        "bold" to "Bold",
        "monospace" to "Monospace"
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTypedSignatureBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupFontSelector()
        setupPreview()
        setupControls()
        setupButtons()
        observeEvents()
    }

    private fun setupFontSelector() {
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            fontOptions.map { it.second }
        )
        binding.fontSelector.setAdapter(adapter)
        binding.fontSelector.setOnItemClickListener { _, _, position, _ ->
            currentFontFamily = fontOptions[position].first
            updatePreview()
        }
        binding.fontSelector.setText(fontOptions[0].second, false)
    }

    private fun setupPreview() {
        binding.signatureInput.doAfterTextChanged {
            updatePreview()
            binding.saveButton.isEnabled = !it.isNullOrBlank()
        }
    }

    private fun setupControls() {
        binding.fontSizeSlider.addOnChangeListener { _, value, _ ->
            currentFontSize = value
            updatePreview()
        }

        binding.colorBlack.setOnClickListener { setColor(Color.BLACK) }
        binding.colorBlue.setOnClickListener { setColor(Color.BLUE) }
        binding.colorRed.setOnClickListener { setColor(Color.RED) }
        binding.colorGreen.setOnClickListener { setColor(Color.GREEN) }
    }

    private fun setColor(color: Int) {
        currentTextColor = color
        updatePreview()
        updateColorSelection()
    }

    private fun updateColorSelection() {
        binding.colorBlack.alpha = if (currentTextColor == Color.BLACK) 1.0f else 0.5f
        binding.colorBlue.alpha = if (currentTextColor == Color.BLUE) 1.0f else 0.5f
        binding.colorRed.alpha = if (currentTextColor == Color.RED) 1.0f else 0.5f
        binding.colorGreen.alpha = if (currentTextColor == Color.GREEN) 1.0f else 0.5f
    }

    private fun updatePreview() {
        val text = binding.signatureInput.text.toString()
        if (text.isBlank()) {
            binding.signaturePreview.setImageBitmap(null)
            return
        }

        val bitmap = renderTextToBitmap(text, currentFontFamily, currentFontSize, currentTextColor)
        binding.signaturePreview.setImageBitmap(bitmap)
    }

    private fun renderTextToBitmap(
       
