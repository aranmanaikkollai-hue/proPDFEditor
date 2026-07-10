package com.propdf.core.domain.model

data class DocumentFilter(
    val query: String = "",
    val sortOption: SortOption = SortOption.DATE_MODIFIED_DESC,
    val fileTypeFilter: FileTypeFilter = FileTypeFilter.ALL,
    val tags: List<Long> = emptyList(),
    val collections: List<Long> = emptyList(),
    val dateFrom: Long? = null,
    val dateTo: Long? = null,
    val sizeFrom: Long? = null,
    val sizeTo: Long? = null,
    val includeHidden: Boolean = false,
    val includeRecycleBin: Boolean = false,
    val onlyFavorites: Boolean = false,
    val folderPath: String? = null
)
