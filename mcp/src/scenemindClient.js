/**
 * scenemindClient.js — SceneMind business layer.
 *
 * Maps 1:1 onto the REAL Spring Boot REST endpoints (see the controllers under
 * server/.../controller). No invented endpoints:
 *
 *   POST /user/login              (JSON {username,password}) -> {token}   [no auth header]
 *   POST /media/upload-url        (form url)                 -> MediaSummary
 *   POST /analysis/ai             (?id&goal&mode)            -> 202/200 Result<Void>
 *   GET  /analysis/analysis-status(?id&goal&mode)            -> TaskStatus {state,result,message}
 *   GET  /analysis/agent-evaluation(?id&goal&mode)           -> aggregate metrics map
 *   GET  /analysis/agent-trace   (?id&goal&mode)             -> retrieval diagnostics map
 *   GET  /analysis/evidence-search(?id&query)                -> List<VideoEvidenceHit>
 *   POST /analysis/follow-up     (?id&question&goal&mode)     -> Result<String>
 *   GET  /analysis/frame         (?id&timestampMs)            -> Result<FrameView>
 *   GET  /analysis/transcript    (?id&startMs&endMs)          -> Result<List<TranscriptView>>
 *   GET  /analysis/timeline      (?id)                        -> Result<TimelineView>
 *
 * All protected endpoints require `Authorization: Bearer <token>` (see
 * AuthInterceptor / AuthService.bearerToken). The token is either supplied via
 * SCENEMIND_TOKEN or obtained lazily from SCENEMIND_EMAIL/PASSWORD via login.
 *
 * When config.demo is true, every low-level method returns synthetic data from
 * demoData.js and NO socket is ever opened.
 *
 * Beyond the CRUD-ish methods, this file owns the *aggregation* that turns the
 * platform's separate status/evaluation/trace responses into one structured
 * result object shared by scenemind_get_result / scenemind_wait_for_result.
 * Crucially, it does NOT re-implement evidence verification: it only relays the
 * platform's existing verification signals (agent-evaluation / agent-trace).
 */

import { createHttpClient, BackendError } from './httpClient.js';
import * as demo from './demoData.js';
import { DEFAULT_GOAL } from './demoData.js';

export const MODES = ['GENERAL', 'LEARNING', 'REVIEW', 'CREATION'];

/** Read all configuration from environment variables (no other config source). */
export function readConfig(env = process.env) {
  const rawTimeout = Number.parseInt(env.SCENEMIND_TIMEOUT_MS, 10);
  return {
    baseUrl: (env.SCENEMIND_API_BASE || 'http://127.0.0.1:9090').replace(/\/$/, ''),
    token: env.SCENEMIND_TOKEN || null,
    email: env.SCENEMIND_EMAIL || null,
    password: env.SCENEMIND_PASSWORD || null,
    demo: env.SCENEMIND_DEMO === '1' || String(env.SCENEMIND_DEMO).toLowerCase() === 'true',
    timeoutMs: Number.isFinite(rawTimeout) && rawTimeout > 0 ? Math.min(rawTimeout, 600000) : 15000,
  };
}

export class SceneMindClient {
  constructor(config) {
    this.config = config;
    this.demo = config.demo;
    this.token = config.token || null;
    this._loginPromise = null;
    this.http = createHttpClient({
      baseUrl: config.baseUrl,
      timeoutMs: config.timeoutMs,
      getToken: async () => this.token,
    });
  }

  /** POST /user/login — the only call that must NOT carry an auth header. */
  async login() {
    const res = await this.http({
      method: 'POST',
      path: '/user/login',
      body: { username: this.config.email, password: this.config.password },
      auth: false,
    });
    const token = res.data?.token;
    if (!token) throw new BackendError('登录成功但响应缺少 token', { status: res.status });
    this.token = token;
    return token;
  }

  /** Resolve credentials lazily; dedupe concurrent logins. */
  async ensureAuth() {
    if (this.demo || this.token) return;
    if (this.config.email && this.config.password) {
      if (!this._loginPromise) {
        this._loginPromise = this.login().finally(() => {
          this._loginPromise = null;
        });
      }
      await this._loginPromise;
    }
    // No credentials: proceed without Authorization (best effort). A real
    // backend will answer 401, which is surfaced as a BackendError.
  }

  async call(opts) {
    await this.ensureAuth();
    return this.http({ ...opts, auth: true });
  }

  // ---- low-level endpoint wrappers (demo-aware) --------------------------

  async submitUrl(url) {
    if (this.demo) return demo.submitUrl(url);
    const res = await this.call({ method: 'POST', path: '/media/upload-url', form: { url } });
    return res.data;
  }

  async analyze(mediaId, goal, mode) {
    if (this.demo) return demo.analyze();
    const res = await this.call({
      method: 'POST',
      path: '/analysis/ai',
      query: { id: mediaId, goal, mode: mode || 'GENERAL' },
    });
    return { status: res.status, accepted: true, alreadyCompleted: res.status === 200 };
  }

  async status(mediaId, goal, mode) {
    if (this.demo) return demo.status();
    const res = await this.call({
      path: '/analysis/analysis-status',
      query: { id: mediaId, goal, mode: mode || 'GENERAL' },
    });
    return res.data || { state: 'NOT_STARTED', result: null, message: '' };
  }

  async evaluation(mediaId, goal, mode) {
    if (this.demo) return demo.evaluation();
    const res = await this.call({
      path: '/analysis/agent-evaluation',
      query: { id: mediaId, goal, mode: mode || 'GENERAL' },
    });
    return res.data && typeof res.data === 'object' ? res.data : {};
  }

  async trace(mediaId, goal, mode) {
    if (this.demo) return demo.trace();
    const res = await this.call({
      path: '/analysis/agent-trace',
      query: { id: mediaId, goal, mode: mode || 'GENERAL' },
    });
    return res.data && typeof res.data === 'object' ? res.data : {};
  }

  async evidenceSearch(mediaId, query) {
    if (this.demo) return demo.evidenceSearch(query);
    const res = await this.call({
      path: '/analysis/evidence-search',
      query: { id: mediaId, query },
    });
    return Array.isArray(res.data) ? res.data : [];
  }

  async followUp(mediaId, question, goal, mode) {
    if (this.demo) return demo.followUp(question);
    const query = { id: mediaId, question };
    if (goal) query.goal = goal;
    if (mode) query.mode = mode;
    const res = await this.call({ method: 'POST', path: '/analysis/follow-up', query });
    if (typeof res.data === 'string') return res.data;
    return res.data == null ? '' : JSON.stringify(res.data);
  }

  /** GET /analysis/frame -> Result<FrameView>.data */
  async frameView(mediaId, timestampMs) {
    if (this.demo) return demo.frameView(timestampMs);
    const res = await this.call({
      path: '/analysis/frame',
      query: { id: mediaId, timestampMs },
    });
    return res.data == null ? null : res.data;
  }

  /** GET /analysis/transcript -> Result<List<TranscriptView>>.data */
  async transcriptRange(mediaId, startMs, endMs) {
    if (this.demo) return demo.transcript(startMs, endMs);
    const res = await this.call({
      path: '/analysis/transcript',
      query: { id: mediaId, startMs, endMs },
    });
    return Array.isArray(res.data) ? res.data : [];
  }

  /** GET /analysis/timeline -> Result<TimelineView>.data */
  async timeline(mediaId) {
    if (this.demo) return demo.timeline();
    const res = await this.call({
      path: '/analysis/timeline',
      query: { id: mediaId },
    });
    return res.data == null ? null : res.data;
  }

  // ---- aggregation -------------------------------------------------------

  /**
   * Build the single structured result shared by get_result / wait_for_result /
   * the report resource. It combines status + evaluation + trace and never
   * waits for completion on its own.
   */
  async getResult(mediaId, goal, mode) {
    const resolvedGoal = goal || DEFAULT_GOAL;
    const resolvedMode = mode || 'GENERAL';
    const statusData = await this.status(mediaId, resolvedGoal, resolvedMode);

    const state = statusData?.state || 'NOT_STARTED';
    const completed = state === 'COMPLETED';
    const failed = state === 'FAILED';
    const markdown = typeof statusData?.result === 'string' ? statusData.result : null;
    const message = statusData?.message || '';

    let evaluationData = null;
    let traceData = null;
    if (completed) {
      const [ev, tr] = await Promise.allSettled([
        this.evaluation(mediaId, resolvedGoal, resolvedMode),
        this.trace(mediaId, resolvedGoal, resolvedMode),
      ]);
      if (ev.status === 'fulfilled') evaluationData = ev.value;
      if (tr.status === 'fulfilled') traceData = tr.value;
    }

    return buildStructuredResult({
      mediaId,
      goal: resolvedGoal,
      mode: resolvedMode,
      state,
      completed,
      failed,
      markdown,
      message,
      statusData,
      evaluationData,
      traceData,
    });
  }

  /**
   * Honest evidence verification: relay the platform's EXISTING verification
   * signals (agent-evaluation / agent-trace, and a structured evidenceAudit if
   * the backend provides one). It deliberately does NOT re-run a per-snippet
   * pseudo-verification in the client — the VideoContext corpus is not exposed
   * over REST, and the server-side EvidenceAuditor/Critic already did that work.
   */
  async verifyEvidence(mediaId, goal, evidence, mode) {
    const resolvedGoal = goal || DEFAULT_GOAL;
    const resolvedMode = mode || 'GENERAL';
    const [ev, tr] = await Promise.allSettled([
      this.evaluation(mediaId, resolvedGoal, resolvedMode),
      this.trace(mediaId, resolvedGoal, resolvedMode),
    ]);
    const evaluationData = ev.status === 'fulfilled' ? ev.value : null;
    const traceData = tr.status === 'fulfilled' ? tr.value : null;

    const structuredAudit =
      evaluationData && evaluationData.evidenceAudit && typeof evaluationData.evidenceAudit === 'object'
        ? evaluationData.evidenceAudit
        : null;
    const signals = extractPlatformSignals(evaluationData, traceData);

    const items = (Array.isArray(evidence) ? evidence : []).map((item, index) => {
      const base = {
        index,
        timestampMs: item.timestampMs,
        source: item.source,
        content: item.content,
      };
      if (structuredAudit) {
        const verdict = findStructuredVerdict(structuredAudit, item);
        if (verdict) {
          return {
            ...base,
            verdict: verdict.verdict || 'UNKNOWN',
            reason: verdict.reason || null,
            note: verdict.detail || null,
            verdictOrigin: 'platform-structured-evidenceAudit',
            verifiable: verdict.verdict !== 'UNKNOWN' && verdict.verdict !== 'NO_PLATFORM_VERDICT',
          };
        }
        return {
          ...base,
          verdict: 'NO_PLATFORM_VERDICT',
          reason: null,
          note: '平台结构化 evidenceAudit 中没有与该时间戳/来源匹配的条目，无法给出逐条判定。',
          verdictOrigin: 'platform-structured-evidenceAudit',
          verifiable: false,
        };
      }
      return {
        ...base,
        verdict: 'RELAYED_FROM_PLATFORM_SIGNALS',
        reason: null,
        note:
          '客户端不对单条证据重新实现校验。该证据是否"可核验"由平台服务端 EvidenceAuditor/Critic 判定；' +
          '此处仅转述下方 platformSignals 中的聚合校验信号，无法给出独立的逐条布尔结论。',
        verdictOrigin: 'platform-aggregate-signals',
        verifiable: null, // unknown: requires the server-side corpus
      };
    });

    return {
      mediaId,
      goal: resolvedGoal,
      mode: resolvedMode,
      method: structuredAudit ? 'platform-structured-audit' : 'platform-signals',
      disclaimer:
        '本判定完全基于平台 /analysis/agent-evaluation 与 /analysis/agent-trace 已产生的校验信号' +
        '（服务端 EvidenceAuditor / Critic / ConflictDetector 的聚合结果）。MCP 客户端不重新实现一套' +
        '伪校验，也不持有 VideoContext 原始语料，因此不对每条证据独立做逐字 / 逐通道 / 时间戳越界的' +
        '重验；下面的 verdict 是对平台信号的转述与匹配。若平台提供了结构化 evidenceAudit，则直接' +
        '采用其中的逐条 verdict。',
      evidenceAuditAvailable: Boolean(structuredAudit),
      platformSignals: signals,
      conflicts: collectConflicts(evaluationData, traceData, null).conflicts,
      items,
      trace: {
        uri: `scenemind://task/${mediaId}/trace`,
        traceId: traceData?.traceId ?? null,
        hint: '可读取该资源 URI 查看检索诊断（通道名次、覆盖时间窗、Token / 成本等）。',
      },
    };
  }
}

// ---- pure aggregation helpers (exported for reuse / testing) -------------

const AGGREGATE_KEYS = [
  'structuredValid',
  'timestampCoverageRate',
  'evidenceSupportRate',
  'claimEvidenceSupportRate',
  'citationPrecision',
  'citationRecall',
  'citationF1',
  'criticPassed',
  'evidenceRedundancyRate',
  'temporalSpread',
  'conflictFlagged',
  'userAcceptanceRate',
  'feedbackSamples',
];

export function extractPlatformSignals(evaluationData, traceData) {
  const metrics = {};
  if (evaluationData && typeof evaluationData === 'object') {
    for (const key of AGGREGATE_KEYS) {
      if (key in evaluationData) metrics[key] = evaluationData[key];
    }
  }
  return {
    source: 'SceneMind /analysis/agent-evaluation + /analysis/agent-trace (平台已有校验信号)',
    metrics,
    traceId: traceData?.traceId ?? null,
    note:
      '这些是平台服务端证据校验器 / Critic / 冲突检测器输出的聚合指标，' +
      '不是 MCP 客户端的独立重校验结果。',
  };
}

function labelToMs(label) {
  const parts = String(label).split(':').map((p) => Number.parseInt(p, 10) || 0);
  if (parts.length === 3) return (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000;
  if (parts.length === 2) return (parts[0] * 60 + parts[1]) * 1000;
  return parts[0] * 1000;
}

/** Parse the `## 视频证据` section of the platform markdown report. */
export function parseEvidenceFromMarkdown(markdown) {
  if (typeof markdown !== 'string') return [];
  const result = [];
  let inSection = false;
  for (const line of markdown.split('\n')) {
    const trimmed = line.trim();
    if (/^##\s+视频证据/.test(trimmed)) {
      inSection = true;
      continue;
    }
    if (inSection && /^##\s+/.test(trimmed)) inSection = false;
    if (!inSection) continue;
    const m = trimmed.match(/^-\s*\[(\d{1,3}:\d{2}(?::\d{2})?)\]\s*([^：:]+?)\s*[：:]\s*(.*)$/);
    if (m) {
      result.push({
        timestampLabel: `[${m[1]}]`,
        timestampMs: labelToMs(m[1]),
        source: m[2].trim(),
        content: m[3].trim(),
      });
    }
  }
  return result;
}

function collectEvidence(statusData, evaluationData, markdown) {
  if (Array.isArray(statusData?.evidence) && statusData.evidence.length) {
    return { evidence: statusData.evidence, origin: 'platform-structured' };
  }
  if (Array.isArray(evaluationData?.evidence) && evaluationData.evidence.length) {
    return { evidence: evaluationData.evidence, origin: 'platform-structured' };
  }
  const parsed = parseEvidenceFromMarkdown(markdown);
  return { evidence: parsed, origin: parsed.length ? 'parsed-from-markdown-report' : 'none' };
}

function collectConflicts(evaluationData, traceData, statusData) {
  // The platform may surface conflicts either at the top level of a response
  // or nested inside a structured evidenceAudit (e.g. EvidenceAuditReport).
  const candidates = [
    [evaluationData, 'agent-evaluation'],
    [evaluationData?.evidenceAudit, 'agent-evaluation.evidenceAudit'],
    [traceData, 'agent-trace'],
    [traceData?.evidenceAudit, 'agent-trace.evidenceAudit'],
    [statusData, 'analysis-status'],
    [statusData?.evidenceAudit, 'analysis-status.evidenceAudit'],
  ];
  for (const [holder, label] of candidates) {
    if (holder && Array.isArray(holder.conflicts) && holder.conflicts.length) {
      return { conflicts: holder.conflicts, origin: `platform-structured (${label})` };
    }
  }
  if (evaluationData?.conflictFlagged === true) {
    return {
      conflicts: [
        {
          flagged: true,
          note: '平台检测到音画冲突（conflictFlagged=true）；详细冲突条目由平台持有，可查看 report / trace 资源。',
        },
      ],
      origin: 'platform-flag (agent-evaluation.conflictFlagged)',
    };
  }
  return { conflicts: [], origin: 'none' };
}

function collectEvidenceAudit(evaluationData, statusData, traceData) {
  for (const holder of [evaluationData, statusData, traceData]) {
    if (holder?.evidenceAudit && typeof holder.evidenceAudit === 'object') {
      return { audit: holder.evidenceAudit, origin: 'platform-structured' };
    }
  }
  const signals = extractPlatformSignals(evaluationData, null);
  if (evaluationData && Object.keys(signals.metrics).length) {
    return {
      audit: {
        ...signals.metrics,
        source: signals.source,
        note: signals.note,
        signalsFrom: ['/analysis/agent-evaluation', '/analysis/agent-trace'],
      },
      origin: 'platform-signals',
    };
  }
  return { audit: null, origin: 'none' };
}

/**
 * Normalize the several per-item verdict shapes the platform may use:
 *  - illustrative / legacy: { timestampMs, source, verdict, reason, detail }
 *  - EvidenceAuditReport.EvidenceFinding:
 *      { timestampMs, channel, accepted, reason, retryable, similarity }
 */
function normalizeVerdict(entry) {
  if (typeof entry.verdict === 'string') {
    return {
      verdict: entry.verdict,
      reason: entry.reason ?? null,
      detail: entry.detail ?? null,
    };
  }
  if (typeof entry.accepted === 'boolean') {
    return {
      verdict: entry.accepted ? 'SUPPORTED' : 'REJECTED',
      reason: entry.reason ?? null,
      detail: entry.channel ? `channel=${entry.channel}` : null,
    };
  }
  return { verdict: 'UNKNOWN', reason: null, detail: null };
}

function findStructuredVerdict(audit, item) {
  const list = Array.isArray(audit.items)
    ? audit.items
    : Array.isArray(audit.findings)
      ? audit.findings
      : null;
  if (!list) return null;
  let best = null;
  let bestDist = Infinity;
  for (const entry of list) {
    const entrySource = String(entry.source ?? entry.channel ?? '').toUpperCase();
    const sameSource = entrySource === String(item.source || '').toUpperCase();
    const dist = Math.abs(Number(entry.timestampMs) - Number(item.timestampMs));
    if (sameSource && dist === 0) return entry;
    if (dist < bestDist && dist <= 2000) {
      best = entry;
      bestDist = dist;
    }
  }
  return best;
}

export function buildStructuredResult(input) {
  const {
    mediaId,
    goal,
    mode,
    state,
    completed,
    failed,
    markdown,
    message,
    statusData,
    evaluationData,
    traceData,
  } = input;

  const evidence = collectEvidence(statusData, evaluationData, markdown);
  const conflicts = collectConflicts(evaluationData, traceData, statusData);
  const audit = collectEvidenceAudit(evaluationData);
  const conflictFlagged =
    (evaluationData?.conflictFlagged === true) || conflicts.conflicts.length > 0;

  let hint;
  if (completed) {
    hint =
      '结果已完成：markdown 内含带时间戳 / 来源 / 原文的证据；证据核验摘要来自平台已有校验信号；' +
      '可用 scenemind_verify_evidence 查看逐条核验说明。';
  } else if (failed) {
    hint = '任务失败：请查看 message，必要时调整 goal 后重新调用 scenemind_analyze。';
  } else {
    hint =
      '任务尚未完成：这里返回的是当前状态与阶段（未死等）。' +
      '请用 scenemind_wait_for_result 轮询，或稍后再次 scenemind_get_result。';
  }

  return {
    mediaId,
    goal,
    mode,
    state,
    completed,
    failed,
    message,
    markdown,
    evidence: evidence.evidence,
    evidenceOrigin: evidence.origin,
    conflicts: conflicts.conflicts,
    conflictFlagged,
    conflictsOrigin: conflicts.origin,
    evidenceAudit: audit.audit,
    evidenceAuditOrigin: audit.origin,
    trace: {
      uri: `scenemind://task/${mediaId}/trace`,
      traceId: traceData?.traceId ?? null,
      available: Boolean(traceData && Object.keys(traceData).length),
      hint: '可通过 resources/read 读取该 URI 获取检索诊断 JSON（各通道名次、覆盖时间窗、Token / 成本等）。',
    },
    hint,
  };
}
