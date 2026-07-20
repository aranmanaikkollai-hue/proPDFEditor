package com.propdfeditor.batch.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.propdfeditor.R
import com.propdfeditor.batch.data.entity.BatchJobEntity
import com.propdfeditor.batch.data.util.BatchJobStatus
import com.propdfeditor.batch.data.util.BatchJobType
import com.propdfeditor.databinding.ItemBatchJobBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class BatchJobAdapter(
    private val onCancelClick: (BatchJobEntity) -> Unit,
    private val onDeleteClick: (BatchJobEntity) -> Unit,
    private val onRetryClick: (BatchJobEntity) -> Unit
) : ListAdapter<BatchJobEntity, BatchJobAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBatchJobBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemBatchJobBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(job: BatchJobEntity) {
            val context = binding.root.context

            binding.jobTypeText.text = getTypeLabel(job.type)
            binding.jobStatusText.text = getStatusLabel(job.status)
            binding.jobStatusText.setTextColor(getStatusColor(job.status))

            binding.progressBar.progress = job.progress
            binding.progressText.text = context.getString(
                R.string.progress_format,
                job.processedItems,
                job.totalItems,
                job.progress
            )

            binding.createdAtText.text = formatTime(job.createdAt)

            // Status-specific UI
            when (job.status) {
                BatchJobStatus.RUNNING, BatchJobStatus.PENDING -> {
                    binding.cancelButton.visibility = View.VISIBLE
                    binding.deleteButton.visibility = View.GONE
                    binding.retryButton.visibility = View.GONE
                    binding.progressBar.visibility = View.VISIBLE
                }
                BatchJobStatus.FAILED -> {
                    binding.cancelButton.visibility = View.GONE
                    binding.deleteButton.visibility = View.VISIBLE
                    binding.retryButton.visibility = View.VISIBLE
                    binding.progressBar.visibility = View.GONE
                    binding.errorText.visibility = View.VISIBLE
                    binding.errorText.text = job.errorMessage
                }
                BatchJobStatus.COMPLETED -> {
                    binding.cancelButton.visibility = View.GONE
                    binding.deleteButton.visibility = View.VISIBLE
                    binding.retryButton.visibility = View.GONE
                    binding.progressBar.visibility = View.GONE
                }
                BatchJobStatus.CANCELLED -> {
                    binding.cancelButton.visibility = View.GONE
                    binding.deleteButton.visibility = View.VISIBLE
                    binding.retryButton.visibility = View.GONE
                    binding.progressBar.visibility = View.GONE
                }
                else -> {
                    binding.cancelButton.visibility = View.GONE
                    binding.deleteButton.visibility = View.VISIBLE
                    binding.retryButton.visibility = View.GONE
                }
            }

            binding.cancelButton.setOnClickListener { onCancelClick(job) }
            binding.deleteButton.setOnClickListener { onDeleteClick(job) }
            binding.retryButton.setOnClickListener { onRetryClick(job) }
        }

        private fun getTypeLabel(type: BatchJobType): String {
            return when (type) {
                BatchJobType.MERGE -> "Merge"
                BatchJobType.SPLIT -> "Split"
                BatchJobType.RENAME -> "Rename"
                BatchJobType.WATERMARK -> "Watermark"
                BatchJobType.ROTATE -> "Rotate"
                BatchJobType.COMPRESS -> "Compress"
                BatchJobType.OCR -> "OCR"
                BatchJobType.ENCRYPT -> "Encrypt"
                BatchJobType.DECRYPT -> "Decrypt"
                BatchJobType.EXPORT -> "Export"
                BatchJobType.DELETE -> "Delete"
            }
        }

        private fun getStatusLabel(status: BatchJobStatus): String {
            return when (status) {
                BatchJobStatus.PENDING -> "Pending"
                BatchJobStatus.RUNNING -> "Running"
                BatchJobStatus.PAUSED -> "Paused"
                BatchJobStatus.COMPLETED -> "Completed"
                BatchJobStatus.FAILED -> "Failed"
                BatchJobStatus.CANCELLED -> "Cancelled"
            }
        }

        private fun getStatusColor(status: BatchJobStatus): Int {
            val context = binding.root.context
            return when (status) {
                BatchJobStatus.COMPLETED -> context.getColor(R.color.status_success)
                BatchJobStatus.FAILED -> context.getColor(R.color.status_error)
                BatchJobStatus.RUNNING -> context.getColor(R.color.status_running)
                BatchJobStatus.PENDING -> context.getColor(R.color.status_pending)
                BatchJobStatus.CANCELLED -> context.getColor(R.color.status_cancelled)
                else -> context.getColor(R.color.status_default)
            }
        }

        private fun formatTime(timestamp: Long): String {
            val diff = System.currentTimeMillis() - timestamp
            return when {
                diff < TimeUnit.MINUTES.toMillis(1) -> "Just now"
                diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m ago"
                diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)}h ago"
                else -> SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(timestamp))
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<BatchJobEntity>() {
        override fun areItemsTheSame(oldItem: BatchJobEntity, newItem: BatchJobEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: BatchJobEntity, newItem: BatchJobEntity): Boolean {
            return oldItem == newItem
        }
    }
}
