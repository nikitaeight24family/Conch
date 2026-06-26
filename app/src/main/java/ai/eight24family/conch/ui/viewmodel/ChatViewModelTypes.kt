package ai.eight24family.conch.ui.viewmodel

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.util.SilentlyTry
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ───────────────────────── Slash modal/data ─────────────────────────

sealed interface ChatModal {
    data object Memory : ChatModal
    data object ModelHint : ChatModal
    data class Unsupported(val name: String, val reason: String) : ChatModal
}

enum class MemoryScope { GLOBAL, PROJECT }

data class MemoryDocs(
    val global: String = "",
    val project: String = "",
    val projectPath: String = "",
    /** "CLAUDE.md" / "AGENTS.md" / "GEMINI.md" — drives the editor header. */
    val filename: String = "CLAUDE.md",
    /** Human-readable global path, e.g. "~/.claude/CLAUDE.md". */
    val globalDisplay: String = "~/.claude/CLAUDE.md",
)

// ───────────────────────── Cost ─────────────────────────

data class CostStats(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheCreationTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val totalCostUsd: Double = 0.0,
    val turns: Int = 0,
    /** Current context footprint (largest single-turn input+cache) — for the
     *  "Context window X / Y" line. */
    val contextTokens: Long = 0,
    /** Model context window max (from result.model_usage.contextWindow). */
    val contextMax: Long = 0,
)

// ───────────────────────── Usage bar ─────────────────────────

/**
 * State for the thin bar above the chat input. Replaces the old plain
 * divider. Three flavours, picked automatically:
 *  - [filled] == true  → a PLAN limit: [fill] (0..1) of the accent bar is
 *    drawn, [label] is "14% · 3h" (percent + reset).
 *  - [filled] == false + non-blank [label] → API spend / token count
 *    ("$0.42", "12.3k tok") shown right-aligned over a plain divider.
 *  - [EMPTY] → nothing to show yet; render the plain 1.dp divider.
 */
data class UsageBarState(
    val fill: Float = 0f,
    val label: String = "",
    val filled: Boolean = false,
    /** How depleted the limit actually is (0..1 consumed), independent of what
     *  [fill] draws. Codex draws REMAINING, but the warning colour must still
     *  key off consumption — so "99% left" stays accent, not alarm-red. */
    val severity: Float = 0f,
) {
    val isEmpty: Boolean get() = !filled && label.isBlank()

    companion object {
        val EMPTY = UsageBarState()
    }
}

internal fun computeCostStats(messages: List<AgentMessage>): CostStats {
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
    var input = 0L; var output = 0L; var cacheC = 0L; var cacheR = 0L
    var cost = 0.0; var turns = 0
    var ctxTokens = 0L; var ctxMax = 0L
    fun pluckUsage(rawJson: String) {
        // Only parse what actually looks like a JSON object. System/Result events
        // sometimes carry prose; feeding that to the strict parser threw a
        // JsonDecodingException on EVERY message — and kotlinx embeds the
        // offending input ("JSON input: …") in the exception message, which the
        // old SilentlyTry.fired logged → the ENTIRE conversation leaked to logcat
        // (SEC-10/PRIV-1) plus per-message log-spam + parse cost (BUG-1). Guard +
        // a NON-logging runCatching kills both.
        if (!rawJson.trimStart().startsWith("{")) return
        runCatching {
            val obj = json.parseToJsonElement(rawJson).jsonObject
            val usage = obj["usage"]?.jsonObject
                ?: obj["message"]?.jsonObject?.get("usage")?.jsonObject
            if (usage != null) {
                val i = usage["input_tokens"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                val o = usage["output_tokens"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                val cc = usage["cache_creation_input_tokens"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                val cr = usage["cache_read_input_tokens"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                input += i; output += o; cacheC += cc; cacheR += cr
                // Current context ≈ the largest single-turn input footprint
                // (context grows across the chat, so the max ≈ the latest turn).
                ctxTokens = maxOf(ctxTokens, i + cc + cr)
            }
            obj["total_cost_usd"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.let { cost += it }
            obj["cost_usd"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.let { cost += it }
            // Model context window (for "Context window X / Y"). Claude's
            // result carries it in model_usage.<model>.contextWindow.
            val mu = (obj["model_usage"] ?: obj["modelUsage"])?.jsonObject
            val cw = mu?.values?.mapNotNull {
                (it as? kotlinx.serialization.json.JsonObject)
                    ?.get("contextWindow")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            }?.maxOrNull()
                ?: obj["contextWindow"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            if (cw != null) ctxMax = maxOf(ctxMax, cw)
        }
    }
    for (m in messages) {
        when (m) {
            is AgentMessage.System -> if (m.raw.isNotBlank()) pluckUsage(m.raw)
            is AgentMessage.Result -> {
                turns++
                if (!m.text.isNullOrBlank()) pluckUsage(m.text)
            }
            else -> {}
        }
    }
    return CostStats(input, output, cacheC, cacheR, cost, turns, ctxTokens, ctxMax)
}

// ───────────────────────── Attachments ─────────────────────────

/**
 * Local-only model for an attachment staged in the prompt bar before sending.
 *
 * NB: the default data-class `equals` is intentionally retained — it compares all
 * fields, so a status change (Uploading(0.1) → Uploading(0.5) → Ready) produces a
 * not-equal instance and `MutableStateFlow.update { … }` actually emits. An earlier
 * custom `equals` keyed only on `id` masked status changes and the chip stayed frozen
 * at 0% even though the backend upload succeeded.
 *
 * `bytes: ByteArray` uses reference equality in the generated equals; that's fine
 * since the array reference is preserved across `copy()` calls.
 */
data class StagedAttachment(
    val id: String,
    val displayName: String,
    val mimeType: String?,
    /** In-memory content — populated only for images / small files (needed for
     *  the inline preview). EMPTY for large files, which stream from [localFile]
     *  instead so they never sit in the phone's heap (user 2026-06-14). */
    val bytes: ByteArray,
    val isImage: Boolean,
    val status: UploadStatus,
    /** For large (streamed) attachments: a temp file in cacheDir holding the
     *  picked content. Uploaded by streaming, then deleted. Null for the
     *  in-memory [bytes] path. */
    val localFile: java.io.File? = null,
    /** Byte size (from the picker), for the file-chip label and progress. */
    val sizeBytes: Long = bytes.size.toLong(),
)

sealed interface UploadStatus {
    data class Uploading(val progress: Float) : UploadStatus
    data class Ready(val remotePath: String) : UploadStatus
    data class Failed(val reason: String) : UploadStatus
}
