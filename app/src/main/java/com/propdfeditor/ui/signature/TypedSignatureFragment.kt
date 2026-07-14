       
package com.propdfeditor.ui.signature

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
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
        text: String,
        fontFamily: String,
        fontSize: Float,
        textColor: Int,
        width: Int = 400,
        height: Int = 200
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        bitmap.eraseColor(Color.TRANSPARENT)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            this.textSize = fontSize
            textAlign = Paint.Align.CENTER
            typeface = when (fontFamily.lowercase()) {
                "cursive", "script" -> Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                "serif" -> Typeface.SERIF
                "sans-serif" -> Typeface.SANS_SERIF
                "monospace" -> Typeface.MONOSPACE
                "bold" -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                else -> Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            }
        }

        val x = width / 2f
        val y = height / 2f - (paint.descent() + paint.ascent()) / 2f

        // Draw underline
        val textWidth = paint.measureText(text)
        val underlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            strokeWidth = 2f
            alpha = 128
        }

        canvas.drawText(text, x, y, paint)
        canvas.drawLine(
            x - textWidth / 2 - 20,
            y + paint.descent() + 5,
            x + textWidth / 2 + 20,
            y + paint.descent() + 5,
            underlinePaint
        )

        return bitmap
    }

    private fun setupButtons() {
        binding.saveButton.setOnClickListener {
            val text = binding.signatureInput.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener

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
                        viewModel.createTypedSignature(
                            name = name,
                            text = text,
                            fontFamily = currentFontFamily,
                            fontSize = currentFontSize,
                            textColor = currentTextColor
                        )
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun observeEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiEvent.collect { event ->
                    when (event) {
                        is SignatureViewModel.UiEvent.SignatureCreated -> {
                            Snackbar.make(binding.root, R.string.signature_saved, Snackbar.LENGTH_SHORT).show()
                            binding.signatureInput.text?.clear()
                            binding.signaturePreview.setImageBitmap(null)
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
