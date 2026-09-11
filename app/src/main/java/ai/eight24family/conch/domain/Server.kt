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
    /**
     * This server's accent colour as `#RRGGBB` — its name is drawn in it
     * everywhere in the app. Random at creation, editable in server settings.
     * Null ⇒ derive a stable colour from [id] (see
     * [ai.eight24family.conch.ui.theme.ServerAccent.derive]), so servers saved
     * before this field existed are already colour-coded without a data write.
     */
    val colorHex: String? = null,
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
    /**
     * A SOFTWARE key attached to a server that also has a security key — the
     * way in that costs no tap.
     *
     * [privateKeyPem] stays null on an SK row, because every `skKeys.isNotEmpty()`
     * branch in the app reads it as "this server authenticates with the physical
     * key" and quietly filling the PEM there would reroute the deliberate FIDO
     * session the owner chose. This field is the other half of the answer: the
     * credential exists, it just isn't the primary one, and the tapless ladder
     * ([ai.eight24family.conch.ssh.SshConnectionPool.taplessConnect]) may reach
     * for it when nobody is there to touch anything.
     *
     * Before this existed the whole server was decided by `keys.first()`, so a
     * row whose security key happened to be added first ignored a perfectly good
     * passwordless key and demanded a tap — and the key order is documented on
     * [Server.sshKeyIds] as purely cosmetic.
     */
    val taplessPem: String? = null,
    val taplessPassphrase: String? = null,
)
