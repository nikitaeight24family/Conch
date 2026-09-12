package ai.eight24family.conch.agent

/**
 * Read a session rollout the way the CLI reads it — by RECORD, not by byte.
 *
 * ⛔ THE MEASUREMENT THAT PRODUCED THIS FILE (owner's server, 2026-09-12).
 * One Codex rollout, `01a075f4`, 483,478,308 bytes over 5,391 records:
 *
 * ```
 *   custom_tool_call_output   553 records   321 MB
 *   item_completed           1872 records   109 MB
 *   function_call_output       32 records    21 MB
 *   reasoning                 657 records   2.1 MB
 *   message                   242 records   294 KB   ← the entire conversation
 * ```
 *
 * 119 single lines exceed a megabyte each and together are 458 MB — command
 * output, file contents, generated images. **The conversation is 0.06% of the
 * file.** Any window measured in BYTES lands inside one of those blobs: an 8 MB
 * tail of this file holds 25 records, of which one is a message. That is what
 * an "empty session" was — a screen of collapsed tool rows and nothing to read.
 * Downloading all 483 MB would have shown the same thing, after twenty minutes
 * and a third of a gigabyte of the user's storage.
 *
 * So the file is projected ON THE SERVER, where it already lives: records under
 * [DEFAULT_CAP_BYTES] pass through byte-for-byte, and an oversized one is
 * replaced by a stub that keeps its identity — same envelope, same type, same
 * id — carries the FIRST [PREVIEW_BYTES] of its own text (the lines the CLI
 * itself prints before it says "+N lines") and appends how big the rest was.
 * Measured on that same rollout:
 *
 * ```
 *   483,478,308 B  →  13,127,445 B projected  →  3.5 MB gzipped on the wire
 *   5,391 records in, 5,392 lines out (all of them), 243 stubbed
 *   864 ms of server CPU for the whole pass
 * ```
 *
 * Ten seconds instead of twenty-four minutes, and the WHOLE conversation rather
 * than the last 25 records of it. This is also what the CLI does: it does not
 * print a 12 MB blob into a terminal either.
 *
 * The pass ends with a `{"conch_raw_end":N}` marker giving the raw bytes it
 * consumed, because the app can no longer infer its remote position from the
 * size of what it received — the two are unrelated now. [RAW_END] strips it.
 */
object SessionProjection {

    /** Records at or under this survive verbatim. 32 KB is far more text than
     *  any chat row renders, and on the measured rollout it keeps every
     *  message, every reasoning block and 95% of tool calls intact while
     *  holding the wire under 4 MB. */
    const val DEFAULT_CAP_BYTES = 32_000

    /** How much of an oversized record's own text is carried through, so a
     *  stubbed row still shows what the CLI shows: the first lines of the
     *  output, not an apology. 2 KB is far more than any collapsed row renders
     *  and costs 243 x 2 KB = under half a megabyte on the measured session. */
    const val PREVIEW_BYTES = 2000

    /** Key of the trailing marker line. */
    const val RAW_END = "conch_raw_end"

    /**
     * The projector.
     *
     * ⚠ Written for POSIX awk (mawk on Debian, busybox awk on Alpine): no
     * `gensub`, no character classes beyond POSIX, and `{` escaped as `[{]`
     * because mawk warns on `\{`. It must also never FAIL: an unrecognised
     * line shape still yields a syntactically valid JSON line, because a
     * dropped record is a hole in the user's history.
     *
     * THREE stub shapes, because a stub is only useful if the agent's own
     * parser still recognises it — and Conch drives ten CLIs, not two. The
     * third exists because the other eight used to receive the Codex envelope
     * and would have rendered an oversized record as something it is not:
     *  - Codex — `{"type":…,"payload":{"type":…,"item":{"type":…}}}`; the
     *    preview + note goes in every field a Codex row reads for its text
     *    (`aggregated_output`, `output`, `text`), so whichever branch the
     *    parser takes finds it.
     *  - Claude — `{"type":"user|assistant","message":{"role":…,"content":…}}`;
     *    the note replaces the tool_result content (which is what is actually
     *    enormous there: base64 images and file reads, measured 1.36 MB in a
     *    single line).
     *  - Everyone else — the record's OWN top-level `type` is kept so the
     *    agent's parser dispatches exactly as it always would, and the preview
     *    goes in the fields every parser here reads for a generic row. Each of
     *    them renders an unknown shape rather than swallowing it, so this lands
     *    as a visible line saying what is missing instead of a silent hole.
     */
    val AWK: String = """
        # ⚠ JSON MAY PUT WHITESPACE AFTER A COLON AND STILL BE JSON. The CLIs
        # write compact lines today, but a projector that silently returns ""
        # the day one of them pretty-prints would strip the record's own type
        # and hand every parser an unrecognisable stub. Tolerate the space.
        function fld(s, key,   m) {
          if (match(s, "\"" key "\"[[:space:]]*:[[:space:]]*\"[^\"]*\"")) {
            m = substr(s, RSTART, RLENGTH)
            sub("^\"" key "\"[[:space:]]*:[[:space:]]*\"", "", m); sub("\"${'$'}", "", m)
            return m
          }
          return ""
        }
        function nested(s, outer,   m) {
          if (match(s, "\"" outer "\"[[:space:]]*:[[:space:]]*[{][[:space:]]*\"type\"[[:space:]]*:[[:space:]]*\"[A-Za-z_.]+\"")) {
            m = substr(s, RSTART, RLENGTH); sub(".*\"type\"[[:space:]]*:[[:space:]]*\"", "", m); sub("\"${'$'}", "", m)
            return m
          }
          return ""
        }
        # ⛔ A FRAGMENT OF A JSON STRING MUST STILL BE A JSON STRING.
        # Taking substr(s, 1, N) off a value inside a JSON string breaks it two
        # ways, and both happened: the cut can land INSIDE an escape (leaving a
        # lone trailing backslash, which then escapes the stub's own closing
        # quote), and a short value runs past its own closing quote into the rest
        # of the record. Measured on the owner's rollout: 87 of 5,609 projected
        # lines came back as invalid JSON before this existed. So walk the
        # characters: stop at the first quote that is NOT escaped, and never end
        # on an odd run of backslashes.
        function jsonprefix(s, n,   i, c, out, bs, lim) {
          out = ""; bs = 0; lim = length(s); if (n < lim) lim = n
          for (i = 1; i <= lim; i++) {
            c = substr(s, i, 1)
            if (c == "\"" && bs % 2 == 0) break
            if (c == "\\") bs++; else bs = 0
            out = out c
          }
          if (bs % 2 == 1) out = substr(out, 1, length(out) - 1)
          return out
        }
        function human(n) {
          if (n >= 1048576) return sprintf("%.1f MB", n / 1048576)
          if (n >= 1024)    return sprintf("%.0f KB", n / 1024)
          return n " B"
        }
        { raw += length(${'$'}0) + 1 }
        length(${'$'}0) <= CAP { print; next }
        {
          head = substr(${'$'}0, 1, 8000)
          # ⛔ KEEP THE PART THE CLI ACTUALLY PRINTS.
          #
          # The first version of this replaced an oversized record with a bare
          # "not synced" note, and the owner was right to call that a broken
          # promise: the terminal does not print the 4.8 MB either, it prints
          # the FIRST few lines and says how many it hid. Those lines live at
          # the very START of the record — a custom_tool_call_output opens with
          # {"output":[{"type":"input_text","text":"Script completed\nWall time
          # 2.0 seconds\nOutput:\n"}, ... — so the head window already has them.
          # Carry that text and append the note, instead of throwing the record
          # away and telling the user to go look at a terminal.
          keep = ""
          h2 = head
          sub(/^.*"text"[[:space:]]*:[[:space:]]*"/, "", h2)
          if (h2 != head) keep = jsonprefix(h2, PREVIEW)
          if (keep == "") {
            h3 = head
            sub(/^.*"output"[[:space:]]*:[[:space:]]*"/, "", h3)
            if (h3 != head) keep = jsonprefix(h3, PREVIEW)
          }
          note = "[Conch] " human(length(${'$'}0)) " in this record, showing the first part"
          if (keep != "") note = keep "\\n\\n" note
          else note = "[Conch] " human(length(${'$'}0)) " in this record - open it on the server"
          # ⛔ A FAILED COMMAND MUST STILL LOOK FAILED.
          # A tool row is coloured by whether its text says "exited with code 0",
          # so a stub that carries no verdict paints a failure as an ordinary
          # grey row and hides it. `exit_code` cannot be used: it is written
          # BETWEEN two of the fields that made the record huge (after
          # aggregated_output, before formatted_output), so neither the head nor
          # the tail sees it, and scanning the whole line costs 5 extra seconds
          # of the user's server per open — measured, 2.8 s → 7.8 s on 483 MB.
          # `status` says the same thing and sits in the head, ahead of stdout.
          st = fld(head, "status")
          if (st == "failed") note = note " (exited with code 1)"
          else if (st == "completed") note = note " (exited with code 0)"
          rt = fld(head, "type")
          id = fld(head, "id")
          # Stub ids must stay DISTINCT: the item branches de-duplicate rows by
          # id, so two id-less stubs would collapse into one and quietly delete
          # a record. The ordinal is unique per line by construction.
          if (id == "") id = "conch-stub-" NR
          if (index(head, "\"payload\":[{]") > 0 || match(head, "\"payload\":[{]")) {
            pt = nested(head, "payload")
            it = nested(head, "item")
            ord = 0
            if (match(head, "\"ordinal\":[0-9]+")) { ord = substr(head, RSTART, RLENGTH); sub(".*:", "", ord) }
            printf "{\"timestamp\":\"%s\",\"ordinal\":%s,\"type\":\"%s\",\"conch_truncated\":%d,\"payload\":{\"type\":\"%s\",\"id\":\"%s\",\"call_id\":\"%s\",\"item\":{\"type\":\"%s\",\"id\":\"%s\",\"status\":\"completed\",\"aggregated_output\":\"%s\"},\"output\":\"%s\",\"text\":\"%s\"}}\n",
              fld(head, "timestamp"), ord, (rt == "" ? "event_msg" : rt), length(${'$'}0),
              (pt == "" ? "item_completed" : pt), id, fld(head, "call_id"),
              (it == "" ? "CommandExecution" : it), id, note, note, note
          } else if (index(head, "\"message\"") > 0) {
            role = fld(head, "role")
            printf "{\"parentUuid\":\"%s\",\"uuid\":\"%s\",\"sessionId\":\"%s\",\"type\":\"%s\",\"conch_truncated\":%d,\"message\":{\"role\":\"%s\",\"content\":[{\"tool_use_id\":\"%s\",\"type\":\"tool_result\",\"content\":\"%s\"}]}}\n",
              fld(head, "parentUuid"), fld(head, "uuid"), fld(head, "sessionId"),
              (rt == "" ? "user" : rt), length(${'$'}0),
              (role == "" ? "user" : role), fld(head, "tool_use_id"), note
          } else {
            # ⛔ EVERY OTHER AGENT. Conch drives ten CLIs and only two of them
            # have a stub shape here; the rest used to receive the Codex
            # envelope, which their parsers do not recognise — so an oversized
            # record came back wearing another CLI's clothes and rendered as
            # something it is not, or not at all.
            #
            # Without knowing a schema the only honest stub is a minimal one:
            # keep the record's own top-level `type` so the agent's parser
            # dispatches as it always would, and put the preview in the fields
            # every parser in this codebase reads for a generic row. Each of
            # them renders an unknown shape rather than swallowing it, so this
            # lands as a visible line saying what is missing.
            printf "{\"type\":\"%s\",\"conch_truncated\":%d,\"text\":\"%s\",\"message\":\"%s\",\"content\":\"%s\"}\n",
              (rt == "" ? "conch_truncated_record" : rt), length(${'$'}0), note, note, note
          }
        }
        END { printf "{\"${'$'}{RAWKEY}\":%d}\n", raw }
    """.trimIndent().replace("\${RAWKEY}", RAW_END)

    /** awk with newlines collapsed to `;`-safe form is NOT needed — the program
     *  is passed through a single-quoted shell argument, so it may contain
     *  newlines verbatim. This only guards against a stray single quote. */
    private fun shQuote(v: String): String = "'" + v.replace("'", "'\\''") + "'"

    /**
     * `tail -c +<from+1> <path> | awk … | gzip -c | base64 -w0`.
     *
     * Same wire discipline as every other fetch in the app. `from` is a RAW
     * byte offset into the remote file and must land on a record boundary —
     * the caller keeps that offset from the previous pass's [RAW_END] marker,
     * never from the size of what it stored.
     */
    fun command(path: String, fromOffset: Long, cap: Int = DEFAULT_CAP_BYTES): String {
        val q = shQuote(path)
        val src = if (fromOffset <= 0L) "cat $q" else "tail -c +${fromOffset + 1} $q"
        return RemoteEnv.portable(
            "bash -lc " + shQuote(
                "$src | awk -v CAP=$cap -v PREVIEW=$PREVIEW_BYTES " + shQuote(AWK) + " | gzip -c | base64 -w0",
            ),
        )
    }
}
