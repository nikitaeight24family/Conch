package ai.eight24family.conch.data

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.data.db.ChatSessionDao
import ai.eight24family.conch.data.db.ChatSessionEntity
import ai.eight24family.conch.domain.ChatSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class ChatSessionRepository(
    private val dao: ChatSessionDao
) {
    fun observe(serverId: String, agent: Agent): Flow<List<ChatSession>> =
        dao.observeForServerAndAgent(serverId, agent.name).map { list -> list.map { it.toDomain() } }

    suspend fun getById(id: String): ChatSession? = dao.getById(id)?.toDomain()

    suspend fun getMostRecent(serverId: String, agent: Agent): ChatSession? =
        dao.getMostRecent(serverId, agent.name)?.toDomain()

    suspend fun create(serverId: String, agent: Agent, name: String? = null): ChatSession {
        val now = System.currentTimeMillis()
        val finalName = name ?: defaultName(now)
        val session = ChatSession(
            id = UUID.randomUUID().toString(),
            serverId = serverId,
            agent = agent,
            name = finalName,
            createdAt = now,
            lastUsedAt = now
        )
        dao.upsert(ChatSessionEntity.fromDomain(session))
        return session
    }

    suspend fun touch(id: String) {
        dao.touch(id, System.currentTimeMillis())
    }

    suspend fun rename(id: String, name: String) {
        dao.rename(id, name)
    }

    suspend fun delete(id: String) {
        dao.deleteById(id)
    }

    suspend fun deleteAllForServer(serverId: String) {
        dao.deleteAllForServer(serverId)
    }

    private fun defaultName(ts: Long): String {
        val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        return "Session · ${fmt.format(Date(ts))}"
    }
}
