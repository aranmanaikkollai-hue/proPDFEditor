// security/src/main/java/com/propdf/security/data/entity/SecureDocumentEntity.kt
package com.propdf.security.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.propdf.security.data.converter.InstantConverter
import java.time.Instant

@Entity(tableName = "secure_documents")
@TypeConverters(InstantConverter::class)
data class SecureDocumentEntity(
    @PrimaryKey
    val uri: String,
    val encryptionType: EncryptionType,
    val hasOwnerPassword: Boolean,
    val hasUserPassword: Boolean,
    val permissions: Int,
    val createdAt: Instant = Instant.now(),
    val lastAccessed: Instant = Instant.now(),
    val isSanitized: Boolean = false,
    val isRedacted: Boolean = false,
    val checksum: String? = null
)

enum class EncryptionType {
    NONE,
    AES_128,
    AES_256,
    STANDARD_128,
    STANDARD_40
}
