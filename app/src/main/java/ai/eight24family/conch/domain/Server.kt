package ai.eight24family.conch.domain

import ai.eight24family.conch.agent.Agent

enum class AuthMethod { PASSWORD, KEY }

data class Server(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val authMethod: AuthMethod,
    val knownHostKey: String?,
    val agent: Agent = Agent.CLAUDE,
    /**
     * Flat list of ssh_key ids this server can authenticate with. The
     * order is purely cosmetic — sshj walks them all sending pubkey-only
     * test packets and the server picks whichever it recognises in
     * `authorized_keys`. Only the matching one's signer ever blocks for
     * the user's tap, so multi-key enrollment costs no extra friction.
     *
     * Empty list ⇒ server has no key bound (PASSWORD auth) OR the key
     * was deleted from the keychain since the row was saved (orphaned
     * ids are silently filtered when resolving secrets).
     */
    val sshKeyIds: List<String> = emptyList(),
)

data class ServerSecrets(
    val password: String? = null,
    val privateKeyPem: String? = null,
    val keyPassphrase: String? = null,
    /**
     * All FIDO security keys enrolled for this server. The pool builds
     * one [ai.eight24family.conch.ssh.securitykey.SkAuthPublickey] per
     * entry; sshj walks them and only the one matching the server's
     * `authorized_keys` triggers a tap. Empty for non-SK rows.
     */
    val skKeys: List<SshKey> = emptyList(),
)
