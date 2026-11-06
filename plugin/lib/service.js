import net from 'node:net'
import http from 'node:http'
import { EventEmitter } from 'node:events'
import { createRequire } from 'node:module'
import { readFile, writeFile } from 'node:fs/promises'
import { appendFileSync } from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import {
  FrameParser,
  MAX_FRAME,
  b64u,
  deriveSession,
  encodeFrame,
  fromB64u,
  genCode,
  makeEcdh,
  openFrame,
  randomB64u,
  safeEqual,
  sealFrame,
  sha256,
} from './protocol.js'

const err = (code, message) => ({ type: 'ERR', ok: false, error: { code, message } })

// 认证调试日志：默认关闭，设置 DSH_PHONE_DEBUG=1 才写系统临时目录下的
// dsh-phone-bridge-token-debug.log（不写进包目录，免得被打进 npm 包）。
// 日志只记状态与错误码，不记 token / 配对码明文。
const DEBUG = process.env.DSH_PHONE_DEBUG === '1'
const DEBUG_LOG = path.join(os.tmpdir(), 'dsh-phone-bridge-token-debug.log')

function tokenDebug(message) {
  if (!DEBUG) return
  try {
    appendFileSync(DEBUG_LOG, `${new Date().toISOString()} ${message}\n`)
  } catch {
    // ignore
  }
}

class WsSocket extends EventEmitter {
  constructor(ws) {
    super()
    this.ws = ws
    this.destroyed = false
    this.timer = null
    this.alive = true
    this.heartbeat = setInterval(() => {
      if (this.ws.readyState !== 1) return
      if (!this.alive) {
        try {
          this.ws.terminate()
        } catch {
          // ignore
        }
        return
      }
      this.alive = false
      try {
        this.ws.ping()
      } catch {
        // ignore
      }
    }, 25000)
    ws.on('pong', () => {
      this.alive = true
    })
    ws.on('message', (data) => this.emit('data', Buffer.from(data)))
    ws.on('close', () => {
      this.destroyed = true
      clearInterval(this.heartbeat)
      this.emit('close')
    })
    ws.on('error', (error) => this.emit('error', error))
  }

  setNoDelay() {}

  setTimeout(ms, callback) {
    if (this.timer !== null) {
      clearTimeout(this.timer)
      this.timer = null
    }
    if (Number(ms) > 0 && typeof callback === 'function') {
      this.timer = setTimeout(callback, Number(ms))
    }
  }

  write(buffer) {
    if (this.ws.readyState === 1) this.ws.send(buffer)
  }

  end() {
    try {
      this.ws.close(1000)
    } catch {
      // ignore
    }
  }

  destroy(error) {
    this.destroyed = true
    if (this.timer !== null) {
      clearTimeout(this.timer)
      this.timer = null
    }
    clearInterval(this.heartbeat)
    try {
      this.ws.close(1000, error instanceof Error ? error.message.slice(0, 120) : undefined)
    } catch {
      // ignore
    }
  }
}

function loadWebSocketServer() {
  const candidates = []
  const profileDir = process.env.DSH_PROFILE_DIR
  if (profileDir !== undefined && profileDir.length > 0) {
    candidates.push(createRequire(path.join(profileDir, 'package.json')))
  }
  candidates.push(createRequire(import.meta.url))
  for (const requireFrom of candidates) {
    try {
      const loaded = requireFrom('ws')
      if (typeof loaded?.WebSocketServer === 'function') return loaded.WebSocketServer
    } catch {
      // try next
    }
  }
  return null
}

/**
 * Whether a TCP port can be bound on the given host right now.
 * @param {number} port - the candidate port.
 * @param {string} host - bind address to test against.
 * @returns {Promise<boolean>} true when a probe server could listen.
 */
function portFree(port, host) {
  return new Promise((resolve) => {
    const probe = net.createServer()
    probe.once('error', () => resolve(false))
    probe.once('listening', () => probe.close(() => resolve(true)))
    try {
      probe.listen(port, host)
    } catch {
      resolve(false)
    }
  })
}

export class SpikeService {
  constructor({ ctx, config }) {
    this.ctx = ctx
    // Port 0 — the shipped default — means "pick a free port automatically".
    // The chosen port is persisted to settingsFile so later restarts reuse it
    // instead of drifting every boot.
    const requested = config?.port === undefined ? 0 : Number(config.port)
    this.requestedPort = Number.isInteger(requested) && requested >= 0 && requested <= 65535 ? requested : 0
    this.autoPort = this.requestedPort === 0
    this.port = this.requestedPort
    this.bindHost = String(config?.bindHost ?? '0.0.0.0')
    const configuredCode = String(config?.pairingCode ?? '').trim()
    const autoCode = config?.autoCode !== false
    this.autoCode = autoCode
    this.configuredCode = /^\d{8}$/u.test(configuredCode) ? configuredCode : null
    this.code = autoCode || this.configuredCode === null ? genCode() : this.configuredCode
    this.codeIssuedAt = Date.now()
    this.codeExpiresAt = this.codeIssuedAt + 5 * 60_000
    this.failedAttempts = 0
    this.lockedUntil = 0
    this.tokenHash = null
    this.previousTokenHash = null
    this.previousTokenExpiresAt = 0
    this.pairedAt = null
    this.sessions = new Set()
    this.pending = new Map()
    this.nextId = 0
    this.server = null
    this.startedAt = Date.now()
    const profileDir = process.env.DSH_PROFILE_DIR
      ?? (process.env.DSH_HOME
        ? path.join(process.env.DSH_HOME, 'profiles', process.env.DSH_PROFILE ?? 'desktop')
        : path.join(os.homedir(), '.dsh', 'profiles', 'desktop'))
    this.tokenFile = path.join(profileDir, 'phone-bridge-token.json')
    this.settingsFile = path.join(profileDir, 'phone-bridge.json')
  }

  async loadToken() {
    if (this.tokenFile === null) return
    try {
      const raw = JSON.parse(await readFile(this.tokenFile, 'utf8'))
      if (typeof raw?.tokenHash === 'string' && raw.tokenHash.length > 0) {
        this.tokenHash = Buffer.from(raw.tokenHash, 'base64')
        this.pairedAt = Number(raw.pairedAt) || null
      }
      if (typeof raw?.previousTokenHash === 'string' && raw.previousTokenHash.length > 0) {
        this.previousTokenHash = Buffer.from(raw.previousTokenHash, 'base64')
        this.previousTokenExpiresAt = Number(raw.previousTokenExpiresAt) || 0
      }
      tokenDebug(`load ok file=${this.tokenFile} pairedAt=${this.pairedAt}`)
    } catch (error) {
      tokenDebug(`load fail file=${this.tokenFile} error=${error.message}`)
      if (error?.code !== 'ENOENT') {
        this.ctx?.logger?.warn?.(`[phone-control] load token failed: ${error.message}`)
      }
    }
  }

  async saveToken() {
    if (this.tokenFile === null || this.tokenHash === null) return
    try {
      await writeFile(
        this.tokenFile,
        JSON.stringify({
          tokenHash: this.tokenHash.toString('base64'),
          previousTokenHash: this.previousTokenHash?.toString('base64') ?? null,
          previousTokenExpiresAt: this.previousTokenExpiresAt,
          pairedAt: this.pairedAt,
        }),
        'utf8',
      )
      tokenDebug(`save ok file=${this.tokenFile} pairedAt=${this.pairedAt}`)
    } catch (error) {
      tokenDebug(`save fail file=${this.tokenFile} error=${error.message}`)
      this.ctx?.logger?.warn?.(`[phone-control] save token failed: ${error.message}`)
    }
  }

  /** Load persisted listener settings (port / bindHost) when a file exists. */
  async loadSettings() {
    try {
      const raw = JSON.parse(await readFile(this.settingsFile, 'utf8'))
      const port = Number(raw?.port)
      if (Number.isInteger(port) && port >= 0 && port <= 65535) {
        this.requestedPort = port
        this.autoPort = port === 0
        this.port = port
      }
      if (typeof raw?.bindHost === 'string' && raw.bindHost.trim().length > 0) {
        this.bindHost = raw.bindHost.trim()
      }
    } catch (error) {
      if (error?.code !== 'ENOENT') {
        this.ctx?.logger?.warn?.(`[phone-control] load settings failed: ${error.message}`)
      }
    }
  }

  /** Persist the listener settings so a restart keeps the same port. */
  async saveSettings() {
    try {
      await writeFile(
        this.settingsFile,
        JSON.stringify({ port: this.requestedPort, bindHost: this.bindHost }, null, 2),
        'utf8',
      )
    } catch (error) {
      this.ctx?.logger?.warn?.(`[phone-control] save settings failed: ${error.message}`)
    }
  }

  /**
   * Resolve the port to listen on: the saved one when it is still free,
   * otherwise a random free port in 8000–9999, otherwise "let the OS decide".
   * @returns {Promise<number>} the port to bind (0 = OS-assigned).
   */
  async pickPort() {
    if (this.requestedPort > 0) {
      // 重启 DSH 时上一个进程的 socket 可能还没完全关掉：先在保存的端口上等一小会儿
      // （最多约 4 秒），别急着换端口——换了端口会让已经指向它的隧道/App 全部失联。
      for (let wait = 0; wait < 8; wait += 1) {
        if (await portFree(this.requestedPort, this.bindHost)) return this.requestedPort
        if (wait === 0) {
          this.ctx?.logger?.warn?.(`[phone-control] port ${this.requestedPort} still busy, waiting…`)
        }
        await new Promise((resolve) => setTimeout(resolve, 500))
      }
      this.ctx?.logger?.warn?.(`[phone-control] port ${this.requestedPort} is busy, picking another`)
    }
    for (let attempt = 0; attempt < 40; attempt += 1) {
      const candidate = 8000 + Math.floor(Math.random() * 2000)
      if (await portFree(candidate, this.bindHost)) return candidate
    }
    return 0
  }

  /** Close every listener and drop all sessions, keeping the service reusable. */
  stopListener() {
    for (const entry of this.pending.values()) {
      clearTimeout(entry.timer)
      entry.reject(new Error('SERVICE_RESTART: 手机桥正在重启'))
    }
    this.pending.clear()
    for (const session of this.sessions) {
      session.closed = true
      session.socket.destroy()
    }
    this.sessions.clear()
    if (this.wss !== undefined) {
      try {
        this.wss.close()
      } catch {
        /* already closed */
      }
      this.wss = undefined
    }
    if (this.httpServer !== undefined) {
      try {
        this.httpServer.close()
      } catch {
        /* already closed */
      }
      this.httpServer = undefined
    }
    if (this.server !== null) {
      try {
        this.server.close()
      } catch {
        /* already closed */
      }
      this.server = null
    }
  }

  /** Rebind the listener (used after a settings change). */
  async restart() {
    this.stopListener()
    await this.start()
  }

  /**
   * Apply listener settings coming from the settings page and rebind.
   * @param {{port?: number, bindHost?: string}} next - requested values.
   * @returns {Promise<object>} the refreshed status.
   */
  async applySettings(next = {}) {
    let changed = false
    if (next.port !== undefined) {
      const port = Number(next.port)
      if (!Number.isInteger(port) || port < 0 || port > 65535) {
        throw new Error('端口必须是 0–65535 的整数（0 = 自动挑一个空闲端口）')
      }
      if (port !== 0 && port < 1024) {
        throw new Error('1024 以下属于系统保留端口，请填 1024–65535 或 0 自动')
      }
      if (port !== this.requestedPort) {
        this.requestedPort = port
        this.autoPort = port === 0
        changed = true
      }
    }
    if (next.bindHost !== undefined) {
      const host = String(next.bindHost).trim()
      if (host.length === 0) throw new Error('绑定地址不能为空')
      if (!['0.0.0.0', '127.0.0.1', '::', '::1', 'localhost'].includes(host)) {
        throw new Error('绑定地址只接受 0.0.0.0 / 127.0.0.1 / :: / ::1 / localhost')
      }
      if (host !== this.bindHost) {
        this.bindHost = host
        changed = true
      }
    }
    await this.saveSettings()
    if (changed && this.server !== null) await this.restart()
    return this.status()
  }

  refreshCode(force = false) {
    if (!force && Date.now() < this.lockedUntil) return
    this.code = this.autoCode || this.configuredCode === null ? genCode() : this.configuredCode
    this.codeIssuedAt = Date.now()
    this.codeExpiresAt = this.codeIssuedAt + 5 * 60_000
    this.failedAttempts = 0
    this.lockedUntil = 0
  }

  status() {
    const ready = this.readySession()
    return {
      listening: this.server?.listening === true,
      port: this.port,
      requestedPort: this.requestedPort,
      autoPort: this.autoPort,
      bindHost: this.bindHost,
      pairingCode: this.code,
      codeIssuedAt: this.codeIssuedAt,
      codeExpiresAt: this.codeExpiresAt,
      failedAttempts: this.failedAttempts,
      lockedUntil: this.lockedUntil > Date.now() ? this.lockedUntil : null,
      paired: this.tokenHash !== null,
      pairedAt: this.pairedAt,
      connected: ready !== undefined,
      sessions: this.sessions.size,
      pending: this.pending.size,
      websocket: this.wss !== undefined,
      tokenFile: this.tokenFile,
      settingsFile: this.settingsFile,
      uptimeSec: Math.floor((Date.now() - this.startedAt) / 1000),
    }
  }

  readySession() {
    for (const session of this.sessions) {
      if (session.state === 'ready' && !session.closed && !session.socket.destroyed) return session
    }
    return undefined
  }

  async start() {
    if (this.server !== null) return
    await this.loadToken()
    await this.loadSettings()

    const httpServer = http.createServer((request, response) => {
      if (request.url === '/' || request.url === '/health') {
        response.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' })
        response.end('dsh-phone-bridge ok\n')
        return
      }
      response.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' })
      response.end('not found\n')
    })
    this.httpServer = httpServer

    const WebSocketServer = loadWebSocketServer()
    if (WebSocketServer !== null) {
      const wss = new WebSocketServer({ noServer: true })
      this.wss = wss
      httpServer.on('upgrade', (request, socket, head) => {
        const pathname = new URL(request.url ?? '/', 'http://localhost').pathname
        if (pathname !== '/ws') {
          socket.destroy()
          return
        }
        wss.handleUpgrade(request, socket, head, (ws) => {
          this.accept(new WsSocket(ws))
        })
      })
    }

    const server = net.createServer((socket) => this.route(socket))
    this.server = server
    server.on('error', (error) => {
      this.ctx?.logger?.warn?.(`[phone-control] listen error: ${error.message}`)
    })
    this.port = await this.pickPort()
    let lastError = null
    for (let attempt = 0; attempt < 3; attempt += 1) {
      try {
        await new Promise((resolve, reject) => {
          server.once('error', reject)
          server.listen(this.port, this.bindHost, () => {
            server.off('error', reject)
            resolve()
          })
        })
        lastError = null
        break
      } catch (error) {
        lastError = error
        if (error?.code !== 'EADDRINUSE') break
        this.ctx?.logger?.warn?.(`[phone-control] port ${this.port} busy, retrying with another`)
        this.port = await this.pickPort()
      }
    }
    if (lastError !== null) throw lastError
    const address = server.address()
    if (typeof address === 'object' && address !== null) this.port = address.port
    this.requestedPort = this.port
    this.autoPort = false
    await this.saveSettings()
    this.ctx?.logger?.info?.(`[phone-control] listening on ${this.bindHost}:${this.port}`)
  }

  route(socket) {
    socket.once('data', (chunk) => {
      const head = chunk.subarray(0, 8).toString('latin1')
      const isHttp = /^(GET|POST|PUT|DELETE|OPTIONS|HEAD|PATCH) /u.test(head)
      socket.pause()
      socket.unshift(chunk)
      if (isHttp && this.httpServer !== undefined) {
        this.httpServer.emit('connection', socket)
      } else {
        this.accept(socket)
      }
      socket.resume()
    })
  }

  accept(socket) {
    const session = {
      socket,
      state: 'hello',
      hello: null,
      salt: null,
      nonceS: null,
      ecdh: null,
      sessionKey: null,
      outSeq: 0,
      inSeq: -1n,
      chain: Promise.resolve(),
      closed: false,
    }
    this.sessions.add(session)
    socket.setNoDelay(true)
    socket.setTimeout(15000, () => {
      if (session.state !== 'ready') socket.destroy(new Error('handshake timeout'))
    })
    socket.on('close', () => {
      session.closed = true
      this.sessions.delete(session)
      this.rejectPending(session, new Error('DEVICE_DISCONNECTED: 手机连接已断开'))
    })
    socket.on('error', (error) => {
      this.ctx?.logger?.warn?.(`[phone-control] socket error: ${error.message}`)
    })
    const parser = new FrameParser((frame, error) => {
      if (error) {
        socket.destroy(error)
        return
      }
      session.chain = session.chain
        .then(() => this.handleFrame(session, frame))
        .catch((error2) => {
          this.ctx?.logger?.warn?.(`[phone-control] frame error: ${error2.message}`)
          socket.destroy()
        })
    })
    socket.on('data', (chunk) => {
      try {
        parser.push(chunk)
      } catch (error) {
        this.ctx?.logger?.warn?.(`[phone-control] parser error: ${error.message}`)
        socket.destroy()
      }
    })
  }

  async handleFrame(session, frame) {
    if (session.closed) return
    let message
    if ((frame.flags & 1) === 1) {
      if (session.sessionKey === null || session.state !== 'ready') throw new Error('encrypted frame before auth')
      if (frame.seq <= session.inSeq) throw new Error('replayed or out-of-order frame')
      session.inSeq = frame.seq
      const plain = openFrame(frame, session.sessionKey)
      message = JSON.parse(plain.toString('utf8'))
    } else {
      if (frame.payload.length > 64 * 1024) throw new Error('handshake frame too large')
      message = JSON.parse(frame.payload.toString('utf8'))
    }
    if (message === null || typeof message !== 'object') throw new Error('bad message')

    if (session.state === 'hello') {
      await this.handleHello(session, message)
      return
    }
    if (session.state === 'challenge') {
      await this.handleAuth(session, message)
      return
    }
    if (session.state === 'ready') {
      if (message.type === 'RESULT') {
        this.resolveResult(message)
        return
      }
      if (message.type === 'CMD' || typeof message.cmd === 'string') {
        await this.handleCommand(session, message)
        return
      }
      throw new Error('bad ready message')
    }
    throw new Error('bad state')
  }

  sendJson(session, payload, encrypted = true) {
    if (session.closed) return
    if (encrypted && session.sessionKey !== null) {
      session.outSeq += 1
      session.socket.write(sealFrame({ seq: session.outSeq, key: session.sessionKey, payload }))
    } else {
      session.socket.write(encodeFrame({ flags: 0, payload }))
    }
  }

  request(cmd, args = {}, timeoutMs = 15000) {
    const session = this.readySession()
    if (session === undefined) {
      return Promise.reject(new Error('NO_DEVICE: 手机未连接，请先在 App 里连接电脑'))
    }
    const id = ++this.nextId
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id)
        reject(new Error(`TIMEOUT: 手机未在 ${Math.round(timeoutMs / 1000)} 秒内响应 ${cmd}`))
      }, timeoutMs)
      this.pending.set(id, { resolve, reject, timer, session })
      try {
        this.sendJson(session, { type: 'CMD', id, cmd, args })
      } catch (error) {
        clearTimeout(timer)
        this.pending.delete(id)
        reject(error)
      }
    })
  }

  resolveResult(message) {
    const entry = this.pending.get(message.id)
    if (entry === undefined) return
    clearTimeout(entry.timer)
    this.pending.delete(message.id)
    if (message.ok === true) {
      entry.resolve(message.result ?? {})
    } else {
      const code = String(message.error?.code ?? 'PHONE_ERROR')
      const text = String(message.error?.message ?? '手机执行失败')
      entry.reject(new Error(`${code}: ${text}`))
    }
  }

  rejectPending(session, error) {
    for (const [id, entry] of this.pending) {
      if (entry.session !== session) continue
      clearTimeout(entry.timer)
      this.pending.delete(id)
      entry.reject(error)
    }
  }

  disconnectAll(reason = 'PC_DISCONNECTED: 电脑端已断开') {
    let closed = 0
    for (const session of this.sessions) {
      if (session.state !== 'ready') continue
      session.closed = true
      this.sessions.delete(session)
      this.rejectPending(session, new Error(reason))
      try {
        session.socket.destroy()
      } catch {
        // ignore
      }
      closed += 1
    }
    return closed
  }

  async handleHello(session, msg) {
    if (msg.type !== 'HELLO') {
      this.sendJson(session, err('BAD_REQUEST', 'expected HELLO'), false)
      session.socket.end()
      return
    }
    const deviceId = String(msg.deviceId ?? '').slice(0, 128)
    const nonceC = String(msg.nonceC ?? '')
    if (deviceId.length === 0 || nonceC.length === 0) {
      this.sendJson(session, err('BAD_REQUEST', 'deviceId/nonceC required'), false)
      session.socket.end()
      return
    }

    let mode
    let secret
    if (typeof msg.token === 'string' && msg.token.length > 0) {
      const gotHash = sha256(fromB64u(msg.token))
      const current = this.tokenHash !== null && safeEqual(gotHash, this.tokenHash)
      const previous = this.previousTokenHash !== null
        && Date.now() < this.previousTokenExpiresAt
        && safeEqual(gotHash, this.previousTokenHash)
      const match = current || previous
      tokenDebug(`hello token gotLen=${gotHash.length} hasCurrent=${this.tokenHash !== null} previous=${previous} match=${match}`)
      if (!match) {
        this.sendJson(session, err('TOKEN_INVALID', '登录凭证已失效，请重新配对'), false)
        session.socket.end()
        return
      }
      if (previous) {
        this.tokenHash = gotHash
        this.previousTokenHash = null
        this.previousTokenExpiresAt = 0
        void this.saveToken()
      }
      mode = 'token'
      secret = msg.token
    } else if (typeof msg.key === 'string' && msg.key.length > 0) {
      if (Date.now() < this.lockedUntil) {
        const left = Math.ceil((this.lockedUntil - Date.now()) / 1000)
        this.sendJson(session, err('LOCKED', `尝试次数过多，已锁定，${left} 秒后可重试`), false)
        session.socket.end()
        return
      }
      if (Date.now() > this.codeExpiresAt) {
        this.sendJson(session, err('CODE_EXPIRED', '配对码已过期，请在电脑端刷新配对码'), false)
        session.socket.end()
        return
      }
      if (!/^\d{8}$/u.test(msg.key) || msg.key !== this.code) {
        this.failedAttempts += 1
        if (this.failedAttempts >= 5) {
          this.lockedUntil = Date.now() + 5 * 60_000
          this.sendJson(session, err('LOCKED', '配对码错误次数过多，已锁定 5 分钟'), false)
          session.socket.end()
          return
        }
        const left = 5 - this.failedAttempts
        this.sendJson(session, err('BAD_CODE', `配对码错误，还可以再试 ${left} 次`), false)
        session.socket.end()
        return
      }
      this.failedAttempts = 0
      mode = 'key'
      secret = msg.key
    } else {
      this.sendJson(session, err('BAD_REQUEST', 'key or token required'), false)
      session.socket.end()
      return
    }

    const salt = randomB64u(16)
    const nonceS = randomB64u(16)
    const ecdh = makeEcdh()
    session.hello = { mode, secret, deviceId, nonceC }
    session.salt = salt
    session.nonceS = nonceS
    session.ecdh = ecdh
    session.state = 'challenge'
    session.socket.setTimeout(15000)
    this.sendJson(
      session,
      {
        type: 'CHALLENGE',
        salt,
        nonceS,
        serverPub: b64u(ecdh.getPublicKey()),
        mode,
      },
      false,
    )
  }

  async handleAuth(session, msg) {
    if (msg.type !== 'AUTH' || typeof msg.clientPub !== 'string' || typeof msg.proofC !== 'string') {
      this.sendJson(session, err('BAD_REQUEST', 'expected AUTH'), false)
      session.socket.end()
      return
    }
    const hello = session.hello
    const clientPub = fromB64u(msg.clientPub)
    if (clientPub.length !== 65) {
      this.sendJson(session, err('BAD_REQUEST', 'bad clientPub'), false)
      session.socket.end()
      return
    }
    let derived
    try {
      derived = deriveSession({
        mode: hello.mode,
        secret: hello.secret,
        deviceId: hello.deviceId,
        salt: session.salt,
        nonceC: hello.nonceC,
        nonceS: session.nonceS,
        peerPub: clientPub,
        ecdh: session.ecdh,
      })
    } catch {
      this.sendJson(session, err('BAD_REQUEST', 'ECDH failed'), false)
      session.socket.end()
      return
    }
    if (!safeEqual(fromB64u(msg.proofC), derived.proofC)) {
      this.sendJson(session, err('BAD_PROOF', 'proof invalid'), false)
      session.socket.end()
      return
    }

    let tokenOut = null
    if (hello.mode === 'key') {
      const oldHash = this.tokenHash
      tokenOut = randomB64u(32)
      this.previousTokenHash = oldHash
      this.previousTokenExpiresAt = Date.now() + 10 * 60_000
      this.tokenHash = sha256(fromB64u(tokenOut))
      this.pairedAt = Date.now()
      void this.saveToken()
      this.refreshCode(true)
    }
    session.sessionKey = derived.sessionKey
    session.state = 'ready'
    session.inSeq = 0n
    session.socket.setTimeout(0)
    this.sendJson(session, {
      type: 'OK',
      ok: true,
      token: tokenOut,
      proofS: b64u(derived.proofS),
      expiresAt: Date.now() + 30 * 24 * 3600 * 1000,
      serverTime: Date.now(),
    })
  }

  async handleCommand(session, msg) {
    const id = msg.id ?? null
    const cmd = String(msg.cmd ?? '')
    const args = msg.args ?? {}
    let result
    if (cmd === 'ping') {
      result = { pong: true, time: Date.now() }
    } else if (cmd === 'status') {
      result = this.status()
    } else if (cmd === 'echo') {
      result = { echo: args }
    } else {
      this.sendJson(session, { ...err('UNSUPPORTED', `unknown cmd ${cmd}`), type: 'RESULT', id })
      return
    }
    this.sendJson(session, { type: 'RESULT', id, ok: true, result })
  }

  dispose() {
    this.stopListener()
  }
}
