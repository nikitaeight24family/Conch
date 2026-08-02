package ai.eight24family.conch.agent.claude

import ai.eight24family.conch.util.SilentlyTry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Typed view of the CLI's `initialize` control_response — the handshake the
 * Agent SDK reads at session start. THIS is the honest source for "what does
 * the server's claude offer": model catalog (with resolved ids and display
 * names), slash commands, subagents, and the account — straight from the
 * CLI's own registry, no TUI scraping.
 *
 * Response shape (verified against the 2.1.219 binary's `cbl` builder):
 * ```
 * { commands:[{name,description,argumentHint,aliases?}],
 *   agents:[{name,description,model?}],
 *   models:[{value,resolvedModel,displayName,description,disabled?,
 *            supportsEffort?,supportedEffortLevels?}],
 *   unavailable_models?:[…], output_style, available_output_styles,
 *   account:{email,organization,subscriptionType,tokenSource,apiKeySource,
 *            apiProvider}, pid, … }
 * ```
 */
internal data class ClaudeInitState(
    val models: List<InitModel>,
    val commands: List<InitCommand>,
    val agents: List<InitAgent>,
    val account: InitAccount?,
    val outputStyle: String?,
) {
    data class InitModel(
        /** The `--model`-accepted key ("default", "sonnet", "fable",
         *  "sonnet[1m]", or a full id). */
        val value: String,
        /** Concrete model id the key resolves to (e.g. `claude-sonnet-5`) —
         *  present for the "default" row too, which is how we learn the CLI's
         *  effective default without a menu scrape. */
        val resolvedModel: String?,
        /** Human label ("Sonnet 5"). */
        val displayName: String,
        val description: String?,
        /** CLI marked the row unrunnable. Billing wording is NOT this flag —
         *  see BILLING-TEXT-IS-NOT-UNAVAILABILITY-1. */
        val disabled: Boolean,
        /** Effort tokens this model supports (low…max/xhigh), empty when the
         *  model doesn't expose the effort ladder. */
        val effortLevels: List<String>,
    )

    data class InitCommand(
        val name: String,
        val description: String,
        val argumentHint: String,
        val aliases: List<String>,
    )

    data class InitAgent(val name: String, val description: String, val model: String?)

    data class InitAccount(
        val email: String?,
        val organization: String?,
        /** "max" / "pro" / "team" / "enterprise" / null (API key). */
        val subscriptionType: String?,
        val apiProvider: String?,
    )

    companion object {
        private const val TAG = "SshAi-Control"

        private fun JsonObject.str(key: String): String? =
            this[key]?.jsonPrimitive?.contentOrNull

        /** Parse the `response` payload of a successful initialize
         *  control_response. Defensive throughout — a missing/renamed field
         *  degrades that field, never the whole parse. */
        fun parse(payload: JsonObject): ClaudeInitState {
            val models = SilentlyTry.logged(TAG, "init models") {
                payload["models"]?.jsonArray?.mapNotNull { el ->
                    val o = SilentlyTry.logged(TAG, "init model row") { el.jsonObject }
                        ?: return@mapNotNull null
                    val value = o.str("value") ?: return@mapNotNull null
                    InitModel(
                        value = value,
                        resolvedModel = o.str("resolvedModel"),
                        displayName = o.str("displayName") ?: value,
                        description = o.str("description"),
                        disabled = o["disabled"]?.jsonPrimitive?.booleanOrNull == true,
                        effortLevels = SilentlyTry.logged(TAG, "init effort levels") {
                            o["supportedEffortLevels"]?.jsonArray
                                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        }.orEmpty(),
                    )
                }
            }.orEmpty()
            val commands = SilentlyTry.logged(TAG, "init commands") {
                payload["commands"]?.jsonArray?.mapNotNull { el ->
                    val o = SilentlyTry.logged(TAG, "init command row") { el.jsonObject }
                        ?: return@mapNotNull null
                    val name = o.str("name") ?: return@mapNotNull null
                    InitCommand(
                        name = name,
                        description = o.str("description").orEmpty(),
                        argumentHint = o.str("argumentHint").orEmpty(),
                        aliases = SilentlyTry.logged(TAG, "init command aliases") {
                            o["aliases"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        }.orEmpty(),
                    )
                }
            }.orEmpty()
            val agents = SilentlyTry.logged(TAG, "init agents") {
                payload["agents"]?.jsonArray?.mapNotNull { el ->
                    val o = SilentlyTry.logged(TAG, "init agent row") { el.jsonObject }
                        ?: return@mapNotNull null
                    val name = o.str("name") ?: return@mapNotNull null
                    InitAgent(name, o.str("description").orEmpty(), o.str("model"))
                }
            }.orEmpty()
            val account = SilentlyTry.logged(TAG, "init account") {
                payload["account"]?.jsonObject
            }?.let { a ->
                InitAccount(
                    email = a.str("email"),
                    organization = a.str("organization"),
                    subscriptionType = a.str("subscriptionType"),
                    apiProvider = a.str("apiProvider"),
                )
            }?.takeIf { it.email != null || it.subscriptionType != null || it.organization != null }
            return ClaudeInitState(
                models = models,
                commands = commands,
                agents = agents,
                account = account,
                outputStyle = payload.str("output_style"),
            )
        }

        /** Row label with the VERSION kept: the handshake's displayName is the
         *  CLI's short alias label ("Opus"), while the resolved id carries the
         *  real name ("claude-opus-4-8[1m]" → "Opus 4.8 1M"). The version is
         *  what tells the user which model they're actually on, so derive from
         *  the id first; the CLI's own label only as fallback. */
        internal fun labelOf(m: InitModel): String =
            m.resolvedModel?.let { claudeLabelFromId(it) } ?: m.displayName

        /** Picker map (`--model` key → display label) in CLI menu order — the
         *  same contract the old TUI parse produced. The "default" row is
         *  chip metadata, not a picker row, and is skipped (its RESOLVED
         *  model is published separately as the CLI default). */
        fun toPickerMap(state: ClaudeInitState): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (m in state.models) {
                if (m.value == "default") continue
                out[m.value] = labelOf(m)
            }
            return out
        }

        /** What the CLI runs when no `--model` is passed: the "default" row's
         *  resolved id, else the first non-disabled row. Second = the picker
         *  KEY to put on the wire for it (SHOWN-MODEL-IS-SENT-MODEL-1). */
        fun defaultModel(state: ClaudeInitState): Pair<String?, String?> {
            val def = state.models.firstOrNull { it.value == "default" }
            if (def?.resolvedModel != null) {
                // The resolved id is itself a valid --model value; if a picker
                // row resolves to the same model, prefer that row's key so the
                // wire value matches what the user would tap.
                val twin = state.models.firstOrNull {
                    it.value != "default" && it.resolvedModel == def.resolvedModel
                }
                // NEVER surface the word "Default" as a label — the row's label
                // usually reads "Default (recommended)", so resolve the concrete
                // model name.
                val label = claudeLabelFromId(def.resolvedModel)
                    ?: twin?.let { labelOf(it) }
                    ?: def.displayName.takeIf {
                        it.isNotBlank() && !it.contains("default", ignoreCase = true)
                    }
                    ?: def.resolvedModel
                return label to (twin?.value ?: def.resolvedModel)
            }
            val first = state.models.firstOrNull { it.value != "default" && !it.disabled }
                ?: return null to null
            return labelOf(first) to first.value
        }

        /** Display labels of rows the CLI marked disabled/unavailable — same
         *  derivation as the picker so the topbar's veto compares like with
         *  like. */
        fun unavailableLabels(state: ClaudeInitState): Set<String> =
            state.models.filter { it.disabled }.map { labelOf(it) }.toSet()

        /** Union of every model's effort ladder, CLI order preserved —
         *  Claude's effort catalog is uniform, so any row's list serves. */
        fun effortLevels(state: ClaudeInitState): List<String> {
            val seen = LinkedHashSet<String>()
            for (m in state.models) seen.addAll(m.effortLevels)
            return seen.toList()
        }
    }
}
