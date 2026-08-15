package ai.eight24family.conch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.eight24family.conch.ui.terminal.VtEmulator
import ai.eight24family.conch.ui.terminal.VtScreen
import ai.eight24family.conch.ui.viewmodel.TerminalViewModel
import androidx.compose.foundation.text.KeyboardOptions

/**
 * Full-VT terminal: renders the [VtScreen] cell grid (per-cell SGR colour +
 * block cursor) and feeds raw keystrokes to the shell — hardware keys via
 * onPreviewKeyEvent (DeX / Bluetooth) and soft-keyboard input via the
 * value-delta path. The viewport size is measured from the available space
 * and pushed to the PTY (SIGWINCH), so vim/htop/tmux size correctly.
 */
/**
 * Cell metrics in **dp**, deliberately not sp.
 *
 * A terminal is a grid, not prose: with `sp` the system font-size setting
 * multiplies it, and on a phone set to large text the grid collapsed to about
 * twenty columns — every line wrapped, and the CLI's own layout came apart.
 * dp keeps the cell tied to the screen's density, which is what decides how
 * many columns actually fit. Accessibility scaling belongs to the CHAT, where
 * text reflows.
 */
/** Survives leaving the screen; a terminal you have to re-size every time is
 *  worse than one that is simply too small. */
private object TerminalPrefs { var scale: Float = 1f }

private val TERM_FONT_DP = 12.dp
private val TERM_LINE_DP = 15.dp

/**
 * The app's own monospace face, not the system's.
 *
 * `FontFamily.Monospace` on this phone has no box-drawing or status glyphs, so
 * the CLI's own footer rendered as tofu (`⊠ ⊠ bypass`). JetBrains Mono ships in
 * the APK already and covers them.
 */
private val TERM_FAMILY = FontFamily(
    androidx.compose.ui.text.font.Font(ai.eight24family.conch.R.font.jetbrains_mono),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    serverName: String,
    onBack: () -> Unit,
    vm: TerminalViewModel = viewModel(),
) {
    val screen by vm.screen.collectAsState()
    val connected by vm.connected.collectAsState()

    val focus = remember { FocusRequester() }
    var imeField by remember { mutableStateOf(TextFieldValue("")) }

    // Pull focus on entry so a hardware keyboard (DeX) types immediately.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val termDensity = LocalDensity.current
    // PINCH TO SIZE IT. Neither the system font scale nor the display-size
    // setting can pick a column count that suits a terminal: 44 columns is
    // comfortable for some output and useless for a TUI that wants 80. The
    // user's own pinch is the only honest input, and it survives the session.
    var termScale by rememberSaveable { mutableFloatStateOf(TerminalPrefs.scale) }
    val termFontSp = with(termDensity) { (TERM_FONT_DP * termScale).toSp() }
    val termLineSp = with(termDensity) { (TERM_LINE_DP * termScale).toSp() }
    val termStyle = remember(termFontSp, termLineSp) {
        TextStyle(fontFamily = TERM_FAMILY, fontSize = termFontSp, lineHeight = termLineSp)
    }
    val defFg = MaterialTheme.colorScheme.onSurface
    val defBg = MaterialTheme.colorScheme.background
    val connectedGreen = Color(0xFF21D07A)

    fun sendKey(ev: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (ev.type != KeyEventType.KeyDown) return false
        // Ctrl-letter → control byte (Ctrl-A = 0x01 … Ctrl-Z = 0x1A).
        if (ev.isCtrlPressed) {
            val code = ev.nativeKeyEvent.keyCode
            if (code in android.view.KeyEvent.KEYCODE_A..android.view.KeyEvent.KEYCODE_Z) {
                vm.sendBytes(byteArrayOf((code - android.view.KeyEvent.KEYCODE_A + 1).toByte()))
                return true
            }
        }
        when (ev.key) {
            Key.Enter, Key.NumPadEnter -> { vm.send("\r"); return true }
            Key.Backspace -> { vm.sendBytes(byteArrayOf(0x7f)); return true }
            Key.Delete -> { vm.send("\u001B[3~"); return true }
            Key.Tab -> { vm.send("\t"); return true }
            Key.Escape -> { vm.send("\u001B"); return true }
            Key.DirectionUp -> { vm.sendArrow('A'); return true }
            Key.DirectionDown -> { vm.sendArrow('B'); return true }
            Key.DirectionRight -> { vm.sendArrow('C'); return true }
            Key.DirectionLeft -> { vm.sendArrow('D'); return true }
            Key.MoveHome -> { vm.send("\u001B[H"); return true }
            Key.MoveEnd -> { vm.send("\u001B[F"); return true }
            Key.PageUp -> { vm.send("\u001B[5~"); return true }
            Key.PageDown -> { vm.send("\u001B[6~"); return true }
            else -> {}
        }
        // Printable char (honours shift via the IME-reported codepoint).
        val cp = ev.utf16CodePoint
        if (cp != 0 && !ev.isCtrlPressed && !ev.nativeKeyEvent.isAltPressed && cp >= 32) {
            vm.send(String(Character.toChars(cp)))
            return true
        }
        return false
    }

    Scaffold(
        containerColor = defBg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        serverName.ifBlank { "terminal" },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
                    }
                },
                actions = {
                    // Live-connection dot — green ● when the shell is up.
                    Text(
                        text = if (connected) "●" else "○",
                        color = if (connected) connectedGreen else MaterialTheme.colorScheme.outline,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 14.dp),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = defBg,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                    navigationIconContentColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .imePadding()
                .navigationBarsPadding(),
        ) {
            // ── VT viewport ──
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(defBg),
            ) {
                val density = LocalDensity.current
                val measurer = rememberTextMeasurer()
                // Char + line metrics from the actual font.
                val charWpx = remember(termStyle, density) {
                    measurer.measure(AnnotatedString("M".repeat(40)), termStyle).size.width / 40f
                }
                val lineHpx = remember(termStyle, density, termScale) {
                    with(density) { (TERM_LINE_DP * termScale).toPx() }
                }
                val padPx = with(density) { 6.dp.toPx() }

                val cols = (((constraints.maxWidth - padPx) / charWpx).toInt()).coerceIn(20, 400)
                val rows = ((constraints.maxHeight / lineHpx).toInt()).coerceIn(4, 200)

                LaunchedEffect(cols, rows) { vm.resize(cols, rows) }

                // SCROLLBACK. The live screen is the last `rows` lines; above it
                // sits everything that scrolled off. Sticks to the bottom while
                // the user is at the bottom, and stays put the moment they scroll
                // up — output arriving must never yank the page out from under
                // someone reading it.
                val vScroll = rememberScrollState()
                var pinned by remember { mutableStateOf(true) }
                LaunchedEffect(vScroll.value, vScroll.maxValue) {
                    pinned = vScroll.value >= vScroll.maxValue - lineHpx.toInt()
                }
                LaunchedEffect(screen.version) {
                    if (pinned) vScroll.scrollTo(vScroll.maxValue)
                }
                Text(
                    text = renderScreen(screen, defFg, defBg),
                    style = termStyle,
                    softWrap = false,
                    maxLines = screen.history.size + screen.rows,
                    color = defFg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(vScroll)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, _, zoom, _ ->
                                if (zoom != 1f) {
                                    termScale = (termScale * zoom).coerceIn(0.5f, 2.5f)
                                    TerminalPrefs.scale = termScale
                                }
                            }
                        }
                        .padding(horizontal = 3.dp),
                )

                // Transparent input overlay: captures focus + keystrokes.
                BasicTextField(
                    value = imeField,
                    onValueChange = { nv ->
                        val t = nv.text
                        if (t.isNotEmpty()) {
                            vm.send(t.replace("\n", "\r"))
                            imeField = TextFieldValue("")
                        } else {
                            imeField = nv
                        }
                    },
                    textStyle = TextStyle(color = Color.Transparent, fontSize = termFontSp),
                    cursorBrush = SolidColor(Color.Transparent),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.None,
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                    ),
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focus)
                        .onPreviewKeyEvent { sendKey(it) },
                    decorationBox = { inner -> inner() },
                )
            }

            // ── thin separator (no boxed field — the terminal IS the input) ──
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

            // ── control-key bar ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(defBg)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KeyChip("esc") { vm.send("\u001B"); focus.requestFocus() }
                KeyChip("tab") { vm.send("\t"); focus.requestFocus() }
                KeyChip("^C") { vm.sendBytes(byteArrayOf(0x03)); focus.requestFocus() }
                KeyChip("^D") { vm.sendBytes(byteArrayOf(0x04)); focus.requestFocus() }
                IconButton(onClick = { vm.sendArrow('A'); focus.requestFocus() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "up", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { vm.sendArrow('B'); focus.requestFocus() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "down", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { vm.sendArrow('D'); focus.requestFocus() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "left", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { vm.sendArrow('C'); focus.requestFocus() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "right", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun KeyChip(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
    }
}

/** Build the whole screen as one AnnotatedString — runs of identical style
 *  are coalesced into spans; the cursor cell is rendered as an inverted block. */
/** Row separator as a char — a literal newline in source gets mangled. */
private val NL = 10.toChar()

private fun renderScreen(screen: VtScreen, defFg: Color, defBg: Color): AnnotatedString =
    buildAnnotatedString {
        // Scrollback first, live screen last — ONE continuous block, so what
        // scrolled away and what is on screen can never disagree about their
        // order. The cursor never lives up here, hence row = -1.
        for (row in screen.history) {
            appendRow(row, -1, screen, defFg, defBg)
            append(NL)
        }
        for (y in 0 until screen.rows) {
            val row = screen.lines.getOrNull(y) ?: continue
            appendRow(row, y, screen, defFg, defBg)
            if (y < screen.rows - 1) append(NL)
        }
    }

/** One row as runs of identical style; the cursor cell renders inverted. */
private fun AnnotatedString.Builder.appendRow(
    row: ai.eight24family.conch.ui.terminal.VtRow,
    y: Int,
    screen: VtScreen,
    defFg: Color,
    defBg: Color,
) {
    val n = row.ch.size
    var x = 0
    while (x < n) {
        val cursorHere = screen.cursorVisible && y == screen.cursorRow && x == screen.cursorCol
        val fgI = row.fg[x]; val bgI = row.bg[x]; val fl = row.fl[x]
        var x2 = x + 1
        if (!cursorHere) {
            while (x2 < n &&
                !(screen.cursorVisible && y == screen.cursorRow && x2 == screen.cursorCol) &&
                row.fg[x2] == fgI && row.bg[x2] == bgI && row.fl[x2] == fl
            ) x2++
        }
        withStyle(cellStyle(fgI, bgI, fl, cursorHere, defFg, defBg)) {
            append(String(row.ch, x, x2 - x))
        }
        x = x2
    }
}

private fun rgb(v: Int): Color = Color((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)

private fun cellStyle(
    fgI: Int, bgI: Int, fl: Int, cursor: Boolean, defFg: Color, defBg: Color,
): SpanStyle {
    var fg = if (fgI == VtEmulator.DEFAULT_COLOR) defFg else rgb(fgI)
    var bg = if (bgI == VtEmulator.DEFAULT_COLOR) Color.Unspecified else rgb(bgI)

    if (fl and VtEmulator.Flags.REVERSE != 0) {
        val realBg = if (bg == Color.Unspecified) defBg else bg
        val realFg = fg
        fg = realBg; bg = realFg
    }
    if (fl and VtEmulator.Flags.DIM != 0) fg = fg.copy(alpha = 0.6f)
    if (fl and VtEmulator.Flags.INVISIBLE != 0) fg = if (bg == Color.Unspecified) defBg else bg
    if (cursor) {
        // Inverted block cursor.
        val keepFg = if (fg == Color.Unspecified) defFg else fg
        bg = keepFg
        fg = defBg
    }
    return SpanStyle(
        color = fg,
        background = bg,
        fontWeight = if (fl and VtEmulator.Flags.BOLD != 0) FontWeight.Bold else null,
        fontStyle = if (fl and VtEmulator.Flags.ITALIC != 0) FontStyle.Italic else null,
        textDecoration = if (fl and VtEmulator.Flags.UNDERLINE != 0) TextDecoration.Underline else null,
    )
}
