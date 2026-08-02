package ai.eight24family.conch.agent

/**
 * Outcome of a CONVERSATION rewind (`rewind_conversation`).
 *
 * Top-level rather than nested in the (internal) persistent-stream class so
 * the session and the ViewModel can hand it around without leaking the
 * transport type.
 */
data class RewindResult(
    val ok: Boolean,
    val targetMessageUuid: String? = null,
    /** The rewound prompt, handed back so the composer can offer it for
     *  editing — the CLI does the same. */
    val prefillText: String? = null,
    /** The CLI's OWN reason on refusal ("turn running", "target not found",
     *  "stale target"). Surfaced verbatim: it is what tells the user what to
     *  do next. */
    val error: String? = null,
)

/** What a FILE rewind (`rewind_files`) would do (dry run) or did. */
data class FileRewindResult(
    val canRewind: Boolean,
    val filesChanged: List<String> = emptyList(),
    val insertions: Int = 0,
    val deletions: Int = 0,
    val error: String? = null,
)
