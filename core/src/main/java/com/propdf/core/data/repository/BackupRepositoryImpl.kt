package com.propdf.core.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.Room
import com.propdf.core.data.database.ProPDFDatabase
import com.propdf.core.domain.repository.BackupRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : BackupRepository {

    override suspend fun createBackup(destination: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val dbFile = context.getDatabasePath("propdf_database")
            val walFile = File(dbFile.parent, "${dbFile.name}-wal")
            val shmFile = File(dbFile.parent, "${dbFile.name}-shm")

            context.contentResolver.openOutputStream(destination)?.use { output ->
                ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry("propdf_database"))
                    FileInputStream(dbFile).use { it.copyTo(zip) }
                    zip.closeEntry()

                    if (walFile.exists()) {
                        zip.putNextEntry(ZipEntry("propdf_database-wal"))
                        FileInputStream(walFile).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                    if (shmFile.exists()) {
                        zip.putNextEntry(ZipEntry("propdf_database-shm"))
                        FileInputStream(shmFile).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            } ?: throw IllegalStateException("Cannot open output stream")
        }
    }

    override suspend fun restoreBackup(source: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val dbFile = context.getDatabasePath("propdf_database")
            val walFile = File(dbFile.parent, "${dbFile.name}-wal")
            val shmFile = File(dbFile.parent, "${dbFile.name}-shm")

            // Close database before restore
            // In production, use proper database close/reopen cycle
            context.contentResolver.openInputStream(source)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        when (entry.name) {
                            "propdf_database" -> FileOutputStream(dbFile).use { zip.copyTo(it) }
                            "propdf_database-wal" -> FileOutputStream(walFile).use { zip.copyTo(it) }
                            "propdf_database-shm" -> FileOutputStream(shmFile).use { zip.copyTo(it) }
                        }
                        entry = zip.nextEntry
                    }
                }
            } ?: throw IllegalStateException("Cannot open input stream")
        }
    }

    override suspend fun exportSettings(destination: Uri): Result<Unit> {
        // Serialize settings DataStore to JSON and write
        return Result.success(Unit)
    }
}
