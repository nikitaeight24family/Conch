package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.util.SilentlyTry

/** Inline search-in-chat bar with right-side match counter (per user
 * spec:). */
@Composable
internal fun ChatSearchBar(
    query: String,
    matchCount: Int,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { SilentlyTry.fired("Conch-ChatSearch", "request focus on open") { focusRequester.requestFocus() } }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(18.dp),
        )
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            placeholder = { Text("search in chat", style = MaterialTheme.typography.bodyMedium) },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        // Match counter — bold, primary-tinted, right-aligned.
        if (query.isNotEmpty()) {
            Text(
                "$matchCount",
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 4.dp, start = 4.dp),
            )
        }
        IconButton(onClick = onClose) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "close search",
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** One match for in-chat search — what the compact 2-line row needs. */
internal data class InChatHit(
    val msgId: String,
    val roleLabel: String,
    val snippet: String,
    val matchStartInSnippet: Int,
    val matchLength: Int,
)

/** Build a flat list of [InChatHit] for the current chat + query.
 *  One hit per message that contains the query — only the FIRST
 *  match per message is surfaced to keep the row count proportional
 *  to "messages with matches", not "total matches across the chat".
 *  Cheap: linear scan with `String.indexOf` (JVM native). */
internal fun buildInChatHits(messages: List<AgentMessage>, query: String): List<InChatHit> {
    if (query.isEmpty()) return emptyList()
    val out = ArrayList<InChatHit>(8)
    for (m in messages) {
        val body = chatSearchableBody(m) ?: continue
        val pos = body.indexOf(query, ignoreCase = true)
        if (pos < 0) continue
        // Snippet window ~120 chars centred on the match, line breaks
        // collapsed so the row reads as one strip of text.
        val winStart = (pos - 50).coerceAtLeast(0)
        val winEnd = (pos + query.length + 50).coerceAtMost(body.length)
        val raw = body.substring(winStart, winEnd).replace('\n', ' ')
        val snippet = (if (winStart > 0) "…" else "") + raw +
            (if (winEnd < body.length) "…" else "")
        val matchInSnippet = pos - winStart + (if (winStart > 0) 1 else 0)
        val role = when (m) {
            // Turn-completion signal, consumed by the stream reader — never a row.
            is AgentMessage.TurnEnd -> "sys"
            is AgentMessage.UserText -> "user"
            is AgentMessage.AssistantText -> "ai"
            is AgentMessage.ToolUse -> "tool · ${m.toolName}"
            is AgentMessage.ToolResult -> "out"
            is AgentMessage.System -> "sys"
            // Panel data, never a chat row — it carries no searchable body, so
            // this label is unreachable in practice.
            is AgentMessage.SubagentActivity -> "agent"
            is AgentMessage.BackgroundTasks -> "task"
            is AgentMessage.CommandsChanged -> "sys"
            is AgentMessage.Error -> "err"
            is AgentMessage.Raw -> "•"
            is AgentMessage.PermissionRequest -> "ask · ${m.toolName}"
            is AgentMessage.AskUserQuestion -> "ask"
            is AgentMessage.EventNote -> "event"
            is AgentMessage.Result -> "result"
        }
        out += InChatHit(
            msgId = m.id,
            roleLabel = role,
            snippet = snippet,
            matchStartInSnippet = matchInSnippet,
            matchLength = query.length,
        )
    }
    // Newest matches first — the user wants the most recent at the top, not
    // chat order (user, 2026-06-14). `messages` is oldest→newest, so reverse.
    return out.asReversed()
}

/** Compact 2-line search-result row, in-chat variant. Matches the
 *  global-search format: top = role label (bold, primary), bottom =
 *  snippet (gray) with matched substring rendered bold-white. */
@Composable
internal fun InChatHitRow(
    hit: InChatHit,
    onTap: () -> Unit,
) {
    val whiteBold = SpanStyle(
        color = Color.White,
        fontWeight = FontWeight.Bold,
    )
    val s = hit.matchStartInSnippet.coerceIn(0, hit.snippet.length)
    val e = (s + hit.matchLength).coerceAtMost(hit.snippet.length)
    val annotated = buildAnnotatedString {
        if (s > 0) append(hit.snippet.substring(0, s))
        withStyle(whiteBold) { append(hit.snippet.substring(s, e)) }
        if (e < hit.snippet.length) append(hit.snippet.substring(e))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTap() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            hit.roleLabel,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            annotated,
            color = MaterialTheme.colorScheme.outline,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Extract user-visible text from a message for in-chat search.
 *  Mirrors [ai.eight24family.conch.data.ChatSearch.searchableBody]
 *  but inlined so this file doesn't pay an import cost. */
internal fun chatSearchableBody(m: AgentMessage): String? = when (m) {
    // Turn-completion signal, not conversation text.
    is AgentMessage.TurnEnd -> null
    is AgentMessage.UserText -> m.text
    is AgentMessage.AssistantText -> m.text
    is AgentMessage.ToolUse -> "${m.toolName} ${m.input}"
    is AgentMessage.ToolResult -> m.output
    is AgentMessage.System -> m.raw
    // Subagent bookkeeping is not conversation text — keep it out of search.
    // The agents' own words. They are not a transcript row (the CLI keeps them
    // out too, and twenty agents would bury the answer) but they ARE the research
    // trail — and since a subagent turn stopped parsing as AssistantText, this is
    // the only thing keeping it findable in the chat the user ran it from.
    is AgentMessage.SubagentActivity -> m.text?.takeIf { it.isNotBlank() }
    // A snapshot of running background tasks — state, not text. Nothing to find.
    is AgentMessage.BackgroundTasks -> null
    // The command catalogue is a picker, not conversation.
    is AgentMessage.CommandsChanged -> null
    is AgentMessage.Error -> m.text
    is AgentMessage.Raw -> m.text
    is AgentMessage.PermissionRequest -> "${m.toolName} ${m.description}"
    is AgentMessage.AskUserQuestion ->
        m.questions.joinToString(" ") { q -> q.question + " " + q.options.joinToString(" ") { it.label } }
    is AgentMessage.EventNote -> m.label + (m.detail?.let { " $it" } ?: "")
    is AgentMessage.Result -> m.text
}
