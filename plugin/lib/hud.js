/**
 * 手机端悬浮 HUD：把「智能体正在对手机做什么」推到手机屏幕上。
 *
 * 只推动作，不推回答正文——HUD 是给旁边的人看的，不是把模型输出搬到手机上。
 * 只推「最近真的调用过 phone_* 的会话」，普通会话不动手机就不会突然冒出一条浮层。
 *
 * 一整轮里动作行与「还在想」心跳累积不清空（ttlMs=0），turn/end 才给 7 秒的停留时间再淡出。
 *
 * 传输层：service.request('hud_push', { lines: [{ kind, text }], ttlMs })，手机上按 5 行、
 * 超过就丢最旧的；手机桥自己的界面在前台时手机端会整块收起（不会盖住 App 自己）。
 * 手机上关掉开关或没授权时服务端会回 reason=disabled/permission，这里就静默一段时间，
 * 不重复打扰。
 */

const THROTTLE_MS = 300
const MUTE_MS = 60_000
const ACTIVE_MS = 120_000
/**
 * 「这个会话还算在操控手机」的宽限：从最近一次 `phone_*` 调用算起。
 * 一轮里调完一次手机后会连续思考几分钟，这期间不能再要求「120 秒内调过手机」，
 * 否则思考行就停在那儿不动了。
 */
const ENGAGED_MS = 20 * 60_000
const MAX_LINE = 40
const MAX_QUEUE = 20
const MAX_TRACKED = 100

/** 一轮进行中不清空：ttl=0 交给手机端「挂住」，等 turn/end 再给停留时间。 */
const HOLD_TTL_MS = 0
/** 一轮结束后还留多久，跟手机端 IDLE_MS 对齐。 */
const DONE_TTL_MS = 7_000
/** 思考心跳的最小间隔：模型连续出几条 assistant 消息也只推一行。 */
const THINK_GAP_MS = 2_500
/** 流式思考的推送节奏：比动作慢一点，免得手机端一直在重排。 */
const STREAM_GAP_MS = 400
/** 有流式思考进来之后的这么久里，不再补「还在想」心跳。 */
const STREAM_HOLD_MS = 5_000
/** 只留思考的尾巴：浮层一行放不下，看的是「他现在在想哪一步」。 */
const MAX_THINK = 80

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

/** 思考要看的是「尾巴」：最后一句说到哪儿了，而不是从头念。 */
function tailOf(text, max) {
  const flat = String(text ?? '').replace(/\s+/gu, ' ').trim()
  if (flat.length <= max) return flat
  return `…${flat.slice(-(max - 1))}`
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
  const thinkAt = new Map()
  /** 会话 → 本轮流式思考累积的尾巴（只留最后 MAX_THINK 字）。 */
  const streamText = new Map()
  /** 会话 → 最近一次收到流式思考的时间，用来抑制「还在想」心跳。 */
  const streamAt = new Map()
  /** 会话 → 最近一次真的把思考行推上去的时间（节流用）。 */
  const streamPushAt = new Map()
  const queue = []
  let timer = null
  let lastSent = 0
  let mutedUntil = 0
  let disposed = false
  /** 上一条真正推上去的行的类型：思考行连着来时用 replaceLast 原地更新，不刷屏。 */
  let lastKind = ''

  function trim() {
    while (active.size > MAX_TRACKED) {
      const oldest = active.keys().next().value
      active.delete(oldest)
      thinkAt.delete(oldest)
      streamText.delete(oldest)
      streamAt.delete(oldest)
      streamPushAt.delete(oldest)
    }
  }

  function muted() {
    return Date.now() < mutedUntil
  }

  function newer(id) {
    const at = active.get(id)
    return at === undefined ? 0 : at
  }

  /** 记一次「这个会话还在推进」：turn 之内每个事件都刷新，避免宽限被思考时间吃掉。 */
  function touch(id) {
    active.set(id, Date.now())
    if (active.size > MAX_TRACKED) trim()
  }

  function enqueue(kind, text, replaceLast = false) {
    if (disposed || typeof text !== 'string' || text.length === 0) return
    if (queue.length >= MAX_QUEUE) queue.shift()
    queue.push({ kind, text, replaceLast })
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
    // 中途的行挂住不淡出；只有「本轮结束」那一批带停留时间。
    const ttlMs = lines.some((line) => line.kind === KIND_DONE) ? DONE_TTL_MS : HOLD_TTL_MS
    // 单独一条思考行、且屏幕上最后一条也是思考行：让手机端原地替换，而不是又堆一行。
    const replaceLast = lines.length === 1 && lines[0].replaceLast === true
    lastKind = lines[lines.length - 1].kind
    void service
      .request('hud_push', { lines: lines.map(({ kind, text }) => ({ kind, text })), ttlMs, replaceLast }, 4000)
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
          touch(id)
          enqueue(KIND_ACT, describe(name, data.arguments))
          return
        }
        case 'turn/start': {
          if (Date.now() - newer(id) > ENGAGED_MS) return
          touch(id)
          thinkAt.delete(id)
          enqueue(KIND_THINK, '正在思考下一步')
          return
        }
        case 'assistant/message': {
          // 思考心跳：不读正文，只表示「模型还在推进」，避免一整轮只有动作行。
          // 有流式思考正在来时不必再补一句，否则两行说的是同一件事。
          if (Date.now() - newer(id) > ENGAGED_MS) return
          touch(id)
          if (Date.now() - (streamAt.get(id) ?? 0) < STREAM_HOLD_MS) return
          const now = Date.now()
          if (now - (thinkAt.get(id) ?? 0) < THINK_GAP_MS) return
          thinkAt.set(id, now)
          enqueue(KIND_THINK, '还在想')
          return
        }
        case 'turn/end': {
          if (Date.now() - newer(id) > ENGAGED_MS) return
          touch(id)
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

  /**
   * `agent/assistant-stream`：模型这一轮的**实时** token。
   *
   * 只认 `chunk.type === 'reasoning-delta'`（思考增量），正文一律不看。浮层只有 5 行，
   * 所以思考行是「原地更新」的：第一条追加，之后同一个会话再来就用 replaceLast 覆写它。
   */
  function stream(agent, frame) {
    try {
      if (disposed || frame === null || frame === undefined) return
      const id = typeof agent?.session?.id === 'string' ? agent.session.id : ''
      if (id.length === 0) return
      if (frame.type === 'start') {
        streamText.set(id, '')
        return
      }
      if (frame.type === 'end') {
        streamText.delete(id)
        return
      }
      if (frame.type !== 'chunk') return
      const chunk = frame.chunk
      if (chunk?.type !== 'reasoning-delta' || typeof chunk.text !== 'string') return
      // 没在操控手机的会话不往手机上推
      const now = Date.now()
      if (now - newer(id) > ENGAGED_MS) return
      touch(id)
      if (muted()) return
      const text = `${streamText.get(id) ?? ''}${chunk.text}`.slice(-MAX_THINK)
      streamText.set(id, text)
      streamAt.set(id, now)
      // 节流按「上一次真的推进去的时间」算，而不是 flush 时间：否则一个 300ms 的
      // 排队窗口会把紧接着的几个增量全吞掉。
      if (timer !== null || now - (streamPushAt.get(id) ?? 0) < STREAM_GAP_MS) return
      streamPushAt.set(id, now)
      const tail = tailOf(text, MAX_LINE)
      if (tail.length === 0) return
      enqueue(KIND_THINK, tail, lastKind === KIND_THINK)
    } catch (error) {
      logger?.warn?.(`[phone-control] hud stream failed: ${error?.message ?? String(error)}`)
    }
  }

  async function clear() {
    queue.length = 0
    streamText.clear()
    streamAt.clear()
    lastKind = ''
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
    streamText.clear()
    streamAt.clear()
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
  }

  return { observe, stream, clear, state, dispose }
}
