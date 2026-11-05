/**
 * Demo-mode tests: SCENEMIND_DEMO=1 must work with no backend at all.
 * SCENEMIND_API_BASE points at a deliberately dead address so any accidental
 * network call would fail the assertions below.
 */

import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startRpcServer, parseToolText } from './helpers/rpc.js';

let rpc;

before(() => {
  rpc = startRpcServer({
    env: {
      SCENEMIND_DEMO: '1',
      SCENEMIND_API_BASE: 'http://127.0.0.1:1',
      SCENEMIND_TOKEN: '',
      SCENEMIND_EMAIL: '',
      SCENEMIND_PASSWORD: '',
    },
  });
});

after(() => rpc.stop());

async function tool(name, args) {
  const resp = await rpc.request('tools/call', { name, arguments: args });
  return { resp, payload: parseToolText(resp) };
}

test('demo initialize returns a legal JSON-RPC response (acceptance smoke)', async () => {
  const resp = await rpc.request('initialize', { protocolVersion: '2024-11-05' });
  assert.equal(resp.jsonrpc, '2.0');
  assert.equal(typeof resp.id, 'number');
  assert.ok(resp.result);
  assert.equal(resp.result.serverInfo.name, 'scenemind-mcp');
});

test('demo tools/list exposes 7 tools', async () => {
  const resp = await rpc.request('tools/list', {});
  assert.equal(resp.result.tools.length, 7);
});

test('demo submit_video returns a mediaId without any network', async () => {
  const { resp, payload } = await tool('scenemind_submit_video', {
    url: 'https://example.com/demo.mp4',
    goal: '核查上线日期冲突',
  });
  assert.equal(resp.result.isError, undefined);
  assert.equal(payload.mediaId, 1001);
  assert.equal(payload.accepted, true);
});

test('demo analyze returns a 202 acceptance', async () => {
  const { resp, payload } = await tool('scenemind_analyze', {
    mediaId: 1001,
    goal: '核查上线日期冲突',
  });
  assert.equal(resp.result.isError, undefined);
  assert.equal(payload.httpStatus, 202);
  assert.equal(payload.accepted, true);
});

test('demo get_result is completed with evidence, conflicts and audit', async () => {
  const { resp, payload } = await tool('scenemind_get_result', {
    mediaId: 1001,
    goal: '核查上线日期冲突',
  });
  assert.equal(resp.result.isError, undefined);
  assert.equal(payload.state, 'COMPLETED');
  assert.equal(payload.completed, true);
  assert.ok(payload.markdown.includes('视频证据'));
  assert.equal(payload.evidence.length, 4);
  assert.equal(payload.conflictFlagged, true);
  const conflictText = JSON.stringify(payload.conflicts);
  assert.match(conflictText, /8月1日/);
  assert.match(conflictText, /8月3日/);
  assert.ok(payload.evidenceAudit);
  assert.equal(payload.evidenceAuditOrigin, 'platform-structured');
  assert.ok(payload.trace.uri.endsWith('/trace'));
});

test('demo wait_for_result completes immediately', async () => {
  const { resp, payload } = await tool('scenemind_wait_for_result', {
    mediaId: 1001,
    goal: '核查上线日期冲突',
    timeoutMs: 5000,
    pollIntervalMs: 50,
  });
  assert.equal(resp.result.isError, undefined);
  assert.equal(payload.completed, true);
  assert.equal(payload.timedOut, false);
  assert.ok(payload.polls >= 1);
});

test('demo search_evidence returns timestamped hits', async () => {
  const { resp, payload } = await tool('scenemind_search_evidence', {
    mediaId: 1001,
    query: '上线日期',
  });
  assert.equal(resp.result.isError, undefined);
  assert.ok(payload.count >= 1);
  assert.equal(payload.hits[0].source, 'ASR+OCR');
  assert.ok(payload.hits[0].timestampLabel.includes(':'));
});

test('demo ask_followup returns an answer mentioning the conflict', async () => {
  const { resp, payload } = await tool('scenemind_ask_followup', {
    mediaId: 1001,
    question: '到底哪天上线？',
    goal: '核查上线日期冲突',
  });
  assert.equal(resp.result.isError, undefined);
  assert.match(payload.answer, /8月1日/);
  assert.match(payload.answer, /8月3日/);
});

test('demo verify_evidence uses the structured evidenceAudit when available', async () => {
  const { resp, payload } = await tool('scenemind_verify_evidence', {
    mediaId: 1001,
    goal: '核查上线日期冲突',
    evidence: [
      { timestampMs: 522000, source: 'ASR', content: '新功能将于八月一日正式上线。' },
      { timestampMs: 525000, source: 'OCR', content: '上线时间：8月3日' },
    ],
  });
  assert.equal(resp.result.isError, undefined);
  assert.equal(payload.method, 'platform-structured-audit');
  assert.equal(payload.evidenceAuditAvailable, true);
  assert.match(payload.disclaimer, /不重新实现/);
  assert.equal(payload.items.length, 2);
  assert.equal(payload.items[0].verdict, 'SUPPORTED');
  assert.equal(payload.items[1].verdict, 'FLAGGED');
  assert.equal(payload.items[1].reason, 'AUDIO_VISUAL_CONFLICT');
});

test('demo resources/read report returns markdown with the 8月1日/8月3日 conflict', async () => {
  const resp = await rpc.request('resources/read', { uri: 'scenemind://task/1001/report' });
  assert.equal(resp.result.contents[0].mimeType, 'text/markdown');
  assert.match(resp.result.contents[0].text, /8月1日/);
  assert.match(resp.result.contents[0].text, /8月3日/);
});

test('demo resources/read trace returns diagnostics JSON', async () => {
  const resp = await rpc.request('resources/read', { uri: 'scenemind://task/1001/trace' });
  assert.equal(resp.result.contents[0].mimeType, 'application/json');
  const trace = JSON.parse(resp.result.contents[0].text);
  assert.equal(trace.traceId, 'demo-trace-0001');
});

test('demo ping works', async () => {
  const resp = await rpc.request('ping', {});
  assert.deepEqual(resp.result, {});
});

test('demo never leaked a non-JSON line onto stdout', () => {
  assert.equal(rpc.getStderr().includes('[non-json stdout]'), false);
});
