package com.propdf.editor.data.local


import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow


@Entity(tableName = "recent_files")
data class RecentFileEntity(
    @PrimaryKey val uri: String,
    val displayName: String,
    val fileSizeBytes: Long,
    val lastOpenedAt: Long = System.currentTimeMillis(),
    val pageCount: Int = 0,
    val isFavourite: Boolean = false,
    val category: String = "General"
)


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
}


@Database(entities = [RecentFileEntity::class], version = 2, exportSchema = false)
abstract class RecentFilesDatabase : RoomDatabase() {
    abstract fun recentFilesDao(): RecentFilesDao


    companion object {
        @Volatile private var INSTANCE: RecentFilesDatabase? = null


        fun get(context: Context): RecentFilesDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RecentFilesDatabase::class.java, "recent_files.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}