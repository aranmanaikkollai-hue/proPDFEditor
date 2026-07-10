package com.propdf.editor.data.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.propdf.core.data.local.dao.PdfDocumentDao
import com.propdf.core.domain.repository.DocumentRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

@HiltWorker
class DuplicateFinderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val pdfDocumentDao: PdfDocumentDao,
    private val documentRepository: DocumentRepository
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "duplicate_finder_worker"
        const val KEY_PROGRESS = "progress"
        const val KEY_DUPLICATE_COUNT = "duplicate_count"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val docs = pdfDocumentDao.getAllDocuments("NAME_ASC", includeHidden = false).first()
                .filter { it.checksum == null }

            val total = docs.size
            var processed = 0

            docs.forEach { doc ->
                val file = doc.filePath?.let { File(it) }
                if (file != null && file.exists()) {
                    val checksum = calculateChecksum(file)
                    pdfDocumentDao.update(doc.copy(checksum = checksum))
                }
                processed++
                if (total > 0) {
                    setProgress(
                        androidx.work.Data.Builder()
                            .putInt(KEY_PROGRESS, ((processed * 100) / total))
                            .build()
                    )
                }
            }

            val duplicates = documentRepository.findDuplicates()
            
            Result.success(
                androidx.work.Data.Builder()
                    .putInt(KEY_DUPLICATE_COUNT, duplicates.size)
                    .build()
            )
        } catch (e: Exception) {
            Result.failure()
        }
    }

    private fun calculateChecksum(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
