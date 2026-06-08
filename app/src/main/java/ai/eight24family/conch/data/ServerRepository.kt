package ai.eight24family.conch.data

import ai.eight24family.conch.data.db.ServerDao
import ai.eight24family.conch.data.db.ServerEntity
import ai.eight24family.conch.data.secrets.SecretsStore
import ai.eight24family.conch.domain.AuthMethod
import ai.eight24family.conch.domain.Server
import ai.eight24family.conch.domain.ServerSecrets
import ai.eight24family.conch.domain.SshKeyType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class ServerRepository(
    private val dao: ServerDao,
    private val secretsStore: SecretsStore,
    private val sshKeyRepository: SshKeyRepository,
    private val chatSessionRepository: ChatSessionRepository? = null
) {
    fun observeServers(): Flow<List<Server>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getById(id: String): Server? = dao.getById(id)?.toDomain()

    /**
     * Resolve the secret material the connector needs for [id].
     *
     * For PASSWORD servers this is just the stored password. For KEY
     * servers we materialise every enrolled SshKey row so the auth flow
     * can build N×SkAuthPublickey methods (FIDO) or fall back to the
     * single PEM (software). Orphaned key ids — rows that were deleted
     * from the keychain after being attached — are silently filtered.
     */
    suspend fun getSecrets(id: String): ServerSecrets {
        val server = dao.getById(id)?.toDomain() ?: return ServerSecrets()
        val storedPassword = secretsStore.loadServerSecret(id).password
        return when (server.authMethod) {
            AuthMethod.PASSWORD -> ServerSecrets(password = storedPassword)
            AuthMethod.KEY -> {
                if (server.sshKeyIds.isEmpty()) return ServerSecrets()
                val keys = server.sshKeyIds.mapNotNull { sshKeyRepository.getById(it) }
                if (keys.isEmpty()) return ServerSecrets()
                val first = keys.first()
                val isSk = first.type == SshKeyType.SK_ED25519 ||
                    first.type == SshKeyType.SK_ECDSA_NISTP256
                if (isSk) {
                    val skKeys = keys.filter {
                        it.type == SshKeyType.SK_ED25519 || it.type == SshKeyType.SK_ECDSA_NISTP256
                    }
                    ServerSecrets(skKeys = skKeys)
                } else {
                    // Software path is single-key — multi-PEM is rare and
                    // adds confusing UX (which key signed?). Use first only.
                    val keySec = sshKeyRepository.loadSecret(first.id) ?: return ServerSecrets()
                    ServerSecrets(
                        privateKeyPem = keySec.privateKeyPem,
                        keyPassphrase = keySec.passphrase,
                    )
                }
            }
        }
    }

    suspend fun save(
        server: Server,
        password: String?,
        leaveSecretsAlone: Boolean = false,
    ): Server {
        val finalServer = if (server.id.isBlank()) server.copy(id = UUID.randomUUID().toString()) else server
        dao.upsert(ServerEntity.fromDomain(finalServer))
        if (!leaveSecretsAlone) {
            if (finalServer.authMethod == AuthMethod.PASSWORD) {
                secretsStore.saveServerSecret(finalServer.id, ServerSecrets(password = password))
            } else {
                secretsStore.deleteServerSecret(finalServer.id)
            }
        }
        return finalServer
    }

    suspend fun updateKnownHostKey(id: String, fingerprint: String) {
        val current = dao.getById(id) ?: return
        dao.upsert(current.copy(knownHostKey = fingerprint))
    }

    /**
     * Append [keyId] to the server's enrolled-key list. No-op if it's
     * already attached. Order is preserved so newly added keys sit at
     * the bottom of edit-screen lists.
     */
    suspend fun attachKey(serverId: String, keyId: String) {
        val current = dao.getById(serverId)?.toDomain() ?: return
        if (keyId in current.sshKeyIds) {
            android.util.Log.d("SshAi-Pool", "  attachKey($serverId,$keyId): already attached, no-op")
            return
        }
        val updated = current.copy(sshKeyIds = current.sshKeyIds + keyId)
        dao.upsert(ServerEntity.fromDomain(updated))
        android.util.Log.d("SshAi-Pool", "  attachKey($serverId,$keyId): saved → sshKeyIds=${updated.sshKeyIds.size}")
    }

    suspend fun detachKey(serverId: String, keyId: String) {
        val current = dao.getById(serverId)?.toDomain() ?: return
        if (keyId !in current.sshKeyIds) return
        val updated = current.copy(sshKeyIds = current.sshKeyIds - keyId)
        dao.upsert(ServerEntity.fromDomain(updated))
    }

    suspend fun updateAgent(id: String, agent: ai.eight24family.conch.agent.Agent) {
        val current = dao.getById(id) ?: return
        dao.upsert(current.copy(agent = agent.name))
    }

    suspend fun delete(id: String) {
        chatSessionRepository?.deleteAllForServer(id)
        dao.deleteById(id)
        secretsStore.deleteServerSecret(id)
    }
}
