package ai.eight24family.conch.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.AgentScope
import ai.eight24family.conch.agent.SubagentCatalog
import ai.eight24family.conch.data.SubagentService
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URLDecoder

/**
 * Backs [AgentEditScreen]. When `path` is non-null, loads the existing
 * agent file's body + frontmatter from the server; otherwise starts in
 * "creating" mode with a blank form (or whatever template the user picks).
 *
 * State is held as a single [Form] data class so the screen can read one
 * StateFlow and pipe edits back through narrow update lambdas.
 */
class AgentEditViewModel(savedStateHandle: SavedStateHandle) : ViewModel() {

    private val serverId: String = checkNotNull(savedStateHandle["serverId"])
    private val agent: Agent = Agent.valueOf(checkNotNull(savedStateHandle["agent"]))
    private val chatId: String? = savedStateHandle["chatId"]
    /** Encoded by the route helper; URLDecode here. */
    val path: String? = savedStateHandle.get<String>("path")
        ?.takeIf { it.isNotBlank() }
        ?.let { SilentlyTry.logged("SshAi-AgentEdit", "URLDecode agent path") { URLDecoder.decode(it, "UTF-8") } }

    val isNew: Boolean get() = path == null

    private val service = SubagentService(serverId, agent, chatId)

    data class Form(
        val scope: AgentScope = AgentScope.GLOBAL,
        val name: String = "",
        val description: String = "",
        val tools: Set<String> = emptySet(),
        val body: String = "",
    )

    private val _form = MutableStateFlow(Form())
    val form: StateFlow<Form> = _form.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _cwd = MutableStateFlow<String?>(null)
    val cwd: StateFlow<String?> = _cwd.asStateFlow()

    /** One-shot results pushed to the screen. */
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()
    fun consumeToast() { _toast.value = null }

    /** True when the screen should pop after save. */
    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    init {
        _cwd.value = service.cwdSnapshot()
        if (path != null) loadExisting(path)
    }

    private fun loadExisting(p: String) {
        viewModelScope.launch {
            _loading.value = true
            try {
                val raw = service.fetchOne(p) ?: ""
                val (fm, body) = parseFrontmatter(raw)
                val isProject = p.contains("/.claude/agents/") &&
                    !p.startsWith(System.getProperty("user.home").orEmpty() + "/.claude/")
                val nameFromFile = p.substringAfterLast('/').removeSuffix(".md")
                _form.value = Form(
                    scope = if (isProjectScopePath(p)) AgentScope.PROJECT else AgentScope.GLOBAL,
                    name = fm["name"] ?: nameFromFile,
                    description = fm["description"].orEmpty(),
                    tools = fm["tools"]?.split(",")
                        ?.map { it.trim() }?.filter { it.isNotBlank() }
                        ?.toSet() ?: emptySet(),
                    body = body.trim(),
                )
            } finally {
                _loading.value = false
            }
        }
    }

    fun updateScope(s: AgentScope) = _form.update { it.copy(scope = s) }
    fun updateName(v: String) = _form.update {
        // Block characters that would break the filename — agents are stored
        // as `<name>.md` so no slashes/spaces.
        it.copy(name = v.filter { c -> c != '/' && c != '\\' })
    }
    fun updateDescription(v: String) = _form.update { it.copy(description = v) }
    fun updateBody(v: String) = _form.update { it.copy(body = v) }
    fun toggleTool(name: String) = _form.update {
        if (name in it.tools) it.copy(tools = it.tools - name)
        else it.copy(tools = it.tools + name)
    }

    /** Replace the form with a starter template's contents. */
    fun applyTemplate(template: SubagentCatalog.Template) {
        _form.update {
            it.copy(
                tools = template.tools.toSet(),
                body = template.body,
                // Don't overwrite name/description if the user already typed
                // something — only fill blank fields.
                name = if (it.name.isBlank() && template.id != "blank") template.id else it.name,
                description = if (it.description.isBlank()) template.description else it.description,
            )
        }
    }

    fun save() {
        val f = _form.value
        if (f.name.isBlank()) {
            _toast.value = "name is required"
            return
        }
        viewModelScope.launch {
            _saving.value = true
            try {
                val result = service.save(
                    scope = f.scope,
                    name = f.name,
                    description = f.description,
                    tools = f.tools.toList(),
                    body = f.body,
                    oldPath = path,
                )
                when (result) {
                    is SubagentService.SaveResult.Ok -> {
                        _toast.value = "saved"
                        _saved.value = true
                    }
                    SubagentService.SaveResult.InvalidName -> _toast.value = "name has invalid characters"
                    SubagentService.SaveResult.NoLiveSession ->
                        _toast.value = "no live SSH session — open a chat first to save"
                    SubagentService.SaveResult.NoCwd ->
                        _toast.value = "project scope needs a chat with a known cwd"
                    SubagentService.SaveResult.WriteFailed ->
                        _toast.value = "write failed (check disk / permissions)"
                }
            } finally {
                _saving.value = false
            }
        }
    }

    fun delete() {
        val p = path ?: return
        viewModelScope.launch {
            _saving.value = true
            try {
                val ok = service.delete(p)
                if (ok) {
                    _toast.value = "deleted"
                    _saved.value = true
                } else {
                    _toast.value = "delete failed"
                }
            } finally {
                _saving.value = false
            }
        }
    }

    // ── helpers ──

    /** Heuristic: project-scope files live under `<cwd>/.claude/agents/`,
     *  global ones under `$HOME/.claude/agents/`. We don't have $HOME on
     *  the device, so check whether the path matches the live cwd. */
    private fun isProjectScopePath(p: String): Boolean {
        val cwd = _cwd.value ?: return false
        if (cwd.isBlank()) return false
        return p.startsWith("$cwd/.claude/agents/")
    }

    private fun parseFrontmatter(raw: String): Pair<Map<String, String>, String> {
        val trimmed = raw.trimStart()
        if (!trimmed.startsWith("---")) return emptyMap<String, String>() to raw
        val rest = trimmed.removePrefix("---")
        val end = rest.indexOf("\n---")
        if (end < 0) return emptyMap<String, String>() to raw
        val fm = rest.substring(0, end)
        val body = rest.substring(end + 4).trimStart('\n', '\r')
        val map = fm.lineSequence()
            .mapNotNull { line ->
                val colon = line.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
                line.substring(0, colon).trim().lowercase() to
                    line.substring(colon + 1).trim().trim('"', '\'')
            }
            .toMap()
        return map to body
    }
}
