package com.propdf.viewer.ui

import java.io.File

/**
 * Decrypted working copies of password-protected PDFs opened in the viewer.
 *
 * They are plaintext files in the app's private cache directory (`cacheDir/viewer_unlocked`), which
 * Android excludes from Auto Backup. The viewer deletes them when another document opens or the
 * viewer closes; this object additionally lets the application remove copies left behind when the
 * process was killed.
 *
 * Deleting a file is not secure erasure: flash storage, file-system journals and snapshots may
 * keep old bytes. This only removes the readable file from the app's directory.
 */
object ViewerUnlockedCache {

    const val DIR_NAME = "viewer_unlocked"

    fun dir(cacheDir: File): File = File(cacheDir, DIR_NAME)

    /**
     * Deletes entries inside the unlocked-copy directory only (never anything outside it).
     *
     * @param except a file that must be kept (the copy currently in use).
     * @param modifiedBefore only entries last modified strictly before this time (epoch millis) are
     *   deleted. Startup passes the process start time so a copy created by a viewer that opened
     *   moments after launch is never removed.
     * @return the number of entries deleted.
     */
    fun purge(cacheDir: File, except: File? = null, modifiedBefore: Long = Long.MAX_VALUE): Int {
        val directory = dir(cacheDir)
        val entries = directory.listFiles() ?: return 0
        var deleted = 0
        for (entry in entries) {
            if (except != null && entry == except) continue
            if (entry.lastModified() >= modifiedBefore) continue
            val removed = if (entry.isDirectory) entry.deleteRecursively() else entry.delete()
            if (removed) deleted++
        }
        return deleted
    }
}
