package ai.eight24family.conch.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.R
import ai.eight24family.conch.util.SilentlyTry

/**
 * The ONLY way social/community links appear in the app: a centred row of
 * brand logos at the bottom of About. Each logo IS the link — no text labels,
 * no separate rows, no hand-drawn glyphs.
 *
 * One mark in the row is not a brand: the globe, which is Conch's own site and
 * leads the row. Everything else about the law holds for it — no label, no
 * separate row, and the glyph is Material's, not drawn by hand.
 *
 * Tapping opens the NATIVE app first (Telegram via its custom URI scheme) and
 * only falls back to the browser if that app isn't installed. Earlier this
 * fired the https URL straight at the system, which Android hands to the
 * browser even when the app is installed. The site has no app scheme, so it
 * goes to the browser directly.
 */
@Composable
internal fun SocialLinksRow(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val tint = MaterialTheme.colorScheme.onSurface

    // res, native-app URI (empty = no scheme), https fallback, a11y label
    data class Social(val res: Int, val app: String, val web: String, val label: String)
    val links = listOf(
        Social(R.drawable.ic_link_site, "", "https://conch-labs.com", "Conch website"),
        Social(R.drawable.ic_logo_telegram, "tg://resolve?domain=conchapplication", "https://t.me/conchapplication", "Conch on Telegram"),
        Social(R.drawable.ic_logo_github, "", "https://github.com/nikitaeight24family/Conch", "Conch on GitHub"),
    )

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        links.forEach { s ->
            IconButton(onClick = { openSocial(ctx, s.app, s.web) }) {
                Icon(
                    painter = painterResource(s.res),
                    contentDescription = s.label,
                    tint = tint,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/** Prefer the native app via its custom scheme; fall back to the browser if
 *  the app isn't installed (ActivityNotFoundException) or has no scheme. */
private fun openSocial(ctx: Context, appUri: String, webUri: String) {
    if (appUri.isNotEmpty()) {
        val opened = SilentlyTry.nullOnError {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(appUri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }
        if (opened == true) return
    }
    SilentlyTry.fired("SshAi-About", "open social link in browser") {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(webUri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
