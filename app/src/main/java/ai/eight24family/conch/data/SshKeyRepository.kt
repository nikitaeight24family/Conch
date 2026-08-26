package ai.eight24family.conch.data

import ai.eight24family.conch.data.db.SshKeyDao
import ai.eight24family.conch.data.db.SshKeyEntity
import ai.eight24family.conch.data.secrets.SecretsStore
import ai.eight24family.conch.domain.SshKey
import ai.eight24family.conch.domain.SshKeySecrets
import ai.eight24family.conch.domain.SshKeyType
import ai.eight24family.conch.domain.SecurityKeyTransport
import ai.eight24family.conch.domain.SshKeySecurityInfo
import ai.eight24family.conch.ssh.SshKeyGenerator
import ai.eight24family.conch.ssh.pickKeyProvider
import ai.eight24family.conch.ssh.securitykey.SecurityKeyRegistrar
import ai.eight24family.conch.util.SilentlyTry
import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import java.security.MessageDigest
import java.security.PublicKey
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.UUID

class SshKeyRepository(
    private val dao: SshKeyDao,
    private val secretsStore: SecretsStore
) {
    fun observeKeys(): Flow<List<SshKey>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getById(id: String): SshKey? = dao.getById(id)?.toDomain()

    suspend fun loadSecret(id: String): SshKeySecrets? = secretsStore.loadKeySecret(id)

    suspend fun generateEd25519(name: String, comment: String): SshKey {
        val generated = SshKeyGenerator.generateEd25519(comment.ifBlank { "conch@android" })
        val key = SshKey(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "Key ${System.currentTimeMillis() / 1000}" },
            type = SshKeyType.ED25519,
            publicKey = generated.publicKeyOpenSsh,
            fingerprint = generated.fingerprintSha256,
            comment = comment,
            createdAt = System.currentTimeMillis()
        )
        dao.upsert(SshKeyEntity.fromDomain(key))
        secretsStore.saveKeySecret(key.id, SshKeySecrets(privateKeyPem = generated.privateKeyOpenSsh))
        return key
    }

    /**
     * Outcome of an import attempt. The UI uses these to decide whether to
     * prompt for a passphrase, ask the user to pick a different file, or
     * close the dialog with success.
     *
     * Distinguishing `EncryptedNoPassphrase` from `WrongPassphrase` matters:
     * the first time around we don't even ask for one (most keys aren't
     * encrypted), and only re-prompt with a passphrase field when sshj
     * tells us decryption is required.
     */
    sealed interface ImportOutcome {
        data class Ok(val key: SshKey) : ImportOutcome
        data object EncryptedNoPassphrase : ImportOutcome
        data object WrongPassphrase : ImportOutcome
        data class Unsupported(val reason: String) : ImportOutcome
    }

    /**
     * Parse [privateKeyPem] (OpenSSH-v1 modern format OR legacy PEM RSA/DSA),
     * derive its public key + SHA-256 fingerprint, and persist both the
     * domain row and the encrypted secret. We deliberately reject keys we
     * couldn't parse — saving an unparseable PEM means the user picks it
     * later, attempts a connection, and sees an obscure SSH-time error
     * instead of a clear "this isn't a key file" at import time.
     */
    suspend fun importKey(
        name: String,
        privateKeyPem: String,
        passphrase: String?,
        comment: String,
    ): ImportOutcome {
        val info = try {
            extractPublicInfo(privateKeyPem, passphrase?.takeIf { it.isNotEmpty() })
        } catch (t: Throwable) {
            // sshj surfaces "passphrase" / "decrypt" / "BAD_PASSPHRASE" in the
            // exception chain when the cipher fails. Detect that vs. a
            // structurally-broken file via substring sniff (the exception
            // hierarchy isn't stable enough to switch on).
            val msg = (t.message ?: "") + " " + (t.cause?.message ?: "")
            return when {
                msg.contains("passphrase", ignoreCase = true) ||
                msg.contains("decrypt", ignoreCase = true) ||
                msg.contains("BAD_PASSPHRASE", ignoreCase = true) -> {
                    if (passphrase.isNullOrEmpty()) ImportOutcome.EncryptedNoPassphrase
                    else ImportOutcome.WrongPassphrase
                }
                else -> ImportOutcome.Unsupported(t.message ?: t.javaClass.simpleName)
            }
        }
        val typeFromAlgo = mapAlgoToType(info.algorithm)
        val key = SshKey(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "Imported ${System.currentTimeMillis() / 1000}" },
            type = typeFromAlgo,
            publicKey = info.openSshLine,
            fingerprint = info.fingerprint,
            comment = comment,
            createdAt = System.currentTimeMillis()
        )
        dao.upsert(SshKeyEntity.fromDomain(key))
        secretsStore.saveKeySecret(key.id, SshKeySecrets(privateKeyPem, passphrase?.takeIf { it.isNotEmpty() }))
        return ImportOutcome.Ok(key)
    }

    private fun mapAlgoToType(algo: String): SshKeyType =
        when {
            algo.equals("Ed25519", ignoreCase = true) -> SshKeyType.ED25519
            algo.equals("EdDSA", ignoreCase = true) -> SshKeyType.ED25519
            algo.equals("RSA", ignoreCase = true) -> SshKeyType.RSA
            algo.contains("ECDSA", ignoreCase = true) || algo.equals("EC", ignoreCase = true) -> SshKeyType.ECDSA
            algo.equals("DSA", ignoreCase = true) -> SshKeyType.DSA
            else -> SshKeyType.UNKNOWN
        }

    suspend fun rename(id: String, newName: String) {
        val current = dao.getById(id) ?: return
        dao.upsert(current.copy(name = newName))
    }

    /**
     * Persist a credential we read off a FIDO authenticator via
     * `credentialManagement.enumerateCredentials`. No secret goes into
     * [SecretsStore] — the private bytes live on the token.
     */
    suspend fun addSecurityKey(
        nameHint: String,
        cred: SecurityKeyRegistrar.ImportedCredential,
        transport: SecurityKeyTransport,
    ): SshKey {
        // Dedup by fingerprint+credId — recovery flow used to create a fresh
        // UUID on every retry tap, leaving the keychain with N copies of the
        // same physical credential. If we already have a row matching this
        // (fingerprint, credentialId) pair we return that row's domain
        // object unchanged so callers' attach-to-server logic still works.
        val existing = dao.getAll().firstOrNull {
            it.fingerprint == cred.fingerprint && it.credentialIdBase64 == cred.credentialIdBase64
        }
        if (existing != null) {
            android.util.Log.d("SshAi-KeyRepo", "addSecurityKey: dedup → ${existing.id} (already in DB)")
            return existing.toDomain()
        }
        val typeFromAlgo = when (cred.algorithm) {
            SecurityKeyRegistrar.Algorithm.ED25519 -> SshKeyType.SK_ED25519
            SecurityKeyRegistrar.Algorithm.ECDSA_NISTP256 -> SshKeyType.SK_ECDSA_NISTP256
        }
        val ts = System.currentTimeMillis()
        val name = listOfNotNull(
            nameHint.takeIf { it.isNotBlank() },
            cred.displayName?.takeIf { it.isNotBlank() },
        ).firstOrNull() ?: "Security key ${ts / 1000}"
        val comment = cred.displayName ?: "imported"
        val key = SshKey(
            id = UUID.randomUUID().toString(),
            name = name,
            type = typeFromAlgo,
            publicKey = "${cred.openSshLine} $comment".trim(),
            fingerprint = cred.fingerprint,
            comment = comment,
            createdAt = ts,
            securityInfo = SshKeySecurityInfo(
                credentialIdBase64 = cred.credentialIdBase64,
                application = cred.application,
                transport = transport,
            ),
        )
        dao.upsert(SshKeyEntity.fromDomain(key))
        return key
    }

    /**
     * Heal a security-key row whose stored `application` (rpId) drifted
     * from the actual rpId baked into the FIDO credential on the
     * physical token. Triggered by [SkInlineTouchDialog]'s
     * `runEnumerateAndHoldSession` after matching a candidate credId
     * to the enumerated set and noticing the rpId we saw on the key
     * is different from what the DB has.
     *
     * Common cause: an old `registerNewCredential` call landed with the
     * default `rpId = "ssh:"`, then the user later registered the same
     * credId elsewhere with a real rpId like `"ssh:eight24"`. The
     * connect flow then couldn't find a matching cred because it was
     * filtering by `"ssh:"`. The forward fix (match by credId) gets
     * the user past the immediate failure; this heal prevents the
     * "app drift" warning from re-logging on every connect.
     *
     * No-op if [credentialIdBase64] isn't found OR the stored
     * application already equals [realApplication].
     */
    suspend fun healSecurityKeyApplication(
        credentialIdBase64: String,
        realApplication: String,
    ) {
        val matches = dao.getAll().filter { it.credentialIdBase64 == credentialIdBase64 }
        for (entity in matches) {
            if (entity.application == realApplication) continue
            android.util.Log.i(
                "SshAi-KeyRepo",
                "healSecurityKeyApplication(${entity.id}): app '${entity.application}' → '$realApplication'",
            )
            dao.upsert(entity.copy(application = realApplication))
        }
    }

    suspend fun delete(id: String) {
        val before = dao.getById(id)
        android.util.Log.i("SshAi-KeyRepo", "delete(id=$id) before: name=${before?.name} type=${before?.type}")
        dao.deleteById(id)
        secretsStore.deleteKeySecret(id)
        val after = dao.getById(id)
        android.util.Log.i("SshAi-KeyRepo", "delete(id=$id) after: ${if (after == null) "GONE" else "STILL THERE name=${after.name}"}")
    }

    /**
     * Find private keys in encrypted prefs that have no matching DB row (orphaned
     * after a destructive migration) and rebuild the DB rows from the PEM, so the
     * user does not lose access.
     */
    suspend fun recoverOrphans(): Int {
        val knownIds = dao.getAll().map { it.id }.toSet()
        val orphans = secretsStore.getAllKeyIds() - knownIds
        if (orphans.isEmpty()) return 0
        val ts = System.currentTimeMillis()
        val fmt = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
        var recovered = 0
        for (id in orphans) {
            val secret = secretsStore.loadKeySecret(id) ?: continue
            val info = SilentlyTry.logged("SshAi-KeyRepo", "extract public info from orphan key") { extractPublicInfo(secret.privateKeyPem, secret.passphrase) }
            if (info != null) {
                val key = SshKey(
                    id = id,
                    name = "Recovered · ${fmt.format(Date(ts))}",
                    type = SshKeyType.ED25519,
                    publicKey = info.openSshLine,
                    fingerprint = info.fingerprint,
                    comment = "recovered",
                    createdAt = ts
                )
                dao.upsert(SshKeyEntity.fromDomain(key))
                recovered++
            }
        }
        return recovered
    }

    private data class PublicInfo(
        val openSshLine: String,
        val fingerprint: String,
        val algorithm: String,
    )

    /**
     * Best-effort public-key + SHA-256 fingerprint extraction. Tries the
     * format auto-detected by [pickKeyProvider] (handles modern OpenSSH-v1
     * AND legacy PEM); throws if the format isn't recognised, the
     * passphrase is wrong, or the file is not a private key at all. Caller
     * maps the throw into an [ImportOutcome].
     */
    private fun extractPublicInfo(pem: String, passphrase: String?): PublicInfo {
        val provider = pickKeyProvider(pem, passphrase)
        val pub: PublicKey = provider.public
        val keyType = KeyType.fromKey(pub)
        val buf = Buffer.PlainBuffer()
        keyType.putPubKeyIntoBuffer(pub, buf)
        val pubBytes = buf.compactData
        val b64 = Base64.getEncoder().encodeToString(pubBytes)
        val openSsh = "$keyType $b64 conch@android"
        val digest = MessageDigest.getInstance("SHA-256").digest(pubBytes)
        val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        return PublicInfo(openSsh, fingerprint, pub.algorithm)
    }
}
