package ai.eight24family.conch.ui.screens

import ai.eight24family.conch.linux.LocalLlm
import ai.eight24family.conch.linux.LocalLlmEngine
import ai.eight24family.conch.linux.chat.LocalApiAccess
import ai.eight24family.conch.ui.theme.ConchTheme
import ai.eight24family.conch.ui.window.handCursor
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * "<App> wants to use the model on this phone" — the one door through which
 * another app on this device can reach the local engine.
 *
 * ⛔ THE CALLER IS IDENTIFIED BY THE SYSTEM, NEVER BY WHAT IT TELLS US.
 * [Activity.getCallingPackage] is filled in by the framework from the uid that
 * launched this activity, and ONLY when it was launched for a result. So a
 * request that arrives with no calling package is refused outright: an app
 * that cannot be named cannot be granted, and an extra saying "I am the
 * keyboard" would be worth exactly nothing.
 *
 * What a grant is, precisely: one key of its own on Conch's inference port
 * (see [LocalApiAccess]). It buys the holder prompts and answers from a model
 * the owner already downloaded — not this app's chats, not its files, not its
 * servers or SSH keys, none of which live behind that port.
 *
 * ── THE CONTRACT (for whoever writes the other app) ──
 *
 * ```kotlin
 * val i = Intent("ai.eight24family.conch.action.REQUEST_LOCAL_MODEL")
 *     .setPackage("ai.eight24family.conch")          // ".debug" while testing
 * startActivityForResult(i, RC)                       // MUST be for-result
 * // onActivityResult: RESULT_OK →
 * //   data.getStringExtra("base_url")  // http://127.0.0.1:8317/v1
 * //   data.getStringExtra("api_key")   // Bearer token, this app's own
 * //   data.getStringExtra("model")     // what is loaded right now, or null
 * ```
 * From there it is ordinary OpenAI-compatible HTTP — any client library, no
 * SDK from us, nothing leaving the phone.
 *
 * ⚠ AND ONE HONEST LIMIT, SAID ON THE SCREEN TOO: the port answers only while
 * a model is loaded in Conch, and Conch frees the weights after two minutes
 * idle (`LocalLlmEngine.IDLE_STOP_MS`) because they are gigabytes of a phone's
 * ram. Waking the engine on an outside request needs a resident, visible
 * service — that is its own piece of work, not something to smuggle in behind
 * a consent screen.
 */
class LocalModelAccessActivity : ComponentActivity() {

    private val tag = "Conch-LocalApi"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Identity, from the framework. Null ⇒ launched with startActivity
        // (no result channel) ⇒ nobody to grant to.
        val caller = callingPackage
        if (caller == null) {
            android.util.Log.w(tag, "access request with no calling package — refused")
            setResult(
                Activity.RESULT_CANCELED,
                Intent().putExtra(
                    "error",
                    "launch this with startActivityForResult — Conch grants access to a " +
                        "named app, and the system only names the caller of a for-result start",
                ),
            )
            finish()
            return
        }
        if (caller == packageName) {
            // Conch asking Conch: it already has its own key.
            setResult(Activity.RESULT_OK, resultFor(LocalApiAccess.ownKey))
            finish()
            return
        }

        val pm = packageManager
        val label = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(caller, 0)).toString()
        }.getOrDefault(caller)

        // The owner's earlier decision stands: an app that is already allowed
        // gets its key back without a second prompt (it holds that key
        // already), and revoking is where the decision is unmade.
        LocalApiAccess.grantFor(caller)?.let { existing ->
            android.util.Log.i(tag, "re-issued existing grant to $caller")
            setResult(Activity.RESULT_OK, resultFor(existing.token))
            finish()
            return
        }

        setContent {
            ConchTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ConsentBody(
                        label = label,
                        pkg = caller,
                        onAllow = { allow(caller, label) },
                        onDeny = {
                            android.util.Log.i(tag, "owner denied $caller")
                            setResult(Activity.RESULT_CANCELED)
                            finish()
                        },
                    )
                }
            }
        }
    }

    private fun allow(pkg: String, label: String) {
        val g = LocalApiAccess.grant(pkg, label)
        android.util.Log.i(tag, "owner allowed $pkg")
        setResult(Activity.RESULT_OK, resultFor(g.token))
        // The engine read its key file at startup, so a model serving RIGHT
        // NOW has never heard of this key. Restart it, or the app the owner
        // just allowed would spend its first request on a 401.
        lifecycleScope.launch { LocalLlmEngine.restartForKeys() }
        finish()
    }

    private fun resultFor(token: String) = Intent()
        .putExtra(LocalApiAccess.BASE_URL_EXTRA, "${LocalLlmEngine.BASE_URL}/v1")
        .putExtra(LocalApiAccess.API_KEY_EXTRA, token)
        .putExtra(
            LocalApiAccess.MODEL_EXTRA,
            (LocalLlmEngine.state.value as? LocalLlmEngine.State.Up)?.modelId,
        )
}

@Composable
private fun ConsentBody(
    label: String,
    pkg: String,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    val dim = MaterialTheme.colorScheme.outline
    val cyan = MaterialTheme.colorScheme.primary
    val ready = LocalLlm.CATALOG.count { LocalLlm.status(it) is LocalLlm.Status.Ready }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 28.dp),
    ) {
        Text(
            "// local model access",
            color = dim,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "$label wants to use the model on this phone",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            pkg,
            color = dim,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(20.dp))
        // What it gets — and, just as plainly, what it does not. A permission
        // screen that only lists the upside is an advertisement.
        Bullet("send prompts and pictures to a model that runs here, and read its answers")
        Bullet("see which of your $ready installed models are available")
        Spacer(Modifier.height(12.dp))
        Text(
            "It does NOT get your chats, files, servers or keys. The key it " +
                "receives opens the inference port and nothing else, and no " +
                "request leaves this phone.",
            color = dim,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "It can only answer while a model is loaded in Conch — the weights " +
                "are gigabytes of your ram, so Conch frees them after two " +
                "minutes idle.",
            color = dim,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Revoke any time: local models → api access.",
            color = dim,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(28.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "[ deny ]",
                color = dim,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .handCursor()
                    .clickable { onDeny() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
            Spacer(Modifier.height(0.dp))
            Text(
                "[ allow ]",
                color = cyan,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .handCursor()
                    .clickable { onAllow() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            "· ",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
