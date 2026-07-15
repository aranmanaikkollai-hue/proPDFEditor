package com.propdf.editor.ui.conversion.adapter

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.propdf.editor.databinding.ItemSelectedFileBinding
import com.propdf.editor.utils.FileUtils

class SelectedFileAdapter(
    private val onRemove: (Uri) -> Unit
) : ListAdapter<Uri, SelectedFileAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSelectedFileBinding.inflate(
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
        private val binding: ItemSelectedFileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(uri: Uri) {
            binding.apply {
                fileNameText.text = FileUtils.getFileName(root.context, uri) ?: uri.lastPathSegment
                fileSizeText.text = FileUtils.getFormattedFileSize(root.context, uri)
                
                removeButton.setOnClickListener {
                    onRemove(uri)
                }
                
                // Load thumbnail for images
                if (FileUtils.isImage(uri)) {
                    thumbnailImage.setImageURI(uri)
                } else {
                    thumbnailImage.setImageResource(FileUtils.getFileIcon(uri))
                }
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Uri>() {
        override fun areItemsTheSame(oldItem: Uri, newItem: Uri): Boolean {
            return oldItem == newItem
        }

        override fun areContentsTheSame(oldItem: Uri, newItem: Uri): Boolean {
            return oldItem == newItem
        }
    }
}
