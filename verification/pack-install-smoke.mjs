#!/usr/bin/env node
// 发布包冒烟：验证「别人 npm install 这个 tarball 之后插件还能起来」。
//
//   node verification/pack-install-smoke.mjs [安装前缀默认 C:\dsh-tmp\pkgtest]
//
// 做四件事：
//   1. 从 <前缀>/node_modules/dsh-phone-control 导入 index.js（而不是源码目录）；
//   2. 用桩 ctx 调 apply()，检查 12 个 phone_* 工具都注册了；
//   3. 起一个隔离实例（127.0.0.1:18791），确认监听、设置文件落盘、状态字段齐全；
//   4. 停掉实例并检查端口释放。
//
// 依赖是装在前缀里的那份 ws（不是本仓库的），所以这一步同时验证了 dependencies 声明正确。

import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const PREFIX = process.argv[2] ?? 'C:\\dsh-tmp\\pkgtest'
const PKG = path.join(PREFIX, 'node_modules', 'dsh-phone-control')
const PORT = 18791

let pass = 0
let fail = 0
function check(name, ok, detail = '') {
  if (ok) {
    pass += 1
    console.log(`  PASS  ${name}`)
  } else {
    fail += 1
    console.log(`  FAIL  ${name}${detail ? ` — ${detail}` : ''}`)
  }
}

if (!fs.existsSync(path.join(PKG, 'index.js'))) {
  console.error(`找不到安装好的包：${PKG}\n先跑 npm install <tarball> --prefix <前缀>`)
  process.exit(2)
}

const profileDir = path.join(PREFIX, 'prof')
fs.mkdirSync(profileDir, { recursive: true })
process.env.DSH_PROFILE_DIR = profileDir

const tools = []
let service = null
const effects = []
const ctx = {
  logger: { info() {}, warn() {}, error() {}, debug() {} },
  provide(name, value) {
    if (name === 'phoneBridgeSpike') service = value
  },
  tools: { register(tool) { tools.push(tool) } },
  skills: { registerProvider() {} },
  // 真 DSH 的 ctx.effect 会立刻执行回调并把返回的清理函数托管起来；
  // 这里照做（否则工具注册与 service.start() 都不会被调用）。
  effect(fn) {
    const result = fn()
    if (result && typeof result.then === 'function') effects.push(result)
    return () => {}
  },
  inject() { throw new Error('webServer 不在这个桩里') },
}

const mod = await import(pathToFileURL(path.join(PKG, 'index.js')).href)
const apply = mod.default ?? mod.apply
check('index.js 可导入且导出 apply()', typeof apply === 'function')
check('包名一致', mod.name === 'dsh-phone-control', String(mod.name))

apply(ctx, { port: PORT, bindHost: '127.0.0.1' })
check('拿得到 service 实例', service !== null)
check('注册了 15 个工具', tools.length === 15, `实际 ${tools.length}(${tools.map((t) => t.name).join(',')})`)
check(
  '工具名都是 phone_*',
  tools.every((tool) => String(tool.name ?? tool.id ?? '').startsWith('phone_')),
  tools.map((tool) => tool.name ?? tool.id).join(','),
)

if (effects.length > 0) await Promise.all(effects)
await new Promise((resolve) => setTimeout(resolve, 300))

const status = service.status()
check('监听成功', status.listening === true)
check('端口就是传入的那个', status.port === PORT, String(status.port))
check('设置文件已落盘', fs.existsSync(status.settingsFile), status.settingsFile)
check('状态含 requestedPort / autoPort', 'requestedPort' in status && 'autoPort' in status)
check('token 文件路径在隔离 profile 里', status.tokenFile.startsWith(profileDir), status.tokenFile)
check('ttl 字段可用', typeof (await service.pairingCodeInfo?.()) !== 'undefined' || typeof status.pairingCode === 'string')

await service.dispose?.()
await new Promise((resolve) => setTimeout(resolve, 200))
check('dispose 后已停', service.status().listening === false)

console.log(`\n${fail === 0 ? 'OK' : 'FAILED'} — PASS ${pass} / FAIL ${fail}`)
process.exit(fail === 0 ? 0 : 1)
