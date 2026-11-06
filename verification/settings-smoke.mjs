// 设置页/自动端口 的隔离冒烟测试：不碰真实 profile，也不碰 8788。
// 用法：node verification/settings-smoke.mjs
import http from 'node:http'
import path from 'node:path'
import os from 'node:os'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { mkdtemp, readFile } from 'node:fs/promises'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const PLUGIN = path.resolve(HERE, '..', 'plugin')

const tmp = await mkdtemp(path.join(os.tmpdir(), 'dsh-pb-settings-'))
process.env.DSH_PROFILE_DIR = tmp

const { SpikeService } = await import(pathToFileURL(path.join(PLUGIN, 'lib', 'service.js')).href)
const { BASE_PATH, createHandler } = await import(pathToFileURL(path.join(PLUGIN, 'lib', 'routes.js')).href)

const results = []
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail === '' ? '' : ` — ${detail}`}`)
}

const logger = { info() {}, warn() {}, error() {} }

// ---- 1. 自动端口：port: 0 时应挑一个 8000–9999 的空闲端口
const service = new SpikeService({ ctx: { logger }, config: { port: 0, bindHost: '127.0.0.1' } })
await service.start()
const first = service.status()
check('自动端口落在 8000–9999', first.port >= 8000 && first.port <= 9999, `port=${first.port}`)
check('监听已启动', first.listening === true)
const settingsFile = path.join(tmp, 'phone-bridge.json')
const saved = JSON.parse(await readFile(settingsFile, 'utf8'))
check('设置文件已落盘且端口一致', saved.port === first.port, JSON.stringify(saved))

// ---- 2. 设置页路由：GET /state
const server = http.createServer(createHandler(service, { logger }))
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
const routePort = server.address().port
const base = `http://127.0.0.1:${routePort}${BASE_PATH}`

const state = await (await fetch(`${base}/state`)).json()
check('GET /state 返回状态', state.ok === true && state.status?.pairingCode?.length === 8)
check('GET /state 带 settings', typeof state.settings?.port === 'number')

// ---- 3. 换码
const beforeCode = state.status.pairingCode
service.failedAttempts = 3
const refreshed = await (await fetch(`${base}/refresh`, { method: 'POST' })).json()
check('POST /refresh 换新码', refreshed.status.pairingCode !== beforeCode)
check('换码同时清零失败计数', refreshed.status.failedAttempts === 0)

// ---- 4. 改端口 → 重新绑定
const target = first.port === 8788 ? 8789 : 8788
const applied = await (
  await fetch(`${base}/settings`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ port: target }),
  })
).json()
check('POST /settings 生效', applied.status.port === target, `port=${applied.status.port}`)
check('重新绑定后仍在监听', applied.status.listening === true)
const reused = JSON.parse(await readFile(settingsFile, 'utf8'))
check('新端口写入设置文件', reused.port === target, JSON.stringify(reused))

// ---- 5. 非法端口被拒绝
const badPort = await fetch(`${base}/settings`, {
  method: 'POST',
  headers: { 'content-type': 'application/json' },
  body: JSON.stringify({ port: 80 }),
})
const badBody = await badPort.json()
check('保留端口 80 被拒绝', badPort.status === 400 && /系统保留端口/u.test(String(badBody.error)), String(badBody.error))

const badHost = await fetch(`${base}/settings`, {
  method: 'POST',
  headers: { 'content-type': 'application/json' },
  body: JSON.stringify({ bindHost: '8.8.8.8' }),
})
check('非法绑定地址被拒绝', badHost.status === 400)

// ---- 6. 跨站请求被拒绝（Origin 与 Host 不一致）
const cross = await fetch(`${base}/state`, { headers: { origin: 'http://evil.example' } })
check('跨站 Origin 被拒绝', cross.status === 403)

// ---- 7. 断开
const disc = await (await fetch(`${base}/disconnect`, { method: 'POST' })).json()
check('POST /disconnect 可用', disc.ok === true && typeof disc.closed === 'number')

// ---- 8. 重启后沿用设置文件里的端口
service.dispose()
const second = new SpikeService({ ctx: { logger }, config: { port: 0, bindHost: '127.0.0.1' } })
await second.start()
check('重启沿用保存的端口', second.status().port === target, `port=${second.status().port}`)
second.dispose()

server.close()
const failed = results.filter((r) => !r.ok)
console.log(`\nPASS ${results.length - failed.length} / FAIL ${failed.length}`)
process.exit(failed.length === 0 ? 0 : 1)
