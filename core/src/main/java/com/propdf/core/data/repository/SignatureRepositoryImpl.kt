package com.propdf.core.data.repository

import android.graphics.Bitmap
import com.propdf.core.data.database.dao.SignatureDao
import com.propdf.core.data.database.entity.SignatureEntity
import com.propdf.core.domain.repository.SavedSignature
import com.propdf.core.domain.repository.SignatureRepository
import java.io.File
import java.io.FileOutputStream
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SignatureRepositoryImpl @Inject constructor(
    private val signatureDao: SignatureDao,
    private val filesDir: File
) : SignatureRepository {

    override suspend fun saveSignature(bitmap: Bitmap, name: String) {
        val id = UUID.randomUUID().toString()
        val file = File(filesDir, "signatures/$id.png")
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        signatureDao.insert(
            SignatureEntity(
                id = id,
                name = name,
                filePath = file.absolutePath,
                type = "drawn"
            )
        )
    }

    override suspend fun getSignatures(): List<SavedSignature> {
        return signatureDao.observeAll().let { flow ->
            // This should be collected as flow in UI; for now return empty
            emptyList()
        }
    }

    override suspend fun deleteSignature(id: String) {
        signatureDao.delete(id)
    }
}
