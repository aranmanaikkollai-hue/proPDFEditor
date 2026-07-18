// security/src/main/java/com/propdf/security/data/entity/SecurityOperationEntity.kt
package com.propdf.security.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.propdf.security.data.converter.InstantConverter
import java.time.Instant

@Entity(tableName = "security_operations")
@TypeConverters(InstantConverter::class)
data class SecurityOperationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val documentUri: String,
    val operationType: SecurityOperationType,
    val status: OperationStatus,
    val createdAt: Instant = Instant.now(),
    val completedAt: Instant? = null,
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
