package ai.eight24family.conch.ui.screens

import ai.eight24family.conch.linux.LocalLlm
import ai.eight24family.conch.linux.LocalLlmEngine
import ai.eight24family.conch.linux.PhoneResources
import ai.eight24family.conch.linux.RamReclaim
import ai.eight24family.conch.linux.store.DeviceProfile
import ai.eight24family.conch.linux.store.ModelRecords
import ai.eight24family.conch.linux.store.ShareMeasurements
import ai.eight24family.conch.linux.store.StoreCatalog
import ai.eight24family.conch.ui.window.handCursor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone as a GRAPHICAL, EXPANDABLE spec sheet — the one device card both
 * the store and the library wear.
 *
 * Collapsed: four rows, each a labeled bar or one short value — ram, cpu,
 * gpu, disk. Tap a row to expand its details; only one open at a time. Every
 * number is read from the device (DeviceProfile / PhoneResources) and every
 * bar means the same thing — filled is used, the tail is what a model can
 * take.
 *
 * The GPU-driver detail is a LINK-OUT, never an install: local inference runs
 * on the vendor OpenCL driver shown here, custom Adreno drivers (Turnip/Mesa)
 * are Vulkan for emulators, and Play forbids an app downloading executable
 * code — so the row states the driver and points at where drivers live and at
 * Android's own picker, and claims nothing about inference speed.
 */
@Composable
internal fun PhoneSpecSheet(
    modifier: Modifier = Modifier,
) {
    val catalog by StoreCatalog.catalog.collectAsState()
    val engine by LocalLlmEngine.state.collectAsState()
    val profile = remember { DeviceProfile.read() }
    val gpu = remember { DeviceProfile.gpu() }
    // Where inference ACTUALLY runs right now — the live meter follows it,
    // instead of always showing CPU load that is near-idle under GPU
    // offload. GPU busy% is SELinux-closed to the app (measured), so the GPU
    // side shows the STATE "serving", not a fabricated percent.
    val up = engine as? LocalLlmEngine.State.Up
    val servingGpu = up?.gpu == true
    val servingCpu = up != null && !up.gpu
    val servingLabel = up?.let { LocalLlm.byId(it.modelId)?.label ?: it.modelId }
    var res by remember { mutableStateOf(PhoneResources.read()) }
    var cpuLoad by remember { mutableStateOf<Float?>(null) }
    var temp by remember { mutableStateOf<Pair<Float, String>?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var freeing by remember { mutableStateOf(false) }
    var ramMsg by remember { mutableStateOf<String?>(null) }
    // Collapsed by default: just the phone model + free ram. Tap the card to
    // reveal cpu/gpu/disk.
    var sheetExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (true) {
            val r = withContext(Dispatchers.IO) {
                Triple(PhoneResources.read(), DeviceProfile.cpuClockLoad(), DeviceProfile.temperature())
            }
            res = r.first; cpuLoad = r.second; temp = r.third
            delay(3_000L)
        }
    }
    // The engine coming up or (more to the point) going away hands ram back —
    // refresh the gauge at once instead of waiting for the 3 s tick, so a stop is
    // SEEN to free ram.
    LaunchedEffect(engine) {
        delay(700) // let the kernel account the freed mmap pages first
        res = withContext(Dispatchers.IO) { PhoneResources.read() }
    }

    val dim = MaterialTheme.colorScheme.outline
    val fg = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val (bw, bwSource) = DeviceProfile.bwInfo(catalog, profile)
    // Recomputed when a measurement lands (records flow), so the verb appears
    // the moment this phone has something to contribute.
    val records by ModelRecords.flow.collectAsState()
    val shareable = remember(records, catalog) { ShareMeasurements.samples(records, catalog) }
    var showShare by remember { mutableStateOf(false) }
    if (showShare) {
        val ctx = LocalContext.current
        val payload = remember(shareable, bw, profile) {
            ShareMeasurements.render(
                p = profile,
                gpu = DeviceProfile.gpu(),
                samples = shareable,
                bwGbps = bw,
                appVersion = ai.eight24family.conch.BuildConfig.VERSION_NAME,
            )
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showShare = false },
            title = { Text("Share these measurements") },
            text = {
                Column {
                    Text(
                        "This is everything that would leave your phone. Nothing " +
                            "is sent by the app — you pick where it goes.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        payload,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showShare = false
                    ShareMeasurements.share(ctx, payload)
                }) { Text("Share") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showShare = false }) { Text("Cancel") }
            },
        )
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            // Header — the whole collapsed card. Left: model + free ram, the
            // two facts that matter at a glance. Right: the expand chevron.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().handCursor().clickable { sheetExpanded = !sheetExpanded },
            ) {
                Text(
                    "this device",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "${profile.device} · ${PhoneResources.gb(res.ramFreeBytes)}G free",
                    color = fg,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (sheetExpanded) "▲" else "▾",
                    color = dim,
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            if (!sheetExpanded) return@Column
            Spacer(Modifier.height(8.dp))

            // ── RAM ──
            val ramUsed = (profile.ramTotalBytes - res.ramFreeBytes).coerceAtLeast(0L)
            GaugeRow(
                label = "ram",
                fraction = if (profile.ramTotalBytes > 0) ramUsed.toFloat() / profile.ramTotalBytes else 0f,
                value = "${PhoneResources.gb(res.ramFreeBytes)}G free",
                expanded = expanded == "ram",
                onTap = { expanded = if (expanded == "ram") null else "ram" },
                trailing = {
                    if (freeing || RamReclaim.worthOffering(res.ramFreeBytes)) {
                        Text(
                            if (freeing) "freeing…" else "[ free ram ]",
                            color = if (freeing) dim else accent,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .handCursor()
                                .clickable(enabled = !freeing) {
                                    freeing = true; ramMsg = null
                                    scope.launch {
                                        val msg = when (val o = RamReclaim.freeUp()) {
                                            is RamReclaim.Outcome.Freed ->
                                                "freed ${PhoneResources.gb(o.freedBytes)}G · ${PhoneResources.gb(o.availAfter)}G free now"
                                            RamReclaim.Outcome.BridgeDown -> {
                                                ai.eight24family.conch.adb.PhoneBridgeSetup.ask(
                                                    "Freeing memory runs on the phone itself.",
                                                )
                                                "this phone's shell is off"
                                            }
                                        }
                                        ramMsg = msg
                                        res = withContext(Dispatchers.IO) { PhoneResources.read() }
                                        freeing = false
                                        delay(3_000L)
                                        if (ramMsg == msg) ramMsg = null
                                    }
                                }
                                .padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                        )
                    }
                },
            ) {
                DetailLine("in use ${PhoneResources.gb(ramUsed)}G · free ${PhoneResources.gb(res.ramFreeBytes)}G · total ${PhoneResources.gb(profile.ramTotalBytes)}G")
                DetailLine("a model needs about its file size plus ~1.8G runtime; the free tail is what it can claim")
                ramMsg?.let { DetailLine(it, if (it.startsWith("freed")) accent else dim) }
            }

            // ── CPU ── the live load bar shows ONLY when the CPU is the
            // compute path; under GPU offload the CPU is near-idle and its
            // load is not the story. At rest: capabilities, no bar.
            GaugeRow(
                label = "cpu",
                fraction = cpuLoad ?: 0f,
                fractionKnown = servingCpu && cpuLoad != null,
                value = buildString {
                    append("${profile.cores} cores")
                    if (servingCpu) append(" · serving ●")
                    temp?.let { append(" · ${it.first.toInt()}°C") }
                },
                expanded = expanded == "cpu",
                onTap = { expanded = if (expanded == "cpu") null else "cpu" },
            ) {
                DetailLine(
                    "${profile.cores} cores" + when {
                        servingCpu && cpuLoad != null -> " · serving ${servingLabel ?: ""} at ${(cpuLoad!! * 100).toInt()}% clock"
                        servingGpu -> " · idle — ${servingLabel ?: "the model"} is on the GPU"
                        else -> ""
                    },
                )
                temp?.let {
                    DetailLine(
                        "temperature ${it.first.toInt()}°C" +
                            if (it.second == "batt") " (battery sensor — CPU zones are closed to apps at rest)"
                            else " (CPU zone)",
                    )
                }
                val flags = buildList {
                    if (profile.fp16) add("fp16 — half-precision math, the core inference speedup")
                    if (profile.dotprod) add("dotprod — int8 dot product, faster quantized matmul")
                    if (profile.i8mm) add("i8mm — int8 matrix multiply, faster prefill")
                    if (profile.sve) add("sve — scalable vectors")
                }
                if (flags.isEmpty()) DetailLine("no inference ISA extensions detected")
                else flags.forEach { DetailLine(it) }
            }

            // ── GPU ──
            GaugeRow(
                label = "gpu",
                fraction = 0f,
                fractionKnown = false,
                value = buildString {
                    append(gpu.model ?: "unknown")
                    // Serving on GPU is STATE, not a fabricated percent — GPU
                    // busy% is SELinux-closed to the app (measured).
                    if (servingGpu) append(" · serving ●") else append(" · opencl ${if (profile.gpuFront) "✓" else "—"}")
                },
                expanded = expanded == "gpu",
                onTap = { expanded = if (expanded == "gpu") null else "gpu" },
            ) {
                if (servingGpu) DetailLine("serving ${servingLabel ?: "a model"} on the GPU now", accent)
                DetailLine(
                    "driver " + (gpu.driverVersion?.let {
                        it + if (gpu.driverUpdatable) " · updatable (via Play)" else ""
                    } ?: "ships in the system image (no version API)"),
                )
                DetailLine(
                    "opencl backend " + if (profile.gpuFront) "present — layers can offload to the GPU"
                    else "no vendor OpenCL on this device — inference runs on CPU",
                )
                Spacer(Modifier.height(4.dp))
                DetailLine("Local models use the OpenCL driver above. Custom Adreno drivers (Turnip/Mesa) are Vulkan — for emulators and games, not this app's inference.", dim)
                DetailLine("Conch can't install drivers (Play forbids downloading executable code) — it links you out.", dim)
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    catalog.driversUrl?.let { url ->
                        LinkAction("driver downloads ↗") {
                            ai.eight24family.conch.util.SilentlyTry.fired("Conch-Store", "open drivers url") {
                                ctx.startActivity(
                                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                    }
                    LinkAction("driver settings ↗") {
                        ai.eight24family.conch.util.SilentlyTry.fired("Conch-Store", "open dev settings") {
                            ctx.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }
            }

            // ── DISK ──
            val diskUsed = (res.diskTotalBytes - res.diskFreeBytes).coerceAtLeast(0L)
            GaugeRow(
                label = "disk",
                fraction = if (res.diskTotalBytes > 0) diskUsed.toFloat() / res.diskTotalBytes else 0f,
                fractionKnown = res.diskTotalBytes > 0,
                value = "${PhoneResources.gb(res.diskFreeBytes)}G free",
                expanded = expanded == "disk",
                onTap = { expanded = if (expanded == "disk") null else "disk" },
            ) {
                DetailLine("free ${PhoneResources.gb(res.diskFreeBytes)}G of ${PhoneResources.gb(res.diskTotalBytes)}G")
                val onDisk = LocalLlm.CATALOG.filter { LocalLlm.isReady(it) }
                if (onDisk.isEmpty()) DetailLine("no models stored yet")
                else DetailLine("models here: " + onDisk.joinToString(", ") { "${it.label} ${PhoneResources.gb(it.bytes)}G" })
                // ⛔ SAY WHERE THE NUMBER CAME FROM. It prices every shelf
                // row, and "measured here" / "measured on 12 phones like this"
                // / "SoC-class guess" are worth wildly different amounts of
                // trust. The store must not launder a bucket into a fact.
                DetailLine(
                    "bandwidth ~${String.format(java.util.Locale.US, "%.0f", bw)} GB/s " +
                        when (val src = bwSource) {
                            DeviceProfile.BwSource.Own -> "measured on this device"
                            is DeviceProfile.BwSource.Community ->
                                "measured on ${src.n} phone${if (src.n == 1) "" else "s"} like this one"
                            DeviceProfile.BwSource.SocClass -> "SoC-class estimate"
                            DeviceProfile.BwSource.Default -> "generic estimate — this chip is unknown to us"
                        } + " — sets the speed guesses",
                    dim,
                )
                // Contributing is a deliberate act, and the owner reads the
                // payload first. The app never posts anything itself.
                if (shareable.isNotEmpty()) {
                    LinkAction("share these measurements") { showShare = true }
                }
            }
        }
    }
}

/** A labeled row: fixed dim label gutter, a bar (when the fraction is known),
 *  a short value, a chevron; tap toggles the detail block below it. */
@Composable
private fun GaugeRow(
    label: String,
    fraction: Float,
    fractionKnown: Boolean = true,
    value: String,
    expanded: Boolean,
    onTap: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    detail: @Composable () -> Unit,
) {
    val dim = MaterialTheme.colorScheme.outline
    val fg = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().handCursor().clickable { onTap() }.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                color = dim,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(34.dp),
            )
            if (fractionKnown) {
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.width(84.dp),
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                value,
                color = fg,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
            Spacer(Modifier.width(6.dp))
            Text(
                if (expanded) "▲" else "▾",
                color = dim,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(start = 34.dp, top = 4.dp, bottom = 2.dp)) { detail() }
        }
    }
}

@Composable
private fun DetailLine(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(vertical = 1.dp),
    )
}

@Composable
private fun LinkAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.handCursor().clickable { onClick() }.padding(vertical = 4.dp),
    )
}
