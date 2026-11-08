# DSH 手机桥

让运行在 [DeepSeek Harness](https://github.com/deepseek-ai)（DSH）里的 AI 直接操控一台真实的 Android 手机：
把屏幕读成**控件树**、按**文字**点击、往输入框写字、需要时再截图，实在没有别的办法才用坐标点。

[English](README.md) | **简体中文**

---

## 为什么优先读控件树而不是截图

一张 1080×2400 的截图大约是 1–2 MB 的 PNG，会吃掉模型大量上下文；同一屏的无障碍控件树是 1–3 KB 的
JSON（通常几百 token），而且带着截图给不了的信息：文字、view-id、可点击标志、可编辑标志、边界坐标。

| 方式 | 数据量 | 能得到什么 |
| --- | --- | --- |
| `phone_screenshot` | 1–2 MB PNG | 像素，坐标只能靠估 |
| `phone_ui_dump` | 1–3 KB JSON | 文字、id、可点击/可编辑、边界 |
| `phone_tap_text` | 几百字节 | 不用算像素就能点到正确的控件 |

控件树也有看不见的东西：用 `SurfaceView` 画的游戏、部分 Flutter/Unity 界面、标了
`importantForAccessibility="no"` 的自绘控件。这种情况就退回 `phone_screenshot` + `phone_tap`。

## 仓库结构

```
plugin/        DSH 插件：22 个工具 + 设置面板（npm 包名 `dsh-phone-control`）
android/       Android App：无障碍服务 + 连接客户端的实现
verification/  用 Node.js 跑协议、设置接口和打包产物的验证脚本
```

## 运行环境

- DeepSeek Harness `>= 0.2.0-rc.1`
- Node.js `>= 20`
- Android 11（API 30）或更高
- 手机电脑能互相访问：同一个 Wi-Fi，或者用隧道（见下文）

## 五步开始

1. 把插件装进 DSH profile（见[安装](#安装)）。
2. 重启 DSH，*设置 → 手机桥*面板就会出现。
3. 在手机上安装本仓库 Release 里的 APK。
4. 打开 App，开启**无障碍服务**（截图和点击都依赖它）。
5. 在 DSH 的「手机桥」面板里看 **8 位配对码**，填进 App，点**连接**；或者省掉手打——用 App 设置页的
   **扫码配对**扫面板上那张二维码，码、端口、地址会自动填好。面板变绿、通知显示「已连接」即可。

配对成功会在手机上保存一个 30 天的 token，以后打开 App 会自动重连。

## 安装

### 从 npm 安装

```bash
npm install dsh-phone-control --prefix ~/.dsh/profiles/desktop
```

然后把包名加进 `~/.dsh/profiles/desktop/package.json` 的 bundles：

```json
{
  "dsh": {
    "profile": {
      "bundles": ["...原有条目...", "dsh-phone-control"]
    }
  }
}
```

重启 DSH 生效。

### 从 tarball 安装

```bash
npm pack            # 生成 dsh-phone-control-0.1.2.tgz
npm install ./dsh-phone-control-0.1.2.tgz --prefix ~/.dsh/profiles/desktop
```

### 从源码安装

```bash
git clone <本仓库>
npm install <仓库>/plugin --prefix ~/.dsh/profiles/desktop
```

插件的运行时依赖是 `ws` 和 `qrcode`（二维码路由用）；已经发布到 npm 的包会把依赖一起带上。

## 设置面板

*设置 → 手机桥*显示：

- 连接状态灯（绿=已连接，黄=连接中，灰=空闲）
- 8 位配对码（带倒计时）和**换一个**按钮
- **配对二维码**（内容 `dsh-phone://pair?v=1&c=…&p=…&w=…`），配对码或对外地址一变就重画；手机扫码即配对
- 监听地址与端口
- 已配对设备数、活动会话数、失败次数
- **断开手机**按钮
- 端口输入框（`0` = 自动挑空闲端口）与绑定地址下拉框
- **悬浮 HUD** 开关（默认关，需要系统「显示在其他应用的上层」权限）

改端口会立即重绑监听并把选择写进 `<profile>/phone-bridge.json`；首次启动挑到的端口也会写回，重启后保持不变。

## 从外网连到手机

是手机主动连电脑，所以电脑必须能被手机访问到。两种做法：

**Cloudflare 临时隧道（最快）**

```bash
cloudflared tunnel --url http://127.0.0.1:8788     # 会打印一个 https 域名
```

把 App 里的「WebSocket 地址」填成 `wss://<打印出来的域名>/ws`。

**固定隧道（长期可用）**

```bash
cloudflared tunnel create dsh-phone
cloudflared tunnel route dns dsh-phone dsh.example.com
cloudflared service install
```

配置：

```yaml
tunnel: dsh-phone
credentials-file: C:\Users\<你>\.cloudflared\<tunnel-id>.json
ingress:
  - hostname: dsh.example.com
    service: http://127.0.0.1:8788
  - service: http_status:404
```

浏览器打开 `https://dsh.example.com/health` 能看到 `dsh-phone-bridge ok` 就说明隧道通了。
用 Tailscale 之类的组网工具也一样，App 只需要一条能到上面那个端口的 TCP 通路。

## 工具一览

| 工具 | 作用 |
| --- | --- |
| `phone_status` | 监听状态、已连设备、配对码、端口、待处理请求数 |
| `phone_pair_code` | 当前配对码与剩余时间；`refresh: true` 换新码并解除锁定 |
| `phone_ui_dump` | 输出控件树 JSON（字段 `i/d/c/t/cd/id/b/f`，支持 `maxDepth` / `maxNodes` / `includeInvisible`） |
| `phone_tap_text` | 按文字 / contentDescription / view-id 点击，点不动时自动退回手势点击 |
| `phone_set_text` | 按提示文字或 view-id 定位输入框并写入 |
| `phone_screenshot` | 当前屏幕截图（PNG） |
| `phone_tap` / `phone_long_press` / `phone_swipe` | 坐标操作，和截图同一像素空间 |
| `phone_input` | 向当前焦点输入框打字 |
| `phone_key` | `back` / `home` / `recents` / `notifications` |
| `phone_open_app` / `phone_current_app` / `phone_list_apps` | 打开、识别、列出应用 |
| `phone_fs_list` / `phone_fs_stat` | 列目录（默认 `/sdcard`）/ 看某个路径的类型、大小、读写权限 |
| `phone_fs_read` / `phone_fs_write` | 读文件（UTF-8 文本或 base64，`offset`/`length` 分块）/ 写文本或 base64 |
| `phone_fs_mkdir` / `phone_fs_rm` | 建目录（父目录一起建）/ 删文件或目录 |
| `phone_disconnect` | 从电脑端断开当前会话；`forget: true` 同时撤销所有已签发的 token |
| `phone_notify` | 往手机推一条通知（`title` / `body` / `session`）；`probe: true` 查手机端通知权限与开关 |

典型流程：

```
phone_ui_dump                      → 用文字找到「登录」按钮
phone_tap_text {text: "登录"}       → 点它
phone_set_text {target: "邮箱", text: "..."}   → 填字段
phone_ui_dump                      → 确认界面已经变了
```

两个默认打开的保险：

- 手机前台**就是手机桥自己**的时候，`phone_tap` / `phone_tap_text` / `phone_set_text` / `phone_swipe` /
  `phone_input` 一律回 `SELF_PROTECTED`——免得 Agent 把放它进来的那个界面点坏。设置页「权限与安全」里有
  开关（默认关）可以放开。
- 没开「所有文件访问」时，文件类工具只能碰 App 私有目录，越界回 `PERMISSION_DENIED`；那张卡片能直接跳到
  系统开关。

## 会话通知

有两件事值得在你不看屏幕时推一条：一轮回答刚结束，以及有个问题在等你回答。插件盯着所有会话的事件，把这两类推到手机：

- `turn/end` 且 `reason.kind === 'completed'` → **「某会话已回答」**，附最后一段回复的尾巴。
- `tool/call` 打到 `ask_user_question` → **「某会话在等你回答」**，附问题原文。

标题里的会话名取自会话标题（拿不到就退化成 id 的前 8 位），所以几路会话同时跑也分得清；插件晚于会话启动时才挂载也不影响——拿不到标题时会从会话日志里回查一次。手机没连、或者用户在 App 里关了某一类，就安静地不发，工具调用回 `{sent: false, reason}` 而不是报错。App 的「会话通知」卡片里有系统权限、总开关和两个分类开关；想知道当前状态用 `phone_notify { probe: true }`。

也可以手动推：`phone_notify { title, body, session }`。什么时候值得打断用户，见 `phone-notify` 技能。

## 悬浮 HUD

App 设置页里有「悬浮 HUD」开关，**默认关闭**；第一次打开会跳到系统「显示在其他应用的上层」授权页。打开之后，智能体操控这台手机时，屏幕顶部会出现一条半透明浮层，把正在做的事写成一行行字：

```text
… 正在思考下一步
▸ 点击文字「微信」
… 先看看通知栏里刚才那条，再决定点哪个
▸ 输入文字「你好」
✓ 本轮结束
```

它只显示**动作**与**思考**，不显示模型的回答正文；思考那一行是**实时**的：模型一边想，浮层上那一行就在原地往后滚（每 400 毫秒推一次最新的尾巴），不会一行行往下堆。动作行和思考行加起来最多 5 行，超出的从最旧的开始丢。**一整轮里这些行会一直留在屏幕上（不自动淡出）**，`✓ 本轮结束` 之后再过 5 秒整块淡出，不是缩成细条。浮层不接收触摸（点穿），也不抢焦点。

另一个细节：**手机桥自己的界面在前台时，浮层会整块收起**——你在 App 里看配对码、开权限的时候，不会有一层自己的半透明黑底盖在上面；切到别的应用，刚攒下的那些行又会回来。

插件只在「最近调用过 `phone_*` 的会话」上推，所以普通聊天不会突然在你屏幕上冒出一条浮层；手机没连、或者你把 App 里的开关关掉，插件就安静地不发。

有一个边界要说清：**智能体自己的截图和控件树里看不到它**。抓帧前会把浮层临时摘掉、等一帧再抓，抓完放回；读控件树时同理。这是为了不让浮层污染 OCR 与控件定位——代价就是「你看得见，智能体看不见」。你手动截的图里是有的。

## 工作原理

```
手机  ── WebSocket ──►  电脑（DSH 插件，默认端口 8788）
       二进制帧           JSON 命令 / JSON 结果
```

- 帧格式：19 字节头（`DSHP`、版本、类型、标志、64 位序号、负载长度），单帧上限 8 MB。
- 握手：`HELLO` → `CHALLENGE` → `AUTH` → `OK`。会话密钥由临时 ECDH（prime256v1）与会话口令
  （配对码经 PBKDF2 拉伸，或预共享密钥）经 HKDF 派生，双方用 HMAC 互证；之后全部流量用 AES-256-GCM，
  序号写进 nonce。
- 配对成功签发 256 位随机 token，服务端只存它的 SHA-256（`<profile>/phone-bridge-token.json`）。
  已签发的 token **永不过期**（保留最近 16 条），治「睡一觉 / 被杀进程 / 覆盖安装后再连就 TOKEN_INVALID」。
- 配对码 5 分钟过期、每次配对成功后立即轮换；连续 5 次错误锁定 5 分钟，强制换码即解锁。
- HTTP 设置接口只接受同源或回环地址的请求。

## 安全提示

- 插件默认监听 `0.0.0.0:8788`。不建议直接把这端口暴露在公网，请放在隧道后面；只用隧道时也可以绑 `127.0.0.1`。
- 无障碍服务能读屏和点击屏幕上的一切；开了「所有文件访问」之后，文件类工具也能读写 App 能碰到的任何路径。
  只配对你自己控制的设备，不需要时把「允许远程操作本 App」关着，用完后在面板里断开并清除登录凭证。
- token 调试日志默认关闭，只有设 `DSH_PHONE_DEBUG=1` 时才写。

## 常见问题

| 现象 | 含义 | 处理 |
| --- | --- | --- |
| `NO_DEVICE` | 没有手机连着 | 看 App 状态、端口和隧道 |
| `TOKEN_INVALID` | 保存的 token 被拒 | 用新配对码重新配对 |
| `BAD_CODE` | 配对码不对 | 看面板里当前的码；每次配对成功都会换码 |
| `CODE_EXPIRED` | 超过 5 分钟 | 点**换一个** |
| `LOCKED` | 错误次数过多 | 等倒计时结束，或点**换一个** |
| `ACCESSIBILITY_OFF` | 无障碍没开 | 在 App 里开启；每次覆盖安装后都要重新开 |
| `NODE_NOT_FOUND` | 没有匹配的控件 | 先 dump 控件树，或改用截图 |
| `NOT_EDITABLE` | 匹配到的控件不能输入 | 直接定位到输入框本身 |
| `SELF_PROTECTED` | 输入类命令打到了手机桥自己的界面 | 先把前台切走，或打开「允许远程操作本 App」 |
| 超时 | 手机休眠或离线 | 唤醒手机，App 会自动重连 |
| `PERMISSION_DENIED` | 没开「所有文件访问」，路径越出 App 沙箱 | 在 App 设置页「权限与安全」里授权 |

## 编译 Android App

```bash
cd android
gradle :app:assembleDebug      # 需要 JDK 21 与 Android SDK 36
```

产物在 `android/app/build/outputs/apk/debug/app-debug.apk`。

注意：安装新版本会让系统撤销无障碍授权（所有 App 都这样），装完要重新开启一次。

## 测试

```bash
node verification/pair-token-test.mjs    # 配对、token、锁定、轮换
node verification/plugin-smoke.mjs       # 工具注册与接线
node verification/settings-smoke.mjs     # 设置接口与端口处理
node verification/pack-install-smoke.mjs # 打包产物安装后能否启动
```

## 许可证

MIT，见 [LICENSE](LICENSE)。
