package ai.eight24family.conch.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import ai.eight24family.conch.domain.SecurityKeyTransport
import ai.eight24family.conch.domain.SshKey
import ai.eight24family.conch.domain.SshKeySecurityInfo
import ai.eight24family.conch.domain.SshKeyType

@Entity(tableName = "ssh_keys")
data class SshKeyEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val publicKey: String,
    val fingerprint: String,
    val comment: String,
    val createdAt: Long,
    /**
     * The three columns below populated only for FIDO security keys.
     * Software keys leave them null. Defaulted at the column level so
     * inserts from the software-key path don't have to mention them.
     */
    @ColumnInfo(defaultValue = "NULL") val credentialIdBase64: String? = null,
    @ColumnInfo(defaultValue = "NULL") val application: String? = null,
    @ColumnInfo(defaultValue = "NULL") val transport: String? = null,
) {
    fun toDomain(): SshKey {
        val parsedType = runCatching { SshKeyType.valueOf(type) }.getOrDefault(SshKeyType.ED25519)
        val secInfo = if (
            (parsedType == SshKeyType.SK_ED25519 || parsedType == SshKeyType.SK_ECDSA_NISTP256) &&
            credentialIdBase64 != null && application != null
        ) {
            SshKeySecurityInfo(
                credentialIdBase64 = credentialIdBase64,
                application = application,
                transport = runCatching {
                    SecurityKeyTransport.valueOf(transport ?: "EITHER")
                }.getOrDefault(SecurityKeyTransport.EITHER),
            )
        } else null
        return SshKey(
            id = id,
            name = name,
            type = parsedType,
            publicKey = publicKey,
            fingerprint = fingerprint,
            comment = comment,
            createdAt = createdAt,
            securityInfo = secInfo,
        )
    }

    companion object {
        fun fromDomain(k: SshKey) = SshKeyEntity(
            id = k.id,
            name = k.name,
            type = k.type.name,
            publicKey = k.publicKey,
            fingerprint = k.fingerprint,
            comment = k.comment,
            createdAt = k.createdAt,
            credentialIdBase64 = k.securityInfo?.credentialIdBase64,
            application = k.securityInfo?.application,
            transport = k.securityInfo?.transport?.name,
        )
    }
}
