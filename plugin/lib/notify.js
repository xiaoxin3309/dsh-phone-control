/**
 * 会话通知：把 DSH 会话里「该看一眼手机」的两件事转成手机通知——
 * 一轮回答结束（turn/end 且 reason.kind === 'completed'）、
 * 智能体抛出一个需要人回答的问题（tool/call 且 name === 'ask_user_question'）。
 *
 * 这里只负责「发」：手机收不收（总开关、按类开关、系统通知权限）由 App 决定，
 * App 会在回包里给一个 reason（shown / permission / disabled / kind_off / no_service）。
 * 手机没连上、或用户在 App 里关掉了，都只是安静地不发，不打扰会话本身。
 */

export const KIND_COMPLETE = 'complete'
export const KIND_QUESTION = 'question'
export const KIND_MANUAL = 'manual'

const ASK_TOOL = 'ask_user_question'
const MAX_TITLE = 60
const MAX_BODY = 240
const MIN_GAP_MS = 4000
const MAX_TRACKED = 200

function collapse(text, max) {
  const flat = String(text ?? '').replace(/\s+/gu, ' ').trim()
  if (flat.length <= max) return flat
  return `${flat.slice(0, Math.max(0, max - 1))}…`
}

/** assistant/message 的正文在 content 分块里，只取 text 块。 */
function textOfMessage(message) {
  const parts = message?.content
  if (!Array.isArray(parts)) return ''
  const out = []
  for (const part of parts) {
    if (part?.type === 'text' && typeof part.text === 'string' && part.text.trim().length > 0) {
      out.push(part.text)
    }
  }
  return out.join('\n').trim()
}

function questionText(args) {
  const questions = args?.questions
  if (!Array.isArray(questions) || questions.length === 0) return ''
  const first = questions[0]
  const body = typeof first?.question === 'string' ? first.question : ''
  const header = typeof first?.header === 'string' ? first.header.trim() : ''
  const extra = questions.length > 1 ? `（共 ${questions.length} 个问题）` : ''
  if (body.length === 0) return header.length === 0 ? '' : `${header}${extra}`
  return header.length === 0 ? `${body}${extra}` : `${header}：${body}${extra}`
}

export function createNotifier({ service, logger }) {
  const titles = new Map()
  const lastText = new Map()
  const recent = new Map()

  function trim() {
    for (const map of [titles, lastText, recent]) {
      while (map.size > MAX_TRACKED) map.delete(map.keys().next().value)
    }
  }

  function label(sessionId, session) {
    const remembered = titles.get(sessionId)
    if (typeof remembered === 'string' && remembered.length > 0) return remembered
    const fromHeader = session?.header?.title
    if (typeof fromHeader === 'string' && fromHeader.trim().length > 0) return fromHeader.trim()
    return `会话 ${sessionId.slice(0, 8)}`
  }

  async function push(kind, title, body, sessionKey) {
    const now = Date.now()
    const key = `${sessionKey}:${kind}`
    const previous = recent.get(key) ?? 0
    if (now - previous < MIN_GAP_MS) return { sent: false, reason: 'throttled' }
    recent.set(key, now)
    trim()
    try {
      const result = await service.request(
        'notify',
        {
          kind,
          title: collapse(title, MAX_TITLE),
          body: collapse(body, MAX_BODY),
          session: sessionKey,
        },
        6000,
      )
      const reason = typeof result?.reason === 'string' ? result.reason : 'unknown'
      return { sent: Boolean(result?.shown), reason }
    } catch (error) {
      const message = String(error?.message ?? '')
      const reason = message.startsWith('NO_DEVICE') ? 'no_device' : message.startsWith('TIMEOUT') ? 'timeout' : 'error'
      return { sent: false, reason }
    }
  }

  function observe(session, event) {
    try {
      const id = typeof session?.id === 'string' ? session.id : ''
      if (id.length === 0 || event === undefined || event === null) return
      const data = event.data ?? {}
      switch (event.type) {
        case 'session/title': {
          const title = data.title
          if (typeof title === 'string' && title.trim().length > 0) {
            titles.set(id, title.trim())
            trim()
          }
          return
        }
        case 'turn/start': {
          lastText.delete(id)
          return
        }
        case 'assistant/message': {
          const text = textOfMessage(data.message)
          if (text.length > 0) lastText.set(id, { turn: data.turn, text })
          return
        }
        case 'tool/call': {
          if (data.name !== ASK_TOOL) return
          const name = label(id, session)
          void push(KIND_QUESTION, `「${name}」在等你回答`, questionText(data.arguments) || '智能体提出了一个问题，等你回复。', id)
          return
        }
        case 'turn/end': {
          if (data.reason?.kind !== 'completed') return
          const name = label(id, session)
          const answer = lastText.get(id)
          const body = answer !== undefined && answer.turn === data.turn ? collapse(answer.text, MAX_BODY) : ''
          void push(KIND_COMPLETE, `「${name}」已回答`, body || '本轮回答结束。', id)
          return
        }
        default:
          return
      }
    } catch (error) {
      logger?.warn?.(`[phone-control] notify hook failed: ${error?.message ?? String(error)}`)
    }
  }

  async function manual({ title, body, session }) {
    const sessionKey = typeof session === 'string' && session.trim().length > 0 ? session.trim() : 'dsh-manual'
    return push(KIND_MANUAL, title ?? '来自 DSH 的提醒', body ?? '', sessionKey)
  }

  async function state() {
    try {
      return await service.request('notify_state', {}, 6000)
    } catch (error) {
      const message = String(error?.message ?? '')
      throw new Error(message.startsWith('NO_DEVICE') ? message : `NOTIFY_STATE_FAILED: ${message}`)
    }
  }

  return { observe, manual, state }
}
