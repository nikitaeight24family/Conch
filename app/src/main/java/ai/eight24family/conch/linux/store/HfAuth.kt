package ai.eight24family.conch.linux.store

import ai.eight24family.conch.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * The owner's Hugging Face account, when he chooses to connect one.
 *
 * ⛔ WHY: the store showed gated repos and could not download them. Meta's
 * Llama, Google's Gemma and a good part of what people actually want answer
 * 401 to an anonymous pull — they are open weights behind a click-through
 * licence, and the click-through is an HF account. Without a token the shelf
 * is "eighteen models"; with one it is Hugging Face.
 *
 * ── WHAT THE TOKEN IS ALLOWED TO TOUCH ──
 *
 * The value lives in [ai.eight24family.conch.data.secrets.SecretsStore]
 * (Keystore-wrapped, device-bound, excluded from backup — the settings
 * DataStore is on the backup whitelist and would have carried it into the
 * cloud), and NOTHING but presence and the account name is ever read back for
 * the UI.
 *
 * ⛔ AND IT GOES TO huggingface.co ONLY. A model download 302s onto a signed
 * CDN URL (`cdn-lfs*.hf.co`), and `HttpURLConnection` copies request
 * properties across a redirect — so following redirects automatically would
 * hand the owner's credential to a host that neither needs nor should have
 * it. [open] therefore follows redirects BY HAND and attaches the header only
 * while the hop is still HF. The signed URL is already the authorisation for
 * that hop; that is the whole point of it being signed.
 */
object HfAuth {

    private const val TAG = "Conch-HfAuth"

    sealed interface State {
        data object Absent : State
        data class Connected(val user: String?) : State
    }

    private val _state = MutableStateFlow<State>(State.Absent)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Read the stored presence into [state]. Cheap; call on screen entry. */
    fun refresh() {
        val store = ServiceLocator.secretsStore
        _state.value = if (store.hasHfToken()) {
            State.Connected(store.loadHfUser())
        } else {
            State.Absent
        }
    }

    fun isConnected(): Boolean = ServiceLocator.secretsStore.hasHfToken()

    sealed interface ConnectResult {
        data class Ok(val user: String?) : ConnectResult
        /** HF refused the token — a typo, or one that was revoked. */
        data object Refused : ConnectResult
        data class Failed(val reason: String) : ConnectResult
    }

    /**
     * Validate a pasted token against HF and keep it only if HF accepts it.
     *
     * A token that is never checked is a silent 401 on the first gigabyte
     * download, blamed on the network. `whoami-v2` costs one request and
     * returns the account name, which is what the row then shows.
     */
    suspend fun connect(token: String): ConnectResult = withContext(Dispatchers.IO) {
        val clean = token.trim()
        if (clean.isEmpty()) return@withContext ConnectResult.Refused
        runCatching {
            val c = (URL("https://huggingface.co/api/whoami-v2")
                .openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Authorization", "Bearer $clean")
            }
            val code = c.responseCode
            if (code == 401 || code == 403) {
                c.disconnect()
                return@runCatching ConnectResult.Refused
            }
            if (code != 200) {
                c.disconnect()
                return@runCatching ConnectResult.Failed("Hugging Face answered $code")
            }
            val body = c.inputStream.use { it.reader().readText() }
            c.disconnect()
            val user = runCatching {
                Json.parseToJsonElement(body).jsonObject["name"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            val store = ServiceLocator.secretsStore
            store.saveHfToken(clean)
            user?.let { store.saveHfUser(it) }
            refresh()
            android.util.Log.i(TAG, "connected to hugging face as ${user ?: "(unnamed)"}")
            ConnectResult.Ok(user)
        }.getOrElse { ConnectResult.Failed(it.message ?: it.javaClass.simpleName) }
    }

    fun forget() {
        ServiceLocator.secretsStore.deleteHfToken()
        refresh()
        android.util.Log.i(TAG, "hugging face token forgotten")
    }

    /** Hugging Face's own hosts — the ONLY ones the token may reach. The CDN
     *  (`cdn-lfs-us-1.hf.co` and friends) is deliberately NOT here: its URLs
     *  are signed and must stay credential-free. */
    fun isHfApiHost(host: String): Boolean =
        host == "huggingface.co" || host.endsWith(".huggingface.co")

    /** Attach the token if there is one and the host is HF's. Used by the
     *  browse/resolve calls, which never redirect off-site. */
    fun authorize(conn: HttpURLConnection) {
        if (!isHfApiHost(conn.url.host)) return
        ServiceLocator.secretsStore.loadHfToken()?.let {
            conn.setRequestProperty("Authorization", "Bearer $it")
        }
    }

    /**
     * Open a GET, following redirects by hand so the credential stops at the
     * HF boundary. [rangeFrom] > 0 asks for a resume.
     *
     * Returns a CONNECTED connection whose `responseCode` is already known —
     * the caller reads it to decide append/restart, exactly as before.
     */
    fun open(url: String, rangeFrom: Long = 0L, hops: Int = 5): HttpURLConnection {
        var current = url
        var left = hops
        while (true) {
            val c = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                // BY HAND, on purpose — see the class comment.
                instanceFollowRedirects = false
                if (rangeFrom > 0L) setRequestProperty("Range", "bytes=$rangeFrom-")
            }
            authorize(c)
            val code = c.responseCode
            if (code !in 300..399) return c
            val next = c.getHeaderField("Location")
            c.disconnect()
            if (next.isNullOrBlank() || left-- <= 0) {
                throw IllegalStateException("too many redirects fetching a model file")
            }
            current = URL(URL(current), next).toString()
        }
    }
}
