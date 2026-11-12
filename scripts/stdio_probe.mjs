#!/usr/bin/env node
// Drive the stdio transport: spawn the jar, initialize, list tools, and prove stdout carries nothing
// but JSON-RPC frames (a stray banner or log line would poison the protocol stream).
// Usage: node scripts/stdio_probe.mjs [path-to-jar]
import { spawn } from 'node:child_process';

const jar = process.argv[2] ?? 'build/mail-mcp-server.jar';
const child = spawn('java', ['-jar', jar, '--spring.profiles.active=stdio'], {
  stdio: ['pipe', 'pipe', 'pipe'],
});

const stdoutChunks = [];
const stderrChunks = [];
child.stdout.on('data', (d) => stdoutChunks.push(d));
child.stderr.on('data', (d) => stderrChunks.push(d));

const send = (obj) => child.stdin.write(`${JSON.stringify(obj)}\n`);
const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

send({
  jsonrpc: '2.0',
  id: 1,
  method: 'initialize',
  params: { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'stdio-probe', version: '0' } },
});
await wait(3000);
send({ jsonrpc: '2.0', method: 'notifications/initialized' });
send({ jsonrpc: '2.0', id: 2, method: 'tools/list' });
await wait(3000);

child.kill('SIGTERM');
await wait(500);

const stdout = Buffer.concat(stdoutChunks).toString('utf8');
const stderr = Buffer.concat(stderrChunks).toString('utf8');
const lines = stdout.split('\n').filter((l) => l.trim() !== '');

let nonJson = 0;
const tools = [];
for (const line of lines) {
  let doc;
  try {
    doc = JSON.parse(line);
  } catch {
    nonJson += 1;
    console.error(`NON-JSON on stdout: ${line.slice(0, 160)}`);
    continue;
  }
  if (doc.id === 1) console.error(`initialize -> ${doc.result?.serverInfo?.name}@${doc.result?.serverInfo?.version}`);
  if (doc.id === 2) for (const t of doc.result?.tools ?? []) tools.push(t.name);
}

console.error(`stdout lines=${lines.length} nonJson=${nonJson} tools=${tools.length}`);
console.error(`tools: ${tools.join(', ')}`);
console.error(`stderr tail: ${stderr.split('\n').filter(Boolean).slice(-1)[0] ?? '(empty)'}`);
process.exit(nonJson === 0 && tools.length > 0 ? 0 : 1);
