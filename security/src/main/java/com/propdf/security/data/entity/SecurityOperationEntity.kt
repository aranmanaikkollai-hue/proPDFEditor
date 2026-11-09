// security/src/main/java/com/propdf/security/data/entity/SecurityOperationEntity.kt
package com.propdf.security.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "security_operations")
data class SecurityOperationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val documentUri: String,
    val operationType: SecurityOperationType,
    val status: OperationStatus,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val errorMessage: String? = null,
    val outputUri: String? = null,
    val metadata: String? = null // JSON metadata
)

enum class SecurityOperationType {
    AES_ENCRYPT,
    AES_DECRYPT,
    PASSWORD_PROTECT,
    OWNER_PASSWORD,
    PERMISSION_SET,
    METADATA_REMOVE,
    SANITIZE,
    REDACT,
    PERMANENT_REDACT,
    SECURE_DELETE
}

enum class OperationStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED,
    CANCELLED
}
