package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.linux.LocalLlm
import ai.eight24family.conch.linux.LocalLlmEngine
import ai.eight24family.conch.linux.PhoneResources
import ai.eight24family.conch.ui.window.handCursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ready models, plus downloads in flight (their progress/cancel/resume live
 * on their rows, the way Play's library shows an installing app). Discovering
 * and getting new models is the model STORE's job — `[ store ]` in the top
 * bar, and the empty state's one door.
 *
 * Tapping a ready row CHATS with that model, in the app, at once — the
 * conversation goes straight to the engine's loopback endpoint, so it needs
 * no Linux environment, no phone bridge and no CLI (see
 * [ai.eight24family.conch.linux.chat.LocalChatApi]).
 *
 * A downloaded model is ALSO a model choice for the real Codex CLI — its
 * tools, its sessions, its sandbox flags, with only the brain local. That
 * path is one tap further, from the chat's own `[ agent ]`: it needs the
 * phone's Alpine and a CLI install, which is exactly why it stopped being
 * the ENTRY (owner's local-models pivot — a model on disk must answer
 * without a developer-options ritual first).
 *
 * Everything shown is state, never advice: live free ram / storage, each
 * model's size + need + fits / tight / short verdict against free ram right
 * now, live download progress, and — while the engine serves — which model
 * holds the ram, with the one button that frees it.
 */
@Composable
internal fun LocalModelsBlock(
    onPickModel: (modelId: String) -> Unit,
    /** The dedicated screen shows each model's add-on packs (vision) with
     *  their own install rows; inline hosts keep the compact form. */
    showPacks: Boolean = false,
    /** The door to the store, for the empty state. */
    onOpenStore: (() -> Unit)? = null,
) {
    val dim = MaterialTheme.colorScheme.outline
    var res by remember { mutableStateOf<PhoneResources.Snapshot?>(null) }
    var statuses by remember { mutableStateOf<Map<String, LocalLlm.Status>>(emptyMap()) }
    val progress by LocalLlm.progress.collectAsState()
    val speeds by LocalLlm.speed.collectAsState()
    val revision by LocalLlm.revision.collectAsState()
    val engine by LocalLlmEngine.state.collectAsState()

    LaunchedEffect(Unit) {
        while (true) {
            res = withContext(Dispatchers.IO) { PhoneResources.read() }
            delay(3_000L)
        }
    }
    LaunchedEffect(revision, progress.keys) {
        statuses = withContext(Dispatchers.IO) {
            LocalLlm.CATALOG.associate { it.id to LocalLlm.status(it) }
        }
    }
    // Real publisher marks, from the store's DISK cache only — this panel is
    // not the store and opens no network for icons.
    val brands by ai.eight24family.conch.linux.store.BrandIcons.flow.collectAsState()
    LaunchedEffect(Unit) { ai.eight24family.conch.linux.store.BrandIcons.loadCached() }

    val engineAvailable = remember { LocalLlmEngine.available() }
    // One graphical, expandable spec card — the SAME [PhoneSpecSheet] the
    // store wears (ram/cpu/gpu/disk gauges, [ free ram ] on the ram row).
    PhoneSpecSheet()
    if (!engineAvailable) {
        Text(
            "this device's cpu is not supported (arm64 only)",
            color = dim,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        return
    }
    // ⛔ THE LIBRARY, NOT THE SHELF. Only models that are ON this phone —
    // ready, downloading, or paused mid-download — appear here; discovering
    // and getting new ones is the store's whole job. An in-flight download
    // stays visible because its cancel/resume and progress live on its row,
    // the way Play's library shows an installing app.
    val onPhone = LocalLlm.CATALOG.filter {
        (statuses[it.id] ?: LocalLlm.Status.Absent) !is LocalLlm.Status.Absent ||
            // ⛔ THE ROLE MODELS ARE LISTED EVEN WHEN ABSENT, because there is
            // nowhere else to get them: search and voice are not on the store's
            // shelf (its manifest is chat models), and a feature whose
            // prerequisite has no download button is a feature nobody can use.
            // Their rows say what they are for and cost one tap.
            !it.isBrain
    }
    if (onPhone.isEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "nothing on this device yet",
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            onOpenStore?.let { open ->
                Text(
                    "[ open store ]",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .handCursor()
                        .clickable { open() }
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
    }
    onPhone.forEach { m ->
        LocalModelRow(
            m = m,
            status = statuses[m.id] ?: LocalLlm.Status.Absent,
            liveBytes = progress[m.id],
            speedBps = speeds[m.id],
            ramFree = res?.ramFreeBytes,
            showPacks = showPacks,
            brand = brands[m.brandOrg],
            onPick = { onPickModel(m.id) },
        )
    }
    // The engine's footprint is real ram; the row that HOLDS it gets the
    // button that frees it.
    (engine as? LocalLlmEngine.State.Up)?.let { up ->
        val scope = rememberCoroutineScope()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "engine · serving ${LocalLlm.byId(up.modelId)?.label ?: up.modelId}" +
                    (if (up.gpu) " · gpu" else "") +
                    (if (LocalLlmEngine.keepLoaded.value) " · kept" else ""),
                color = dim,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f, fill = true),
            )
            // The pin, beside the button that frees the ram: the two halves of
            // the same decision. Idle reclaim reads as "the model unloaded
            // itself while I was reading the answer" for anyone who comes back
            // to a chat - and as a closed port for an app granted the local
            // API - so it must be suspendable in one tap, visibly.
            val pinned by LocalLlmEngine.keepLoaded.collectAsState()
            TextButton(onClick = { LocalLlmEngine.setKeepLoaded(!pinned) }) {
                Text(
                    if (pinned) "let idle" else "keep",
                    color = if (pinned) MaterialTheme.colorScheme.primary else dim,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            TextButton(onClick = { scope.launch { LocalLlmEngine.stop() } }) {
                Text(
                    "stop",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
    (engine as? LocalLlmEngine.State.Failed)?.let { f ->
        Text(
            "engine: ${f.reason}",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun LocalModelRow(
    m: LocalLlm.Model,
    status: LocalLlm.Status,
    liveBytes: Long?,
    speedBps: Long? = null,
    ramFree: Long?,
    showPacks: Boolean = false,
    brand: android.graphics.Bitmap? = null,
    onPick: () -> Unit,
) {
    val cyan = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.outline
    val err = MaterialTheme.colorScheme.error
    val need = LocalLlm.ramNeeded(m)
    val ready = status is LocalLlm.Status.Ready
    // Is THIS model the one currently loaded + serving? Its row then offers to
    // END it (stop the engine, free the RAM) instead of delete — you can't/
    // shouldn't delete a running model, and stopping it is what frees memory for
    // another model or a speed check. Delete returns once it's stopped (owner,
    // 2026-09-01).
    val engine by LocalLlmEngine.state.collectAsState()
    val servingThis = (engine as? LocalLlmEngine.State.Up)?.modelId == m.id
    val verifyingIds by LocalLlm.verifying.collectAsState()
    val verifying = m.id in verifyingIds
    val rowScope = rememberCoroutineScope()
    // Metered guard: gigabytes never start silently on mobile data — the
    // dialog names the exact bill first. bytes = what THIS tap costs.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var meteredAskBytes by remember { mutableStateOf<Long?>(null) }
    fun startGuarded(costBytes: Long) {
        if (ai.eight24family.conch.util.NetGuard.isMetered(ctx)) {
            meteredAskBytes = costBytes
        } else {
            LocalLlm.startDownload(m)
        }
    }
    meteredAskBytes?.let { cost ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { meteredAskBytes = null },
            title = { Text("Mobile data") },
            text = { Text("Download ${PhoneResources.gb(cost)} GB over mobile data?") },
            confirmButton = {
                TextButton(onClick = {
                    meteredAskBytes = null
                    LocalLlm.startDownload(m)
                }) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = { meteredAskBytes = null }) { Text("Cancel") }
            },
        )
    }
    val subtitle = when (status) {
        is LocalLlm.Status.Downloading -> {
            // Past the weights = the vision pack is streaming; No word
            // "downloading" — the bar below says it, and the line must FIT:
            // an ellipsized speed is worse than none.
            val soFar = liveBytes ?: status.bytesSoFar
            val total = if (soFar > m.bytes) LocalLlm.totalBytes(m) else m.bytes
            "${PhoneResources.gb(soFar)} of ${PhoneResources.gb(total)} GB" +
                (speedBps?.let { " · ${PhoneResources.rate(it)}" } ?: "")
        }
        is LocalLlm.Status.Paused -> buildString {
            append("paused at ${PhoneResources.gb(status.bytesSoFar)} of ${PhoneResources.gb(m.bytes)} GB — resumes")
            status.error?.let { append(" · ").append(it) }
        }
        is LocalLlm.Status.Ready ->
            if (verifying) {
                // The bar is at 100 % and the phone is reading a few gigabytes
                // to check them: silence here reads as a hang.
                "checking the download…"
            } else if (ai.eight24family.conch.linux.store.ModelRecords.of(m.id)?.failed == true)
                // Tried to load here and crashed the engine — say so plainly and
                // point at delete; never present it as usable (owner, 2026-09-01).
                "✕ won't run on this device — delete it"
            // What the tap DOES, in the words of what happens: a chat with
            // this model, generated here. The agent (a real CLI with tools)
            // lives one tap further, inside that chat.
            // An embedder has no chat to open - its row states its job.
            else if (m.embedder) "used to search your chats · not a chat model"
            else if (m.voice) "used to turn your voice into text · not a chat model"
            else "tap to chat · every token on this phone"
        is LocalLlm.Status.Absent -> buildString {
            // ONE line: size, ram need, the fits verdict, the blurb — and the
            // row ellipsizes rather than wrapping into a ragged four-line
            // paragraph.
            append(PhoneResources.gb(m.bytes)); append("G · ~")
            append(PhoneResources.gb(need)); append("G ram")
            if (ramFree != null) {
                when (LocalLlm.fit(m, ramFree)) {
                    LocalLlm.Fit.FITS -> append(" · fits")
                    LocalLlm.Fit.TIGHT -> append(" · tight")
                    LocalLlm.Fit.SHORT -> append(" · short ${PhoneResources.gb(need - ramFree)}G")
                }
            }
            append(" · "); append(m.blurb)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { base ->
                // ⛔ NO CLI GATE ANY MORE. This tap opens the in-app chat,
                // which needs nothing but the file on disk — it used to be
                // gated on a Codex install because it opened a CLI chat, and
                // a phone without the CLI was shown a row it could not tap.
                if (ready && m.isBrain) base.handCursor().clickable { onPick() } else base
            }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The model's own mark, everywhere a model is shown. Dim until
        // downloaded. monogram otherwise.
        FamilyMark(m.family, m.iconRes.takeIf { m.family == "qwen" }, ready, brand = brand)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f, fill = true)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    m.label,
                    color = if (ready) MaterialTheme.colorScheme.onSurface else dim,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // Tool-calling-capable models wear an "agent" mark; the rest
                // are honest chat/vision models. Read from the manifest's
                // researched `agent` flag — NOT size or family reflex, so a
                // tiny 0.8B is not miscalled an agent (owner, 2026-09-01). The
                // ✓ means it was actually proven firing tools on this app.
                val catalog by ai.eight24family.conch.linux.store.StoreCatalog.catalog.collectAsState()
                val entry = catalog.models.firstOrNull { it.id == m.id }
                if (entry?.agent == true) {
                    Spacer(Modifier.width(6.dp))
                    AgentBadge(verified = entry.tier == "verified")
                }
            }
            Text(
                subtitle,
                color = dim,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            // The bar IS the word "downloading" — and a paused download keeps
            // showing how much already lies on disk.
            if (status is LocalLlm.Status.Downloading || status is LocalLlm.Status.Paused) {
                val soFar = liveBytes ?: when (status) {
                    is LocalLlm.Status.Downloading -> status.bytesSoFar
                    is LocalLlm.Status.Paused -> status.bytesSoFar
                    else -> 0L
                }
                val total = (if (soFar > m.bytes) LocalLlm.totalBytes(m) else m.bytes)
                    .coerceAtLeast(1L)
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { (soFar.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        when (status) {
            is LocalLlm.Status.Ready -> {
                // No vision button here on purpose: the pack fetches ITSELF the
                // first time a chat actually sends an image (Wi-Fi silently,
                // mobile data behind an in-chat consent dialog) — the panel
                // stays one verb per row.
                if (servingThis) {
                    ModelAction("[ end ]", cyan) { rowScope.launch { LocalLlmEngine.stop() } }
                } else {
                    ModelAction("[ delete ]", dim) { LocalLlm.delete(m) }
                }
            }
            is LocalLlm.Status.Downloading -> ModelAction("[ cancel ]", err) { LocalLlm.cancelDownload(m) }
            is LocalLlm.Status.Paused -> ModelAction("[ resume ]", cyan) {
                // Past the weights = the pause is inside the vision pack.
                val target = if (status.bytesSoFar > m.bytes) LocalLlm.totalBytes(m) else m.bytes
                startGuarded((target - status.bytesSoFar).coerceAtLeast(0L))
            }
            is LocalLlm.Status.Absent -> ModelAction("[ download ]", cyan) { startGuarded(m.bytes) }
        }
    }
    // Add-on packs — the dedicated screen's detail layer. Vision is the one
    // pack today. Shown ONLY as an OFFER for a model that doesn't have it yet;
    // once installed it needs no row (the model just sees images, and the
    // auto-fetch-on-photo path handles the rest) — the "vision · installed"
    // line was noise the owner asked gone (2026-09-01).
    if (showPacks && ready && m.mmprojUrl != null && !LocalLlm.hasVision(m)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 52.dp, end = 20.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "vision · sees images · ${PhoneResources.gb(m.mmprojBytes)}G",
                color = dim,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            ModelAction("[ add ]", cyan) { startGuarded(m.mmprojBytes) }
        }
    }
}

/** The "agent" mark — worn by tool-calling-capable models, so the list
 *  separates real agents from chat/vision models at a glance (owner,
 *  2026-09-01). `✓ agent` when it was actually proven firing tools here;
 *  plain `agent` when it's capable by design but untested on this app. */
@Composable
private fun AgentBadge(verified: Boolean) {
    val cyan = MaterialTheme.colorScheme.primary
    Text(
        if (verified) "✓ agent" else "agent",
        color = cyan,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .border(1.dp, cyan.copy(alpha = if (verified) 0.7f else 0.45f), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** Bracketed text action — the same voice as the agent rows' `[ ready ]` /
 *  `[ log in ]`, instead of a boxed button shouting over a one-line row. */
@Composable
private fun ModelAction(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = color,
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        modifier = Modifier
            .handCursor()
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 8.dp),
    )
}


/**
 * The DEDICATED local-models screen — everything about the phone's own
 * brains in one place: live resources, each model's row (download / open /
 * delete), its add-on packs (vision), and the serving engine. Reached from
 * the agents panel's "Local Models" row.
 */
@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
fun LocalModelsScreen(
    onBack: () -> Unit,
    onPickModel: (modelId: String) -> Unit,
    /** Reopen a stored conversation with a model on this phone. */
    onOpenChat: (modelId: String, chatId: String) -> Unit,
    /** Opens the model store — the shelf of everything this phone can get. */
    onOpenStore: (() -> Unit)? = null,
) {
    androidx.compose.material3.Scaffold(
        topBar = {
            androidx.compose.material3.CenterAlignedTopAppBar(
                title = {
                    androidx.compose.material3.Text(
                        "local models",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "back",
                        )
                    }
                },
                actions = {
                    ImportModelAction()
                    onOpenStore?.let { open ->
                        androidx.compose.material3.Text(
                            "[ store ]",
                            color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .handCursor()
                                .clickable { open() }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxWidth()
                .padding(padding)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
        ) {
            LocalModelsBlock(
                onPickModel = onPickModel,
                showPacks = true,
                onOpenStore = onOpenStore,
            )
            LocalChatsBlock(onOpenChat = onOpenChat)
            LocalApiAccessBlock()
        }
    }
}

/**
 * The conversations this phone has already had with its own models.
 *
 * ⛔ A CHAT YOU CANNOT GET BACK TO IS A DEMO, NOT A FEATURE. The transcripts
 * are on disk from the first answer ([ai.eight24family.conch.linux.chat.LocalChatStore]),
 * so this list is the door back into them — the local half of what the
 * sessions list is for a server's CLI threads.
 *
 * Hidden entirely when there are none: an empty header would be furniture,
 * and the models above already say how a first chat starts.
 */
@Composable
internal fun LocalChatsBlock(onOpenChat: (modelId: String, chatId: String) -> Unit) {
    val dim = MaterialTheme.colorScheme.outline
    val chats by ai.eight24family.conch.linux.chat.LocalChatStore.chats.collectAsState()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { ai.eight24family.conch.linux.chat.LocalChatStore.reload() }
    }
    if (chats.isEmpty()) return
    ChatSearchBlock(onOpenChat)
    Text(
        "// chats",
        color = dim,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 2.dp),
    )
    chats.forEach { c ->
        // Deleting a transcript is not recoverable, and a single stray tap on
        // a row full of them would be. The verb arms first and says so.
        var armed by remember(c.id) { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .handCursor()
                .clickable { onOpenChat(c.modelId, c.id) }
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f, fill = true)) {
                Text(
                    c.title,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    (LocalLlm.byId(c.modelId)?.label ?: c.modelId) +
                        " · ${c.turns} turn" + (if (c.turns == 1) "" else "s") +
                        " · " + formatStamp(c.updatedAtMs / 1000L),
                    color = dim,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            ModelAction(
                if (armed) "[ sure? ]" else "[ x ]",
                if (armed) MaterialTheme.colorScheme.error else dim,
            ) {
                if (armed) {
                    ai.eight24family.conch.linux.chat.LocalChatStore.delete(c.id)
                } else {
                    armed = true
                }
            }
        }
    }
}

/**
 * Which OTHER apps on this phone may talk to the local engine — the visible
 * half of [ai.eight24family.conch.linux.chat.LocalApiAccess].
 *
 * ⛔ A GRANT THE OWNER CANNOT SEE IS NOT A GRANT, IT IS A LEAK. The engine's
 * loopback port is reachable by every app on the device (Android does not
 * isolate 127.0.0.1), so access is a key — and a key needs a place that names
 * who holds one and takes it back in one tap. Hidden while nobody holds one,
 * because a permission list with no permissions in it is furniture.
 *
 * Revoking rewrites the key file AND restarts a serving engine: the keys are
 * read at startup, so without the restart the revoke would be a promise the
 * port has not heard about.
 */
@Composable
internal fun LocalApiAccessBlock() {
    val dim = MaterialTheme.colorScheme.outline
    val grants by ai.eight24family.conch.linux.chat.LocalApiAccess.grants.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { ai.eight24family.conch.linux.chat.LocalApiAccess.all() }
    }
    if (grants.isEmpty()) return
    Text(
        "// api access",
        color = dim,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 2.dp),
    )
    grants.forEach { g ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f, fill = true)) {
                Text(
                    g.label,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    g.pkg + " · allowed " + formatStamp(g.grantedAtMs / 1000L),
                    color = dim,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            ModelAction("[ revoke ]", MaterialTheme.colorScheme.error) {
                ai.eight24family.conch.linux.chat.LocalApiAccess.revoke(g.pkg)
                scope.launch { LocalLlmEngine.restartForKeys() }
            }
        }
    }
}

/**
 * Search the local chats by MEANING, using the embedding model on this phone.
 *
 * ⛔ SEMANTIC, BECAUSE THE WORDS NEVER MATCH. a substring search finds neither
 * from the other, in any language. Measured with the model this ships
 * (bge-m3): a Russian question ranked both the Russian AND the English answer
 * above everything unrelated.
 *
 * Nothing is indexed until the owner taps [ index ]: embedding an archive is
 * minutes of CPU and warmth, and this app does not spend either behind
 * someone's back. The state line says exactly what exists.
 */
@Composable
private fun ChatSearchBlock(onOpenChat: (modelId: String, chatId: String) -> Unit) {
    val dim = MaterialTheme.colorScheme.outline
    val cyan = MaterialTheme.colorScheme.primary
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val status by ai.eight24family.conch.linux.embed.ChatIndex.status.collectAsState()
    val progress by ai.eight24family.conch.linux.embed.ChatIndex.progress.collectAsState()
    val revision by LocalLlm.revision.collectAsState()
    val dlProgress by LocalLlm.progress.collectAsState()
    var query by remember { mutableStateOf("") }
    var hits by remember {
        mutableStateOf<List<ai.eight24family.conch.linux.embed.ChatIndex.Hit>?>(null)
    }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { ai.eight24family.conch.linux.embed.ChatIndex.ensureLoaded() }
    }

    // The model this needs, and whether it is here yet.
    val embedModel = remember(revision) {
        LocalLlm.CATALOG.firstOrNull { it.embedder }
    }
    val embedReady = remember(revision, dlProgress) {
        embedModel != null && LocalLlm.isReady(embedModel)
    }
    val downloading = embedModel != null && dlProgress[embedModel.id] != null

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "// search chats",
            color = dim,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        when {
            embedModel == null -> Unit
            downloading -> {
                val got = dlProgress[embedModel.id] ?: 0L
                Text(
                    PhoneResources.gb(got) + " of " + PhoneResources.gb(embedModel.bytes) + "G",
                    color = dim,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            // The feature offers its own prerequisite, with the price on the
            // button - never a silent multi-hundred-megabyte download.
            !embedReady -> ModelAction("[ get " + PhoneResources.gb(embedModel.bytes) + "G ]", cyan) {
                if (ai.eight24family.conch.util.NetGuard.isMetered(ctx)) {
                    note = "on mobile data, start it from the model's row"
                } else {
                    LocalLlm.startDownload(embedModel)
                }
            }
            progress != null -> Text(
                "indexing " + progress!!.first + " / " + progress!!.second,
                color = cyan,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
            else -> ModelAction(if (status.isEmpty) "[ index ]" else "[ reindex ]", cyan) {
                if (busy) return@ModelAction
                busy = true
                note = null
                scope.launch {
                    // try/finally: an exception here used to leave `busy`
                    // true forever, and the button dead with no message.
                    val r = try {
                        ai.eight24family.conch.linux.embed.ChatIndex.build()
                    } catch (t: Throwable) {
                        busy = false
                        note = t.message ?: t.javaClass.simpleName
                        return@launch
                    }
                    busy = false
                    note = when (r) {
                        is ai.eight24family.conch.linux.embed.ChatIndex.Build.Done ->
                            null
                        ai.eight24family.conch.linux.embed.ChatIndex.Build.NothingToIndex ->
                            "no chats to index yet"
                        ai.eight24family.conch.linux.embed.ChatIndex.Build.NoModel ->
                            "the search model is not downloaded"
                        is ai.eight24family.conch.linux.embed.ChatIndex.Build.Failed ->
                            r.reason
                    }
                }
            }
        }
    }
    // State, not advice: what is indexed, by which model, or what is missing.
    Text(
        when {
            embedModel == null -> "no search model in this build"
            !embedReady && downloading -> "getting the search model"
            !embedReady -> embedModel.blurb
            status.isEmpty -> "not indexed yet - tap index"
            else -> status.entries.toString() + " messages from " + status.chats +
                " chats - " + (LocalLlm.byId(status.modelId ?: "")?.label ?: "?")
        },
        color = dim,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        maxLines = 2,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
    )
    note?.let {
        Text(
            it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )
    }

    if (!embedReady || status.isEmpty) return

    androidx.compose.material3.OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        singleLine = true,
        enabled = !busy,
        placeholder = {
            Text(
                "what was it about?",
                style = MaterialTheme.typography.bodySmall,
                color = dim,
            )
        },
        // ⛔ ON SUBMIT, NOT PER KEYSTROKE. Every search is an embedding pass
        // on the phone's CPU; searching as you type would run one per letter
        // and heat the device to answer a question nobody finished asking.
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = androidx.compose.ui.text.input.ImeAction.Search,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
            onSearch = {
                if (query.isBlank()) { hits = null; return@KeyboardActions }
                busy = true
                note = null
                scope.launch {
                    when (val r = ai.eight24family.conch.linux.embed.ChatIndex.search(query)) {
                        is ai.eight24family.conch.linux.embed.ChatIndex.Found.Ok -> {
                            hits = r.hits
                            if (r.hits.isEmpty()) note = "nothing close to that"
                        }
                        ai.eight24family.conch.linux.embed.ChatIndex.Found.NotIndexed ->
                            note = "not indexed yet"
                        ai.eight24family.conch.linux.embed.ChatIndex.Found.StaleModel ->
                            note = "the index was built by another model - reindex"
                        is ai.eight24family.conch.linux.embed.ChatIndex.Found.Failed ->
                            note = r.reason
                    }
                    busy = false
                }
            },
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
    )

    hits?.forEach { h ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .handCursor()
                .clickable { onOpenChat(h.entry.modelId, h.entry.chatId) }
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f, fill = true)) {
                Text(
                    h.chatTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    h.entry.snippet,
                    color = dim,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            // The score, because a semantic hit is a GUESS and the number is
            // how the owner judges it.
            Text(
                String.format(java.util.Locale.US, "%.2f", h.score),
                color = dim,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
