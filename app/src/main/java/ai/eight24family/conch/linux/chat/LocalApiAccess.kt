package ai.eight24family.conch.linux.chat

import ai.eight24family.conch.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.security.SecureRandom

/**
 * Who may talk to the model on this phone.
 *
 * ⛔ THE PORT WAS OPEN TO EVERY APP ON THE DEVICE, AND THAT IS NOT A
 * THEORETICAL HOLE. Android does not isolate loopback between apps: any
 * installed app holding `INTERNET` (which is nearly all of them) can connect
 * to another app's listening socket on 127.0.0.1. That is exactly how Meta's
 * and Yandex's SDKs were caught stitching browser identity to app identity
 * through localhost in June 2025. `llama-server` was serving
 * `/v1/chat/completions` unauthenticated for as long as a model was loaded —
 * so a neighbouring app could spend the owner's battery, read whatever it
 * asked the model, and leave nothing behind for him to see. In an app whose
 * store listing, landing page, About screen and privacy policy all promise
 * "no network but your own servers", that could not stand.
 *
 * So the engine now demands a key, and this object owns the keys:
 *
 *  - [ownKey] — Conch's own, minted once and kept. The in-app chat, the speed
 *    probe, the telemetry and the CLI agents in the phone's Linux all send it.
 *  - one key per GRANTED third-party app, minted when the owner allows it on
 *    the consent screen and destroyed when he revokes it.
 *
 * ── AND THAT IS ALSO THE PLATFORM DOOR ──
 *
 * The same gate that closes the hole is what makes the local engine usable BY
 * other apps deliberately: a keyboard, a notes app, a launcher or a Tasker
 * script asks once (see `LocalModelAccessActivity`), the owner allows it, and
 * from then on it speaks ordinary OpenAI-compatible HTTP to a model running on
 * the phone — no SDK, no cloud, no key of its own to buy. `--api-key-file`
 * takes one key per line, so every app gets its OWN secret: one app's leaked
 * token authorizes only that app, and revoking it leaves the others alone.
 *
 * ⚠ THE ENGINE READS THE KEY FILE ONLY AT STARTUP. A new grant or a
 * revocation therefore has to be followed by
 * [ai.eight24family.conch.linux.LocalLlmEngine.restartForKeys] when a model is
 * serving, or the change is a promise the port has not heard about.
 */
object LocalApiAccess {

    private const val TAG = "Conch-LocalApi"

    /** What a granted app is handed. Everything here is public knowledge to
     *  that app — it is what it needs to make a request. */
    const val BASE_URL_EXTRA = "base_url"
    const val API_KEY_EXTRA = "api_key"
    const val MODEL_EXTRA = "model"

    @Serializable
    data class Grant(
        /** The caller's package, as the SYSTEM reported it — never a name the
         *  caller told us. See `LocalModelAccessActivity`. */
        val pkg: String,
        /** Its user-visible label at grant time, for the revoke list. */
        val label: String,
        val token: String,
        val grantedAtMs: Long,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _grants = MutableStateFlow<List<Grant>>(emptyList())
    val grants: StateFlow<List<Grant>> = _grants.asStateFlow()

    @Volatile private var loaded = false

    private fun dir(): File =
        File(ServiceLocator.appContext.filesDir, "llm").apply { mkdirs() }

    private fun ownKeyFile(): File = File(dir(), "api-own-key")

    private fun grantsFile(): File = File(dir(), "api-grants.json")

    /** The file the engine is launched with. */
    fun keyFile(): File = File(dir(), "api-keys.txt")

    /**
     * Conch's own key. Minted once and KEPT: a CLI session in the phone's
     * Linux holds it in its environment for the life of that session, so
     * rolling it on every engine start would 401 every chat that outlived one
     * restart.
     */
    val ownKey: String by lazy {
        // No Application ⇒ no phone: a unit test has no files to keep a key in
        // and no engine listening to authenticate against, so a process-local
        // secret is the honest answer rather than a crash. On a device this
        // branch is unreachable — ConchApp.onCreate runs first.
        val f = runCatching { ownKeyFile() }.getOrNull() ?: return@lazy mintToken()
        val existing = runCatching { f.readText().trim() }.getOrNull()
        if (!existing.isNullOrBlank()) {
            existing
        } else {
            mintToken().also { k ->
                runCatching { f.writeText(k) }.onFailure {
                    android.util.Log.w(TAG, "could not persist own key: ${it.message}")
                }
            }
        }
    }

    /**
     * 32 bytes of urandom, base64url — no comma and no newline, because the
     * key file is line-based and `--api-key` is comma-separated.
     *
     * `java.util.Base64` (API 26+, our floor) rather than `android.util` on
     * purpose: the Android one is a stub in unit tests (`returnDefaultValues`)
     * and returned null, which NPE'd every test that builds a codex command
     * line. A pure-JVM encoder works the same on both sides.
     */
    private fun mintToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            // Same reasoning as [ownKey]: without an Application there is
            // nothing to read, and an empty grant set is the truth.
            _grants.value = runCatching {
                json.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(Grant.serializer()),
                    grantsFile().readText(),
                )
            }.getOrDefault(emptyList())
            loaded = true
        }
    }

    fun all(): List<Grant> { ensureLoaded(); return _grants.value }

    fun grantFor(pkg: String): Grant? = all().firstOrNull { it.pkg == pkg }

    /**
     * Allow [pkg] to use the local model, or hand back the grant it already
     * has — the owner's earlier decision stands until he revokes it, and
     * re-asking must not mint a second secret behind the first one's back.
     */
    fun grant(pkg: String, label: String): Grant {
        ensureLoaded()
        synchronized(this) {
            grantFor(pkg)?.let { return it }
            val g = Grant(pkg, label, mintToken(), System.currentTimeMillis())
            _grants.value = _grants.value + g
            save()
            return g
        }
    }

    fun revoke(pkg: String) {
        ensureLoaded()
        synchronized(this) {
            _grants.value = _grants.value.filterNot { it.pkg == pkg }
            save()
        }
    }

    private fun save() {
        runCatching {
            grantsFile().writeText(
                json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(Grant.serializer()),
                    _grants.value,
                ),
            )
        }.onFailure { android.util.Log.w(TAG, "could not save grants: ${it.message}") }
        writeKeyFile()
    }

    /**
     * Render the key file the engine authenticates against: Conch's own key
     * first, then one line per granted app.
     *
     * Written on every launch as well as on every change, so it can never
     * describe a grant set the owner has moved on from. The comments are
     * llama-server's own syntax (`#`) and make the file readable by whoever
     * has to debug this at 3 a.m.
     */
    fun writeKeyFile(): File {
        val f = keyFile()
        runCatching { f.writeText(renderKeyFile(ownKey, all())) }
            .onFailure { android.util.Log.w(TAG, "could not write key file: ${it.message}") }
        return f
    }

    /**
     * The file's exact text — pure, because the FORMAT is a contract with
     * llama-server (one key per line, `#` for comments) and a stray comma or
     * a wrapped line would turn a key into two keys that authenticate nothing.
     */
    internal fun renderKeyFile(own: String, grants: List<Grant>): String = buildString {
        appendLine("# Conch — keys the phone's inference engine accepts.")
        appendLine("# Rewritten by LocalApiAccess; edits here are lost on the next launch.")
        appendLine("# conch (this app)")
        appendLine(own)
        grants.forEach { g ->
            appendLine("# ${g.pkg} — allowed by the owner")
            appendLine(g.token)
        }
    }

    /** Put Conch's own key on a request to the engine. Every in-app caller of
     *  a non-public endpoint goes through this — `/health` and `/v1/models`
     *  are the only ones llama-server leaves open. */
    fun authorize(conn: HttpURLConnection) {
        conn.setRequestProperty("Authorization", "Bearer $ownKey")
    }
}
