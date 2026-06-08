package ai.eight24family.conch.ui.viewmodel

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.AgentSession
import ai.eight24family.conch.agent.spec.AgentExec
import ai.eight24family.conch.agent.spec.AgentSpecRegistry
import ai.eight24family.conch.agent.spec.ModelReasoningInfo
import ai.eight24family.conch.agent.spec.PtyProbe
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
        /** In-memory mirror of the last alias→label map each agent's probe
         *  returned (also persisted in prefs.modelLabelsForAgent). Lets a chat
         *  seed [availableModels] SYNCHRONOUSLY at construction so the topbar
         *  shows the real model name from frame zero instead of the stale
         *  hardcoded fallback. Warmed by probes and the disk hydrate. */
        private val labelMemory = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
        fun cachedLabels(agent: Agent): Map<String, String> = labelMemory[agent.name].orEmpty()
        fun rememberLabels(agentName: String, map: Map<String, String>) {
            if (map.isNotEmpty()) labelMemory[agentName] = map
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

    /** Whatever the CLI uses when no --model flag is passed. */
    private val _defaultModel = MutableStateFlow<String?>(null)
    val defaultModel: StateFlow<String?> = _defaultModel.asStateFlow()

    /** Effective CLI-default reasoning effort. */
    private val _defaultReasoning = MutableStateFlow<String?>(null)
    val defaultReasoning: StateFlow<String?> = _defaultReasoning.asStateFlow()

    /** Model parsed out of the session JSONL during the sessions-list discovery pass. */
    private val _sessionInitialModel = MutableStateFlow(initialSessionModel)
    val sessionInitialModel: StateFlow<String?> = _sessionInitialModel.asStateFlow()

    /** Reasoning effort parsed from the session's JSONL header. */
    private val _sessionInitialReasoning = MutableStateFlow(initialSessionReasoning)
    val sessionInitialReasoning: StateFlow<String?> = _sessionInitialReasoning.asStateFlow()

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
        }
    }

    /**
     * Find the claude-code CLI bundle (`cli.js`), grep out the model display names
     * baked into it. Result cached in [_availableModels] and used by the topbar/menu
     * to show real names rather than aliases. Same call also probes default-model and
     * default-reasoning so the topbar's sub-labels mirror what the CLI actually runs.
     */
    suspend fun probeAvailableModels(session: AgentSession) {
        val tag = "SshAi-Models"
        val spec = AgentSpecRegistry[currentAgent.value]
        val exec = AgentExec { cmd -> session.execOnLive(cmd) }
        val pty = PtyProbe { session.probeModelMenu() }
        _modelsProbing.value = true
        val map = try {
            runCatching { spec.probeAvailableModels(exec, pty) }
                .onFailure { android.util.Log.w(tag, "model probe failed for ${spec.agent}", it) }
                .getOrDefault(emptyMap())
        } finally {
            _modelsProbing.value = false
        }
        android.util.Log.d(tag, "extracted ${spec.agent} models: $map")
        if (map.isNotEmpty()) {
            _availableModels.value = map
            val agentForCache = currentAgent.value
            rememberLabels(agentForCache.name, map)   // warm in-memory for frame-zero on next open
            scope.launch {
                ServiceLocator.preferences.setModelLabelsForAgent(agentForCache.name, map)
            }
            val rmap = map.keys.mapNotNull { slug ->
                spec.reasoningInfoFor(slug)?.let { slug to it }
            }.toMap()
            _reasoningCatalog.value = rmap
        } else {
            _availableModels.value = emptyMap()
            _reasoningCatalog.value = emptyMap()
        }
        val defaultModelValue = runCatching { spec.probeDefaultModel(exec) }
            .onFailure { android.util.Log.w(tag, "default-model probe failed for ${spec.agent}", it) }
            .getOrNull()
        android.util.Log.d(tag, "default ${spec.agent} model: $defaultModelValue")
        _defaultModel.value = defaultModelValue
        val defaultReasoningValue = runCatching { spec.probeDefaultReasoning(exec) }
            .onFailure { android.util.Log.w(tag, "default-reasoning probe failed for ${spec.agent}", it) }
            .getOrNull()
        android.util.Log.d(tag, "default ${spec.agent} reasoning: $defaultReasoningValue")
        _defaultReasoning.value = defaultReasoningValue
    }

    /**
     * Pin a model slug for this chat. Per-chat key when resumeId is known, transient
     * pending state otherwise (migrated by [observeResumeIdForBackfill] on the first
     * thread_id assignment). Never per-agent — new chats start with config.toml default.
     */
    fun setModel(model: String?, applyToLiveSession: (String?) -> Unit) {
        scope.launch {
            val prefs = ServiceLocator.preferences
            val rid = resumeId.value
            if (rid != null) {
                prefs.setSelectedModelForChat(rid, model)
            } else {
                _pendingModelPick.value = model?.takeIf { it.isNotBlank() }
            }
            applyToLiveSession(model?.takeIf { it.isNotBlank() })
        }
    }

    /** Same isolation rules as [setModel] but for reasoning effort. */
    fun setReasoning(effort: String?, applyToLiveSession: (String?) -> Unit) {
        scope.launch {
            val prefs = ServiceLocator.preferences
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
    }

    /** Seeds the topbar's model display from the listing-time probe BEFORE startNewChat
     *  triggers history load + SSH open. */
    fun setSessionInitialModel(model: String?) {
        _sessionInitialModel.value = model
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
