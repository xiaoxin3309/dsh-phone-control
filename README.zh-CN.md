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
plugin/        DSH 插件：15 个工具 + 设置面板（npm 包名 `dsh-phone-control`）
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
5. 在 DSH 的「手机桥」面板里看 **8 位配对码**，填进 App，点**连接**。面板变绿、通知显示「已连接」即可。

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

插件唯一的运行时依赖是 `ws`；已经发布到 npm 的包会把依赖一起带上。

## 设置面板

*设置 → 手机桥*显示：

- 连接状态灯（绿=已连接，黄=连接中，灰=空闲）
- 8 位配对码（带倒计时）和**换一个**按钮
- 监听地址与端口
- 已配对设备数、活动会话数、失败次数
- **断开手机**按钮
- 端口输入框（`0` = 自动挑空闲端口）与绑定地址下拉框

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
| `phone_disconnect` | 从电脑端断开当前会话 |

典型流程：

```
phone_ui_dump                      → 用文字找到「登录」按钮
phone_tap_text {text: "登录"}       → 点它
phone_set_text {target: "邮箱", text: "..."}   → 填字段
phone_ui_dump                      → 确认界面已经变了
```

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
  换 token 时旧的仍保留 10 分钟宽限，避免重连被自己锁死。
- 配对码 5 分钟过期、每次配对成功后立即轮换；连续 5 次错误锁定 5 分钟，强制换码即解锁。
- HTTP 设置接口只接受同源或回环地址的请求。

## 安全提示

- 插件默认监听 `0.0.0.0:8788`。不建议直接把这端口暴露在公网，请放在隧道后面；只用隧道时也可以绑 `127.0.0.1`。
- 无障碍服务能读屏和点击屏幕上的一切。只配对你自己控制的设备，用完后在面板里断开并清除登录凭证。
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
| 超时 | 手机休眠或离线 | 唤醒手机，App 会自动重连 |

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
