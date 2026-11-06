/**
 * End-to-end MCP tests: spawn the real stdio server (src/server.js) with a
 * fake SceneMind HTTP backend, then drive initialize / tools/call /
 * resources/read over newline-delimited JSON-RPC. No network beyond localhost.
 */

import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startRpcServer, parseToolText } from './helpers/rpc.js';
import { startFakeBackend, REPORT_MARKDOWN } from './helpers/fakeBackend.js';
import { ErrorCode } from '../src/protocol.js';

let fake;
let rpc;

before(async () => {
  fake = await startFakeBackend();
  rpc = startRpcServer({
    env: {
      SCENEMIND_DEMO: '0',
      SCENEMIND_API_BASE: fake.baseUrl,
      SCENEMIND_TOKEN: 'test-token',
      SCENEMIND_TIMEOUT_MS: '5000',
    },
  });
});

after(async () => {
  rpc.stop();
  await fake.close();
});

async function tool(name, args) {
  const resp = await rpc.request('tools/call', { name, arguments: args });
  return { resp, payload: parseToolText(resp) };
}

test('initialize returns a valid MCP result with capabilities', async () => {
  const resp = await rpc.request('initialize', {
    protocolVersion: '2024-11-05',
    capabilities: {},
    clientInfo: { name: 'test-client', version: '1.0' },
  });
  assert.equal(resp.jsonrpc, '2.0');
  assert.equal(typeof resp.id, 'number');
  assert.ok(resp.result);
  assert.equal(resp.result.serverInfo.name, 'scenemind-mcp');
  assert.ok(resp.result.capabilities.tools);
  assert.ok(resp.result.capabilities.resources);
  assert.equal(typeof resp.result.protocolVersion, 'string');
});

test('ping returns an empty result object', async () => {
  const resp = await rpc.request('ping', {});
  assert.deepEqual(resp.result, {});
});

test('notifications/initialized produces no response', async () => {
  const beforeCount = rpc.getResponses().length;
  rpc.notification('notifications/initialized', {});
  const pingResp = await rpc.request('ping', {});
  const afterCount = rpc.getResponses().length;
  assert.deepEqual(pingResp.result, {});
  assert.equal(afterCount - beforeCount, 1); // only the ping reply
});

test('tools/list exposes the 10 scenemind_ tools', async () => {
  const resp = await rpc.request('tools/list', {});
  assert.equal(resp.result.tools.length, 10);
  assert.ok(resp.result.tools.every((t) => t.name.startsWith('scenemind_')));
  assert.ok(resp.result.tools.every((t) => t.inputSchema && t.inputSchema.type === 'object'));
});

test('resources/list lists the report and trace templates', async () => {
  const resp = await rpc.request('resources/list', {});
  const templates = resp.result.resourceTemplates;
  assert.ok(templates.some((t) => t.uriTemplate === 'scenemind://task/{mediaId}/report'));
  assert.ok(templates.some((t) => t.uriTemplate === 'scenemind://task/{mediaId}/trace'));
});

test('unknown method maps to -32601', async () => {
  const resp = await rpc.request('does/not/exist', {});
  assert.equal(resp.error.code, ErrorCode.METHOD_NOT_FOUND);
});

test('unknown tool maps to -32601', async () => {
  const resp = await rpc.request('tools/call', { name: 'scenemind_nope', arguments: {} });
  assert.equal(resp.error.code, ErrorCode.METHOD_NOT_FOUND);
});

test('missing required params map to -32602', async () => {
  const resp = await rpc.request('tools/call', { name: 'scenemind_analyze', arguments: {} });
  assert.equal(resp.error.code, ErrorCode.INVALID_PARAMS);
});

test('scenemind_submit_video uploads via /media/upload-url and returns mediaId', async () => {
  const { resp, payload } = await tool('scenemind_submit_video', {
    url: 'https://example.com/lecture.mp4',
    goal: '核查上线日期',
    mode: 'REVIEW',
  });
  assert.equal(resp.result.isError, undefined);
  assert.equal(payload.mediaId, 4242);
  assert.equal(payload.accepted, true);
  assert.equal(payload.mode, 'REVIEW');
  assert.equal(payload.nextStep.tool, 'scenemind_analyze');
  assert.equal(payload.nextStep.arguments.mediaId, 4242);

  const upload = fake.state.requests.find((r) => r.path === '/media/upload-url');
  assert.ok(upload, 'upload-url endpoint was called');
  assert.equal(upload.method, 'POST');
  assert.equal(upload.headers.authorization, 'Bearer test-token');
  assert.ok(upload.body.includes('url='), 'url sent as form body');
});

test('scenemind_analyze hits /analysis/ai with correct params and 202', async () => {
  const { resp, payload } = await tool('scenemind_analyze', {
    mediaId: 4242,
    goal: '核查上线日期',
    mode: 'REVIEW',
  });
  assert.equal(resp.result.isError, undefined);
  assert.equal(payload.httpStatus, 202);
  assert.equal(payload.accepted, true);
  assert.match(payload.state, /ACCEPTED/);

  const ai = fake.state.requests.find((r) => r.path === '/analysis/ai');
  assert.ok(ai);
  assert.equal(ai.method, 'POST');
  assert.equal(ai.headers.authorization, 'Bearer test-token');
  assert.equal(ai.query.id, '4242');
  assert.equal(ai.query.goal, '核查上线日期');
  assert.equal(ai.query.mode, 'REVIEW');
});

test('scenemind_get_result returns the full structured result when completed', async () => {
  fake.reset();
  const { resp, payload } = await tool('scenemind_get_result', {
    mediaId: 4242,
    goal: '核查上线日期',
    mode: 'GENERAL',
  });
  assert.equal(resp.result.isError, undefined);
  assert.equal(payload.state, 'COMPLETED');
  assert.equal(payload.completed, true);
  assert.ok(payload.markdown.includes('视频证据'));
  assert.equal(payload.evidence.length, 3);
  assert.equal(payload.evidence[0].source, 'ASR');
  assert.equal(payload.evidence[0].timestampMs, 522000);
  assert.equal(payload.evidenceOrigin, 'parsed-from-markdown-report');
  assert.ok(payload.evidenceAudit, 'evidence verification summary present');
  assert.equal(payload.evidenceAuditOrigin, 'platform-signals');
  assert.equal(payload.conflictFlagged, false);
  assert.ok(payload.trace.uri.endsWith('/trace'));
  assert.equal(payload.trace.traceId, 'trace-real-0001');

  const statusReq = fake.state.requests.find((r) => r.path === '/analysis/analysis-status');
  assert.ok(statusReq);
  const evalReq = fake.state.requests.find((r) => r.path === '/analysis/agent-evaluation');
  assert.ok(evalReq);
  const traceReq = fake.state.requests.find((r) => r.path === '/analysis/agent-trace');
  assert.ok(traceReq);
});

test('scenemind_wait_for_result polls QUEUED -> PROCESSING -> COMPLETED', async () => {
  fake.setStatusScript([
    { state: 'QUEUED', result: null, message: '任务已排队' },
    { state: 'PROCESSING', result: null, message: '正在解析视频语音和关键画面' },
    { state: 'COMPLETED', result: REPORT_MARKDOWN, message: '任务完成' },
  ]);
  const { resp, payload } = await tool('scenemind_wait_for_result', {
    mediaId: 4242,
    goal: '核查上线日期',
    timeoutMs: 5000,
    pollIntervalMs: 50,
  });
  assert.equal(resp.result.isError, undefined);
  assert.equal(payload.completed, true);
  assert.equal(payload.timedOut, false);
  assert.equal(payload.state, 'COMPLETED');
  assert.ok(payload.polls >= 3);
  assert.equal(payload.evidence.length, 3);
});

test('scenemind_wait_for_result times out and clearly marks the result unfinished', async () => {
  fake.setStatusScript([{ state: 'QUEUED', result: null, message: '任务已排队' }]);
  const { resp, payload } = await tool('scenemind_wait_for_result', {
    mediaId: 4242,
    goal: '核查上线日期',
    timeoutMs: 300,
    pollIntervalMs: 50,
  });
  assert.equal(resp.result.isError, undefined);
  assert.equal(payload.timedOut, true);
  assert.equal(payload.unfinished, true);
  assert.equal(payload.completed, false);
  assert.equal(payload.state, 'QUEUED');
  assert.match(payload.message, /未完成/);
  assert.match(payload.hint, /未完成/);
});

test('scenemind_search_evidence returns timestamped hits', async () => {
  fake.reset();
  const { resp, payload } = await tool('scenemind_search_evidence', {
    mediaId: 4242,
    query: '上线日期',
  });
  assert.equal(resp.result.isError, undefined);
  assert.equal(payload.count, 2);
  assert.equal(payload.hits[0].source, 'ASR+OCR');
  assert.equal(payload.hits[0].startMs, 522000);
  assert.match(payload.hits[0].timestampLabel, /^\d{2}:\d{2}-\d{2}:\d{2}$/);

  const search = fake.state.requests.find((r) => r.path === '/analysis/evidence-search');
  assert.ok(search);
  assert.equal(search.query.query, '上线日期');
  assert.equal(search.query.id, '4242');
});

test('scenemind_ask_followup hits /analysis/follow-up and returns an answer', async () => {
  const { resp, payload } = await tool('scenemind_ask_followup', {
    mediaId: 4242,
    question: '到底哪天上线？',
    goal: '核查上线日期',
    mode: 'REVIEW',
  });
  assert.equal(resp.result.isError, undefined);
  assert.equal(typeof payload.answer, 'string');
  assert.ok(payload.answer.length > 0);

  const followUp = fake.state.requests.find((r) => r.path === '/analysis/follow-up');
  assert.ok(followUp);
  assert.equal(followUp.method, 'POST');
  assert.equal(followUp.query.question, '到底哪天上线？');
});

test('scenemind_verify_evidence relays platform signals without fake re-verification', async () => {
  fake.reset();
  const { resp, payload } = await tool('scenemind_verify_evidence', {
    mediaId: 4242,
    goal: '核查上线日期',
    evidence: [{ timestampMs: 522000, source: 'ASR', content: '新功能将于八月一日正式上线。' }],
  });
  assert.equal(resp.result.isError, undefined);
  assert.equal(payload.method, 'platform-signals');
  assert.equal(payload.evidenceAuditAvailable, false);
  assert.match(payload.disclaimer, /不重新实现/);
  assert.equal(payload.items.length, 1);
  assert.equal(payload.items[0].verdict, 'RELAYED_FROM_PLATFORM_SIGNALS');
  assert.equal(payload.items[0].verifiable, null);
  assert.equal(payload.platformSignals.metrics.evidenceSupportRate, 0.9);

  const evalReq = fake.state.requests.find((r) => r.path === '/analysis/agent-evaluation');
  const traceReq = fake.state.requests.find((r) => r.path === '/analysis/agent-trace');
  assert.ok(evalReq && traceReq, 'reuses agent-evaluation + agent-trace');
});

test('scenemind_peek_frame returns the frame view for a timestamp', async () => {
  fake.reset();
  const { resp, payload } = await tool('scenemind_peek_frame', {
    mediaId: 4242,
    timestampMs: 627000,
  });
  assert.equal(resp.result.isError, undefined);
  assert.equal(payload.timestampMs, 627000);
  assert.equal(payload.frameUrl, 'http://minio/fake/frame_000010.jpg');
  assert.equal(payload.source, 'ASR+OCR');
  assert.match(payload.transcript, /显式栈/);
  assert.deepEqual(payload.ocrTexts, ['前序遍历：根节点、左子树、右子树']);

  const frame = fake.state.requests.find((r) => r.path === '/analysis/frame');
  assert.ok(frame);
  assert.equal(frame.query.id, '4242');
  assert.equal(frame.query.timestampMs, '627000');
});

test('scenemind_get_transcript returns overlapping slices with labels', async () => {
  fake.reset();
  const { resp, payload } = await tool('scenemind_get_transcript', {
    mediaId: 4242,
    startMs: 520000,
    endMs: 640000,
  });
  assert.equal(resp.result.isError, undefined);
  assert.equal(payload.count, 2);
  assert.equal(payload.segments[0].startMs, 522000);
  assert.match(payload.segments[0].timestampLabel, /^\d{2}:\d{2}-\d{2}:\d{2}$/);
  assert.deepEqual(payload.segments[1].ocrTexts, ['前序遍历：根节点、左子树、右子树']);

  const tx = fake.state.requests.find((r) => r.path === '/analysis/transcript');
  assert.ok(tx);
  assert.equal(tx.query.startMs, '520000');
  assert.equal(tx.query.endMs, '640000');
});

test('scenemind_get_timeline returns chapter summaries and labels', async () => {
  fake.reset();
  const { resp, payload } = await tool('scenemind_get_timeline', { mediaId: 4242 });
  assert.equal(resp.result.isError, undefined);
  assert.equal(payload.count, 2);
  assert.equal(payload.durationMs, 709000);
  assert.equal(payload.entries[0].startMs, 0);
  assert.match(payload.entries[0].timestampLabel, /^\d{2}:\d{2}-\d{2}:\d{2}$/);
  assert.equal(payload.entries[1].keywords[0], '递归');

  const timeline = fake.state.requests.find((r) => r.path === '/analysis/timeline');
  assert.ok(timeline);
  assert.equal(timeline.query.id, '4242');
});

test('scenemind_get_transcript rejects endMs <= startMs with -32602', async () => {
  const resp = await rpc.request('tools/call', {
    name: 'scenemind_get_transcript',
    arguments: { mediaId: 4242, startMs: 5000, endMs: 1000 },
  });
  assert.equal(resp.error.code, ErrorCode.INVALID_PARAMS);
});

test('resources/read report returns markdown with evidence', async () => {
  fake.reset();
  const resp = await rpc.request('resources/read', { uri: 'scenemind://task/4242/report' });
  assert.equal(resp.result.contents[0].mimeType, 'text/markdown');
  assert.ok(resp.result.contents[0].text.includes('视频证据'));
});

test('resources/read trace returns diagnostics JSON', async () => {
  const resp = await rpc.request('resources/read', {
    uri: 'scenemind://task/4242/trace?goal=%E6%A0%B8%E6%9F%A5&mode=REVIEW',
  });
  assert.equal(resp.result.contents[0].mimeType, 'application/json');
  const trace = JSON.parse(resp.result.contents[0].text);
  assert.equal(trace.traceId, 'trace-real-0001');
});

test('resources/read rejects a malformed URI with -32602', async () => {
  const resp = await rpc.request('resources/read', { uri: 'scenemind://bad-uri' });
  assert.equal(resp.error.code, ErrorCode.INVALID_PARAMS);
});

test('resources/read rejects an unknown resource kind with -32002', async () => {
  const resp = await rpc.request('resources/read', { uri: 'scenemind://task/4242/unknown' });
  assert.equal(resp.error.code, ErrorCode.RESOURCE_NOT_FOUND);
});

test('SCENEMIND_EMAIL/SCENEMIND_PASSWORD triggers lazy login then injects the token', async () => {
  fake.reset();
  const loginRpc = startRpcServer({
    env: {
      SCENEMIND_DEMO: '0',
      SCENEMIND_API_BASE: fake.baseUrl,
      SCENEMIND_TOKEN: '',
      SCENEMIND_EMAIL: 'demo-user',
      SCENEMIND_PASSWORD: 'demo-pass',
      SCENEMIND_TIMEOUT_MS: '5000',
    },
  });
  try {
    const resp = await loginRpc.request('tools/call', {
      name: 'scenemind_submit_video',
      arguments: { url: 'https://example.com/login.mp4', goal: '核查上线日期' },
    });
    assert.equal(resp.result.isError, undefined);
    const payload = parseToolText(resp);
    assert.equal(payload.mediaId, 4242);

    const loginReq = fake.state.requests.find((r) => r.path === '/user/login');
    assert.ok(loginReq, '/user/login was called');
    assert.equal(loginReq.method, 'POST');
    assert.equal(loginReq.headers.authorization, undefined, 'login must not need an auth header');
    assert.match(loginReq.body, /demo-user/);
    assert.equal(JSON.parse(loginReq.body).password, 'demo-pass');

    const uploadReq = fake.state.requests.find((r) => r.path === '/media/upload-url');
    assert.equal(uploadReq.headers.authorization, 'Bearer test-token');
  } finally {
    loginRpc.stop();
  }
});
