package ai.eight24family.conch

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.codex.CodexMessageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real lines lifted verbatim from the owner's 483 MB Codex rollout
 * (2026-09-06, thread 01a075f4). Every one of them used to render as a dim,
 * contentless "item completed" row — 1872 of those in that one session, which
 * is what "an empty session" actually was: the bytes were on the phone and the
 * parser dropped them, because a rollout spells an item type PascalCase while
 * only the live stream's snake_case names were handled.
 *
 * These pin the on-disk schema so it cannot silently stop rendering again.
 */
class CodexRolloutItemTest {

    private val CMD =
        "{\"timestamp\":\"2026-09-06T09:05:33.825Z\",\"ordinal\":29,\"type\":\"event_msg\",\"payload\":{\"type\":\"item_completed\",\"thread_id\":\"01a075f4-f0e3-7540-a116-fb5bda38a35c\",\"turn_id\":\"01a075f6-8ae6-7492-b59e-9f1ca775f96c\",\"item\":{\"type\":\"CommandExecution\",\"id\":\"exec-3f5e628f-c095-4879-bfc9-3cf824e48df1\",\"process_id\":\"17645\",\"command\":[\"/bin/bash\",\"-lc\",\"for p in /AGENTS.md /home/AGENTS.md /home/user/AGENTS.md /home/user/reach/AGENTS.md; do if [ -f \\\"\$p\\\" ]; then printf '\\\\n%s\\\\n' \\\"\$p\\\"; sed -n '1,240p' \\\"\$p\\\"; fi; done\"],\"cwd\":\"file:///home/user\",\"parsed_cmd\":[{\"type\":\"unknown\",\"cmd\":\"for p in /AGENTS.md /home/AGENTS.md /home/user/AGENTS.md /home/user/reach/AGENTS.md; do if [ -f \\\"\$p\\\" ]; then printf '\\\\n%s\\\\n' \\\"\$p\\\"; sed -n '1,240p' \\\"\$p\\\"; fi; done\"}],\"source\":\"unified_exec_startup\",\"status\":\"completed\",\"stdout\":\"\",\"stderr\":\"\",\"aggregated_output\":\"\",\"exit_code\":0,\"duration\":{\"secs\":0,\"nanos\":855378274},\"formatted_output\":\"\"},\"started_at_ms\":1788685532965,\"completed_at_ms\":1788685533824}}"

    private val FILE =
        "{\"timestamp\":\"2026-09-06T16:05:53.716Z\",\"ordinal\":384,\"type\":\"event_msg\",\"payload\":{\"type\":\"item_completed\",\"thread_id\":\"01a075f4-f0e3-7540-a116-fb5bda38a35c\",\"turn_id\":\"01a0776f-05c2-7171-ade5-3439e95b6d55\",\"item\":{\"type\":\"FileChange\",\"id\":\"exec-d84e771e-c0fd-4d82-b6c0-a081c6c40cd2\",\"changes\":{\"/home/user/reach/webmap/.gitignore\":{\"type\":\"add\",\"content\":\"node_modules/\\ntest-results/\\n__pycache__/\\n\"},\"/home/user/reach/webmap/package.json\":{\"type\":\"add\",\"content\":\"{\\n  \\\"name\\\": \\\"the-reach-atlas\\\",\\n  \\\"private\\\": true,\\n  \\\"type\\\": \\\"module\\\",\\n  \\\"scripts\\\": {\\n    \\\"test\\\": \\\"node --test tests/world.test.mjs\\\",\\n    \\\"test:browser\\\": \\\"node tests/browser.mjs\\\"\\n  },\\n  \\\"devDependencies\\\": { \\\"playwright\\\": \\\"1.62.1\\\" }\\n}\\n\"}},\"status\":\"completed\",\"stdout\":\"Success. Updated the following files:\\nA /home/user/reach/webmap/package.json\\nA /home/user/reach/webmap/.gitignore\\n\",\"stderr\":\"\"},\"started_at_ms\":1788710746477,\"completed_at_ms\":1788710753716}}"

    private val IMG =
        "{\"timestamp\":\"2026-09-07T03:56:05.261Z\",\"ordinal\":600,\"type\":\"event_msg\",\"payload\":{\"type\":\"item_completed\",\"thread_id\":\"01a075f4-f0e3-7540-a116-fb5bda38a35c\",\"turn_id\":\"01a07a02-0b81-7780-a093-5551543128d1\",\"item\":{\"type\":\"ImageView\",\"id\":\"exec-bd1ea98a-ce59-4d3f-9847-33951fe9a123\",\"path\":\"file:///home/user/reach/webmap/test-results/desktop.png\"},\"started_at_ms\":1788753365255,\"completed_at_ms\":1788753365261}}"

    private val EXT =
        "{\"timestamp\":\"2026-09-06T15:57:06.073Z\",\"ordinal\":310,\"type\":\"event_msg\",\"payload\":{\"type\":\"item_completed\",\"thread_id\":\"01a075f4-f0e3-7540-a116-fb5bda38a35c\",\"turn_id\":\"01a0776f-05c2-7171-ade5-3439e95b6d55\",\"item\":{\"type\":\"Extension\",\"kind\":\"web.search\",\"id\":\"exec-d89d3dfe-ef87-410b-a75b-d132ccdaa763\",\"query\":\"Manor Lords forest castle landscape gameplay ...\",\"action\":{\"type\":\"search\",\"query\":null,\"queries\":[\"Manor Lords forest castle landscape gameplay\",\"Civilization VII commander unit map\"]},\"results\":[]},\"started_at_ms\":1788710221629,\"completed_at_ms\":1788710226072}}"

    private val AGENTMSG =
        "{\"timestamp\":\"2026-09-06T09:04:12.449Z\",\"ordinal\":10,\"type\":\"event_msg\",\"payload\":{\"type\":\"item_completed\",\"thread_id\":\"01a075f4-f0e3-7540-a116-fb5bda38a35c\",\"turn_id\":\"01a075f5-d4c8-7283-8c69-37be704b349e\",\"item\":{\"type\":\"AgentMessage\",\"id\":\"msg_0fdcdd19f38bfa6e016a9d2c83494c87d29d18ae2da25afda3\",\"content\":[{\"type\":\"Text\",\"text\":\"Sure — I can look at an existing game and critique it: design decisions, pacing, readability of the UI, and the code if sources are available. Send a build, a gameplay recording or screenshots and say whether you want a player-side or developer-side read.\"}],\"phase\":\"final_answer\"},\"started_at_ms\":1788685443294,\"completed_at_ms\":1788685452449}}"

    private val USERMSG =
        "{\"timestamp\":\"2026-09-06T09:04:01.052Z\",\"ordinal\":9,\"type\":\"event_msg\",\"payload\":{\"type\":\"item_completed\",\"thread_id\":\"01a075f4-f0e3-7540-a116-fb5bda38a35c\",\"turn_id\":\"01a075f5-d4c8-7283-8c69-37be704b349e\",\"item\":{\"type\":\"UserMessage\",\"id\":\"01a075f5-d81c-7a50-8b28-c15e612a6547\",\"content\":[{\"type\":\"text\",\"text\":\"Can you analyse an existing game — one made before your release — and critique it?\",\"text_elements\":[]}]},\"started_at_ms\":1788685441052,\"completed_at_ms\":1788685441052}}"

    private val REASONING =
        "{\"timestamp\":\"2026-09-06T09:05:40.771Z\",\"ordinal\":33,\"type\":\"event_msg\",\"payload\":{\"type\":\"item_completed\",\"thread_id\":\"01a075f4-f0e3-7540-a116-fb5bda38a35c\",\"turn_id\":\"01a075f6-8ae6-7492-b59e-9f1ca775f96c\",\"item\":{\"type\":\"Reasoning\",\"id\":\"rs_0fdcdd19f38bfa6e016a9d2ce371b087d29e3b3215b113a8c7\",\"summary_text\":[],\"raw_content\":[]},\"started_at_ms\":1788685539452,\"completed_at_ms\":1788685540771}}"

    private val COMPACT =
        "{\"timestamp\":\"2026-09-06T17:22:36.505Z\",\"ordinal\":588,\"type\":\"event_msg\",\"payload\":{\"type\":\"item_completed\",\"thread_id\":\"01a075f4-f0e3-7540-a116-fb5bda38a35c\",\"turn_id\":\"01a0776f-05c2-7171-ade5-3439e95b6d55\",\"item\":{\"type\":\"ContextCompaction\",\"id\":\"01a077ba-f11c-77d2-90a7-aad47f4ebf19\"},\"started_at_ms\":1788715135260,\"completed_at_ms\":1788715356505}}"

    private fun labels(line: String): List<String> =
        CodexMessageParser.parse(line).map {
            when (it) {
                is AgentMessage.EventNote -> "note:" + it.label
                is AgentMessage.ToolResult -> "tool:" + it.output.take(80)
                is AgentMessage.AssistantText -> "assistant:" + it.text.take(40)
                else -> it::class.simpleName.orEmpty()
            }
        }

    @Test
    fun `PascalCase item types normalise to the snake_case vocabulary`() {
        assertEquals("command_execution", CodexMessageParser.normalizeItemType("CommandExecution"))
        assertEquals("file_change", CodexMessageParser.normalizeItemType("FileChange"))
        assertEquals("image_view", CodexMessageParser.normalizeItemType("ImageView"))
        assertEquals("context_compaction", CodexMessageParser.normalizeItemType("ContextCompaction"))
        // Already snake_case (the live item.completed stream) passes through.
        assertEquals("agent_message", CodexMessageParser.normalizeItemType("agent_message"))
    }

    @Test
    fun `a rollout command execution renders its command and output`() {
        val rows = labels(CMD)
        assertTrue("expected a tool row, got " + rows, rows.any { it.startsWith("tool:") })
        assertTrue("bare label survived: " + rows, rows.none { it == "note:item completed" })
    }

    @Test
    fun `a file change names the file it wrote`() {
        val rows = labels(FILE)
        assertTrue("got " + rows, rows.any { it.startsWith("note:add ") && it.contains(".gitignore") })
        assertTrue(rows.none { it == "note:item completed" })
    }

    @Test
    fun `an image view names the image`() {
        val rows = labels(IMG)
        assertTrue("got " + rows, rows.any { it.startsWith("note:viewed image") })
    }

    @Test
    fun `an extension names its kind`() {
        val rows = labels(EXT)
        assertTrue("got " + rows, rows.isNotEmpty())
        assertTrue("bare label survived: " + rows, rows.none { it == "note:item completed" })
    }

    @Test
    fun `context compaction is named`() {
        val rows = labels(COMPACT)
        assertTrue("got " + rows, rows.any { it.contains("compact") })
    }

    /**
     * The rollout stores every message and every reasoning block TWICE — once
     * in this envelope and once as a response_item, paired by id (verified on
     * the owner's file: ordinal 10 and ordinal 11 share msg_0fdcdd19…).
     * Rendering both would double the entire conversation.
     */
    @Test
    fun `messages and reasoning are left to the response_item records`() {
        assertTrue("agent message duplicated: " + labels(AGENTMSG), labels(AGENTMSG).isEmpty())
        assertTrue("user message duplicated: " + labels(USERMSG), labels(USERMSG).isEmpty())
        assertTrue("reasoning duplicated: " + labels(REASONING), labels(REASONING).isEmpty())
    }
}
