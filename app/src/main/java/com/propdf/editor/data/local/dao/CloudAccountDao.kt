package com.propdf.editor.data.local.dao

import androidx.room.*
import com.propdf.editor.data.local.entity.CloudAccountEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CloudAccountDao {

    @Query("SELECT * FROM cloud_accounts WHERE isActive = 1")
    fun getActiveAccounts(): Flow<List<CloudAccountEntity>>

    @Query("SELECT * FROM cloud_accounts WHERE provider = :provider LIMIT 1")
    suspend fun getByProvider(provider: String): CloudAccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: CloudAccountEntity)

    @Update
    suspend fun update(account: CloudAccountEntity)

    @Query("UPDATE cloud_accounts SET isActive = 0 WHERE id = :id")
    suspend fun deactivate(id: Long)

    @Delete
    suspend fun delete(account: CloudAccountEntity)
}
