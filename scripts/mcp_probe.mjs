#!/usr/bin/env node
// Probe a running mail-mcp-server over Streamable HTTP (or the legacy SSE endpoints with --sse).
// Usage:
//   node scripts/mcp_probe.mjs list
//   node scripts/mcp_probe.mjs GetMe
//   node scripts/mcp_probe.mjs ListMessages '{"limit":3,"read":false}'
//   node scripts/mcp_probe.mjs SendMessage '{"to":["a@b.com"],"subject":"hi","text":"body"}'
//   node scripts/mcp_probe.mjs list --url http://127.0.0.1:8081/mcp
import { parseArgs } from 'node:util';

const { values, positionals } = parseArgs({
  options: {
    url: { type: 'string', default: 'http://127.0.0.1:8080/mcp' },
  },
  allowPositionals: true,
});

const [mode, argJson] = positionals;
let sessionId = null;
let nextId = 1;

/** Streamable HTTP replies with bare JSON or an SSE frame; unwrap both. */
async function rpc(method, params, expectResponse = true) {
  const res = await fetch(values.url, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      accept: 'application/json, text/event-stream',
      ...(sessionId ? { 'mcp-session-id': sessionId } : {}),
    },
    body: JSON.stringify({ jsonrpc: '2.0', ...(expectResponse ? { id: nextId++ } : {}), method, params }),
  });
  const sid = res.headers.get('mcp-session-id');
  if (sid) sessionId = sid;
  const text = await res.text();
  if (!res.ok) throw new Error(`http ${res.status} for ${method}: ${text.slice(0, 400)}`);
  if (!expectResponse) return null;
  const frame = text.split('\n').find((line) => line.startsWith('data:'));
  const doc = JSON.parse(frame ? frame.slice(5).trim() : text);
  if (doc.error) throw new Error(`${method} error: ${JSON.stringify(doc.error)}`);
  return doc.result;
}

function render(result) {
  const text = (result?.content ?? []).map((c) => c.text ?? JSON.stringify(c)).join('\n');
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}

const started = Date.now();
const init = await rpc('initialize', {
  protocolVersion: '2025-03-26',
  capabilities: {},
  clientInfo: { name: 'mcp-probe', version: '0.1.0' },
});
console.error(`initialized ${init.serverInfo.name}@${init.serverInfo.version} session=${sessionId}`);
await rpc('notifications/initialized', {}, false);

if (!mode || mode === 'list') {
  const { tools } = await rpc('tools/list', {});
  console.error(`${tools.length} tools in ${Date.now() - started}ms`);
  console.log(tools.map((t) => `${t.name} required=[${(t.inputSchema?.required ?? []).join(',')}]`).join('\n'));
} else {
  const args = argJson ? JSON.parse(argJson) : {};
  const result = await rpc('tools/call', { name: mode, arguments: args });
  console.error(`tools/call ${mode} took ${Date.now() - started}ms isError=${result.isError}`);
  console.log(render(result));
}
