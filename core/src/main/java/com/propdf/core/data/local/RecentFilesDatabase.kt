package com.propdf.core.data.local

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "recent_files")
data class RecentFileEntity(
    @PrimaryKey val uri: String,
    val displayName: String,
    val fileSizeBytes: Long = 0,
    val lastOpenedAt: Long = System.currentTimeMillis(),
    val pageCount: Int = 0,
    val isFavourite: Boolean = false,
    val category: String = ""
)

@Dao
interface RecentFilesDao {
    @Query("SELECT * FROM recent_files ORDER BY lastOpenedAt DESC")
    fun getAll(): Flow<List<RecentFileEntity>>

    @Query("SELECT * FROM recent_files WHERE isFavourite = 1 ORDER BY lastOpenedAt DESC")
    fun getFavourites(): Flow<List<RecentFileEntity>>

    @Query("SELECT * FROM recent_files WHERE category = :category ORDER BY lastOpenedAt DESC")
    fun getByCategory(category: String): Flow<List<RecentFileEntity>>

    @Query("SELECT DISTINCT category FROM recent_files WHERE category != '' ORDER BY category")
    fun getCategories(): Flow<List<String>>

    @Query("SELECT * FROM recent_files WHERE displayName LIKE '%' || :query || '%' OR category LIKE '%' || :query || '%' ORDER BY lastOpenedAt DESC")
    fun search(query: String): Flow<List<RecentFileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RecentFileEntity)

    @Query("DELETE FROM recent_files WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("UPDATE recent_files SET isFavourite = :isFavourite WHERE uri = :uri")
    suspend fun setFavourite(uri: String, isFavourite: Boolean)

    @Query("UPDATE recent_files SET category = :category WHERE uri = :uri")
    suspend fun setCategory(uri: String, category: String)

    @Query("UPDATE recent_files SET pageCount = :count WHERE uri = :uri")
    suspend fun updatePageCount(uri: String, count: Int)

    @Query("DELETE FROM recent_files WHERE isFavourite = 0")
    suspend fun clearRecentOnly()

    @Query("DELETE FROM recent_files")
    suspend fun clearAll()

    @Query("SELECT * FROM recent_files WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): RecentFileEntity?
}

@Database(
    entities = [RecentFileEntity::class],
    version = 1,
    exportSchema = false
)
abstract class RecentFilesDatabase : RoomDatabase() {
    abstract fun recentFilesDao(): RecentFilesDao

    companion object {
        @Volatile
        private var INSTANCE: RecentFilesDatabase? = null

        fun getInstance(context: Context): RecentFilesDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RecentFilesDatabase::class.java,
                    "recent_files.db"
                )
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
            }
        }
    }
}
