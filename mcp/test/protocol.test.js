import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  JSONRPC_VERSION,
  MCP_PROTOCOL_VERSION,
  ErrorCode,
  JsonRpcError,
  ok,
  err,
  parseMessage,
  isNotification,
  createDispatcher,
  handleLine,
} from '../src/protocol.js';

test('parseMessage returns the parsed object for a valid JSON-RPC message', () => {
  const line = JSON.stringify({ jsonrpc: '2.0', id: 7, method: 'ping' });
  const message = parseMessage(line);
  assert.equal(message.id, 7);
  assert.equal(message.method, 'ping');
});

test('parseMessage throws -32700 on malformed JSON', () => {
  assert.throws(
    () => parseMessage('{not json'),
    (error) => error instanceof JsonRpcError && error.code === ErrorCode.PARSE_ERROR,
  );
});

test('parseMessage throws -32600 for a non-object / wrong jsonrpc payload', () => {
  assert.throws(
    () => parseMessage('[1,2,3]'),
    (error) => error instanceof JsonRpcError && error.code === ErrorCode.INVALID_REQUEST,
  );
  assert.throws(
    () => parseMessage('{"jsonrpc":"1.0","method":"ping"}'),
    (error) => error instanceof JsonRpcError && error.code === ErrorCode.INVALID_REQUEST,
  );
});

test('ok / err build correctly shaped JSON-RPC envelopes', () => {
  assert.deepEqual(ok(1, { a: 1 }), { jsonrpc: JSONRPC_VERSION, id: 1, result: { a: 1 } });
  assert.deepEqual(ok(2, undefined), { jsonrpc: JSONRPC_VERSION, id: 2, result: {} });
  assert.deepEqual(err(3, ErrorCode.INVALID_PARAMS, 'bad'), {
    jsonrpc: JSONRPC_VERSION,
    id: 3,
    error: { code: -32602, message: 'bad' },
  });
  assert.deepEqual(err(null, ErrorCode.PARSE_ERROR, 'parse'), {
    jsonrpc: JSONRPC_VERSION,
    id: null,
    error: { code: -32700, message: 'parse' },
  });
});

test('isNotification detects messages with a method but no id', () => {
  assert.equal(isNotification({ jsonrpc: '2.0', method: 'notifications/initialized' }), true);
  assert.equal(isNotification({ jsonrpc: '2.0', id: 1, method: 'ping' }), false);
  assert.equal(isNotification({ jsonrpc: '2.0', id: null, method: 'ping' }), false);
});

test('MCP protocol version constant is defined', () => {
  assert.equal(typeof MCP_PROTOCOL_VERSION, 'string');
  assert.ok(MCP_PROTOCOL_VERSION.length > 0);
});

test('dispatcher resolves a request through its handler', async () => {
  const handlers = { ping: async () => ({ pong: true }) };
  const response = await createDispatcher(handlers)({ jsonrpc: '2.0', id: 9, method: 'ping' });
  assert.deepEqual(response, { jsonrpc: '2.0', id: 9, result: { pong: true } });
});

test('dispatcher maps unknown method to -32601', async () => {
  const response = await createDispatcher({})({ jsonrpc: '2.0', id: 1, method: 'nope' });
  assert.equal(response.error.code, ErrorCode.METHOD_NOT_FOUND);
  assert.equal(response.id, 1);
});

test('dispatcher maps JsonRpcError to its own code (-32602)', async () => {
  const handlers = {
    boom: async () => {
      throw new JsonRpcError(ErrorCode.INVALID_PARAMS, 'missing goal', { field: 'goal' });
    },
  };
  const response = await createDispatcher(handlers)({ jsonrpc: '2.0', id: 4, method: 'boom' });
  assert.equal(response.error.code, ErrorCode.INVALID_PARAMS);
  assert.equal(response.error.message, 'missing goal');
  assert.deepEqual(response.error.data, { field: 'goal' });
});

test('dispatcher maps a generic throw to -32603', async () => {
  const handlers = {
    boom: async () => {
      throw new Error('kaboom');
    },
  };
  const response = await createDispatcher(handlers)({ jsonrpc: '2.0', id: 5, method: 'boom' });
  assert.equal(response.error.code, ErrorCode.INTERNAL_ERROR);
  assert.equal(response.error.message, 'kaboom');
});

test('dispatcher drops notifications (returns null) but still runs the handler', async () => {
  let called = 0;
  const handlers = {
    'notifications/initialized': async () => {
      called += 1;
      return {};
    },
  };
  const response = await createDispatcher(handlers)({
    jsonrpc: '2.0',
    method: 'notifications/initialized',
  });
  assert.equal(response, null);
  assert.equal(called, 1);
});

test('handleLine returns a -32700 response (id null) for a parse error', async () => {
  const response = await handleLine('{oops', {});
  assert.equal(response.error.code, ErrorCode.PARSE_ERROR);
  assert.equal(response.id, null);
});

test('handleLine returns null for blank lines', async () => {
  assert.equal(await handleLine('   ', {}), null);
  assert.equal(await handleLine('', {}), null);
});

test('handleLine handles a batch and drops notification results', async () => {
  const handlers = {
    ping: async () => ({}),
    'notifications/initialized': async () => ({}),
  };
  const batch = JSON.stringify([
    { jsonrpc: '2.0', id: 1, method: 'ping' },
    { jsonrpc: '2.0', method: 'notifications/initialized' },
    { jsonrpc: '2.0', id: 2, method: 'ping' },
  ]);
  const responses = await handleLine(batch, handlers);
  assert.ok(Array.isArray(responses));
  assert.equal(responses.length, 2);
  assert.deepEqual(responses.map((r) => r.id), [1, 2]);
});
