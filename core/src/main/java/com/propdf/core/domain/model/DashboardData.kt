package com.propdf.core.domain.model

data class DashboardData(
    val recentFiles: List<RecentFile> = emptyList(),
    val pinnedFiles: List<RecentFile> = emptyList(),
    val favoriteFiles: List<RecentFile> = emptyList(),
    val continueReading: ReadingProgress? = null,
    val recentOcr: List<OcrRecord> = emptyList(),
    val recentScans: List<ScanRecord> = emptyList(),
    val suggestions: List<SmartSuggestion> = emptyList(),
    val storageUsed: Long = 0L,
    val storageTotal: Long = 1L,
    val recycleBinCount: Int = 0
) {
    fun isEmpty(): Boolean =
        recentFiles.isEmpty() && pinnedFiles.isEmpty() && favoriteFiles.isEmpty()
                && continueReading == null && recentOcr.isEmpty() && recentScans.isEmpty()
}
