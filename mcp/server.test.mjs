import test from 'node:test';
import assert from 'node:assert/strict';
import { PassThrough } from 'node:stream';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createAdbRunner, createJournal, createHandler, serveStdio } from './server.mjs';

test('MCP initialization discovery and validation never execute model shell text', async () => {
  let calls = 0;
  const handle = createHandler({ run: async () => { calls++; return ''; }, print: () => {}, record: async () => {} });
  const request = (id, method, params = {}) => ({ jsonrpc: '2.0', id, method, params });
  const init = await handle(request(1, 'initialize', { protocolVersion: '2024-11-05', capabilities: {}, clientInfo: { name: 'test', version: '1' } }));
  assert.equal(init.result.protocolVersion, '2024-11-05');
  assert.equal(init.result.serverInfo.name, 's23-power');
  const list = await handle(request(2, 'tools/list'));
  assert.equal(list.result.tools.length, 10);
  const response = await handle(request(3, 'tools/call', { name: 'force_stop', arguments: { pkg: 'org.example.sync' } }));
  assert.equal(response.result.isError, true);
  assert.equal(calls, 0);
  assert.equal((await handle(request(4, 'shell', { command: 'reboot' }))).error.code, -32601);
  assert.equal(await handle({ jsonrpc: '2.0', method: 'notifications/initialized' }), null);
});

test('adb uses an argv subprocess with timeout and bounded output', async () => {
  let invocation;
  const run = createAdbRunner('192.0.2.10:37000', async (...args) => { invocation = args; return { stdout: '73', stderr: '' }; });
  assert.equal(await run(['dumpsys', 'battery']), '73');
  assert.equal(invocation[0], 'adb');
  assert.deepEqual(invocation[1], ['-s', '192.0.2.10:37000', 'shell', 'dumpsys', 'battery']);
  assert.equal(invocation[2].shell, false);
  assert.equal(invocation[2].timeout, 12000);
  assert.throws(() => createAdbRunner('phone;reboot'));
});

test('durable journal retains both prepared and completed events', async () => {
  const folder = await mkdtemp(join(tmpdir(), 's23-journal-'));
  try {
    const path = join(folder, 'journal.jsonl'), record = createJournal(path);
    await record({ id: '1', status: 'PREPARED' });
    await record({ id: '1', status: 'ACCEPTED' });
    assert.deepEqual((await readFile(path, 'utf8')).trim().split('\n').map(JSON.parse).map(row => row.status), ['PREPARED', 'ACCEPTED']);
  } finally { await rm(folder, { recursive: true, force: true }); }
});

test('stdio server emits only newline-delimited JSON-RPC', { timeout: 5000 }, async () => {
  const input = new PassThrough(), output = new PassThrough(), errors = new PassThrough();
  let stdout = '';
  output.setEncoding('utf8'); output.on('data', chunk => { stdout += chunk; });
  const done = serveStdio(input, output, errors, createHandler({ run: async () => '', print: () => {}, record: async () => {} }));
  input.end(JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'test', version: '1' } } }) + '\n' +
      JSON.stringify({ jsonrpc: '2.0', id: 2, method: 'tools/list' }) + '\n');
  await done;
  const responses = stdout.trim().split('\n').map(JSON.parse);
  assert.equal(responses.length, 2);
  assert.equal(responses[1].result.tools.length, 10);
});
