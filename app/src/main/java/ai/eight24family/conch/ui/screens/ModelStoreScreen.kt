package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.eight24family.conch.linux.LocalLlm
import ai.eight24family.conch.linux.LocalLlmEngine
import ai.eight24family.conch.linux.PhoneResources
import ai.eight24family.conch.linux.store.DeviceProfile
import ai.eight24family.conch.linux.store.HfStats
import ai.eight24family.conch.linux.store.ModelRecords
import ai.eight24family.conch.linux.store.StoreCatalog
import ai.eight24family.conch.ui.window.handCursor
import ai.eight24family.conch.util.NetGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The MODEL STORE — Play-shaped, phone-aware.
 *
 * Two screens, deliberately shaped like an app store because that's what it
 * is: a browse screen of CARDS (mark, name, publisher line, live stats, one
 * button) grouped in sections, and a per-model PAGE (header, stats strip,
 * full-width action, on-this-phone facts, description, rating stars).
 *
 * What no other store has, carried by layout: the "this phone" banner leads,
 * every verdict on every card is computed against the device (ram capacity,
 * KV math, bandwidth-derived speed estimates that self-calibrate from real
 * measurements), popularity is Hugging Face's true pull/like counts for the
 * exact repo the button fetches, and the trust badges are facts recorded on
 * THIS device — ran here, measured tok/s, the owner's stars. All local.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelStoreScreen(
    onBack: () -> Unit,
    onOpenModel: (modelId: String) -> Unit,
    onPickModel: (modelId: String) -> Unit,
) {
    val catalog by StoreCatalog.catalog.collectAsState()
    val revision by LocalLlm.revision.collectAsState()
    val progress by LocalLlm.progress.collectAsState()
    val speeds by LocalLlm.speed.collectAsState()
    val records by ModelRecords.flow.collectAsState()
    val stats by HfStats.flow.collectAsState()
    val brands by ai.eight24family.conch.linux.store.BrandIcons.flow.collectAsState()
    var res by remember { mutableStateOf<PhoneResources.Snapshot?>(null) }
    val profile = remember { DeviceProfile.read() }
    var fitsOnly by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var statuses by remember { mutableStateOf<Map<String, LocalLlm.Status>>(emptyMap()) }
    var hits by remember { mutableStateOf<List<ai.eight24family.conch.linux.store.HfBrowse.Hit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            res = withContext(Dispatchers.IO) { PhoneResources.read() }
            delay(3_000L)
        }
    }
    LaunchedEffect(Unit) {
        StoreCatalog.refresh()
        val shelf = StoreCatalog.catalog.value.models
        HfStats.refresh(shelf.mapNotNull { it.hfRepo })
        ai.eight24family.conch.linux.store.BrandIcons.refresh(shelf.mapNotNull { it.brandOrg })
        ModelRecords.all()
    }
    LaunchedEffect(revision, progress.keys, catalog) {
        statuses = withContext(Dispatchers.IO) {
            catalog.models.mapNotNull { e -> LocalLlm.byId(e.id) }
                .associate { it.id to LocalLlm.status(it) }
        }
    }
    LaunchedEffect(query) {
        val q = query.trim()
        searching = true
        // No query → the WHOLE ecosystem, ranked by popularity (the store is
        // not a hand-list); typing → search all of Hugging Face. Either way the
        // curated shelf leads and these fill the long tail below it.
        hits = if (q.length < 2) {
            ai.eight24family.conch.linux.store.HfBrowse.popular()
        } else {
            delay(400) // debounce typing
            ai.eight24family.conch.linux.store.HfBrowse.search(q)
        }
        searching = false
        // Fetch the REAL brand orgs (inferred from model names), not uploaders.
        ai.eight24family.conch.linux.store.BrandIcons.refresh(
            hits.mapNotNull { it.brandOrg }.distinct().take(12),
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "model store",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
                    }
                },
            )
        },
    ) { padding ->
        val visible = remember(catalog, fitsOnly, query, res, records) {
            catalog.models.filter { e ->
                // A model that already crashed the engine on this device can't
                // run here — never re-offer it (owner, 2026-09-01).
                records[e.id]?.failed != true &&
                    (!fitsOnly || DeviceProfile.runsOnThisPhone(e, catalog, profile)) &&
                    (query.isBlank() || listOfNotNull(e.label, e.family, e.blurb, LocalLlm.byId(e.id)?.label)
                        .any { it.contains(query.trim(), ignoreCase = true) })
            }
        }
        val hidden = catalog.models.size - catalog.models.count {
            DeviceProfile.runsOnThisPhone(it, catalog, profile)
        }
        LazyColumn(Modifier.fillMaxWidth().padding(padding)) {
            item(key = "device") { PhoneSpecSheet() }
            item(key = "controls") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PillToggle("fits this device", fitsOnly) { fitsOnly = true }
                    Spacer(Modifier.width(8.dp))
                    PillToggle(if (hidden > 0) "all +$hidden" else "all", !fitsOnly) { fitsOnly = false }
                    Spacer(Modifier.width(10.dp))
                    SearchField(query, { query = it }, Modifier.weight(1f))
                }
            }
            val cats = listOf(
                "tiny" to "Tiny",
                "everyday" to "Everyday",
                "strong" to "Strong",
                "frontier" to "Frontier",
            )
            cats.forEach { (cat, title) ->
                val inCat = visible.filter { it.cat == cat }.sortedBy { entryBytes(it) }
                if (inCat.isNotEmpty()) {
                    item(key = "hdr-$cat") {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${inCat.size}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                    inCat.forEach { e ->
                        item(key = e.id) {
                            ModelCard(
                                e = e,
                                catalog = catalog,
                                status = statuses[e.id],
                                liveBytes = progress[e.id],
                                speedBps = speeds[e.id],
                                ramFree = res?.ramFreeBytes,
                                rec = records[e.id],
                                stat = stats[e.hfRepo],
                                brand = brands[e.brandOrg],
                                onOpen = { onOpenModel(e.id) },
                                onChat = { onPickModel(e.id) },
                            )
                        }
                    }
                }
            }
            val shelfRepos = catalog.models.mapNotNull { it.hfRepo }.toSet()
            val freshHits = hits.filter { it.repo !in shelfRepos }
            // The ecosystem, always — popular GGUF models from all of Hugging
            // Face when nothing's typed, search results when it is. The curated
            // shelf above leads; this is the long tail so the store is never a
            // hand-list of a dozen (owner, 2026-09-01).
            val searchingNow = query.trim().length >= 2
            run {
                item(key = "hdr-hf") {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (searchingNow) "On Hugging Face" else "Popular on Hugging Face",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (searching) "loading…" else "${freshHits.size} · tap to inspect · untested here",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                freshHits.forEach { hit ->
                    item(key = "hf-${hit.repo}") {
                        BrowseHitCard(
                            hit = hit,
                            brand = brands[hit.brandOrg],
                            onOpen = {
                                onOpenModel(ai.eight24family.conch.linux.store.HfBrowse.register(hit))
                            },
                        )
                    }
                }
            }
            item(key = "gpu") { GpuRuntimeFooter(profile, records.values.any { it.ranGpu }) }
            item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun entryBytes(e: StoreCatalog.Entry): Long =
    if (e.bytes > 0) e.bytes else LocalLlm.byId(e.id)?.bytes ?: Long.MAX_VALUE

// The "this phone" card is the shared, graphical, expandable [PhoneSpecSheet]
// (ram/cpu/gpu/disk gauges, each row tap-to-expand) — used by the store
// banner AND the library header so both speak one spec language.

// ── controls ──

@Composable
private fun PillToggle(label: String, active: Boolean, onClick: () -> Unit) {
    val cyan = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.outline
    Surface(
        shape = CircleShape,
        color = if (active) cyan.copy(alpha = 0.16f) else Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (active) cyan else dim.copy(alpha = 0.5f)),
        modifier = Modifier.handCursor().clickable { onClick() },
    ) {
        Text(
            label,
            color = if (active) cyan else dim,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val dim = MaterialTheme.colorScheme.outline
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text("⌕", color = dim, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) {
                            Text("search models", color = dim, style = MaterialTheme.typography.bodySmall)
                        }
                        inner()
                    }
                }
            },
        )
    }
}

// ── the card ──

@Composable
private fun ModelCard(
    e: StoreCatalog.Entry,
    catalog: StoreCatalog.Catalog,
    status: LocalLlm.Status?,
    liveBytes: Long?,
    speedBps: Long?,
    ramFree: Long?,
    rec: ModelRecords.Rec?,
    stat: HfStats.Stat?,
    brand: android.graphics.Bitmap?,
    onOpen: () -> Unit,
    onChat: () -> Unit,
) {
    val dim = MaterialTheme.colorScheme.outline
    val model = LocalLlm.byId(e.id) ?: StoreCatalog.toModel(e)
    val label = model?.label ?: e.label ?: e.id
    val ready = status is LocalLlm.Status.Ready
    val publisher = e.hfRepo?.substringBefore('/') ?: "community"

    var meteredAskBytes by remember { mutableStateOf<Long?>(null) }
    MeteredAsk(meteredAskBytes, onDismiss = { meteredAskBytes = null }) {
        meteredAskBytes = null
        model?.let { LocalLlm.startDownload(it) }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .handCursor()
            .clickable { onOpen() },
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FamilyMark(e.family, model?.iconRes?.takeIf { e.family == "qwen" }, ready, 44.dp, brand)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f, fill = true)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            label,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // Take the row's slack and ellipsize — otherwise the
                            // title starves the badge to 0 width and it stacks
                            // one letter per line (owner, 2026-09-01).
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (e.agent) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (e.tier == "verified") "✓ agent" else "agent",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                    Text(
                        "$publisher · ${e.quant ?: "gguf"} · ${PhoneResources.gb(entryBytes(e))}G" +
                            (if (model?.mmprojUrl != null) " · vision" else ""),
                        color = dim,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    CardStatsLine(e, catalog, status, ramFree, rec, stat, model)
                }
                Spacer(Modifier.width(10.dp))
                CardAction(model, status, onChat) { cost -> meteredAskBytes = cost }
            }
            if (status is LocalLlm.Status.Downloading && model != null) {
                val soFar = liveBytes ?: status.bytesSoFar
                val total = (if (soFar > model.bytes) LocalLlm.totalBytes(model) else model.bytes)
                    .coerceAtLeast(1L)
                LinearProgressIndicator(
                    progress = { (soFar.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                )
                Text(
                    "${PhoneResources.gb(soFar)} of ${PhoneResources.gb(total)} GB" +
                        (speedBps?.let { " · ${PhoneResources.rate(it)}" } ?: ""),
                    color = dim,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** One line of store-truth under the title: stars when the owner rated it,
 *  real pulls/likes/speed as ONE truncating text, and a single compact chip.
 *  The text carries the weight so it ellipsizes — a long line must never
 *  wrap character-by-character into a tower (that shipped once, 2026-09-01).
 *  Glyphs carry U+FE0E so ♥ stays a tinted glyph, not a red emoji. */
@Composable
private fun CardStatsLine(
    e: StoreCatalog.Entry,
    catalog: StoreCatalog.Catalog,
    status: LocalLlm.Status?,
    ramFree: Long?,
    rec: ModelRecords.Rec?,
    stat: HfStats.Stat?,
    model: LocalLlm.Model?,
) {
    val dim = MaterialTheme.colorScheme.outline
    val accent = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        rec?.rating?.let {
            Text(
                "★$it",
                color = accent,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(6.dp))
        }
        val statsText = buildList {
            stat?.let { add("⤓︎ ${HfStats.fmt(it.downloads)}"); add("♥︎ ${HfStats.fmt(it.likes)}") }
            if (status !is LocalLlm.Status.Ready && status !is LocalLlm.Status.Downloading &&
                DeviceProfile.runsOnThisPhone(e, catalog)
            ) add("~${DeviceProfile.estTokS(e, catalog)} tok/s")
        }.joinToString(" · ")
        if (statsText.isNotEmpty()) {
            Text(
                statsText,
                color = dim,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(6.dp))
        }
        when {
            // Downloaded → the [Open] button already says it's on device; a
            // separate "on device" chip is noise (owner, 2026-09-01). Show a
            // chip ONLY when it carries new info: measured speed, or ✓ ran.
            status is LocalLlm.Status.Ready -> when {
                rec?.tokS != null -> FitChip(String.format(java.util.Locale.US, "%.1f tok/s", rec.tokS), accent)
                rec?.ran == true -> FitChip("✓ ran", accent)
                else -> {}
            }
            status is LocalLlm.Status.Paused -> FitChip("paused", MaterialTheme.colorScheme.tertiary)
            status is LocalLlm.Status.Downloading -> {}
            !DeviceProfile.runsOnThisPhone(e, catalog) -> FitChip(
                "needs ${PhoneResources.gb(DeviceProfile.needBytes(e))}G",
                MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
            )
            else -> {
                val fit = if (ramFree != null && model != null) LocalLlm.fit(model, ramFree) else null
                when (fit) {
                    LocalLlm.Fit.TIGHT -> FitChip("tight", MaterialTheme.colorScheme.tertiary)
                    LocalLlm.Fit.SHORT -> FitChip("ram busy", MaterialTheme.colorScheme.tertiary)
                    else -> FitChip("fits", accent)
                }
            }
        }
    }
}

/** A Hugging Face search result — knows only what the listing said. Size,
 *  file and gated-ness resolve on its page; the card promises nothing. */
@Composable
private fun BrowseHitCard(
    hit: ai.eight24family.conch.linux.store.HfBrowse.Hit,
    brand: android.graphics.Bitmap?,
    onOpen: () -> Unit,
) {
    val dim = MaterialTheme.colorScheme.outline
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .handCursor()
            .clickable { onOpen() },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FamilyMark(hit.family, null, true, 44.dp, brand)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f, fill = true)) {
                Text(
                    hit.repo.substringAfter('/').removeSuffix("-GGUF").removeSuffix("-gguf"),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    hit.repo.substringBefore('/'),
                    color = dim,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "⤓︎ ${HfStats.fmt(hit.downloads)}/mo · ♥︎ ${HfStats.fmt(hit.likes)}",
                    color = dim,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            StorePillButton("View") { onOpen() }
        }
    }
}

@Composable
private fun FitChip(text: String, color: Color) {
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.14f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.6f)),
    ) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
        )
    }
}

/** The one button a card carries — Play grammar: Get / Resume / Open. */
@Composable
private fun CardAction(
    model: LocalLlm.Model?,
    status: LocalLlm.Status?,
    onChat: () -> Unit,
    askMetered: (Long) -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun start(cost: Long) {
        val m = model ?: return
        LocalLlm.addFromStore(m)
        if (NetGuard.isMetered(ctx)) askMetered(cost) else LocalLlm.startDownload(m)
    }
    when (status) {
        null, is LocalLlm.Status.Absent -> StorePillButton("Get") { start(model?.bytes ?: 0L) }
        is LocalLlm.Status.Downloading -> TextButton(onClick = { model?.let { LocalLlm.cancelDownload(it) } }) {
            Text(
                "cancel",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
        is LocalLlm.Status.Paused -> StorePillButton("Resume") {
            model?.let {
                val target = if (status.bytesSoFar > it.bytes) LocalLlm.totalBytes(it) else it.bytes
                start((target - status.bytesSoFar).coerceAtLeast(0L))
            }
        }
        is LocalLlm.Status.Ready -> StorePillButton("Open") { onChat() }
    }
}

/** Play's install button, at Conch volume: compact, quiet fill, no shouting. */
@Composable
private fun StorePillButton(label: String, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        shape = CircleShape,
        color = accent.copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
        modifier = Modifier.handCursor().clickable { onClick() },
    ) {
        Text(
            label,
            color = accent,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun MeteredAsk(costBytes: Long?, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    costBytes ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mobile data") },
        text = { Text("Download ${PhoneResources.gb(costBytes)} GB over mobile data?") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Download") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ── the model PAGE — the store's "app page" ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPageScreen(
    modelId: String,
    onBack: () -> Unit,
    onPickModel: (modelId: String) -> Unit,
) {
    val catalog by StoreCatalog.catalog.collectAsState()
    val revision by LocalLlm.revision.collectAsState()
    val progress by LocalLlm.progress.collectAsState()
    val speeds by LocalLlm.speed.collectAsState()
    val records by ModelRecords.flow.collectAsState()
    val stats by HfStats.flow.collectAsState()
    val brands by ai.eight24family.conch.linux.store.BrandIcons.flow.collectAsState()
    val browseEntries by ai.eight24family.conch.linux.store.HfBrowse.resolved.collectAsState()
    var res by remember { mutableStateOf<PhoneResources.Snapshot?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            res = withContext(Dispatchers.IO) { PhoneResources.read() }
            delay(3_000L)
        }
    }
    // A browse page earns its facts on entry: the downloadable file, its true
    // size, gated-ness, all-time counts — one repo call + one tree call.
    LaunchedEffect(modelId) {
        ai.eight24family.conch.linux.store.HfBrowse.resolve(modelId)
    }

    val e = catalog.models.firstOrNull { it.id == modelId } ?: browseEntries[modelId]
    val model = LocalLlm.byId(modelId) ?: e?.let { StoreCatalog.toModel(it) }
    LaunchedEffect(e?.brandOrg) {
        e?.brandOrg?.let { ai.eight24family.conch.linux.store.BrandIcons.refresh(listOf(it)) }
    }
    val dim = MaterialTheme.colorScheme.outline
    val cyan = MaterialTheme.colorScheme.primary

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
                    }
                },
            )
        },
    ) { padding ->
        if (e == null) {
            Text(
                "this model left the catalog",
                color = dim,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(padding).padding(20.dp),
            )
            return@Scaffold
        }
        var statusTick by remember { mutableStateOf(0) }
        val status = remember(revision, progress.keys, statusTick, model) {
            model?.let { LocalLlm.status(it) }
        }
        val ready = status is LocalLlm.Status.Ready
        val rec = records[e.id]
        val stat = stats[e.hfRepo]

        val ctx = androidx.compose.ui.platform.LocalContext.current
        var meteredAskBytes by remember { mutableStateOf<Long?>(null) }
        MeteredAsk(meteredAskBytes, onDismiss = { meteredAskBytes = null }) {
            meteredAskBytes = null
            model?.let { LocalLlm.startDownload(it) }
        }

        LazyColumn(Modifier.fillMaxWidth().padding(padding)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FamilyMark(
                        e.family, model?.iconRes?.takeIf { e.family == "qwen" }, true, 64.dp,
                        brands[e.brandOrg],
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            model?.label ?: e.label ?: e.id,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "${e.hfRepo?.substringBefore('/') ?: "community"} · ${e.quant ?: "gguf"}",
                            color = dim,
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            when {
                                e.tier == "verified" -> "✓ agent — proven firing tools on this app"
                                e.agent -> "agent — tool-calling capable (untested here)"
                                else -> "chat / vision model — not built for agent tools"
                            },
                            color = if (e.agent) cyan else dim,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
            item {
                // The Play-style stats strip: pulls | likes | size | speed.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    StatCell(stat?.let { HfStats.fmt(it.downloads) } ?: "—", "pulls")
                    StatCell(stat?.let { HfStats.fmt(it.likes) } ?: "—", "likes")
                    StatCell(if (e.bytes > 0) "${PhoneResources.gb(e.bytes)}G" else "…", "download")
                    StatCell(
                        rec?.tokS?.let { String.format(java.util.Locale.US, "%.1f", it) }
                            ?: if (e.bytes > 0) "~${DeviceProfile.estTokS(e, catalog)}" else "…",
                        if (rec?.tokS != null) "tok/s here" else "tok/s est",
                    )
                }
            }
            item {
                // Full-width action — the page's one big verb.
                Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                    if (e.gated) {
                        Text(
                            "gated repo — needs a Hugging Face sign-in; the store only shelves what downloads anonymously",
                            color = dim,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        return@Box
                    }
                    if (model == null || status == null) {
                        Text(
                            "resolving the download — file, size, license gate…",
                            color = dim,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        return@Box
                    }
                    when (status) {
                        is LocalLlm.Status.Absent -> Button(
                            onClick = {
                                LocalLlm.addFromStore(model)
                                if (NetGuard.isMetered(ctx)) meteredAskBytes = model.bytes
                                else LocalLlm.startDownload(model)
                                statusTick++
                            },
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth().height(44.dp).handCursor(),
                        ) { Text("Get · ${PhoneResources.gb(model.bytes)} GB") }
                        is LocalLlm.Status.Downloading -> Column {
                            val soFar = progress[e.id] ?: status.bytesSoFar
                            val total = (if (soFar > model.bytes) LocalLlm.totalBytes(model) else model.bytes)
                                .coerceAtLeast(1L)
                            LinearProgressIndicator(
                                progress = { (soFar.toFloat() / total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${PhoneResources.gb(soFar)} of ${PhoneResources.gb(total)} GB" +
                                        (speeds[e.id]?.let { " · ${PhoneResources.rate(it)}" } ?: ""),
                                    color = dim,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                                TextButton(onClick = { LocalLlm.cancelDownload(model); statusTick++ }) {
                                    Text("cancel", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        is LocalLlm.Status.Paused -> Button(
                            onClick = {
                                val target = if (status.bytesSoFar > model.bytes) LocalLlm.totalBytes(model) else model.bytes
                                val cost = (target - status.bytesSoFar).coerceAtLeast(0L)
                                if (NetGuard.isMetered(ctx)) meteredAskBytes = cost
                                else LocalLlm.startDownload(model)
                                statusTick++
                            },
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth().height(44.dp).handCursor(),
                        ) { Text("Resume · ${PhoneResources.gb(status.bytesSoFar)} GB done") }
                        is LocalLlm.Status.Ready -> Button(
                            onClick = { onPickModel(e.id) },
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth().height(44.dp).handCursor(),
                        ) { Text("Open — chat on this model") }
                    }
                }
            }
            if (model != null) {
                item {
                    OnThisPhoneCard(e, catalog, model, ready, rec, res)
                }
                if (model.mmprojUrl != null) {
                    item {
                        AddOnsBlock(model, ready)
                    }
                } else {
                    // Say it plainly, so "why no vision here?" never has to be
                    // asked (owner, 2026-09-01). Vision is not built into text
                    // GGUFs — it needs a projector this model's family doesn't
                    // ship; only Qwen 3.5 in this catalog does.
                    item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                            Text(
                                "Add-ons",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "text-only — this model has no image input. Vision needs a separate projector, which its family doesn't ship here.",
                                color = MaterialTheme.colorScheme.outline,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
            e.desc?.let { desc ->
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text(
                            "About this model",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            desc,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        val kv = if (e.kvPerTok > 0) {
                            " · kv ${PhoneResources.gb(e.kvPerTok * LocalLlmEngine.CTX_TOKENS)}G @16k"
                        } else ""
                        Text(
                            "ctx 16k$kv · engine llama.cpp on-device · ${e.hfRepo ?: ""}",
                            color = dim,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
            item {
                ReviewBlock(e.id, records[e.id])
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
private fun StatCell(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
    }
}

/** The page's device block: the computed verdict, the recorded facts, and
 *  the [ verify speed ] button that turns estimates into measurements. */
@Composable
private fun OnThisPhoneCard(
    e: StoreCatalog.Entry,
    catalog: StoreCatalog.Catalog,
    model: LocalLlm.Model,
    ready: Boolean,
    rec: ModelRecords.Rec?,
    res: PhoneResources.Snapshot?,
) {
    val dim = MaterialTheme.colorScheme.outline
    val cyan = MaterialTheme.colorScheme.primary
    val scope = rememberCoroutineScope()
    var verifyState by remember(e.id) { mutableStateOf<String?>(null) }
    var freeing by remember { mutableStateOf(false) }
    var ramMsg by remember { mutableStateOf<String?>(null) }
    val need = LocalLlm.ramNeeded(model)
    val capacityOk = DeviceProfile.runsOnThisPhone(e, catalog)
    val fitNow = res?.ramFreeBytes?.let { LocalLlm.fit(model, it) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                "on this device",
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                buildString {
                    append("needs ~"); append(PhoneResources.gb(need)); append("G ram")
                    if (!capacityOk) {
                        append(" — over this device's ~")
                        append(PhoneResources.gb(DeviceProfile.capacityBytes(catalog))); append("G budget")
                    } else {
                        res?.ramFreeBytes?.let {
                            when (LocalLlm.fit(model, it)) {
                                LocalLlm.Fit.FITS -> append(" · fits right now")
                                LocalLlm.Fit.TIGHT -> append(" · tight right now")
                                LocalLlm.Fit.SHORT -> append(" · ram busy right now")
                            }
                        }
                    }
                },
                color = if (capacityOk) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                buildString {
                    if (rec?.ran == true) {
                        append("✓ ran on this device"); if (rec.ranGpu) append(" (gpu)")
                    } else append("not run on this device yet")
                    rec?.tokS?.let {
                        append(" · measured ")
                        append(String.format(java.util.Locale.US, "%.1f", it)); append(" tok/s")
                    }
                },
                color = if (rec?.ran == true) cyan else dim,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
            // Ram is busy RIGHT NOW → the one real lever, right here (bridge-
            // armed phones; the system's own safe kill of cached processes).
            // Same manners as the library header: the outcome shows for 3s,
            // and the verb hides while there is nothing left worth killing.
            val offerClean = capacityOk && fitNow != null && fitNow != LocalLlm.Fit.FITS &&
                (freeing || res?.let { ai.eight24family.conch.linux.RamReclaim.worthOffering(it.ramFreeBytes) } == true)
            if (offerClean || ramMsg != null) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (offerClean) {
                        Text(
                            if (freeing) "freeing…" else "[ free ram ]",
                            color = if (freeing) dim else cyan,
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .handCursor()
                                .clickable(enabled = !freeing) {
                                    freeing = true
                                    ramMsg = null
                                    scope.launch {
                                        val msg = when (val o = ai.eight24family.conch.linux.RamReclaim.freeUp()) {
                                            is ai.eight24family.conch.linux.RamReclaim.Outcome.Freed ->
                                                "freed ${PhoneResources.gb(o.freedBytes)}G · ${PhoneResources.gb(o.availAfter)}G free now"
                                            ai.eight24family.conch.linux.RamReclaim.Outcome.BridgeDown ->
                                                "needs the phone bridge — Settings → Phone bridge"
                                        }
                                        ramMsg = msg
                                        freeing = false
                                        kotlinx.coroutines.delay(3_000L)
                                        if (ramMsg == msg) ramMsg = null
                                    }
                                }
                                .padding(vertical = 4.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    ramMsg?.let {
                        Text(
                            it,
                            color = if (it.startsWith("freed")) cyan else dim,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
            if (ready) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        border = androidx.compose.foundation.BorderStroke(1.dp, cyan.copy(alpha = 0.6f)),
                        color = Color.Transparent,
                        modifier = Modifier.handCursor().clickable {
                            if (verifyState != "measuring…") {
                                verifyState = "measuring…"
                                scope.launch {
                                    verifyState = when (val r = ModelRecords.verify(model)) {
                                        is ModelRecords.VerifyResult.Done ->
                                            String.format(java.util.Locale.US, "measured %.1f tok/s", r.tokS)
                                        is ModelRecords.VerifyResult.Refused -> r.why
                                    }
                                }
                            }
                        },
                    ) {
                        Text(
                            "verify speed",
                            color = cyan,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }
                    verifyState?.let {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            it,
                            color = if (it.startsWith("measured")) cyan else dim,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

/** Add-ons — the model's optional packs, installable RIGHT HERE (owner:
 * ). Vision is the one pack today; the row states the fact and carries the
 * one verb. The download itself is [LocalLlm]'s ordinary machinery — same
 * resume, same service, same metered guard. */
@Composable
private fun AddOnsBlock(model: LocalLlm.Model, ready: Boolean) {
    val dim = MaterialTheme.colorScheme.outline
    val revision by LocalLlm.revision.collectAsState()
    val progress by LocalLlm.progress.collectAsState()
    val hasVision = remember(revision, progress.keys) { LocalLlm.hasVision(model) }
    val streaming = progress.containsKey(model.id) && LocalLlm.isReady(model)
    var meteredAskBytes by remember { mutableStateOf<Long?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    MeteredAsk(meteredAskBytes, onDismiss = { meteredAskBytes = null }) {
        meteredAskBytes = null
        LocalLlm.startDownload(model)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            "Add-ons",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "vision — the model can see images",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    when {
                        hasVision -> "installed · ${PhoneResources.gb(model.mmprojBytes)}G"
                        streaming -> "downloading…"
                        ready -> "${PhoneResources.gb(model.mmprojBytes)}G · also fetches itself when a chat first needs it"
                        else -> "${PhoneResources.gb(model.mmprojBytes)}G · available after the model downloads"
                    },
                    color = dim,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.width(10.dp))
            when {
                hasVision -> StorePillButton("Remove") {
                    LocalLlm.mmprojOf(model)?.delete()
                    LocalLlm.revision.value++
                }
                streaming -> {}
                ready -> StorePillButton("Add") {
                    if (NetGuard.isMetered(ctx)) meteredAskBytes = model.mmprojBytes
                    else LocalLlm.startDownload(model)
                }
                else -> {}
            }
        }
    }
}

/**
 * The review block — stars AND words. Local by design: the app never phones
 * home, so today this is the owner's own record; it is also exactly the
 * payload a future opt-in community sync would carry. Other users' reviews
 * need that backend — a store with no server has nothing honest to show
 * yet, and shows nothing instead of fakes. */
@Composable
private fun ReviewBlock(modelId: String, rec: ModelRecords.Rec?) {
    val dim = MaterialTheme.colorScheme.outline
    val accent = MaterialTheme.colorScheme.primary
    var draft by remember(modelId, rec?.reviewText) { mutableStateOf(rec?.reviewText ?: "") }
    val saved = draft == (rec?.reviewText ?: "")
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            "Your review",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            val r = rec?.rating ?: 0
            (1..5).forEach { i ->
                Text(
                    if (i <= r) "★" else "☆",
                    color = if (i <= r) accent else dim,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier
                        .handCursor()
                        .clickable { ModelRecords.rate(modelId, i) }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it.take(2000) },
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                ),
                cursorBrush = SolidColor(accent),
                decorationBox = { inner ->
                    Box(Modifier.padding(12.dp)) {
                        if (draft.isEmpty()) {
                            Text(
                                "how did it run for you? speed, quality, quirks…",
                                color = dim,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 0.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!saved) {
                StorePillButton("Save review") { ModelRecords.review(modelId, draft) }
                Spacer(Modifier.width(10.dp))
            }
            Text(
                if (rec?.reviewText != null && saved) "saved · stays on this device"
                else "stays on this device",
                color = dim,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

// ── shared bits ──

/** The model family's mark: the real drawable where we have one (qwen),
 * a monogram tile elsewhere — honest, and no hand-faked brand art. Shared
 * with the local-models rows so a store model wears the same face
 * everywhere. */
@Composable
internal fun FamilyMark(
    family: String,
    iconRes: Int?,
    lit: Boolean,
    size: Dp = 20.dp,
    brand: android.graphics.Bitmap? = null,
) {
    // The REAL mark first: the publisher's own avatar from its HF org
    // page. Rounded like a Play app icon.
    if (brand != null) {
        Image(
            bitmap = brand.asImageBitmap(),
            contentDescription = null,
            alpha = if (lit) 1f else 0.5f,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(size * 0.23f)),
        )
        return
    }
    val tiled = size >= 36.dp
    // Store sizes get the app-icon TILE (Play's grammar: every app wears a
    // uniform rounded tile, whatever its art). Inline row sizes stay bare —
    // a tile at 20dp is noise.
    if (iconRes != null && !tiled) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            alpha = if (lit) 1f else 0.45f,
            modifier = Modifier.size(size),
        )
        return
    }
    val (letter, color) = when (family) {
        "qwen" -> "Q" to Color(0xFF8B7CF6)
        "gemma" -> "G" to Color(0xFF4285F4)
        "llama" -> "L" to Color(0xFF4A8FE7)
        "liquid" -> "F" to Color(0xFF19C2D8)
        "smol" -> "S" to Color(0xFFFFC83D)
        "phi" -> "P" to Color(0xFF7B68EE)
        "granite" -> "R" to Color(0xFF8A97A8)
        "openai" -> "O" to Color(0xFF9BE29B)
        else -> family.take(1).uppercase() to MaterialTheme.colorScheme.outline
    }
    val c = if (lit) color else color.copy(alpha = 0.5f)
    if (!tiled) {
        Box(
            modifier = Modifier.size(size).border(1.dp, c, RoundedCornerShape(size / 4)),
            contentAlignment = Alignment.Center,
        ) {
            Text(letter, color = c, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
        }
        return
    }
    Box(
        modifier = Modifier
            .size(size)
            .background(c.copy(alpha = 0.10f), RoundedCornerShape(size * 0.23f))
            .border(1.dp, c.copy(alpha = 0.35f), RoundedCornerShape(size * 0.23f)),
        contentAlignment = Alignment.Center,
    ) {
        if (iconRes != null) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                alpha = if (lit) 1f else 0.45f,
                modifier = Modifier.size(size * 0.62f),
            )
        } else {
            Text(
                letter,
                color = c,
                style = if (size >= 56.dp) MaterialTheme.typography.titleLarge
                else MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun GpuRuntimeFooter(profile: DeviceProfile.Profile, offloadSeen: Boolean) {
    val dim = MaterialTheme.colorScheme.outline
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(
            "gpu runtime",
            color = MaterialTheme.colorScheme.tertiary,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            if (profile.gpuFront) {
                "opencl backend ✓ · vendor driver ✓ · offload " +
                    (if (offloadSeen) "seen on this device ✓" else "not yet observed here")
            } else {
                "opencl backend in app · no vendor driver on this device — cpu only"
            },
            color = dim,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
        )
    }
}
