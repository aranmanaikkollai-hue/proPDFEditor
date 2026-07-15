package com.propdf.editor.ui.conversion.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.propdf.editor.R
import com.propdf.editor.databinding.ItemConversionHistoryBinding
import com.propdf.editor.domain.model.ConversionStatus
import com.propdf.editor.domain.model.ConversionTask
import com.propdf.editor.domain.model.ConversionType
import java.text.SimpleDateFormat
import java.util.*

class ConversionHistoryAdapter(
    private val onOpen: (ConversionTask) -> Unit,
    private val onDelete: (ConversionTask) -> Unit,
    private val onRetry: (ConversionTask) -> Unit
) : ListAdapter<ConversionTask, ConversionHistoryAdapter.ViewHolder>(DiffCallback()) {

    private val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemConversionHistoryBinding.inflate(
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
        private val binding: ItemConversionHistoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(task: ConversionTask) {
            binding.apply {
                conversionTypeText.text = getConversionTypeLabel(task.conversionType)
                fileNameText.text = task.outputFileName
                statusText.text = getStatusLabel(task.status)
                statusText.setTextColor(getStatusColor(task.status))
                
                dateText.text = dateFormat.format(task.createdAt)
                
                progressBar.isVisible = task.status == ConversionStatus.RUNNING
                progressBar.progress = task.progress
                
                // Show output info for completed tasks
                outputInfoText.isVisible = task.status == ConversionStatus.SUCCESS
                if (task.status == ConversionStatus.SUCCESS) {
                    outputInfoText.text = root.context.getString(
                        R.string.output_ready,
                        task.completedAt?.let { dateFormat.format(it) } ?: ""
                    )
                }

                // Action buttons
                openButton.isVisible = task.status == ConversionStatus.SUCCESS && task.outputUri != null
                retryButton.isVisible = task.status == ConversionStatus.FAILED
                deleteButton.isVisible = task.status != ConversionStatus.RUNNING

                openButton.setOnClickListener { onOpen(task) }
                retryButton.setOnClickListener { onRetry(task) }
                deleteButton.setOnClickListener { onDelete(task) }

                // Click to open if successful
                root.setOnClickListener {
                    if (task.status == ConversionStatus.SUCCESS) {
                        onOpen(task)
                    }
                }
            }
        }

        private fun getConversionTypeLabel(type: ConversionType): String {
            return when (type) {
                ConversionType.PDF_TO_IMAGES -> "PDF → Images"
                ConversionType.IMAGES_TO_PDF -> "Images → PDF"
                ConversionType.PDF_TO_TXT -> "PDF → Text"
                ConversionType.TXT_TO_PDF -> "Text → PDF"
                ConversionType.HTML_TO_PDF -> "HTML → PDF"
                ConversionType.MARKDOWN_TO_PDF -> "Markdown → PDF"
                ConversionType.MERGE_IMAGES -> "Merge Images"
                ConversionType.SPLIT_IMAGE -> "Split Image"
                ConversionType.ZIP_EXPORT -> "ZIP Export"
            }
        }

        private fun getStatusLabel(status: ConversionStatus): String {
            return when (status) {
                ConversionStatus.PENDING -> "Pending"
                ConversionStatus.RUNNING -> "Running"
                ConversionStatus.SUCCESS -> "Completed"
                ConversionStatus.FAILED -> "Failed"
                ConversionStatus.CANCELLED -> "Cancelled"
            }
        }

        private fun getStatusColor(status: ConversionStatus): Int {
            val context = binding.root.context
            return when (status) {
                ConversionStatus.PENDING -> context.getColor(R.color.status_pending)
                ConversionStatus.RUNNING -> context.getColor(R.color.status_running)
                ConversionStatus.SUCCESS -> context.getColor(R.color.status_success)
                ConversionStatus.FAILED -> context.getColor(R.color.status_failed)
                ConversionStatus.CANCELLED -> context.getColor(R.color.status_cancelled)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ConversionTask>() {
        override fun areItemsTheSame(oldItem: ConversionTask, newItem: ConversionTask): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ConversionTask, newItem: ConversionTask): Boolean {
            return oldItem == newItem
        }
    }
}
