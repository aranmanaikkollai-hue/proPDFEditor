// security/src/main/java/com/propdf/security/data/database/SecurityDatabase.kt
package com.propdf.security.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.propdf.security.data.converter.InstantConverter
import com.propdf.security.data.converter.RectConverter
import com.propdf.security.data.dao.RedactionDao
import com.propdf.security.data.dao.SecureDocumentDao
import com.propdf.security.data.dao.SecurityOperationDao
import com.propdf.security.data.entity.RedactionEntity
import com.propdf.security.data.entity.SecureDocumentEntity
import com.propdf.security.data.entity.SecurityOperationEntity

@Database(
    entities = [
        SecurityOperationEntity::class,
        RedactionEntity::class,
        SecureDocumentEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(InstantConverter::class, RectConverter::class)
abstract class SecurityDatabase : RoomDatabase() {
    abstract fun securityOperationDao(): SecurityOperationDao
    abstract fun redactionDao(): RedactionDao
    abstract fun secureDocumentDao(): SecureDocumentDao
}
