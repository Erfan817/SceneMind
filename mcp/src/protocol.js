/**
 * protocol.js — pure JSON-RPC 2.0 helpers (no I/O, no side effects).
 *
 * SceneMind MCP uses the MCP stdio transport: one JSON-RPC message per line,
 * newline-delimited. No Content-Length framing. This module only knows about
 * parsing a line, dispatching a parsed message to injected handlers, and the
 * standard error codes; it never reads stdin or writes stdout.
 */

export const JSONRPC_VERSION = '2.0';

/** MCP protocol version advertised during initialize. */
export const MCP_PROTOCOL_VERSION = '2024-11-05';

/** JSON-RPC 2.0 standard error codes + MCP server-defined range. */
export const ErrorCode = Object.freeze({
  PARSE_ERROR: -32700,
  INVALID_REQUEST: -32600,
  METHOD_NOT_FOUND: -32601,
  INVALID_PARAMS: -32602,
  INTERNAL_ERROR: -32603,
  // -32000..-32099 are reserved for implementation-defined server errors.
  RESOURCE_NOT_FOUND: -32002,
});

/** Error that carries a JSON-RPC error code so the dispatcher can map it. */
export class JsonRpcError extends Error {
  constructor(code, message, data) {
    super(message);
    this.name = 'JsonRpcError';
    this.code = code;
    this.data = data;
  }
}

/** Build a successful JSON-RPC response object. */
export function ok(id, result) {
  return { jsonrpc: JSONRPC_VERSION, id, result: result === undefined ? {} : result };
}

/** Build a JSON-RPC error response object. */
export function err(id, code, message, data) {
  const error = { code, message };
  if (data !== undefined) error.data = data;
  return { jsonrpc: JSONRPC_VERSION, id: id ?? null, error };
}

/**
 * Parse one newline-delimited JSON-RPC message.
 * Throws JsonRpcError(-32700) for malformed JSON and
 * JsonRpcError(-32600) for a valid JSON value that is not an object.
 */
export function parseMessage(line) {
  let payload;
  try {
    payload = JSON.parse(line);
  } catch {
    throw new JsonRpcError(ErrorCode.PARSE_ERROR, 'Parse error');
  }

  // JSON-RPC 2.0 batch: an array of valid Request objects. Every entry must
  // itself be a well-formed message; a bare array of scalars is not a batch.
  if (Array.isArray(payload)) {
    if (payload.length === 0) {
      throw new JsonRpcError(ErrorCode.INVALID_REQUEST, 'Invalid Request: empty batch');
    }
    for (const entry of payload) {
      if (
        entry === null ||
        typeof entry !== 'object' ||
        Array.isArray(entry) ||
        entry.jsonrpc !== JSONRPC_VERSION
      ) {
        throw new JsonRpcError(ErrorCode.INVALID_REQUEST, 'Invalid Request: bad batch entry');
      }
    }
    return payload;
  }

  if (payload === null || typeof payload !== 'object') {
    throw new JsonRpcError(ErrorCode.INVALID_REQUEST, 'Invalid Request');
  }
  if (payload.jsonrpc !== JSONRPC_VERSION) {
    throw new JsonRpcError(ErrorCode.INVALID_REQUEST, 'Invalid Request: jsonrpc must be "2.0"');
  }
  return payload;
}

/** A message is a notification when it has a method but no id. */
export function isNotification(message) {
  return (
    message !== null &&
    typeof message === 'object' &&
    typeof message.method === 'string' &&
    !Object.prototype.hasOwnProperty.call(message, 'id')
  );
}

/**
 * Create a dispatcher over a handlers map: { [method]: async (params, message) => result }.
 * Returns `async dispatch(message)` that resolves to:
 *   - a response object for requests (including mapped errors), or
 *   - `null` for notifications (no response must be sent).
 *
 * Error mapping:
 *   - unknown method           -> -32601 (request) / ignored (notification)
 *   - JsonRpcError              -> that code
 *   - any other thrown error    -> -32603
 */
export function createDispatcher(handlers) {
  return async function dispatch(message) {
    const notification = isNotification(message);
    const id = Object.prototype.hasOwnProperty.call(message, 'id') ? message.id : null;

    if (typeof message.method !== 'string' || message.method.length === 0) {
      return notification ? null : err(id, ErrorCode.INVALID_REQUEST, 'Invalid Request: missing method');
    }

    const handler = handlers[message.method];
    if (typeof handler !== 'function') {
      // Unknown notifications are silently ignored per JSON-RPC spec.
      return notification ? null : err(id, ErrorCode.METHOD_NOT_FOUND, `Method not found: ${message.method}`);
    }

    try {
      const result = await handler(message.params ?? {}, message);
      return notification ? null : ok(id, result);
    } catch (error) {
      if (notification) return null;
      if (error instanceof JsonRpcError) {
        return err(id, error.code, error.message, error.data);
      }
      return err(id, ErrorCode.INTERNAL_ERROR, error?.message || 'Internal error');
    }
  };
}

/**
 * Convenience: parse one line and dispatch. Also supports JSON-RPC batch
 * (an array of messages), returning an array of responses (notifications
 * dropped). A parse error yields a single -32700 response with id null.
 * Returns null when there is nothing to send back.
 */
export async function handleLine(line, handlers) {
  if (typeof line !== 'string' || line.trim() === '') return null;

  let message;
  try {
    message = parseMessage(line);
  } catch (error) {
    const code = error instanceof JsonRpcError ? error.code : ErrorCode.PARSE_ERROR;
    return err(null, code, error.message || 'Parse error');
  }

  const dispatch = createDispatcher(handlers);
  if (Array.isArray(message)) {
    const responses = [];
    for (const entry of message) {
      // Each batch entry is dispatched as a single message.
      const response = await dispatch(entry);
      if (response !== null) responses.push(response);
    }
    return responses.length ? responses : null;
  }
  return dispatch(message);
}
