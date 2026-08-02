package ai.eight24family.conch.agent

/** Where a subagent definition lives — global home dir vs project repo. */
enum class AgentScope { GLOBAL, PROJECT }

/** Parsed view of one `~/.claude/agents/<name>.md` file on the server. */
data class AgentDoc(
    val name: String,
    val scope: String,        // "global" or "project" — matches dir owner
    val description: String?,
    val tools: List<String>,
    val body: String,
    val path: String,         // absolute path on the server
)

/**
 * Static catalog of subagent tools and starter templates. Mirrors what
 * Claude Code's main agent recognises so the chip selector in
 * AgentEditScreen offers something instead of free-text guesswork.
 *
 * Tool names match the canonical Claude Code tool identifiers — keep them
 * casing-exact (`Read`, not `read`) since Claude matches verbatim.
 */
object SubagentCatalog {

    data class Tool(val name: String, val description: String)

    /** Canonical Claude Code tools. Order is roughly read → write → search → web. */
    val ALL_TOOLS: List<Tool> = listOf(
        Tool("Read",       "read files"),
        Tool("Write",      "create new files"),
        Tool("Edit",       "edit existing files"),
        Tool("Bash",       "run shell commands"),
        Tool("Glob",       "find files by pattern"),
        Tool("Grep",       "search file contents"),
        Tool("Task",       "delegate to other subagents"),
        Tool("WebFetch",   "fetch web pages"),
        Tool("WebSearch",  "search the web"),
        Tool("TodoWrite",  "manage todos"),
        Tool("NotebookEdit","edit Jupyter notebooks"),
    )

    fun toolByName(name: String): Tool? = ALL_TOOLS.firstOrNull { it.name == name }

    data class Template(
        val id: String,
        val displayName: String,
        val description: String,
        val tools: List<String>,
        val body: String,
    )

    val TEMPLATES: List<Template> = listOf(
        Template(
            id = "blank",
            displayName = "Blank",
            description = "Start from scratch.",
            tools = emptyList(),
            body = ""
        ),
        Template(
            id = "code-reviewer",
            displayName = "Code reviewer",
            description = "Reads diffs/code, surfaces only substantive issues. No praise, no nitpicks.",
            tools = listOf("Read", "Grep", "Glob"),
            body = """
                You are a focused code reviewer. Read diffs and code carefully and surface only substantive issues:
                - Real bugs (race conditions, null derefs, off-by-one, wrong API usage).
                - Missing error handling on critical paths.
                - Security smells (unvalidated input, secret leakage).
                - Performance issues that would actually matter at scale.

                Do NOT comment on:
                - Style nits the formatter handles.
                - Naming preferences.
                - Missing comments.
                - Things you would write differently.

                If the code is fine, say "no concerns" and stop. Don't praise.
            """.trimIndent()
        ),
        Template(
            id = "test-writer",
            displayName = "Test writer",
            description = "Generates unit and integration tests in the project's existing style.",
            tools = listOf("Read", "Write", "Edit", "Glob", "Grep", "Bash"),
            body = """
                You write unit and integration tests. Given a function or module, produce a complete test file that:
                - Covers the happy path.
                - Covers boundary conditions and error cases.
                - Uses the project's existing test framework — read a sibling test file first to match style.
                - Uses real fixtures over mocks where reasonable.

                Output the test file. Don't change the implementation. After writing, run the test once to confirm it executes.
            """.trimIndent()
        ),
        Template(
            id = "refactor-helper",
            displayName = "Refactor helper",
            description = "Proposes a plan first, then refactors in small reviewable steps.",
            tools = listOf("Read", "Edit", "Grep", "Glob", "Bash"),
            body = """
                You refactor existing code without changing behaviour. When asked to refactor:
                1. Read the target code AND its callers/tests first.
                2. Propose a plan in 3-5 bullets — what changes, what stays, what could break.
                3. Wait for confirmation before editing.
                4. After editing, run any tests touching the area.

                Bias towards small, reviewable changes over big rewrites. Never rename a public API without checking external usage.
            """.trimIndent()
        ),
        Template(
            id = "bug-hunter",
            displayName = "Bug hunter",
            description = "Investigates failures via reading and instrumented commands. No speculation.",
            tools = listOf("Read", "Grep", "Glob", "Bash"),
            body = """
                You investigate bugs. When given a failure (stack trace, wrong output, hang):
                1. Read the code path actually involved — start at the failure site, walk back to the entry point.
                2. Form 2-3 hypotheses ranked by likelihood.
                3. Either prove or disprove each via reading code or instrumented commands. NO speculation.
                4. Report the root cause and the minimal fix.

                Do not change code unless asked. Your job is to explain.
            """.trimIndent()
        ),
        Template(
            id = "doc-writer",
            displayName = "Doc writer",
            description = "Writes short, useful docs. Lead with what + why, one example, gotchas.",
            tools = listOf("Read", "Write", "Edit", "Glob", "Grep"),
            body = """
                You write docs that engineers actually read. When asked to document something:
                - Lead with what it does and why someone would use it.
                - Show one realistic example.
                - Note gotchas or non-obvious behaviour.
                - Keep it short — if it grew past a page, you're explaining too much.

                Match the tone of nearby docs in the repo.
            """.trimIndent()
        ),
        Template(
            id = "release-notes",
            displayName = "Release notes",
            description = "Reads commit history and produces user-facing release notes.",
            tools = listOf("Bash", "Read"),
            body = """
                You write release notes for end-users. Given a range of commits or a date window:
                1. Run `git log --oneline <range>` to enumerate commits.
                2. Group changes by user-visible category: Features, Fixes, Performance, Breaking.
                3. Write each entry as one sentence in user-facing language. NEVER quote commit hashes or internal refactors.
                4. Skip commits that are pure refactors, test changes, or chore work.

                Output markdown ready to paste into a CHANGELOG.
            """.trimIndent()
        ),
    )

    fun templateById(id: String): Template? = TEMPLATES.firstOrNull { it.id == id }
}
