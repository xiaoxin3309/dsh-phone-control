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
const listeners = new Map()
let provided = null
const ctx = {
  provide(name, value) {
    provided = { name, value }
  },
  on(name, handler, _options) {
    listeners.set(name, handler)
    return () => listeners.delete(name)
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
  // v0.1.4 新增：手动推手机通知（probe 可查手机端权限与开关）
  'phone_notify',
]
for (const name of expected) check(`注册工具 ${name}`, tools.has(name))
check('工具总数为 22', tools.size >= 22, `size=${tools.size}`)

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

// ---- 会话通知：直接喂会话事件，看它怎么组包 ----
const { createNotifier } = await import(pathToFileURL(path.join(ROOT, 'plugin', 'lib', 'notify.js')).href)
const sent = []
const notifier = createNotifier({
  service: {
    async request(cmd, args) {
      sent.push({ cmd, args })
      return { shown: true, reason: 'shown' }
    },
  },
  logger: { warn() {} },
})
const session = { id: 'sess-abcdef123456', header: { id: 'sess-abcdef123456' } }
notifier.observe(session, { type: 'session/title', data: { title: '修登录页' } })
notifier.observe(session, { type: 'turn/start', data: { turn: 1 } })
notifier.observe(session, {
  type: 'assistant/message',
  data: { turn: 1, message: { content: [{ type: 'text', text: '登录页修好了。' }] } },
})
notifier.observe(session, { type: 'turn/end', data: { turn: 1, reason: { kind: 'completed' } } })
notifier.observe(session, { type: 'turn/end', data: { turn: 2, reason: { kind: 'aborted' } } })
notifier.observe(session, {
  type: 'tool/call',
  data: { name: 'ask_user_question', arguments: { questions: [{ question: '要保留旧的扫码入口吗？' }] } },
})
// 插件晚于标题事件挂载时，靠会话历史回查补出真实标题（sess-abcdef… 那条是实时事件路径）
const historical = {
  id: 'sess-9876543210fe',
  header: { id: 'sess-9876543210fe' },
  snapshotEvents() {
    return [
      { type: 'turn/start', data: { turn: 1 } },
      { type: 'session/title', data: { title: '手机桥配对排错' } },
    ]
  },
}
notifier.observe(historical, {
  type: 'tool/call',
  data: { name: 'ask_user_question', arguments: { questions: [{ question: '要继续吗？' }] } },
})
await new Promise((r) => setTimeout(r, 120))
check('回答结束推一条 complete 通知（带会话名与摘要）',
  sent.length >= 2 && sent[0]?.cmd === 'notify' && sent[0].args.kind === 'complete'
    && sent[0].args.title.includes('修登录页') && sent[0].args.body.includes('登录页修好了'),
  JSON.stringify(sent[0]?.args ?? null))
check('提问推一条 question 通知（带问题原文）',
  sent[1]?.args.kind === 'question' && sent[1].args.body.includes('要保留旧的扫码入口吗'),
  JSON.stringify(sent[1]?.args ?? null))
check('标题事件早于插件挂载时，从会话历史回查到真实标题',
  sent[2]?.args.kind === 'question'
    && sent[2].args.title.includes('手机桥配对排错')
    && !sent[2].args.title.includes('会话 sess-9876'),
  JSON.stringify(sent[2]?.args ?? null))
check('非 completed 的 turn/end 不推送', sent.filter((s) => s.args.kind === 'complete').length === 1)

const manualNoDevice = await tools.get('phone_notify').execute({ body: '测试' })
check('无设备时 phone_notify 返回 no_device 而不抛错',
  manualNoDevice.ok === false && manualNoDevice.reason === 'no_device', JSON.stringify(manualNoDevice))

let probeErr = ''
try {
  await tools.get('phone_notify').execute({ probe: true })
} catch (error) {
  probeErr = String(error.message ?? error)
}
check('无设备时 phone_notify probe 报 NO_DEVICE', probeErr.includes('NO_DEVICE'), probeErr)

const disconnect = await tools.get('phone_disconnect').execute({})
check('phone_disconnect 可执行', disconnect.ok === true)

for (const dispose of disposers) dispose()
await new Promise((r) => setTimeout(r, 300))

const failed = checks.filter((c) => !c.ok)
console.log(`\n结果：PASS ${checks.length - failed.length} / FAIL ${failed.length}`)
process.exit(failed.length === 0 ? 0 : 1)

