package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val text = remember {
        ctx.resources.openRawResource(R.raw.privacy_policy)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy Policy") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Same compact typography override as
            // [TermsOfServiceScreen] — markdown lib's default
            // h1/h2 sizes turn legal text into a billboard.
            com.mikepenz.markdown.m3.Markdown(
                content = text,
                typography = com.mikepenz.markdown.m3.markdownTypography(
                    h1 = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.primary),
                    h2 = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.primary),
                    h3 = MaterialTheme.typography.titleSmall.copy(color = MaterialTheme.colorScheme.primary),
                    h4 = MaterialTheme.typography.titleSmall,
                    h5 = MaterialTheme.typography.titleSmall,
                    h6 = MaterialTheme.typography.titleSmall,
                    text = MaterialTheme.typography.bodyMedium,
                    paragraph = MaterialTheme.typography.bodyMedium,
                    code = MaterialTheme.typography.bodySmall,
                    inlineCode = MaterialTheme.typography.bodyMedium,
                    quote = MaterialTheme.typography.bodyMedium,
                    bullet = MaterialTheme.typography.bodyMedium,
                    list = MaterialTheme.typography.bodyMedium,
                    link = MaterialTheme.typography.bodyMedium,
                    ordered = MaterialTheme.typography.bodyMedium,
                ),
            )
        }
    }
}
