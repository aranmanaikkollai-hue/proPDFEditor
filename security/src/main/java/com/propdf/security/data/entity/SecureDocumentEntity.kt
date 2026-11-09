// security/src/main/java/com/propdf/security/data/entity/SecureDocumentEntity.kt
package com.propdf.security.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "secure_documents")
data class SecureDocumentEntity(
    @PrimaryKey
    val uri: String,
    val encryptionType: EncryptionType,
    val hasOwnerPassword: Boolean,
    val hasUserPassword: Boolean,
    val permissions: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAccessed: Long = System.currentTimeMillis(),
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
