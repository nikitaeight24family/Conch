package ai.eight24family.conch.agent

object PermissionBridge {
    const val TOOL_NAME = "prompt_user"
    const val MCP_SERVER_NAME = "sshai"

    val NODE_SCRIPT: String = """
#!/usr/bin/env node
// Conch permission bridge — minimal MCP stdio server.
// Exposes one tool: prompt_user(tool_name, input, tool_use_id).
// Writes each request to SSHAI_REQ_FILE as JSONL, blocks until a
// response file appears at SSHAI_RESP_DIR/<tool_use_id>.json, then
// returns the response back to Claude Code via MCP.
const fs = require('fs');
const path = require('path');
const REQ_FILE = process.env.SSHAI_REQ_FILE;
const RESP_DIR = process.env.SSHAI_RESP_DIR;
let buf = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', chunk => {
  buf += chunk;
  const parts = buf.split('\n');
  buf = parts.pop();
  for (const line of parts) {
    if (!line.trim()) continue;
    try { handle(JSON.parse(line)); } catch (e) {}
  }
});
function send(msg) {
  process.stdout.write(JSON.stringify(msg) + '\n');
}
function handle(msg) {
  const id = msg.id;
  if (msg.method === 'initialize') {
    send({ jsonrpc: '2.0', id, result: {
      protocolVersion: '2024-11-05',
      capabilities: { tools: { listChanged: false } },
      serverInfo: { name: '$MCP_SERVER_NAME', version: '1.0.5' }
    }});
  } else if (msg.method === 'tools/list') {
    send({ jsonrpc: '2.0', id, result: {
      tools: [{
        name: '$TOOL_NAME',
        description: 'Ask the user to approve or deny a tool call from a mobile app.',
        inputSchema: {
          type: 'object',
          properties: {
            tool_name: { type: 'string' },
            input: { type: 'object' },
            tool_use_id: { type: 'string' }
          },
          required: ['tool_name', 'input']
        }
      }]
    }});
  } else if (msg.method === 'tools/call' && msg.params && msg.params.name === '$TOOL_NAME') {
    const args = msg.params.arguments || {};
    const reqId = args.tool_use_id || ('req_' + Date.now() + '_' + Math.random().toString(36).slice(2, 8));
    const reqRecord = { id: reqId, tool_name: args.tool_name, input: args.input, tool_use_id: args.tool_use_id };
    try { fs.appendFileSync(REQ_FILE, JSON.stringify(reqRecord) + '\n'); } catch (e) {}
    const respFile = path.join(RESP_DIR, reqId + '.json');
    const wait = () => {
      if (fs.existsSync(respFile)) {
        let resp;
        try {
          resp = JSON.parse(fs.readFileSync(respFile, 'utf8'));
          fs.unlinkSync(respFile);
        } catch (e) {
          resp = { behavior: 'deny', message: 'bridge error: ' + e.message };
        }
        send({ jsonrpc: '2.0', id, result: { content: [{ type: 'text', text: JSON.stringify(resp) }] } });
      } else {
        setTimeout(wait, 200);
      }
    };
    wait();
  } else if (msg.method && msg.method.startsWith('notifications/')) {
    // notifications/initialized etc — no response
  } else if (id !== undefined) {
    send({ jsonrpc: '2.0', id, error: { code: -32601, message: 'method not found: ' + msg.method } });
  }
}
""".trimIndent()
}
