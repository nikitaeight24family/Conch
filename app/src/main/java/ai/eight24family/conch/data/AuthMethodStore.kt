package ai.eight24family.conch.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ai.eight24family.conch.agent.Agent
import kotlinx.coroutines.flow.first

/**
 * Persists the user's auth-method choices (short method keys only — never
 * secrets; the credentials live on the server, see
 * [ai.eight24family.conch.agent.AuthSelector]):
 *
 *  - **ACTIVE** method per (serverId, agent) — set from the long-press
 *    switcher; applied to new sessions + general use of that agent.
 *  - **per-SESSION** method (sessionId → key) — so resuming a session uses
 *    the method it was BORN under. A Codex ChatGPT-authed rollout won't
 *    answer under API auth and vice-versa, so the birth method is immutable
 *    once bound; switching the active method later must not rewrite it.
 *
 * Absent choice ⇒ null ⇒ launch unchanged (CLI default). Opt-in by design.
 */
class AuthMethodStore(private val context: Context) {

    private val Context.authDataStore by preferencesDataStore(name = "auth_methods")

    suspend fun activeMethod(serverId: String, agent: Agent): String? =
        context.authDataStore.data.first()[activeKey(serverId, agent)]?.takeIf { it.isNotBlank() }

    suspend fun setActiveMethod(serverId: String, agent: Agent, methodKey: String?) {
        context.authDataStore.edit {
            if (methodKey.isNullOrBlank()) it.remove(activeKey(serverId, agent))
            else it[activeKey(serverId, agent)] = methodKey
        }
    }

    /** The active credential SLOT id per (server, agent) — which saved
     *  account is currently copied into the CLI's live cred path. Distinct
     *  from [activeMethod] (the method TYPE); a method like OAuth can hold
     *  several account slots. */
    suspend fun activeSlot(serverId: String, agent: Agent): String? =
        context.authDataStore.data.first()[slotKey(serverId, agent)]?.takeIf { it.isNotBlank() }

    suspend fun setActiveSlot(serverId: String, agent: Agent, slotId: String?) {
        context.authDataStore.edit {
            if (slotId.isNullOrBlank()) it.remove(slotKey(serverId, agent))
            else it[slotKey(serverId, agent)] = slotId
        }
    }

    suspend fun sessionMethod(sessionId: String?): String? {
        if (sessionId.isNullOrBlank()) return null
        return context.authDataStore.data.first()[sessionKey(sessionId)]?.takeIf { it.isNotBlank() }
    }

    /** Bind a session to a method ONCE — never overwrite (birth method is
     *  immutable). No-op when method is null (keeps the session on CLI
     *  default, unchanged). */
    suspend fun bindSessionIfAbsent(sessionId: String, methodKey: String?) {
        if (sessionId.isBlank() || methodKey.isNullOrBlank()) return
        context.authDataStore.edit {
            if (it[sessionKey(sessionId)] == null) it[sessionKey(sessionId)] = methodKey
        }
    }

    /** The method to apply for one invocation: the session's bound method
     *  wins (correctness on resume); else the agent's active method; else
     *  null → launch unchanged. */
    suspend fun resolve(serverId: String, agent: Agent, sessionId: String?): String? =
        sessionMethod(sessionId) ?: activeMethod(serverId, agent)

    private fun activeKey(serverId: String, agent: Agent) =
        stringPreferencesKey("active/$serverId/${agent.name}")

    private fun slotKey(serverId: String, agent: Agent) =
        stringPreferencesKey("slot/$serverId/${agent.name}")

    private fun sessionKey(sessionId: String) =
        stringPreferencesKey("session/$sessionId")
}
