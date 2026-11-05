/**
 * Test helper: spawn the MCP server as a child process and speak newline-
 * delimited JSON-RPC over its stdio. Lets tests exercise the real entrypoint
 * (initialize / tools/call / resources/read) end-to-end.
 */

import { spawn } from 'node:child_process';
import readline from 'node:readline';
import { fileURLToPath } from 'node:url';

const SERVER_PATH = fileURLToPath(new URL('../../src/server.js', import.meta.url));

export function startRpcServer({ env = {}, requestTimeoutMs = 15000 } = {}) {
  const child = spawn(process.execPath, [SERVER_PATH], {
    env: { ...process.env, ...env },
    stdio: ['pipe', 'pipe', 'pipe'],
  });

  const pending = new Map();
  const allResponses = [];
  const stderrChunks = [];
  let nextId = 1;

  child.stderr.on('data', (chunk) => stderrChunks.push(chunk));

  const rl = readline.createInterface({ input: child.stdout, crlfDelay: Infinity });
  rl.on('line', (line) => {
    if (!line.trim()) return;
    let message;
    try {
      message = JSON.parse(line);
    } catch {
      // A non-JSON line on stdout would be a protocol violation; keep it for
      // diagnostics but do not resolve any request.
      stderrChunks.push(Buffer.from(`[non-json stdout] ${line}\n`));
      return;
    }
    allResponses.push(message);
    if (message.id !== undefined && message.id !== null) {
      const key = String(message.id);
      if (pending.has(key)) {
        const { resolve, timer } = pending.get(key);
        pending.delete(key);
        clearTimeout(timer);
        resolve(message);
      }
    }
  });

  function request(method, params) {
    const id = nextId++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        pending.delete(String(id));
        reject(new Error(`RPC 超时：等待 ${method} (id=${id}) 的响应`));
      }, requestTimeoutMs);
      pending.set(String(id), { resolve, timer });
      child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`);
    });
  }

  function notification(method, params) {
    child.stdin.write(`${JSON.stringify({ jsonrpc: '2.0', method, params })}\n`);
  }

  function stop() {
    try {
      child.stdin.end();
    } catch {
      /* ignore */
    }
    try {
      child.kill();
    } catch {
      /* ignore */
    }
  }

  return {
    child,
    request,
    notification,
    stop,
    getResponses: () => allResponses.slice(),
    getStderr: () => Buffer.concat(stderrChunks).toString('utf8'),
  };
}

/** Parse the JSON payload carried by a tools/call text content. */
export function parseToolText(response) {
  if (!response || !response.result) return null;
  const text = response.result?.content?.[0]?.text;
  if (typeof text !== 'string') return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}
