package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

// Alpha bumped from 0x40 (25%) to 0x66 (40%) to meet WCAG AA contrast against
// the dark chat background (Color(0xFF06090F)). Combined with the leading
// `- `/`+ ` glyphs below, colour is no longer the only signal (WCAG 1.4.1).
private val RemovedBg = Color(0x66FF6E6E)
private val AddedBg = Color(0x6654D67E)
private val RemovedFg = Color(0xFFFF6E6E)
private val AddedFg = Color(0xFF54D67E)

@Composable
fun EditDiffViewer(toolName: String, inputJson: String) {
    val parsed = remember(inputJson) { SilentlyTry.logged("Conch-DiffViewer", "parse edit input") { parseEditInput(inputJson) } }
    if (parsed == null) {
        Text(
            inputJson,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }

    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)) {
        Text(
            parsed.filePath,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace
        )
        DiffBlock(parsed.oldString, parsed.newString)
    }
}

@Composable
private fun DiffBlock(oldStr: String, newStr: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        oldStr.lines().forEach { line ->
            Text(
                diffLine("-", line, RemovedFg),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(RemovedBg)
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            )
        }
        newStr.lines().forEach { line ->
            Text(
                diffLine("+", line, AddedFg),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AddedBg)
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            )
        }
    }
}

/**
 * Builds a diff line where the leading `+`/`-` marker is rendered in bold
 * + the diff foreground colour, so colour is not the only signal that a
 * line was added vs removed (WCAG 1.4.1). The rest of the line uses the
 * default content colour for legibility against the tinted background.
 */
private fun diffLine(marker: String, content: String, markerColor: Color): AnnotatedString =
    buildAnnotatedString {
        withStyle(SpanStyle(color = markerColor, fontWeight = FontWeight.Bold)) {
            append(marker)
        }
        append(' ')
        append(content)
    }

@Composable
fun WriteFileViewer(inputJson: String) {
    val parsed = remember(inputJson) { SilentlyTry.logged("Conch-DiffViewer", "parse write input") { parseWriteInput(inputJson) } }
    if (parsed == null) {
        Text(
            inputJson,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            parsed.filePath,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        parsed.content.lines().take(120).forEach { line ->
            Text(
                diffLine("+", line, AddedFg),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AddedBg)
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            )
        }
        if (parsed.content.lines().size > 120) {
            Text("…(${parsed.content.lines().size - 120} more lines)", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private data class EditInput(val filePath: String, val oldString: String, val newString: String)
private data class WriteInput(val filePath: String, val content: String)

private fun parseEditInput(inputJson: String): EditInput? {
    val obj = json.parseToJsonElement(inputJson).jsonObject
    val path = (obj["file_path"] as? JsonPrimitive)?.contentOrNull
        ?: (obj["filePath"] as? JsonPrimitive)?.contentOrNull
        ?: return null
    val oldS = (obj["old_string"] as? JsonPrimitive)?.contentOrNull
        ?: (obj["oldString"] as? JsonPrimitive)?.contentOrNull
        ?: ""
    val newS = (obj["new_string"] as? JsonPrimitive)?.contentOrNull
        ?: (obj["newString"] as? JsonPrimitive)?.contentOrNull
        ?: ""
    return EditInput(path, oldS, newS)
}

private fun parseWriteInput(inputJson: String): WriteInput? {
    val obj = json.parseToJsonElement(inputJson).jsonObject
    val path = (obj["file_path"] as? JsonPrimitive)?.contentOrNull
        ?: (obj["filePath"] as? JsonPrimitive)?.contentOrNull
        ?: return null
    val content = (obj["content"] as? JsonPrimitive)?.contentOrNull ?: ""
    return WriteInput(path, content)
}

