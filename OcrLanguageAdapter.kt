package com.propdf.editor.ui.ocr

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.propdf.core.domain.model.OcrLanguage
import com.propdf.editor.databinding.ItemOcrLanguageBinding

class OcrLanguageAdapter(
    private val onLanguageToggled: (OcrLanguage, Boolean) -> Unit
) : ListAdapter<OcrLanguage, OcrLanguageAdapter.ViewHolder>(DiffCallback()) {

    private val selectedLanguages = mutableSetOf<OcrLanguage>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(ItemOcrLanguageBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) { holder.bind(getItem(position)) }

    fun setSelectedLanguages(languages: List<OcrLanguage>) {
        selectedLanguages.clear(); selectedLanguages.addAll(languages); notifyDataSetChanged()
    }

    inner class ViewHolder(private val binding: ItemOcrLanguageBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(language: OcrLanguage) {
            binding.languageChip.text = language.displayName
            binding.languageChip.isChecked = selectedLanguages.contains(language)
            binding.languageChip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedLanguages.add(language) else selectedLanguages.remove(language)
                onLanguageToggled(language, isChecked)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<OcrLanguage>() {
        override fun areItemsTheSame(oldItem: OcrLanguage, newItem: OcrLanguage) = oldItem.code == newItem.code
        override fun areContentsTheSame(oldItem: OcrLanguage, newItem: OcrLanguage) = oldItem == newItem
    }
}
