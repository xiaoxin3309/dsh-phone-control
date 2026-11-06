// 把本地开发版的手机桥插件（包名取自插件目录的 package.json）固化进 DSH profile。
// 用途：pnpm install / 插件市场操作会清掉 node_modules 里的 junction，
// 本脚本恢复 junction 与 package.json 的 dsh.profile.bundles 条目（不改 dependencies）。
//
// 用法：
//   node m0/scripts/install-into-profile.mjs            # 安装/修复
//   node m0/scripts/install-into-profile.mjs --verify   # 只检查，不写
//   node m0/scripts/install-into-profile.mjs --remove   # 移除
import { existsSync, readFileSync, writeFileSync, copyFileSync, readlinkSync, mkdirSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import path from 'node:path'
import os from 'node:os'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const args = new Map()
for (const raw of process.argv.slice(2)) {
  if (!raw.startsWith('--')) continue
  const [k, v] = raw.slice(2).split('=')
  args.set(k, v ?? 'true')
}
const pluginDir = path.resolve(args.get('plugin') ?? path.join(here, '..', 'plugin'))
const profileDir = path.resolve(args.get('profile') ?? path.join(os.homedir(), '.dsh', 'profiles', 'desktop'))
const verify = args.has('verify')
const remove = args.has('remove')

// 包名永远以插件自己的 package.json 为准，避免改名后脚本还指向旧名字。
function readPackageName(dir) {
  const manifest = JSON.parse(readFileSync(path.join(dir, 'package.json'), 'utf8').replace(/^\uFEFF/u, ''))
  if (typeof manifest.name !== 'string' || manifest.name.length === 0) {
    console.error(`插件 package.json 缺少 name：${path.join(dir, 'package.json')}`)
    process.exit(2)
  }
  return manifest.name
}

const NAME = args.get('name') ?? readPackageName(pluginDir)

const link = path.join(profileDir, 'node_modules', NAME)
const manifest = path.join(profileDir, 'package.json')

if (!existsSync(path.join(pluginDir, 'index.js'))) {
  console.error(`插件目录不对，找不到 index.js：${pluginDir}`)
  process.exit(2)
}
if (!existsSync(manifest)) {
  console.error(`profile 不对，找不到 package.json：${manifest}`)
  process.exit(2)
}

const changes = []

function linkState() {
  if (!existsSync(link)) return { ok: false, reason: '缺失' }
  try {
    const target = path.resolve(path.dirname(link), readlinkSync(link))
    const same = target.toLowerCase() === pluginDir.toLowerCase()
    return { ok: same, reason: `指向 ${target}` }
  } catch {
    return { ok: false, reason: '不是链接（可能是实体目录）' }
  }
}

let state = linkState()
if (remove) {
  if (existsSync(link)) {
    if (verify) changes.push(`将删除 junction ${link}`)
    else {
      execFileSync('cmd', ['/c', 'rmdir', link])
      changes.push('已删除 junction')
    }
  }
} else if (!state.ok) {
  if (verify) changes.push(`需要重建 junction：${link} -> ${pluginDir}（当前：${state.reason}）`)
  else {
    if (existsSync(link)) execFileSync('cmd', ['/c', 'rmdir', link])
    writeFileSync(path.join(profileDir, 'node_modules', '.keep'), '')
    execFileSync('cmd', ['/c', 'mklink', '/J', link, pluginDir], { shell: false })
    changes.push(`已创建 junction ${link} -> ${pluginDir}`)
  }
}

const json = JSON.parse(readFileSync(manifest, 'utf8').replace(/^\uFEFF/u, ''))
json.dsh ??= {}
json.dsh.profile ??= {}
const bundles = Array.isArray(json.dsh.profile.bundles) ? json.dsh.profile.bundles : []
const hasBundle = bundles.includes(NAME)

if (remove) {
  if (hasBundle) {
    if (verify) changes.push(`将从 bundles 移除 ${NAME}`)
    else {
      json.dsh.profile.bundles = bundles.filter((b) => b !== NAME)
      changes.push(`已从 bundles 移除 ${NAME}`)
    }
  }
} else if (!hasBundle) {
  if (verify) changes.push(`需要把 ${NAME} 加进 dsh.profile.bundles`)
  else {
    json.dsh.profile.bundles = [...bundles, NAME]
    changes.push(`已把 ${NAME} 加进 bundles`)
  }
}

if (changes.length > 0 && !verify) {
  const stamp = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 17)
  copyFileSync(manifest, `${manifest}.bak-phonebridge-${stamp}`)
  writeFileSync(manifest, `${JSON.stringify(json, null, 2)}\n`, 'utf8')
  changes.push(`已写回 package.json（备份 package.json.bak-phonebridge-${stamp}）`)
}

state = linkState()
const finalBundles = JSON.parse(readFileSync(manifest, 'utf8').replace(/^\uFEFF/u, '')).dsh?.profile?.bundles ?? []
console.log(`profile : ${profileDir}`)
console.log(`plugin  : ${pluginDir}`)
console.log(`junction: ${existsSync(link) ? `存在（${state.reason}）` : '缺失'}`)
console.log(`bundles : ${finalBundles.includes(NAME) ? '已登记' : '未登记'}`)
if (changes.length === 0) {
  console.log('状态：已就绪，无需改动')
} else {
  console.log('状态：')
  for (const c of changes) console.log(`  - ${c}`)
}
if (!verify && !remove && changes.length > 0) {
  console.log('提示：在 DSH 里用 plugin_manager set_bundle（enabled=false → true）重载插件，或重启 DSH。')
}
