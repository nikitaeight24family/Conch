package ai.eight24family.conch.ui.screens

import ai.eight24family.conch.linux.store.HfAuth
import ai.eight24family.conch.linux.store.HfBrowse
import ai.eight24family.conch.ui.window.handCursor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The owner's Hugging Face account, as one line in the store.
 *
 * ⛔ WHAT IT BUYS: gated repos. Meta's Llama and Google's Gemma are open
 * weights behind a licence click-through, and the click-through lives in an HF
 * account — so without a token the store can SHOW them and never fetch them,
 * which is the most annoying shape a store can have. With one they are
 * ordinary rows.
 *
 * The token is never displayed back. The row shows presence and the account
 * name; the value lives Keystore-wrapped in SecretsStore and reaches
 * huggingface.co and nothing else (see [HfAuth]).
 */
@Composable
internal fun HfAccountBlock() {
    val state by HfAuth.state.collectAsState()
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val dim = MaterialTheme.colorScheme.outline
    LaunchedEffect(Unit) { HfAuth.refresh() }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f, fill = true)) {
            Text(
                "hugging face",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                when (val s = state) {
                    is HfAuth.State.Connected ->
                        "connected" + (s.user?.let { " as $it" } ?: "") + " · gated models available"
                    HfAuth.State.Absent ->
                        "not connected · gated models (Llama, Gemma) can't be downloaded"
                },
                color = dim,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
            )
        }
        Spacer(Modifier.width(8.dp))
        val connected = state is HfAuth.State.Connected
        Text(
            if (connected) "[ forget ]" else "[ connect ]",
            color = if (connected) dim else MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .handCursor()
                .clickable {
                    if (connected) {
                        HfAuth.forget()
                        // Entries resolved WITH the token may have been shelved
                        // as downloadable; drop the cache so the store goes back
                        // to telling the anonymous truth.
                        HfBrowse.clearResolved()
                    } else {
                        typed = ""; problem = null; asking = true
                    }
                }
                .padding(horizontal = 4.dp, vertical = 8.dp),
        )
    }

    if (!asking) return
    AlertDialog(
        onDismissRequest = { if (!busy) asking = false },
        title = { Text("Connect Hugging Face") },
        text = {
            Column {
                Text(
                    "Paste a READ token from huggingface.co/settings/tokens. " +
                        "It is stored encrypted on this phone, sent only to " +
                        "huggingface.co, and never to the model CDN.",
                    style = MaterialTheme.typography.bodySmall,
                    color = dim,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.trim() },
                    singleLine = true,
                    enabled = !busy,
                    label = { Text("hf_…") },
                    // A credential is not read aloud on a train.
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                problem?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && typed.isNotBlank(),
                onClick = {
                    busy = true
                    problem = null
                    scope.launch {
                        // Checked against HF before it is kept: an unchecked
                        // token is a 401 on the first gigabyte, blamed on the
                        // network.
                        when (val r = HfAuth.connect(typed)) {
                            is HfAuth.ConnectResult.Ok -> {
                                // Anything resolved anonymously said "gated";
                                // let the pages ask again with the token.
                                HfBrowse.clearResolved()
                                typed = ""
                                busy = false
                                asking = false
                            }
                            HfAuth.ConnectResult.Refused -> {
                                busy = false
                                problem = "Hugging Face refused that token"
                            }
                            is HfAuth.ConnectResult.Failed -> {
                                busy = false
                                problem = r.reason
                            }
                        }
                    }
                },
            ) { Text(if (busy) "checking…" else "connect") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { asking = false }) { Text("cancel") }
        },
    )
}
