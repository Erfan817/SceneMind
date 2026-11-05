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
import { handleLine, MCP_PROTOCOL_VERSION, JsonRpcError, ErrorCode } from './protocol.js';
import { listTools, callTool } from './tools.js';
import { listResources, readResource } from './resources.js';

const SERVER_INFO = { name: 'scenemind-mcp', version: '1.0.0' };

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

const handlers = {
  async initialize(params) {
    return {
      protocolVersion: params?.protocolVersion || MCP_PROTOCOL_VERSION,
      capabilities: {
        tools: { listChanged: false },
        resources: { subscribe: false, listChanged: false },
      },
      serverInfo: SERVER_INFO,
      instructions:
        'SceneMind MCP：把长视频交给 SceneMind 平台做「证据可核验」的分析，' +
        '并把带时间戳 / 来源 / 原文证据的结构化结果返回给调用方 Agent。' +
        '典型流程：submit_video -> analyze -> wait_for_result / get_result。',
    };
  },

  async 'notifications/initialized'() {
    log('client sent notifications/initialized');
    return {};
  },

  async ping() {
    return {};
  },

  async 'tools/list'() {
    return { tools: listTools() };
  },

  async 'tools/call'(params) {
    const name = params?.name;
    if (typeof name !== 'string') {
      throw new JsonRpcError(ErrorCode.INVALID_PARAMS, 'tools/call 需要字符串参数 name');
    }
    return callTool(name, params?.arguments ?? {}, ctx);
  },

  async 'resources/list'() {
    return listResources();
  },

  async 'resources/read'(params) {
    return readResource(params?.uri, ctx);
  },
};

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
