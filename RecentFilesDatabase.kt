// PATH: app/src/main/java/com/propdf/editor/data/local/RecentFilesDatabase.kt
package com.propdf.editor.data.local

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ?? Entity ????????????????????????????????????????????????????
@Entity(tableName = "recent_files")
data class RecentFileEntity(
    @PrimaryKey val uri: String,
    val displayName: String,
    val fileSizeBytes: Long,
    val lastOpenedAt: Long = System.currentTimeMillis(),
    val pageCount: Int = 0,
    val thumbnailPath: String? = null
)

// ?? DAO ???????????????????????????????????????????????????????
@Dao
interface RecentFilesDao {
    @Query("SELECT * FROM recent_files ORDER BY lastOpenedAt DESC LIMIT 20")
    fun getAll(): Flow<List<RecentFileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(file: RecentFileEntity)

    @Query("DELETE FROM recent_files WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("DELETE FROM recent_files")
    suspend fun clearAll()
}

// ?? Database ??????????????????????????????????????????????????
@Database(entities = [RecentFileEntity::class], version = 1, exportSchema = false)
abstract class RecentFilesDatabase : RoomDatabase() {
    abstract fun recentFilesDao(): RecentFilesDao

    companion object {
        @Volatile private var INSTANCE: RecentFilesDatabase? = null

        fun get(context: Context): RecentFilesDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RecentFilesDatabase::class.java,
                    "recent_files.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}
