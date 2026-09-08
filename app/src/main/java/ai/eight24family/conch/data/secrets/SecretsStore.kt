package ai.eight24family.conch.data.secrets

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import ai.eight24family.conch.domain.ServerSecrets
import ai.eight24family.conch.domain.SshKeySecrets

class SecretsStore(
    context: Context,
    /** Test seam: builds the backing prefs. Production default is the
     *  Keystore-backed EncryptedSharedPreferences below. */
    private val createPrefs: (Context) -> SharedPreferences = { ctx -> defaultEncryptedPrefs(ctx) },
) {

    private val prefs: SharedPreferences = openSelfHealing(context)

    /**
     * Open the encrypted store, healing the one unrecoverable state instead
     * of crash-looping the app on it.
     *
     * The file's keyset is wrapped by a master key that lives in THIS
     * device's Android Keystore and never leaves it. If the file arrives
     * from ANOTHER device — an OEM migration tool (Smart Switch and friends)
     * copying data our backup rules exclude — the wrapping key does not
     * exist here, `EncryptedSharedPreferences.create` throws, and it will
     * throw forever: the ciphertext is garbage on this device by design
     * (secrets are device-bound; see res/xml/data_extraction_rules.xml).
     * Without this handler that is a crash on EVERY launch — the app is
     * bricked until the user clears data by hand. That is exactly the
     * "unexpected closure" class Google Play's device-transfer quality
     * requirement (2026) tells apps to eliminate.
     *
     * One failure is NOT proof of that state: Android Keystore has known
     * transient flakes. So retry once; only a REPEAT failure deletes the
     * file — losing device-bound secrets that were already unreadable — and
     * starts fresh. Server rows survive in the Room DB; the user re-enters
     * passwords / re-imports keys.
     */
    private fun openSelfHealing(context: Context): SharedPreferences {
        runCatching { return createPrefs(context) }
        Thread.sleep(RETRY_DELAY_MS) // rare path; give a transient Keystore flake a beat
        val second = runCatching { createPrefs(context) }
        second.getOrNull()?.let { return it }
        android.util.Log.w(
            "Conch-Secrets",
            "encrypted store unreadable twice — resetting device-bound secrets " +
                "(expected only after a device migration copied a foreign blob)",
            second.exceptionOrNull(),
        )
        context.deleteSharedPreferences(PREFS_FILE)
        // A third failure is a real bug, not a foreign blob — let it propagate.
        return createPrefs(context)
    }

    companion object {
        /** Deliberately unprefixed: there is exactly one, and [getAllKeyIds]
         *  filters on "keypem:" so it can never mistake this for an SSH key. */
        private const val ADB_KEY = "adb-identity-pkcs8"
        private const val HF_TOKEN = "hf-token"
        private const val HF_USER = "hf-user"
        private const val PREFS_FILE = "encrypted_servers"
        private const val RETRY_DELAY_MS = 150L

        private fun defaultEncryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
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

    /**
     * The identity this phone's own ADB access is pinned to, PKCS#8, base64.
     *
     * ⚠ Losing it means pairing again — the device stores the matching public
     * key and recognises nothing else — so it lives with the other credentials
     * rather than in an ordinary file, and is generated exactly once.
     */
    fun saveAdbPrivateKey(pkcs8: ByteArray) {
        prefs.edit()
            .putString(ADB_KEY, android.util.Base64.encodeToString(pkcs8, android.util.Base64.NO_WRAP))
            .apply()
    }

    fun loadAdbPrivateKey(): ByteArray? = prefs.getString(ADB_KEY, null)
        ?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }

    /** Forget the identity — the next pairing starts from a new key. */
    fun deleteAdbPrivateKey() {
        prefs.edit().remove(ADB_KEY).apply()
    }

    /**
     * The owner's Hugging Face access token — what makes GATED repos (the
     * official Llama and Gemma weights among them) downloadable at all.
     *
     * ⛔ IT LIVES HERE AND NOWHERE ELSE. The obvious home, the settings
     * DataStore, is on the BACKUP WHITELIST (`data_extraction_rules.xml`), so
     * a token put there would ride into a cloud backup — the SEC-7 hole this
     * store exists to close. Here it is Keystore-wrapped, device-bound and
     * excluded from backup by the same whitelist that keeps the ssh keys out.
     *
     * Presence is the only thing any UI is allowed to read back
     * ([hasHfToken]); the value goes to huggingface.co and nowhere else.
     */
    fun saveHfToken(token: String) {
        prefs.edit().putString(HF_TOKEN, token).apply()
    }

    fun loadHfToken(): String? = prefs.getString(HF_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun hasHfToken(): Boolean = loadHfToken() != null

    fun deleteHfToken() {
        prefs.edit().remove(HF_TOKEN).apply()
    }

    /** The account name the token resolved to, for the row that says whose
     *  it is. Not a secret — and never a substitute for asking HF again. */
    fun saveHfUser(name: String) {
        prefs.edit().putString(HF_USER, name).apply()
    }

    fun loadHfUser(): String? = prefs.getString(HF_USER, null)?.takeIf { it.isNotBlank() }

    private fun passwordKey(id: String) = "pwd:$id"
    private fun keyPemKey(id: String) = "keypem:$id"
    private fun keyPassphraseKey(id: String) = "keypass:$id"
}
