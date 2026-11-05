/**
 * A dependency-free fake SceneMind REST backend for the end-to-end MCP tests.
 * It reproduces the real Result envelope `{ code, message, data }`, the 202
 * Accept semantics of /analysis/ai, and the Bearer-token requirement of the
 * AuthInterceptor. No real backend is needed.
 */

import http from 'node:http';

export const REPORT_MARKDOWN = [
  '## 发布计划核查',
  '',
  '## 核心结论',
  '- 口播称新功能 8月1日 上线，但画面 PPT 标注为 8月3日。',
  '',
  '## 视频证据',
  '- [08:42] ASR：新功能将于八月一日正式上线，敬请期待。',
  '- [08:45] OCR：上线时间：8月3日',
  '- [10:27] ASR+OCR：迭代遍历使用显式栈保存待访问节点。',
  '',
  '## 建议',
  '- 向发布方核实最终上线日期。',
].join('\n');

const DEFAULT_COMPLETED = {
  state: 'COMPLETED',
  result: REPORT_MARKDOWN,
  message: '任务完成',
};

function envelope(code, data) {
  return { code, message: code === 0 ? 'success' : 'error', data };
}

export async function startFakeBackend() {
  const state = {
    authToken: 'test-token',
    statusScript: null,
    statusIndex: 0,
    requests: [],
    evaluation: {
      structuredValid: true,
      timestampCoverageRate: 1.0,
      evidenceSupportRate: 0.9,
      claimEvidenceSupportRate: 0.85,
      criticPassed: true,
      evidenceRedundancyRate: 0.0,
      temporalSpread: 0.7,
      conflictFlagged: false,
      userAcceptanceRate: 0.0,
      feedbackSamples: 0,
      // NOTE: no structured evidenceAudit / conflicts here on purpose, so the
      // tests exercise the honest "platform aggregate signals" path.
    },
    trace: {
      traceId: 'trace-real-0001',
      taskId: 4242,
      goalDigest: 'fake',
      startedAt: '2026-01-01T00:00:00Z',
      stageDurationMs: { retrieval: 90, executor: 640 },
      counters: { modelCalls: 3, inputTokensEstimated: 2100 },
      values: { rrfK: 60 },
      estimatedCost: 0.0011,
    },
    evidenceHits: [
      {
        startMs: 522000,
        endMs: 582000,
        source: 'ASR+OCR',
        snippet: '口播 8月1日上线 / 画面 8月3日。',
        transcript: '新功能将于八月一日正式上线。',
        ocrTexts: ['上线时间：8月3日'],
      },
      {
        startMs: 627000,
        endMs: 687000,
        source: 'ASR+OCR',
        snippet: '迭代遍历使用显式栈保存待访问节点。',
        transcript: '迭代遍历使用显式栈保存待访问节点。',
        ocrTexts: ['前序遍历：根节点、左子树、右子树'],
      },
    ],
    followUpAnswer: '根据 [08:42] ASR 与 [08:45] OCR，口播为 8月1日、画面为 8月3日，存在音画冲突。',
  };

  function nextStatus() {
    if (Array.isArray(state.statusScript) && state.statusScript.length) {
      const entry = state.statusScript[Math.min(state.statusIndex, state.statusScript.length - 1)];
      state.statusIndex += 1;
      return entry;
    }
    return DEFAULT_COMPLETED;
  }

  function send(res, status, payload) {
    const body = JSON.stringify(payload);
    res.writeHead(status, {
      'Content-Type': 'application/json',
      'Content-Length': String(Buffer.byteLength(body)),
    });
    res.end(body);
  }

  const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost');
    let rawBody = '';
    req.on('data', (chunk) => {
      rawBody += chunk;
    });
    req.on('end', () => {
      state.requests.push({
        method: req.method,
        path: url.pathname,
        query: Object.fromEntries(url.searchParams),
        headers: req.headers,
        body: rawBody,
      });

      const path = url.pathname;
      const protectedPath = path.startsWith('/media/') || path.startsWith('/analysis/');
      if (protectedPath && req.headers.authorization !== `Bearer ${state.authToken}`) {
        return send(res, 401, envelope(40100, null));
      }

      let status = 200;
      let payload;
      switch (path) {
        case '/user/login':
          payload = envelope(0, { userInfo: {}, token: state.authToken });
          break;
        case '/media/upload-url':
          payload = envelope(0, {
            id: 4242,
            filename: 'fake-video.mp4',
            status: 'READY',
            coverUrl: '',
            uploadTime: '2026-01-01T00:00:00',
          });
          break;
        case '/analysis/ai':
          status = 202; // newly accepted
          payload = envelope(0, null);
          break;
        case '/analysis/analysis-status':
          payload = envelope(0, nextStatus());
          break;
        case '/analysis/agent-evaluation':
          payload = envelope(0, state.evaluation);
          break;
        case '/analysis/agent-trace':
          payload = envelope(0, state.trace);
          break;
        case '/analysis/evidence-search':
          payload = envelope(0, state.evidenceHits);
          break;
        case '/analysis/follow-up':
          payload = envelope(0, state.followUpAnswer);
          break;
        default:
          return send(res, 404, envelope(40400, null));
      }
      return send(res, status, payload);
    });
  });

  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;

  return {
    state,
    port,
    baseUrl: `http://127.0.0.1:${port}`,
    reset() {
      state.statusScript = null;
      state.statusIndex = 0;
      state.requests.length = 0;
    },
    setStatusScript(script) {
      state.statusScript = script;
      state.statusIndex = 0;
    },
    close() {
      return new Promise((resolve) => {
        if (typeof server.closeAllConnections === 'function') server.closeAllConnections();
        server.close(resolve);
      });
    },
  };
}
