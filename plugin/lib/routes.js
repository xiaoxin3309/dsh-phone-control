/**
 * HTTP surface for the phone-bridge settings page.
 *
 * It hangs off DSH's own web server (registered from index.js through
 * `ctx.webServer.register`) instead of a second listener: the page is served
 * from the same origin, so a same-origin check is the whole authorization
 * story, and nothing extra needs a port.
 *
 * Every response is `no-store`. The boilerplate (prefix dispatch, same-origin
 * check, size-capped JSON body) mirrors dsh-proxy-switcher so both plugins
 * behave the same way on the same web server.
 */

/** Base path every route lives under. Keep in sync with lib/client.js. */
export const BASE_PATH = '/dsh-phone-control'

/** Largest request body accepted, in bytes. */
const MAX_BODY_BYTES = 64 * 1024

/**
 * Whether a request may reach these routes.
 *
 * A browser attaches `Origin` on cross-site and same-site non-GET requests, so
 * when present it must name exactly the authority we were addressed by. A
 * request without `Origin` is allowed only when addressed to a loopback host.
 *
 * @param req - the incoming request.
 * @returns true when the request is same-origin and locally addressed.
 */
export function isAuthorized(req) {
  const hostHeader = String(req.headers.host ?? '')
  if (hostHeader === '') return false
  const hostName = hostHeader.replace(/:\d+$/u, '').replace(/^\[|\]$/gu, '').toLowerCase()
  const isLoopback = hostName === 'localhost' || hostName === '127.0.0.1' || hostName === '::1'
  const origin = req.headers.origin
  if (origin === undefined) return isLoopback
  try {
    return new URL(origin).host === hostHeader
  } catch {
    return false
  }
}

/**
 * Send a JSON response that must never be cached.
 * @param res - the server response.
 * @param status - HTTP status.
 * @param payload - JSON-serializable body.
 */
export function sendJson(res, status, payload) {
  const body = JSON.stringify(payload)
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    'content-length': Buffer.byteLength(body),
  })
  res.end(body)
}

/**
 * Read and parse a JSON request body within a size cap.
 * @param req - the incoming request.
 * @returns the parsed value; `{}` for an empty body.
 * @throws {Error} when the body is too large or is not valid JSON.
 */
export async function readJsonBody(req) {
  const chunks = []
  let total = 0
  for await (const chunk of req) {
    total += chunk.length
    if (total > MAX_BODY_BYTES) throw new Error(`请求体超过 ${MAX_BODY_BYTES} 字节上限`)
    chunks.push(chunk)
  }
  const text = Buffer.concat(chunks).toString('utf8').trim()
  if (text === '') return {}
  return JSON.parse(text)
}

/**
 * Build the route handler serving the whole settings surface.
 *
 * Routes (all under BASE_PATH):
 *   GET  /state       service status + listen settings
 *   POST /refresh     rotate the pairing code (and clear the lockout)
 *   POST /settings    {port?, bindHost?} -> persist and rebind
 *   POST /disconnect  drop every connected device
 *
 * @param service - the {@link import('./service.js').SpikeService} instance.
 * @param options.logger - for reporting malformed requests.
 * @returns a `WebRoute` handler.
 */
export function createHandler(service, options = {}) {
  const logger = options.logger ?? console

  const snapshot = () => {
    const status = service.status()
    return {
      ok: true,
      status,
      settings: {
        port: status.port,
        requestedPort: status.requestedPort,
        autoPort: status.autoPort,
        bindHost: status.bindHost,
        settingsFile: status.settingsFile,
      },
    }
  }

  return async function handle(req, res) {
    try {
      if (!isAuthorized(req)) {
        sendJson(res, 403, { error: '已拒绝：该接口仅允许同源且来自回环地址的请求' })
        return
      }

      const url = new URL(req.url ?? '/', `http://${req.headers.host ?? '127.0.0.1'}`)
      const action = url.pathname.slice(BASE_PATH.length).replace(/^\/+|\/+$/gu, '')
      const method = req.method ?? 'GET'

      if (method === 'GET' && (action === 'state' || action === 'status')) {
        sendJson(res, 200, snapshot())
        return
      }

      if (method === 'POST' && action === 'refresh') {
        service.refreshCode(true)
        sendJson(res, 200, snapshot())
        return
      }

      if (method === 'POST' && action === 'settings') {
        const body = await readJsonBody(req)
        await service.applySettings({ port: body.port, bindHost: body.bindHost })
        sendJson(res, 200, snapshot())
        return
      }

      if (method === 'POST' && action === 'disconnect') {
        const closed = service.disconnectAll()
        sendJson(res, 200, { ...snapshot(), closed })
        return
      }

      sendJson(res, 404, { error: `未知操作 ${JSON.stringify(action)}` })
    } catch (error) {
      logger.warn?.(`dsh-phone-control: 请求处理失败: ${error?.message ?? error}`)
      sendJson(res, 400, { error: String(error?.message ?? error) })
    }
  }
}
