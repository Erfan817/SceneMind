#!/usr/bin/env node
/**
 * httpServer.js — MCP Streamable HTTP transport (stateless request/response).
 *
 * MCP 2024-11-05 的 HTTP 传输基线：客户端 POST JSON-RPC 到 /mcp，
 * 服务端返回 application/json；通知类消息返回 202。鉴权用 Bearer token，
 * 与 stdio 模式共享同一套 handlers/tools/resources。
 *
 * 运行：
 *   SCENEMIND_MCP_TOKEN=xxx SCENEMIND_API_BASE=http://127.0.0.1:9090 \
 *   SCENEMIND_EMAIL=... SCENEMIND_PASSWORD=... node src/httpServer.js
 */

import http from 'node:http';
import { pathToFileURL } from 'node:url';
import { readConfig, SceneMindClient } from './scenemindClient.js';
import { handleLine } from './protocol.js';
import { createHandlers } from './handlers.js';

const MAX_BODY_BYTES = 1024 * 1024;

/**
 * Build the MCP HTTP server without starting it (testable factory).
 * @param {{ctx: object, token: string, logger?: Console}} options
 */
export function createMcpHttpServer({ ctx, token, logger = console }) {
  if (!token) throw new Error('SCENEMIND_MCP_TOKEN is required for HTTP transport');
  const handlers = createHandlers(ctx);

  return http.createServer(async (req, res) => {
    const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

    if (req.method === 'GET' && url.pathname === '/health') {
      return sendJson(res, 200, { status: 'UP', server: 'scenemind-mcp' });
    }
    if (url.pathname !== '/mcp') {
      return sendJson(res, 404, { error: 'not found' });
    }
    if ((req.headers.authorization || '') !== `Bearer ${token}`) {
      res.writeHead(401, {
        'WWW-Authenticate': 'Bearer',
        'Content-Type': 'application/json; charset=utf-8',
      });
      return res.end(JSON.stringify({ error: 'unauthorized' }));
    }
    if (req.method !== 'POST') {
      res.writeHead(405, { Allow: 'POST', 'Content-Type': 'application/json; charset=utf-8' });
      return res.end(JSON.stringify({ error: 'method not allowed' }));
    }

    let body = '';
    let size = 0;
    try {
      for await (const chunk of req) {
        size += chunk.length;
        if (size > MAX_BODY_BYTES) {
          res.writeHead(413, { 'Content-Type': 'application/json; charset=utf-8' });
          return res.end(JSON.stringify({ error: 'payload too large' }));
        }
        body += chunk;
      }
    } catch (error) {
      logger.error?.('[scenemind-mcp] read body failed', error);
      return sendJson(res, 400, { error: 'bad request' });
    }

    if (!body.trim()) return sendJson(res, 400, { error: 'empty body' });
    try {
      const response = await handleLine(body, handlers);
      if (response === null) {
        res.writeHead(202);
        return res.end();
      }
      return sendJson(res, 200, response);
    } catch (error) {
      logger.error?.('[scenemind-mcp] dispatch failed', error);
      return sendJson(res, 200, {
        jsonrpc: '2.0',
        id: null,
        error: { code: -32603, message: 'Internal error' },
      });
    }
  });
}

function sendJson(res, status, payload) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(payload));
}

async function main() {
  const config = readConfig(process.env);
  const token = process.env.SCENEMIND_MCP_TOKEN || '';
  if (!token) {
    console.error('[scenemind-mcp] SCENEMIND_MCP_TOKEN is required for HTTP transport');
    process.exit(1);
  }
  const port = Number(process.env.SCENEMIND_MCP_HTTP_PORT || 8090);
  const host = process.env.SCENEMIND_MCP_HTTP_HOST || '127.0.0.1';
  const server = createMcpHttpServer({
    ctx: { client: new SceneMindClient(config), config },
    token,
  });
  server.listen(port, host, () => {
    console.error(
      `[scenemind-mcp] http listening on http://${host}:${port}/mcp ` +
        `(demo=${config.demo}, auth=token)`,
    );
  });
  const shutdown = () => server.close(() => process.exit(0));
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    console.error('[scenemind-mcp] fatal', error);
    process.exit(1);
  });
}
