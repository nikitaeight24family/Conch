package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.eight24family.conch.agent.AgentDoc
import ai.eight24family.conch.ui.viewmodel.AgentsListViewModel

/**
 * Full-screen subagents browser. Two sections (global / project), each
 * card edits a single `~/.claude/agents/<name>.md` file on the server.
 *
 * Why full-screen instead of bottom sheet: subagent bodies are
 * multi-paragraph system prompts. Editing them in a half-height sheet
 * is miserable; full screen lets users actually read what they're
 * about to ship.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsListScreen(
    onBack: () -> Unit,
    onNew: () -> Unit,
    onEdit: (path: String) -> Unit,
    vm: AgentsListViewModel = viewModel()
) {
    val agents by vm.agents.collectAsState()
    val loading by vm.loading.collectAsState()
    val cwd by vm.cwd.collectAsState()

    val cyan = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline

    val global = agents.filter { it.scope != "project" }
    val project = agents.filter { it.scope == "project" }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = cyan, fontWeight = FontWeight.Bold)) {
                                append("subagents")
                            }
                            withStyle(SpanStyle(color = outline)) { append(" ▌ ") }
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) {
                                append(vm.agentCli)
                            }
                        },
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            Surface(
                onClick = onNew,
                shape = RectangleShape,
                color = MaterialTheme.colorScheme.background,
                contentColor = cyan,
                border = BorderStroke(1.dp, cyan),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Text(
                    "[ + new subagent ]",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Header — what subagents ARE + how to use them. User said —
            // putting that explanation right above the list makes it
            // self-evident.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "// what is this?",
                    color = cyan,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Subagents are specialised personas your main agent " +
                        "can delegate to. Each .md file below describes " +
                        "ONE persona (name, system prompt, allowed tools). " +
                        "Files live on the server — global ones apply to " +
                        "every project, project-scoped only in the current " +
                        "directory.",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                )
                // Concrete usage example — without this the user reads
                // the abstract description and still asks "ok but
                // HOW do I use one?".
                Text(
                    "// example",
                    color = cyan,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "In chat: \"have code-reviewer look at the last commit\" — " +
                        "main agent forks a fresh subagent context with the " +
                        "code-reviewer's system prompt + only its allowed tools, " +
                        "runs the task, and returns the answer to your chat.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "// reusable role definitions the main agent can spin up",
                    color = outline,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "// global: ~/.claude/agents",
                        color = outline,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        Text(
                            "  refreshing…",
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                cwd?.takeIf { it.isNotBlank() }?.let { c ->
                    Text(
                        "// project: $c/.claude/agents",
                        color = outline,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            if (agents.isEmpty() && !loading) {
                EmptyState(onNew = onNew, modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (global.isNotEmpty()) {
                        item {
                            SectionHeader("// global", count = global.size)
                        }
                        items(global, key = { it.path }) { ag ->
                            AgentCard(ag, onClick = { onEdit(ag.path) })
                        }
                    }
                    if (project.isNotEmpty()) {
                        item {
                            SectionHeader("// project", count = project.size)
                        }
                        items(project, key = { it.path }) { ag ->
                            AgentCard(ag, onClick = { onEdit(ag.path) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String, count: Int) {
    Text(
        "$label · $count",
        color = MaterialTheme.colorScheme.tertiary,
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun AgentCard(ag: AgentDoc, onClick: () -> Unit) {
    val cyan = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val outline = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface
    val isProject = ag.scope == "project"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(1.dp, outline.copy(alpha = 0.4f))
            .background(
                if (isProject) tertiary.copy(alpha = 0.05f)
                else MaterialTheme.colorScheme.background
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (isProject) "● " else "○ ",
                color = if (isProject) tertiary else outline,
                fontWeight = FontWeight.Bold
            )
            Text(
                ag.name,
                color = cyan,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Text("›", color = outline, style = MaterialTheme.typography.bodyLarge)
        }
        if (!ag.description.isNullOrBlank()) {
            Text(
                ag.description,
                color = onSurface,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2
            )
        }
        if (ag.tools.isNotEmpty()) {
            Text(
                "tools: " + ag.tools.joinToString(" · "),
                color = outline,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace
            )
        } else if (ag.description.isNullOrBlank()) {
            // No frontmatter at all — show a muted hint so the user knows
            // the file is bare, not parse-broken.
            Text(
                "// no description / no tools — inherits parent",
                color = outline,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun EmptyState(onNew: () -> Unit, modifier: Modifier = Modifier) {
    val cyan = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface

    Box(modifier = modifier, contentAlignment = Alignment.TopStart) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "// no subagents on this host",
                color = outline,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
            Text(
                "//",
                color = outline,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
            Text(
                "// a subagent is a focused role you can hand work to",
                color = outline,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
            Text(
                "// — code reviewer, test writer, bug hunter, etc.",
                color = outline,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
            Text(
                "//",
                color = outline,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
            Text(
                "// pick a starter template or write your own.",
                color = outline,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = cyan, fontWeight = FontWeight.Bold)) { append("❯ ") }
                    withStyle(SpanStyle(color = onSurface)) { append("tap [ + new subagent ] to begin") }
                },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .clickable(onClick = onNew)
            )
        }
    }
}
