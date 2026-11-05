#!/usr/bin/env node
/**
 * server.js — SceneMind MCP server entrypoint (stdio transport).
 *
 * Reads newline-delimited JSON-RPC 2.0 messages from stdin, dispatches them,
 * and writes exactly one JSON-RPC message per line to stdout. ALL logs go to
 * stderr — stdout is reserved strictly for protocol messages.
 *
 * Run:
 *   SCENEMIND_DEMO=1 node src/server.js
 *   SCENEMIND_API_BASE=http://127.0.0.1:9090 SCENEMIND_TOKEN=xxx node src/server.js
 */

import readline from 'node:readline';
import { readConfig, SceneMindClient } from './scenemindClient.js';
import { handleLine, ErrorCode } from './protocol.js';
import { createHandlers } from './handlers.js';

function log(...parts) {
  try {
    process.stderr.write(`[scenemind-mcp] ${parts.join(' ')}\n`);
  } catch {
    /* never let logging break the protocol */
  }
}

const config = readConfig(process.env);
const client = new SceneMindClient(config);
const ctx = { client, config };
const handlers = createHandlers(ctx);

function writeResponse(response) {
  const messages = Array.isArray(response) ? response : [response];
  for (const message of messages) {
    if (message === null || message === undefined) continue;
    process.stdout.write(`${JSON.stringify(message)}\n`);
  }
}

// Serialize line processing so responses stay ordered even when a tool call
// performs network I/O and the client pipelines several requests.
let chain = Promise.resolve();

async function processLine(line) {
  try {
    const response = await handleLine(line, handlers);
    if (response !== null) writeResponse(response);
  } catch (error) {
    log('unexpected error while handling line:', error?.stack || String(error));
    writeResponse({
      jsonrpc: '2.0',
      id: null,
      error: { code: ErrorCode.INTERNAL_ERROR, message: 'Internal error' },
    });
  }
}

const rl = readline.createInterface({
  input: process.stdin,
  crlfDelay: Infinity,
  terminal: false,
});

rl.on('line', (line) => {
  chain = chain.then(() => processLine(line));
});

rl.on('close', () => {
  log('stdin closed, shutting down');
});

log(
  `started (demo=${config.demo}, base=${config.baseUrl}, timeoutMs=${config.timeoutMs}, ` +
    `auth=${config.token ? 'token' : config.email ? 'login' : 'none'})`,
);
