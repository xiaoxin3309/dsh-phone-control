/**
 * 手机端悬浮 HUD：把「智能体正在对手机做什么」推到手机屏幕上。
 *
 * 只推动作，不推回答正文——HUD 是给旁边的人看的，不是把模型输出搬到手机上。
 * 只推「最近真的调用过 phone_* 的会话」，普通会话不动手机就不会突然冒出一条浮层。
 *
 * 传输层：service.request('hud_push', { lines: [{ kind, text }] })，手机上按 5 行、
 * 静置 5 秒淡出。手机上关掉开关或没授权时服务端会回 reason=disabled/permission，
 * 这里就静默一段时间，不重复打扰。
 */

const THROTTLE_MS = 300
const MUTE_MS = 60_000
const ACTIVE_MS = 120_000
const MAX_LINE = 40
const MAX_QUEUE = 20
const MAX_TRACKED = 100

export const KIND_ACT = 'act'
export const KIND_THINK = 'think'
export const KIND_DONE = 'done'

const LABELS = {
  phone_tap_text: '点击文字',
  phone_set_text: '输入文字',
  phone_input: '输入',
  phone_tap: '点击',
  phone_long_press: '长按',
  phone_swipe: '滑动',
  phone_key: '按键',
  phone_open_app: '打开 App',
  phone_current_app: '查看前台 App',
  phone_list_apps: '列应用',
  phone_ui_dump: '读控件树',
  phone_screenshot: '截图',
  phone_fs_list: '列目录',
  phone_fs_read: '读文件',
  phone_fs_write: '写文件',
  phone_fs_mkdir: '建目录',
  phone_fs_rm: '删文件',
  phone_fs_stat: '查看文件',
}

/** phone_* 里不算「屏幕上做了什么」的几个：推通知、查状态、断开连接 */
const SKIP = new Set(['phone_notify', 'phone_status', 'phone_pair_code', 'phone_disconnect'])

function collapse(text, max) {
  const flat = String(text ?? '').replace(/\s+/gu, ' ').trim()
  if (flat.length <= max) return flat
  return `${flat.slice(0, Math.max(0, max - 1))}…`
}

function pack(value) {
  const text = typeof value === 'string' ? value.trim() : ''
  if (text.length === 0) return ''
  return `「${collapse(text, MAX_LINE)}」`
}

function point(args) {
  const x = Number(args?.x)
  const y = Number(args?.y)
  return Number.isFinite(x) && Number.isFinite(y) ? `(${Math.round(x)}, ${Math.round(y)})` : ''
}

/** 每个工具挑一个「人一眼能懂」的参数，取不到就不带后缀 */
function detail(name, args) {
  const a = args ?? {}
  switch (name) {
    case 'phone_tap_text':
      return pack(a.text)
    case 'phone_set_text':
    case 'phone_input':
      return pack(a.text)
    case 'phone_open_app':
      return pack(a.package)
    case 'phone_key':
      return pack(a.key)
    case 'phone_tap':
    case 'phone_long_press':
      return point(a)
    case 'phone_swipe': {
      const from = point({ x: a.x1, y: a.y1 })
      const to = point({ x: a.x2, y: a.y2 })
      return from.length > 0 && to.length > 0 ? `${from} → ${to}` : ''
    }
    case 'phone_fs_list':
    case 'phone_fs_read':
    case 'phone_fs_write':
    case 'phone_fs_mkdir':
    case 'phone_fs_rm':
    case 'phone_fs_stat':
      return pack(a.path)
    default:
      return ''
  }
}

function describe(name, args) {
  const label = LABELS[name] ?? name.replace(/^phone_/u, '')
  const extra = detail(name, args)
  const text = extra.length > 0 ? `${label} ${extra}` : label
  return collapse(text, MAX_LINE)
}

export function createHud({ service, logger }) {
  const active = new Map()
  const queue = []
  let timer = null
  let lastSent = 0
  let mutedUntil = 0
  let disposed = false

  function trim() {
    while (active.size > MAX_TRACKED) active.delete(active.keys().next().value)
  }

  function muted() {
    return Date.now() < mutedUntil
  }

  function newer(id) {
    const at = active.get(id)
    return at === undefined ? 0 : at
  }

  function enqueue(kind, text) {
    if (disposed || typeof text !== 'string' || text.length === 0) return
    if (queue.length >= MAX_QUEUE) queue.shift()
    queue.push({ kind, text })
    schedule()
  }

  function schedule() {
    if (timer !== null || queue.length === 0) return
    const wait = Math.max(0, THROTTLE_MS - (Date.now() - lastSent))
    timer = setTimeout(flush, wait)
    if (typeof timer?.unref === 'function') timer.unref()
  }

  function flush() {
    timer = null
    if (disposed || queue.length === 0) return
    const lines = queue.splice(0, MAX_QUEUE)
    if (muted()) return
    lastSent = Date.now()
    void service
      .request('hud_push', { lines }, 4000)
      .then((result) => {
        const reason = typeof result?.reason === 'string' ? result.reason : ''
        if (reason === 'disabled' || reason === 'permission') mutedUntil = Date.now() + MUTE_MS
      })
      .catch((error) => {
        const message = String(error?.message ?? '')
        if (message.startsWith('NO_DEVICE') || message.startsWith('TIMEOUT')) {
          // 手机没连上：丢掉这批，不要让队列在重连后突然刷一屏旧动作
          return
        }
        logger?.debug?.(`[phone-control] hud push failed: ${message}`)
      })
  }

  function observe(session, event) {
    try {
      const id = typeof session?.id === 'string' ? session.id : ''
      if (id.length === 0 || event === undefined || event === null) return
      const data = event.data ?? {}
      switch (event.type) {
        case 'tool/call': {
          const name = typeof data.name === 'string' ? data.name : ''
          if (!name.startsWith('phone_') || SKIP.has(name)) return
          active.set(id, Date.now())
          trim()
          enqueue(KIND_ACT, describe(name, data.arguments))
          return
        }
        case 'turn/start': {
          if (Date.now() - newer(id) > ACTIVE_MS) return
          enqueue(KIND_THINK, '正在思考下一步')
          return
        }
        case 'turn/end': {
          if (Date.now() - newer(id) > ACTIVE_MS) return
          if (data.reason?.kind === 'completed') enqueue(KIND_DONE, '本轮结束')
          return
        }
        default:
          return
      }
    } catch (error) {
      logger?.warn?.(`[phone-control] hud hook failed: ${error?.message ?? String(error)}`)
    }
  }

  async function clear() {
    queue.length = 0
    try {
      await service.request('hud_clear', {}, 4000)
    } catch {
      // 手机没连上就没什么可清的
    }
  }

  async function state() {
    try {
      return await service.request('hud_state', {}, 4000)
    } catch (error) {
      const message = String(error?.message ?? '')
      throw new Error(message.startsWith('NO_DEVICE') ? message : `HUD_STATE_FAILED: ${message}`)
    }
  }

  function dispose() {
    disposed = true
    queue.length = 0
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
  }

  return { observe, clear, state, dispose }
}
