package com.propdfeditor.ui.signature

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.propdf.editor.databinding.ItemSignatureBinding
import com.propdfeditor.core.database.entity.SignatureEntity
import java.io.File

class SignatureListAdapter(
    private val onSignatureClick: (SignatureEntity) -> Unit,
    private val onSignatureLongClick: (SignatureEntity) -> Unit,
    private val onFavoriteClick: (SignatureEntity) -> Unit
) : ListAdapter<SignatureEntity, SignatureListAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSignatureBinding.inflate(
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
        private val binding: ItemSignatureBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(signature: SignatureEntity) {
            binding.apply {
                signatureName.text = signature.name
                signatureType.text = signature.type.name.lowercase().replaceFirstChar { it.uppercase() }
                signatureUseCount.text = "Used ${signature.useCount} times"
                
                favoriteButton.isSelected = signature.isFavorite
                
                signature.bitmapPath?.let { path ->
                    Glide.with(root.context)
                        .load(File(path))
                        .centerInside()
                        .into(signaturePreview)
                }

                root.setOnClickListener { onSignatureClick(signature) }
                root.setOnLongClickListener {
                    onSignatureLongClick(signature)
                    true
                }
                favoriteButton.setOnClickListener { onFavoriteClick(signature) }
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<SignatureEntity>() {
        override fun areItemsTheSame(oldItem: SignatureEntity, newItem: SignatureEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: SignatureEntity, newItem: SignatureEntity): Boolean {
            return oldItem == newItem
        }
    }
}
