package ai.eight24family.conch.util

import java.nio.ByteBuffer

/**
 * Shared JSONL helpers. The agent layer fetches stream-json files
 * incrementally — the tail of a remote `*.jsonl` may end mid-line if
 * the agent is still writing it. Feeding that half-line to a parser
 * throws; the canonical fix is to trim back to the last complete-line
 * boundary, save THAT length as "what we've absorbed so far", and let
 * the next tail-fetch resume from there.
 *
 * Lived inline in three call sites (ChatViewModel, SessionsViewModel,
 * GlobalPrefetcher) before issue #21 — the duplicated routine made
 * edge cases like "no newline at all" and "empty buffer" easy to
 * regress one file at a time.
 */
object JsonlUtils {

    /**
     * Return the prefix of [bytes] that ends at the last `\n`, or an
     * empty array if the buffer contains no newline at all.
     *
     * `bytes[bytes.size - 1] == '\n'` ⇒ the entire input is kept.
     * `bytes.isEmpty()` ⇒ empty out.
     * No newlines ⇒ empty out (caller treats as "nothing complete yet").
     */
    fun trimToLastNewline(bytes: ByteArray): ByteArray {
        var i = bytes.size - 1
        val newline = '\n'.code.toByte()
        while (i >= 0 && bytes[i] != newline) i--
        if (i < 0) return ByteArray(0)
        return bytes.copyOf(i + 1)
    }

    /**
     * `ByteBuffer` overload: returns a **slice view** of [buffer] up to
     * (and including) the last `\n` it contains. Zero copy — the result
     * shares storage with [buffer] and inherits its mmap-backed pages.
     * Position of the returned slice is 0, limit is the trimmed length.
     *
     * The caller's [buffer] is not advanced. An empty / newline-free
     * input yields an empty buffer (same semantics as the ByteArray
     * variant — "nothing complete yet").
     */
    fun trimToLastNewline(buffer: ByteBuffer): ByteBuffer {
        val dup = buffer.duplicate().apply { rewind() }
        val limit = dup.limit()
        if (limit == 0) return ByteBuffer.allocate(0)
        val newline = '\n'.code.toByte()
        var i = limit - 1
        while (i >= 0 && dup.get(i) != newline) i--
        if (i < 0) return ByteBuffer.allocate(0)
        // `slice` after limit-shrink gives a zero-copy view of [0, i+1).
        dup.limit(i + 1)
        dup.position(0)
        return dup.slice()
    }

    /** Single-line size cap for [forEachLine]. A JSONL line above this is a
     *  pathological blob (e.g. a 475 MB base64 file embedded in one message)
     *  — allocating it whole OOM-crashed the process. Skipped, not allocated.
     *  16 MB ≫ any real chat line; non-chat bodies are separately capped at
     *  16 KB by the indexer. */
    const val MAX_LINE_BYTES = 16 * 1024 * 1024

    /** Bytes of an oversized line read for the preview handed to `onOversize`.
     *  Bounded so a 475 MB line costs 64 KB to peek, not 475 MB. */
    const val OVERSIZE_HEAD_BYTES = 64 * 1024

    /**
     * Build a human-readable, truncated preview of a single oversized JSONL
     * line for display/indexing — agent-agnostic. [headPreview] is the raw
     * first [OVERSIZE_HEAD_BYTES] of the line; [totalBytes] is its true size.
     *
     * It best-effort digs the human body out of the JSON wrapper (covers
     * Claude/Codex/Gemini content fields: `output` / `text` / `content`),
     * lightly un-escapes it, caps it, and frames it with a clear truncation
     * notice. The full blob (e.g. a 475 MB runaway tool dump) is NEVER
     * materialised — the user sees what it was + its start, the app doesn't
     * OOM, and the session stays complete.
     */
    fun oversizedPreview(headPreview: String, totalBytes: Long): String {
        var s = -1
        for (k in listOf("\"output\":\"", "\"text\":\"", "\"content\":\"")) {
            val idx = headPreview.indexOf(k)
            if (idx >= 0) { s = idx + k.length; break }
        }
        val body = if (s >= 0) headPreview.substring(s) else headPreview
        val cleaned = body
            .replace("\\n", "\n").replace("\\t", "\t")
            .replace("\\\"", "\"").replace("\\\\", "\\")
            .take(8000)
        val mb = (totalBytes + (1L shl 19)) shr 20 // round to nearest MB
        return "[large output truncated — ${mb} MB]\n\n$cleaned\n\n[… truncated — ${mb} MB total]"
    }

    /**
     * Stream [buffer] line-by-line, invoking [action] once per
     * `\n`-delimited line. The terminator (and a preceding `\r`, if any)
     * is stripped; blank handling is left to the caller. Each line is
     * decoded as its own small UTF-8 slice — the whole buffer is **never**
     * materialised as one `String`.
     *
     * **Why this exists (OOM, 2026-05-29).** `Charset.decode(wholeBuffer)`
     * allocates a `CharBuffer` sized to the ENTIRE input: UTF-8 decodes at
     * up to 1 char/byte and a char is 2 bytes, so a 28 MB session forces a
     * single ~57 MB transient allocation. The "load all" sweep started
     * pulling in such sessions, and the moment one was opened (parse) or
     * indexed it `OutOfMemoryError`-killed the whole process. Per-line
     * decode bounds the allocation to one line (KBs).
     *
     * **Why splitting on the raw `\n` byte is safe.** In UTF-8, 0x0A can
     * only ever be the newline itself — it never appears as a lead byte
     * (≥0xC0) or a continuation byte (0x80–0xBF). So a slice ending just
     * before a `\n` is always a valid UTF-8 boundary; decoding it
     * independently can't split a multi-byte codepoint.
     *
     * Matches [kotlin.text.lineSequence] semantics (used by the String
     * parse path) by also dropping a trailing `\r` from `\r\n` endings.
     */
    fun forEachLine(
        buffer: ByteBuffer,
        onOversize: ((headPreview: String, totalBytes: Long) -> Unit)? = null,
        action: (String) -> Unit,
    ) {
        val dup = buffer.duplicate().apply { rewind() }
        val limit = dup.limit()
        if (limit == 0) return
        val newline = '\n'.code.toByte()
        val cr = '\r'.code.toByte()
        // Emit the line [from, rawEnd) — trimming a trailing `\r`. A line above
        // MAX_LINE_BYTES is never allocated whole (that single ~475 MB alloc
        // OOM-killed parse). Instead we hand its bounded HEAD to [onOversize]
        // so the caller can render/index a truncated placeholder; with no
        // handler we skip it (the safe default for merge/dedup callers).
        fun emit(from: Int, rawEnd: Int) {
            var end = rawEnd
            if (end > from && dup.get(end - 1) == cr) end--
            val size = end - from
            if (size <= 0) return
            if (size > MAX_LINE_BYTES) {
                if (onOversize != null) {
                    val headN = minOf(OVERSIZE_HEAD_BYTES, size)
                    val head = ByteArray(headN)
                    dup.position(from)
                    dup.get(head)
                    onOversize(String(head, Charsets.UTF_8), size.toLong())
                } else {
                    android.util.Log.w(
                        "SshAi-Jsonl",
                        "skipping oversized JSONL line (${size}B > ${MAX_LINE_BYTES}B) — no oversize handler"
                    )
                }
                return
            }
            val arr = ByteArray(size)
            dup.position(from)
            dup.get(arr)
            action(String(arr, Charsets.UTF_8))
        }
        var start = 0
        var i = 0
        while (i < limit) {
            if (dup.get(i) == newline) {
                emit(start, i)
                start = i + 1
            }
            i++
        }
        emit(start, limit) // trailing line with no terminating newline
    }
}
