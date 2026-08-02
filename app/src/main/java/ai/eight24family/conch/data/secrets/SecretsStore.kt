package ai.eight24family.conch.data.secrets

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import ai.eight24family.conch.domain.ServerSecrets
import ai.eight24family.conch.domain.SshKeySecrets

class SecretsStore(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            "encrypted_servers",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveServerSecret(serverId: String, secrets: ServerSecrets) {
        prefs.edit().apply {
            secrets.password?.let { putString(passwordKey(serverId), it) } ?: remove(passwordKey(serverId))
            apply()
        }
    }

    fun loadServerSecret(serverId: String): ServerSecrets = ServerSecrets(
        password = prefs.getString(passwordKey(serverId), null)
    )

    fun deleteServerSecret(serverId: String) {
        prefs.edit().apply {
            remove(passwordKey(serverId))
            apply()
        }
    }

    fun saveKeySecret(keyId: String, secrets: SshKeySecrets) {
        prefs.edit().apply {
            putString(keyPemKey(keyId), secrets.privateKeyPem)
            secrets.passphrase?.let { putString(keyPassphraseKey(keyId), it) } ?: remove(keyPassphraseKey(keyId))
            apply()
        }
    }

    fun loadKeySecret(keyId: String): SshKeySecrets? {
        val pem = prefs.getString(keyPemKey(keyId), null) ?: return null
        return SshKeySecrets(
            privateKeyPem = pem,
            passphrase = prefs.getString(keyPassphraseKey(keyId), null)
        )
    }

    fun deleteKeySecret(keyId: String) {
        prefs.edit().apply {
            remove(keyPemKey(keyId))
            remove(keyPassphraseKey(keyId))
            apply()
        }
    }

    fun getAllKeyIds(): Set<String> = prefs.all.keys
        .filter { it.startsWith("keypem:") }
        .map { it.removePrefix("keypem:") }
        .toSet()

    private fun passwordKey(id: String) = "pwd:$id"
    private fun keyPemKey(id: String) = "keypem:$id"
    private fun keyPassphraseKey(id: String) = "keypass:$id"
}
