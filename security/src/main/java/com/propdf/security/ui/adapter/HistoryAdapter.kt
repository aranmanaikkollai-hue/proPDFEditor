// security/src/main/java/com/propdf/security/ui/adapter/HistoryAdapter.kt
package com.propdf.security.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.propdf.security.data.entity.OperationStatus
import com.propdf.security.data.entity.SecurityOperationEntity
import com.propdf.security.data.entity.SecurityOperationType
import com.propdf.security.databinding.ItemHistoryBinding
import java.time.format.DateTimeFormatter

class HistoryAdapter : ListAdapter<SecurityOperationEntity, HistoryAdapter.ViewHolder>(DiffCallback()) {

    private val dateFormatter = DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm")

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemHistoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(operation: SecurityOperationEntity) {
            binding.tvOperationType.text = getOperationLabel(operation.operationType)
            binding.tvDocumentName.text = operation.documentUri.substringAfterLast("/")
            binding.tvTimestamp.text = dateFormatter.format(
                java.time.LocalDateTime.ofInstant(
                    operation.createdAt, 
                    java.time.ZoneId.systemDefault()
                )
            )
            binding.tvStatus.text = operation.status.name
            binding.tvStatus.setTextColor(getStatusColor(operation.status))
        }

        private fun getOperationLabel(type: SecurityOperationType): String {
            return when (type) {
                SecurityOperationType.AES_ENCRYPT -> "AES Encrypt"
                SecurityOperationType.AES_DECRYPT -> "AES Decrypt"
                SecurityOperationType.PASSWORD_PROTECT -> "Password Protect"
                SecurityOperationType.OWNER_PASSWORD -> "Owner Password"
                SecurityOperationType.PERMISSION_SET -> "Set Permissions"
                SecurityOperationType.METADATA_REMOVE -> "Remove Metadata"
                SecurityOperationType.SANITIZE -> "Sanitize"
                SecurityOperationType.REDACT -> "Redact"
                SecurityOperationType.PERMANENT_REDACT -> "Permanent Redact"
                SecurityOperationType.SECURE_DELETE -> "Secure Delete"
            }
        }

        private fun getStatusColor(status: OperationStatus): Int {
            return when (status) {
                OperationStatus.SUCCESS -> android.graphics.Color.parseColor("#4CAF50")
                OperationStatus.FAILED -> android.graphics.Color.parseColor("#F44336")
                OperationStatus.PENDING -> android.graphics.Color.parseColor("#FFC107")
                OperationStatus.PROCESSING -> android.graphics.Color.parseColor("#2196F3")
                OperationStatus.CANCELLED -> android.graphics.Color.parseColor("#9E9E9E")
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<SecurityOperationEntity>() {
        override fun areItemsTheSame(old: SecurityOperationEntity, new: SecurityOperationEntity): Boolean {
            return old.id == new.id
        }

        override fun areContentsTheSame(old: SecurityOperationEntity, new: SecurityOperationEntity): Boolean {
            return old == new
        }
    }
}
