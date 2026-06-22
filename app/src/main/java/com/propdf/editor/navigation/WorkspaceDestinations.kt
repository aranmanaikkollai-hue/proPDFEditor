package com.propdf.editor.navigation

/**
 * Navigation destinations for Phase 5 workspace features.
 */
sealed class WorkspaceDestination(val route: String) {
    object StorageManager : WorkspaceDestination("storage_manager")
    object BackupSettings : WorkspaceDestination("backup_settings")
    object RestoreBackup : WorkspaceDestination("restore_backup")
    object FolderWatch : WorkspaceDestination("folder_watch")
    object OfflineShare : WorkspaceDestination("offline_share")
    object NasConnections : WorkspaceDestination("nas_connections")
    object NasBrowser : WorkspaceDestination("nas_browser/{configId}") {
        fun createRoute(configId: Long) = "nas_browser/$configId"
    }
}
