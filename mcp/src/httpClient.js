/**
 * httpClient.js — thin, dependency-free HTTP wrapper over node:http / node:https.
 *
 * Responsibilities (and nothing else):
 *   - inject the `Authorization: Bearer <token>` header (token supplied by an
 *     async getter, so it can be resolved lazily after an auto-login);
 *   - apply a per-request timeout;
 *   - unwrap SceneMind's unified Result envelope `{ code, message, data }`
 *     (code === 0 => success) and normalize failures into BackendError;
 *   - preserve the HTTP status so callers can distinguish 202 Accepted from
 *     200 "already completed" on POST /analysis/ai.
 *
 * No business logic lives here; it never assumes any particular endpoint.
 */

import http from 'node:http';
import https from 'node:https';

/** Normalized backend failure (transport error, timeout, or business error). */
export class BackendError extends Error {
  constructor(message, { status = 0, code, cause } = {}) {
    super(message, cause === undefined ? undefined : { cause });
    this.name = 'BackendError';
    this.status = status;
    this.code = code;
  }
}

function isEnvelope(payload) {
  return (
    payload !== null &&
    typeof payload === 'object' &&
    !Array.isArray(payload) &&
    typeof payload.code === 'number' &&
    'message' in payload
  );
}

/**
 * @param {object} options
 * @param {string} options.baseUrl   e.g. http://127.0.0.1:9090
 * @param {number} options.timeoutMs
 * @param {() => (string|null|Promise<string|null>)} options.getToken
 * @returns {(req: object) => Promise<{status:number, data:*, nonEnvelope?:boolean}>}
 */
export function createHttpClient({ baseUrl, timeoutMs = 15000, getToken = async () => null }) {
  const base = new URL(baseUrl);
  const transport = base.protocol === 'https:' ? https : http;

  return function request({
    method = 'GET',
    path,
    query = {},
    body,
    form,
    auth = true,
    headers: extraHeaders = {},
    timeoutMs: overrideTimeout,
  }) {
    return new Promise((resolve, reject) => {
      const target = new URL(base.href);
      const basePath = base.pathname.replace(/\/$/, '');
      target.pathname = basePath + (path.startsWith('/') ? path : `/${path}`);
      for (const [key, value] of Object.entries(query)) {
        if (value !== undefined && value !== null && value !== '') {
          target.searchParams.set(key, String(value));
        }
      }

      const headers = { Accept: 'application/json', ...extraHeaders };
      let bodyString = null;
      if (form && typeof form === 'object') {
        bodyString = new URLSearchParams(form).toString();
        headers['Content-Type'] = 'application/x-www-form-urlencoded';
      } else if (body !== undefined && body !== null) {
        bodyString = typeof body === 'string' ? body : JSON.stringify(body);
        headers['Content-Type'] = 'application/json';
      }
      if (bodyString !== null) {
        headers['Content-Length'] = String(Buffer.byteLength(bodyString));
      }

      let settled = false;
      const finish = (fn, value) => {
        if (settled) return;
        settled = true;
        fn(value);
      };

      const applyAuth = async () => {
        if (!auth) return;
        const token = await getToken();
        if (token) headers.Authorization = `Bearer ${token}`;
      };

      applyAuth()
        .then(() => {
          const req = transport.request(
            target,
            { method, headers },
            (res) => {
              const chunks = [];
              res.on('data', (chunk) => chunks.push(chunk));
              res.on('end', () => {
                const rawText = Buffer.concat(chunks).toString('utf8');
                const status = res.statusCode || 0;
                const httpOk = status >= 200 && status < 300;
                let parsed = null;
                try {
                  parsed = rawText ? JSON.parse(rawText) : null;
                } catch {
                  parsed = null;
                }

                if (isEnvelope(parsed)) {
                  if (httpOk && parsed.code === 0) {
                    return finish(resolve, { status, data: parsed.data ?? null });
                  }
                  const message =
                    parsed.message || `后端返回错误(code=${parsed.code}, http=${status})`;
                  return finish(
                    reject,
                    new BackendError(message, { status, code: parsed.code }),
                  );
                }

                if (httpOk) {
                  return finish(resolve, { status, data: parsed ?? rawText, nonEnvelope: true });
                }
                const message =
                  (parsed && typeof parsed.message === 'string' && parsed.message) ||
                  rawText ||
                  `HTTP ${status}`;
                return finish(reject, new BackendError(message, { status }));
              });
            },
          );

          const effectiveTimeout = overrideTimeout ?? timeoutMs;
          if (Number.isFinite(effectiveTimeout) && effectiveTimeout > 0) {
            req.setTimeout(effectiveTimeout, () => {
              req.destroy(new BackendError(`后端请求超时 (${effectiveTimeout}ms)`, { status: 0 }));
            });
          }
          req.on('error', (error) => {
            if (error instanceof BackendError) return finish(reject, error);
            return finish(
              reject,
              new BackendError(`无法连接后端服务: ${error.message}`, { status: 0, cause: error }),
            );
          });
          if (bodyString !== null) req.write(bodyString);
          req.end();
        })
        .catch((error) => finish(reject, error));
    });
  };
}
