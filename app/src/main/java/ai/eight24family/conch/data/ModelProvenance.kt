package ai.eight24family.conch.data

/**
 * Which catalog entries the picker may OFFER, given which keys some CLI's own
 * registry has confirmed.
 *
 * The model catalog is shared by every server and grows monotonically
 * (`ModelLabelMerge`, MODEL-CATALOG-MONOTONIC-1) — nothing is ever deleted,
 * because a short/failed probe must not be able to wipe models the user
 * really has, and because a chat pinned to an old alias still needs its
 * LABEL. But after the move from scraping the `/model` TUI to reading the
 * CLI's `initialize` registry, the catalog also carries dead keys the CLI no
 * longer offers at all — picking one is a coin flip.
 *
 * So entries get PROVENANCE instead of deletion: a key confirmed by ANY
 * server's registry is a real choice; a key no registry has ever mentioned is
 * a leftover — resolvable, not choosable.
 *
 * Pure so the rules are testable, because both failure directions are
 * expensive: hiding a live model makes it unpickable, and showing a dead one
 * sends `--model <something the CLI never heard of>`.
 */
object ModelProvenance {

    /**
     * Keys to hide from the picker.
     *
     * FAIL-OPEN in two directions:
     *  - no confirmations at all (nothing has handshaken yet, or an agent
     *    whose spec isn't authoritative) ⇒ hide NOTHING. An empty picker is
     *    worse than a stale row, and it is exactly what a cold start would
     *    otherwise show;
     *  - a key the user has PINNED ([keepVisible] — an explicit pick, the
     *    session's own model) is never hidden, even unconfirmed: the model a
     *    chat is genuinely running is always a legitimate choice, and hiding
     *    it would make the picker contradict the chip.
     */
    fun hidden(
        catalog: Set<String>,
        registryConfirmed: Set<String>,
        keepVisible: Set<String> = emptySet(),
    ): Set<String> {
        if (registryConfirmed.isEmpty()) return emptySet()
        return catalog.filterTo(HashSet()) { key ->
            key !in registryConfirmed && key !in keepVisible
        }
    }
}
