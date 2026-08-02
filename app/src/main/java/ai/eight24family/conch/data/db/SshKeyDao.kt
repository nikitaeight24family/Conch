package ai.eight24family.conch.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SshKeyDao {
    @Query("SELECT * FROM ssh_keys ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SshKeyEntity>>

    @Query("SELECT * FROM ssh_keys WHERE id = :id")
    suspend fun getById(id: String): SshKeyEntity?

    @Query("SELECT * FROM ssh_keys")
    suspend fun getAll(): List<SshKeyEntity>

    @Upsert
    suspend fun upsert(entity: SshKeyEntity)

    @Query("DELETE FROM ssh_keys WHERE id = :id")
    suspend fun deleteById(id: String)
}
