package com.propdf.editor.data.local

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "recent_files")
data class RecentFile(
    @PrimaryKey val path: String,
    val name: String,
    val date: Long
)

@Dao
interface RecentFilesDao {
    @Query("SELECT * FROM recent_files ORDER BY date DESC LIMIT 100")
    suspend fun getAll(): List<RecentFile>

    @Query("SELECT * FROM recent_files ORDER BY date DESC LIMIT 100")
    fun getAllFlow(): Flow<List<RecentFile>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(file: RecentFile)

    @Query("DELETE FROM recent_files WHERE path = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM recent_files WHERE date < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("SELECT COUNT(*) FROM recent_files")
    suspend fun count(): Int

    @Query("DELETE FROM recent_files WHERE path NOT IN (SELECT path FROM recent_files ORDER BY date DESC LIMIT 50)")
    suspend fun trimToFifty()
}

@Database(entities = [RecentFile::class], version = 1, exportSchema = false)
abstract class RecentFilesDatabase : RoomDatabase() {
    abstract fun recentFilesDao(): RecentFilesDao

    companion object {
        @Volatile
        private var INSTANCE: RecentFilesDatabase? = null

        fun get(context: Context): RecentFilesDatabase {
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
