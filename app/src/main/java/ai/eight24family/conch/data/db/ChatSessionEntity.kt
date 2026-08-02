package ai.eight24family.conch.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.domain.ChatSession

@Entity(
    tableName = "chat_sessions",
    indices = [Index(value = ["serverId", "agent"])]
)
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val serverId: String,
    val agent: String,
    val name: String,
    val createdAt: Long,
    val lastUsedAt: Long
) {
    fun toDomain() = ChatSession(
        id = id,
        serverId = serverId,
        agent = runCatching { Agent.valueOf(agent) }.getOrDefault(Agent.CLAUDE),
        name = name,
        createdAt = createdAt,
        lastUsedAt = lastUsedAt
    )

    companion object {
        fun fromDomain(s: ChatSession) = ChatSessionEntity(
            id = s.id,
            serverId = s.serverId,
            agent = s.agent.name,
            name = s.name,
            createdAt = s.createdAt,
            lastUsedAt = s.lastUsedAt
        )
    }
}
