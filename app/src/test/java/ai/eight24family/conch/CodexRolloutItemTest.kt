package ai.eight24family.conch

import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.codex.CodexMessageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Codex ROLLOUT item envelope — the on-disk schema, not the live stream.
 *
 * A live `codex exec` sends items at the top level (`{"type":"item.completed",
 * "item":{"type":"agent_message",…}}`) and that has been parsed since 0.125. A
 * rollout on disk wraps the same item in `event_msg`/`item_completed` and
 * spells its type in PascalCase (`CommandExecution`, `FileChange`, …). Nothing
 * routed that envelope, so replaying a session from its file drew one dim,
 * contentless "item completed" row per item — in a real session measured while
 * fixing this, 1872 of them: every command, every file write and every image,
 * all invisible.
 *
 * These fixtures reproduce the shapes exactly (field names, nesting, PascalCase
 * types, the array-vs-object split in `file_change`) with neutral content, so
 * the schema cannot silently stop rendering again.
 */
class CodexRolloutItemTest {

    private val CMD =
        "{\"timestamp\":\"2026-09-06T09:06:48.444Z\",\"ordinal\":61,\"type\":\"event_msg\"," +
            "\"payload\":{\"type\":\"item_completed\",\"thread_id\":\"00000000-0000-4000-8000-000000000001\"," +
            "\"turn_id\":\"00000000-0000-4000-8000-000000000002\",\"item\":{\"type\":\"CommandExecution\"," +
            "\"id\":\"exec-00000001\",\"process_id\":\"12345\",\"command\":[\"/bin/bash\",\"-lc\",\"ls -l docs\"]," +
            "\"cwd\":\"file:///home/user/project\",\"parsed_cmd\":[{\"type\":\"read\",\"cmd\":\"ls -l docs\"}]," +
            "\"source\":\"unified_exec_startup\",\"status\":\"completed\",\"stdout\":\"total 4\\ndocs\\n\"," +
            "\"stderr\":\"\",\"aggregated_output\":\"total 4\\ndocs\\n\",\"exit_code\":\"0\"," +
            "\"duration\":{\"secs\":1,\"nanos\":0},\"formatted_output\":\"total 4\\ndocs\\n\"}," +
            "\"started_at_ms\":1788685539452,\"completed_at_ms\":1788685540771}}"

    /** `changes` as an OBJECT keyed by path — the rollout spelling. The live
     *  stream sends an array of `{kind,path}` instead; both must render. */
    private val FILE =
        "{\"timestamp\":\"2026-09-06T16:05:53.716Z\",\"ordinal\":384,\"type\":\"event_msg\"," +
            "\"payload\":{\"type\":\"item_completed\",\"thread_id\":\"00000000-0000-4000-8000-000000000001\"," +
            "\"item\":{\"type\":\"FileChange\",\"id\":\"exec-00000002\",\"changes\":{" +
            "\"/home/user/project/.gitignore\":{\"type\":\"add\",\"content\":\"node_modules\\n\"}}}," +
            "\"started_at_ms\":1788685539452,\"completed_at_ms\":1788685540771}}"

    private val IMG =
        "{\"timestamp\":\"2026-09-06T16:06:05.255Z\",\"ordinal\":390,\"type\":\"event_msg\"," +
            "\"payload\":{\"type\":\"item_completed\",\"item\":{\"type\":\"ImageView\"," +
            "\"id\":\"exec-00000003\",\"path\":\"file:///home/user/project/out/preview.png\"}," +
            "\"started_at_ms\":1788753365255,\"completed_at_ms\":1788753365261}}"

    private val EXT =
        "{\"timestamp\":\"2026-09-06T16:07:11.100Z\",\"ordinal\":401,\"type\":\"event_msg\"," +
            "\"payload\":{\"type\":\"item_completed\",\"item\":{\"type\":\"Extension\"," +
            "\"kind\":\"web.search\",\"id\":\"exec-00000004\",\"query\":\"kotlin coroutines cancellation\"," +
            "\"action\":{\"type\":\"search\"},\"results\":[]}," +
            "\"started_at_ms\":1788753365255,\"completed_at_ms\":1788753365261}}"

    private val AGENTMSG =
        "{\"timestamp\":\"2026-09-06T09:04:12.449Z\",\"ordinal\":10,\"type\":\"event_msg\"," +
            "\"payload\":{\"type\":\"item_completed\",\"item\":{\"type\":\"AgentMessage\"," +
            "\"id\":\"msg_00000001\",\"content\":[{\"type\":\"Text\",\"text\":\"Sure, here is the plan.\"}]}," +
            "\"started_at_ms\":1788685539452,\"completed_at_ms\":1788685540771}}"

    private val USERMSG =
        "{\"timestamp\":\"2026-09-06T09:04:01.018Z\",\"ordinal\":3,\"type\":\"event_msg\"," +
            "\"payload\":{\"type\":\"item_completed\",\"item\":{\"type\":\"UserMessage\"," +
            "\"id\":\"00000000-0000-4000-8000-000000000003\"," +
            "\"content\":[{\"type\":\"text\",\"text\":\"Have a look at this project.\"}]}," +
            "\"started_at_ms\":1788685539452,\"completed_at_ms\":1788685540771}}"

    private val REASONING =
        "{\"timestamp\":\"2026-09-06T09:05:00.000Z\",\"ordinal\":20,\"type\":\"event_msg\"," +
            "\"payload\":{\"type\":\"item_completed\",\"item\":{\"type\":\"Reasoning\"," +
            "\"id\":\"rs_00000001\",\"summary_text\":[],\"raw_content\":[]}," +
            "\"started_at_ms\":1788685539452,\"completed_at_ms\":1788685540771}}"

    private val COMPACT =
        "{\"timestamp\":\"2026-09-06T12:00:00.000Z\",\"ordinal\":900,\"type\":\"event_msg\"," +
            "\"payload\":{\"type\":\"item_completed\",\"item\":{\"type\":\"ContextCompaction\"," +
            "\"id\":\"00000000-0000-4000-8000-000000000004\"}," +
            "\"started_at_ms\":1788715135260,\"completed_at_ms\":1788715356505}}"

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
     * A rollout stores every message and every reasoning block TWICE — once in
     * this envelope and once as a `response_item`, paired by id. Rendering both
     * would double the entire conversation.
     */
    @Test
    fun `messages and reasoning are left to the response_item records`() {
        assertTrue("agent message duplicated: " + labels(AGENTMSG), labels(AGENTMSG).isEmpty())
        assertTrue("user message duplicated: " + labels(USERMSG), labels(USERMSG).isEmpty())
        assertTrue("reasoning duplicated: " + labels(REASONING), labels(REASONING).isEmpty())
    }
}
