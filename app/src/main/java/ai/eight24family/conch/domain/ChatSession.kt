package ai.eight24family.conch.domain

import ai.eight24family.conch.agent.Agent

data class ChatSession(
    val id: String,
    val serverId: String,
    val agent: Agent,
    val name: String,
    val createdAt: Long,
    val lastUsedAt: Long
)
