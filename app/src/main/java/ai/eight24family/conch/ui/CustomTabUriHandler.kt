package ai.eight24family.conch.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.ui.platform.UriHandler
import ai.eight24family.conch.util.SilentlyTry

/**
 * In-app browser via Chrome Custom Tabs.
 *
 * Compose's `Text` composable, when it sees a `LinkAnnotation.Url`, calls
 * `LocalUriHandler.current.openUri(url)` on click. The default handler fires
 * `Intent.ACTION_VIEW`, which kicks the user OUT of Conch into their default
 * browser app. The user explicitly asked for in-app:.
 *
 * Custom Tabs is the standard pattern for this:
 *  - the user's default browser renders the page (Chrome / Firefox /
 *    Edge / Samsung Internet — whatever they've set as default),
 *  - cookies / passwords / extensions are shared with that browser,
 *  - a tiny "close" affordance returns to Conch with one tap,
 *  - the page lifts on top of the app instead of taking over the
 *    task stack, so the back-button still works as expected.
 *
 * Provide via:
 *   ```
 *   CompositionLocalProvider(LocalUriHandler provides CustomTabUriHandler(ctx)) {
 *       AppTheme { … }
 *   }
 *   ```
 *
 * Falls back to plain `ACTION_VIEW` if Custom Tabs intent throws
 * (no compatible browser installed — should never happen on a
 * stock-Android device, but worth handling so a link click can't
 * crash the app).
 */
class CustomTabUriHandler(private val context: Context) : UriHandler {
    override fun openUri(uri: String) {
        val parsed = SilentlyTry.logged("SshAi-UriHandler", "parse link uri") { Uri.parse(uri) } ?: return
        // Reject anything that isn't an http(s) URL. ACTION_VIEW on a
        // `javascript:` / `file:` / `intent:` URI could be exploited
        // by a malicious server-side reply; we only want web links.
        val scheme = parsed.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            android.util.Log.w(
                "SshAi-UriHandler",
                "blocked non-http link: scheme=$scheme",
            )
            return
        }
        val tab = CustomTabsIntent.Builder()
            .setShowTitle(true)
            // Default browser handles colour scheme.
            .build()
        // Custom Tabs reuses the activity task by default. We want a
        // separate task so the system back gesture returns to Conch
        // even after the OS swaps the user out and back via recents.
        tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { tab.launchUrl(context, parsed) }.onFailure { t ->
            android.util.Log.w(
                "SshAi-UriHandler",
                "custom tab launch failed (${t.javaClass.simpleName}: ${t.message}) — falling back to ACTION_VIEW",
            )
            SilentlyTry.fired("SshAi-UriHandler", "ACTION_VIEW fallback") {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, parsed).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}
