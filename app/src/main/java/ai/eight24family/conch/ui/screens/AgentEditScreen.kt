package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.eight24family.conch.agent.AgentScope
import ai.eight24family.conch.agent.SubagentCatalog
import ai.eight24family.conch.ui.viewmodel.AgentEditViewModel

/**
 * Full-screen create/edit form for one subagent file.
 *
 * Why this is its own screen, not a bottom sheet: the system-prompt body
 * is multi-paragraph and lives next to the tools selector and three text
 * fields. Sheets force everything into half a viewport — fine for a
 * one-off picker, awful for editing prose. Full screen lets the user
 * actually read what they're writing.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AgentEditScreen(
    onBack: () -> Unit,
    vm: AgentEditViewModel = viewModel()
) {
    val form by vm.form.collectAsState()
    val loading by vm.loading.collectAsState()
    val saving by vm.saving.collectAsState()
    val cwd by vm.cwd.collectAsState()
    val toast by vm.toast.collectAsState()
    val saved by vm.saved.collectAsState()

    val cyan = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val outline = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface

    var templateSheetOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val snackbarState = remember { SnackbarHostState() }

    LaunchedEffect(toast) {
        toast?.let {
            snackbarState.showSnackbar(it)
            vm.consumeToast()
        }
    }
    LaunchedEffect(saved) {
        if (saved) onBack()
    }

    val canSave = form.name.isNotBlank() &&
        (form.scope == AgentScope.GLOBAL || !cwd.isNullOrBlank()) &&
        !saving

    val displayPath = remember(form.scope, form.name, cwd) {
        val sanitized = form.name.trim()
            .replace(Regex("[^A-Za-z0-9_-]"), "-")
            .lowercase()
            .ifBlank { "<name>" }
        when (form.scope) {
            AgentScope.GLOBAL -> "~/.claude/agents/$sanitized.md"
            AgentScope.PROJECT -> {
                val c = cwd?.takeIf { it.isNotBlank() } ?: "<no cwd>"
                "$c/.claude/agents/$sanitized.md"
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = cyan, fontWeight = FontWeight.Bold)) {
                                append(if (vm.isNew) "new" else "edit")
                            }
                            withStyle(SpanStyle(color = outline)) { append(" ▌ ") }
                            withStyle(SpanStyle(color = onSurface)) {
                                append(if (vm.isNew) "subagent" else form.name.ifBlank { "subagent" })
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
                actions = {
                    TextButton(
                        onClick = { vm.save() },
                        enabled = canSave
                    ) {
                        Text(
                            if (saving) "[ saving… ]" else "[ save ]",
                            color = if (canSave) cyan else outline,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── target path indicator ──
            Text(
                "// $displayPath",
                color = tertiary,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace
            )

            // ── starter template (only visible when creating) ──
            if (vm.isNew) {
                LabeledField(label = "starter template") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .semantics { role = Role.Button }
                            .clickable { templateSheetOpen = true }
                            .border(1.dp, outline)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            "[ pick a template ]   ▾",
                            color = cyan,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // ── scope ──
            LabeledField(label = "scope") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScopePill(
                        label = "global",
                        sub = "all hosts you ssh into",
                        selected = form.scope == AgentScope.GLOBAL,
                        onClick = { vm.updateScope(AgentScope.GLOBAL) }
                    )
                    ScopePill(
                        label = "project",
                        sub = if (cwd.isNullOrBlank()) "needs a chat with cwd" else "this repo only",
                        selected = form.scope == AgentScope.PROJECT,
                        enabled = !cwd.isNullOrBlank(),
                        onClick = { vm.updateScope(AgentScope.PROJECT) }
                    )
                }
            }

            // ── name ──
            LabeledField(
                label = "name",
                hint = if (form.name.isNotBlank())
                    "in chat: \"have ${form.name} …\" — main agent delegates"
                else "letters, digits, dashes — turns into the filename and how you address it in chat",
            ) {
                FieldBox {
                    BasicTextField(
                        value = form.name,
                        onValueChange = vm::updateName,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = onSurface,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = SolidColor(cyan),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None
                        ),
                        decorationBox = { inner ->
                            if (form.name.isBlank()) {
                                Text(
                                    "code-reviewer",
                                    color = outline,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            inner()
                        }
                    )
                }
            }

            // ── description ──
            LabeledField(
                label = "description",
                hint = "tells the parent agent when to delegate to this subagent"
            ) {
                FieldBox {
                    BasicTextField(
                        value = form.description,
                        onValueChange = vm::updateDescription,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = onSurface),
                        cursorBrush = SolidColor(cyan),
                        decorationBox = { inner ->
                            if (form.description.isBlank()) {
                                Text(
                                    "Reviews diffs/code, surfaces only substantive issues.",
                                    color = outline,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            inner()
                        }
                    )
                }
            }

            // ── tools ──
            LabeledField(
                label = "tools",
                hint = if (form.tools.isEmpty()) "empty = inherits all parent tools"
                       else "${form.tools.size} selected — only these will be available"
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SubagentCatalog.ALL_TOOLS.forEach { tool ->
                        ToolChip(
                            tool = tool,
                            selected = tool.name in form.tools,
                            onToggle = { vm.toggleTool(tool.name) }
                        )
                    }
                }
            }

            // ── system prompt body ──
            LabeledField(
                label = "system prompt",
                hint = "the role/behaviour/constraints — written to the agent verbatim"
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp, max = 480.dp)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, outline.copy(alpha = 0.4f))
                ) {
                    BasicTextField(
                        value = form.body,
                        onValueChange = vm::updateBody,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState()),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = onSurface,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = SolidColor(cyan),
                        decorationBox = { inner ->
                            if (form.body.isBlank()) {
                                Text(
                                    "You are a focused …\n\n" +
                                        "Tip: tap [ pick a template ] above for a head-start.",
                                    color = outline,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            inner()
                        }
                    )
                }
            }

            // ── delete (existing only) ──
            if (!vm.isNew) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { confirmDelete = true }, enabled = !saving) {
                        Text(
                            "[ delete this subagent ]",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
            }

            Spacer(modifier = Modifier.heightIn(min = 24.dp))
        }
    }

    // ── starter template picker ──
    if (templateSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { templateSheetOpen = false },
            containerColor = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = cyan, fontWeight = FontWeight.Bold)) {
                            append("starter")
                        }
                        withStyle(SpanStyle(color = outline)) { append(" ▌ ") }
                        withStyle(SpanStyle(color = onSurface)) { append("template") }
                    },
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    "Pre-fills tools and system prompt. You can still edit anything afterwards.",
                    color = outline,
                    style = MaterialTheme.typography.bodySmall
                )
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.heightIn(max = 480.dp)
                ) {
                    items(SubagentCatalog.TEMPLATES, key = { it.id }) { template ->
                        TemplateRow(
                            template = template,
                            onPick = {
                                vm.applyTemplate(template)
                                templateSheetOpen = false
                            }
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete subagent?") },
            text = { Text("Removes:\n${vm.path.orEmpty()}") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }

    // Optional thin "loading…" hint while we hydrate the form on edit.
    if (loading) {
        // No-op visual — we leave the form empty until hydrated; first
        // render shows blank fields, then they fill in. Adding a spinner
        // here would just flash for a fraction of a second and look noisy.
    }
}

@Composable
private fun LabeledField(
    label: String,
    hint: String? = null,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "// $label",
            color = MaterialTheme.colorScheme.outline,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace
        )
        content()
        if (hint != null) {
            Text(
                hint,
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun FieldBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        content()
    }
}

@Composable
private fun ScopePill(
    label: String,
    sub: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val cyan = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val borderColor = when {
        !enabled -> outline.copy(alpha = 0.3f)
        selected -> cyan
        else -> outline
    }
    val labelColor = when {
        !enabled -> outline.copy(alpha = 0.4f)
        selected -> cyan
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics { role = Role.Button }
            .clickable(enabled = enabled, onClick = onClick)
            .background(if (selected) cyan.copy(alpha = 0.08f) else MaterialTheme.colorScheme.background)
            .border(1.dp, borderColor)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            "[ $label ]",
            color = labelColor,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge
        )
        Text(
            sub,
            color = outline,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun ToolChip(
    tool: SubagentCatalog.Tool,
    selected: Boolean,
    onToggle: () -> Unit
) {
    val cyan = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics { role = Role.Button }
            .clickable(onClick = onToggle)
            .background(if (selected) cyan.copy(alpha = 0.10f) else MaterialTheme.colorScheme.background)
            .border(
                1.dp,
                if (selected) cyan else outline.copy(alpha = 0.5f)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            (if (selected) "✓ " else "  ") + tool.name,
            color = if (selected) cyan else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun TemplateRow(
    template: SubagentCatalog.Template,
    onPick: () -> Unit
) {
    val cyan = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics { role = Role.Button }
            .clickable(onClick = onPick)
            .border(1.dp, outline.copy(alpha = 0.4f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            template.displayName,
            color = cyan,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            template.description,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall
        )
        if (template.tools.isNotEmpty()) {
            Text(
                "tools: " + template.tools.joinToString(" · "),
                color = outline,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
