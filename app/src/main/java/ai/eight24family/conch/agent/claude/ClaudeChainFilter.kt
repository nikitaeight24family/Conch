package ai.eight24family.conch.agent.claude

/**
 * Reconstructs the ACTIVE conversation chain out of a Claude session JSONL.
 *
 * ⚠ WHY THIS EXISTS. A rewind does NOT truncate the session file — VERIFIED
 * against a live CLI (2026-08-02): rewinding to a user message appends a
 * `last-prompt` record whose `leafUuid` points BACK at the preceding
 * assistant, and the next turn's user record parents onto that same uuid.
 * The abandoned branch stays in the file forever. Read linearly — which is
 * exactly what our mirror does — the chat shows BOTH the discarded reply and
 * its replacement, i.e. the rewind looks like it silently failed.
 *
 * The CLI resolves this the same way: take the leaf, walk `parentUuid`
 * backwards, keep only what is on that path. This is that walk, as a pure
 * function over raw lines so it is unit-testable and costs no JSON parse
 * (a substring scan of two keys per line).
 *
 * FAIL-OPEN BY CONSTRUCTION — every branch that cannot prove a record is
 * off-chain keeps it:
 *  - a record with no `uuid` (queue-operation, last-prompt, summary,
 *    file-history-snapshot) is NEVER dropped;
 *  - no leaf found, or no fork present ⇒ empty veto set, nothing dropped;
 *  - a window that starts mid-file (the tail-poll's byte offset) just walks
 *    until the parent is missing and keeps everything it reached.
 * Dropping a real message is far worse than showing a stale one.
 */
internal object ClaudeChainFilter {

    private const val UUID_KEY = "\"uuid\":\""
    private const val PARENT_KEY = "\"parentUuid\":\""
    private const val LEAF_KEY = "\"leafUuid\":\""
    private const val USER_TYPE = "\"type\":\"user\""

    /** Value of [key] in [line] when present at the top level of the record.
     *  Cheap substring read — the ids are plain hex/dashes, never escaped. */
    private fun readKey(line: String, key: String): String? {
        val at = line.indexOf(key)
        if (at < 0) return null
        val from = at + key.length
        val end = line.indexOf('"', from)
        if (end <= from) return null
        return line.substring(from, end)
    }

    /**
     * PASS 1 — did this transcript EVER get rewound? O(1) memory, substring
     * only, safe to run over a 100 MB rollout.
     *
     * The tell: every turn ends with a `last-prompt` naming the record that
     * just landed, but a REWIND writes one naming an EARLIER record. So a
     * `leafUuid` that isn't the uuid of the line right before it means the
     * chain forked. Callers run [offChainUuids] (which builds a real map,
     * and therefore holds memory proportional to the record count) ONLY when
     * this returns true — a never-rewound session pays nothing.
     */
    fun hasRewind(lines: Sequence<String>): Boolean {
        val d = RewindDetector()
        for (line in lines) {
            d.feed(line)
            if (d.found) return true
        }
        return d.found
    }

    /** Incremental form of [hasRewind] for callers that stream lines out of a
     *  ByteBuffer and must never materialise them (the mmap'd session file). */
    class RewindDetector {
        private var prevUuid: String? = null
        var found: Boolean = false
            private set

        fun feed(line: String) {
            if (found || line.isEmpty() || line[0] != '{') return
            val uuid = readKey(line, UUID_KEY)
            if (uuid != null) {
                prevUuid = uuid
                return
            }
            val leaf = readKey(line, LEAF_KEY) ?: return
            // Before the first uuid-carrying record we have nothing to
            // compare against — say no rather than guess yes.
            if (prevUuid != null && leaf != prevUuid) found = true
        }
    }

    /** Incremental form of [offChainUuids]; same streaming rationale. */
    class ChainResolver {
        private val parentOf = LinkedHashMap<String, String>()
        private val childCount = HashMap<String, Int>()
        /** Uuids of `user` records. A conversational branch ALWAYS starts with
         *  one; a hook / system / attachment record hanging off the chain is
         *  not a branch, and treating it as one deletes rows from an ordinary
         *  chat (caught by tests after it was caught on device). */
        private val userUuids = HashSet<String>()
        private var lastUuid: String? = null
        private var lastLeaf: String? = null
        /** True once a uuid-carrying record lands AFTER the last `last-prompt`
         *  marker. See [resolve] — it decides which record is the live tip. */
        private var recordAfterLeaf = false

        fun feed(line: String) {
            if (line.isEmpty() || line[0] != '{') return
            val uuid = readKey(line, UUID_KEY)
            if (uuid == null) {
                readKey(line, LEAF_KEY)?.let { lastLeaf = it; recordAfterLeaf = false }
                return
            }
            val parent = readKey(line, PARENT_KEY).orEmpty()
            if (parentOf.put(uuid, parent) == null && parent.isNotEmpty()) {
                childCount[parent] = (childCount[parent] ?: 0) + 1
            }
            if (line.contains(USER_TYPE)) userUuids.add(uuid)
            lastUuid = uuid
            recordAfterLeaf = true
        }

        fun result(): Set<String> =
            resolve(parentOf, childCount, userUuids, lastUuid, lastLeaf, recordAfterLeaf)
    }

    /**
     * PASS 2 — record uuids that are NOT on the active chain, i.e. the
     * abandoned branches. Builds a uuid→parent map, so run it only behind
     * [hasRewind]. Empty when the transcript never actually forked.
     */
    fun offChainUuids(lines: Sequence<String>): Set<String> {
        val r = ChainResolver()
        for (line in lines) r.feed(line)
        return r.result()
    }

    private fun resolve(
        // uuid → parentUuid ("" = root), in file order.
        parentOf: LinkedHashMap<String, String>,
        // Children per parent: a parent with 2+ children MAY be a fork.
        childCount: HashMap<String, Int>,
        // Which uuids are `user` records — the only legal head of a branch.
        userUuids: Set<String>,
        lastUuid: String?,
        lastLeaf: String?,
        /** Did any real record land after the last `last-prompt` marker? */
        recordAfterLeaf: Boolean,
    ): Set<String> {
        // ⚠ A GENUINE FORK IS THE ONLY LICENCE TO HIDE ANYTHING. A parent with
        // two or more children is what a rewind leaves behind; nothing else
        // proves a branch was abandoned.
        //
        // The first version also vetoed whenever the leaf was not the last
        // record, to cover "rewound but nothing sent yet". That was WRONG and
        // it ate real chat rows: an ordinary session writes uuid-carrying
        // records AFTER the assistant (a SessionStart hook, system rows), so
        // the leaf legitimately is not the last record, and everything off the
        // leaf's ancestor path — the user's own message included — vanished
        // from a perfectly normal chat (caught on device, 2026-08-02).
        // Losing a message is far worse than briefly showing a discarded turn:
        // the live session already truncates locally on rewind, so the only
        // cost of this narrower rule is that a rewind with nothing sent yet
        // reappears if the chat is REOPENED before the next prompt.
        val forkPoints = childCount.filterValues { it > 1 }.keys
        // ⚠ THE MARKER IS NOT THE TIP. `last-prompt` is ROUTINE bookkeeping —
        // the CLI writes one naming the current tip after every single turn
        // (14 of them in one ordinary session on the user's box). Only its
        // POSITION distinguishes a rewind: a marker with nothing after it may
        // still name a record the chain moved past, but the moment real
        // records follow it, THEY are the live tip and the marker is history.
        // Trusting the marker regardless is what made a freshly sent prompt
        // vanish from the transcript: the new user record was a child of the
        // marker's leaf, so it was judged an abandoned branch and hidden —
        // the user's own message, gone from a chat that was working fine.
        val leaf = if (recordAfterLeaf) {
            lastUuid ?: return emptySet()
        } else {
            lastLeaf?.takeIf { parentOf.containsKey(it) } ?: lastUuid ?: return emptySet()
        }
        // Second, equally narrow licence: the CLI's own `leafUuid` names a
        // record that is NOT the last one written. That is a rewind whose
        // replacement turn hasn't been sent yet — no fork exists, so the rule
        // above sees nothing, and the mirror would append the discarded turn
        // straight back onto a chat we just truncated (measured on device:
        // "rewind: dropped 6 row(s)" and the row reappeared seconds later).
        // ⚠ ONLY the leaf's STRICT DESCENDANTS may go. The first version of
        // this rule vetoed everything off the leaf's ancestor path, which is a
        // completely different (and much larger) set.
        val leafIsStale = leaf != lastUuid
        if (forkPoints.isEmpty() && !leafIsStale) return emptySet()

        // Walk back to the root, collecting the active path. The visited
        // guard makes a malformed/cyclic chain terminate instead of hanging.
        val onChain = HashSet<String>()
        var cur: String? = leaf
        while (cur != null && cur.isNotEmpty() && onChain.add(cur)) {
            cur = parentOf[cur]
        }
        if (onChain.isEmpty()) return emptySet()

        // Veto ONLY the sibling branches of a fork that lies on the active
        // chain: the children of that fork which the chain did not take, plus
        // their descendants. A record that merely hangs off the chain without
        // a sibling conflict (that SessionStart hook) is NOT a branch and must
        // survive.
        val children = HashMap<String, MutableList<String>>()
        for ((uuid, parent) in parentOf) {
            if (parent.isEmpty()) continue
            children.getOrPut(parent) { mutableListOf() }.add(uuid)
        }
        val off = HashSet<String>()
        val queue = ArrayDeque<String>()
        for (fork in forkPoints) {
            if (fork !in onChain) continue
            for (child in children[fork].orEmpty()) {
                // ⚠ Only a USER record heads a discarded turn. Without this a
                // system/hook record parented on a chain node looks like a
                // second child, i.e. a "fork", and its subtree — real chat
                // rows — gets deleted.
                if (child !in onChain && child in userUuids) queue.addLast(child)
            }
        }
        // Descendants of a stale leaf: the turn the user rewound away from,
        // still physically the tail of the file. Same user-record gate.
        if (leafIsStale) children[leaf].orEmpty().forEach {
            if (it !in onChain && it in userUuids) queue.addLast(it)
        }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (!off.add(node)) continue
            children[node]?.forEach { if (it !in onChain) queue.addLast(it) }
        }
        return off
    }

    /** True when [line] belongs to an abandoned branch and must not render. */
    fun isOffChain(line: String, offChain: Set<String>): Boolean {
        if (offChain.isEmpty()) return false
        val uuid = readKey(line, UUID_KEY) ?: return false
        return uuid in offChain
    }
}
