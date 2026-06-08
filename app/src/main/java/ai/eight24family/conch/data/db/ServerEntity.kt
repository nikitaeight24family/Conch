package ai.eight24family.conch.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.domain.AuthMethod
import ai.eight24family.conch.domain.Server

/**
 * Servers row.
 *
 * Schema v7 collapses the legacy `sshKeyId` (single-primary) +
 * `additionalKeyIdsCsv` columns into one flat [sshKeyIdsCsv]. There is
 * no concept of "primary" any more — sshj walks every enrolled key in
 * the list and the server picks. Migration 6→7 in [Migrations.kt]
 * concatenates the old columns into the new one.
 */
@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val authMethod: String,
    val knownHostKey: String?,
    @ColumnInfo(defaultValue = "CLAUDE") val agent: String = "CLAUDE",
    /** Comma-separated ssh_key.id values. Null for password servers,
     *  or for KEY servers whose only key was deleted from the keychain. */
    @ColumnInfo(defaultValue = "NULL") val sshKeyIdsCsv: String? = null,
) {
    fun toDomain() = Server(
        id = id,
        name = name,
        host = host,
        port = port,
        username = username,
        authMethod = AuthMethod.valueOf(authMethod),
        knownHostKey = knownHostKey,
        agent = runCatching { Agent.valueOf(agent) }.getOrDefault(Agent.CLAUDE),
        sshKeyIds = sshKeyIdsCsv
            ?.split(",")
            ?.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
            ?: emptyList(),
    )

    companion object {
        fun fromDomain(s: Server) = ServerEntity(
            id = s.id,
            name = s.name,
            host = s.host,
            port = s.port,
            username = s.username,
            authMethod = s.authMethod.name,
            knownHostKey = s.knownHostKey,
            agent = s.agent.name,
            sshKeyIdsCsv = s.sshKeyIds.takeIf { it.isNotEmpty() }
                ?.joinToString(","),
        )
    }
}
