package ai.eight24family.conch.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK, CUSTOM }

/** SharedPreferences file holding the few flags we need to read SYNCHRONOUSLY
 *  in `Application.onCreate()` (Sentry init can't wait on a DataStore Flow).
 *  Default values are mirrored into both: DataStore for the UI to bind via
 *  Compose, and SharedPreferences for fast bootstrap reads. */
private const val FAST_PREFS_NAME = "ssh_ai_bootstrap_prefs"
private const val KEY_CRASH_REPORTING = "crash_reporting_enabled"

/** Read crash-reporting opt-in synchronously, from [Context]. Default = true.
 *  Used by [SshAiApp] when deciding whether to init Sentry; called BEFORE any
 *  ViewModel exists, so we can't go through DataStore. */
fun isCrashReportingEnabled(context: Context): Boolean {
    val sp = context.getSharedPreferences(FAST_PREFS_NAME, Context.MODE_PRIVATE)
    return sp.getBoolean(KEY_CRASH_REPORTING, true)
}

/**
 * How aggressively the agent is allowed to act on its own. Mirrors the
 * approval/sandbox knobs each CLI exposes; we map them onto the same
 * three-step ladder so the UI is consistent.
 *
 *  • [SAFE]   — the CLI's strictest defaults. Tool writes get rejected
 *               or stalled; good for "I just want to talk".
 *  • [AUTO]   — low-friction: auto-approve writes, escalate on failure.
 *               This is what most chat sessions actually want.
 *  • [YOLO]   — bypass all approvals and the sandbox. Fast, dangerous —
 *               only on hosts you trust.
 */
enum class AgentApprovalMode { SAFE, AUTO, YOLO }

/**
 * Lock-screen visibility for the "tap your security key" notification we
 * post during SK-keyed SSH auth. Mirrors the Android Notification.VISIBILITY_*
 * ladder so we can pass it straight to `NotificationCompat.Builder.setVisibility`.
 *
 *  • [PUBLIC]  — full text shown on the lock screen.
 *  • [PRIVATE] — text replaced by generic placeholder; notification still visible.
 *  • [SECRET]  — notification hidden entirely until device is unlocked.
 */
enum class SkNotificationVisibility { PUBLIC, PRIVATE, SECRET }

private val Context.dataStore by preferencesDataStore(name = "settings")

class AppPreferences(private val context: Context) {

    /** Line separator for the serialized label cache. */
    private val NL = 10.toChar().toString()

    private val themeKey = stringPreferencesKey("theme_mode")
    private val enterSendsKey = booleanPreferencesKey("enter_sends")
    private val accentHexKey = stringPreferencesKey("accent_hex")
    private val modelKey = stringPreferencesKey("selected_model")
    private val approvalKey = stringPreferencesKey("agent_approval_mode")
    private val lastRouteKey = stringPreferencesKey("last_route")
    private val modelLabelsKey = stringPreferencesKey("model_alias_labels")
    // Per-agent cache. `model_alias_labels` (above) is the legacy
    // claude-only key, still read for backwards compat. New code
    // writes/reads via `modelLabelsFor(agent)` which uses
    // `model_alias_labels_<AGENT>`.
    // `_v2` bump (2026-05-24) invalidates the stale hardcoded Gemini
    // list (auto/pro/flash/flash-lite) that was being served from the
    // old key before the dynamic REST-API probe could replace it.
    // `_v3` bump (2026-06-10) invalidates labels written by the
    // regex-ANSI-strip Claude parser, which corrupted display names
    // ("onnt 4.6") before the terminal-renderer fix. Old keys are
    // abandoned in DataStore; a few bytes of dead storage per agent,
    // harmless.
    private fun modelLabelsKeyFor(agent: String) =
        stringPreferencesKey("model_alias_labels_${agent.uppercase()}_v3")
    // Per-agent reasoning catalog cache (spec-serialized opaque string —
    // see AgentCliSpec.serializeReasoningCatalog). Lets a cold start show
    // the server's REAL effort levels instantly instead of the hardcoded
    // fallback ladder until the live probe lands.
    private fun reasoningCatalogKeyFor(agent: String) =
        stringPreferencesKey("agent_reasoning_catalog_${agent.uppercase()}_v1")
    // What the CLI runs when no `--model` is passed, per agent. Persisted so a
    // NEW chat can show its real default at frame zero: the value is discovered
    // by the live `/model` probe, and keeping it only in memory meant every
    // process restart produced an empty model chip until something re-probed.
    private fun defaultModelKeyFor(agent: String) =
        stringPreferencesKey("agent_default_model_${agent.uppercase()}_v1")
    private val userHeldServerIdsKey = stringPreferencesKey("user_held_server_ids")
    private val highRefreshRateKey = booleanPreferencesKey("high_refresh_rate_enabled")
    private val hapticsEnabledKey = booleanPreferencesKey("haptics_enabled")
    private val appScaleKey = floatPreferencesKey("app_scale")
    private val customBgHexKey = stringPreferencesKey("custom_bg_hex")
    private val customTextHexKey = stringPreferencesKey("custom_text_hex")
    private val fontFamilyIdKey = stringPreferencesKey("font_family_id")
    private val dataSaverEnabledKey = booleanPreferencesKey("data_saver_enabled")
    private val sshConnectTimeoutKey = intPreferencesKey("ssh_connect_timeout_sec")
    private val sshKeepaliveIntervalKey = intPreferencesKey("ssh_keepalive_interval_sec")
    private val seamlessReconnectKey = booleanPreferencesKey("seamless_reconnect_enabled")
    // SEC-1 kill-switch: when false, the conch-bridge refuses `shell` commands
    // from the server-side agent (logs/ping/screenshot still work). Defends
    // against a compromised/injected server driving the phone at adb level.
    // Default true to preserve the autonomous "agent drives my phone" UX.
    private val bridgeShellAllowedKey = booleanPreferencesKey("bridge_shell_allowed")
    private val bridgeAudioAllowedKey = booleanPreferencesKey("bridge_audio_allowed")
    private val seamlessReconnectDaysKey = intPreferencesKey("seamless_reconnect_days")
    private val deviceKeyExpiryKey = stringPreferencesKey("device_key_expiry")
    private val skNotificationVisibilityKey = stringPreferencesKey("sk_notification_visibility")
    private val oemAutoStartAcknowledgedKey = booleanPreferencesKey("oem_autostart_acknowledged")
    private val permissionGuardShownKey = booleanPreferencesKey("permission_guard_shown")

    /**
     * Last-known display labels for the well-known model aliases
     * (`default`, `sonnet`, `haiku`, `opus`), as `alias=label` lines.
     * Updated whenever a chat session successfully probes
     * `claude /model`, so the next cold launch immediately shows the
     * current real name rather than a stale hardcoded fallback.
     */
    val modelLabels: Flow<Map<String, String>> = context.dataStore.data.map { p ->
        val raw = p[modelLabelsKey].orEmpty()
        if (raw.isBlank()) emptyMap()
        else raw.lineSequence()
            .mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq <= 0) null else line.substring(0, eq) to line.substring(eq + 1)
            }
            .toMap()
    }

    suspend fun setModelLabels(labels: Map<String, String>) {
        if (labels.isEmpty()) return
        val serialized = labels.entries.joinToString("\n") { (k, v) -> "$k=$v" }
        context.dataStore.edit { it[modelLabelsKey] = serialized }
    }

    /**
     * Per-agent cache of the last-known `slug → display_name` map.
     *
     * For Codex this is the contents of `~/.codex/models_cache.json`
     * mapped into `slug=display_name` lines, written after every
     * successful probe so the next cold launch can pre-populate the
     * dropdown / topbar resolution **before** the live SSH probe
     * runs. Without it the user sees a brief flash of the raw slug
     * (or the agent name "Codex CLI" fallback) on every chat open.
     *
     * Keyed by `Agent.name` (e.g. "CODEX", "CLAUDE") to keep agents
     * fenced — claude's "default/sonnet/haiku" aliases must never
     * be served back into codex's picker.
     */
    fun modelLabelsForAgent(agent: String): Flow<Map<String, String>> =
        context.dataStore.data.map { p ->
            val raw = p[modelLabelsKeyFor(agent)].orEmpty()
            if (raw.isBlank()) emptyMap()
            else raw.lineSequence()
                .mapNotNull { line ->
                    val eq = line.indexOf('=')
                    if (eq <= 0) null else line.substring(0, eq) to line.substring(eq + 1)
                }
                .toMap()
        }

    /**
     * MERGE, never overwrite. The catalog is a growing record of what this
     * agent offers, shared across every server, and it must never move
     * backwards: Opus 4.8 -> Opus 5 happens, the reverse does not. A probe CAN
     * hand back the older name (stale menu render, half-parsed screen, an
     * un-updated box), and blindly storing it is how the picker went back to
     * advertising "Opus 4.8" after it had already learned "Opus 5" (user,
     * 2026-07-29). A short probe result also must not delete aliases we
     * already know.
     */
    suspend fun setModelLabelsForAgent(agent: String, labels: Map<String, String>) {
        if (labels.isEmpty()) return
        val key = modelLabelsKeyFor(agent)
        context.dataStore.edit { prefs ->
            val cached = prefs[key].orEmpty().lineSequence()
                .mapNotNull { line ->
                    val eq = line.indexOf('=')
                    if (eq <= 0) null else line.substring(0, eq) to line.substring(eq + 1)
                }
                .toMap()
            val merged = ai.eight24family.conch.data.ModelLabelMerge.merge(cached, labels)
            prefs[key] = merged.entries.joinToString(NL) { (k, v) -> "$k=$v" }
        }
    }

    /** Opaque spec-serialized reasoning catalog (see
     *  `AgentCliSpec.serializeReasoningCatalog`). Empty string = none. */
    fun reasoningCatalogForAgent(agent: String): Flow<String> =
        context.dataStore.data.map { p -> p[reasoningCatalogKeyFor(agent)].orEmpty() }

    suspend fun setReasoningCatalogForAgent(agent: String, raw: String) {
        if (raw.isBlank()) return
        context.dataStore.edit { it[reasoningCatalogKeyFor(agent)] = raw }
    }

    /** The CLI's own default model for [agent] — null when never probed. */
    fun defaultModelForAgent(agent: String): Flow<String?> =
        context.dataStore.data.map { p -> p[defaultModelKeyFor(agent)]?.takeIf { it.isNotBlank() } }

    suspend fun setDefaultModelForAgent(agent: String, model: String) {
        if (model.isBlank()) return
        context.dataStore.edit { it[defaultModelKeyFor(agent)] = model }
    }

    /**
     * The route the user was last on. Used by [AppNav] to bring them back to
     * the same chat after process death rather than dumping them on the
     * servers list. Empty string = no saved route, do default behaviour.
     */
    val lastRoute: Flow<String> = context.dataStore.data.map { p ->
        p[lastRouteKey].orEmpty()
    }

    suspend fun setLastRoute(route: String) {
        context.dataStore.edit { it[lastRouteKey] = route }
    }

    /** One-shot read for cold-start nav-restore. */
    suspend fun lastRouteOnce(): String = lastRoute.first()

    /**
     * The server the "Agents" bottom-tab is scoped to. With the unified
     * Sessions home, Servers/Agents are management tabs — the Agents tab
     * needs a server context, and this is it. Set whenever the user taps a
     * server (Servers tab) or opens one; defaults to the first server when
     * never set. null = no server configured yet.
     */
    private val currentServerIdKey = stringPreferencesKey("current_server_id")
    val currentServerId: Flow<String?> = context.dataStore.data.map { p ->
        p[currentServerIdKey]?.takeIf { it.isNotBlank() }
    }

    suspend fun setCurrentServerId(id: String?) {
        context.dataStore.edit { p ->
            if (id.isNullOrBlank()) p.remove(currentServerIdKey) else p[currentServerIdKey] = id
        }
    }

    suspend fun currentServerIdOnce(): String? = currentServerId.first()

    /**
     * The agent filter chip the user last selected on the unified Sessions home
     * (Agent.name, e.g. "CODEX"; null/absent = "All"). Persisted so the choice
     * survives an app RESTART, not just a rotation (rememberSaveable only covered
     * process-death restore) — user:.
     */
    private val homeAgentFilterKey = stringPreferencesKey("home_agent_filter")
    val homeAgentFilter: Flow<String?> = context.dataStore.data.map { p ->
        p[homeAgentFilterKey]?.takeIf { it.isNotBlank() }
    }

    suspend fun setHomeAgentFilter(agentName: String?) {
        context.dataStore.edit { p ->
            if (agentName.isNullOrBlank()) p.remove(homeAgentFilterKey)
            else p[homeAgentFilterKey] = agentName
        }
    }

    // ── Per-chat input drafts ── Unsent text typed into a chat's input box,
    // keyed by the chat's resume id (or local id for a brand-new chat).
    // Persisted so LEAVING the chat never loses what the user typed — only the
    // user clears it (by sending or deleting). The home list reads
    // [draftedChatIds] to badge "has draft". user:.
    private fun inputDraftKey(chatId: String) = stringPreferencesKey("input_draft_$chatId")

    fun inputDraft(chatId: String): Flow<String> =
        context.dataStore.data.map { it[inputDraftKey(chatId)].orEmpty() }

    suspend fun inputDraftOnce(chatId: String): String = inputDraft(chatId).first()

    suspend fun setInputDraft(chatId: String, text: String) {
        context.dataStore.edit { p ->
            if (text.isBlank()) p.remove(inputDraftKey(chatId)) else p[inputDraftKey(chatId)] = text
        }
    }

    /** All saved input drafts as chatId → text. Drives the home list's inline
     *  "Draft: …" subtitle (shown in place of the session preview). */
    val draftsByChat: Flow<Map<String, String>> = context.dataStore.data.map { p ->
        buildMap {
            for ((k, v) in p.asMap()) {
                val n = k.name
                val t = v as? String
                if (n.startsWith("input_draft_") && !t.isNullOrBlank()) {
                    put(n.removePrefix("input_draft_"), t)
                }
            }
        }
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { p ->
        SilentlyTry.loggedOrElse("SshAi-Prefs", "parse themeMode", ThemeMode.SYSTEM) { ThemeMode.valueOf(p[themeKey] ?: "SYSTEM") }
    }

    val enterSends: Flow<Boolean> = context.dataStore.data.map { p ->
        p[enterSendsKey] ?: false
    }

    /** SEC-1: master switch for the bridge `shell` command. Default true. */
    val bridgeShellAllowed: Flow<Boolean> = context.dataStore.data.map { p ->
        p[bridgeShellAllowedKey] ?: true
    }

    suspend fun setBridgeShellAllowed(allowed: Boolean) {
        context.dataStore.edit { it[bridgeShellAllowedKey] = allowed }
    }

    /**
     * Master switch for the bridge `audio` command — the server-side agent
     * recording this phone's microphone.
     *
     * DEFAULT FALSE, unlike every other bridge verb. `shell` and `logs` read a
     * device the user handed over; a microphone records the ROOM, and the people
     * in it who never agreed to anything. The bridge is an unauthenticated
     * channel by design — anything that can run code as the SSH user can drive
     * it — so this one stays off until the user turns it on themselves.
     */
    val bridgeAudioAllowed: Flow<Boolean> = context.dataStore.data.map { p ->
        p[bridgeAudioAllowedKey] ?: false
    }

    suspend fun setBridgeAudioAllowed(allowed: Boolean) {
        context.dataStore.edit { it[bridgeAudioAllowedKey] = allowed }
    }

    /** Accent neon color as #RRGGBB hex. Default = classic cyan. */
    val accentHex: Flow<String> = context.dataStore.data.map { p ->
        (p[accentHexKey] ?: "#00E5FF").let { if (isValidHex(it)) it else "#00E5FF" }
    }

    suspend fun setAccentHex(hex: String) {
        if (!isValidHex(hex)) return
        context.dataStore.edit { it[accentHexKey] = hex }
    }

    /** CLI `--model` override. null/blank = let the agent pick its own default. */
    val selectedModel: Flow<String?> = context.dataStore.data.map { p ->
        p[modelKey]?.takeIf { it.isNotBlank() }
    }

    suspend fun setSelectedModel(model: String?) {
        context.dataStore.edit { p ->
            if (model.isNullOrBlank()) p.remove(modelKey)
            else p[modelKey] = model
        }
    }

    /**
     * Per-agent `--model` override. Each CLI (claude / codex / gemini) names
     * its models differently and they are NOT interchangeable: feeding
     * `Opus 4.7` to codex was killing every send with "model not found",
     * because we used to store a single global selection that leaked across
     * agents. Now we key on the agent name in lowercase ("claude" / "codex"
     * / "gemini").
     *
     * Falls back to the legacy [selectedModel] if no per-agent value is
     * stored (so existing prefs aren't lost on first launch after upgrade).
     */
    fun selectedModelFor(agentKey: String): Flow<String?> {
        val key = stringPreferencesKey("selected_model_${agentKey.lowercase()}")
        return context.dataStore.data.map { p ->
            p[key]?.takeIf { it.isNotBlank() } ?: p[modelKey]?.takeIf { it.isNotBlank() && agentKey.equals("claude", ignoreCase = true) }
        }
    }

    suspend fun setSelectedModelFor(agentKey: String, model: String?) {
        val key = stringPreferencesKey("selected_model_${agentKey.lowercase()}")
        context.dataStore.edit { p ->
            if (model.isNullOrBlank()) p.remove(key)
            else p[key] = model
        }
    }

    /**
     * Per-chat saved model pick, keyed by the CLI's resume id
     * (claude UUID / codex `thread_id`). Each chat row remembers
     * which model the user explicitly chose in *that* chat. Setting
     * the model in chat A does NOT leak into chat B — that was the
     * bug where switching gpt-5.5 in one codex session changed every
     * other codex session's topbar.
     *
     * Returns null when:
     *  - no pick was ever made in this chat (use the per-agent
     *    default via [selectedModelFor], or whatever model the chat
     *    is actually running on from its JSONL header), or
     *  - the chat has no resume id yet (brand-new conversation
     *    before the CLI assigned a thread_id — the active selection
     *    lives only in ViewModel state until the first turn lands).
     */
    fun selectedModelForChat(resumeId: String): Flow<String?> {
        val key = stringPreferencesKey("selected_model_chat_${resumeId}")
        return context.dataStore.data.map { p ->
            p[key]?.takeIf { it.isNotBlank() }
        }
    }

    suspend fun setSelectedModelForChat(resumeId: String, model: String?) {
        val key = stringPreferencesKey("selected_model_chat_${resumeId}")
        context.dataStore.edit { p ->
            if (model.isNullOrBlank()) p.remove(key)
            else p[key] = model
        }
    }

    /**
     * Per-chat reasoning effort pick (Codex's `low|medium|high|xhigh`,
     * Claude's `low|medium|high|max`). Same isolation rules as
     * [selectedModelForChat] — set in one chat does not leak into
     * another.
     */
    fun selectedReasoningForChat(resumeId: String): Flow<String?> {
        val key = stringPreferencesKey("selected_reasoning_chat_${resumeId}")
        return context.dataStore.data.map { p ->
            p[key]?.takeIf { it.isNotBlank() }
        }
    }

    suspend fun setSelectedReasoningForChat(resumeId: String, effort: String?) {
        val key = stringPreferencesKey("selected_reasoning_chat_${resumeId}")
        context.dataStore.edit { p ->
            if (effort.isNullOrBlank()) p.remove(key)
            else p[key] = effort
        }
    }

    /**
     * Per-agent default reasoning effort. Same role as
     * [selectedModelFor] — last-picked value, used as the starting
     * point for brand-new chats. Keyed by `Agent.name`
     * (CODEX / CLAUDE / GEMINI).
     */
    fun selectedReasoningFor(agentKey: String): Flow<String?> {
        val key = stringPreferencesKey("selected_reasoning_${agentKey.lowercase()}")
        return context.dataStore.data.map { p ->
            p[key]?.takeIf { it.isNotBlank() }
        }
    }

    suspend fun setSelectedReasoningFor(agentKey: String, effort: String?) {
        val key = stringPreferencesKey("selected_reasoning_${agentKey.lowercase()}")
        context.dataStore.edit { p ->
            if (effort.isNullOrBlank()) p.remove(key)
            else p[key] = effort
        }
    }

    /**
     * Index of downloaded files keyed by **SHA-256 of remote content**.
     * Value lines: `hash<TAB>uri<TAB>basename<TAB>sizeBytes`.
     *
     * Hash-keying is what makes "already downloaded? skip the SSH
     * transfer" actually correct — basename matching produced false
     * positives. Hash is the only reliable key.
     */
    private val downloadIndexKey = stringPreferencesKey("download_index_v1")

    data class DownloadIndexEntry(
        val uriString: String,
        val basename: String,
        val sizeBytes: Long,
    )

    val downloadIndex: Flow<Map<String, DownloadIndexEntry>> = context.dataStore.data.map { p ->
        val raw = p[downloadIndexKey].orEmpty()
        if (raw.isBlank()) emptyMap()
        else raw.lineSequence()
            .mapNotNull { line ->
                val parts = line.split('\t', limit = 4)
                if (parts.size < 4) return@mapNotNull null
                val hash = parts[0].trim()
                val size = parts[3].trim().toLongOrNull() ?: return@mapNotNull null
                if (hash.length != 64) return@mapNotNull null
                hash to DownloadIndexEntry(parts[1], parts[2], size)
            }
            .toMap()
    }

    suspend fun addDownloadIndexEntry(hash: String, entry: DownloadIndexEntry) {
        if (hash.length != 64) return
        context.dataStore.edit { p ->
            val existing = p[downloadIndexKey].orEmpty()
            val filtered = existing.lineSequence()
                .filter { line ->
                    val first = line.split('\t', limit = 2).firstOrNull()?.trim()
                    first != hash
                }
                .joinToString("\n")
            val newLine = "$hash\t${entry.uriString}\t${entry.basename}\t${entry.sizeBytes}"
            val out = if (filtered.isBlank()) newLine else "$filtered\n$newLine"
            p[downloadIndexKey] = out
        }
    }

    suspend fun removeDownloadIndexEntry(hash: String) {
        context.dataStore.edit { p ->
            val existing = p[downloadIndexKey].orEmpty()
            val filtered = existing.lineSequence()
                .filter { line ->
                    val first = line.split('\t', limit = 2).firstOrNull()?.trim()
                    first != hash
                }
                .joinToString("\n")
            if (filtered.isBlank()) p.remove(downloadIndexKey)
            else p[downloadIndexKey] = filtered
        }
    }

    private val downloadsFolderUriKey = stringPreferencesKey("downloads_folder_uri")
    private val downloadsFolderDisplayKey = stringPreferencesKey("downloads_folder_display")

    /**
     * User-picked downloads folder, as a SAF tree URI string. When
     * null, downloads default to `Download/conch/` via MediaStore
     * (Q+) or app-private external Downloads (pre-Q).
     *
     * The URI is acquired via `ACTION_OPEN_DOCUMENT_TREE` and we
     * keep a `persistableUriPermission` so it survives app restarts.
     */
    val downloadsFolderUri: Flow<android.net.Uri?> = context.dataStore.data.map { p ->
        p[downloadsFolderUriKey]?.takeIf { it.isNotBlank() }
            ?.let { SilentlyTry.logged("SshAi-Prefs", "parse downloads folder uri") { android.net.Uri.parse(it) } }
    }

    /** Human-readable label for the chosen folder (e.g.
     *  `primary:Documents/code`). Just for showing in Settings —
     *  the actual write goes through the persisted URI. */
    val downloadsFolderDisplay: Flow<String?> = context.dataStore.data.map { p ->
        p[downloadsFolderDisplayKey]?.takeIf { it.isNotBlank() }
    }

    suspend fun setDownloadsFolder(uri: android.net.Uri?, display: String?) {
        context.dataStore.edit { p ->
            if (uri == null) {
                p.remove(downloadsFolderUriKey)
                p.remove(downloadsFolderDisplayKey)
            } else {
                p[downloadsFolderUriKey] = uri.toString()
                p[downloadsFolderDisplayKey] = display.orEmpty()
            }
        }
    }

    /**
     * "Open downloaded file with" preference keyed by file extension
     * (`.toml` / `.pdf` / `.md` / …). Values are either `"internal"`
     * (route to our built-in TextViewerScreen) or `"external"`
     * (fire ACTION_VIEW, let Android pick a handler). `null` means
     * the user hasn't decided yet — UI should prompt with a chooser
     * and offer a "remember this" checkbox.
     */
    fun openFilePreferenceForExtension(extension: String): Flow<String?> {
        val key = stringPreferencesKey("open_file_pref_${extension.lowercase()}")
        return context.dataStore.data.map { p ->
            p[key]?.takeIf { it == "internal" || it == "external" }
        }
    }

    suspend fun setOpenFilePreferenceForExtension(extension: String, choice: String?) {
        val key = stringPreferencesKey("open_file_pref_${extension.lowercase()}")
        context.dataStore.edit { p ->
            if (choice.isNullOrBlank()) p.remove(key)
            else p[key] = choice
        }
    }

    /**
     * One-shot cleanup of every `selected_model_chat_*` key in
     * prefs. Used to undo the broken backfill (2026-05-22) that
     * wrote the current per-agent value into per-chat the moment
     * ANY chat was opened — that polluted every chat's per-chat
     * key with whatever the per-agent default was at first open,
     * making the per-chat isolation effectively a no-op. Gated by
     * a marker so it runs at most once per install.
     */
    suspend fun runChatModelKeysCleanupIfNeeded() {
        val marker = booleanPreferencesKey("chat_model_keys_cleanup_v1_done")
        val alreadyDone = context.dataStore.data.map { it[marker] ?: false }.first()
        if (alreadyDone) return
        context.dataStore.edit { p ->
            val victims = p.asMap().keys
                .filter { it.name.startsWith("selected_model_chat_") }
            for (k in victims) p.remove(k)
            p[marker] = true
        }
    }

    /** Default to YOLO — that's how the app has always shipped (`--dangerously-skip-permissions`),
     *  and switching it silently would surprise existing users. New users opt
     *  down via the topbar shield icon. */
    val approvalMode: Flow<AgentApprovalMode> = context.dataStore.data.map { p ->
        SilentlyTry.loggedOrElse("SshAi-Prefs", "parse approval mode", AgentApprovalMode.YOLO) {
            AgentApprovalMode.valueOf(p[approvalKey] ?: "YOLO")
        }
    }

    suspend fun setApprovalMode(mode: AgentApprovalMode) {
        context.dataStore.edit { it[approvalKey] = mode.name }
    }

    /**
     * Per-AGENT approval override, keyed by agent name (lowercase). Each CLI has
     * its own approval flags/semantics, so picking a mode in one agent's chat
     * must NOT change it for the others. Falls back to the global [approvalMode]
     * default (the Settings "Default approval mode" picker) when this agent has
     * no explicit pick yet, then to YOLO — so existing users keep their setting
     * on first launch after upgrade. Mirrors the per-agent model pattern
     * ([selectedModelFor]).
     */
    fun approvalModeFor(agentKey: String): Flow<AgentApprovalMode> {
        val key = stringPreferencesKey("agent_approval_mode_${agentKey.lowercase()}")
        return context.dataStore.data.map { p ->
            SilentlyTry.loggedOrElse("SshAi-Prefs", "parse per-agent approval mode", AgentApprovalMode.YOLO) {
                AgentApprovalMode.valueOf(p[key] ?: p[approvalKey] ?: "YOLO")
            }
        }
    }

    suspend fun setApprovalModeFor(agentKey: String, mode: AgentApprovalMode) {
        val key = stringPreferencesKey("agent_approval_mode_${agentKey.lowercase()}")
        context.dataStore.edit { it[key] = mode.name }
    }

    /** Whether the approval shield is shown as a chat top-bar icon. Off = the
     *  user granted their permission level once and doesn't want to see/touch it
     *  per chat. Default on. */
    private val showApprovalInChatBarKey = booleanPreferencesKey("show_approval_in_chat_bar")
    val showApprovalInChatBar: Flow<Boolean> = context.dataStore.data.map { p ->
        p[showApprovalInChatBarKey] ?: true
    }

    suspend fun setShowApprovalInChatBar(show: Boolean) {
        context.dataStore.edit { it[showApprovalInChatBarKey] = show }
    }

    // ── Crash reporting + telemetry ────────────────────────────
    // Stored in BOTH a DataStore pref (so the Settings UI can collectAsState)
    // and a SharedPreferences file (so SshAiApp.onCreate can read it
    // synchronously before any coroutine has a chance to spin up).
    private val crashReportingKey = booleanPreferencesKey("crash_reporting_enabled")

    val crashReportingEnabled: Flow<Boolean> = context.dataStore.data.map { p ->
        p[crashReportingKey] ?: true
    }

    suspend fun setCrashReportingEnabled(enabled: Boolean) {
        // Write both copies. The SharedPreferences write is the one that
        // takes effect on the NEXT launch's Sentry init; the DataStore copy
        // drives the UI right now.
        context.dataStore.edit { it[crashReportingKey] = enabled }
        context.getSharedPreferences(FAST_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CRASH_REPORTING, enabled).apply()
    }

    private fun isValidHex(value: String): Boolean =
        Regex("^#[0-9A-Fa-f]{6}$").matches(value.trim())

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[themeKey] = mode.name }
    }


    suspend fun setEnterSends(value: Boolean) {
        context.dataStore.edit { it[enterSendsKey] = value }
    }

    // ── Connection knobs ───────────────────────────────────────
    // Both are stored in seconds and clamped to sane UI ranges. Defaults
    // match what SshConnectionPool / sshj have historically baked in, so
    // existing users see no behaviour change on upgrade.

    /** SSH socket connect timeout, in seconds. Range 5..60, default 15. */
    val sshConnectTimeoutSec: Flow<Int> = context.dataStore.data.map { p ->
        (p[sshConnectTimeoutKey] ?: 15).coerceIn(5, 60)
    }

    suspend fun setSshConnectTimeoutSec(seconds: Int) {
        context.dataStore.edit { it[sshConnectTimeoutKey] = seconds.coerceIn(5, 60) }
    }

    /** SSH transport keep-alive interval, in seconds. Range 15..120, default 45. */
    val sshKeepaliveIntervalSec: Flow<Int> = context.dataStore.data.map { p ->
        (p[sshKeepaliveIntervalKey] ?: 45).coerceIn(15, 120)
    }

    suspend fun setSshKeepaliveIntervalSec(seconds: Int) {
        context.dataStore.edit { it[sshKeepaliveIntervalKey] = seconds.coerceIn(15, 120) }
    }

    /**
     * Opt-in: keep SECURITY-KEY sessions alive across network changes by
     * enrolling a self-expiring, hardware-backed device key on the server (see
     * EphemeralSshKey). OFF by default. When on, ONE FIDO tap enrolls this
     * phone; reconnects after a Wi-Fi⇄cellular handoff are silent (no second
     * tap — for the "I leave my key at home" workflow), and the enrolled key
     * auto-expires server-side after [seamlessReconnectDays].
     */
    val seamlessReconnectEnabled: Flow<Boolean> = context.dataStore.data.map { p ->
        p[seamlessReconnectKey] ?: false
    }

    suspend fun setSeamlessReconnectEnabled(enabled: Boolean) {
        context.dataStore.edit { it[seamlessReconnectKey] = enabled }
    }

    /** Lifetime (days) of the enrolled device key's server-side `expiry-time`. 1/3/7. */
    val seamlessReconnectDays: Flow<Int> = context.dataStore.data.map { p ->
        (p[seamlessReconnectDaysKey] ?: 7).coerceIn(1, 30)
    }

    suspend fun setSeamlessReconnectDays(days: Int) {
        context.dataStore.edit { it[seamlessReconnectDaysKey] = days.coerceIn(1, 30) }
    }

    /**
     * Per-server device-key expiry (epoch-ms) — when the enrolled server-side
     * `expiry-time` line dies. Recorded at enroll as `now + seamlessReconnectDays`
     * (the server uses the same +N-days against its own ~NTP-synced clock, so the
     * skew is seconds). Drives the live "expires in …" countdown in Settings.
     * Serialised as `serverId=epochMs` pairs joined by `,` (UUIDs contain neither).
     */
    val deviceKeyExpiry: Flow<Map<String, Long>> = context.dataStore.data.map { p ->
        parseDeviceKeyExpiry(p[deviceKeyExpiryKey])
    }

    suspend fun setDeviceKeyExpiry(serverId: String, epochMs: Long) {
        context.dataStore.edit { p ->
            val m = parseDeviceKeyExpiry(p[deviceKeyExpiryKey]).toMutableMap()
            m[serverId] = epochMs
            p[deviceKeyExpiryKey] = encodeDeviceKeyExpiry(m)
        }
    }

    suspend fun clearDeviceKeyExpiry(serverId: String) {
        context.dataStore.edit { p ->
            val m = parseDeviceKeyExpiry(p[deviceKeyExpiryKey]).toMutableMap()
            m.remove(serverId)
            if (m.isEmpty()) p.remove(deviceKeyExpiryKey)
            else p[deviceKeyExpiryKey] = encodeDeviceKeyExpiry(m)
        }
    }

    suspend fun clearAllDeviceKeyExpiry() {
        context.dataStore.edit { it.remove(deviceKeyExpiryKey) }
    }

    // ── Per-session last-touched timestamps (epoch MILLIS) ──────────────────
    // Drives the home sessions list's "sort by last message" + the "when"
    // stamp. The server file mtime (RemoteSession.lastActiveAt) only refreshes
    // on a prefetch sweep, so a chat the user JUST wrote in showed a stale date
    // / wrong order — worst after an app restart, where the in-memory bump was
    // lost. Persisting the touch survives restarts → reliable ordering. Pruned
    // to the 300 most-recent so the string can't grow unbounded.
    private val sessionTouchedKey = stringPreferencesKey("session_touched_at")
    val sessionTouchedAt: Flow<Map<String, Long>> = context.dataStore.data.map { p ->
        parseDeviceKeyExpiry(p[sessionTouchedKey])
    }

    /**
     * Atomically replace the whole persisted activity snapshot. [SessionActivityStore]
     * is the in-memory authority and flushes its full map here (debounced), so ONE
     * bulk write per burst beats N read-modify-writes under a listing sweep. Pruned
     * to the 300 most-recently-active entries — far more than fit on screen, and the
     * dropped tail falls back to the server file mtime (still correct, just not the
     * to-the-second local observation).
     */
    suspend fun replaceSessionTouchedAt(map: Map<String, Long>) {
        val pruned = if (map.size > 300)
            map.entries.sortedByDescending { it.value }.take(300).associate { it.key to it.value }
        else map
        context.dataStore.edit { p ->
            if (pruned.isEmpty()) p.remove(sessionTouchedKey)
            else p[sessionTouchedKey] = encodeDeviceKeyExpiry(pruned)
        }
    }

    private fun parseDeviceKeyExpiry(s: String?): Map<String, Long> =
        s?.split(',')
            ?.mapNotNull { e ->
                val i = e.indexOf('=')
                if (i <= 0) return@mapNotNull null
                val v = e.substring(i + 1).toLongOrNull() ?: return@mapNotNull null
                e.substring(0, i) to v
            }
            ?.toMap()
            ?: emptyMap()

    private fun encodeDeviceKeyExpiry(m: Map<String, Long>): String =
        m.entries.joinToString(",") { "${it.key}=${it.value}" }

    // ── Per-server seamless reconnect ──────────────────────────────────────
    // Moved OUT of global app Settings into the per-server detail page — it's a
    // property of the SERVER, not the app. Each server opts in independently.
    private val seamlessServersKey = stringPreferencesKey("seamless_servers")
    private val seamlessDaysByServerKey = stringPreferencesKey("seamless_days_by_server")

    /** Server ids with seamless reconnect ON (controls whether a device key is
     *  minted/re-minted for that server). Comma-separated (UUIDs, no commas). */
    val seamlessServers: Flow<Set<String>> = context.dataStore.data.map { p ->
        p[seamlessServersKey]?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    suspend fun setSeamlessForServer(serverId: String, enabled: Boolean) {
        context.dataStore.edit { p ->
            val cur = p[seamlessServersKey]?.split(',')?.filter { it.isNotBlank() }?.toMutableSet()
                ?: mutableSetOf()
            if (enabled) cur.add(serverId) else cur.remove(serverId)
            if (cur.isEmpty()) p.remove(seamlessServersKey) else p[seamlessServersKey] = cur.joinToString(",")
        }
    }

    // ── Phone-bridge sessions ───────────────────────────────────────────────
    // Chat sessions the user explicitly wired to the phone via the paperclip
    // "Connect phone to server" action. Drives the small phone glyph on the
    // session row in the sessions list. Keyed "<serverId>:<resumeId>" (both
    // UUIDs — never contain a comma or colon), comma-separated.
    private val phoneBridgeSessionsKey = stringPreferencesKey("phone_bridge_sessions")

    /** Set of "<serverId>:<resumeId>" tags for phone-wired sessions. */
    val phoneBridgeSessions: Flow<Set<String>> = context.dataStore.data.map { p ->
        p[phoneBridgeSessionsKey]?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    suspend fun setPhoneBridgeSession(serverId: String, sessionId: String, on: Boolean) {
        if (sessionId.isBlank()) return
        val tag = "$serverId:$sessionId"
        context.dataStore.edit { p ->
            val cur = p[phoneBridgeSessionsKey]?.split(',')?.filter { it.isNotBlank() }?.toMutableSet()
                ?: mutableSetOf()
            if (on) cur.add(tag) else cur.remove(tag)
            if (cur.isEmpty()) p.remove(phoneBridgeSessionsKey) else p[phoneBridgeSessionsKey] = cur.joinToString(",")
        }
    }

    // ── Deleted-session tombstones ──────────────────────────────────────────
    // When the user swipe-deletes a session we drop it locally + `rm` it on the
    // server. But an in-flight/just-after refresh (or a server delete that
    // didn't land) can RESURFACE the row on the next listing. A persisted
    // tombstone keeps a deleted session hidden across refreshes AND app
    // restarts until a fresh server listing confirms it's actually gone (then
    // the tombstone is pruned). Keyed "<serverId>:<sessionId>", comma-separated.
    private val deletedSessionsKey = stringPreferencesKey("deleted_sessions")

    /** Set of "<serverId>:<sessionId>" tombstones for user-deleted sessions. */
    val deletedSessions: Flow<Set<String>> = context.dataStore.data.map { p ->
        p[deletedSessionsKey]?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    suspend fun setDeletedSession(serverId: String, sessionId: String, deleted: Boolean) {
        if (sessionId.isBlank()) return
        val tag = "$serverId:$sessionId"
        context.dataStore.edit { p ->
            val cur = p[deletedSessionsKey]?.split(',')?.filter { it.isNotBlank() }?.toMutableSet()
                ?: mutableSetOf()
            if (deleted) cur.add(tag) else cur.remove(tag)
            if (cur.isEmpty()) p.remove(deletedSessionsKey) else p[deletedSessionsKey] = cur.joinToString(",")
        }
    }

    /** Drop tombstones for [serverId] whose session id is NOT in [presentIds]
     *  (the server just confirmed those sessions are gone). Keeps the set
     *  bounded and self-healing — tombstones for sessions the server STILL
     *  reports (delete pending/failed) are retained so they stay hidden. */
    suspend fun pruneDeletedSessions(serverId: String, presentIds: Set<String>) {
        val prefix = "$serverId:"
        context.dataStore.edit { p ->
            val cur = p[deletedSessionsKey]?.split(',')?.filter { it.isNotBlank() }?.toMutableSet()
                ?: return@edit
            val before = cur.size
            cur.removeAll { tag ->
                tag.startsWith(prefix) && tag.removePrefix(prefix) !in presentIds
            }
            if (cur.size == before) return@edit
            if (cur.isEmpty()) p.remove(deletedSessionsKey) else p[deletedSessionsKey] = cur.joinToString(",")
        }
    }

    /** Per-server device-key lifetime in days (default 7). */
    val seamlessDaysByServer: Flow<Map<String, Int>> = context.dataStore.data.map { p ->
        parseSeamlessDays(p[seamlessDaysByServerKey])
    }

    suspend fun setSeamlessDaysForServer(serverId: String, days: Int) {
        context.dataStore.edit { p ->
            val m = parseSeamlessDays(p[seamlessDaysByServerKey]).toMutableMap()
            m[serverId] = days.coerceIn(1, 30)
            p[seamlessDaysByServerKey] = m.entries.joinToString(",") { "${it.key}=${it.value}" }
        }
    }

    private fun parseSeamlessDays(s: String?): Map<String, Int> =
        s?.split(',')
            ?.mapNotNull { e ->
                val i = e.indexOf('=')
                if (i <= 0) return@mapNotNull null
                val v = e.substring(i + 1).toIntOrNull() ?: return@mapNotNull null
                e.substring(0, i) to v
            }
            ?.toMap()
            ?: emptyMap()

    /** Lock-screen visibility of the "tap your security key" prompt. */
    val skNotificationVisibility: Flow<SkNotificationVisibility> = context.dataStore.data.map { p ->
        SilentlyTry.loggedOrElse("SshAi-Prefs", "parse sk notification visibility", SkNotificationVisibility.PRIVATE) {
            SkNotificationVisibility.valueOf(p[skNotificationVisibilityKey] ?: "PRIVATE")
        }
    }

    suspend fun setSkNotificationVisibility(value: SkNotificationVisibility) {
        context.dataStore.edit { it[skNotificationVisibilityKey] = value.name }
    }

    // ── Persistent-connection permission guard state ───────────────────
    // "OEM auto-start acknowledged": vendor-specific autostart panels
    // (MIUI, EMUI, ColorOS, FuntouchOS, etc) don't expose a readable
    // state — we just remember whether the user clicked through our
    // "Open vendor settings" button. Reset to false from Settings if
    // they want to be re-prompted.
    val oemAutoStartAcknowledged: Flow<Boolean> = context.dataStore.data.map { p ->
        p[oemAutoStartAcknowledgedKey] ?: false
    }

    suspend fun setOemAutoStartAcknowledged(value: Boolean) {
        context.dataStore.edit { it[oemAutoStartAcknowledgedKey] = value }
    }

    /** Have we ever shown the permission-guard sheet to the user? Used
     *  to surface it auto-magically on the first tap-to-connect after
     *  install, but never again unless the user revoked something. */
    val permissionGuardShown: Flow<Boolean> = context.dataStore.data.map { p ->
        p[permissionGuardShownKey] ?: false
    }

    suspend fun setPermissionGuardShown(value: Boolean) {
        context.dataStore.edit { it[permissionGuardShownKey] = value }
    }

    /**
     * Server IDs the user explicitly connected to (via tap-to-connect)
     * during their last app session. Persisted on every change to
     * `SshConnectionPool.userHeldIds` so a cold app start can attempt
     * silent re-connect for non-SK servers and surface a "tap to
     * reconnect" hint for SK ones. Cleared when the user disconnects.
     *
     * Serialised as a comma-separated list (no IDs contain commas;
     * they're DB-generated UUIDs).
     */
    val userHeldServerIds: Flow<Set<String>> = context.dataStore.data.map { p ->
        p[userHeldServerIdsKey]
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()
    }

    suspend fun setUserHeldServerIds(ids: Set<String>) {
        context.dataStore.edit { p ->
            if (ids.isEmpty()) p.remove(userHeldServerIdsKey)
            else p[userHeldServerIdsKey] = ids.joinToString(",")
        }
    }

    /**
     * High refresh rate toggle. Default = true (smooth wins for most
     * users); user can opt out via Settings → Appearance to save
     * battery on phones that aggressively drain at 120 Hz.
     *
     * Read at activity onCreate. Toggling at runtime takes effect on
     * the NEXT activity create (recreate cheap — `recreate()` from
     * the activity observing this flow).
     */
    val highRefreshRateEnabled: Flow<Boolean> = context.dataStore.data.map { p ->
        p[highRefreshRateKey] ?: false
    }

    suspend fun setHighRefreshRateEnabled(value: Boolean) {
        context.dataStore.edit { it[highRefreshRateKey] = value }
    }

    /** Haptic feedback (tactile clicks on button press, PIN keys,
     *  refresh release, file open, etc.). Default on — modern phones
     *  have great taptic engines and this makes the app feel like
     *  a 2026 product. User can disable in Settings → Appearance
     *  for total silence. */
    val hapticsEnabled: Flow<Boolean> = context.dataStore.data.map { p ->
        p[hapticsEnabledKey] ?: true
    }

    suspend fun setHapticsEnabled(value: Boolean) {
        context.dataStore.edit { it[hapticsEnabledKey] = value }
    }

    /** App-wide scale multiplier (1.0 = stock; 0.8 = condensed; 1.4 =
     *  comfortable for one-handed). Applied as a Density override at
     *  the Compose root so EVERY dp + sp scales uniformly — buttons
     *  enlarge, font enlarges, paddings enlarge. Step in the UI is
     *  0.05 so the user can dial in within ±5 %. */
    val appScale: Flow<Float> = context.dataStore.data.map { p ->
        (p[appScaleKey] ?: 1.0f).coerceIn(0.75f, 1.5f)
    }

    suspend fun setAppScale(value: Float) {
        context.dataStore.edit {
            it[appScaleKey] = value.coerceIn(0.75f, 1.5f)
        }
    }

    /** Custom-theme background color when `themeMode == CUSTOM`. Stored
     *  as `#RRGGBB`. Default dark-ish charcoal. */
    val customBgHex: Flow<String> = context.dataStore.data.map { p ->
        p[customBgHexKey] ?: "#0A0F12"
    }

    suspend fun setCustomBgHex(hex: String) {
        context.dataStore.edit { it[customBgHexKey] = hex }
    }

    /** Custom-theme TEXT/foreground color when `themeMode == CUSTOM`. Stored
     *  as `#RRGGBB`. Default near-white for legibility on a dark background. */
    val customTextHex: Flow<String> = context.dataStore.data.map { p ->
        (p[customTextHexKey] ?: "#E6EDF3").let { if (isValidHex(it)) it else "#E6EDF3" }
    }

    suspend fun setCustomTextHex(hex: String) {
        context.dataStore.edit { it[customTextHexKey] = hex }
    }

    /** Chosen coding-font id (see CodingFont). Default "system" = platform
     *  monospace, so existing installs look unchanged. */
    val fontFamilyId: Flow<String> = context.dataStore.data.map { p ->
        p[fontFamilyIdKey] ?: "system"
    }

    suspend fun setFontFamilyId(id: String) {
        context.dataStore.edit { it[fontFamilyIdKey] = id }
    }

    /**
     * **Data saver mode.** When enabled the app shrinks every
     * non-essential SSH round-trip:
     *   - `GlobalPrefetcher` does nothing on home open.
     *   - `SessionsViewModel.startPrefetch` does nothing — JSONL
     *     fetches only happen when the user opens a specific chat.
     *   - `tailPoll` polls 6× less often (5s → 30s active,
     *     30s → 5min idle).
     *   - `AgentStatusProbe` caches per (server, agent) for an hour
     *     instead of re-probing on every AgentPicker refresh.
     *   - `ServerStatsProbe` auto-refresh is disabled — only manual.
     *   - `AgentBridge` poll interval bumps 2s → 10s.
     *   - SSH keepalive untouched (would break connections), and
     *     live streaming of an in-flight agent reply is untouched
     *     (it IS the user's content).
     *
     * Off by default. UI toggle lives in Settings → Connection.
     */
    val dataSaverEnabled: Flow<Boolean> = context.dataStore.data.map { p ->
        p[dataSaverEnabledKey] ?: false
    }

    suspend fun setDataSaverEnabled(enabled: Boolean) {
        context.dataStore.edit { it[dataSaverEnabledKey] = enabled }
    }
}
