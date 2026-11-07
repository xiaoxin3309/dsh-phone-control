import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { SpikeService } from './lib/service.js'
import { BASE_PATH, createHandler } from './lib/routes.js'

export const name = 'dsh-phone-control'
export const inject = ['tools', 'skills']

const OUTPUT = {
  schema: { type: 'object', additionalProperties: true },
  render: (_args, value) => {
    const blocks = [{ type: 'text', text: JSON.stringify(value, null, 2) }]
    const image = value?.image
    if (image !== undefined && image !== null && typeof image.attachmentId === 'string') {
      blocks.push({ type: 'image', attachment: image })
    }
    return blocks
  },
}

const SKILL_DIR = new URL('./skills/phone-control/', import.meta.url)
const SKILL_PATH = new URL('./skills/phone-control/SKILL.md', import.meta.url)

const CANDIDATE = {
  name: 'phone-control',
  description: '通过 DSH 手机桥操控已配对的 Android 手机：截图、坐标点击、长按、滑动、输入文本、返回/桌面/最近任务、打开应用。',
  invocation: {
    modelInvocable: true,
    userInvocable: true,
  },
  provider: 'dsh-phone-control',
  source: 'bundled',
  resourceBase: { kind: 'directory', path: fileURLToPath(SKILL_DIR) },
  rank: 50,
  locator: fileURLToPath(SKILL_PATH),
}

const provider = {
  name: 'dsh-phone-control',
  async list() {
    return [CANDIDATE]
  },
  async get(candidate) {
    if (candidate?.name !== 'phone-control') return undefined
    const { rank: _rank, locator: entryPath, ...summary } = candidate
    const raw = await readFile(entryPath, 'utf8')
    const body = raw.replace(/^---\r?\n[\s\S]*?\r?\n---(?:\r?\n|$)/u, '').trim()
    return {
      ...summary,
      content: body,
    }
  },
}

const NUMBER = { type: 'number' }

function makeTool({ name, description, properties = {}, required = [], timeoutMs = 20000, present, run }) {
  return {
    name,
    description,
    parameters: {
      type: 'object',
      properties,
      required,
      additionalProperties: false,
    },
    output: OUTPUT,
    isConcurrencySafe: () => false,
    timeoutMs,
    async execute(args) {
      return run(args ?? {})
    },
    presentCall() {
      return present
    },
  }
}

async function imageResult(ctx, raw) {
  const encoded = String(raw?.imageB64 ?? '')
  if (encoded.length === 0) throw new Error('PHONE_IMAGE: 手机没有返回截图数据')
  const data = Buffer.from(encoded, 'base64')
  if (data.length === 0) throw new Error('PHONE_IMAGE: 截图数据为空')
  let attachments
  try {
    attachments = ctx.get('attachments')
  } catch {
    attachments = undefined
  }
  if (attachments === undefined || typeof attachments.saveImage !== 'function') {
    throw new Error('ATTACHMENTS_UNAVAILABLE: DSH 附件服务不可用')
  }
  const ref = await attachments.saveImage({
    data,
    mediaType: 'image/png',
    name: `phone-${Date.now()}.png`,
  })
  return {
    ok: true,
    width: raw.width ?? ref.width,
    height: raw.height ?? ref.height,
    bytes: data.length,
    capturedAt: raw.capturedAt ?? Date.now(),
    image: {
      attachmentId: ref.attachmentId,
      mediaType: ref.mediaType,
      bytes: ref.bytes,
      width: raw.width ?? ref.width,
      height: raw.height ?? ref.height,
      ...(ref.name === undefined ? {} : { name: ref.name }),
    },
  }
}

export function apply(ctx, config) {
  const service = new SpikeService({ ctx, config })
  ctx.provide('phoneBridgeSpike', service)

  // ---- settings page transport -------------------------------------------
  // Acquired through ctx.inject rather than a module-level `inject` entry: a
  // profile without a web server (a TUI profile) must still load this plugin,
  // and ctx.inject parks only this child fiber instead of the whole plugin.
  try {
    ctx.inject(['webServer'], (webCtx) => {
      webCtx.effect(
        () => webCtx.webServer.register({
          kind: 'prefix',
          path: BASE_PATH,
          handler: createHandler(service, { logger: ctx.logger }),
        }),
        'dsh-phone-control: settings API',
      )
    })
  } catch (error) {
    ctx.logger?.warn?.(
      `[phone-control] 没有可用的 web 服务，设置页面已禁用: ${error?.message ?? String(error)}`,
    )
  }

  const definitions = [
    makeTool({
      name: 'phone_status',
      description: '查看手机桥状态：是否监听、手机是否已连接、配对码、端口和待处理请求数。',
      timeoutMs: 10000,
      present: { card: 'generic', title: '手机桥状态', kind: 'read' },
      async run() {
        return service.status()
      },
    }),
    makeTool({
      name: 'phone_pair_code',
      description: '查看当前 8 位配对码、监听端口和绑定地址；在手机 App 的“配对码”中输入这 8 位数字。传 refresh=true 可强制换一个新码（并解除错误次数锁定）。',
      properties: {
        refresh: { type: 'boolean', description: '强制生成新的 8 位配对码并解除锁定，默认 false' },
      },
      timeoutMs: 10000,
      present: { card: 'generic', title: '配对码', kind: 'read' },
      async run(args) {
        if (args?.refresh === true) service.refreshCode(true)
        let s = service.status()
        if (args?.refresh !== true && Date.now() > s.codeExpiresAt && s.lockedUntil === null) {
          service.refreshCode()
          s = service.status()
        }
        return {
          code: s.pairingCode,
          port: s.port,
          bindHost: s.bindHost,
          paired: s.paired,
          connected: s.connected,
          expiresAt: s.codeExpiresAt,
          ttlSec: Math.max(0, Math.round((s.codeExpiresAt - Date.now()) / 1000)),
          failedAttempts: s.failedAttempts,
          lockedUntil: s.lockedUntil,
          lockedRemainSec: s.lockedUntil === null ? 0 : Math.max(0, Math.round((s.lockedUntil - Date.now()) / 1000)),
        }
      },
    }),
    makeTool({
      name: 'phone_screenshot',
      description: '截取手机当前屏幕并返回 PNG 图片。坐标类工具应使用这张图片的像素坐标。',
      timeoutMs: 30000,
      present: { card: 'generic', title: '手机截图', kind: 'read' },
      async run() {
        const raw = await service.request('screenshot', {}, 25000)
        return imageResult(ctx, raw)
      },
    }),
    makeTool({
      name: 'phone_ui_dump',
      description:
        '读取手机当前屏幕的控件树（无障碍节点）：text / contentDescription / view-id / bounds / 是否可点击可编辑。比截图小 1–2 个数量级、且不受分辨率与动画影响；想「看屏幕上有什么」优先用它，只有节点树为空（游戏、Canvas、部分 Flutter 界面）时才回退 phone_screenshot。',
      properties: {
        maxDepth: { type: 'number', description: '最大深度，默认 30' },
        maxNodes: { type: 'number', description: '最多返回多少个节点，默认 400' },
        includeInvisible: { type: 'boolean', description: '是否包含不可见节点，默认 false' },
      },
      timeoutMs: 20000,
      present: { card: 'generic', title: '手机控件树', kind: 'read' },
      async run(args) {
        return service.request(
          'ui_dump',
          {
            maxDepth: Number(args?.maxDepth ?? 30),
            maxNodes: Number(args?.maxNodes ?? 400),
            includeInvisible: args?.includeInvisible === true,
          },
          15000,
        )
      },
    }),
    makeTool({
      name: 'phone_tap_text',
      description:
        '按文字点击手机屏幕上的控件（优先无障碍 ACTION_CLICK，自绘控件退回按 bounds 中心手势点击）。比「先截图再换算坐标」更稳更省，是点击按钮/列表项的首选。',
      properties: {
        text: { type: 'string', description: '要点击的文字 / contentDescription / view-id 片段' },
        exact: { type: 'boolean', description: '是否要求完全相等，默认 false（包含匹配）' },
        index: { type: 'number', description: '命中多个时取第几个（从 0 开始），默认 0，可点击的优先' },
      },
      required: ['text'],
      timeoutMs: 15000,
      present: { card: 'generic', title: '按文字点击', kind: 'action' },
      async run(args) {
        return service.request(
          'tap_text',
          { text: String(args.text ?? ''), exact: args?.exact === true, index: Number(args?.index ?? 0) },
          10000,
        )
      },
    }),
    makeTool({
      name: 'phone_set_text',
      description:
        '把文字直接写入输入框（无障碍 ACTION_SET_TEXT，不经键盘）：不传 target 就写进当前聚焦的输入框，传 target 则先按文字定位那个输入框。失败会返回 NOT_EDITABLE，此时改用 phone_input。',
      properties: {
        text: { type: 'string', description: '要写入的文字（会替换原内容）' },
        target: { type: 'string', description: '可选：输入框的提示文字 / view-id 片段，用于定位' },
      },
      required: ['text'],
      timeoutMs: 20000,
      present: { card: 'generic', title: '写入输入框', kind: 'action' },
      async run(args) {
        return service.request(
          'set_text',
          { text: String(args.text ?? ''), ...(args?.target === undefined ? {} : { target: String(args.target) }) },
          15000,
        )
      },
    }),
    makeTool({
      name: 'phone_fs_list',
      description:
        '列出手机上的一个目录（默认 /sdcard）：名称、完整路径、是否目录、大小、修改时间、是否可读可写。需要手机端已打开「权限与安全 → 文件读写」，否则只能访问 App 私有目录。',
      properties: {
        path: { type: 'string', description: '目录路径，默认 /sdcard' },
        maxEntries: { type: 'number', description: '最多返回多少条，默认 500，上限 5000' },
      },
      timeoutMs: 30000,
      present: { card: 'generic', title: '手机目录', kind: 'read' },
      async run(args) {
        return service.request(
          'fs_list',
          { path: String(args?.path ?? '/sdcard'), maxEntries: Number(args?.maxEntries ?? 500) },
          25000,
        )
      },
    }),
    makeTool({
      name: 'phone_fs_stat',
      description: '查看手机上一个文件或目录的信息：类型、大小、修改时间、可读可写。',
      properties: { path: { type: 'string', description: '文件或目录的绝对路径' } },
      required: ['path'],
      timeoutMs: 15000,
      present: { card: 'generic', title: '手机文件信息', kind: 'read' },
      async run(args) {
        return service.request('fs_stat', { path: String(args.path) }, 10000)
      },
    }),
    makeTool({
      name: 'phone_fs_read',
      description:
        '读取手机上的文件。默认按 UTF-8 文本返回（超过 maxChars 会截断），传 base64: true 则返回原始 base64。大文件用 offset/length 分块，默认一次 512 KB、上限 4 MB。',
      properties: {
        path: { type: 'string', description: '文件绝对路径' },
        offset: { type: 'number', description: '从第几字节开始，默认 0' },
        length: { type: 'number', description: '读多少字节，默认 524288，上限 4194304' },
        maxChars: { type: 'number', description: '文本模式最多返回多少字符，默认 20000' },
        base64: { type: 'boolean', description: '为 true 时返回 base64 而不是文本' },
      },
      required: ['path'],
      timeoutMs: 60000,
      present: { card: 'generic', title: '读取手机文件', kind: 'read' },
      async run(args) {
        const raw = await service.request(
          'fs_read',
          {
            path: String(args.path),
            offset: Number(args?.offset ?? 0),
            length: Number(args?.length ?? 512 * 1024),
          },
          55000,
        )
        const buffer = Buffer.from(String(raw?.dataB64 ?? ''), 'base64')
        const base = {
          path: raw?.path,
          offset: raw?.offset,
          bytes: raw?.bytes,
          size: raw?.size,
          eof: raw?.eof,
          nextOffset: raw?.nextOffset,
        }
        if (args?.base64 === true) return { ...base, data: buffer.toString('base64') }
        const text = buffer.toString('utf8')
        const limit = Math.max(0, Number(args?.maxChars ?? 20000))
        return {
          ...base,
          text: text.length > limit ? text.slice(0, limit) : text,
          truncated: text.length > limit,
        }
      },
    }),
    makeTool({
      name: 'phone_fs_write',
      description:
        '往手机写文件：文本用 text，二进制用 dataB64；offset 默认 0，truncate 默认 true（覆盖原文件），传 false 可续写。父目录不存在会自动建。',
      properties: {
        path: { type: 'string', description: '文件绝对路径' },
        text: { type: 'string', description: '要写入的文本（UTF-8）' },
        dataB64: { type: 'string', description: '要写入的二进制（base64），与 text 二选一' },
        offset: { type: 'number', description: '写入起始偏移，默认 0' },
        truncate: { type: 'boolean', description: '是否先截断，默认 true' },
      },
      required: ['path'],
      timeoutMs: 60000,
      present: { card: 'generic', title: '写入手机文件', kind: 'action' },
      async run(args) {
        const hasText = typeof args?.text === 'string'
        const hasData = typeof args?.dataB64 === 'string'
        if (!hasText && !hasData) throw new Error('要写什么？text 和 dataB64 至少给一个')
        const dataB64 = hasData ? String(args.dataB64) : Buffer.from(String(args.text), 'utf8').toString('base64')
        return service.request(
          'fs_write',
          {
            path: String(args.path),
            dataB64,
            offset: Number(args?.offset ?? 0),
            truncate: args?.truncate !== false,
          },
          55000,
        )
      },
    }),
    makeTool({
      name: 'phone_fs_mkdir',
      description: '在手机上创建目录（父目录一起建）。',
      properties: { path: { type: 'string', description: '目录绝对路径' } },
      required: ['path'],
      timeoutMs: 15000,
      present: { card: 'generic', title: '建目录', kind: 'action' },
      async run(args) {
        return service.request('fs_mkdir', { path: String(args.path) }, 10000)
      },
    }),
    makeTool({
      name: 'phone_fs_rm',
      description: '删除手机上的文件或目录（目录必须 recursive: true）。',
      properties: {
        path: { type: 'string', description: '要删除的路径' },
        recursive: { type: 'boolean', description: '删目录时传 true' },
      },
      required: ['path'],
      timeoutMs: 30000,
      present: { card: 'generic', title: '删除手机文件', kind: 'action' },
      async run(args) {
        return service.request(
          'fs_rm',
          { path: String(args.path), recursive: args?.recursive === true },
          25000,
        )
      },
    }),
    makeTool({
      name: 'phone_tap',
      description: '点击手机屏幕上的一个坐标。坐标系与 phone_screenshot 返回图片的像素坐标一致。',
      properties: { x: NUMBER, y: NUMBER },
      required: ['x', 'y'],
      timeoutMs: 15000,
      present: { card: 'generic', title: '手机点击', kind: 'action' },
      async run(args) {
        return service.request('tap', { x: Number(args.x), y: Number(args.y), durationMs: 50 }, 10000)
      },
    }),
    makeTool({
      name: 'phone_long_press',
      description: '在手机屏幕坐标上长按，默认 800ms。',
      properties: { x: NUMBER, y: NUMBER, durationMs: { type: 'number', minimum: 100, maximum: 10000 } },
      required: ['x', 'y'],
      timeoutMs: 15000,
      present: { card: 'generic', title: '手机长按', kind: 'action' },
      async run(args) {
        return service.request(
          'long_press',
          { x: Number(args.x), y: Number(args.y), durationMs: Number(args.durationMs ?? 800) },
          10000,
        )
      },
    }),
    makeTool({
      name: 'phone_swipe',
      description: '从 (x1,y1) 滑动到 (x2,y2)，默认 300ms。',
      properties: {
        x1: NUMBER,
        y1: NUMBER,
        x2: NUMBER,
        y2: NUMBER,
        durationMs: { type: 'number', minimum: 50, maximum: 10000 },
      },
      required: ['x1', 'y1', 'x2', 'y2'],
      timeoutMs: 15000,
      present: { card: 'generic', title: '手机滑动', kind: 'action' },
      async run(args) {
        return service.request(
          'swipe',
          {
            x1: Number(args.x1),
            y1: Number(args.y1),
            x2: Number(args.x2),
            y2: Number(args.y2),
            durationMs: Number(args.durationMs ?? 300),
          },
          10000,
        )
      },
    }),
    makeTool({
      name: 'phone_input',
      description: '向手机当前获得焦点的输入框写入文本（替换原内容）。失败时返回 INPUT_UNSUPPORTED。',
      properties: { text: { type: 'string' } },
      required: ['text'],
      timeoutMs: 20000,
      present: { card: 'generic', title: '手机输入', kind: 'action' },
      async run(args) {
        return service.request('input', { text: String(args.text ?? '') }, 15000)
      },
    }),
    makeTool({
      name: 'phone_key',
      description: '发送全局按键：back（返回）、home（桌面）、recents（最近任务）、notifications（通知栏）。',
      properties: { key: { type: 'string', enum: ['back', 'home', 'recents', 'notifications'] } },
      required: ['key'],
      timeoutMs: 10000,
      present: { card: 'generic', title: '手机按键', kind: 'action' },
      async run(args) {
        return service.request('key', { key: String(args.key) }, 8000)
      },
    }),
    makeTool({
      name: 'phone_open_app',
      description: '通过包名打开手机上的应用。',
      properties: { package: { type: 'string' } },
      required: ['package'],
      timeoutMs: 15000,
      present: { card: 'generic', title: '打开手机应用', kind: 'action' },
      async run(args) {
        return service.request('open_app', { package: String(args.package) }, 10000)
      },
    }),
    makeTool({
      name: 'phone_current_app',
      description: '返回手机当前前台应用的包名和界面标题。',
      timeoutMs: 10000,
      present: { card: 'generic', title: '手机前台应用', kind: 'read' },
      async run() {
        return service.request('current_app', {}, 8000)
      },
    }),
    makeTool({
      name: 'phone_list_apps',
      description: '列出手机已安装且可启动的应用（包名与名称）。',
      timeoutMs: 15000,
      present: { card: 'generic', title: '手机应用列表', kind: 'read' },
      async run() {
        return service.request('list_apps', {}, 12000)
      },
    }),
    makeTool({
      name: 'phone_disconnect',
      description: '从电脑端断开当前手机连接；手机通知会变为未连接。',
      timeoutMs: 10000,
      present: { card: 'generic', title: '断开手机桥', kind: 'action' },
      async run() {
        const closed = service.disconnectAll()
        return { ok: true, closed, status: service.status() }
      },
    }),
  ]

  for (const definition of definitions) {
    ctx.effect(() => ctx.tools.register(definition), `dsh-phone-control: ${definition.name}`)
  }

  ctx.skills.registerProvider(() => provider)

  ctx.effect(() => {
    void service.start().catch((error) => {
      ctx.logger?.warn?.(`[phone-control] start failed: ${error?.message ?? String(error)}`)
    })
    return () => service.dispose()
  }, 'dsh-phone-control: tcp listener')
}
