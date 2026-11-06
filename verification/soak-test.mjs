// 稳定性压测：插件端 100 次命令 + 可选持续 soak + 周期性断线重连
// 用法：
//   node verification/soak-test.mjs                  # 起隔离实例，跑 100 次命令
//   node verification/soak-test.mjs --duration 1800  # 跑 30 分钟 soak（每 20 次断连重连一次）
import net from 'node:net'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { mkdirSync, rmSync } from 'node:fs'

const args = new Map()
for (let i = 2; i < process.argv.length; i += 1) {
  const raw = process.argv[i]
  if (!raw.startsWith('--')) continue
  const body = raw.slice(2)
  const eq = body.indexOf('=')
  if (eq >= 0) {
    args.set(body.slice(0, eq), body.slice(eq + 1))
    continue
  }
  const next = process.argv[i + 1]
  if (next !== undefined && !next.startsWith('--')) {
    args.set(body, next)
    i += 1
  } else {
    args.set(body, 'true')
  }
}
const PORT = Number(args.get('port') ?? 18789)
const COUNT = Number(args.get('count') ?? 100)
const DURATION = Number(args.get('duration') ?? 0) // 秒
const RECONNECT_EVERY = Number(args.get('reconnect-every') ?? 20)
const NO_SERVICE = args.get('no-service') === 'true'
for (const [name, value] of [['port', PORT], ['count', COUNT], ['duration', DURATION], ['reconnect-every', RECONNECT_EVERY]]) {
  if (!Number.isFinite(value)) throw new Error(`参数 --${name} 不是有效数字：${args.get(name)}`)
}
if (DURATION <= 0 && COUNT <= 0) throw new Error('请至少给 --duration 或 --count 一个正数')

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const PLUGIN = path.join(ROOT, 'plugin', 'lib')
const tmpProfile = path.join(ROOT, '.tmp', 'soak', 'profile')
rmSync(tmpProfile, { recursive: true, force: true })
mkdirSync(tmpProfile, { recursive: true })
process.env.DSH_PROFILE_DIR = tmpProfile

const proto = await import(pathToFileURL(path.join(PLUGIN, 'protocol.js')).href)
const { SpikeService } = await import(pathToFileURL(path.join(PLUGIN, 'service.js')).href)

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const stats = { sent: 0, ok: 0, failed: 0, latencies: [], reconnects: 0, errors: [] }

// ---- 假手机：完成握手，并对每个 CMD 回 RESULT ----
class FakePhone {
  constructor(port, deviceId = 'soak-device') {
    this.port = port
    this.deviceId = deviceId
    this.token = null
    this.sessionKey = null
    this.outSeq = 0
    this.inSeq = -1n
    this.connected = false
    this.oncmd = null
  }

  connect(code) {
    return new Promise((resolve, reject) => {
      const socket = net.connect(this.port, '127.0.0.1')
      socket.setNoDelay(true)
      this.socket = socket
      const ecdh = proto.makeEcdh()
      const nonceC = proto.randomB64u(16)
      let derived = null
      const timer = setTimeout(() => {
        socket.destroy()
        reject(new Error('phony handshake timeout'))
      }, 5000)
      const parser = new proto.FrameParser((frame, error) => {
        if (error) {
          reject(error)
          return
        }
        let msg
        try {
          if ((frame.flags & 1) === 1) {
            if (this.sessionKey === null) throw new Error('encrypted before key')
            if (frame.seq <= this.inSeq) throw new Error('replayed frame')
            this.inSeq = frame.seq
            msg = JSON.parse(proto.openFrame(frame, this.sessionKey).toString('utf8'))
          } else {
            msg = JSON.parse(frame.payload.toString('utf8'))
          }
        } catch (e) {
          this.errors?.push(String(e.message))
          return
        }
        if (msg.type === 'CHALLENGE') {
          derived = proto.deriveSession({
            mode: msg.mode,
            secret: this.token ?? code,
            deviceId: this.deviceId,
            salt: msg.salt,
            nonceC,
            nonceS: msg.nonceS,
            peerPub: proto.fromB64u(msg.serverPub),
            ecdh,
          })
          this.sessionKey = derived.sessionKey
          socket.write(
            proto.encodeFrame({
              flags: 0,
              payload: { type: 'AUTH', clientPub: proto.b64u(ecdh.getPublicKey()), proofC: proto.b64u(derived.proofC) },
            }),
          )
          return
        }
        if (msg.type === 'OK') {
          clearTimeout(timer)
          this.token = msg.token ?? this.token
          this.connected = true
          resolve(this)
          return
        }
        if (msg.type === 'ERR') {
          clearTimeout(timer)
          socket.destroy()
          reject(Object.assign(new Error(msg.error?.message ?? 'refused'), { code: msg.error?.code }))
          return
        }
        if (msg.type === 'CMD') {
          this.oncmd?.(msg)
          this.outSeq += 1
          socket.write(
            proto.sealFrame({
              seq: this.outSeq,
              key: this.sessionKey,
              payload: { type: 'RESULT', id: msg.id, ok: true, result: { echo: msg.cmd, t: Date.now() } },
            }),
          )
        }
      })
      socket.on('data', (d) => parser.push(d))
      socket.on('error', (e) => reject(e))
      socket.on('close', () => {
        this.connected = false
      })
      socket.write(
        proto.encodeFrame({
          flags: 0,
          payload: {
            type: 'HELLO',
            deviceId: this.deviceId,
            nonceC,
            ...(this.token !== null ? { token: this.token } : { key: code }),
          },
        }),
      )
    })
  }

  close() {
    try {
      this.socket?.destroy()
    } catch {}
    this.connected = false
    this.sessionKey = null
    this.inSeq = -1n
    this.outSeq = 0
  }
}

const logger = { info() {}, warn() {} }
let service = null
let code = '12345678'
if (!NO_SERVICE) {
  service = new SpikeService({
    ctx: { logger },
    config: { port: PORT, bindHost: '127.0.0.1', pairingCode: code, autoCode: false },
  })
  await service.start()
  console.log(`隔离实例已启动 127.0.0.1:${PORT}`)
}

const phone = new FakePhone(PORT)
await phone.connect(code)
console.log('假手机已配对连接')

async function runOne(index) {
  const started = performance.now()
  try {
    const cmd = index % 3 === 0 ? 'ping' : index % 3 === 1 ? 'echo' : 'status'
    await service.request(cmd, { i: index }, 5000)
    const ms = performance.now() - started
    stats.sent += 1
    stats.ok += 1
    stats.latencies.push(ms)
  } catch (error) {
    stats.sent += 1
    stats.failed += 1
    stats.errors.push(`${error.message}`)
  }
}

const startedAt = Date.now()
const deadline = DURATION > 0 ? startedAt + DURATION * 1000 : null
let index = 0
const heapStart = process.memoryUsage().heapUsed

// 看门狗：即使循环卡住也要退出并留下日志（防止后台任务无限挂起）
const watchdogMs = (DURATION > 0 ? DURATION * 1000 : COUNT * 50) + 120_000
const watchdog = setTimeout(() => {
  console.log(`\nWATCHDOG：已运行 ${((Date.now() - startedAt) / 1000).toFixed(0)}s 仍未结束（已完成 ${index} 次命令），强制退出`)
  process.exit(2)
}, watchdogMs)
watchdog.unref?.()

do {
  await runOne(index)
  index += 1
  if (index % 500 === 0) {
    console.log(`进度：${index} 次命令，失败 ${stats.failed}，已运行 ${((Date.now() - startedAt) / 1000).toFixed(0)}s`)
  }
  if (RECONNECT_EVERY > 0 && index % RECONNECT_EVERY === 0) {
    phone.close()
    await sleep(120)
    try {
      await phone.connect(code)
      stats.reconnects += 1
    } catch (error) {
      stats.errors.push(`reconnect failed: ${error.message}`)
    }
  }
  if (deadline === null && index >= COUNT) break
  if (deadline !== null && Date.now() >= deadline) break
} while (true)

const heapEnd = process.memoryUsage().heapUsed
const sorted = [...stats.latencies].sort((a, b) => a - b)
const pct = (p) => (sorted.length === 0 ? 0 : sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))])

console.log('\n--- 结果 ---')
console.log(`命令：${stats.sent} 次，成功 ${stats.ok}，失败 ${stats.failed}`)
console.log(`延迟：平均 ${(stats.latencies.reduce((a, b) => a + b, 0) / Math.max(1, sorted.length)).toFixed(1)} ms，p50 ${pct(0.5).toFixed(1)} ms，p95 ${pct(0.95).toFixed(1)} ms，max ${sorted.at(-1)?.toFixed(1) ?? 0} ms`)
console.log(`断线重连：${stats.reconnects} 次`)
console.log(`耗时：${((Date.now() - startedAt) / 1000).toFixed(1)} s`)
console.log(`堆内存：${(heapStart / 1048576).toFixed(1)} MB → ${(heapEnd / 1048576).toFixed(1)} MB`)

let leaks = 0
if (service !== null) {
  const st = service.status()
  console.log(`服务端：connected=${st.connected} sessions=${st.sessions} pending=${st.pending}`)
  if (st.pending !== 0) {
    leaks += 1
    console.log(`FAIL 存在未清理的 pending 请求：${st.pending}`)
  }
  if (st.sessions !== 1) {
    console.log(`WARN sessions=${st.sessions}（预期 1，断连残留可能未清理）`)
  }
}
if (stats.failed > 0) {
  console.log(`FAIL 失败命令 ${stats.failed} 次：${stats.errors.slice(0, 5).join(' | ')}`)
}
const okRate = stats.sent === 0 ? 0 : stats.ok / stats.sent
console.log(`成功率：${(okRate * 100).toFixed(2)}%`)

phone.close()
clearTimeout(watchdog)
service?.dispose()
await sleep(200)
process.exit(stats.failed === 0 && leaks === 0 && okRate === 1 ? 0 : 1)
