package ai.eight24family.conch.ui.viewmodel

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.AgentSession
import ai.eight24family.conch.agent.SlashCommand
import ai.eight24family.conch.agent.SlashCommandKind
import ai.eight24family.conch.agent.SlashCommands
import ai.eight24family.conch.agent.spec.AgentSpecRegistry
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
    /** One-line, dismissible message shown over the chat. */
    private val notice: (String) -> Unit = {},
    /** Send text as an ordinary turn through the VM's own send path — bubble,
     *  visible outbox, cancel — without re-entering slash dispatch. */
    private val sendAsTurn: (String) -> Unit = {},
    /** Put LOCAL output (a plan, a cost table) into the transcript as
     *  assistant-style text - which is how the CLI itself renders the output of a
     *  local command, and the only place long content can be read on a phone. */
    private val emitLocal: (String) -> Unit = {},
) {
    private val _customCommands = MutableStateFlow<List<SlashCommand>>(emptyList())
    val customCommands: StateFlow<List<SlashCommand>> = _customCommands.asStateFlow()

    /** The CLI's OWN commands and skills, straight from the `initialize`
     *  handshake — 45 of them on a stock install, none of which the palette
     *  used to show. Ours still win on name collisions. */
    private val _agentCommands = MutableStateFlow<List<SlashCommand>>(emptyList())
    val agentCommands: StateFlow<List<SlashCommand>> = _agentCommands.asStateFlow()

    fun setAgentCommands(cmds: List<SlashCommand>) {
        _agentCommands.value = SlashCommands.mergeAgentCommands(cmds, _customCommands.value)
    }

    private val _memory = MutableStateFlow(MemoryDocs())
    val memory: StateFlow<MemoryDocs> = _memory.asStateFlow()

    /**
     * If [text] starts with `/`, run the matching command and return true (so the caller
     * does NOT also send the text as a chat prompt).
     */
    fun runSlash(text: String): Boolean {
        val (name, args) = SlashCommands.parse(text) ?: return false
        val cmd = SlashCommands.find(name, _customCommands.value + _agentCommands.value)
            ?: return false
        dispatchSlash(cmd, args)
        return true
    }

    fun dispatchSlash(cmd: SlashCommand, args: String = "") {
        val agent = currentAgent()
        when (cmd.kind) {
            SlashCommandKind.NEW_SESSION -> newSession()
            // Control-channel capabilities. Each one asks the CLI rather than
            // guessing locally, and each says plainly when the channel cannot
            // answer instead of showing a zero or an empty panel.
            SlashCommandKind.SESSION_COST -> askOverControl("cost") { it.sessionCostText() }
            SlashCommandKind.CLI_VERSION -> askOverControl("version") { it.cliVersion() }
            SlashCommandKind.SHOW_PLAN -> askOverControl("plan", "No plan yet — the agent has not written one.") {
                it.planText()
            }
            SlashCommandKind.BACKGROUND_RUNNING -> {
                val s = sessionAccess() ?: return
                scope.launch {
                    val ok = s.backgroundRunningTasks()
                    notice(
                        if (ok) "Detached what was running — it keeps going and the turn is free."
                        else "Nothing to detach, or the CLI refused.",
                    )
                }
            }
            SlashCommandKind.STOP_TASK -> {
                val id = args.trim()
                if (id.isBlank()) {
                    notice("/stoptask needs a task id — the agent panel shows them.")
                    return
                }
                val s = sessionAccess() ?: return
                scope.launch {
                    notice(if (s.stopTask(id)) "Task $id stopped." else "Could not stop $id.")
                }
            }
            SlashCommandKind.INJECT_DIFF -> injectGitDiff()
            SlashCommandKind.INIT_REPO -> sendInitPrompt()
            SlashCommandKind.OPEN_MEMORY -> {
                if (!agent.supportsMemory) {
                    // Our editor is Claude-only; the COMMAND is not ours to
                    // refuse. Hand it to the CLI rather than telling the user
                    // their own tool doesn't exist.
                    sendAgentBuiltin(cmd, args)
                } else {
                    setModal(ChatModal.Memory)
                    refreshMemory()
                }
            }
            SlashCommandKind.OPEN_AGENTS -> {
                if (!agent.supportsSubagents) {
                    sendAgentBuiltin(cmd, args)
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
            // The CLI runs its own commands from a plain turn — that is how
            // /compact, /context, /doctor and every skill are invoked. We send
            // exactly what the user would have typed. `/loop` is the one
            // command that must OUTLIVE this chat, so it does not run inside it
            // — see [runLoopDetached]. ⚠ NO COMMAND OF THE CLI'S MAY CREATE A
            // SESSION BEHIND THE USER'S BACK. `/loop` was briefly routed
            // through `--bg --fork-session` to survive the app closing; it
            // inherited the conversation, and the fork showed up in the list as
            // another copy of the chat — four of them before the user counted.
            // Forking stays where it belongs: the explicit menu item.
            SlashCommandKind.AGENT_BUILTIN -> sendAgentBuiltin(cmd, args)
            SlashCommandKind.RUN_BACKGROUND -> runInBackground(args)
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
            // `startReview` drives CODEX's purpose-built reviewer. Every other
            // CLI has its own review command or skill (Claude ships
            // /code-review) — send the line and let it answer.
            sendAgentBuiltin(SlashCommand("review", "", SlashCommandKind.AGENT_BUILTIN), args)
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
        scope.launch(Dispatchers.IO) {
            val s = sessionAccess() ?: return@launch
            val cwd = observedCwd()
            val gitCmd = if (cwd != null && cwd.isNotBlank())
                "cd ${shQuote(cwd)} && git diff --no-color HEAD"
            else "git diff --no-color HEAD"
            // ASK THE WORKER FIRST. It resolves the base ref itself (working tree
            // vs HEAD, falling back to branch-vs-merge-base when the tree is
            // clean - the case where our own `git diff HEAD` returned nothing and
            // we told the user "no changes") and applies its own caps, so a huge
            // repo cannot hang the turn. The shell stays as the fallback for a
            // chat that is not on a persistent Claude stream.
            val out = (
                ai.eight24family.conch.util.SilentlyTry.logged("Conch-Slash", "worker diff") {
                    s.workspaceDiffText()
                }?.takeIf { it.isNotBlank() }
                    ?: s.execOnLive("bash -lc " + shQuote(gitCmd)).orEmpty()
                ).trimEnd()
            if (out.isBlank()) {
                setModal(ChatModal.Unsupported("/diff", "git diff is empty (no changes vs HEAD)."))
                return@launch
            }
            val truncated = if (out.length > 60_000) out.take(60_000) + "\n\n[…truncated, ${out.length - 60_000} bytes elided]" else out
            val prompt = "Here is the current `git diff` for review:\n\n```diff\n$truncated\n```"
            sendAsTurn(prompt)
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
        sendAsTurn(prompt)
    }

    /**
     * Send a per-CLI "draft a memory file for this repo" prompt.
     */
    fun sendInitPrompt() {
        val s = sessionAccess() ?: return
        val agent = currentAgent()
        val filename = agent.memoryFilename
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
        sendAsTurn(prompt)
    }

    /** Run one of the CLI's own commands the way the CLI runs them: as the
     *  literal `/name args` turn the user would have typed. */
    private fun sendAgentBuiltin(cmd: SlashCommand, args: String) {
        val line = if (args.isBlank()) "/${cmd.name}" else "/${cmd.name} $args"
        sendAsTurn(line)
    }

    /**
     * `/bg <task>` — hand the task to a detached agent on the server.
     *
     * `claude --bg` returns the moment the job is spawned (`backgrounded ·
     * <id>`), and the job outlives this chat's process, the SSH channel and the
     * app: that is the whole point on a phone. The agent writes an ordinary
     * session file, so it appears in the sessions list by itself — we don't
     * mirror its output here, we say where it went.
     */
    /**
     * Ask the CLI something over the control channel and put the answer in front
     * of the user, as a row they can read rather than a toast that disappears.
     *
     * The channel legitimately cannot answer sometimes (this chat is not on a
     * persistent Claude stream, the transport is down, the responder refused) —
     * that is reported, never papered over with a blank panel or a zero.
     */
    private fun askOverControl(
        label: String,
        emptyText: String = "Nothing to show — the CLI had no answer.",
        ask: suspend (ai.eight24family.conch.agent.AgentSession) -> String?,
    ) {
        val s = sessionAccess() ?: return
        scope.launch {
            val text = ai.eight24family.conch.util.SilentlyTry.logged("Conch-Slash", "control ask $label") {
                ask(s)
            }
            if (text.isNullOrBlank()) { notice(emptyText); return@launch }
            // Long content (a plan, a cost table) belongs in the transcript, not
            // in a one-line notice that truncates it.
            if (text.length > 160 || text.contains('\n')) {
                emitLocal("$label\n\n" + text.trim())
            } else {
                notice("$label · ${text.trim()}")
            }
        }
    }

    private fun runInBackground(args: String) {
        val task = args.trim()
        if (task.isBlank()) {
            notice("/bg needs a task — e.g. `/bg run the test suite and fix what breaks`")
            return
        }
        val agent = currentAgent()
        if (agent != Agent.CLAUDE) {
            // Only Claude has `--bg`. Saying so beats spawning something else.
            notice("${agent.displayName} has no background mode — this is Claude Code only.")
            return
        }
        val s = sessionAccess() ?: return
        scope.launch(Dispatchers.IO) {
            val cwd = observedCwd()
            val prefix = if (!cwd.isNullOrBlank()) "cd ${shQuote(cwd)} && " else ""
            val out = s.execOnLive("bash -lc " + shQuote(prefix + "claude --bg " + shQuote(task)))
                .orEmpty()
            // "backgrounded · 941a5f38" — the id is what every follow-up
            // (`claude logs`, `claude stop`) is keyed by, so it is the one
            // thing worth putting in front of the user.
            val id = BACKGROUNDED.find(out)?.groupValues?.get(1)
            notice(
                if (id != null) "Started in the background · $id — it keeps running if you close the app. It will appear in your sessions list."
                else out.trim().takeIf { it.isNotBlank() }?.take(180)
                    ?: "Could not start a background task."
            )
        }
    }

    private fun sendCustom(cmd: SlashCommand, args: String) {
        val s = sessionAccess() ?: return
        val body = (cmd.customPrompt ?: return)
            .replace("\$ARGUMENTS", args)
            .trim()
        if (body.isBlank()) return
        sendAsTurn(body)
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

    /** `backgrounded · 941a5f38` — the CLI's own confirmation line. The id is
     *  what `claude logs` / `claude stop` are keyed by. */
    private val BACKGROUNDED = Regex("""backgrounded\s*·\s*([0-9a-f]{6,})""")

    private fun shQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
