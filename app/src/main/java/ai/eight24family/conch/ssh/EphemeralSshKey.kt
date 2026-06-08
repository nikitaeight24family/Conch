package ai.eight24family.conch.ssh

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Hardware-backed, per-server **ephemeral device key** that lets a FIDO/SK
 * server reconnect SILENTLY across a network handoff — no second tap.
 *
 * This is the **opt-in** "seamless reconnect" mechanism (Settings → Connection,
 * OFF by default). The user's workflow: tap the security key at home, then go
 * out *leaving the key at home* — so a real Wi-Fi⇄cellular handoff can't be
 * re-authenticated with the (absent) key. Reconnecting without the key requires
 * the server to accept a NON-key credential; this is that credential, made as
 * clean as possible:
 *
 *  1. First connect = real FIDO tap (unchanged).
 *  2. If the setting is on, the app mints a throwaway **ECDSA P-256 key in the
 *     Android Keystore** (private half non-exportable, never leaves secure
 *     hardware — the app never handles the key VALUE).
 *  3. Its public half is added to `~/.ssh/authorized_keys` with a server-side
 *     **`expiry-time`** so it self-destructs after N days even if the app never
 *     cleans up. The line carries a unique `sshai-ephemeral-<serverId>` comment.
 *  4. Reconnects (network change / cold start) authenticate with this key via
 *     standard `ecdsa-sha2-nistp256` publickey auth — no tap.
 *
 * Revoke = toggle the setting off (deletes the local keys) or strip the
 * `sshai-ephemeral` line from the server. Everything here is best-effort.
 */
object EphemeralSshKey {
    private const val TAG = "SshAi-EphKey"
    private const val ALIAS_PREFIX = "sshai_eph_"
    private fun alias(serverId: String) = ALIAS_PREFIX + serverId
    /** Unique comment on the authorized_keys line — used to find/strip our key
     *  (`grep sshai-ephemeral ~/.ssh/authorized_keys`). A server's real FIDO
     *  line never contains this string. */
    fun markerComment(serverId: String) = "sshai-ephemeral-$serverId"

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun exists(serverId: String): Boolean =
        runCatching { keyStore().containsAlias(alias(serverId)) }.getOrDefault(false)

    /** Get-or-create the per-server Keystore key. Returns true if it now exists. */
    fun ensure(serverId: String): Boolean = runCatching {
        if (exists(serverId)) return true
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        kpg.initialize(
            KeyGenParameterSpec.Builder(alias(serverId), KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                // No setUserAuthenticationRequired — silent signing is the point
                // (reconnect must not prompt). Still device-bound + non-exportable.
                .build()
        )
        kpg.generateKeyPair()
        android.util.Log.d(TAG, "minted device key for $serverId")
        true
    }.getOrElse {
        android.util.Log.w(TAG, "ensure($serverId) failed: ${it.javaClass.simpleName}: ${it.message}")
        false
    }

    /** Destroy the local private key for one server → revokes silent reconnect
     *  for it (the authorized_keys line, if any, becomes dead text + expires). */
    fun delete(serverId: String) {
        runCatching { keyStore().deleteEntry(alias(serverId)) }
    }

    /** Server ids that currently have a local device key. NON-destructive —
     *  used by Settings to show the user where seamless reconnect is armed
     *  (presence only; the private key value never leaves secure hardware). */
    fun serverIdsWithKeys(): List<String> = runCatching {
        keyStore().aliases().toList()
            .filter { it.startsWith(ALIAS_PREFIX) }
            .map { it.removePrefix(ALIAS_PREFIX) }
    }.getOrDefault(emptyList())

    /** Wipe ALL device keys (toggle-off / revoke). Returns the server ids that
     *  had a key, so the caller can strip lines from live servers. */
    fun deleteAll(): List<String> = runCatching {
        val ks = keyStore()
        val ids = serverIdsWithKeys()
        ids.forEach { runCatching { ks.deleteEntry(alias(it)) } }
        ids
    }.getOrDefault(emptyList())

    private fun publicKey(serverId: String): ECPublicKey? =
        runCatching { keyStore().getCertificate(alias(serverId))?.publicKey as? ECPublicKey }.getOrNull()

    /** Standard SSH SHA-256 fingerprint of the device PUBLIC key
     *  (`SHA256:<base64>`, same as `ssh-keygen -lf`). Shown in Settings so the
     *  user can SEE a key exists and which one it is. Public-key material only —
     *  never the private value (which can't leave secure hardware anyway). */
    fun fingerprint(serverId: String): String? = runCatching {
        val pub = publicKey(serverId) ?: return null
        val blob = Buffer.PlainBuffer().putPublicKey(pub).compactData
        val md = java.security.MessageDigest.getInstance("SHA-256").digest(blob)
        "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(md)
    }.getOrNull()

    private fun privateKey(serverId: String): PrivateKey? =
        runCatching { keyStore().getKey(alias(serverId), null) as? PrivateKey }.getOrNull()

    /** The bare `ecdsa-sha2-nistp256 <base64> sshai-ephemeral-<id>` portion
     *  (NO `expiry-time` — the server computes + prepends that against its own
     *  clock, see SshConnectionPool.installEphemeralAsync). */
    fun keyPart(serverId: String): String? {
        val pub = publicKey(serverId) ?: return null
        return authorizedKeyLineFor(pub, markerComment(serverId))
    }

    /**
     * Build the `<type> <base64> <comment>` authorized_keys line for [pub] using
     * SSHJ'S OWN encoder, so the line is byte-identical to the pubkey sshj
     * presents during auth. The earlier hand-rolled blob mismatched → the
     * server never matched our key → "Exhausted available authentication
     * methods" (the Keystore DID sign — pubkey mismatch was the only failure
     * left). Pure + Keystore-free (takes a [PublicKey], uses java.util.Base64)
     * so it's unit-testable on the JVM against a real embedded sshd.
     */
    internal fun authorizedKeyLineFor(pub: java.security.PublicKey, comment: String): String? =
        runCatching {
            val blob = Buffer.PlainBuffer().putPublicKey(pub).compactData
            val b64 = java.util.Base64.getEncoder().encodeToString(blob)
            "${KeyType.fromKey(pub)} $b64 $comment"
        }.getOrNull()

    /** sshj [KeyProvider] backed by the Keystore key. sshj signs the challenge
     *  via JCA `Signature`, which routes to the AndroidKeyStore provider, so the
     *  private key never leaves secure hardware. */
    fun keyProvider(serverId: String): KeyProvider? {
        val priv = privateKey(serverId) ?: return null
        val pub = publicKey(serverId) ?: return null
        return object : KeyProvider {
            override fun getPrivate(): PrivateKey = priv
            override fun getPublic(): PublicKey = pub
            override fun getType(): KeyType = KeyType.ECDSA256
        }
    }
}
