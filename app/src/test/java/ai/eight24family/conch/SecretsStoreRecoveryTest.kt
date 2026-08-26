package ai.eight24family.conch

import ai.eight24family.conch.data.secrets.SecretsStore
import ai.eight24family.conch.domain.ServerSecrets
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Self-healing contract of the encrypted secrets store (Google Play's
 * device-transfer quality requirement, 2026): a blob copied from another
 * device by an OEM migration tool is undecryptable HERE forever — the app
 * must reset it and come up, not crash-loop on every launch. But a SINGLE
 * failure is a known transient Keystore flake and must NOT wipe anything.
 */
class SecretsStoreRecoveryTest {

    /** Records deleteSharedPreferences calls; everything else is inert
     *  (android.jar methods return defaults in unit tests). */
    private class RecordingContext : ContextWrapper(null) {
        val deleted = mutableListOf<String>()
        override fun deleteSharedPreferences(name: String): Boolean {
            deleted += name
            return true
        }
    }

    /** Minimal in-memory SharedPreferences — just what SecretsStore touches. */
    private class FakePrefs : SharedPreferences {
        val map = HashMap<String, String?>()
        override fun getAll(): Map<String, *> = map.toMap()
        override fun getString(key: String, defValue: String?): String? = map[key] ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = defValue
        override fun getLong(key: String, defValue: Long) = defValue
        override fun getFloat(key: String, defValue: Float) = defValue
        override fun getBoolean(key: String, defValue: Boolean) = defValue
        override fun contains(key: String) = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String, value: String?) = apply { map[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = this
            override fun putInt(key: String, value: Int) = this
            override fun putLong(key: String, value: Long) = this
            override fun putFloat(key: String, value: Float) = this
            override fun putBoolean(key: String, value: Boolean) = this
            override fun remove(key: String) = apply { map.remove(key) }
            override fun clear() = apply { map.clear() }
            override fun commit() = true
            override fun apply() {}
        }
        override fun registerOnSharedPreferenceChangeListener(
            l: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {}
        override fun unregisterOnSharedPreferenceChangeListener(
            l: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {}
    }

    @Test
    fun `single transient failure heals by retry and wipes NOTHING`() {
        val ctx = RecordingContext()
        var calls = 0
        val store = SecretsStore(ctx) {
            calls++
            if (calls == 1) throw GeneralSecurityException("transient keystore flake")
            FakePrefs()
        }
        assertEquals(2, calls)
        assertTrue("a transient flake must not delete the store", ctx.deleted.isEmpty())
        // The healed store is functional.
        store.saveServerSecret("s1", ServerSecrets(password = "p"))
        assertEquals("p", store.loadServerSecret("s1").password)
    }

    @Test
    fun `undecryptable-after-migration blob is deleted once and the store starts fresh`() {
        val ctx = RecordingContext()
        var calls = 0
        val store = SecretsStore(ctx) {
            calls++
            // Fails on the original AND on the retry — the foreign-blob
            // signature. Succeeds only after the reset.
            if (calls <= 2) throw IOException("keyset decrypt failed")
            FakePrefs()
        }
        assertEquals(3, calls)
        assertEquals(listOf("encrypted_servers"), ctx.deleted)
        store.saveKeySecret("k1", ai.eight24family.conch.domain.SshKeySecrets(privateKeyPem = "PEM", passphrase = null))
        assertEquals("PEM", store.loadKeySecret("k1")?.privateKeyPem)
    }

    @Test(expected = IllegalStateException::class)
    fun `failure that survives the reset propagates - a real bug must crash, not loop silently`() {
        SecretsStore(RecordingContext()) { throw IllegalStateException("real bug") }
    }
}
