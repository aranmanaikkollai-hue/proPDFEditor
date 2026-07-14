package com.propdfeditor.ui.signature

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.propdf.editor.R
import com.propdf.editor.databinding.FragmentDrawSignatureBinding
import com.propdfeditor.security.SignatureViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class DrawSignatureFragment : Fragment() {

    private var _binding: FragmentDrawSignatureBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SignatureViewModel by activityViewModels()

    private var currentColor = Color.BLACK
    private var currentStrokeWidth = 4f

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDrawSignatureBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupSignaturePad()
        setupControls()
        setupButtons()
        observeEvents()
    }

    private fun setupSignaturePad() {
        binding.signaturePad.apply {
            strokeColor = currentColor
            strokeWidth = currentStrokeWidth
            onSignatureChanged = { hasSignature ->
                binding.saveButton.isEnabled = hasSignature
            }
        }
    }

    private fun setupControls() {
        binding.strokeWidthSlider.apply {
            progress = currentStrokeWidth.toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    currentStrokeWidth = progress.coerceAtLeast(1).toFloat()
                    binding.signaturePad.strokeWidth = currentStrokeWidth
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        binding.colorBlack.setOnClickListener { setColor(Color.BLACK) }
        binding.colorBlue.setOnClickListener { setColor(Color.BLUE) }
        binding.colorRed.setOnClickListener { setColor(Color.RED) }
        binding.colorGreen.setOnClickListener { setColor(Color.GREEN) }
    }

    private fun setColor(color: Int) {
        currentColor = color
        binding.signaturePad.strokeColor = color
        updateColorSelection()
    }

    private fun updateColorSelection() {
        binding.colorBlack.alpha = if (currentColor == Color.BLACK) 1.0f else 0.5f
        binding.colorBlue.alpha = if (currentColor == Color.BLUE) 1.0f else 0.5f
        binding.colorRed.alpha = if (currentColor == Color.RED) 1.0f else 0.5f
        binding.colorGreen.alpha = if (currentColor == Color.GREEN) 1.0f else 0.5f
    }

    private fun setupButtons() {
        binding.clearButton.setOnClickListener {
            binding.signaturePad.clear()
        }

        binding.saveButton.setOnClickListener {
            showSaveDialog()
        }
    }

    private fun showSaveDialog() {
        val bitmap = binding.signaturePad.getSignatureBitmap() ?: return
        
        val editText = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.signature_name_hint)
        }
        
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.save_signature)
            .setView(editText.apply {
                setPadding(48, 24, 48, 24)
            })
            .setPositiveButton(R.string.save) { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewModel.createDrawnSignature(
                        name = name,
                        bitmap = bitmap,
                        strokeWidth = currentStrokeWidth,
                        strokeColor = currentColor
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun observeEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiEvent.collect { event ->
                    when (event) {
                        is SignatureViewModel.UiEvent.SignatureCreated -> {
                            Snackbar.make(binding.root, R.string.signature_saved, Snackbar.LENGTH_SHORT).show()
                            binding.signaturePad.clear()
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
