package com.propdf.editor.domain.model

/**
 * Cloud sync status for a document, stored as its .name string alongside the
 * document record (see CloudSyncManager, which writes SyncStatus.SYNCED.name
 * into PdfDocumentEntity.syncStatus). This type was imported by
 * CloudSyncManager, GoogleDriveManager, OneDriveManager, and DropboxManager but
 * was never declared anywhere in the codebase - an import of a nonexistent
 * symbol is an unresolved-reference compile error even in files where the
 * import ends up unused.
 */
enum class SyncStatus {
    NOT_SYNCED,
    PENDING,
    SYNCING,
    SYNCED,
    FAILED
}
