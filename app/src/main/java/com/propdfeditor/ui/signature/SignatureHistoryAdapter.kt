package com.propdfeditor.ui.signature

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.propdf.editor.R
import com.propdf.editor.databinding.ItemSignatureHistoryBinding
import com.propdfeditor.core.database.entity.SignatureHistoryEntity
import java.text.SimpleDateFormat

class SignatureHistoryAdapter(private val dateFormat: SimpleDateFormat) 
    : ListAdapter<SignatureHistoryEntity, SignatureHistoryAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSignatureHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemSignatureHistoryBinding) 
        : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: SignatureHistoryEntity) {
            binding.apply {
                documentName.text = entry.documentName
                signatureName.text = entry.signatureName
                signedAt.text = dateFormat.format(entry.signedAt)
                pageNumber.text = root.context.getString(R.string.page_number, entry.pageNumber)
                val statusColor = when (entry.verificationStatus) {
                    SignatureHistoryEntity.VerificationStatus.VALID -> 
                        androidx.core.content.ContextCompat.getColor(root.context, android.R.color.holo_green_dark)
                    SignatureHistoryEntity.VerificationStatus.INVALID,
                    SignatureHistoryEntity.VerificationStatus.CORRUPTED -> 
                        androidx.core.content.ContextCompat.getColor(root.context, android.R.color.holo_red_dark)
                    else -> androidx.core.content.ContextCompat.getColor(root.context, android.R.color.darker_gray)
                }
                statusIndicator.setColorFilter(statusColor)
                certificateAlias.visibility = if (entry.certificateAlias != null) View.VISIBLE else View.GONE
                certificateAlias.text = entry.certificateAlias
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<SignatureHistoryEntity>() {
        override fun areItemsTheSame(a: SignatureHistoryEntity, b: SignatureHistoryEntity) = a.id == b.id
        override fun areContentsTheSame(a: SignatureHistoryEntity, b: SignatureHistoryEntity) = a == b
    }
}
