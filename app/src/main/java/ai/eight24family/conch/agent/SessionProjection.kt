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
 * id — and says how big the body was instead of carrying it. Measured on that
 * same rollout:
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
     * Two stub shapes, because the two CLIs store different envelopes and a
     * stub is only useful if the agent's own parser still recognises it:
     *  - Codex — `{"type":…,"payload":{"type":…,"item":{"type":…}}}`; the
     *    truncation note goes in every field a Codex row reads for its text
     *    (`aggregated_output`, `output`, `text`), so whichever branch the
     *    parser takes finds it.
     *  - Claude — `{"type":"user|assistant","message":{"role":…,"content":…}}`;
     *    the note replaces the tool_result content (which is what is actually
     *    enormous there: base64 images and file reads, measured 1.36 MB in a
     *    single line).
     */
    val AWK: String = """
        function fld(s, key,   m) {
          if (match(s, "\"" key "\":\"[^\"]*\"")) {
            m = substr(s, RSTART, RLENGTH)
            sub("\"" key "\":\"", "", m); sub("\"${'$'}", "", m)
            return m
          }
          return ""
        }
        function nested(s, outer,   m) {
          if (match(s, "\"" outer "\":[{]\"type\":\"[A-Za-z_.]+\"")) {
            m = substr(s, RSTART, RLENGTH); sub(".*\"type\":\"", "", m); sub("\"${'$'}", "", m)
            return m
          }
          return ""
        }
        function human(n) {
          if (n >= 1048576) return sprintf("%.1f MB", n / 1048576)
          if (n >= 1024)    return sprintf("%.0f KB", n / 1024)
          return n " B"
        }
        { raw += length(${'$'}0) + 1 }
        length(${'$'}0) <= CAP { print; next }
        {
          head = substr(${'$'}0, 1, 4000)
          note = "[Conch] not synced - " human(length(${'$'}0)) " in this record; open it on the server"
          # ⛔ THE EXIT CODE LIVES PAST THE OUTPUT, SO SEARCH THE WHOLE LINE.
          # A tool row is coloured by whether its text says "exited with code 0"
          # — the parser decides on the text the stub carries, so a stub with no
          # exit code paints a FAILED command as an ordinary grey row and hides
          # the failure. The field is written after the output that made the
          # record huge, so the 4000-byte head cannot see it; one match over the
          # full line can, and costs nothing next to the gzip that follows.
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
          } else {
            role = fld(head, "role")
            printf "{\"parentUuid\":\"%s\",\"uuid\":\"%s\",\"sessionId\":\"%s\",\"type\":\"%s\",\"conch_truncated\":%d,\"message\":{\"role\":\"%s\",\"content\":[{\"tool_use_id\":\"%s\",\"type\":\"tool_result\",\"content\":\"%s\"}]}}\n",
              fld(head, "parentUuid"), fld(head, "uuid"), fld(head, "sessionId"),
              (rt == "" ? "user" : rt), length(${'$'}0),
              (role == "" ? "user" : role), fld(head, "tool_use_id"), note
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
                "$src | awk -v CAP=$cap " + shQuote(AWK) + " | gzip -c | base64 -w0",
            ),
        )
    }
}
