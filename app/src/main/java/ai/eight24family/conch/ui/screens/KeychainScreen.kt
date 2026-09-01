package ai.eight24family.conch.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.eight24family.conch.domain.SshKey
import ai.eight24family.conch.ui.viewmodel.KeychainViewModel
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Which CTAP2 SK flow to drive — paired with `attachToServerId` to
 * power the Wrong-Key recovery detour from the connect dialog.
 *  - [DISCOVER] reads existing resident creds off the touched token
 *    (CTAP `enumerateCredentials`).
 *  - [REGISTER] mints a brand-new resident credential (CTAP
 *    `makeCredential`).
 */
enum class AddSkMode { DISCOVER, REGISTER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeychainScreen(
    onBack: () -> Unit,
    /** When non-null, every key imported through the SK dialog while the
     *  user is on this screen is also attached to the given server. */
    attachToServerId: String? = null,
    /** When non-null, auto-arm the matching SK flow on first composition.
     *  Pairs with [attachToServerId] to fold connect-dialog recovery into
     *  one navigation hop. */
    autoArmMode: AddSkMode? = null,
    /** Called after a successful auto-attach import — navigates the user
     *  back to the server's screen so the auth flow can re-run with the
     *  freshly enrolled key. */
    onAttachedRetry: ((serverId: String) -> Unit)? = null,
    vm: KeychainViewModel = viewModel()
) {
    val keys by vm.keys.collectAsState()
    val importState by vm.importState.collectAsState()
    val addSkState by vm.addSkState.collectAsState()
    val ctx = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    var generateOpen by remember { mutableStateOf(false) }
    var importDialogOpen by remember { mutableStateOf(false) }
    /** Which SK flow the user kicked off via the FAB menu (or via the
     *  nav route's [autoArmMode]). null = no SK dialog open. */
    var addSkOpen by remember { mutableStateOf<AddSkMode?>(autoArmMode) }
    var pickedPem by remember { mutableStateOf<String?>(null) }
    var pickedFilename by remember { mutableStateOf<String?>(null) }
    var keyToDelete by remember { mutableStateOf<SshKey?>(null) }

    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        SilentlyTry.logged("Conch-Keychain", "read selected key file") {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }?.let { bytes ->
            val pem = bytes.toString(Charsets.UTF_8)
            if (looksLikePrivateKey(pem)) {
                pickedPem = pem
                pickedFilename = queryDisplayName(ctx, uri) ?: "imported key"
                importDialogOpen = true
            } else {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(
                        "That file doesn't look like an SSH private key."
                    )
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("SSH keys") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
                    }
                }
            )
        },
        floatingActionButton = {
            var addMenuOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
            Box {
                FloatingActionButton(
                    onClick = { addMenuOpen = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RectangleShape,
                ) {
                    Icon(Icons.Default.Add, contentDescription = "add a key")
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = addMenuOpen,
                    onDismissRequest = { addMenuOpen = false },
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = {
                            Column {
                                Text("Generate", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "fresh Ed25519 keypair on this device",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        },
                        leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = "generate") },
                        onClick = {
                            addMenuOpen = false
                            generateOpen = true
                        },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = {
                            Column {
                                Text("Import from file", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "pick an existing PEM / OpenSSH key",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        },
                        leadingIcon = { Icon(Icons.Default.FileOpen, contentDescription = "import file") },
                        onClick = {
                            addMenuOpen = false
                            pickFile.launch(arrayOf("*/*"))
                        },
                    )
                    HorizontalDivider()
                    androidx.compose.material3.DropdownMenuItem(
                        text = {
                            Column {
                                Text("Find on security key", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "import existing resident creds from a FIDO2 security key",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "find existing key") },
                        onClick = {
                            addMenuOpen = false
                            addSkOpen = AddSkMode.DISCOVER
                        },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = {
                            Column {
                                Text("Register new on security key", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "mint a brand-new credential on a FIDO2 security key",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        },
                        leadingIcon = { Icon(Icons.Default.AddCircle, contentDescription = "register new key") },
                        onClick = {
                            addMenuOpen = false
                            addSkOpen = AddSkMode.REGISTER
                        },
                    )
                }
            }
        }
    ) { padding ->
        if (keys.isEmpty()) {
            EmptyKeyState(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(keys, key = { it.id }) { k ->
                    KeyCard(
                        k,
                        onDelete = { keyToDelete = k },
                        onCopied = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Copied")
                            }
                        },
                    )
                }
            }
        }
    }

    if (generateOpen) {
        GenerateKeyDialog(
            onDismiss = { generateOpen = false },
            onConfirm = { name, comment ->
                vm.generate(name, comment)
                generateOpen = false
            }
        )
    }

    if (importDialogOpen) {
        val pem = pickedPem
        if (pem == null) {
            importDialogOpen = false
        } else {
            ImportKeyDialog(
                filename = pickedFilename.orEmpty(),
                state = importState,
                onSubmit = { name, passphrase ->
                    vm.import(
                        name = name,
                        pem = pem,
                        passphrase = passphrase.takeIf { it.isNotEmpty() },
                        comment = "imported from $pickedFilename",
                    )
                },
                onDismiss = {
                    importDialogOpen = false
                    pickedPem = null
                    pickedFilename = null
                    vm.clearImportState()
                },
            )
            LaunchedEffect(importState) {
                if (importState is KeychainViewModel.ImportState.Success) {
                    kotlinx.coroutines.delay(700)
                    importDialogOpen = false
                    pickedPem = null
                    pickedFilename = null
                    vm.clearImportState()
                }
            }
        }
    }

    val activeMode = addSkOpen
    if (activeMode != null) {
        val activity = ctx as? android.app.Activity
        // Auto-arm fires ONCE per (activeMode, retryTick) — on the first
        // composition where addSkOpen flipped from null OR the user pressed
        // "Try again" in a terminal error state (which bumps retryTick).
        // We track the trigger with a remembered flag so re-renders (e.g.
        // after Cancel sends state back to Idle, before addSkOpen flips off)
        // don't re-fire the import.
        var retryTick by remember(activeMode) { mutableStateOf(0) }
        var armed by remember(activeMode, retryTick) { mutableStateOf(false) }
        LaunchedEffect(activeMode, retryTick) {
            if (!armed && addSkState is KeychainViewModel.AddSkState.Idle) {
                armed = true
                when (activeMode) {
                    AddSkMode.DISCOVER -> vm.importSecurityKeyResidentCredentials(
                        ai.eight24family.conch.domain.SecurityKeyTransport.EITHER,
                        activity,
                    )
                    AddSkMode.REGISTER -> vm.registerNewSecurityKey(
                        ai.eight24family.conch.domain.SecurityKeyTransport.EITHER,
                        activity,
                        displayName = "Conch key ${System.currentTimeMillis() / 1000}",
                    )
                }
            }
        }
        UseSecurityKeyDialog(
            mode = activeMode,
            state = addSkState,
            activity = activity,
            onPickTransport = { transport ->
                when (activeMode) {
                    AddSkMode.DISCOVER -> vm.importSecurityKeyResidentCredentials(transport, activity)
                    AddSkMode.REGISTER -> vm.registerNewSecurityKey(
                        transport, activity,
                        displayName = "Conch key ${System.currentTimeMillis() / 1000}",
                    )
                }
            },
            onSubmitPin = { pin -> vm.submitPin(pin) },
            onDismiss = {
                addSkOpen = null
                vm.clearAddSkState()
                armed = false
            },
            // Reset state machine to Idle so parent's auto-arm LaunchedEffect
            // re-fires and the user can pick a different transport / retry
            // without bouncing out of the dialog. Bumping retryTick re-keys
            // the auto-arm effect so it actually runs again.
            onRetry = {
                vm.clearAddSkState()
                retryTick += 1
            },
        )
        LaunchedEffect(addSkState) {
            val s = addSkState
            if (s is KeychainViewModel.AddSkState.Saved) {
                attachToServerId?.let { sid ->
                    val repo = ai.eight24family.conch.di.ServiceLocator.serverRepository
                    s.keys.forEach { k -> repo.attachKey(sid, k.id) }
                }
                kotlinx.coroutines.delay(1500)
                addSkOpen = null
                vm.clearAddSkState()
                attachToServerId?.let { sid -> onAttachedRetry?.invoke(sid) }
            }
        }
    }

    keyToDelete?.let { k ->
        AlertDialog(
            onDismissRequest = { keyToDelete = null },
            title = { Text("Delete \"${k.name}\"?") },
            text = { Text("Servers using this key will fail to connect.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(k.id)
                    keyToDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { keyToDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun EmptyKeyState(modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ai.eight24family.conch.ui.components.SecurityKeyIcon(modifier = Modifier.size(20.dp))
            Text("No SSH keys", style = MaterialTheme.typography.titleLarge)
            Text(
                "Generate a fresh Ed25519 keypair, or import an existing key from this device or a USB drive (the file picker shows USB-OTG storage automatically).",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Usb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                )
                Text(
                    "USB-OTG drives appear in the picker.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun KeyCard(key: SshKey, onDelete: () -> Unit, onCopied: () -> Unit) {
    val ctx = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ai.eight24family.conch.ui.components.SecurityKeyIcon(modifier = Modifier.size(20.dp))
                Column(modifier = Modifier.weight(1f, fill = true)) {
                    Text(key.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        // UX-5: include the time so two keys generated/imported
                        // on the same day (both often named "openssh") don't read
                        // as identical rows — the timestamp + fingerprint below
                        // disambiguate.
                        "${key.type.name} · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(key.createdAt))}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }
            // UX-5: fingerprint is the only truly-unique handle when two keys
            // share a title ("openssh") — give it its own copy affordance, not
            // just the public key.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    key.fingerprint,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f, fill = true),
                )
                IconButton(onClick = {
                    copyToClipboard(ctx, "${key.name} fingerprint", key.fingerprint)
                    onCopied()
                }) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy fingerprint",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Text(
                key.publicKey,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3
            )
            TextButton(
                onClick = {
                    copyToClipboard(ctx, key.name, key.publicKey)
                    onCopied()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Text("  Copy public key")
            }
        }
    }
}

@Composable
private fun GenerateKeyDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, comment: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("conch@android") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Generate Ed25519 key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name (e.g. \"My laptop\")") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = comment, onValueChange = { comment = it },
                    label = { Text("Comment (visible in authorized_keys)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, comment) }) { Text("Generate") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ImportKeyDialog(
    filename: String,
    state: KeychainViewModel.ImportState,
    onSubmit: (name: String, passphrase: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialName = remember(filename) {
        filename.substringBeforeLast('.').take(40).ifBlank { "Imported key" }
    }
    var name by remember { mutableStateOf(initialName) }
    var passphrase by remember { mutableStateOf("") }
    val passphraseVisible = state is KeychainViewModel.ImportState.NeedsPassphrase ||
        state is KeychainViewModel.ImportState.WrongPassphrase
    val working = state is KeychainViewModel.ImportState.Working
    val success = state is KeychainViewModel.ImportState.Success

    // Surface a "stalled" hint if Working stays put for 15 s — usually
    // means the user picked a giant key (or a junk file we misclassified
    // as PEM) and the parse is grinding.
    var stalled by remember(state) { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state is KeychainViewModel.ImportState.Working) {
            kotlinx.coroutines.delay(15_000)
            stalled = true
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import private key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "From: $filename",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                HorizontalDivider()
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    enabled = !working && !success,
                    modifier = Modifier.fillMaxWidth()
                )
                if (passphraseVisible) {
                    OutlinedTextField(
                        value = passphrase, onValueChange = { passphrase = it },
                        label = { Text("Passphrase") },
                        singleLine = true,
                        enabled = !working,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                when (state) {
                    is KeychainViewModel.ImportState.NeedsPassphrase -> Text(
                        "This key is encrypted — enter its passphrase.",
                        color = MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    is KeychainViewModel.ImportState.WrongPassphrase -> Text(
                        "Passphrase incorrect — try again.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    is KeychainViewModel.ImportState.Failed -> Text(
                        state.reason,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    is KeychainViewModel.ImportState.Success -> Text(
                        "Saved · ${state.key.fingerprint}",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    else -> {}
                }
                if (working) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(start = 0.dp).fillMaxWidth(0f),
                            strokeWidth = 2.dp,
                        )
                        Text("Parsing…", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (stalled) {
                        Text(
                            "Still parsing… try a smaller key or different file.",
                            color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !working && !success,
                onClick = { onSubmit(name, passphrase) },
            ) { Text(if (passphraseVisible) "Unlock & save" else "Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (success) "Done" else "Cancel") } }
    )
}

private fun copyToClipboard(ctx: Context, label: String, text: String) {
    val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cb.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun looksLikePrivateKey(s: String): Boolean {
    val head = s.take(2_000)
    return head.contains("BEGIN OPENSSH PRIVATE KEY") ||
        head.contains("BEGIN RSA PRIVATE KEY") ||
        head.contains("BEGIN DSA PRIVATE KEY") ||
        head.contains("BEGIN EC PRIVATE KEY") ||
        head.contains("BEGIN PRIVATE KEY") ||
        head.contains("BEGIN ENCRYPTED PRIVATE KEY")
}

private fun queryDisplayName(ctx: Context, uri: android.net.Uri): String? {
    return SilentlyTry.logged("Conch-Keychain", "query display name") {
        ctx.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }
}

@Composable
internal fun UseSecurityKeyDialog(
    mode: AddSkMode,
    state: KeychainViewModel.AddSkState,
    activity: android.app.Activity?,
    onPickTransport: (ai.eight24family.conch.domain.SecurityKeyTransport) -> Unit,
    onSubmitPin: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Resets the AddSk state machine back to Idle so the parent's
     *  auto-arm LaunchedEffect can fire fresh. Wired to a "Try again"
     *  button rendered in the terminal-error states (Failed / PinNotSet /
     *  PinNotSupported / WrongPin / PinBlocked / NoResidentCredentials)
     *  so the user has an explicit recovery path without leaving the
     *  dialog and losing transport context. */
    onRetry: () -> Unit = {},
) {
    var pinInput by remember { mutableStateOf("") }
    val isBusy = state is KeychainViewModel.AddSkState.AwaitingTap ||
        state is KeychainViewModel.AddSkState.AwaitingPin ||
        state is KeychainViewModel.AddSkState.Importing
    val isSaved = state is KeychainViewModel.AddSkState.Saved
    val isTerminalError = state is KeychainViewModel.AddSkState.Failed ||
        state is KeychainViewModel.AddSkState.PinNotSet ||
        state is KeychainViewModel.AddSkState.PinNotSupported ||
        state is KeychainViewModel.AddSkState.WrongPin ||
        state is KeychainViewModel.AddSkState.PinBlocked ||
        state is KeychainViewModel.AddSkState.NoResidentCredentials

    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        title = {
            Text(when (state) {
                is KeychainViewModel.AddSkState.AwaitingPin -> "Enter your security key PIN"
                is KeychainViewModel.AddSkState.AwaitingTap -> when (state.transport) {
                    ai.eight24family.conch.domain.SecurityKeyTransport.USB -> "Plug in your security key"
                    ai.eight24family.conch.domain.SecurityKeyTransport.NFC -> "Hold your security key to the phone"
                    ai.eight24family.conch.domain.SecurityKeyTransport.EITHER -> "Use your security key"
                }
                is KeychainViewModel.AddSkState.Importing ->
                    if (mode == AddSkMode.REGISTER) "Registering credential" else "Reading credentials"
                is KeychainViewModel.AddSkState.Saved ->
                    if (mode == AddSkMode.REGISTER) "Registered" else "Imported"
                else -> if (mode == AddSkMode.REGISTER) "Register a security key" else "Import from security key"
            })
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (state) {
                    // Idle while the dialog is mounted is a transient state
                    // — the parent's LaunchedEffect drives the actual
                    // import/register kick-off. We render the same waiting
                    // copy as AwaitingTap(EITHER) so the visual stays put
                    // through that one-frame transition.
                    KeychainViewModel.AddSkState.Idle ->
                        WaitingRow(label = "Plug it in, or hold against the back of the phone…")
                    is KeychainViewModel.AddSkState.AwaitingTap -> WaitingRow(
                        label = when (state.transport) {
                            ai.eight24family.conch.domain.SecurityKeyTransport.USB ->
                                "Plug the key into USB-C…"
                            ai.eight24family.conch.domain.SecurityKeyTransport.NFC ->
                                "Hold against the back of the phone…"
                            ai.eight24family.conch.domain.SecurityKeyTransport.EITHER ->
                                "Plug it in, or hold against the back of the phone…"
                        }
                    )
                    is KeychainViewModel.AddSkState.AwaitingPin -> {
                        Text(
                            if (state.transport == ai.eight24family.conch.domain.SecurityKeyTransport.NFC)
                                "Keep holding the key against the phone while you type."
                            else "Type the PIN you set on your security key.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PinPad(
                            pin = pinInput,
                            onChange = { pinInput = it },
                            onSubmit = {
                                if (pinInput.isNotEmpty()) {
                                    onSubmitPin(pinInput)
                                    pinInput = ""
                                }
                            },
                        )
                    }
                    is KeychainViewModel.AddSkState.Importing -> WaitingRow(
                        label = if (mode == AddSkMode.REGISTER) "Creating credential…" else "Reading credentials…"
                    )
                    is KeychainViewModel.AddSkState.Saved -> {
                        Text(
                            if (mode == AddSkMode.REGISTER)
                                "Registered new credential — add the public key below to ~/.ssh/authorized_keys."
                            else "Imported ${state.keys.size} credential(s) — same key as your desktop.",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        for (k in state.keys) {
                            Text(
                                k.fingerprint,
                                color = MaterialTheme.colorScheme.outline,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    KeychainViewModel.AddSkState.PinNotSet -> Text(
                        "This security key doesn't have a FIDO2 PIN set. Use your token's manager app on a desktop to set one, then come back.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    KeychainViewModel.AddSkState.PinNotSupported -> Text(
                        "This security key doesn't support PIN-protected credential management. Needs a FIDO2 / CTAP 2.1 authenticator.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    is KeychainViewModel.AddSkState.WrongPin -> Text(
                        "PIN incorrect" + (state.attemptsLeft?.let { " — $it attempt(s) left" } ?: ""),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    KeychainViewModel.AddSkState.PinBlocked -> Text(
                        "PIN locked after too many wrong attempts. Reset the FIDO2 application on a desktop using your security key's manager app — note that wipes resident keys.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    KeychainViewModel.AddSkState.NoResidentCredentials -> Text(
                        "No resident SSH credentials on this security key.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    is KeychainViewModel.AddSkState.Failed -> Text(
                        state.reason,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    else -> {}
                }
            }
        },
        confirmButton = {
            if (isTerminalError) {
                TextButton(onClick = onRetry) { Text("Try again") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (isSaved) "Done" else "Cancel")
            }
        }
    )
}

@Composable
private fun WaitingRow(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.padding(2.dp),
            strokeWidth = 2.dp,
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
