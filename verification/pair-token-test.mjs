// M2 验收：token 断开/重连 + 配对码过期/锁定
// 用隔离实例（独立端口 + 独立 profile 目录）跑协议层测试，绝不碰真实插件与手机 token。
import net from 'node:net'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { mkdirSync, rmSync, readFileSync } from 'node:fs'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const PLUGIN = path.join(ROOT, 'plugin', 'lib')
const PORT = 18788
const CODE = '12345678'
const tmpProfile = path.join(ROOT, '.tmp', 'pair-test', 'profile')
rmSync(tmpProfile, { recursive: true, force: true })
mkdirSync(tmpProfile, { recursive: true })
process.env.DSH_PROFILE_DIR = tmpProfile

const proto = await import(pathToFileURL(path.join(PLUGIN, 'protocol.js')).href)
const { SpikeService } = await import(pathToFileURL(path.join(PLUGIN, 'service.js')).href)

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
let pass = 0
let fail = 0
function check(name, cond, extra = '') {
  if (cond) {
    pass += 1
    console.log(`PASS  ${name}${extra ? '   ' + extra : ''}`)
  } else {
    fail += 1
    console.log(`FAIL  ${name}${extra ? '   ' + extra : ''}`)
  }
}

function openClient(port) {
  const socket = net.connect(port, '127.0.0.1')
  socket.setNoDelay(true)
  const c = { socket, sessionKey: null, outSeq: 0, closed: false }
  const parser = new proto.FrameParser((frame, error) => {
    if (error) {
      c.closed = true
      c.onerror?.(error)
      return
    }
    let msg
    try {
      if ((frame.flags & 1) === 1) {
        if (!c.sessionKey) throw new Error('encrypted before key')
        msg = JSON.parse(proto.openFrame(frame, c.sessionKey).toString('utf8'))
      } else {
        msg = JSON.parse(frame.payload.toString('utf8'))
      }
    } catch (e) {
      c.onerror?.(e)
      return
    }
    c.onmessage?.(msg)
  })
  socket.on('data', (d) => parser.push(d))
  socket.on('error', (e) => {
    c.closed = true
    c.onerror?.(e)
  })
  socket.on('close', () => {
    c.closed = true
    c.onclose?.()
  })
  c.send = (payload, encrypted = false) => {
    if (encrypted) {
      c.outSeq += 1
      socket.write(proto.sealFrame({ seq: c.outSeq, key: c.sessionKey, payload }))
    } else {
      socket.write(proto.encodeFrame({ flags: 0, payload }))
    }
  }
  return c
}

// 完成一次 HELLO/CHALLENGE/AUTH/OK 握手；失败返回 {ok:false, code}
function handshake(port, { key, token, deviceId = 'test-device-A' }) {
  return new Promise((resolve) => {
    const c = openClient(port)
    const ecdh = proto.makeEcdh()
    const nonceC = proto.randomB64u(16)
    let derived = null
    let done = false
    const finish = (v) => {
      if (done) return
      done = true
      clearTimeout(timer)
      resolve(v)
    }
    const timer = setTimeout(() => {
      c.socket.destroy()
      finish({ ok: false, code: 'TIMEOUT', message: 'no reply in 3s' })
    }, 3000)
    c.onerror = (e) => finish({ ok: false, code: 'SOCKET', message: String(e.message) })
    c.onclose = () => finish({ ok: false, code: 'CLOSED', message: 'socket closed before OK' })
    c.onmessage = (msg) => {
      if (msg.type === 'ERR') {
        c.socket.destroy()
        finish({ ok: false, code: msg.error?.code, message: msg.error?.message })
        return
      }
      if (msg.type === 'CHALLENGE') {
        derived = proto.deriveSession({
          mode: msg.mode,
          secret: token ?? key,
          deviceId,
          salt: msg.salt,
          nonceC,
          nonceS: msg.nonceS,
          peerPub: proto.fromB64u(msg.serverPub),
          ecdh,
        })
        c.sessionKey = derived.sessionKey
        c.send({ type: 'AUTH', clientPub: proto.b64u(ecdh.getPublicKey()), proofC: proto.b64u(derived.proofC) })
        return
      }
      if (msg.type === 'OK') {
        const proofOk = proto.safeEqual(proto.fromB64u(msg.proofS), derived.proofS)
        finish({ ok: true, client: c, token: msg.token ?? null, proofOk, expiresAt: msg.expiresAt })
      }
    }
    c.send({ type: 'HELLO', deviceId, nonceC, ...(token ? { token } : { key }) })
  })
}

function ping(c) {
  return new Promise((resolve) => {
    const t = setTimeout(() => resolve({ ok: false, code: 'TIMEOUT' }), 2000)
    c.onmessage = (m) => {
      if (m.type === 'RESULT') {
        clearTimeout(t)
        resolve({ ok: m.ok === true, result: m.result })
      }
    }
    c.send({ type: 'CMD', id: 1, cmd: 'ping' }, true)
  })
}

const logger = { info() {}, warn() {} }
function makeService() {
  return new SpikeService({
    ctx: { logger },
    config: { port: PORT, bindHost: '127.0.0.1', pairingCode: CODE, autoCode: false },
  })
}

const svc = makeService()
await svc.start()
console.log(`--- 隔离实例已启动 127.0.0.1:${PORT}（profile=${tmpProfile}）---`)
const st0 = svc.status()
check('初始状态：pairingCode 为配置值且 5 分钟 TTL', st0.pairingCode === CODE && st0.codeExpiresAt - st0.codeIssuedAt === 300000, `code=${st0.pairingCode}`)

// 1) 错误配对码 → BAD_CODE，剩余次数递减
const bad1 = await handshake(PORT, { key: '00000000' })
check('错误配对码被拒（BAD_CODE）', !bad1.ok && bad1.code === 'BAD_CODE', `${bad1.code} / ${bad1.message}`)
check('提示剩余 4 次（中文）', /再试 4 次/.test(bad1.message ?? ''), bad1.message)

// 2) 再错 4 次 → 累计 5 次触发锁定
let last = null
for (let i = 0; i < 4; i += 1) last = await handshake(PORT, { key: '00000000' })
check('第 5 次错误直接返回 LOCKED（不再报 0 attempts left）', last?.code === 'LOCKED' && /已锁定 5 分钟/.test(last.message ?? ''), last?.message)
const st1 = svc.status()
check('锁定已生效（lockedUntil 非空，约 5 分钟）', typeof st1.lockedUntil === 'number' && st1.lockedUntil - Date.now() > 250000, `lockedUntil=${st1.lockedUntil}`)

// 3) 锁定期间即使配对码正确也应被拒（LOCKED 优先于 CODE_EXPIRED / 比对）
const locked = await handshake(PORT, { key: CODE })
check('锁定期间正确配对码也被拒（LOCKED）', !locked.ok && locked.code === 'LOCKED', `${locked.code} / ${locked.message}`)

// 4) 锁定期间 refreshCode() 不应清锁（force=false 被 lockedUntil 挡住）
const codeBefore = svc.status().pairingCode
const failedBefore = svc.status().failedAttempts
svc.refreshCode(false)
check('锁定期间 refreshCode 不生效', svc.status().failedAttempts === failedBefore && svc.status().pairingCode === codeBefore, `attempts=${failedBefore}`)

// 5) 强制刷新配对码可解除锁定（对应 phone_pair_code refresh=true）
svc.refreshCode(true)
check('强制刷新解除锁定并清零失败计数', svc.status().failedAttempts === 0 && svc.status().lockedUntil === null, 'attempts=' + svc.status().failedAttempts + ' locked=' + svc.status().lockedUntil)
svc.codeExpiresAt = Date.now() - 1000
const expired = await handshake(PORT, { key: CODE })
check('配对码过期被拒（CODE_EXPIRED）', !expired.ok && expired.code === 'CODE_EXPIRED', `${expired.code} / ${expired.message}`)

// 6) 新配对码配对成功 → 拿到 token
svc.refreshCode(true)
const paired = await handshake(PORT, { key: CODE })
const token1 = paired.token
check('正确配对码配对成功（OK）', paired.ok === true, `ok=${paired.ok}`)
check('服务端返回 token 且 proofS 校验通过', typeof token1 === 'string' && token1.length > 0 && paired.proofOk === true, `tokenLen=${token1?.length ?? 0} proofOk=${paired.proofOk}`)
check('配对后 status.paired=true', svc.status().paired === true)

// 7) token 会话可用（加密通道 CMD ping）
const pingRes = await ping(paired.client)
check('token 会话加密通道可用（ping→pong）', pingRes.ok === true && pingRes.result?.pong === true, JSON.stringify(pingRes.result))

// 8) 错误 token 被拒
const badToken = await handshake(PORT, { token: proto.randomB64u(32) })
check('伪造 token 被拒（TOKEN_INVALID）', !badToken.ok && badToken.code === 'TOKEN_INVALID', `${badToken.code} / ${badToken.message}`)

// 9) 断开后重连：同一 token 直接认证，无需重新配对
paired.client.socket.destroy()
await sleep(150)
const reconnected = await handshake(PORT, { token: token1, deviceId: 'test-device-B' })
check('断线后用同一 token 重连成功（token 模式，不下发新 token）', reconnected.ok === true && reconnected.token === null, `tokenOut=${reconnected.token}`)
check('重连后 status.connected=true', svc.status().connected === true)

// 10) 重新配对 → 旧 token 进入 previousTokenHash（10 分钟宽限）
const paired2 = await handshake(PORT, { key: CODE })
const token2 = paired2.token
check('再次配对拿新 token', paired2.ok === true && typeof token2 === 'string' && token2 !== token1)
check('旧 token 被记为 previous（10 分钟宽限）', svc.previousTokenHash !== null, `prevExpiresIn=${Math.round((svc.previousTokenExpiresAt - Date.now()) / 1000)}s`)

// 11) 宽限期内旧 token 仍可连，且会被提升为当前 token
reconnected.client.socket.destroy()
await sleep(100)
const oldTokenConn = await handshake(PORT, { token: token1, deviceId: 'test-device-C' })
check('宽限期内旧 token 仍可连接', oldTokenConn.ok === true)
check('旧 token 连接后提升为当前 token', svc.tokenHash !== null && svc.tokenHash.toString('base64') === proto.sha256(proto.fromB64u(token1)).toString('base64'))

// 12) PC 主动断开 → 手机侧 socket 收到关闭，重连可恢复
const closedFlag = { closed: false }
oldTokenConn.client.onclose = () => {
  closedFlag.closed = true
}
const killed = svc.disconnectAll()
await sleep(300)
check('PC 端 disconnectAll 断开就绪会话', killed >= 1 && closedFlag.closed === true, `killed=${killed} closed=${closedFlag.closed}`)
const afterKill = await handshake(PORT, { token: token1, deviceId: 'test-device-D' })
check('被断开后手机可立即重连', afterKill.ok === true)

// 13) 持久化：新实例从 token 文件恢复，token 仍有效（插件重载场景）
svc.dispose()
await sleep(300)
const tokenFile = path.join(tmpProfile, 'phone-bridge-token.json')
const saved = JSON.parse(readFileSync(tokenFile, 'utf8'))
check('token 文件已写入且不含明文 token', typeof saved.tokenHash === 'string' && saved.tokenHash.length > 0 && !('token' in saved), `pairedAt=${saved.pairedAt}`)
const svc2 = makeService()
await svc2.start()
check('新实例加载 token 成功（paired=true）', svc2.status().paired === true)
const afterReload = await handshake(PORT, { token: token1, deviceId: 'test-device-E' })
check('插件重载后旧 token 仍可直接重连', afterReload.ok === true, `${afterReload.code ?? ''} ${afterReload.message ?? ''}`)
afterReload.client?.socket.destroy()
svc2.dispose()
await sleep(200)

console.log(`\n结果：PASS ${pass} / FAIL ${fail}`)
process.exit(fail === 0 ? 0 : 1)
