/**
 * handlers.js — MCP method handlers shared by stdio and HTTP transports.
 */

import { JsonRpcError, ErrorCode, MCP_PROTOCOL_VERSION } from './protocol.js';
import { listTools, callTool } from './tools.js';
import { listResources, readResource } from './resources.js';

export const SERVER_INFO = { name: 'scenemind-mcp', version: '1.0.0' };

export function createHandlers(ctx) {
  return {
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
}
