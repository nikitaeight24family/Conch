package ai.eight24family.conch.ui.terminal

/**
 * A real VT100/VT220/xterm-subset screen emulator: a fixed `rows × cols`
 * cell grid with a cursor, scroll region, SGR colour state, and an
 * alternate screen buffer. This is what makes full-screen TUIs (vim, htop,
 * tmux, less, git's pager) render correctly — they drive the screen by
 * absolute cursor addressing + erase + scroll-region ops, which the old
 * line-buffer (which only knew `\n\r\b\t` and stripped escapes) could not
 * represent.
 *
 * Scope: the common 90% of xterm control functions — C0 controls, CSI
 * cursor moves / erase / insert-delete / scroll / SGR (16/256/truecolour)
 * / DECSTBM scroll region / DEC private modes (cursor visibility, alt
 * screen, autowrap, cursor-key mode, bracketed paste), ESC index / reverse
 * index / save-restore cursor / RIS. Not implemented (rare): origin mode,
 * double-width lines, charset designation beyond consume-and-ignore, mouse
 * reporting, sixel. These degrade gracefully (ignored) rather than corrupt.
 *
 * Thread-safety: [feed], [resize] and [snapshot] all synchronize on this
 * instance. The SSH read loop calls feed/resize off the main thread; the
 * UI calls snapshot on the main thread; the lock keeps the grid consistent.
 */
class VtEmulator(cols: Int, rows: Int) {

    // ─────────────────────────── style ───────────────────────────
    object Flags {
        const val BOLD = 1
        const val DIM = 1 shl 1
        const val ITALIC = 1 shl 2
        const val UNDERLINE = 1 shl 3
        const val REVERSE = 1 shl 4
        const val INVISIBLE = 1 shl 5
        const val STRIKE = 1 shl 6
    }

    /** -1 = "use the theme default" (fg = onSurface, bg = transparent);
     *  otherwise a 0xRRGGBB value already resolved from the palette. */
    companion object {
        const val DEFAULT_COLOR = -1

        /** Lines of history kept above the screen. ~8 screenfuls on a phone,
         *  bounded so a runaway `yes` can't eat the heap. */
        const val MAX_SCROLLBACK = 2000

        private val ANSI16 = intArrayOf(
            0x000000, 0xCD0000, 0x00CD00, 0xCDCD00, 0x1E90FF, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
            0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00, 0x5C9FFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF,
        )

        /** xterm 256-colour palette → 0xRRGGBB. */
        fun palette(i: Int): Int {
            if (i < 16) return ANSI16[i.coerceIn(0, 15)]
            if (i in 16..231) {
                val n = i - 16
                val r = n / 36; val g = (n / 6) % 6; val b = n % 6
                fun ch(v: Int) = if (v == 0) 0 else 55 + 40 * v
                return (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
            }
            // 232..255 grayscale ramp
            val c = (8 + 10 * (i - 232)).coerceIn(0, 255)
            return (c shl 16) or (c shl 8) or c
        }
    }

    var cols = cols.coerceAtLeast(1); private set
    var rows = rows.coerceAtLeast(1); private set

    // Parallel cell arrays for the active screen.
    private lateinit var ch: Array<CharArray>
    private lateinit var fg: Array<IntArray>
    private lateinit var bg: Array<IntArray>
    private lateinit var fl: Array<IntArray>

    // Saved primary screen while the alternate screen is active.
    private var saved: Screen? = null

    private var curRow = 0
    private var curCol = 0
    private var savedCurRow = 0
    private var savedCurCol = 0
    private var pendingWrap = false

    private var scrollTop = 0
    private var scrollBottom = rows - 1

    // current SGR
    private var sgrFg = DEFAULT_COLOR
    private var sgrBg = DEFAULT_COLOR
    private var sgrFlags = 0

    // modes
    private var autowrap = true
    var cursorVisible = true; private set
    var applicationCursorKeys = false; private set
    var bracketedPaste = false; private set
    private var altScreen = false

    /**
     * SCROLLBACK — lines that left the top of the screen.
     *
     * A terminal without it can only ever show the last screenful, which is
     * what made ours useless the moment anything printed more than a page.
     * Only the PRIMARY screen feeds it: a full-screen app on the alt screen
     * owns its viewport and scrolling ITS repaints would be nonsense — that
     * is why real terminals don't either. Bounded, so a `yes` loop can't
     * eat the heap.
     */
    private val scrollback = ArrayDeque<VtRow>()

    private var version = 0L

    // ─────────────────────────── parser state ───────────────────────────
    private enum class S { GROUND, ESC, CSI, OSC, OSC_ESC, CHARSET }
    private var state = S.GROUND
    private val params = StringBuilder()
    private var priv = 0.toChar() // '?' '<' '=' '>' or 0

    /** Callback for replies the host expects on stdin (e.g. cursor position
     *  report). Wired by the session to write to the shell. */
    var respond: ((String) -> Unit)? = null

    init { allocate(this.cols, this.rows, clearAll = true) }

    private class Screen(
        val ch: Array<CharArray>, val fg: Array<IntArray>,
        val bg: Array<IntArray>, val fl: Array<IntArray>,
        val curRow: Int, val curCol: Int,
    )

    private fun allocate(c: Int, r: Int, clearAll: Boolean) {
        val nch = Array(r) { CharArray(c) { ' ' } }
        val nfg = Array(r) { IntArray(c) { DEFAULT_COLOR } }
        val nbg = Array(r) { IntArray(c) { DEFAULT_COLOR } }
        val nfl = Array(r) { IntArray(c) }
        if (!clearAll && ::ch.isInitialized) {
            val rr = minOf(r, ch.size)
            for (y in 0 until rr) {
                val cc = minOf(c, ch[y].size)
                System.arraycopy(ch[y], 0, nch[y], 0, cc)
                System.arraycopy(fg[y], 0, nfg[y], 0, cc)
                System.arraycopy(bg[y], 0, nbg[y], 0, cc)
                System.arraycopy(fl[y], 0, nfl[y], 0, cc)
            }
        }
        ch = nch; fg = nfg; bg = nbg; fl = nfl
    }

    @Synchronized
    fun resize(newCols: Int, newRows: Int) {
        val c = newCols.coerceAtLeast(1)
        val r = newRows.coerceAtLeast(1)
        if (c == cols && r == rows) return
        cols = c; rows = r
        allocate(c, r, clearAll = false)
        saved?.let {
            // Keep the stashed primary screen consistent enough; simplest is
            // to drop it so the next alt-exit just clears — but losing the
            // shell's scrollback on resize is jarring, so reallocate-copy it.
            saved = copyResize(it, c, r)
        }
        curRow = curRow.coerceIn(0, r - 1)
        curCol = curCol.coerceIn(0, c - 1)
        scrollTop = 0
        scrollBottom = r - 1
        pendingWrap = false
        version++
    }

    private fun copyResize(s: Screen, c: Int, r: Int): Screen {
        val nch = Array(r) { CharArray(c) { ' ' } }
        val nfg = Array(r) { IntArray(c) { DEFAULT_COLOR } }
        val nbg = Array(r) { IntArray(c) { DEFAULT_COLOR } }
        val nfl = Array(r) { IntArray(c) }
        val rr = minOf(r, s.ch.size)
        for (y in 0 until rr) {
            val cc = minOf(c, s.ch[y].size)
            System.arraycopy(s.ch[y], 0, nch[y], 0, cc)
            System.arraycopy(s.fg[y], 0, nfg[y], 0, cc)
            System.arraycopy(s.bg[y], 0, nbg[y], 0, cc)
            System.arraycopy(s.fl[y], 0, nfl[y], 0, cc)
        }
        return Screen(nch, nfg, nbg, nfl, s.curRow.coerceIn(0, r - 1), s.curCol.coerceIn(0, c - 1))
    }

    // ─────────────────────────── feed ───────────────────────────
    @Synchronized
    fun feed(text: CharSequence) {
        for (c in text) {
            when (state) {
                S.GROUND -> ground(c)
                S.ESC -> esc(c)
                S.CSI -> csi(c)
                S.OSC -> osc(c)
                S.OSC_ESC -> { if (c == '\\') state = S.GROUND else { state = S.ESC; esc(c) } }
                S.CHARSET -> state = S.GROUND // consume the designator byte
            }
        }
        version++
    }

    private fun ground(c: Char) {
        when (c) {
            '\u001B' -> { state = S.ESC }
            '\u0007' -> {} // BEL
            '\b' -> { if (curCol > 0) curCol--; pendingWrap = false }
            '\t' -> { val n = ((curCol / 8) + 1) * 8; curCol = n.coerceAtMost(cols - 1) }
            '\n', '\u000B', '\u000C' -> lineFeed()
            '\r' -> { curCol = 0; pendingWrap = false }
            else -> if (c.code >= 32) putChar(c)
        }
    }

    private fun putChar(c: Char) {
        if (pendingWrap) { curCol = 0; lineFeed(); pendingWrap = false }
        ch[curRow][curCol] = c
        fg[curRow][curCol] = sgrFg
        bg[curRow][curCol] = sgrBg
        fl[curRow][curCol] = sgrFlags
        if (curCol >= cols - 1) {
            if (autowrap) pendingWrap = true
        } else curCol++
    }

    private fun lineFeed() {
        if (curRow == scrollBottom) scrollUp(1) else if (curRow < rows - 1) curRow++
    }

    private fun esc(c: Char) {
        when (c) {
            '[' -> { params.setLength(0); priv = 0.toChar(); state = S.CSI }
            ']' -> { state = S.OSC }
            '(', ')', '*', '+' -> state = S.CHARSET
            '7' -> { savedCurRow = curRow; savedCurCol = curCol; state = S.GROUND }
            '8' -> { curRow = savedCurRow.coerceIn(0, rows - 1); curCol = savedCurCol.coerceIn(0, cols - 1); state = S.GROUND }
            'D' -> { lineFeed(); state = S.GROUND }                 // IND
            'M' -> { reverseIndex(); state = S.GROUND }             // RI
            'E' -> { curCol = 0; lineFeed(); state = S.GROUND }     // NEL
            'c' -> { reset(); state = S.GROUND }                    // RIS
            '=', '>' -> state = S.GROUND                            // keypad mode (ignored)
            else -> state = S.GROUND
        }
    }

    private fun reverseIndex() {
        if (curRow == scrollTop) scrollDown(1) else if (curRow > 0) curRow--
    }

    private fun osc(c: Char) {
        when (c) {
            '\u0007' -> state = S.GROUND       // BEL terminates
            '\u001B' -> state = S.OSC_ESC      // maybe ST
            else -> {}                          // ignore title/clipboard payloads
        }
    }

    // ─────────────────────────── CSI ───────────────────────────
    private fun csi(c: Char) {
        when {
            (c == '?' || c == '<' || c == '=' || c == '>') && params.isEmpty() && priv == 0.toChar() -> priv = c
            c in '0'..'9' || c == ';' || c == ':' -> params.append(c)
            c in ' '..'/' -> {} // intermediate — ignored
            c in '@'..'~' -> { dispatchCsi(c); state = S.GROUND }
            else -> state = S.GROUND
        }
    }

    private fun args(): IntArray {
        if (params.isEmpty()) return IntArray(0)
        return params.split(';').map { part ->
            // sub-params (colon) — take the first int only
            val head = part.substringBefore(':')
            head.toIntOrNull() ?: 0
        }.toIntArray()
    }

    private fun arg(a: IntArray, i: Int, def: Int): Int =
        if (i < a.size && a[i] != 0) a[i] else def

    private fun dispatchCsi(f: Char) {
        val a = args()
        if (priv == '?') { decPrivate(f, a); return }
        when (f) {
            'A' -> { curRow = (curRow - arg(a, 0, 1)).coerceAtLeast(0); pendingWrap = false }
            'B', 'e' -> { curRow = (curRow + arg(a, 0, 1)).coerceAtMost(rows - 1); pendingWrap = false }
            'C', 'a' -> { curCol = (curCol + arg(a, 0, 1)).coerceAtMost(cols - 1); pendingWrap = false }
            'D' -> { curCol = (curCol - arg(a, 0, 1)).coerceAtLeast(0); pendingWrap = false }
            'E' -> { curRow = (curRow + arg(a, 0, 1)).coerceAtMost(rows - 1); curCol = 0 }
            'F' -> { curRow = (curRow - arg(a, 0, 1)).coerceAtLeast(0); curCol = 0 }
            'G', '`' -> { curCol = (arg(a, 0, 1) - 1).coerceIn(0, cols - 1); pendingWrap = false }
            'd' -> { curRow = (arg(a, 0, 1) - 1).coerceIn(0, rows - 1); pendingWrap = false }
            'H', 'f' -> {
                curRow = (arg(a, 0, 1) - 1).coerceIn(0, rows - 1)
                curCol = (arg(a, 1, 1) - 1).coerceIn(0, cols - 1)
                pendingWrap = false
            }
            'J' -> eraseDisplay(if (a.isEmpty()) 0 else a[0])
            'K' -> eraseLine(if (a.isEmpty()) 0 else a[0])
            'L' -> insertLines(arg(a, 0, 1))
            'M' -> deleteLines(arg(a, 0, 1))
            '@' -> insertChars(arg(a, 0, 1))
            'P' -> deleteChars(arg(a, 0, 1))
            'X' -> eraseChars(arg(a, 0, 1))
            'S' -> scrollUp(arg(a, 0, 1))
            'T' -> scrollDown(arg(a, 0, 1))
            'm' -> sgr(a)
            'r' -> {
                scrollTop = (arg(a, 0, 1) - 1).coerceIn(0, rows - 1)
                scrollBottom = (arg(a, 1, rows) - 1).coerceIn(scrollTop, rows - 1)
                curRow = 0; curCol = 0; pendingWrap = false
            }
            'n' -> if ((if (a.isEmpty()) 0 else a[0]) == 6) {
                respond?.invoke("\u001B[${curRow + 1};${curCol + 1}R")
            }
            's' -> { savedCurRow = curRow; savedCurCol = curCol }
            'u' -> { curRow = savedCurRow.coerceIn(0, rows - 1); curCol = savedCurCol.coerceIn(0, cols - 1) }
            'h', 'l' -> {} // non-private modes — ignored
            else -> {}
        }
    }

    private fun decPrivate(f: Char, a: IntArray) {
        if (f != 'h' && f != 'l') return
        val on = f == 'h'
        for (m in a) when (m) {
            1 -> applicationCursorKeys = on
            7 -> autowrap = on
            25 -> cursorVisible = on
            2004 -> bracketedPaste = on
            47, 1047, 1049 -> setAltScreen(on, save = m == 1049)
        }
    }

    private fun setAltScreen(on: Boolean, save: Boolean) {
        if (on == altScreen) return
        if (on) {
            if (save) { savedCurRow = curRow; savedCurCol = curCol }
            this.saved = Screen(ch, fg, bg, fl, curRow, curCol)
            allocate(cols, rows, clearAll = true)
            curRow = 0; curCol = 0
            scrollTop = 0; scrollBottom = rows - 1
            altScreen = true
        } else {
            saved?.let {
                ch = it.ch; fg = it.fg; bg = it.bg; fl = it.fl
                curRow = it.curRow.coerceIn(0, rows - 1)
                curCol = it.curCol.coerceIn(0, cols - 1)
            }
            saved = null
            scrollTop = 0; scrollBottom = rows - 1
            altScreen = false
            if (save) { curRow = savedCurRow.coerceIn(0, rows - 1); curCol = savedCurCol.coerceIn(0, cols - 1) }
        }
        pendingWrap = false
    }

    // ─────────────────────────── SGR ───────────────────────────
    private fun sgr(a: IntArray) {
        if (a.isEmpty()) { sgrFg = DEFAULT_COLOR; sgrBg = DEFAULT_COLOR; sgrFlags = 0; return }
        var i = 0
        while (i < a.size) {
            when (val n = a[i]) {
                0 -> { sgrFg = DEFAULT_COLOR; sgrBg = DEFAULT_COLOR; sgrFlags = 0 }
                1 -> sgrFlags = sgrFlags or Flags.BOLD
                2 -> sgrFlags = sgrFlags or Flags.DIM
                3 -> sgrFlags = sgrFlags or Flags.ITALIC
                4 -> sgrFlags = sgrFlags or Flags.UNDERLINE
                7 -> sgrFlags = sgrFlags or Flags.REVERSE
                8 -> sgrFlags = sgrFlags or Flags.INVISIBLE
                9 -> sgrFlags = sgrFlags or Flags.STRIKE
                22 -> sgrFlags = sgrFlags and (Flags.BOLD or Flags.DIM).inv()
                23 -> sgrFlags = sgrFlags and Flags.ITALIC.inv()
                24 -> sgrFlags = sgrFlags and Flags.UNDERLINE.inv()
                27 -> sgrFlags = sgrFlags and Flags.REVERSE.inv()
                28 -> sgrFlags = sgrFlags and Flags.INVISIBLE.inv()
                29 -> sgrFlags = sgrFlags and Flags.STRIKE.inv()
                in 30..37 -> sgrFg = palette(n - 30)
                in 40..47 -> sgrBg = palette(n - 40)
                in 90..97 -> sgrFg = palette(n - 90 + 8)
                in 100..107 -> sgrBg = palette(n - 100 + 8)
                39 -> sgrFg = DEFAULT_COLOR
                49 -> sgrBg = DEFAULT_COLOR
                38, 48 -> {
                    // extended colour: 38;5;n  or  38;2;r;g;b
                    val isFg = n == 38
                    if (i + 1 < a.size && a[i + 1] == 5 && i + 2 < a.size) {
                        val col = palette(a[i + 2].coerceIn(0, 255)); if (isFg) sgrFg = col else sgrBg = col
                        i += 2
                    } else if (i + 1 < a.size && a[i + 1] == 2 && i + 4 < a.size) {
                        val col = ((a[i + 2] and 0xFF) shl 16) or ((a[i + 3] and 0xFF) shl 8) or (a[i + 4] and 0xFF)
                        if (isFg) sgrFg = col else sgrBg = col
                        i += 4
                    }
                }
            }
            i++
        }
    }

    // ─────────────────────────── erase / scroll ───────────────────────────
    private fun blankCell(y: Int, x: Int) {
        ch[y][x] = ' '; fg[y][x] = DEFAULT_COLOR; bg[y][x] = sgrBg; fl[y][x] = 0
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> { for (x in curCol until cols) blankCell(curRow, x); for (y in curRow + 1 until rows) for (x in 0 until cols) blankCell(y, x) }
            1 -> { for (y in 0 until curRow) for (x in 0 until cols) blankCell(y, x); for (x in 0..curCol.coerceAtMost(cols - 1)) blankCell(curRow, x) }
            else -> for (y in 0 until rows) for (x in 0 until cols) blankCell(y, x)
        }
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            0 -> for (x in curCol until cols) blankCell(curRow, x)
            1 -> for (x in 0..curCol.coerceAtMost(cols - 1)) blankCell(curRow, x)
            else -> for (x in 0 until cols) blankCell(curRow, x)
        }
    }

    private fun eraseChars(n: Int) {
        val end = (curCol + n).coerceAtMost(cols)
        for (x in curCol until end) blankCell(curRow, x)
    }

    private fun insertChars(n: Int) {
        val cnt = n.coerceIn(1, cols - curCol)
        for (x in cols - 1 downTo curCol + cnt) {
            ch[curRow][x] = ch[curRow][x - cnt]; fg[curRow][x] = fg[curRow][x - cnt]
            bg[curRow][x] = bg[curRow][x - cnt]; fl[curRow][x] = fl[curRow][x - cnt]
        }
        for (x in curCol until curCol + cnt) blankCell(curRow, x)
    }

    private fun deleteChars(n: Int) {
        val cnt = n.coerceIn(1, cols - curCol)
        for (x in curCol until cols - cnt) {
            ch[curRow][x] = ch[curRow][x + cnt]; fg[curRow][x] = fg[curRow][x + cnt]
            bg[curRow][x] = bg[curRow][x + cnt]; fl[curRow][x] = fl[curRow][x + cnt]
        }
        for (x in cols - cnt until cols) blankCell(curRow, x)
    }

    private fun scrollUp(n: Int) {
        val cnt = n.coerceAtLeast(1)
        for (i in 0 until cnt) {
            val top = ch[scrollTop]; val topFg = fg[scrollTop]; val topBg = bg[scrollTop]; val topFl = fl[scrollTop]
            // Keep the line that is about to be overwritten — but only when the
            // whole screen is scrolling on the primary buffer. A partial scroll
            // region is a pane inside an app, not conversation history.
            if (!altScreen && scrollTop == 0 && scrollBottom == rows - 1) {
                scrollback.addLast(VtRow(top.copyOf(), topFg.copyOf(), topBg.copyOf(), topFl.copyOf()))
                while (scrollback.size > MAX_SCROLLBACK) scrollback.removeFirst()
            }
            for (y in scrollTop until scrollBottom) {
                ch[y] = ch[y + 1]; fg[y] = fg[y + 1]; bg[y] = bg[y + 1]; fl[y] = fl[y + 1]
            }
            ch[scrollBottom] = top; fg[scrollBottom] = topFg; bg[scrollBottom] = topBg; fl[scrollBottom] = topFl
            for (x in 0 until cols) { top[x] = ' '; topFg[x] = DEFAULT_COLOR; topBg[x] = sgrBg; topFl[x] = 0 }
        }
    }

    private fun scrollDown(n: Int) {
        val cnt = n.coerceAtLeast(1)
        for (i in 0 until cnt) {
            val bot = ch[scrollBottom]; val botFg = fg[scrollBottom]; val botBg = bg[scrollBottom]; val botFl = fl[scrollBottom]
            for (y in scrollBottom downTo scrollTop + 1) {
                ch[y] = ch[y - 1]; fg[y] = fg[y - 1]; bg[y] = bg[y - 1]; fl[y] = fl[y - 1]
            }
            ch[scrollTop] = bot; fg[scrollTop] = botFg; bg[scrollTop] = botBg; fl[scrollTop] = botFl
            for (x in 0 until cols) { bot[x] = ' '; botFg[x] = DEFAULT_COLOR; botBg[x] = sgrBg; botFl[x] = 0 }
        }
    }

    private fun insertLines(n: Int) {
        if (curRow < scrollTop || curRow > scrollBottom) return
        val cnt = n.coerceIn(1, scrollBottom - curRow + 1)
        val savedTop = scrollTop
        scrollTop = curRow
        scrollDown(cnt)
        scrollTop = savedTop
    }

    private fun deleteLines(n: Int) {
        if (curRow < scrollTop || curRow > scrollBottom) return
        val cnt = n.coerceIn(1, scrollBottom - curRow + 1)
        val savedTop = scrollTop
        scrollTop = curRow
        scrollUp(cnt)
        scrollTop = savedTop
    }

    private fun reset() {
        sgrFg = DEFAULT_COLOR; sgrBg = DEFAULT_COLOR; sgrFlags = 0
        autowrap = true; cursorVisible = true; applicationCursorKeys = false; bracketedPaste = false
        scrollTop = 0; scrollBottom = rows - 1
        curRow = 0; curCol = 0; pendingWrap = false
        if (altScreen) { saved = null; altScreen = false }
        allocate(cols, rows, clearAll = true)
    }

    // ─────────────────────────── snapshot ───────────────────────────
    @Synchronized
    fun snapshot(): VtScreen {
        val out = Array(rows) { y ->
            VtRow(ch[y].copyOf(), fg[y].copyOf(), bg[y].copyOf(), fl[y].copyOf())
        }
        // History travels WITH the frame: the UI renders one continuous block
        // (scrollback first, live screen last) so the user scrolls a single
        // list instead of two views that can disagree.
        val hist = if (altScreen) emptyList() else scrollback.map {
            VtRow(it.ch.copyOf(), it.fg.copyOf(), it.bg.copyOf(), it.fl.copyOf())
        }
        return VtScreen(cols, rows, out, curRow, curCol, cursorVisible, version, hist)
    }
}

/** Immutable per-frame view the UI renders. */
class VtScreen(
    val cols: Int,
    val rows: Int,
    val lines: Array<VtRow>,
    val cursorRow: Int,
    val cursorCol: Int,
    val cursorVisible: Boolean,
    val version: Long,
    /** Lines that scrolled off the top, oldest first. Empty on the alternate
     *  screen — a full-screen app owns its viewport. */
    val history: List<VtRow> = emptyList(),
)

class VtRow(
    val ch: CharArray,
    val fg: IntArray,
    val bg: IntArray,
    val fl: IntArray,
)
