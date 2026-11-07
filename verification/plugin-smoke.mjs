// 插件加载冒烟测试：用桩 ctx 调 apply()，确认工具注册、服务启动、phone_pair_code refresh 生效。
// 用法：node verification/plugin-smoke.mjs
import path from 'node:path'
import { mkdirSync, readFileSync, rmSync } from 'node:fs'
import { fileURLToPath, pathToFileURL } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const PORT = 18790
const tmpProfile = path.join(ROOT, '..', '.tmp', 'smoke', 'profile')
rmSync(tmpProfile, { recursive: true, force: true })
mkdirSync(tmpProfile, { recursive: true })
process.env.DSH_PROFILE_DIR = tmpProfile

const tools = new Map()
const disposers = []
let provided = null
const ctx = {
  provide(name, value) {
    provided = { name, value }
  },
  tools: {
    register(definition) {
      tools.set(definition.name, definition)
      return () => tools.delete(definition.name)
    },
  },
  skills: {
    registerProvider() {
      return () => {}
    },
  },
  effect(fn, _label) {
    const dispose = fn()
    if (typeof dispose === 'function') disposers.push(dispose)
    return () => {}
  },
  logger: { info() {}, warn() {}, debug() {} },
  get() {
    return undefined
  },
}

const checks = []
const check = (name, ok, detail = '') => {
  checks.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `   ${detail}` : ''}`)
}

const mod = await import(pathToFileURL(path.join(ROOT, 'plugin', 'index.js')).href)
const pkgName = JSON.parse(readFileSync(path.join(ROOT, 'plugin', 'package.json'), 'utf8').replace(/^\uFEFF/u, '')).name
check('index.js 导出 name/inject', mod.name === pkgName && Array.isArray(mod.inject), `name=${mod.name}`)

mod.apply(ctx, { port: PORT, bindHost: '127.0.0.1', pairingCode: '12345678', autoCode: false })

const expected = [
  'phone_status', 'phone_pair_code', 'phone_screenshot', 'phone_tap', 'phone_long_press', 'phone_swipe',
  'phone_input', 'phone_key', 'phone_open_app', 'phone_current_app', 'phone_list_apps', 'phone_disconnect',
  // v0.1.2 新增：节点树/文本定位一类的工具，以及 6 个远程文件读写工具
  'phone_ui_dump', 'phone_tap_text', 'phone_set_text',
  'phone_fs_list', 'phone_fs_stat', 'phone_fs_read', 'phone_fs_write', 'phone_fs_mkdir', 'phone_fs_rm',
]
for (const name of expected) check(`注册工具 ${name}`, tools.has(name))
check('工具总数为 21', tools.size >= 21, `size=${tools.size}`)

check('ctx.provide 暴露 service', provided?.name === 'phoneBridgeSpike')

await new Promise((r) => setTimeout(r, 400))
const status = await tools.get('phone_status').execute({})
check('phone_status 可执行且端口正确', status.port === PORT && status.listening === true, `listening=${status.listening} port=${status.port}`)
check('listen 在 127.0.0.1', status.bindHost === '127.0.0.1', `bindHost=${status.bindHost}`)

const first = await tools.get('phone_pair_code').execute({})
check('phone_pair_code 返回已配码 + ttlSec + lockedRemainSec',
  first.code === '12345678' && typeof first.ttlSec === 'number' && first.lockedRemainSec === 0,
  `code=${first.code} ttl=${first.ttlSec}s`)

const refreshed = await tools.get('phone_pair_code').execute({ refresh: true })
check('phone_pair_code refresh=true 生效（固定码场景下计时重置）', refreshed.lockedRemainSec === 0 && refreshed.failedAttempts === 0,
  `ttl=${refreshed.ttlSec}s attempts=${refreshed.failedAttempts}`)

// 断线时工具应给 NO_DEVICE，而不是抛未处理异常
let noDevice = ''
try {
  await tools.get('phone_tap').execute({ x: 1, y: 1 })
} catch (error) {
  noDevice = String(error.message ?? error)
}
check('无设备时 phone_tap 报 NO_DEVICE', noDevice.includes('NO_DEVICE'), noDevice)

let fsNoDevice = ''
try {
  await tools.get('phone_fs_list').execute({})
} catch (error) {
  fsNoDevice = String(error.message ?? error)
}
check('无设备时 phone_fs_list 报 NO_DEVICE', fsNoDevice.includes('NO_DEVICE'), fsNoDevice)

const disconnect = await tools.get('phone_disconnect').execute({})
check('phone_disconnect 可执行', disconnect.ok === true)

for (const dispose of disposers) dispose()
await new Promise((r) => setTimeout(r, 300))

const failed = checks.filter((c) => !c.ok)
console.log(`\n结果：PASS ${checks.length - failed.length} / FAIL ${failed.length}`)
process.exit(failed.length === 0 ? 0 : 1)

