---
name: phone-control
description: 通过 DSH 手机桥操控已配对的 Android 手机：读控件树/按文字点击/输入文字，必要时截图和坐标操作，还能列目录、读写文件、建删目录。手机 App 需已连接电脑并开启无障碍服务。
user-invocable: true
disable-model-invocation: false
---

# phone-control

用这组工具操作用户已经配对的 Android 手机。手机 App 必须已连接电脑，且用户已开启无障碍服务。

## 基本原则

1. **先读节点树，再考虑截图。** 想知道屏幕上有什么，先 `phone_ui_dump`（几百 token），能直接拿到 `text` / `id` / `bounds` / 可点击标志；只有节点树为空（游戏、Canvas、部分 Flutter 界面）才回退 `phone_screenshot`。
2. **优先按文字操作。** `phone_tap_text` 走无障碍 `ACTION_CLICK`，与分辨率无关、不会点偏；`phone_set_text` 直接写输入框，不用切键盘。坐标只是兜底。
3. **坐标就是截图像素。** 只有 `phone_tap` / `phone_long_press` / `phone_swipe` 用坐标，基准是 `phone_screenshot` 返回图片的原始像素，不是 dp，也不是电脑屏幕坐标。
4. **操作后验证。** 点击、输入、滑动后重新 `phone_ui_dump`（或截图）确认界面确实变化；没有变化就报告，不要连续盲点。
5. **敏感操作先问。** 支付、转账、发消息、删除数据、修改账号设置前，先向用户确认；用户没有明确要求就不要做。
6. **失败就说失败。** `NO_DEVICE` 表示手机没连接；`TIMEOUT` 表示手机没响应；`NOT_EDITABLE` 表示该输入框拒绝直接写入（改用 `phone_input` 或让用户手输）；`NODE_NOT_FOUND` 表示树上没有这个文字（先 `phone_ui_dump` 看看实际文案）。
7. **文件操作先看清再动手。** 写之前先 `phone_fs_stat` / `phone_fs_read` 确认目标（`truncate` 默认覆盖！）；`phone_fs_rm` 删的是用户手机上的真实文件，除非用户明确点名，否则不要删，也不要对 `/sdcard` 之类的大目录跑递归删除。

## 工具

- `phone_status`：查看监听端口、是否已连接、配对码、会话数。
- `phone_pair_code`：获取 8 位配对码，给用户在手机 App 里输入；传 `refresh: true` 可强制换新码并解除错误次数锁定。
- `phone_ui_dump`：读当前屏幕控件树（`maxDepth` / `maxNodes` / `includeInvisible` 可调），返回 `{package, count, truncated, nodes:[{i,d,c,t,cd,id,b,f}]}`——`t` 文本、`cd` 描述、`id` view-id、`b` 界限 `[l,t,r,b]`、`f` 标志（`click`/`edit`/`scroll`/`checked`/`foc`/`dis`）。
- `phone_tap_text`：按文字/描述/view-id 点击，`exact` 控制完全匹配，`index` 取第几个（可点击的优先）；返回 `method` = `action` / `ancestor-action` / `gesture`。
- `phone_set_text`：向聚焦输入框（或 `target` 指定的输入框）直接写入文字，替换原内容。
- `phone_screenshot`：截屏并返回 PNG；坐标基准，也是节点树为空时的兜底。
- `phone_tap`：点击 `(x, y)`。
- `phone_long_press`：长按 `(x, y)`，默认 800ms。
- `phone_swipe`：从 `(x1,y1)` 滑到 `(x2,y2)`，默认 300ms。
- `phone_input`：向当前焦点输入框写入文本（替换原内容）。
- `phone_key`：`back` / `home` / `recents` / `notifications`。
- `phone_open_app`：按包名打开应用。
- `phone_current_app`：查看当前前台应用。
- `phone_list_apps`：列出已安装且可启动的应用（约 250 个）。
- `phone_fs_list` / `phone_fs_stat`：列目录（默认 `/sdcard`，`maxEntries` 默认 500）、看某个路径的类型/大小/读写权限。
- `phone_fs_read`：读文件，默认 UTF-8 文本（`maxChars` 默认 20000 会截断），`base64: true` 返回原始字节；大文件用 `offset` + `length` 分块（默认 512 KB，上限 4 MB）。
- `phone_fs_write`：写文件，文本用 `text`、二进制用 `dataB64`；`truncate` 默认 `true`（覆盖），传 `false` 才续写；父目录自动建。
- `phone_fs_mkdir` / `phone_fs_rm`：建目录（父目录一起建）/ 删文件或目录（删目录必须 `recursive: true`）。
- `phone_disconnect`：从电脑端断开当前会话；`forget: true` 会撤销所有已签发的 token（手机丢失/换机时用，之后必须重新配对）。
- `phone_notify`：往手机推一条通知（`title` / `body` / `session`），`probe: true` 只查手机端的通知权限与开关。要长期用通知找人，读 `phone-notify` 技能。

## 坐标建议（iQOO V2425A，截图 1260×2800）

- 顶部状态栏约 `y = 0–120`，底部导航条约 `y = 2600–2800`，交互控件尽量避开这两段。
- **从桌面底部上滑回主页不可靠**：起点 `y = 2500` 不触发系统 Home。优先用 `phone_key { key: "home" }`；必须用手势时起点取 `y ≥ 2700`。
- 设置列表、聊天列表这类纵向列表，滑动幅度取屏高的 40%–60%，滑动后必须重新截图确认。

## 配对/连接错误码

| 码 | 含义 | 处理 |
|---|---|---|
| `NO_DEVICE` | 手机未连接 | 让用户在 App 里连接电脑 |
| `LOCKED` | 配对码错误 5 次，锁定 5 分钟 | 等倒计时，或 `phone_pair_code { refresh: true }` 换码解锁 |
| `CODE_EXPIRED` | 配对码超过 5 分钟 | `phone_pair_code` 获取新码 |
| `BAD_CODE` | 配对码错误 | 核对 8 位数字，注意剩余次数 |
| `TOKEN_INVALID` | 登录凭证失效 | 手机 App 会清空旧 token，重新配对 |
| `TIMEOUT` | 手机未在超时内响应 | 重新截图确认设备是否卡死 |
| `SELF_PROTECTED` | 输入类命令打到手机桥自己的界面（默认保护） | 先把前台切走；或让用户打开「允许远程操作本 App」 |
| `PERMISSION_DENIED` | 文件路径越出 App 沙箱，或没开「所有文件访问」 | 让用户在 App 设置页「权限与安全」里授权，或改用 App 私有目录 |

## 典型流程

```
phone_status
phone_screenshot
# 看图，确定按钮像素坐标
phone_tap { x: 540, y: 2100 }
phone_screenshot
```

如果截图返回的图片尺寸与手机实际分辨率不同，以图片像素为准；App 会在结果里同时返回 `width` / `height`。
