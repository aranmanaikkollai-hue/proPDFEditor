package com.propdf.editor.navigation

sealed class DocumentManagerDestination(val route: String) {
    object Manager : DocumentManagerDestination("document_manager")
    object StorageAnalyzer : DocumentManagerDestination("storage_analyzer")
    object DuplicateFinder : DocumentManagerDestination("duplicate_finder")
    object RecentActivity : DocumentManagerDestination("recent_activity")
    object FolderBrowser : DocumentManagerDestination("folder_browser/{path}") {
        fun createRoute(path: String) = "folder_browser/$path"
    }
    object RecycleBin : DocumentManagerDestination("recycle_bin")
    object HiddenFiles : DocumentManagerDestination("hidden_files")
    object LargeFiles : DocumentManagerDestination("large_files")
    object Search : DocumentManagerDestination("search")
}
