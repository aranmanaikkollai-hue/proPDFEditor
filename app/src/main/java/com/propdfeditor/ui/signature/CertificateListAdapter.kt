package com.propdfeditor.ui.signature

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.propdf.editor.databinding.ItemCertificateBinding
import com.propdfeditor.core.database.entity.CertificateEntity
import java.util.Date

class CertificateListAdapter(
    private val onCertificateClick: (CertificateEntity) -> Unit,
    private val onCertificateLongClick: (CertificateEntity) -> Unit,
    private val onSetDefaultClick: (CertificateEntity) -> Unit
) : ListAdapter<CertificateEntity, CertificateListAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCertificateBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemCertificateBinding) 
        : RecyclerView.ViewHolder(binding.root) {

        fun bind(certificate: CertificateEntity) {
            binding.apply {
                certificateName.text = certificate.displayName
                certificateSubject.text = certificate.subjectDn ?: certificate.alias

                val isValid = certificate.validUntil?.after(Date()) ?: false
                val daysUntil = certificate.validUntil?.let { 
                    ((it.time - System.currentTimeMillis()) / (1000 * 60 * 60 * 24)).toInt()
                }

                certificateValidity.text = when {
                    !isValid -> "Expired"
                    daysUntil != null && daysUntil < 30 -> "Expires in $daysUntil days"
                    else -> "Valid"
                }
                certificateValidity.setTextColor(
                    if (isValid) androidx.core.content.ContextCompat.getColor(
                        root.context, android.R.color.holo_green_dark)
                    else androidx.core.content.ContextCompat.getColor(
                        root.context, android.R.color.holo_red_dark)
                )
                defaultIndicator.visibility = 
                    if (certificate.isDefault) android.view.View.VISIBLE else android.view.View.GONE
                root.setOnClickListener { onCertificateClick(certificate) }
                root.setOnLongClickListener { onCertificateLongClick(certificate); true }
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<CertificateEntity>() {
        override fun areItemsTheSame(a: CertificateEntity, b: CertificateEntity) = a.id == b.id
        override fun areContentsTheSame(a: CertificateEntity, b: CertificateEntity) = a == b
    }
}
