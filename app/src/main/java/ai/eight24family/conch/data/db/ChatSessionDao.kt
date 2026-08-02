package ai.eight24family.conch.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatSessionDao {
    @Query("SELECT * FROM chat_sessions WHERE serverId = :serverId AND agent = :agent ORDER BY lastUsedAt DESC")
    fun observeForServerAndAgent(serverId: String, agent: String): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE serverId = :serverId ORDER BY lastUsedAt DESC")
    fun observeForServer(serverId: String): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    suspend fun getById(id: String): ChatSessionEntity?

    @Query("SELECT * FROM chat_sessions WHERE serverId = :serverId AND agent = :agent ORDER BY lastUsedAt DESC LIMIT 1")
    suspend fun getMostRecent(serverId: String, agent: String): ChatSessionEntity?

    @Upsert
    suspend fun upsert(entity: ChatSessionEntity)

    @Query("UPDATE chat_sessions SET lastUsedAt = :ts WHERE id = :id")
    suspend fun touch(id: String, ts: Long)

    @Query("UPDATE chat_sessions SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM chat_sessions WHERE serverId = :serverId")
    suspend fun deleteAllForServer(serverId: String)
}
