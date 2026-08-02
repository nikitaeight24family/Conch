package ai.eight24family.conch.ssh.securitykey

import ai.eight24family.conch.domain.SshKey
import ai.eight24family.conch.domain.SshKeyType
import java.util.Base64

/**
 * Helpers that turn the persisted [SshKey] row of an SK key back into
 * the SSH wire-format bits the userauth packet needs:
 *
 *  - the algorithm name (`sk-ssh-ed25519@openssh.com` /
 *    `sk-ecdsa-sha2-nistp256@openssh.com`),
 *  - the public-key blob (binary, sent on the wire — the same bytes
 *    whose Base64 encoding lives between the algo prefix and the
 *    comment in `authorized_keys`).
 *
 * We deliberately store the OpenSSH-text form in [SshKey.publicKey]
 * because that's what users copy-paste; ripping the blob back out at
 * auth time is cheap and keeps the storage shape simple. (No second
 * column with the same data in different encoding.)
 */
internal object SkPublicKey {

    fun algorithmName(type: SshKeyType): String = when (type) {
        SshKeyType.SK_ED25519 -> "sk-ssh-ed25519@openssh.com"
        SshKeyType.SK_ECDSA_NISTP256 -> "sk-ecdsa-sha2-nistp256@openssh.com"
        else -> error("not an SK key type: $type")
    }

    /**
     * Decode the second whitespace-separated token of `authorized_keys`
     * line into raw bytes. Throws if the line doesn't have an algo
     * prefix matching an SK type — caller has already verified
     * [SshKey.type] is `SK_*`, so this is a programmer-error path.
     */
    fun blobBytes(key: SshKey): ByteArray {
        val parts = key.publicKey.trim().split(Regex("\\s+"), limit = 3)
        require(parts.size >= 2) { "malformed SK public key line: ${key.publicKey.take(40)}…" }
        val expected = algorithmName(key.type)
        require(parts[0] == expected) {
            "SK key row says type=${key.type} but the OpenSSH text has algo=${parts[0]}"
        }
        // Fix: bound input size before Base64 decode — real SK blobs are <200 chars
        // (Ed25519 ~80B, ECDSA-P256 ~140B); cap at 4096 to refuse hostile/malformed input.
        if (parts[1].length > 4096) {
            throw IllegalArgumentException("public-key blob too large: ${parts[1].length} chars")
        }
        return Base64.getDecoder().decode(parts[1])
    }
}
