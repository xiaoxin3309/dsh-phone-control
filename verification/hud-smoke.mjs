/**
 * 悬浮 HUD（插件侧）回归：只测 lib/hud.js 的排队、节流、去噪与静音，
 * 不碰真机、不碰 WebSocket —— service 换成记录调用的桩。
 *
 *   node verification\hud-smoke.mjs
 */

import { createHud } from '../plugin/lib/hud.js'

let pass = 0
let fail = 0

function check(name, ok, detail) {
  if (ok) {
    pass += 1
    console.log(`PASS ${name}`)
  } else {
    fail += 1
    console.log(`FAIL ${name}${detail === undefined ? '' : ` — ${detail}`}`)
  }
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

function fakeService({ reason = 'shown' } = {}) {
  const calls = []
  return {
    calls,
    async request(method, payload) {
      calls.push({ method, payload })
      if (method === 'hud_push') return { ok: true, shown: reason === 'shown', reason, line: payload?.lines?.length ?? 0 }
      if (method === 'hud_clear') return { ok: true, cleared: true }
      if (method === 'hud_state') return { ok: true, enabled: true, permission: true, visible: false, lines: [] }
      return { ok: true }
    },
  }
}

const session = { id: 'sess-hud-0001', header: { title: '悬浮 HUD 测试' } }
function ev(type, data) {
  return { type, data }
}

// 1) 点按文字 → 一条 act 行，带「点击文字」和参数
{
  const service = fakeService()
  const hud = createHud({ service })
  hud.observe(session, ev('tool/call', { name: 'phone_tap_text', arguments: { text: '微信' } }))
  await sleep(380)
  const pushes = service.calls.filter((c) => c.method === 'hud_push')
  check('tap_text 推一条 hud_push', pushes.length === 1, `pushes=${pushes.length}`)
  const line = pushes[0]?.payload?.lines?.[0]
  check('行内含「点击文字」与参数', line?.text?.includes('点击文字') && line?.text?.includes('微信'), JSON.stringify(line))
  check('行 kind=act', line?.kind === 'act', JSON.stringify(line))
  hud.dispose()
}

// 2) 300ms 内多次调用合并成一次 push
{
  const service = fakeService()
  const hud = createHud({ service })
  hud.observe(session, ev('tool/call', { name: 'phone_tap', arguments: { x: 10, y: 20 } }))
  hud.observe(session, ev('tool/call', { name: 'phone_key', arguments: { key: 'back' } }))
  hud.observe(session, ev('tool/call', { name: 'phone_open_app', arguments: { package: 'com.tencent.mm' } }))
  await sleep(380)
  const pushes = service.calls.filter((c) => c.method === 'hud_push')
  check('节流：三次调用只发一次', pushes.length === 1, `pushes=${pushes.length}`)
  check('节流：一次带三行', pushes[0]?.payload?.lines?.length === 3, JSON.stringify(pushes[0]))
  hud.dispose()
}

// 3) 非手机工具 / 推通知 / 查状态都不上 HUD
{
  const service = fakeService()
  const hud = createHud({ service })
  hud.observe(session, ev('tool/call', { name: 'read', arguments: { file_path: 'a.txt' } }))
  hud.observe(session, ev('tool/call', { name: 'phone_notify', arguments: { title: 'x' } }))
  hud.observe(session, ev('tool/call', { name: 'phone_status', arguments: {} }))
  hud.observe(session, ev('tool/call', { name: 'phone_disconnect', arguments: {} }))
  await sleep(380)
  check('无 phone_* 动作时不发 push', service.calls.every((c) => c.method !== 'hud_push'), JSON.stringify(service.calls))
  hud.dispose()
}

// 4) 没控制过手机的会话，turn/start 不打扰
{
  const service = fakeService()
  const hud = createHud({ service })
  hud.observe(session, ev('turn/start', { turn: 1 }))
  await sleep(380)
  check('陌生会话 turn/start 不推', service.calls.every((c) => c.method !== 'hud_push'), JSON.stringify(service.calls))
  hud.dispose()
}

// 5) 控制过手机之后，turn/start 与 turn/end 各一条状态行
{
  const service = fakeService()
  const hud = createHud({ service })
  hud.observe(session, ev('tool/call', { name: 'phone_ui_dump', arguments: {} }))
  await sleep(340)
  hud.observe(session, ev('turn/end', { turn: 1, reason: { kind: 'completed' } }))
  await sleep(340)
  hud.observe(session, ev('turn/start', { turn: 2 }))
  await sleep(340)
  const texts = service.calls
    .filter((c) => c.method === 'hud_push')
    .flatMap((c) => c.payload.lines.map((l) => `${l.kind}:${l.text}`))
  check('turn/end 推 done', texts.some((t) => t.startsWith('done:')), JSON.stringify(texts))
  check('turn/start 推 think', texts.some((t) => t.startsWith('think:')), JSON.stringify(texts))
  hud.dispose()
}

// 6) 手机端关了 HUD（reason=disabled）→ 静默，不再刷请求
{
  const service = fakeService({ reason: 'disabled' })
  const hud = createHud({ service })
  hud.observe(session, ev('tool/call', { name: 'phone_tap', arguments: { x: 1, y: 2 } }))
  await sleep(340)
  const before = service.calls.filter((c) => c.method === 'hud_push').length
  hud.observe(session, ev('tool/call', { name: 'phone_tap', arguments: { x: 3, y: 4 } }))
  await sleep(340)
  const after = service.calls.filter((c) => c.method === 'hud_push').length
  check('disabled 之后静音', before === 1 && after === 1, `before=${before} after=${after}`)
  hud.dispose()
}

// 7) clear 发 hud_clear
{
  const service = fakeService()
  const hud = createHud({ service })
  hud.observe(session, ev('tool/call', { name: 'phone_tap', arguments: { x: 1, y: 2 } }))
  await hud.clear()
  check('clear 发 hud_clear', service.calls.some((c) => c.method === 'hud_clear'), JSON.stringify(service.calls))
  hud.dispose()
  await sleep(340)
  check('dispose 后队列不再发', service.calls.filter((c) => c.method === 'hud_push').length === 0, JSON.stringify(service.calls))
}

// 8) 行文本有上限（长标题不撑破一行）
{
  const service = fakeService()
  const hud = createHud({ service })
  hud.observe(session, ev('tool/call', { name: 'phone_set_text', arguments: { text: '很'.repeat(120) } }))
  await sleep(340)
  const line = service.calls.find((c) => c.method === 'hud_push')?.payload?.lines?.[0]
  check('超长参数被截断', typeof line?.text === 'string' && line.text.length <= 40, `${line?.text?.length}`)
  hud.dispose()
}

// 9) 整轮不清空：中途 ttlMs=0 挂住，turn/end 才给 7 秒
{
  const service = fakeService()
  const hud = createHud({ service })
  hud.observe(session, ev('tool/call', { name: 'phone_tap', arguments: { x: 1, y: 2 } }))
  await sleep(340)
  hud.observe(session, ev('turn/end', { turn: 1, reason: { kind: 'completed' } }))
  await sleep(340)
  const pushes = service.calls.filter((c) => c.method === 'hud_push')
  check('动作行的 ttlMs=0（不自动淡出）', pushes[0]?.payload?.ttlMs === 0, JSON.stringify(pushes[0]?.payload))
  check('turn/end 的 ttlMs=7000', pushes[1]?.payload?.ttlMs === 7000, JSON.stringify(pushes[1]?.payload))
  hud.dispose()
}

// 10) 思考心跳：assistant/message 推一行，2.5 秒内不重复
{
  const service = fakeService()
  const hud = createHud({ service })
  hud.observe(session, ev('tool/call', { name: 'phone_ui_dump', arguments: {} }))
  await sleep(340)
  hud.observe(session, ev('assistant/message', { content: [{ type: 'text', text: '想一下' }] }))
  await sleep(340)
  hud.observe(session, ev('assistant/message', { content: [{ type: 'text', text: '再想一下' }] }))
  await sleep(340)
  const thinks = service.calls
    .filter((c) => c.method === 'hud_push')
    .flatMap((c) => c.payload.lines)
    .filter((l) => l.kind === 'think')
  check('assistant/message 推 think', thinks.length === 1, JSON.stringify(thinks))
  check('思考行不含正文', thinks[0]?.text === '思考中', JSON.stringify(thinks[0]))
  hud.dispose()
}

// 11) 流式思考：reasoning-delta 推一行，之后原地替换（不刷屏）
{
  const service = fakeService()
  const hud = createHud({ service })
  const agent = { session }
  hud.observe(session, ev('tool/call', { name: 'phone_ui_dump', arguments: {} }))
  await sleep(340)
  hud.stream(agent, { type: 'start', attemptId: 'a1' })
  hud.stream(agent, { type: 'chunk', chunk: { type: 'reasoning-delta', text: '先看通知栏' } })
  await sleep(420)
  hud.stream(agent, { type: 'chunk', chunk: { type: 'reasoning-delta', text: '，再点齿轮' } })
  await sleep(420)
  const thinkPushes = service.calls
    .filter((c) => c.method === 'hud_push' && c.payload.lines.some((l) => l.kind === 'think'))
  check('reasoning-delta 推 think 行', thinkPushes.length >= 2, `pushes=${thinkPushes.length}`)
  check('第一条思考行是追加', thinkPushes[0]?.payload?.replaceLast === false, JSON.stringify(thinkPushes[0]?.payload))
  check('后续思考行原地替换', thinkPushes[1]?.payload?.replaceLast === true, JSON.stringify(thinkPushes[1]?.payload))
  const last = thinkPushes.at(-1)?.payload?.lines?.[0]?.text ?? ''
  check('思考行是尾巴（含最新增量）', last.includes('再点齿轮'), JSON.stringify(last))
  hud.dispose()
}

// 12) 没控制过手机的会话，流式思考不推
{
  const service = fakeService()
  const hud = createHud({ service })
  hud.stream({ session: { id: 'sess-other' } }, { type: 'chunk', chunk: { type: 'reasoning-delta', text: '无关会话' } })
  await sleep(420)
  check('陌生会话的思考不推', service.calls.every((c) => c.method !== 'hud_push'), JSON.stringify(service.calls))
  hud.dispose()
}

// 13) 有流式思考时不再补「思考中」心跳
{
  const service = fakeService()
  const hud = createHud({ service })
  const agent = { session }
  hud.observe(session, ev('tool/call', { name: 'phone_ui_dump', arguments: {} }))
  await sleep(340)
  hud.stream(agent, { type: 'chunk', chunk: { type: 'reasoning-delta', text: '正在看屏幕' } })
  await sleep(420)
  hud.observe(session, ev('assistant/message', { content: [{ type: 'text', text: '想一下' }] }))
  await sleep(420)
  const hearts = service.calls
    .filter((c) => c.method === 'hud_push')
    .flatMap((c) => c.payload.lines)
    .filter((l) => l.kind === 'think' && l.text === '思考中')
  check('流式思考期间不补心跳', hearts.length === 0, JSON.stringify(hearts))
  hud.dispose()
}

// 14) 调完手机之后过了十分钟（只是思考、没再调手机）：新一轮不推 → 浮层卡住不滚也不消失的回归
{
  const service = fakeService()
  const hud = createHud({ service })
  hud.observe(session, ev('tool/call', { name: 'phone_ui_dump', arguments: {} }))
  await sleep(340)
  const realNow = Date.now
  Date.now = () => realNow() + 10 * 60_000
  try {
    hud.observe(session, ev('turn/start', { turn: 2 }))
    await sleep(340)
    hud.observe(session, ev('turn/end', { turn: 2, reason: { kind: 'completed' } }))
    await sleep(340)
  } finally {
    Date.now = realNow
  }
  const pushes = service.calls.filter((c) => c.method === 'hud_push')
  check('十分钟后新的一轮仍推思考行', pushes.some((c) => c.payload.lines.some((l) => l.text === '正在思考下一步')), JSON.stringify(pushes.map((c) => c.payload)))
  check('十分钟后 turn/end 仍给 7 秒（能淡出）', pushes.some((c) => c.payload.ttlMs === 7000), JSON.stringify(pushes.map((c) => c.payload)))
  hud.dispose()
}

console.log(`\n${fail === 0 ? 'PASS' : 'FAIL'} ${pass} / ${fail}`)
process.exitCode = fail === 0 ? 0 : 1
