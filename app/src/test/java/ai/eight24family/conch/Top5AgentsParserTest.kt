package ai.eight24family.conch

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.AgentMessage
import ai.eight24family.conch.agent.cont.ContinueMessageParser
import ai.eight24family.conch.agent.cont.ContinueSpec
import ai.eight24family.conch.agent.crush.CrushMessageParser
import ai.eight24family.conch.agent.crush.CrushSpec
import ai.eight24family.conch.agent.cursor.CursorMessageParser
import ai.eight24family.conch.agent.cursor.CursorSpec
import ai.eight24family.conch.agent.opencode.OpencodeMessageParser
import ai.eight24family.conch.agent.opencode.OpencodeSpec
import ai.eight24family.conch.agent.qwen.QwenMessageParser
import ai.eight24family.conch.agent.qwen.QwenSpec
import ai.eight24family.conch.agent.spec.AgentSpecRegistry
import ai.eight24family.conch.agent.spec.ExecInput
import ai.eight24family.conch.data.prefs.AgentApprovalMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The five agents added 2026-08-28 — Qwen Code, Cursor CLI, opencode, Crush,
 * Continue CLI — pinned against captures taken from the real binaries.
 *
 * Every literal here came off a live run or the CLI's own shipped schema;
 * where a shape could only be read out of a bundle (Cursor's transcripts) the
 * test says so, so nobody later mistakes it for something observed.
 */
class Top5AgentsParserTest {

    // ───────────────────── Qwen Code ─────────────────────
    //
    // Live stream = Claude Agent SDK vocabulary; PERSISTED file = a
    // Claude-shaped envelope around a GEMINI-shaped body. Both verbatim.

    private val qwenPersistedUser =
        """{"uuid":"0b6298e3-1ca1-44b8-9f64-e42811d6476f","parentUuid":null,"sessionId":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee","timestamp":"2026-08-28T03:45:23.477Z","type":"user","provenance":"real_user","cwd":"/home/u/histproj","version":"0.22.2","message":{"role":"user","parts":[{"text":"list the txt files"}]}}"""

    private val qwenPersistedAssistant =
        """{"uuid":"e22a8511-e6ac-4d63-a298-8d19ce101ea3","parentUuid":"a271486c-70af-42a9-81ea-0a927bbb35ba","sessionId":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee","timestamp":"2026-08-28T03:45:23.631Z","type":"assistant","provenance":"assistant_output","cwd":"/home/u/histproj","version":"0.22.2","model":"qwen3-coder-plus","message":{"role":"model","parts":[{"text":"Listing the directory now."},{"functionCall":{"id":"call_mock_1","name":"glob","args":{"pattern":"*.txt"}}}]},"usageMetadata":{"promptTokenCount":1234,"candidatesTokenCount":42,"thoughtsTokenCount":0,"totalTokenCount":1276,"cachedContentTokenCount":0},"contextWindowSize":1000000}"""

    private val qwenPersistedToolResult =
        """{"uuid":"3f429efb-785f-46eb-87c8-19dbeed5a993","parentUuid":"cc0754bf-fd32-4962-aea4-0bf9f51f898a","sessionId":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee","timestamp":"2026-08-28T03:45:23.669Z","type":"tool_result","provenance":"tool_result","cwd":"/home/u/histproj","version":"0.22.2","message":{"role":"user","parts":[{"functionResponse":{"id":"call_mock_1","name":"glob","response":{"output":"Found 2 file(s)"}}}]},"toolCallResult":{"callId":"call_mock_1","status":"success","executionStatus":"success"}}"""

    private val qwenPersistedAnswer =
        """{"uuid":"8bc06590-9145-4003-bbf2-4c52f7f70a2c","parentUuid":"2371c33c-18c6-41f6-ad07-b95dc33179e7","sessionId":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee","timestamp":"2026-08-28T03:45:23.719Z","type":"assistant","provenance":"assistant_output","cwd":"/home/u/histproj","version":"0.22.2","model":"qwen3-coder-plus","message":{"role":"model","parts":[{"text":"The directory contains one file: hello.txt."}]},"usageMetadata":{"promptTokenCount":2345,"candidatesTokenCount":17,"totalTokenCount":2362}}"""

    private val qwenTelemetry =
        """{"uuid":"2371c33c-18c6-41f6-ad07-b95dc33179e7","parentUuid":"3f429efb-785f-46eb-87c8-19dbeed5a993","sessionId":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee","timestamp":"2026-08-28T03:45:23.682Z","type":"system","provenance":"system","subtype":"ui_telemetry","systemPayload":{"uiEvent":{"event.name":"qwen-code.api_response"}}}"""

    @Test
    fun `qwen persisted user turn replays with the record's own uuid`() {
        val out = QwenMessageParser.parse(qwenPersistedUser)
        assertEquals("out=$out", 1, out.size)
        val u = out.first() as AgentMessage.UserText
        assertEquals("list the txt files", u.text)
        assertTrue("id must derive from the record uuid: ${u.id}", u.id.startsWith("0b6298e3"))
    }

    @Test
    fun `qwen assistant record yields text AND the tool call it carries`() {
        val out = QwenMessageParser.parse(qwenPersistedAssistant)
        assertEquals("out=$out", 2, out.size)
        assertEquals("Listing the directory now.", (out[0] as AgentMessage.AssistantText).text)
        val call = out[1] as AgentMessage.ToolUse
        assertEquals("glob", call.toolName)
        // The call id is the join key for the tool_result record.
        assertEquals("call_mock_1", call.id)
    }

    @Test
    fun `qwen tool_result joins by call id and is not mistaken for a user turn`() {
        val out = QwenMessageParser.parse(qwenPersistedToolResult)
        assertEquals("out=$out", 1, out.size)
        val r = out.first() as AgentMessage.ToolResult
        // ⚠ This record's message.role is "user" — only `provenance` tells the
        // truth, which is the whole reason the parser keys on it.
        assertEquals("call_mock_1", r.toolUseId)
        assertFalse(r.isError)
        assertTrue(r.output.contains("Found 2 file(s)"))
    }

    @Test
    fun `qwen telemetry rows are not chat rows`() {
        assertTrue(QwenMessageParser.parse(qwenTelemetry).isEmpty())
    }

    @Test
    fun `qwen turn state ends on an assistant answer, not on a marker`() {
        // The persisted file has NO terminal record, so the verdict is shape.
        val running = QwenSpec.projectTurnStateRecords(
            sequenceOf(qwenPersistedUser, qwenPersistedAssistant, qwenPersistedToolResult),
        )
        val sig = QwenSpec.inferTurnState(running, frozenForMs = 1_000L)
        assertTrue("a pending tool means the turn is alive", sig.inFlight)
        assertFalse(sig.turnComplete)

        val done = QwenSpec.projectTurnStateRecords(
            sequenceOf(qwenPersistedUser, qwenPersistedAssistant, qwenPersistedToolResult, qwenPersistedAnswer),
        )
        val sig2 = QwenSpec.inferTurnState(done, frozenForMs = null)
        assertFalse(sig2.inFlight)
        assertTrue("a text-only assistant record IS the completion", sig2.turnComplete)
    }

    @Test
    fun `qwen command uses the hyphenated mode and never skip-trust`() {
        val cmd = QwenSpec.buildExecCommand(
            ExecInput("hi", null, "qwen3-coder-plus", AgentApprovalMode.AUTO, null),
        )
        // Gemini's `auto_edit` spelling is a hard parse failure here, and
        // `--skip-trust` does not exist at all — passing it aborts the run.
        assertTrue(cmd.contains("--approval-mode auto-edit"))
        assertFalse(cmd.contains("auto_edit"))
        assertFalse(cmd.contains("--skip-trust"))
        assertTrue(cmd.contains("-o stream-json"))
        assertTrue("locale pin keeps error text parseable", cmd.contains("QWEN_CODE_LANG=en"))
    }

    // ───────────────────── Cursor CLI ─────────────────────

    @Test
    fun `cursor init carries the session id and model`() {
        val line =
            """{"type":"system","subtype":"init","apiKeySource":"login","cwd":"/p","session_id":"5f3c1c8e-1111-4222-8333-444455556666","model":"Auto","permissionMode":"default"}"""
        val sys = CursorMessageParser.parse(line).first() as AgentMessage.System
        assertEquals("init", sys.subtype)
        assertEquals("5f3c1c8e-1111-4222-8333-444455556666", sys.sessionId)
        assertEquals("Auto", sys.model)
    }

    @Test
    fun `cursor tool_call started and completed join by call id`() {
        val started =
            """{"type":"tool_call","subtype":"started","call_id":"call_7","tool_call":{"name":"shell","command":"ls"},"session_id":"s"}"""
        val use = CursorMessageParser.parse(started).first() as AgentMessage.ToolUse
        assertEquals("call_7", use.id)
        assertEquals("shell", use.toolName)

        val completed =
            """{"type":"tool_call","subtype":"completed","call_id":"call_7","tool_call":{"result":"User Rejected"},"session_id":"s"}"""
        val res = CursorMessageParser.parse(completed).first() as AgentMessage.ToolResult
        assertEquals("call_7", res.toolUseId)
        // A mode that declines tools must not read as a completed edit.
        assertTrue("a declined tool is an error, not a success", res.isError)
    }

    @Test
    fun `cursor result emits camelCase usage without summing cache into input`() {
        val line =
            """{"type":"result","subtype":"success","is_error":false,"duration_ms":10,"result":"done","session_id":"s","usage":{"inputTokens":24939,"outputTokens":1430,"cacheReadTokens":21263,"cacheWriteTokens":0}}"""
        val out = CursorMessageParser.parse(line)
        assertTrue(out.any { it is AgentMessage.Result })
        assertTrue(out.any { it is AgentMessage.TurnEnd })
        val note = out.filterIsInstance<AgentMessage.EventNote>().first()
        assertTrue("Locale.US k-format: ${note.label}", note.label.contains("in 24.9k"))
        assertTrue(note.label.contains("out 1.4k"))
        assertTrue(note.label.contains("cached 21.3k"))
    }

    @Test
    fun `cursor modes map to real flags and never combine the exclusive pair`() {
        fun cmd(m: AgentApprovalMode) =
            CursorSpec.buildExecCommand(ExecInput("x", null, null, m, null))
        assertTrue(cmd(AgentApprovalMode.PLAN).contains("--mode plan"))
        assertTrue(cmd(AgentApprovalMode.AUTO).contains("--auto-review"))
        assertTrue(cmd(AgentApprovalMode.YOLO).contains("--force"))
        // SAFE is the CLI's own default: no flag at all.
        val safe = cmd(AgentApprovalMode.SAFE)
        assertFalse(safe.contains("--mode"))
        assertFalse(safe.contains("--force"))
        // ⚠ --force + --auto-review is a hard exit 1 in the CLI.
        for (m in AgentApprovalMode.entries) {
            val c = cmd(m)
            assertFalse(
                "$m combined the mutually exclusive autorun flags: $c",
                c.contains("--force") && c.contains("--auto-review"),
            )
        }
    }

    @Test
    fun `cursor resume always passes an explicit id`() {
        val cmd = CursorSpec.buildExecCommand(
            ExecInput("x", "abc-123", null, AgentApprovalMode.SAFE, null),
        )
        // A bare `--resume` opens an Ink picker that hangs without a TTY.
        assertTrue(cmd.contains("--resume='abc-123'"))
        assertFalse(cmd.contains("--resume "))
    }

    // ───────────────────── opencode ─────────────────────

    @Test
    fun `opencode tool_use yields the call and its result from one event`() {
        val line =
            """{"type":"tool_use","timestamp":1787887635937,"sessionID":"ses_fb997d012ffeIb6FLTXwBWmS56","part":{"type":"tool","tool":"read","callID":"chatcmpl-tool-99485","state":{"status":"completed","input":{"filePath":"README.md"},"output":"1: hello world","title":"README.md"}}}"""
        val out = OpencodeMessageParser.parse(line)
        assertEquals("out=$out", 2, out.size)
        val use = out[0] as AgentMessage.ToolUse
        assertEquals("read", use.toolName)
        assertEquals("chatcmpl-tool-99485", use.id)
        val res = out[1] as AgentMessage.ToolResult
        assertEquals("chatcmpl-tool-99485", res.toolUseId)
        assertFalse(res.isError)
    }

    @Test
    fun `opencode step_finish reports tokens and cost`() {
        val line =
            """{"type":"step_finish","timestamp":1787887636295,"sessionID":"ses_x","part":{"id":"prt_1","reason":"tool-calls","type":"step-finish","tokens":{"total":8862,"input":8512,"output":94,"reasoning":0,"cache":{"write":0,"read":256}},"cost":0}}"""
        val note = OpencodeMessageParser.parse(line).first() as AgentMessage.EventNote
        assertTrue(note.label.contains("in 8.5k"))
        assertTrue(note.label.contains("out 94"))
        assertTrue(note.label.contains("cached 256"))
    }

    @Test
    fun `opencode surfaces the log line that explains an opaque error`() {
        // The JSON error is deliberately opaque; the cause is only in the log.
        val opaque =
            """{"type":"error","timestamp":1,"sessionID":"ses_x","error":{"name":"UnknownError","data":{"message":"Unexpected server error.","ref":"err_48a7"}}}"""
        val err = OpencodeMessageParser.parse(opaque).first() as AgentMessage.Error
        assertTrue("the ref must survive so the log line can be matched: ${err.text}",
            err.text.contains("err_48a7"))

        val log =
            """level=ERROR service=session message=failed ref=err_48a7 error="ProviderModelNotFoundError: Model not found: anthropic/claude-sonnet-5""""
        val fromLog = OpencodeMessageParser.parse(log).first() as AgentMessage.Error
        assertTrue(fromLog.text.contains("Model not found"))
    }

    @Test
    fun `opencode replays an exported transcript`() {
        val doc = """{"info":{"id":"ses_x","title":"t"},"messages":[""" +
            """{"info":{"role":"user","id":"msg_1"},"parts":[{"type":"text","text":"read the file","id":"prt_1"}]},""" +
            """{"info":{"role":"assistant","id":"msg_2"},"parts":[""" +
            """{"type":"step-start","id":"prt_2"},""" +
            """{"type":"tool","tool":"read","callID":"c1","state":{"status":"completed","input":{"f":"a"},"output":"hello"},"id":"prt_3"},""" +
            """{"type":"text","text":"second line","id":"prt_4"}]}]}"""
        val out = OpencodeMessageParser.parse(doc)
        assertEquals("read the file", out.filterIsInstance<AgentMessage.UserText>().first().text)
        assertEquals("second line", out.filterIsInstance<AgentMessage.AssistantText>().first().text)
        val use = out.filterIsInstance<AgentMessage.ToolUse>().first()
        assertEquals("c1", use.id)
        assertEquals("hello", out.filterIsInstance<AgentMessage.ToolResult>().first().output)
    }

    @Test
    fun `opencode command pipes the prompt and always passes a directory`() {
        val cmd = OpencodeSpec.buildExecCommand(
            ExecInput("hello world", "ses_1", "anthropic/claude-opus-5", AgentApprovalMode.AUTO, "/srv/app"),
        )
        // The prompt rides stdin: argv prompts are re-quoted by the CLI, and
        // `run` blocks reading stdin to EOF until something closes it.
        assertTrue(cmd.startsWith("printf '%s' 'hello world' |"))
        // Resuming from the wrong directory hangs forever, silently.
        assertTrue(cmd.contains("--dir '/srv/app'"))
        assertTrue(cmd.contains("--session 'ses_1'"))
        assertTrue(cmd.contains("--format json"))
        assertTrue("the log carries the real error text", cmd.contains("--print-logs"))
    }

    @Test
    fun `opencode session marker resolves to an export, others do not`() {
        val cmd = OpencodeSpec.sessionReadCommand("opencode://ses_abc")
        assertNotNull(cmd)
        assertTrue(cmd!!.contains("opencode export 'ses_abc'"))
        // Collapsed to one line — the caller parses line by line.
        assertTrue(cmd.contains("tr -d"))
        // A real path, or another agent's marker, must not be claimed.
        assertEquals(null, OpencodeSpec.sessionReadCommand("/home/u/.claude/x.jsonl"))
        assertEquals(null, OpencodeSpec.sessionReadCommand("crush://abc@/tmp"))
    }

    // ───────────────────── Crush ─────────────────────

    @Test
    fun `crush live stdout is plain prose, and its not-ready banner is an error`() {
        val text = CrushMessageParser.parse("Hello from the provider. Line one.")
        assertEquals(1, text.size)
        assertTrue(text.first() is AgentMessage.AssistantText)

        val err = CrushMessageParser.parse(
            "No providers configured - please run 'crush' to set up a provider interactively.",
        ).first()
        assertTrue("the not-ready banner must not read as an answer", err is AgentMessage.Error)
    }

    @Test
    fun `crush replays its structured transcript`() {
        val doc = """{"meta":{"id":"0d38","cost":0.003738,"prompt_tokens":1234,"completion_tokens":56},""" +
            """"messages":[""" +
            """{"role":"user","parts":[{"type":"text","text":"run echo"},{"type":"finish","reason":"stop"}]},""" +
            """{"role":"assistant","model":"m","parts":[{"type":"tool_call","tool_call_id":"call_1","name":"bash","input":"{\"command\": \"echo hi\"}"},{"type":"finish","reason":"tool_use"}]},""" +
            """{"role":"tool","parts":[{"type":"tool_result","tool_call_id":"call_1","name":"bash","content":"hi"}]},""" +
            """{"role":"assistant","parts":[{"type":"text","text":"done"},{"type":"finish","reason":"end_turn"}]}]}"""
        val out = CrushMessageParser.parse(doc)
        assertEquals("run echo", out.filterIsInstance<AgentMessage.UserText>().first().text)
        val use = out.filterIsInstance<AgentMessage.ToolUse>().first()
        assertEquals("call_1", use.id)
        // `input` is a JSON document inside a string — kept verbatim.
        assertTrue(use.input.contains("echo hi"))
        assertEquals("hi", out.filterIsInstance<AgentMessage.ToolResult>().first().output)
        assertTrue(out.filterIsInstance<AgentMessage.AssistantText>().any { it.text == "done" })
        // Crush counts per SESSION; the label must not pass that off as a turn.
        val note = out.filterIsInstance<AgentMessage.EventNote>().first()
        assertTrue(note.label.startsWith("session total"))
    }

    @Test
    fun `crush command closes stdin and never passes the root-only yolo flag`() {
        for (m in AgentApprovalMode.entries) {
            val cmd = CrushSpec.buildExecCommand(ExecInput("x", null, null, m, null))
            // Without this the turn never starts on an SSH exec channel.
            assertTrue("$m must close stdin: $cmd", cmd.contains("< /dev/null"))
            // `crush run -y` is rejected: --yolo is a ROOT flag.
            assertFalse("$m passed -y to run: $cmd", Regex("\\brun\\b[^|]*\\s-y\\b").containsMatchIn(cmd))
        }
    }

    @Test
    fun `crush marker carries the project directory into its read command`() {
        val cmd = CrushSpec.sessionReadCommand("crush://abc123@/srv/app")
        assertNotNull(cmd)
        // The database is per-project: without the directory it finds nothing.
        assertTrue(cmd!!.contains("--cwd '/srv/app'"))
        assertTrue(cmd.contains("crush session show 'abc123'"))
        assertEquals(null, CrushSpec.sessionReadCommand("opencode://ses_x"))
    }

    @Test
    fun `crush admits it cannot enforce approvals`() {
        val caveat = CrushSpec.approvalsCaveat
        assertNotNull("the shield must not imply a protection Crush lacks", caveat)
        assertTrue(caveat!!.contains("unprompted"))
    }

    // ───────────────────── Continue CLI ─────────────────────

    @Test
    fun `continue live result yields the answer and ends the turn`() {
        val line = """{"response":"MOCK ANSWER ok","status":"success","note":"wrapped"}"""
        val out = ContinueMessageParser.parse(line)
        assertEquals("MOCK ANSWER ok", out.filterIsInstance<AgentMessage.AssistantText>().first().text)
        assertTrue(out.any { it is AgentMessage.TurnEnd })
    }

    @Test
    fun `continue error result is an error, not an answer`() {
        val out = ContinueMessageParser.parse("""{"status":"error","message":"no model configured"}""")
        val err = out.first() as AgentMessage.Error
        assertTrue(err.text.contains("no model configured"))
    }

    @Test
    fun `continue replays a session file with its tool call and result`() {
        val doc = """{"sessionId":"a9e1","title":"Untitled Session","workspaceDirectory":"/srv/app",""" +
            """"history":[""" +
            """{"message":{"role":"user","content":"create the proof file"},"editorState":"create the proof file"},""" +
            """{"message":{"role":"assistant","content":"","toolCalls":[{"id":"call_mock_1","type":"function","function":{"name":"Bash","arguments":"{\"command\":\"echo hi\"}"}}]},""" +
            """"toolCallStates":[{"toolCallId":"call_mock_1","status":"done","output":[{"content":"hi","name":"Tool Result"}]}]}],""" +
            """"usage":{"totalCost":0.003366,"promptTokens":3234,"completionTokens":66}}"""
        val out = ContinueMessageParser.parse(doc)
        assertEquals("create the proof file", out.filterIsInstance<AgentMessage.UserText>().first().text)
        val use = out.filterIsInstance<AgentMessage.ToolUse>().first()
        assertEquals("Bash", use.toolName)
        assertEquals("call_mock_1", use.id)
        val res = out.filterIsInstance<AgentMessage.ToolResult>().first()
        // The join is the call id, not the position.
        assertEquals("call_mock_1", res.toolUseId)
        assertEquals("hi", res.output)
        assertTrue(out.filterIsInstance<AgentMessage.EventNote>().first().label.startsWith("session total"))
    }

    @Test
    fun `continue plan mode removes the shell, not just the writers`() {
        fun cmd(m: AgentApprovalMode) =
            ContinueSpec.buildExecCommand(ExecInput("x", null, null, m, null))
        // `--readonly` ALONE still lets headless run Bash unprompted, so the
        // read-only mode has to exclude it explicitly.
        assertTrue(cmd(AgentApprovalMode.PLAN).contains("--readonly --exclude Bash"))
        assertTrue(cmd(AgentApprovalMode.SAFE).contains("--readonly"))
        assertFalse(cmd(AgentApprovalMode.SAFE).contains("--exclude"))
        assertTrue(cmd(AgentApprovalMode.AUTO).contains("--auto"))
    }

    @Test
    fun `continue isolates each chat so resume cannot grab another chat's session`() {
        val a = ContinueSpec.buildExecCommand(
            ExecInput("x", "chat-a", null, AgentApprovalMode.SAFE, null),
        )
        val b = ContinueSpec.buildExecCommand(
            ExecInput("x", "chat-b", null, AgentApprovalMode.SAFE, null),
        )
        // `--resume` reopens the globally newest session, so the config dir is
        // what makes "newest" mean "this chat".
        assertTrue(a.contains("CONTINUE_GLOBAL_DIR="))
        assertTrue(a.contains("chat-a"))
        assertTrue(b.contains("chat-b"))
        assertFalse(a.contains("chat-b"))
        assertTrue("one command must serve turn 1 and turn N", a.contains("--resume"))
    }

    // ───────────────────── cross-agent ─────────────────────

    @Test
    fun `every new agent is registered and answers the whole contract`() {
        for (agent in listOf(
            Agent.QWEN, Agent.CURSOR, Agent.OPENCODE, Agent.CRUSH, Agent.CONTINUE,
        )) {
            val spec = AgentSpecRegistry[agent]
            assertEquals(agent, spec.agent)
            assertTrue("${agent.name} needs a display name", spec.displayName.isNotBlank())
            assertTrue("${agent.name} needs a binary", spec.cliCommand.isNotBlank())
            assertTrue("${agent.name} needs a session listing", spec.listSessionsScript != null)
            assertTrue("${agent.name} needs a status probe", spec.statusProbeLines.isNotBlank())
            // The probe's keys are read back as `<agent>_inst` etc. — a spec
            // that emits the wrong prefix reports as permanently uninstalled.
            val tag = agent.name.lowercase()
            assertTrue(
                "${agent.name} status probe must emit ${tag}_inst",
                spec.statusProbeLines.contains("${tag}_inst"),
            )
            assertTrue(
                "${agent.name} status probe must emit ${tag}_methods",
                spec.statusProbeLines.contains("${tag}_methods"),
            )
        }
    }

    @Test
    fun `no agent claims a plan mode it cannot pass to its CLI`() {
        for (agent in Agent.entries) {
            val spec = AgentSpecRegistry[agent]
            if (!spec.supportsPlanMode) continue
            val cmd = spec.buildExecCommand(
                ExecInput("x", null, null, AgentApprovalMode.PLAN, null),
            )
            val safe = spec.buildExecCommand(
                ExecInput("x", null, null, AgentApprovalMode.SAFE, null),
            )
            assertTrue(
                "${agent.name} offers Plan but builds the same command as Safe",
                cmd != safe,
            )
        }
    }
}
