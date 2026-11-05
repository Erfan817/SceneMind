/**
 * resources.js — MCP resource templates and reads.
 *
 *   scenemind://task/{mediaId}/report  -> analysis report markdown (text/markdown)
 *   scenemind://task/{mediaId}/trace   -> retrieval diagnostics JSON (application/json)
 *
 * The URIs are templates (parameterized by mediaId), so resources/list exposes
 * them under `resourceTemplates`. They are also mirrored into `resources` as a
 * courtesy for consumers that only look at that array.
 *
 * Because the backend status/trace endpoints require `goal` and `mode` query
 * params (they form the task identity key), reads accept optional URI query
 * params `?goal=<urlencoded>&mode=GENERAL`. When omitted, the backend default
 * goal and GENERAL mode are used. If the analysis used a custom goal, append it
 * to the URI or use the scenemind_get_result tool explicitly.
 */

import { JsonRpcError, ErrorCode } from './protocol.js';
import { DEFAULT_GOAL } from './demoData.js';
import { MODES } from './scenemindClient.js';

export const REPORT_TEMPLATE = 'scenemind://task/{mediaId}/report';
export const TRACE_TEMPLATE = 'scenemind://task/{mediaId}/trace';

const TEMPLATES = [
  {
    uriTemplate: REPORT_TEMPLATE,
    name: 'SceneMind 分析报告 (markdown)',
    description:
      '指定 mediaId 的分析报告 markdown，含带时间戳 / 来源 / 原文的证据。可用 ?goal=&mode= 指定任务。',
    mimeType: 'text/markdown',
  },
  {
    uriTemplate: TRACE_TEMPLATE,
    name: 'SceneMind 检索诊断 (JSON)',
    description:
      '指定 mediaId 的检索诊断 JSON（通道名次、覆盖时间窗、Token / 成本等）。可用 ?goal=&mode= 指定任务。',
    mimeType: 'application/json',
  },
];

export function listResources() {
  return {
    resources: TEMPLATES.map((t) => ({
      uri: t.uriTemplate,
      name: t.name,
      description: t.description,
      mimeType: t.mimeType,
    })),
    resourceTemplates: TEMPLATES.map((t) => ({ ...t })),
  };
}

/** Parse a scenemind resource URI into its parts. Throws JsonRpcError. */
export function parseResourceUri(uri) {
  if (typeof uri !== 'string' || uri.trim() === '') {
    throw new JsonRpcError(ErrorCode.INVALID_PARAMS, 'resources/read 需要非空 uri 字符串');
  }
  const match = uri.match(/^scenemind:\/\/task\/([^/]+)\/([^/?]+)(?:\?(.*))?$/);
  if (!match) {
    throw new JsonRpcError(
      ErrorCode.INVALID_PARAMS,
      `无效的 SceneMind 资源 URI: ${uri}（应形如 scenemind://task/{mediaId}/report 或 /trace）`,
    );
  }
  let mediaId;
  try {
    mediaId = decodeURIComponent(match[1]);
  } catch {
    mediaId = match[1];
  }
  const kind = match[2];
  if (kind !== 'report' && kind !== 'trace') {
    throw new JsonRpcError(ErrorCode.RESOURCE_NOT_FOUND, `未找到资源类型: ${kind}`);
  }

  const params = new URLSearchParams(match[3] || '');
  const goal = params.get('goal') || DEFAULT_GOAL;
  let mode = (params.get('mode') || 'GENERAL').toUpperCase();
  if (!MODES.includes(mode)) mode = 'GENERAL';
  return { mediaId, kind, goal, mode };
}

/**
 * Read a resource. `ctx.client` is a SceneMindClient.
 * Returns { contents: [{ uri, mimeType, text }] }.
 */
export async function readResource(uri, ctx) {
  const { mediaId, kind, goal, mode } = parseResourceUri(uri);
  const client = ctx.client;

  if (kind === 'report') {
    const result = await client.getResult(mediaId, goal, mode);
    const text = result.markdown
      ? result.markdown
      : `# 报告尚未就绪\n\n- state: ${result.state}\n- message: ${result.message || ''}\n\n${result.hint || ''}`;
    return {
      contents: [{ uri, mimeType: 'text/markdown', text }],
    };
  }

  // kind === 'trace'
  const trace = await client.trace(mediaId, goal, mode);
  return {
    contents: [
      {
        uri,
        mimeType: 'application/json',
        text: JSON.stringify(trace ?? {}, null, 2),
      },
    ],
  };
}
