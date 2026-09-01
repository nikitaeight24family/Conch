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
 * A downloaded model IS a model choice for the real Codex CLI — tapping its
 * row opens a Codex chat already set to `local:<id>`, and CodexSpec routes
 * that chat's turns to the phone's inference engine on loopback. The AGENT is
 * genuine Codex: its tools, its sessions, its sandbox flags; only the brain
 * is local.
 *
 * Everything shown is state, never advice: live free ram / storage, each
 * model's size + need + fits / tight / short verdict against free ram right
 * now, live download progress, and — while the engine serves — which model
 * holds the ram, with the one button that frees it.
 */
@Composable
internal fun LocalModelsBlock(
    codexInstalled: Boolean,
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
        (statuses[it.id] ?: LocalLlm.Status.Absent) !is LocalLlm.Status.Absent
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
            codexInstalled = codexInstalled,
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
                    if (up.gpu) " · gpu" else "",
                color = dim,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f, fill = true),
            )
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
    codexInstalled: Boolean,
    showPacks: Boolean = false,
    brand: android.graphics.Bitmap? = null,
    onPick: () -> Unit,
) {
    val cyan = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.outline
    val err = MaterialTheme.colorScheme.error
    val need = LocalLlm.ramNeeded(m)
    val ready = status is LocalLlm.Status.Ready
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
            // The app routes each model to the CLI that fits it (Qwen Code for
            // Qwen models, Codex otherwise) — no environment for the owner to
            // pick; the turn sets the CLI up on first use if it isn't yet.
            "tap to work with ${
                ai.eight24family.conch.agent.spec.AgentSpecRegistry[
                    ai.eight24family.conch.linux.LocalLlm.harnessFor(m.id)
                ].displayName
            } on this model"
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
                if (ready && codexInstalled) {
                    base.handCursor().clickable { onPick() }
                } else base
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
                ModelAction("[ delete ]", dim) { LocalLlm.delete(m) }
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
    codexInstalled: Boolean = true,
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
                codexInstalled = codexInstalled,
                onPickModel = onPickModel,
                showPacks = true,
                onOpenStore = onOpenStore,
            )
        }
    }
}

