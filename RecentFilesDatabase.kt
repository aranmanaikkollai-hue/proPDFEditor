package com.propdf.editor.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Room
import kotlinx.coroutines.flow.Flow

// -------------------------------------------------------
// ENTITY
// -------------------------------------------------------

@Entity(tableName = "recent_files")
data class RecentFileEntity(
    @PrimaryKey
    val uri: String,
    val displayName: String,
    val fileSizeBytes: Long,
    val lastOpenedAt: Long = System.currentTimeMillis(),
    val pageCount: Int = 0,
    val isFavourite: Boolean = false,
    val category: String = "General"
)

// -------------------------------------------------------
// DAO
// -------------------------------------------------------

@Dao
interface RecentFilesDao {

    @Query("SELECT * FROM recent_files ORDER BY lastOpenedAt DESC LIMIT 50")
    fun getAll(): Flow<List<RecentFileEntity>>

    @Query("SELECT * FROM recent_files WHERE isFavourite = 1 ORDER BY displayName ASC")
    fun getFavourites(): Flow<List<RecentFileEntity>>

    @Query("SELECT DISTINCT category FROM recent_files ORDER BY category ASC")
    fun getCategories(): Flow<List<String>>

    @Query("SELECT * FROM recent_files WHERE category = :cat ORDER BY lastOpenedAt DESC")
    fun getByCategory(cat: String): Flow<List<RecentFileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(file: RecentFileEntity)

    @Query("UPDATE recent_files SET isFavourite = :fav WHERE uri = :uri")
    suspend fun setFavourite(uri: String, fav: Boolean)

    @Query("UPDATE recent_files SET category = :cat WHERE uri = :uri")
    suspend fun setCategory(uri: String, cat: String)

    @Query("DELETE FROM recent_files WHERE isFavourite = 0 AND (category IS NULL OR category = '')")
    suspend fun clearRecentOnly()

    @Query("DELETE FROM recent_files WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("DELETE FROM recent_files")
    suspend fun clearAll()

  @Query("SELECT * FROM recent_files WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): RecentFileEntity?

    @Query("UPDATE recent_files SET pageCount = :count WHERE uri = :uri")
    suspend fun updatePageCount(uri: String, count: Int)

    @Query("UPDATE recent_files SET lastOpenedAt = :time WHERE uri = :uri")
    suspend fun updateLastOpened(uri: String, time: Long)

    @Query("SELECT COUNT(*) FROM recent_files")
    suspend fun getCount(): Int

    @Query("SELECT * FROM recent_files WHERE displayName LIKE '%' || :query || '%' ORDER BY lastOpenedAt DESC")
    fun search(query: String): Flow<List<RecentFileEntity>>
}

// -------------------------------------------------------
// DATABASE
// -------------------------------------------------------

@Database(
    entities = [RecentFileEntity::class],
    version = 2,
    exportSchema = false
)
abstract class RecentFilesDatabase : RoomDatabase() {

    abstract fun recentFilesDao(): RecentFilesDao

    companion object {

        @Volatile
        private var INSTANCE: RecentFilesDatabase? = null

        fun get(context: Context): RecentFilesDatabase =
            INSTANCE ?: synchronized(this) {
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
