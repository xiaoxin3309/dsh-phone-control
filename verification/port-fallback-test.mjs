// 固定端口被临时占用时的回归测试：
// 端口绝不能因为一次 EADDRINUSE 就被改成随机端口并写回设置 ——
// 那样指向原端口的 cloudflared 隧道会永久失联（2026-11-11 8788→8898、2026-11-13 8788→9316 各踩一次）。
// 用法：node verification/port-fallback-test.mjs
import net from 'node:net'
import path from 'node:path'
import { mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { fileURLToPath, pathToFileURL } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const PORT = 18791
const tmpProfile = path.join(ROOT, '..', '.tmp', 'port-fallback', 'profile')
rmSync(tmpProfile, { recursive: true, force: true })
mkdirSync(tmpProfile, { recursive: true })
process.env.DSH_PROFILE_DIR = tmpProfile

const settingsFile = path.join(tmpProfile, 'phone-bridge.json')
writeFileSync(settingsFile, `${JSON.stringify({ port: PORT, bindHost: '127.0.0.1' }, null, 2)}\n`, 'utf8')

const checks = []
const check = (name, ok, detail = '') => {
  checks.push({ name, ok })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `   ${detail}` : ''}`)
}

const listen = (server, port) => new Promise((resolve, reject) => {
  server.once('error', reject)
  server.listen(port, '127.0.0.1', () => {
    server.off('error', reject)
    resolve()
  })
})
const close = (server) => new Promise((resolve) => server.close(() => resolve()))

// 1) 先把目标端口占住，模拟「DSH 重启瞬间上一个进程还没放掉 8788」
const blocker = net.createServer()
await listen(blocker, PORT)

const { SpikeService } = await import(pathToFileURL(path.join(ROOT, 'plugin', 'lib', 'service.js')).href)
const logger = { info() {}, warn() {}, debug() {} }
const service = new SpikeService({ ctx: { logger }, config: { port: PORT, bindHost: '127.0.0.1' } })
await service.start()

const fallbackStatus = service.status()
check('原端口被占时仍能启动', fallbackStatus.listening === true, `listening=${fallbackStatus.listening}`)
check('临时端口与原端口不同', fallbackStatus.port !== PORT, `port=${fallbackStatus.port}`)
check('requestedPort 仍是原端口', fallbackStatus.requestedPort === PORT, `requestedPort=${fallbackStatus.requestedPort}`)
check('status 暴露 portFallbackFrom', fallbackStatus.portFallbackFrom === PORT, `portFallbackFrom=${fallbackStatus.portFallbackFrom}`)

const savedAfterFallback = JSON.parse(readFileSync(settingsFile, 'utf8'))
check('设置文件没被临时端口覆盖', savedAfterFallback.port === PORT, `file.port=${savedAfterFallback.port}`)

// 2) 原端口空出来后，应自动搬回去
await close(blocker)
await service.reclaimPort()
const reclaimed = service.status()
check('原端口空出后搬回原端口', reclaimed.port === PORT, `port=${reclaimed.port}`)
check('搬回后仍在监听', reclaimed.listening === true, `listening=${reclaimed.listening}`)
check('搬回后不再标记 fallback', reclaimed.portFallbackFrom === 0, `portFallbackFrom=${reclaimed.portFallbackFrom}`)

const savedAfterReclaim = JSON.parse(readFileSync(settingsFile, 'utf8'))
check('设置文件仍是原端口', savedAfterReclaim.port === PORT, `file.port=${savedAfterReclaim.port}`)

service.dispose()
await new Promise((r) => setTimeout(r, 100))

const failed = checks.filter((c) => !c.ok).length
console.log(`\n${checks.length - failed} passed, ${failed} failed`)
process.exit(failed === 0 ? 0 : 1)
