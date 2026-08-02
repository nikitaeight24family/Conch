package ai.eight24family.conch.ui.viewmodel

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.AgentSession
import ai.eight24family.conch.agent.spec.AgentExec
import ai.eight24family.conch.agent.spec.AgentSpecRegistry
import ai.eight24family.conch.agent.spec.ModelReasoningInfo
import ai.eight24family.conch.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Per-chat model + reasoning effort picker.
 *
 * Owns the `--model` / reasoning override pipeline:
 *  - [selectedModel] / [selectedReasoning] — explicit per-chat picks (combine on
 *    currentAgent + resumeId + pending pick). Resolution order:
 *      1. Transient pending pick (set on `tap` before resumeId arrives).
 *      2. Per-chat persisted key (`selected_model_chat_<rid>`).
 *      3. null — topbar falls through to sessionInitialModel → defaultModel.
 *  - [availableModels] / [modelsProbing] — live PTY probe of `claude /model` etc.
 *  - [defaultModel] / [defaultReasoning] — what the CLI uses when no `--model` flag.
 *  - [sessionInitialModel] / [sessionInitialReasoning] — parsed from session JSONL
 *    header (drives topbar from frame zero).
 *  - [reasoningCatalog] — per-model {default, levels} map (from spec).
 *  - [observedModel] / [observedCwd] — most-recent `system.init` event values.
 *
 * Backfill rule (preserved verbatim from the original inline implementation): when a
 * brand-new chat (`initialResumeId == null`) gets assigned its first thread id by
 * the CLI, migrate any pending pick to the per-chat prefs key. See [observeResumeIdForBackfill].
 *
 * Public surface stays on ChatViewModel via delegate properties.
 *
 * See ChatViewModel.kt prior to extraction for the original inline comments.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ChatViewModelModels(
    private val scope: CoroutineScope,
    private val currentAgent: StateFlow<Agent>,
    private val resumeId: StateFlow<String?>,
    private val messages: StateFlow<List<AgentMessage>>,
    initialSessionModel: String?,
    initialSessionReasoning: String?,
) {
    companion object {
        /** Sentinel: no explicit model pick has been made in this chat yet. */
        private const val NEVER_PICKED = "<<never-picked>>"

        /** In-memory mirror of the last alias→label map each agent's probe
         *  returned (also persisted in prefs.modelLabelsForAgent). Lets a chat
         *  seed [availableModels] SYNCHRONOUSLY at construction so the topbar
         *  shows the real model name from frame zero instead of the stale
         *  hardcoded fallback. Warmed by probes and the disk hydrate. */
        private val labelMemory = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
        fun cachedLabels(agent: Agent): Map<String, String> = labelMemory[agent.name].orEmpty()

        /**
         * MERGE into the mirror and return the merged catalog — the SAME
         * monotonic law the disk cache obeys ([ModelLabelMerge]). Callers must
         * show the RETURN VALUE, not the raw probe map: the picker and the
         * frame-zero seed read this mirror, so overwriting it here would let a
         * stale probe put "Opus 4.8" back in front of the user even though the
         * persisted catalog already knew "Opus 5" (user, 2026-07-29). Atomic —
         * two servers' probes can land concurrently.
         */
        fun rememberLabels(agentName: String, map: Map<String, String>): Map<String, String> {
            if (map.isEmpty()) return labelMemory[agentName].orEmpty()
            return labelMemory.compute(agentName) { _, cached ->
                ai.eight24family.conch.data.ModelLabelMerge.merge(cached.orEmpty(), map)
            }.orEmpty()
        }
    }

    /** Transient pick for a chat that doesn't have a resumeId yet (brand-new
     *  conversation). Migrated to the per-chat prefs key as soon as the resumeId
     *  arrives (see [observeResumeIdForBackfill]). */
    private val _pendingModelPick = MutableStateFlow<String?>(null)
    private val _pendingReasoningPick = MutableStateFlow<String?>(null)

    /**
     * The user's explicit model pick for THIS chat — never leaks across chats.
     *
     * **No per-agent fallback** — new chats default to the CLI's actual default
     * (`defaultModel` from config.toml), not whatever the user picked in some other
     * chat last week.
     */
    val selectedModel: StateFlow<String?> = combine(
        currentAgent,
        resumeId,
        _pendingModelPick,
    ) { _, rid, pending -> rid to pending }
        .flatMapLatest { (rid, pending) ->
            val prefs = ServiceLocator.preferences
            when {
                pending != null -> flowOf(pending)
                rid != null -> prefs.selectedModelForChat(rid)
                else -> flowOf(null)
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Mirror of [selectedModel] for reasoning effort. */
    val selectedReasoning: StateFlow<String?> = combine(
        currentAgent,
        resumeId,
        _pendingReasoningPick,
    ) { _, rid, pending -> rid to pending }
        .flatMapLatest { (rid, pending) ->
            val prefs = ServiceLocator.preferences
            when {
                pending != null -> flowOf(pending)
                rid != null -> prefs.selectedReasoningForChat(rid)
                else -> flowOf(null)
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Per-model reasoning catalog (slug → {default, levels}) from the current
     *  agent's spec. */
    private val _reasoningCatalog = MutableStateFlow<Map<String, ModelReasoningInfo>>(emptyMap())
    val reasoningCatalog: StateFlow<Map<String, ModelReasoningInfo>> = _reasoningCatalog.asStateFlow()

    /**
     * Model that the agent actually reports in its `system` init event for the most
     * recent turn. Single source of truth for "what the agent is actually using right
     * now"; never lie with a hardcoded fallback.
     */
    val observedModel: StateFlow<String?> = messages
        .map { list ->
            list.asReversed().asSequence()
                .filterIsInstance<AgentMessage.System>()
                .mapNotNull { it.model }
                .firstOrNull()
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The OBSERVED model as it stood when the user last picked, "" = never
     *  picked. Sentinel object so "observed was null at pick time" is
     *  distinguishable from "never picked". */
    private val _obsAtPick = MutableStateFlow<String?>(NEVER_PICKED)

    /**
     * False while the live observation is still the SAME one that was on screen
     * when the user picked — i.e. nothing new has been observed since, so the
     * pick must win.
     *
     * ⚠ Do NOT approximate this with "the message list grew". Sending a message
     * grows the list by the USER's own row, which flipped this true instantly
     * and handed the chip straight back to the stale observation: pick Opus,
     * send, chip shows Sonnet for a beat, then Opus once the real turn reports
     * (user, 2026-07-27). Only a CHANGE of the observation itself counts.
     */
    val observationNewerThanPick: StateFlow<Boolean> =
        combine(observedModel, _obsAtPick) { obs, atPick ->
            atPick == NEVER_PICKED || obs != atPick
        }.stateIn(scope, SharingStarted.Eagerly, true)

    /** Model names the SESSION itself reports as unavailable — parsed reactively
     *  from the "Claude <Model> is currently unavailable" banner (AgentMessage.Error
     *  kind="unavailable") in the stream. Reactive so the topbar recomposes the
     *  instant the banner lands and stops advertising a disabled model (e.g. the
     *  dead Fable 5 pin) before any /model probe has run. */
    val unavailableModelLabels: StateFlow<Set<String>> = messages
        .map { list ->
            list.asSequence()
                .filterIsInstance<AgentMessage.Error>()
                .filter { it.kind == "unavailable" }
                .mapNotNull {
                    ai.eight24family.conch.agent.claude.CLAUDE_MODEL_NAME_RX.find(it.text)
                        ?.value?.trim()?.replace(Regex("\\s+"), " ")
                }
                .toSet()
        }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /**
     * Reasoning effort the session is actually running at — mirrored from the
     * session file's effort events (Claude's `ultra_effort_enter` → "ultracode")
     * the same way [observedModel] mirrors the model. Reads the LATEST so a
     * mid-session `/effort` change wins. Never a hardcoded default.
     */
    val observedReasoning: StateFlow<String?> = messages
        .map { list ->
            list.asReversed().asSequence()
                .filterIsInstance<AgentMessage.System>()
                .mapNotNull { it.reasoning }
                .firstOrNull()
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Claude's auto-generated session title (the latest `ai-title`). The topbar
     *  shows this instead of the first user message. */
    val observedTitle: StateFlow<String?> = messages
        .map { list ->
            list.asReversed().asSequence()
                .filterIsInstance<AgentMessage.System>()
                .mapNotNull { it.title }
                .firstOrNull()
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Most-recently-reported working dir from any system event. */
    val observedCwd: StateFlow<String?> = messages
        .map { list ->
            list.asReversed().asSequence()
                .filterIsInstance<AgentMessage.System>()
                .mapNotNull { it.cwd }
                .firstOrNull()
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    // ──── Available model display names (probed from claude cli.js) ────
    // Seed SYNCHRONOUSLY from the in-memory label cache so the topbar resolves
    // the real model name ("Opus 4.8") from frame zero instead of flashing the
    // stale hardcoded fallback ("Opus 4.7"). The live probe overwrites this the
    // moment it lands, so a model change still updates.
    private val _availableModels = MutableStateFlow(cachedLabels(currentAgent.value))
    val availableModels: StateFlow<Map<String, String>> = _availableModels.asStateFlow()

    /** True while probeAvailableModels is in flight. */
    private val _modelsProbing = MutableStateFlow(false)
    val modelsProbing: StateFlow<Boolean> = _modelsProbing.asStateFlow()

    /** Model keys confirmed by some CLI's own registry (union across servers
     *  and runs). Drives [hiddenModels]; empty until the first authoritative
     *  catalog lands, which is the fail-open state. */
    private val _registryKeys = MutableStateFlow<Set<String>>(emptySet())

    /** Record keys an AUTHORITATIVE catalog just confirmed (union, never
     *  subtractive — a server on an older CLI may not un-confirm a model
     *  another server really offers). */
    private fun confirmRegistryKeys(agentName: String, keys: Set<String>) {
        if (keys.isEmpty()) return
        _registryKeys.value = _registryKeys.value + keys
        scope.launch {
            runCatching {
                ServiceLocator.preferences.addRegistryModelKeysForAgent(agentName, keys)
            }
        }
    }

    /**
     * True when the list on screen is the CACHE, because the live `/model` read
     * came back empty. Cleared by the next probe that returns anything.
     */
    private val _modelsStale = MutableStateFlow(false)
    val modelsStale: StateFlow<Boolean> = _modelsStale.asStateFlow()

    /** Whatever the CLI uses when no --model flag is passed. */
    private val _defaultModel = MutableStateFlow<String?>(null)
    val defaultModel: StateFlow<String?> = _defaultModel.asStateFlow()

    /** Effective CLI-default reasoning effort. */
    private val _defaultReasoning = MutableStateFlow<String?>(null)
    val defaultReasoning: StateFlow<String?> = _defaultReasoning.asStateFlow()

    /** Model parsed out of the session JSONL during the sessions-list discovery pass.
     *  Synthetic markers ("<synthetic>" from a STALE cached list row built before
     *  the grep filter landed) are dropped so the topbar never flashes them. */
    private val _sessionInitialModel = MutableStateFlow(
        initialSessionModel?.takeIf { it.isNotBlank() && !it.startsWith("<") }
    )
    val sessionInitialModel: StateFlow<String?> = _sessionInitialModel.asStateFlow()

    /** Reasoning effort parsed from the session's JSONL header. */
    private val _sessionInitialReasoning = MutableStateFlow(initialSessionReasoning)
    val sessionInitialReasoning: StateFlow<String?> = _sessionInitialReasoning.asStateFlow()

    /**
     * Catalog entries that exist for LABEL RESOLUTION only and must not be
     * offered as choices — TUI-scraper leftovers no registry has confirmed.
     * The user's own pick / the session's model are never hidden.
     *
     * ⚠ Declared HERE, below every flow it reads: Kotlin initialises
     * properties in source order, so referencing `_sessionInitialModel` from
     * further up the class would read a null that is not even nullable.
     */
    val hiddenModels: StateFlow<Set<String>> = combine(
        _availableModels, _registryKeys, selectedModel, _sessionInitialModel,
    ) { catalog, confirmed, pick, sessionModel ->
        ai.eight24family.conch.data.ModelProvenance.hidden(
            catalog = catalog.keys,
            registryConfirmed = confirmed,
            keepVisible = setOfNotNull(
                pick?.takeIf { it.isNotBlank() },
                sessionModel?.takeIf { it.isNotBlank() },
            ),
        )
    }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    /**
     * Cold-start hydrate: read whatever the spec's probe reported last time.
     * Per-agent — Claude reads its alias map, Codex reads its slug map from
     * `models_cache.json`. Lets the topbar resolve a known `selectedModel` slug to the
     * human label INSTANTLY on chat open, instead of flashing the raw slug for the
     * ~1-2s the live SSH probe takes.
     */
    fun hydrateFromCache() {
        scope.launch {
            val agentNow = currentAgent.value
            val saved = ServiceLocator.preferences.modelLabelsForAgent(agentNow.name).first()
            if (saved.isNotEmpty()) {
                rememberLabels(agentNow.name, saved)   // warm in-memory for frame-zero next time
                if (_availableModels.value.isEmpty()) _availableModels.value = saved
            } else if (agentNow == Agent.CLAUDE) {
                // Backwards-compat fallback: legacy claude-only key `model_alias_labels`
                // (no agent suffix). Read it ONLY for Claude.
                val legacy = ServiceLocator.preferences.modelLabels.first()
                if (legacy.isNotEmpty()) {
                    rememberLabels(agentNow.name, legacy)
                    if (_availableModels.value.isEmpty()) _availableModels.value = legacy
                }
            }
            // The CLI's own default model — this is what a BRAND-NEW chat shows
            // in the topbar, so it has to be here at frame zero rather than
            // after a ~2s probe (or never, if nothing re-probes this run).
            if (_defaultModel.value.isNullOrBlank()) {
                ServiceLocator.preferences.defaultModelForAgent(agentNow.name).first()
                    ?.let { _defaultModel.value = it }
            }
            // Registry provenance survives restarts — without the hydrate the
            // picker would show scraper leftovers again on every cold start.
            ServiceLocator.preferences.registryModelKeysForAgent(agentNow.name).first()
                .takeIf { it.isNotEmpty() }
                ?.let { _registryKeys.value = _registryKeys.value + it }
            // Re-arm the spec-level default so the FIRST chat of a cold start
            // launches on the model its own chip advertises. These globals only
            // exist in-process; without this the launch resolution starts blind
            // and guesses from catalog order.
            if (agentNow == Agent.CLAUDE) {
                if (ai.eight24family.conch.agent.claude.claudeDefaultModel == null) {
                    _defaultModel.value?.let {
                        ai.eight24family.conch.agent.claude.claudeDefaultModel = it
                    }
                }
                if (ai.eight24family.conch.agent.claude.claudeDefaultModelKey == null) {
                    ServiceLocator.preferences.defaultModelWireKeyForAgent(agentNow.name).first()
                        ?.let { ai.eight24family.conch.agent.claude.claudeDefaultModelKey = it }
                }
            }
            // Reasoning catalog: same cold-start treatment as the labels.
            // Spec-encoded blob (agent-agnostic here) — without it the
            // effort slider regresses to the spec's hardcoded fallback
            // ladder until the live probe lands (~8s).
            val savedCatalog = ServiceLocator.preferences
                .reasoningCatalogForAgent(agentNow.name).first()
            if (savedCatalog.isNotBlank() && _reasoningCatalog.value.isEmpty()) {
                val cat = AgentSpecRegistry[agentNow]
                    .deserializeReasoningCatalog(savedCatalog, _availableModels.value.keys)
                if (cat.isNotEmpty()) _reasoningCatalog.value = cat
            }
        }
    }

    /**
     * Find the claude-code CLI bundle (`cli.js`), grep out the model display names
     * baked into it. Result cached in [_availableModels] and used by the topbar/menu
     * to show real names rather than aliases. Same call also probes default-model and
     * default-reasoning so the topbar's sub-labels mirror what the CLI actually runs.
     */
    suspend fun probeAvailableModels(session: AgentSession, force: Boolean = false) {
        val tag = "SshAi-Models"
        val agentNow = currentAgent.value
        val spec = AgentSpecRegistry[agentNow]
        val exec = AgentExec { cmd -> session.execOnLive(cmd) }
        // [force] = the user tapped the model selector. Availability can
        // change WHILE connected (Fable 5 export-control suspension hit
        // mid-session), and a week-old connection would never re-probe on
        // the freshness gate. A tap means "show me what's available NOW",
        // so bypass the gate and re-run the live probe (the dropdown shows
        // the cached list instantly and refreshes when this lands). Guarded
        // against overlap by [_modelsProbing] at the call site. Startup
        // warm-up (GlobalPrefetcher → ModelCatalogPrefetcher) may have
        // probed this (server, agent) minutes ago — opening a chat must NOT
        // re-run the heavy PTY probe every time. Serve the warmed caches
        // and fall through to the cheap default probes below.
        if (!force && ai.eight24family.conch.data.ModelCatalogPrefetcher.isFresh(session.server.id, agentNow)) {
            android.util.Log.d(tag, "catalog fresh for ${spec.agent}@${session.server.id} — skipping heavy probe")
            if (_availableModels.value.isEmpty()) {
                val saved = ServiceLocator.preferences.modelLabelsForAgent(agentNow.name).first()
                if (saved.isNotEmpty()) _availableModels.value = saved
            }
            if (_reasoningCatalog.value.isEmpty()) {
                // Specs memoise their reasoning info from the warm-up probe
                // (Claude's effort stash, Codex's models_cache parse) —
                // rebuild the per-slug catalog from that; prefs blob as the
                // cross-process fallback.
                val keys = _availableModels.value.keys
                val rmap = keys.mapNotNull { slug ->
                    spec.reasoningInfoFor(slug)?.let { slug to it }
                }.toMap()
                if (rmap.isNotEmpty()) {
                    _reasoningCatalog.value = rmap
                } else {
                    val rawCat = ServiceLocator.preferences
                        .reasoningCatalogForAgent(agentNow.name).first()
                    if (rawCat.isNotBlank()) {
                        val cat = spec.deserializeReasoningCatalog(rawCat, keys)
                        if (cat.isNotEmpty()) _reasoningCatalog.value = cat
                    }
                }
            }
        } else {
        _modelsProbing.value = true
        val map = try {
            runCatching { spec.probeAvailableModels(exec) }
                .onFailure { android.util.Log.w(tag, "model probe failed for ${spec.agent}", it) }
                .getOrDefault(emptyMap())
        } finally {
            _modelsProbing.value = false
        }
        android.util.Log.d(tag, "extracted ${spec.agent} models: $map")
        if (map.isNotEmpty()) {
            val agentForCache = currentAgent.value
            // Show the MERGED catalog, not this probe's raw answer: the list the
            // user opens must never lose a model or step back a version because
            // one probe rendered a stale menu.
            val merged = rememberLabels(agentForCache.name, map)
            _availableModels.value = merged
            _modelsStale.value = false
            // Only an AUTHORITATIVE catalog (the CLI's own registry) may confirm
            // keys — a scrape or a guess must never promote itself.
            if (spec.catalogIsAuthoritative) confirmRegistryKeys(agentForCache.name, map.keys)
            scope.launch {
                ServiceLocator.preferences.setModelLabelsForAgent(agentForCache.name, map)
            }
            val rmap = merged.keys.mapNotNull { slug ->
                spec.reasoningInfoFor(slug)?.let { slug to it }
            }.toMap()
            _reasoningCatalog.value = rmap
            // Persist for cold-start hydrate (specs that opt in return
            // non-null — Claude's uniform effort catalog; Codex rebuilds
            // from models_cache.json instead and returns null).
            spec.serializeReasoningCatalog(rmap)?.let { rawCatalog ->
                scope.launch {
                    ServiceLocator.preferences.setReasoningCatalogForAgent(
                        agentForCache.name, rawCatalog,
                    )
                }
            }
            // Chat-open probe counts as a warm-up too — keeps the next
            // chat open (or the sweep) from re-running the PTY pass.
            ai.eight24family.conch.data.ModelCatalogPrefetcher.markProbed(session.server.id, agentNow)
        } else {
            // Probe came back EMPTY (PTY hiccup, CLI mid-update, a box that
            // answered slowly). That is not evidence the agent lost its models —
            // wiping the picker here is what left the user staring at an empty
            // list / the old hardcoded fallback. Keep the catalog we already
            // have; a genuine agent switch still clears via resetOnAgentSwitch.
            val agentNowName = currentAgent.value.name
            val fallback = cachedLabels(currentAgent.value)
                .ifEmpty { ServiceLocator.preferences.modelLabelsForAgent(agentNowName).first() }
            if (fallback.isNotEmpty()) {
                rememberLabels(agentNowName, fallback)
                _availableModels.value = fallback
                val rmap = fallback.keys.mapNotNull { slug ->
                    spec.reasoningInfoFor(slug)?.let { slug to it }
                }.toMap()
                if (rmap.isNotEmpty()) _reasoningCatalog.value = rmap
                _modelsStale.value = true
                android.util.Log.i(tag, "${spec.agent} probe empty — keeping cached catalog ${fallback.keys}")
            } else {
                _availableModels.value = emptyMap()
                _reasoningCatalog.value = emptyMap()
            }
        }
        }
        val defaultModelValue = runCatching { spec.probeDefaultModel(exec) }
            .onFailure { android.util.Log.w(tag, "default-model probe failed for ${spec.agent}", it) }
            .getOrNull()
        android.util.Log.d(tag, "default ${spec.agent} model: $defaultModelValue")
        _defaultModel.value = defaultModelValue
        // PERSIST it: the value only exists after a live probe, so keeping it in
        // memory meant every process restart showed an empty model chip on a
        // fresh chat until something re-probed. Cached, the next cold start can
        // paint the real default at frame zero.
        if (!defaultModelValue.isNullOrBlank()) {
            runCatching {
                ServiceLocator.preferences.setDefaultModelForAgent(agentNow.name, defaultModelValue)
            }.onFailure { android.util.Log.w(tag, "persist default model failed", it) }
        }
        val defaultReasoningValue = runCatching { spec.probeDefaultReasoning(exec) }
            .onFailure { android.util.Log.w(tag, "default-reasoning probe failed for ${spec.agent}", it) }
            .getOrNull()
        android.util.Log.d(tag, "default ${spec.agent} reasoning: $defaultReasoningValue")
        _defaultReasoning.value = defaultReasoningValue
    }

    /**
     * Adopt the model catalog from a live session's `initialize` handshake
     * (see AgentSession.claudeInitState) — the CLI's own registry, arriving
     * FREE with every persistent-channel launch. Feeds exactly the caches the
     * probe path feeds (monotonic label merge, prefs, reasoning catalog,
     * default model, prefetcher freshness), so the picker/topbar cannot tell
     * the sources apart — except this one is always fresh.
     */
    fun adoptClaudeInit(
        st: ai.eight24family.conch.agent.claude.ClaudeInitState,
        serverId: String,
    ) {
        if (currentAgent.value != Agent.CLAUDE) return
        val map = ai.eight24family.conch.agent.claude.ClaudeInitState.toPickerMap(st)
        if (map.isEmpty()) return
        val agentName = Agent.CLAUDE.name
        val merged = rememberLabels(agentName, map)
        _availableModels.value = merged
        _modelsStale.value = false
        // The handshake IS the registry — these keys are real choices; whatever
        // else the catalog carries is a scraper-era leftover.
        confirmRegistryKeys(agentName, map.keys)
        scope.launch {
            ServiceLocator.preferences.setModelLabelsForAgent(agentName, map)
        }
        val spec = AgentSpecRegistry[Agent.CLAUDE]
        // ClaudeSpec.adoptInitState already ran (AgentSession publishes the
        // handshake through it), so reasoningInfoFor serves the fresh ladder.
        val rmap = merged.keys.mapNotNull { slug ->
            spec.reasoningInfoFor(slug)?.let { slug to it }
        }.toMap()
        if (rmap.isNotEmpty()) {
            _reasoningCatalog.value = rmap
            spec.serializeReasoningCatalog(rmap)?.let { raw ->
                scope.launch {
                    ServiceLocator.preferences.setReasoningCatalogForAgent(agentName, raw)
                }
            }
        }
        ai.eight24family.conch.agent.claude.claudeDefaultModel?.let { def ->
            _defaultModel.value = def
            scope.launch {
                runCatching { ServiceLocator.preferences.setDefaultModelForAgent(agentName, def) }
            }
        }
        // PERSIST THE WIRE KEY TOO. The launch resolution reads the key, not
        // the label, and it had nothing to read on a cold start — so it fell
        // through to "first catalog entry" and sent `--model sonnet` under an
        // "Opus 5 1M" chip (2026-08-02, caught on device).
        ai.eight24family.conch.agent.claude.claudeDefaultModelKey?.let { k ->
            scope.launch {
                runCatching { ServiceLocator.preferences.setDefaultModelWireKeyForAgent(agentName, k) }
            }
        }
        // A handshake IS a probe — keep the sweep from re-spawning a CLI.
        ai.eight24family.conch.data.ModelCatalogPrefetcher.markProbed(serverId, Agent.CLAUDE)
        android.util.Log.d(
            "SshAi-Models",
            "adopted init catalog: ${map.keys} default=${_defaultModel.value}",
        )
    }

    /**
     * Pin a model slug for this chat. Per-chat key when resumeId is known, transient
     * pending state otherwise (migrated by [observeResumeIdForBackfill] on the first
     * thread_id assignment). Never per-agent — new chats start with config.toml default.
     */
    fun setModel(model: String?, applyToLiveSession: (String?) -> Unit) {
        scope.launch {
            val prefs = ServiceLocator.preferences
            _obsAtPick.value = observedModel.value
            val rid = resumeId.value
            if (rid != null) {
                prefs.setSelectedModelForChat(rid, model)
            } else {
                _pendingModelPick.value = model?.takeIf { it.isNotBlank() }
            }
            applyToLiveSession(model?.takeIf { it.isNotBlank() })
        }
    }

    /** The effort the session had REPORTED when the user last picked one; the
     *  sentinel means "never picked in this chat". Mirrors [_obsAtPick]. */
    private val _obsAtPickReasoning = MutableStateFlow<String?>(NEVER_PICKED)

    /**
     * False while the live effort observation is the same one that was on
     * screen when the user picked — i.e. nothing new has been reported since,
     * so the pick stands. A pick can never equal a PAST observation, so this
     * must compare the OBSERVATION to what it was at pick time, never "did the
     * list grow" (the mistake that cost the model chip two fixes).
     */
    val reasoningPickIsNewer: StateFlow<Boolean> =
        combine(observedReasoning, _obsAtPickReasoning) { obs, atPick ->
            atPick != NEVER_PICKED && obs == atPick
        }.stateIn(scope, SharingStarted.Eagerly, false)

    /** Same isolation rules as [setModel] but for reasoning effort. */
    fun setReasoning(effort: String?, applyToLiveSession: (String?) -> Unit) {
        scope.launch {
            val prefs = ServiceLocator.preferences
            _obsAtPickReasoning.value = observedReasoning.value
            val rid = resumeId.value
            if (rid != null) {
                prefs.setSelectedReasoningForChat(rid, effort)
            } else {
                _pendingReasoningPick.value = effort?.takeIf { it.isNotBlank() }
            }
            applyToLiveSession(effort?.takeIf { it.isNotBlank() })
        }
    }

    /**
     * Clear all per-agent model state — Claude's `Opus 4.7` was leaking into the Codex
     * topbar because availableModels / defaultModel weren't being cleared on agent switch.
     */
    fun resetOnAgentSwitch() {
        _availableModels.value = emptyMap()
        _defaultModel.value = null
        _defaultReasoning.value = null
        _sessionInitialModel.value = null
        _sessionInitialReasoning.value = null
        _reasoningCatalog.value = emptyMap()
        _pendingModelPick.value = null
        _pendingReasoningPick.value = null
        _obsAtPick.value = NEVER_PICKED
    }

    /** Seeds the topbar's model display from the listing-time probe BEFORE startNewChat
     *  triggers history load + SSH open. */
    fun setSessionInitialModel(model: String?) {
        // Never the "<synthetic>" marker — only a real model id.
        val real = model?.takeIf { it.isNotBlank() && !it.startsWith("<") }
        _sessionInitialModel.value = real
        // REMEMBER IT AS THE AGENT'S DEFAULT. This value comes from the CLI's own
        // `system.init` (and the per-turn `message.model`), so it is whatever the
        // CLI ACTUALLY RAN — no menu scraping, no hardcoded family list. That
        // makes it survive CLI upgrades for free: when the box moved to 2.1.220
        // and started answering as "Opus 5", the /model TUI scrape returned
        // nothing and a new chat showed an empty model chip, while this record
        // was sitting right there in the stream (user, 2026-07-25 — a repeat of
        // exactly the "be ready for agent updates" failure). A brand-new chat
        // now opens on the last model this agent really used, and it re-learns
        // itself the moment a new family ships.
        if (real != null) {
            scope.launch {
                val agentNow = currentAgent.value
                runCatching {
                    ServiceLocator.preferences.setDefaultModelForAgent(agentNow.name, real)
                }.onFailure { android.util.Log.w("SshAi-Models", "persist last model failed", it) }
            }
        }
    }

    fun setSessionInitialReasoning(reasoning: String?) {
        _sessionInitialReasoning.value = reasoning
    }

    /** Read-only accessors used by ChatViewModel when applying model/reasoning to a
     *  freshly-opened AgentSession (the `s.modelOverride = ...` line). */
    fun currentSessionInitialModel(): String? = _sessionInitialModel.value
    fun currentSessionInitialReasoning(): String? = _sessionInitialReasoning.value

    /**
     * Backfill the per-chat model key on the null → non-null resumeId transition —
     * but ONLY for chats that started *without* a resumeId (brand-new conversations
     * whose thread_id is being assigned right now by the CLI).
     *
     * For chats opened from the sessions list (initialResumeId != null), DO NOT touch
     * the per-chat key. They either:
     *   - already have a per-chat pick → reading it works fine,
     *   - have no per-chat pick → topbar correctly falls through to `sessionInitialModel`.
     * Backfilling here would write whatever the current per-agent value happens to be
     * (which is exactly the "leak" the user reported — pick X in chat A, then open
     * chat B and the backfill writes X into per-chat[B]).
     *
     * Must be invoked by the caller only when `initialResumeId == null`.
     */
    fun observeResumeIdForBackfill(onMigrated: suspend (rid: String) -> Unit) {
        scope.launch {
            var lastPinned: String? = null
            resumeId.collect { rid ->
                if (rid == null || rid == lastPinned) return@collect
                val prefs = ServiceLocator.preferences
                // Migrate transient picks to the per-chat key. After migration the
                // picks are owned by per-chat storage — clear the transient.
                val pendingModel = _pendingModelPick.value
                if (!pendingModel.isNullOrBlank()) {
                    val existingModel = prefs.selectedModelForChat(rid).first()
                    if (existingModel.isNullOrBlank()) {
                        prefs.setSelectedModelForChat(rid, pendingModel)
                    }
                    _pendingModelPick.value = null
                }
                val pendingReasoning = _pendingReasoningPick.value
                if (!pendingReasoning.isNullOrBlank()) {
                    val existingReasoning = prefs.selectedReasoningForChat(rid).first()
                    if (existingReasoning.isNullOrBlank()) {
                        prefs.setSelectedReasoningForChat(rid, pendingReasoning)
                    }
                    _pendingReasoningPick.value = null
                }
                onMigrated(rid)
                lastPinned = rid
            }
        }
    }
}
