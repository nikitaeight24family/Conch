package ai.eight24family.conch.ui.viewmodel

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.AgentSession
import ai.eight24family.conch.agent.SlashCommand
import ai.eight24family.conch.agent.SlashCommandKind
import ai.eight24family.conch.agent.SlashCommands
import ai.eight24family.conch.agent.spec.AgentSpecRegistry
import ai.eight24family.conch.analytics.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Slash-command dispatcher.
 *
 * Handles `/` commands typed in the prompt bar:
 *   - Built-in kinds (NEW_SESSION, INJECT_DIFF, INIT_REPO, OPEN_MEMORY, OPEN_AGENTS,
 *     OPEN_MODEL_PICKER) → publishes a [ChatModal] or fires a callback.
 *   - User-authored Markdown commands (Claude's `~/.claude/commands/`) → discovered via
 *     [probeCustomCommands] and dispatched as `customPrompt` text sent to the agent.
 *
 * Owns:
 *  - [customCommands] — discovered slash commands (refreshed on session open).
 *  - [memory] — loaded memory docs displayed by the [ChatModal.Memory] sheet.
 *  - The slash modal flag is on ChatViewModel; this helper pokes it via [setModal].
 *
 * Built-in actions that have to drive the AgentSession (`/diff`, `/init`, custom)
 * use the [sessionAccess] closure to fetch the current `(session, currentAgent)`
 * tuple and the [postSendUpdate] closure to bump `_resumeId` / refresh sessions
 * when the CLI assigns a new thread id mid-conversation.
 *
 * See ChatViewModel.kt prior to extraction for the original inline comments.
 */
internal class ChatViewModelSlash(
    private val scope: CoroutineScope,
    private val serverId: String,
    private val currentAgent: () -> Agent,
    private val currentLocalSessionId: () -> String?,
    /** (current AgentSession, currentChatId) — null when no session is open. */
    private val sessionAccess: () -> AgentSession?,
    private val observedCwd: () -> String?,
    /** True when the CLI for the current agent supports `~/.<agent>/commands/`. */
    private val setModal: (ChatModal?) -> Unit,
    /** After s.send(prompt), the CLI may assign a fresh thread_id (`s.agentSessionId`).
     *  This closure: a) bumps `_resumeId`, b) refreshes the sessions list. */
    private val postSendUpdate: (newAgentSessionId: String?) -> Unit,
    /** Memory editor display. */
    private val newSession: () -> Unit,
) {
    private val _customCommands = MutableStateFlow<List<SlashCommand>>(emptyList())
    val customCommands: StateFlow<List<SlashCommand>> = _customCommands.asStateFlow()

    private val _memory = MutableStateFlow(MemoryDocs())
    val memory: StateFlow<MemoryDocs> = _memory.asStateFlow()

    /**
     * If [text] starts with `/`, run the matching command and return true (so the caller
     * does NOT also send the text as a chat prompt).
     */
    fun runSlash(text: String): Boolean {
        val (name, args) = SlashCommands.parse(text) ?: return false
        val cmd = SlashCommands.find(name, _customCommands.value) ?: run {
            setModal(ChatModal.Unsupported(name, "no such command"))
            return true
        }
        dispatchSlash(cmd, args)
        return true
    }

    fun dispatchSlash(cmd: SlashCommand, args: String = "") {
        val agent = currentAgent()
        when (cmd.kind) {
            SlashCommandKind.NEW_SESSION -> newSession()
            SlashCommandKind.INJECT_DIFF -> injectGitDiff()
            SlashCommandKind.INIT_REPO -> sendInitPrompt()
            SlashCommandKind.OPEN_MEMORY -> {
                if (!agent.supportsMemory) {
                    setModal(ChatModal.Unsupported(
                        cmd.name,
                        "${agent.displayName} doesn't use CLAUDE.md. Memory editor is only wired up for Claude Code right now."
                    ))
                } else {
                    setModal(ChatModal.Memory)
                    refreshMemory()
                }
            }
            SlashCommandKind.OPEN_AGENTS -> {
                if (!agent.supportsSubagents) {
                    setModal(ChatModal.Unsupported(
                        cmd.name,
                        "${agent.displayName} doesn't have subagents. This feature is Claude Code only."
                    ))
                } else {
                    setModal(ChatModal.Unsupported(
                        cmd.name,
                        "Tap the 🤖 icon in the topbar — subagents now have a full editor."
                    ))
                }
            }
            SlashCommandKind.OPEN_MODEL_PICKER -> setModal(ChatModal.ModelHint)
            SlashCommandKind.REVIEW -> startReview(args)
            SlashCommandKind.CUSTOM -> sendCustom(cmd, args)
        }
    }

    /**
     * Run a Codex code review. `/review` → uncommitted changes ("before I
     * push"); `/review <base-branch>` → review against that branch. Codex-only
     * (the CLI's purpose-built reviewer); other agents get a clear hint.
     */
    fun startReview(args: String) {
        val agent = currentAgent()
        if (agent != Agent.CODEX) {
            setModal(ChatModal.Unsupported(
                "review",
                "Code review is a Codex feature. Switch this server's agent to Codex to use /review."
            ))
            return
        }
        val s = sessionAccess() ?: return
        scope.launch {
            s.startReview(args.trim())
            postSendUpdate(s.agentSessionId)
        }
    }

    /**
     * Inject `git diff HEAD` of the current cwd as a code-fenced block in a new prompt
     * to the agent. Public — also triggered by the [diff] button in PromptBar, not just
     * by typing `/diff`.
     */
    fun injectGitDiff() {
        Telemetry.attachmentUploaded(Telemetry.AttachmentKind.GIT_DIFF)
        scope.launch(Dispatchers.IO) {
            val s = sessionAccess() ?: return@launch
            val cwd = observedCwd()
            val gitCmd = if (cwd != null && cwd.isNotBlank())
                "cd ${shQuote(cwd)} && git diff --no-color HEAD"
            else "git diff --no-color HEAD"
            val out = s.execOnLive("bash -lc " + shQuote(gitCmd)).orEmpty().trimEnd()
            if (out.isBlank()) {
                setModal(ChatModal.Unsupported("/diff", "git diff is empty (no changes vs HEAD)."))
                return@launch
            }
            val truncated = if (out.length > 60_000) out.take(60_000) + "\n\n[…truncated, ${out.length - 60_000} bytes elided]" else out
            val prompt = "Here is the current `git diff` for review:\n\n```diff\n$truncated\n```"
            s.send(prompt)
            postSendUpdate(s.agentSessionId)
        }
    }

    /** Open the memory editor — wired to a topbar icon, not just `/memory`. */
    fun openMemoryEditor() {
        setModal(ChatModal.Memory)
        refreshMemory()
    }

    /**
     * Send a per-CLI prompt that asks the agent to relax its own approval settings
     * persistently. The exact keys differ per CLI; the prompt names them explicitly
     * so the agent doesn't have to guess.
     */
    fun sendDisableApprovalsPrompt() {
        val s = sessionAccess() ?: return
        val agent = currentAgent()
        val prompt = AgentSpecRegistry[agent].disableApprovalsPrompt
        scope.launch {
            s.send(prompt)
            postSendUpdate(s.agentSessionId)
        }
    }

    /**
     * Send a per-CLI "draft a memory file for this repo" prompt.
     */
    fun sendInitPrompt() {
        val s = sessionAccess() ?: return
        val agent = currentAgent()
        val filename = agent.memoryFilename
        Telemetry.attachmentUploaded(Telemetry.AttachmentKind.INIT_PROMPT)
        val prompt = """
            Please analyse this codebase and create a $filename file containing:
            1. Build, lint, and test commands — especially how to run a single test.
            2. Code-style guidelines: imports, formatting, type usage, naming
               conventions, error handling — anything an agent needs to write
               idiomatic code here.

            Usage notes:
            - The file you create will be read by agentic coding agents (such as
              yourself) on every turn while operating in this repository. Keep
              it tight — about 20 lines.
            - If a $filename already exists, suggest improvements rather than
              overwriting blindly.
            - Pull in any rules from .cursor/rules/, .cursorrules, or
              .github/copilot-instructions.md if they exist.
            - Verify with me before finalising.
        """.trimIndent()
        scope.launch {
            s.send(prompt)
            postSendUpdate(s.agentSessionId)
        }
    }

    private fun sendCustom(cmd: SlashCommand, args: String) {
        val s = sessionAccess() ?: return
        val body = (cmd.customPrompt ?: return)
            .replace("\$ARGUMENTS", args)
            .trim()
        if (body.isBlank()) return
        scope.launch {
            s.send(body)
            postSendUpdate(s.agentSessionId)
        }
    }

    fun refreshMemory() {
        scope.launch(Dispatchers.IO) {
            _memory.value = ai.eight24family.conch.data.MemoryService(
                serverId = serverId,
                agent = currentAgent(),
                chatId = currentLocalSessionId(),
            ).load()
        }
    }

    fun saveMemory(memScope: MemoryScope, contents: String) {
        scope.launch(Dispatchers.IO) {
            ai.eight24family.conch.data.MemoryService(
                serverId = serverId,
                agent = currentAgent(),
                chatId = currentLocalSessionId(),
            ).save(memScope, contents)
            refreshMemory()
        }
    }

    /**
     * Discover user-defined slash commands at session-open time. Delegates to the spec —
     * Claude has user-authored .md commands under `~/.claude/commands/`, Codex has
     * nothing, Gemini's TOML commands are headless-undocumented (treated as
     * unsupported).
     */
    suspend fun probeCustomCommands(session: AgentSession) {
        val spec = AgentSpecRegistry[currentAgent()]
        val script = spec.customCommandsScript
        if (script == null) {
            _customCommands.value = emptyList()
            return
        }
        val out = session.execOnLive("bash -lc " + shQuote(script)).orEmpty()
        _customCommands.value = spec.parseCustomCommands(out)
    }

    private fun shQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
