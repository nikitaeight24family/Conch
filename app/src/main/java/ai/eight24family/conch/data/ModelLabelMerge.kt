package ai.eight24family.conch.data

/**
 * Merge a freshly-probed `alias → label` map into the cached one WITHOUT ever
 * moving a model backwards.
 *
 * But a probe can legitimately return the older name — a stale menu render, a
 * half-parsed screen, a box that hasn't been updated yet — and letting that
 * overwrite the cache is how the picker started advertising "Opus 4.8" again
 * after it had already learned "Opus 5".
 *
 * So the cache is monotonic per alias: a new label wins unless it names the SAME
 * family at an OLDER version. Everything else (new aliases, renames to a
 * different family, unparseable labels) takes the fresh value — we only veto the
 * one direction that is known-impossible.
 */
object ModelLabelMerge {

    /** `Opus 4.8` → family "opus", version [4, 8]. Null when it isn't versioned. */
    internal fun parse(label: String): Pair<String, List<Int>>? {
        val m = Regex("^\\s*([A-Za-z]+)\\s+(\\d+(?:\\.\\d+)*)").find(label) ?: return null
        val family = m.groupValues[1].lowercase()
        val version = m.groupValues[2].split('.').mapNotNull { it.toIntOrNull() }
        return if (version.isEmpty()) null else family to version
    }

    /** Numeric, component-wise: 5 > 4.8, 4.10 > 4.8, 4.8 == 4.8. */
    private fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    /** True when [fresh] would move [cached] BACKWARDS for the same family. */
    internal fun isRegression(cached: String, fresh: String): Boolean {
        val (cf, cv) = parse(cached) ?: return false
        val (ff, fv) = parse(fresh) ?: return false
        return cf == ff && compare(fv, cv) < 0
    }

    /**
     * Cached ∪ fresh, keeping the cached label whenever the fresh one is an
     * older version of the same family. Aliases only in the cache SURVIVE — a
     * probe that returned a short list (a truncated render) must not delete
     * models the app already knows about.
     */
    fun merge(cached: Map<String, String>, fresh: Map<String, String>): Map<String, String> {
        if (fresh.isEmpty()) return cached
        val out = LinkedHashMap(cached)
        for ((alias, freshLabel) in fresh) {
            val old = out[alias]
            out[alias] = if (old != null && isRegression(old, freshLabel)) old else freshLabel
        }
        return out
    }
}
