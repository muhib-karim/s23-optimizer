#!/usr/bin/env node
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdir, open } from 'node:fs/promises';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createTools, toolDefinitions } from './tools.mjs';

const exec = promisify(execFile);
const serialPattern = /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,254}$/;

export function createAdbRunner(serial = '', execute = exec) {
  if (serial && !serialPattern.test(serial)) throw Error('Invalid ANDROID_SERIAL.');
  return async args => {
    try {
      const { stdout, stderr } = await execute('adb', [...(serial ? ['-s', serial] : []), 'shell', ...args], {
        encoding: 'utf8', shell: false, timeout: 12000, maxBuffer: 4 * 1024 * 1024, windowsHide: true,
      });
      if (/Permission Denial|SecurityException|(?:^|\n)Error:/i.test(`${stdout}\n${stderr}`)) throw Error('Denied');
      return stdout;
    } catch { throw Error('ADB command failed or timed out. Check pairing, selected device and shell permission; write outcome may be uncertain.'); }
  };
}

export function createJournal(path) {
  return async row => {
    await mkdir(dirname(path), { recursive: true, mode: 0o700 });
    const file = await open(path, 'a', 0o600);
    try { await file.writeFile(JSON.stringify(row) + '\n', 'utf8'); await file.sync(); }
    finally { await file.close(); }
  };
}

/** Minimal MCP stdio: JSON-RPC initialize, ping, tools/list and tools/call. No network listener. */
export function createHandler(options) {
  const tools = createTools(options);
  let initialized = false;
  return async request => {
    const id = request?.id ?? null;
    const error = (code, message) => ({ jsonrpc: '2.0', id, error: { code, message } });
    if (!request || Array.isArray(request) || request.jsonrpc !== '2.0' || typeof request.method !== 'string' ||
        (Object.hasOwn(request, 'id') && typeof request.id !== 'string' && typeof request.id !== 'number')) return error(-32600, 'Invalid JSON-RPC request.');
    // Notifications have no reply and may never invoke a tool or mutate state.
    if (!Object.hasOwn(request, 'id')) return null;
    const result = value => ({ jsonrpc: '2.0', id, result: value });
    const params = request.params ?? {};
    if (typeof params !== 'object' || params === null || Array.isArray(params)) return error(-32602, 'Invalid params.');
    if (request.method === 'initialize') {
      const supported = ['2024-11-05', '2025-03-26', '2025-06-18'];
      initialized = true;
      return result({ protocolVersion: supported.includes(params.protocolVersion) ? params.protocolVersion : '2025-06-18',
        capabilities: { tools: { listChanged: false } }, serverInfo: { name: 's23-power', version: '2.0.0-alpha1' },
        instructions: 'Read power data, explain uncertainty, and ask the owner before each write. Never auto-approve confirm:true. Force-stop cannot be undone. Missing measurements are unknown. Do not claim savings from cumulative counters.' });
    }
    if (request.method === 'ping') return result({});
    if (!initialized) return error(-32002, 'Initialize first.');
    if (request.method === 'tools/list') return result({ tools: toolDefinitions });
    if (request.method !== 'tools/call') return error(-32601, 'Method not found.');
    if (typeof params.name !== 'string') return error(-32602, 'Tool name is required.');
    try {
      const value = await tools.call(params.name, params.arguments ?? {});
      return result({ content: [{ type: 'text', text: JSON.stringify(value) }], isError: value.status === 'NEEDS_REVIEW' });
    } catch (e) {
      // Local errors contain no raw device output, request dumps or credentials.
      const message = e instanceof Error && !('code' in e) ? e.message : 'Tool failed. Check adb connection and journal storage; do not automatically retry writes.';
      return result({ isError: true, content: [{ type: 'text', text: message }] });
    }
  };
}

export function serveStdio(input, output, errors, handle) {
  let buffer = '', pending = 0, chain = Promise.resolve();
  input.setEncoding('utf8');
  input.on('data', chunk => {
    buffer += chunk;
    let end;
    while ((end = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, end); buffer = buffer.slice(end + 1);
      if (!line.trim()) continue;
      if (Buffer.byteLength(line) > 65536 || pending >= 16) { errors.write('MCP input limit exceeded.\n'); input.destroy(); return; }
      pending++;
      chain = chain.then(async () => {
        let request;
        try { request = JSON.parse(line); } catch {
          output.write(JSON.stringify({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'Invalid JSON.' } }) + '\n'); return;
        }
        const response = await handle(request);
        if (response !== null) output.write(JSON.stringify(response) + '\n');
      }).catch(() => { errors.write('MCP request failed.\n'); }).finally(() => { pending--; });
    }
    if (Buffer.byteLength(buffer) > 65536) { errors.write('MCP input limit exceeded.\n'); input.destroy(); }
  });
  return new Promise(resolveDone => {
    input.on('end', () => { chain.finally(resolveDone); });
    input.on('close', () => { chain.finally(resolveDone); });
  });
}

export function startServer() {
  const serial = process.env.ANDROID_SERIAL ?? '';
  const journalPath = process.env.S23_POWER_JOURNAL || join(homedir(), '.local', 'state', 's23-power', 'journal.jsonl');
  const handle = createHandler({
    run: createAdbRunner(serial), print: line => process.stderr.write(line + '\n'), record: createJournal(journalPath),
    commandPrefix: ['adb', ...(serial ? ['-s', serial] : []), 'shell'],
  });
  return serveStdio(process.stdin, process.stdout, process.stderr, handle);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) startServer();
