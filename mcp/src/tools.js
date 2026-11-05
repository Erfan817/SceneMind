/**
 * tools.js — MCP tool definitions (JSON Schema), argument validation, and
 * execution dispatch. Tool names all start with `scenemind_`.
 *
 * Validation errors throw JsonRpcError(-32602) / (-32601) and become JSON-RPC
 * error responses. Runtime/backend failures during execution are returned as an
 * MCP result with `isError: true` so the calling Agent can read a human-readable
 * message instead of a bare protocol error.
 */

import { JsonRpcError, ErrorCode } from './protocol.js';
import { MODES } from './scenemindClient.js';

const MODE_SCHEMA = {
  type: 'string',
  enum: MODES,
  description: '分析模式，缺省 GENERAL。GENERAL/LEARNING/REVIEW/CREATION',
};

const MEDIA_ID_SCHEMA = {
  description: '媒体 ID（/media/upload-url 返回的 id；数字或数字字符串）',
  oneOf: [{ type: 'integer' }, { type: 'string' }],
};

/** Tool definitions returned by tools/list. Pure data. */
export const TOOL_DEFINITIONS = [
  {
    name: 'scenemind_submit_video',
    description:
      '把在线视频 URL 提交给 SceneMind 平台入库（对应后端 /media/upload-url）。' +
      '返回 mediaId 与受理状态；这一步只入库、不触发分析，随后请调用 scenemind_analyze。' +
      '注意：后端该接口只接收 url 参数，goal/mode 仅用于生成下一步建议，不会在入库时传给平台。',
    inputSchema: {
      type: 'object',
      properties: {
        url: { type: 'string', description: '在线视频链接（提交给 /media/upload-url 的 url 参数）' },
        goal: { type: 'string', description: '本次分析目标（用于后续 analyze/get_result；后端以此作为任务身份 key 的一部分）' },
        mode: MODE_SCHEMA,
      },
      required: ['url', 'goal'],
      additionalProperties: false,
    },
  },
  {
    name: 'scenemind_analyze',
    description:
      '对指定 mediaId 触发 AI 分析（对应 POST /analysis/ai）。后端异步执行：' +
      '202 表示已受理并开始排队，200 表示已有可复用结果。返回受理状态，不等待完成。',
    inputSchema: {
      type: 'object',
      properties: {
        mediaId: MEDIA_ID_SCHEMA,
        goal: { type: 'string', description: '分析目标（与后续 get_result/wait 必须一致，否则命中不到同一任务）' },
        mode: MODE_SCHEMA,
      },
      required: ['mediaId', 'goal'],
      additionalProperties: false,
    },
  },
  {
    name: 'scenemind_get_result',
    description:
      '获取结构化分析结果（聚合 /analysis/analysis-status、agent-evaluation、agent-trace）。' +
      '完成后返回 state、markdown 报告、证据列表（时间戳/来源/原文）、音画冲突（若平台提供）、' +
      '证据核验摘要（若平台提供 evidenceAudit）及 trace 资源提示；未完成则只返回当前状态与阶段，不死等。',
    inputSchema: {
      type: 'object',
      properties: {
        mediaId: MEDIA_ID_SCHEMA,
        goal: { type: 'string', description: '分析目标' },
        mode: MODE_SCHEMA,
      },
      required: ['mediaId', 'goal'],
      additionalProperties: false,
    },
  },
  {
    name: 'scenemind_wait_for_result',
    description:
      '在超时内轮询分析状态，完成即返回（复用 get_result 的输出结构），超时返回最后一次状态并明确标注未完成。' +
      '默认超时 180s，最大 600s；避免 MCP 调用无限挂起。',
    inputSchema: {
      type: 'object',
      properties: {
        mediaId: MEDIA_ID_SCHEMA,
        goal: { type: 'string', description: '分析目标' },
        mode: MODE_SCHEMA,
        timeoutMs: { type: 'integer', description: '最长等待毫秒数，默认 180000，最大 600000', minimum: 0, maximum: 600000 },
        pollIntervalMs: { type: 'integer', description: '轮询间隔毫秒数，默认 3000，范围 50..30000', minimum: 50, maximum: 30000 },
      },
      required: ['mediaId', 'goal'],
      additionalProperties: false,
    },
  },
  {
    name: 'scenemind_search_evidence',
    description:
      '按问题检索带时间戳的视频证据片段（对应 GET /analysis/evidence-search）。' +
      '返回命中片段的 startMs/endMs、时间戳文本、来源（ASR/OCR/ASR+OCR）、snippet、transcript、ocrTexts。',
    inputSchema: {
      type: 'object',
      properties: {
        mediaId: MEDIA_ID_SCHEMA,
        query: { type: 'string', description: '检索问题 / 关键词' },
      },
      required: ['mediaId', 'query'],
      additionalProperties: false,
    },
  },
  {
    name: 'scenemind_ask_followup',
    description: '基于已完成的分析进行追问（对应 POST /analysis/follow-up），返回追问答案文本。',
    inputSchema: {
      type: 'object',
      properties: {
        mediaId: MEDIA_ID_SCHEMA,
        question: { type: 'string', description: '追问内容' },
        goal: { type: 'string', description: '原始分析目标（可选，缺省时后端按默认处理）' },
        mode: MODE_SCHEMA,
      },
      required: ['mediaId', 'question'],
      additionalProperties: false,
    },
  },
  {
    name: 'scenemind_verify_evidence',
    description:
      '给出「每条证据是否可核验」的客户端判定说明。如实说明：这是基于平台 /analysis/agent-evaluation 与 ' +
      '/analysis/agent-trace 已有校验信号的转述，客户端不重新实现一套伪校验；若平台提供结构化 evidenceAudit 则直接采用。',
    inputSchema: {
      type: 'object',
      properties: {
        mediaId: MEDIA_ID_SCHEMA,
        goal: { type: 'string', description: '分析目标' },
        mode: MODE_SCHEMA,
        evidence: {
          type: 'array',
          description: '待核验的证据列表',
          items: {
            type: 'object',
            properties: {
              timestampMs: { type: 'integer', description: '时间戳（毫秒）', minimum: 0 },
              source: { type: 'string', description: '来源：ASR / OCR / ASR+OCR' },
              content: { type: 'string', description: '证据原文' },
            },
            required: ['timestampMs', 'source', 'content'],
            additionalProperties: false,
          },
        },
      },
      required: ['mediaId', 'goal', 'evidence'],
      additionalProperties: false,
    },
  },
];

const TOOL_NAMES = new Set(TOOL_DEFINITIONS.map((t) => t.name));

export const DEFAULT_WAIT_TIMEOUT_MS = 180000; // <= 180s
export const MAX_WAIT_TIMEOUT_MS = 600000; // 600s hard cap
const DEFAULT_POLL_INTERVAL_MS = 3000;
const MIN_POLL_INTERVAL_MS = 50;
const MAX_POLL_INTERVAL_MS = 30000;

export function listTools() {
  return TOOL_DEFINITIONS;
}

// ---- validation helpers -------------------------------------------------

function invalid(message) {
  throw new JsonRpcError(ErrorCode.INVALID_PARAMS, message);
}

function requireString(args, key, { optional = false } = {}) {
  const value = args[key];
  if (value === undefined || value === null || value === '') {
    if (optional) return null;
    return invalid(`缺少必需参数 ${key}（非空字符串）`);
  }
  if (typeof value !== 'string' || value.trim() === '') {
    return invalid(`参数 ${key} 必须是非空字符串`);
  }
  return value.trim();
}

function normalizeMediaId(args) {
  const value = args.mediaId;
  if (value === undefined || value === null || value === '') {
    return invalid('缺少必需参数 mediaId');
  }
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) return invalid('mediaId 必须是有限数字或数字字符串');
    return String(value);
  }
  if (typeof value === 'string' && value.trim() !== '') return value.trim();
  return invalid('mediaId 必须是数字或非空字符串');
}

function normalizeMode(args) {
  const value = args.mode;
  if (value === undefined || value === null || value === '') return 'GENERAL';
  if (typeof value !== 'string') return invalid('mode 必须是字符串');
  const upper = value.trim().toUpperCase();
  if (!MODES.includes(upper)) {
    return invalid(`不支持的分析模式: ${value}（可选 ${MODES.join('/')}）`);
  }
  return upper;
}

function optionalInt(args, key, min, max) {
  const value = args[key];
  if (value === undefined || value === null) return undefined;
  if (typeof value !== 'number' || !Number.isInteger(value)) {
    return invalid(`参数 ${key} 必须是整数`);
  }
  if (value < min || value > max) {
    return invalid(`参数 ${key} 必须在 ${min}..${max} 之间`);
  }
  return value;
}

function validateEvidence(args) {
  const list = args.evidence;
  if (!Array.isArray(list)) return invalid('参数 evidence 必须是数组');
  return list.map((item, index) => {
    if (item === null || typeof item !== 'object' || Array.isArray(item)) {
      return invalid(`evidence[${index}] 必须是对象`);
    }
    if (typeof item.timestampMs !== 'number' || !Number.isFinite(item.timestampMs) || item.timestampMs < 0) {
      return invalid(`evidence[${index}].timestampMs 必须是非负数字`);
    }
    if (typeof item.source !== 'string' || item.source.trim() === '') {
      return invalid(`evidence[${index}].source 必须是非空字符串`);
    }
    if (typeof item.content !== 'string') {
      return invalid(`evidence[${index}].content 必须是字符串`);
    }
    return {
      timestampMs: item.timestampMs,
      source: item.source.trim(),
      content: item.content,
    };
  });
}

/** Validate and normalize arguments for a tool. Throws JsonRpcError(-32602). */
function validateArgs(name, args) {
  const normalized = { ...args };
  // submit_video creates the mediaId; every other tool consumes one.
  if (name !== 'scenemind_submit_video') {
    normalized.mediaId = normalizeMediaId(args);
  }
  switch (name) {
    case 'scenemind_submit_video':
      normalized.url = requireString(args, 'url');
      normalized.goal = requireString(args, 'goal');
      normalized.mode = normalizeMode(args);
      break;
    case 'scenemind_analyze':
    case 'scenemind_get_result':
      normalized.goal = requireString(args, 'goal');
      normalized.mode = normalizeMode(args);
      break;
    case 'scenemind_wait_for_result':
      normalized.goal = requireString(args, 'goal');
      normalized.mode = normalizeMode(args);
      normalized.timeoutMs = optionalInt(args, 'timeoutMs', 0, MAX_WAIT_TIMEOUT_MS);
      normalized.pollIntervalMs = optionalInt(args, 'pollIntervalMs', MIN_POLL_INTERVAL_MS, MAX_POLL_INTERVAL_MS);
      break;
    case 'scenemind_search_evidence':
      normalized.query = requireString(args, 'query');
      break;
    case 'scenemind_ask_followup':
      normalized.question = requireString(args, 'question');
      normalized.goal = requireString(args, 'goal', { optional: true });
      normalized.mode = normalizeMode(args);
      break;
    case 'scenemind_verify_evidence':
      normalized.goal = requireString(args, 'goal');
      normalized.mode = normalizeMode(args);
      normalized.evidence = validateEvidence(args);
      break;
    default:
      break;
  }
  return normalized;
}

// ---- helpers ------------------------------------------------------------

function textResult(payload, isError = false) {
  const result = {
    content: [{ type: 'text', text: JSON.stringify(payload, null, 2) }],
  };
  if (isError) result.isError = true;
  return result;
}

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function formatTimestamp(ms) {
  const totalSeconds = Math.max(0, Math.floor(Number(ms) / 1000)) || 0;
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  const pad = (n) => String(n).padStart(2, '0');
  return hours > 0
    ? `${pad(hours)}:${pad(minutes)}:${pad(seconds)}`
    : `${pad(minutes)}:${pad(seconds)}`;
}

/** Poll getResult until completed/failed or the deadline, never infinitely. */
export async function waitForResult(client, args) {
  const timeoutMs = clamp(
    args.timeoutMs ?? DEFAULT_WAIT_TIMEOUT_MS,
    0,
    MAX_WAIT_TIMEOUT_MS,
  );
  const pollIntervalMs = clamp(
    args.pollIntervalMs ?? DEFAULT_POLL_INTERVAL_MS,
    MIN_POLL_INTERVAL_MS,
    MAX_POLL_INTERVAL_MS,
  );
  const startedAt = Date.now();
  const deadline = startedAt + timeoutMs;
  let polls = 0;
  let last = null;

  for (;;) {
    last = await client.getResult(args.mediaId, args.goal, args.mode);
    polls += 1;
    if (last.completed || last.failed) {
      return {
        ...last,
        timedOut: false,
        unfinished: false,
        polls,
        waitedMs: Date.now() - startedAt,
      };
    }
    const remaining = deadline - Date.now();
    if (remaining <= 0) {
      return {
        ...last,
        timedOut: true,
        unfinished: true,
        completed: false,
        polls,
        waitedMs: Date.now() - startedAt,
        message: `${last.message || '任务尚未完成'}｜等待超时：在分析完成前返回最后一次状态（未完成）。`,
        hint:
          '超时返回的仅是最后一次状态，任务仍未完成；可稍后用 scenemind_get_result 再查，或加大 timeoutMs 重试。',
      };
    }
    await sleep(Math.min(pollIntervalMs, remaining));
  }
}

// ---- execution ----------------------------------------------------------

async function executeTool(name, a, ctx) {
  const client = ctx.client;
  switch (name) {
    case 'scenemind_submit_video': {
      const data = await client.submitUrl(a.url);
      return textResult({
        mediaId: data?.id ?? null,
        filename: data?.filename ?? null,
        status: data?.status ?? null,
        goal: a.goal,
        mode: a.mode,
        accepted: true,
        message: '在线视频链接已提交入库（/media/upload-url），返回 mediaId；尚未开始分析。',
        nextStep: {
          tool: 'scenemind_analyze',
          arguments: { mediaId: data?.id ?? a.mediaId, goal: a.goal, mode: a.mode },
        },
      });
    }
    case 'scenemind_analyze': {
      const res = await client.analyze(a.mediaId, a.goal, a.mode);
      return textResult({
        mediaId: a.mediaId,
        goal: a.goal,
        mode: a.mode,
        accepted: res.accepted,
        httpStatus: res.status,
        state: res.alreadyCompleted ? 'COMPLETED (已有可复用结果，可立即 get_result)' : 'ACCEPTED / QUEUED',
        message: res.alreadyCompleted
          ? '后端已有该 (mediaId, goal, mode) 的可复用结果（200）。'
          : '任务已受理（202 Accepted），异步执行中，请轮询或等待。',
        nextStep: {
          tool: 'scenemind_wait_for_result',
          arguments: { mediaId: a.mediaId, goal: a.goal, mode: a.mode },
        },
      });
    }
    case 'scenemind_get_result':
      return textResult(await client.getResult(a.mediaId, a.goal, a.mode));

    case 'scenemind_wait_for_result':
      return textResult(await waitForResult(client, a));

    case 'scenemind_search_evidence': {
      const hits = await client.evidenceSearch(a.mediaId, a.query);
      const mapped = (Array.isArray(hits) ? hits : []).map((hit) => ({
        startMs: hit.startMs,
        endMs: hit.endMs,
        timestampLabel: `${formatTimestamp(hit.startMs)}-${formatTimestamp(hit.endMs)}`,
        source: hit.source ?? '',
        snippet: hit.snippet ?? '',
        transcript: hit.transcript ?? '',
        ocrTexts: Array.isArray(hit.ocrTexts) ? hit.ocrTexts : [],
      }));
      return textResult({
        mediaId: a.mediaId,
        query: a.query,
        count: mapped.length,
        message: mapped.length ? '命中带时间戳的视频证据片段。' : '没有找到匹配的视频证据。',
        hits: mapped,
      });
    }
    case 'scenemind_ask_followup': {
      const answer = await client.followUp(a.mediaId, a.question, a.goal, a.mode);
      return textResult({
        mediaId: a.mediaId,
        question: a.question,
        goal: a.goal,
        mode: a.mode,
        answer,
      });
    }
    case 'scenemind_verify_evidence':
      return textResult(
        await client.verifyEvidence(a.mediaId, a.goal, a.evidence, a.mode),
      );
    default:
      return textResult({ error: 'unreachable' }, true);
  }
}

/**
 * Validate + execute a tools/call. Returns an MCP result object.
 * Throws JsonRpcError for unknown tool / invalid params (mapped to JSON-RPC
 * errors by the dispatcher). Runtime errors become isError content.
 */
export async function callTool(name, rawArgs, ctx) {
  if (typeof name !== 'string' || !TOOL_NAMES.has(name)) {
    throw new JsonRpcError(ErrorCode.METHOD_NOT_FOUND, `Unknown tool: ${name}`);
  }
  const args = rawArgs && typeof rawArgs === 'object' && !Array.isArray(rawArgs) ? rawArgs : {};
  const normalized = validateArgs(name, args);
  try {
    return await executeTool(name, normalized, ctx);
  } catch (error) {
    return textResult(
      {
        tool: name,
        error: '工具执行失败',
        message: error?.message || String(error),
        type: error?.name || 'Error',
      },
      true,
    );
  }
}
