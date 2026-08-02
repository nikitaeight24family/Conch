package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.eight24family.conch.domain.AuthMethod
import ai.eight24family.conch.domain.SshKey
import ai.eight24family.conch.ssh.FailureKind
import ai.eight24family.conch.ui.viewmodel.AddServerForm
import ai.eight24family.conch.ui.viewmodel.AddServerViewModel
import ai.eight24family.conch.ui.viewmodel.TestState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerScreen(
    onBack: () -> Unit,
    onOpenKeychain: () -> Unit,
    vm: AddServerViewModel = viewModel()
) {
    // Form fields live in the VM (see AddServerViewModel) so they survive
    // navigating to Keychain to generate a key and coming back.
    val form by vm.form.collectAsState()
    val state by vm.state.collectAsState()
    val saved by vm.saved.collectAsState()
    val keys by vm.availableKeys.collectAsState()
    val skTouch by vm.skTouch.collectAsState()

    // Local mirror of port-as-text — the VM stores it as Int, but the
    // OutlinedTextField needs a String it can render mid-typing (e.g. "")
    // before it parses to a number.
    var portText by rememberSaveable(form.port) { mutableStateOf(form.port.toString()) }

    LaunchedEffect(saved) { if (saved != null) onBack() }
    LaunchedEffect(keys, form.sshKeyIds) {
        // Drop attached ids whose key was deleted from the keychain.
        // CRITICAL: skip when keys is null (Room hasn't responded yet),
        // otherwise the orphan filter sees an empty universe and marks
        // every enrolled key as deleted — which silently nukes
        // `form.sshKeyIds` the first time an Edit-server screen opens.
        val available = keys ?: return@LaunchedEffect
        val orphans = form.sshKeyIds.filter { id -> available.none { it.id == id } }
        orphans.forEach { vm.toggleKey(it) }
    }

    val isEditing = form.editingId != null

    // Physical security-key "Test connection" → present the deferred-tap touch
    // dialog; the real SK auth runs in vm.testWithSigner() the moment the user
    // taps the token (no more hollow "reachable" message).
    skTouch?.let { t ->
        SkInlineTouchDialog(
            transport = t.transport,
            credentialIdBase64 = t.credentialIdBase64,
            application = t.application,
            onUsbSigner = { signer -> vm.testWithSigner(signer) },
            onNfcSigner = { signer -> vm.testWithSigner(signer) },
            onCancel = { vm.cancelSkTest() },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditing) "Edit server" else "Add server") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            // Tighter than the default 12.dp — the form felt overscaled on
            // a phone, with each row eating ~80 dp + 12 dp gap. Now ~56 dp
            // (placeholder-driven, no floating label) + 8 dp gap.
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Compact form rows: floating Material `label` makes a row 80 dp
            // tall (label area on top of the input). Switching to
            // `placeholder` keeps the same visual hint when empty but lets
            // each row collapse to ~56 dp — a ~1.5× reduction in vertical
            // footprint, which is what the form actually needed on phones.
            OutlinedTextField(
                value = form.name, onValueChange = { vm.updateName(it) },
                placeholder = { Text("Name") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            OutlinedTextField(
                value = form.host, onValueChange = { vm.updateHost(it) },
                placeholder = { Text("Host") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            OutlinedTextField(
                value = portText,
                onValueChange = { raw ->
                    val cleaned = raw.filter { c -> c.isDigit() }
                    portText = cleaned
                    vm.updatePort(cleaned.toIntOrNull() ?: 22)
                },
                placeholder = { Text("Port") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            OutlinedTextField(
                value = form.user, onValueChange = { vm.updateUser(it) },
                placeholder = { Text("User") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )

            // No Agent picker here — the CLI is chosen per-chat (Agents tab),
            // not baked into the server. (User asked to remove it.)
            Text("Auth method", style = MaterialTheme.typography.titleLarge)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = form.authMethod == AuthMethod.PASSWORD,
                    onClick = { vm.updateAuthMethod(AuthMethod.PASSWORD) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) { Text("Password") }
                SegmentedButton(
                    selected = form.authMethod == AuthMethod.KEY,
                    onClick = { vm.updateAuthMethod(AuthMethod.KEY) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) { Text("SSH key") }
            }

            when (form.authMethod) {
                AuthMethod.PASSWORD ->
                    OutlinedTextField(
                        value = form.password, onValueChange = { vm.updatePassword(it) },
                        placeholder = {
                            // Edit mode: the password isn't pre-filled
                            // (we don't surface stored secrets in the
                            // text field), so the placeholder tells the
                            // user that leaving it blank keeps what's
                            // already stored.
                            Text(if (isEditing) "Password (leave blank to keep current)" else "Password")
                        },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                AuthMethod.KEY -> KeyPicker(
                    keys = keys.orEmpty(),
                    selectedKeyIds = form.sshKeyIds,
                    onToggle = { vm.toggleKey(it) },
                    onManage = onOpenKeychain,
                )
            }

            StateBanner(state)

            val running = state is TestState.Running

            // Fix 4: timeout is delegated to AddServerViewModel.test() which
            // calls SshClient.testConnection() — that path already sets
            // connectTimeout=15s and socket timeout=15s on the SSHClient
            // (see SshClient.newClient()), so a slow / unreachable host
            // surfaces as TestState.Failure(TIMEOUT) within ~15s. No
            // screen-level timeout needed.
            OutlinedButton(
                onClick = { vm.test() },
                enabled = !running,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (running) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                Text("Test connection")
            }

            Button(
                onClick = { vm.save() },
                enabled = !running,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (isEditing) "Save changes" else "Save") }
        }
    }
}

@Composable
private fun KeyPicker(
    keys: List<SshKey>,
    selectedKeyIds: List<String>,
    onToggle: (String) -> Unit,
    onManage: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (keys.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("No SSH keys yet", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Generate one in the Keychain, then come back to pick it.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedButton(onClick = onManage, modifier = Modifier.fillMaxWidth()) {
                        ai.eight24family.conch.ui.components.SecurityKeyIcon(
                            modifier = Modifier
                                .size(20.dp)
                                .semantics { contentDescription = "security key" }
                        )
                        Text("  Open Keychain")
                    }
                }
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Enrolled keys",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Tick every key the server's authorized_keys accepts. The auth flow tries each one until the server picks.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    keys.forEach { key ->
                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = key.id in selectedKeyIds,
                                onCheckedChange = { onToggle(key.id) }
                            )
                            // Physical security key → mark it with the token icon.
                            if (key.securityInfo != null) {
                                ai.eight24family.conch.ui.components.SecurityKeyIcon(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .padding(end = 4.dp)
                                        .semantics { contentDescription = "physical security key" }
                                )
                            }
                            Column(modifier = Modifier.padding(start = 4.dp)) {
                                Text(key.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    key.fingerprint,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
            TextButton(onClick = onManage, modifier = Modifier.fillMaxWidth()) {
                ai.eight24family.conch.ui.components.SecurityKeyIcon(
                    modifier = Modifier
                        .size(20.dp)
                        .semantics { contentDescription = "security key" }
                )
                Text("  Manage keys")
            }
        }
    }
}

@Composable
private fun StateBanner(state: TestState) {
    var helpKindOpen by remember { mutableStateOf<FailureKind?>(null) }
    when (state) {
        is TestState.Idle, TestState.Running -> Unit
        is TestState.Success -> Banner("Connected · ${state.fingerprint}", MaterialTheme.colorScheme.secondaryContainer)
        is TestState.UnknownHost -> Banner(
            "New host (${state.keyType}). Will be trusted on save.\n${state.fingerprint}",
            MaterialTheme.colorScheme.tertiaryContainer
        )
        is TestState.HostKeyMismatch -> Banner(
            "Host key changed since last save.\nExpected: ${state.expected}\nGot: ${state.actual}\n\nThis can mean the server was reinstalled — or someone is intercepting the connection. Delete this server and add it again only if you trust the new key.",
            MaterialTheme.colorScheme.errorContainer
        )
        is TestState.Failure -> Banner(
            text = state.reason,
            bg = MaterialTheme.colorScheme.errorContainer,
            onHelp = { helpKindOpen = state.kind }
        )
    }
    helpKindOpen?.let { kind ->
        ConnectionHelpDialog(kind = kind, onDismiss = { helpKindOpen = null })
    }
}

@Composable
private fun Banner(text: String, bg: Color, onHelp: (() -> Unit)? = null) {
    Surface(color = bg, modifier = Modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.padding(12.dp)) {
            Text(text)
            if (onHelp != null) {
                androidx.compose.material3.TextButton(
                    onClick = onHelp,
                    modifier = Modifier.padding(top = 4.dp)
                ) { Text("How to fix?") }
            }
        }
    }
}

@Composable
private fun ConnectionHelpDialog(kind: FailureKind, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(helpTitle(kind)) },
        text = { com.mikepenz.markdown.m3.Markdown(content = helpBody(kind)) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Got it") }
        }
    )
}

private fun helpTitle(kind: FailureKind): String = when (kind) {
    FailureKind.AUTH_KEY_REJECTED -> "Server rejected the SSH key"
    FailureKind.AUTH_PASSWORD_REJECTED -> "Wrong password"
    FailureKind.HOST_NOT_RESOLVED -> "Host not found"
    FailureKind.NETWORK_UNREACHABLE -> "Server unreachable"
    FailureKind.TIMEOUT -> "Connection timed out"
    FailureKind.OTHER -> "Connection failed"
}

private fun helpBody(kind: FailureKind): String = when (kind) {
    FailureKind.AUTH_KEY_REJECTED -> """
The server doesn't recognise your SSH public key. To fix it, the public half of your key needs to live in `~/.ssh/authorized_keys` on the server, on the user account you're connecting with.

**Three ways to do that:**

**1. Ask the agent.** If you already have a working session on this server with Claude / Codex / Gemini (even with password auth), just tell the agent:

> Add this public key to my ~/.ssh/authorized_keys: `ssh-ed25519 AAAAC3... sshai@android`

The agent will append it for you.

**2. Copy it manually.** Open **Settings → SSH keys**, tap **Copy** on the key, then on the server run:

```
mkdir -p ~/.ssh && chmod 700 ~/.ssh
echo 'PASTE_THE_PUBLIC_KEY_HERE' >> ~/.ssh/authorized_keys
chmod 600 ~/.ssh/authorized_keys
```

**3. Use `ssh-copy-id` from another machine** that already has access.

After that, hit **Test connection** again.
""".trimIndent()

    FailureKind.AUTH_PASSWORD_REJECTED -> """
The server rejected the password.

**Things to check:**
- The username matches the account you actually log in with (often it's the OS user, e.g. `root`, `ubuntu`, `ec2-user`).
- Caps Lock isn't on.
- The server allows password auth at all. Some hardened servers only accept SSH keys — switch to **SSH key** above and follow the key flow.
""".trimIndent()

    FailureKind.HOST_NOT_RESOLVED -> """
DNS could not find this host. Double-check the address is exactly what you would put after `ssh user@` from a laptop. If the server only has an IP, use the IP directly.
""".trimIndent()

    FailureKind.NETWORK_UNREACHABLE -> """
The address resolved but nothing answered on that port.

- Make sure SSH (`sshd`) is running on the server.
- Check the port — default is 22 but cloud providers sometimes change it.
- Check your firewall / security group allows incoming traffic from your phone's network.
""".trimIndent()

    FailureKind.TIMEOUT -> """
The server took too long to respond. Network is slow, the server is overloaded, or a firewall is silently dropping packets. Try once more, then check the server is up.
""".trimIndent()

    FailureKind.OTHER -> """
Something went wrong during the connection. Check host, port, user and credentials. If the server is behind a VPN or company firewall, make sure your phone is on the right network.
""".trimIndent()
}
