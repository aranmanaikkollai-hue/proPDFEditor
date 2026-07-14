package com.propdfeditor.ui.signature

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.propdf.editor.R
import com.propdf.editor.databinding.ItemVerificationResultBinding
import com.propdfeditor.core.pdf.signature.PdfSignatureEngine
import java.text.SimpleDateFormat
import java.util.Locale

class VerificationResultAdapter : ListAdapter<PdfSignatureEngine.SignatureVerificationResult, 
    VerificationResultAdapter.ViewHolder>(DiffCallback()) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemVerificationResultBinding.inflate(
            LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemVerificationResultBinding) 
        : RecyclerView.ViewHolder(binding.root) {

        fun bind(result: PdfSignatureEngine.SignatureVerificationResult) {
            binding.apply {
                val ctx = root.context
                if (result.isValid) {
                    validityIcon.setImageResource(R.drawable.ic_check_circle)
                    validityIcon.setColorFilter(androidx.core.content.ContextCompat.getColor(
                        ctx, android.R.color.holo_green_dark))
                } else {
                    validityIcon.setImageResource(R.drawable.ic_error)
                    validityIcon.setColorFilter(androidx.core.content.ContextCompat.getColor(
                        ctx, android.R.color.holo_red_dark))
                }
                signatureFieldName.text = result.signatureFieldName
                signerName.text = ctx.getString(R.string.signer_name, result.signerName ?: "Unknown")
                issuerName.text = ctx.getString(R.string.issuer_name, result.issuerName ?: "Unknown")
                signDate.text = result.signDate?.let { 
                    ctx.getString(R.string.signed_on, dateFormat.format(it))
                } ?: ctx.getString(R.string.sign_date_unknown)
                hashAlgorithm.text = result.hashAlgorithm ?: "Unknown algorithm"
                coversDocumentChip.isChecked = result.coversWholeDocument
                coversDocumentChip.isCheckable = false
                timestampedChip.isChecked = result.isTimestamped
                timestampedChip.isCheckable = false
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<PdfSignatureEngine.SignatureVerificationResult>() {
        override fun areItemsTheSame(a: PdfSignatureEngine.SignatureVerificationResult, 
            b: PdfSignatureEngine.SignatureVerificationResult) = a.signatureFieldName == b.signatureFieldName
        override fun areContentsTheSame(a: PdfSignatureEngine.SignatureVerificationResult, 
            b: PdfSignatureEngine.SignatureVerificationResult) = a == b
    }
}
