import assert from 'node:assert/strict';
import test from 'node:test';
import { createMcpHttpServer } from '../src/httpServer.js';
import { readConfig, SceneMindClient } from '../src/scenemindClient.js';

function createServer(token = 'test-token') {
  const config = readConfig({ SCENEMIND_DEMO: '1' });
  const server = createMcpHttpServer({
    ctx: { client: new SceneMindClient(config), config },
    token,
    logger: { error() {} },
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      resolve({ server, base: `http://127.0.0.1:${port}` });
    });
  });
}

test('HTTP transport requires a bearer token', async () => {
  const { server, base } = await createServer();
  try {
    const response = await fetch(`${base}/mcp`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/list' }),
    });
    assert.equal(response.status, 401);
    assert.equal(response.headers.get('www-authenticate'), 'Bearer');
  } finally {
    server.close();
  }
});

test('HTTP transport answers tools/list with the same handlers as stdio', async () => {
  const { server, base } = await createServer();
  try {
    const response = await fetch(`${base}/mcp`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer test-token',
      },
      body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/list' }),
    });
    assert.equal(response.status, 200);
    const payload = await response.json();
    assert.equal(payload.jsonrpc, '2.0');
    assert.equal(payload.id, 1);
    assert.equal(payload.result.tools.length, 7);
  } finally {
    server.close();
  }
});

test('HTTP transport returns 202 for notifications', async () => {
  const { server, base } = await createServer();
  try {
    const response = await fetch(`${base}/mcp`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer test-token',
      },
      body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }),
    });
    assert.equal(response.status, 202);
  } finally {
    server.close();
  }
});

test('HTTP transport exposes an unauthenticated health endpoint', async () => {
  const { server, base } = await createServer();
  try {
    const response = await fetch(`${base}/health`);
    assert.equal(response.status, 200);
    const payload = await response.json();
    assert.equal(payload.status, 'UP');
  } finally {
    server.close();
  }
});
