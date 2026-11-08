# 手机端「思考/动作」悬浮 HUD —— 可行性分析

> 需求（2026-11-08）：智能体操控手机时，在屏幕上部半透明地显示最近几条「思考 + 动作」；
> 但这块显示**不能**被智能体自己的截图和无障碍抓取看到。先只讨论，不动手。

## 结论先说

**可行，而且是 Android 上有正规做法的一件事**，不需要 root，也不需要改系统。

两条独立的「别让智能体看见」的能力各自都有官方 API：

| 智能体的取景手段 | 现状 | 遮蔽手段 | 可靠度 |
| --- | --- | --- | --- |
| 截图（MediaProjection） | 手机桥 `phone_screenshot` 走虚拟显示器抓帧 | 悬浮窗加 `FLAG_SECURE` | 高（省电/录屏同机制，OEM 偶有差异） |
| 无障碍（`phone_ui_dump`） | 遍历各窗口的节点树 | `IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS` | 高 |

代价是：**那块区域在智能体眼里会是一个黑/空区域**（`FLAG_SECURE` 不是「透明」，是「不给抓」），
所以 HUD 得做得够窄、贴着顶部，别压住智能体要点的东西。这一条是设计约束，不是技术障碍。

## 一、数据从哪来

不需要「从 webview 抓」。插件已经订阅了所有会话的 `session/event`（通知功能刚用的同一条链路），
里面天然就有：

- `tool/call` → `{ name, arguments }`：动作（点了哪个按钮、读了哪个文件）
- `assistant/message` → `content` 里的 text 块：正在说什么
- 若模型走 reasoning 通道，还有思考增量（`assistant/chunk` 一类），可以只取最近几行

路径：`会话事件 → 插件（节流、裁剪成 3~5 行）→ 现有 WS 通道 → App`，
和 `notify` 命令完全同构，只是 `cmd` 换成 `hud_push`。**这一层几乎没有新东西**。

## 二、画在屏幕上：悬浮窗

1. 权限：`SYSTEM_ALERT_WINDOW`（「显示在其他应用的上层」），App 里已有设置页可放一个开关 + 跳系统授权页。
2. 窗口：`WindowManager.addView` + `TYPE_APPLICATION_OVERLAY`，
   `gravity = TOP|CENTER_HORIZONTAL`，`y` 让开状态栏和挖孔；
   布局参数必带 `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_NO_LIMITS`，
   这样它既不吃点击也不抢焦点 —— 智能体的 tap/swipe 会直接穿到下面的应用。
3. 样式：半透明黑底 + 白字、圆角、单行截断、最近 3~5 条、新条目淡入、静置 10 秒自动收成一条细条。

## 三、不让截图看见：`FLAG_SECURE`

`WindowManager.LayoutParams.FLAG_SECURE` 的语义是「这个窗口的内容不进入截图/录屏/投屏」。
`MediaProjection` 抓帧时，安全窗口会被涂黑（或整帧不带它）。
这跟银行 App 防截屏是同一个机制，属于受支持行为，不是黑科技。

**验证方式（真机一次就能定性）**：开 HUD → `phone_screenshot` → 顶部应是黑条而不是文字；
同时用手机自带的截图/录屏看，也应是黑条（说明真的生效，而不是我们自己的抓帧路径特殊）。

已知变数：
- 个别 OEM（vivo/OriginOS 这类）对「安全层」的处理有差异，可能表现为整帧变暗而不是局部黑条。要实测。
- 若 App 以后要做「把画面转给电脑看」的投屏功能，`FLAG_SECURE` 会一并挡掉 —— 那时得改成**按需开关**。

## 四、不让无障碍看见

悬浮窗的 View 默认会进无障碍树（多一个 window，节点里有文字）。
两根手指按住也关不掉，得显式声明：

- 根 View `setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)`
  —— 自己和所有子节点都不再上报；
- 不加 `FLAG_NOT_FOCUSABLE` 之外的焦点能力，避免成为可聚焦窗口。

这样 `phone_ui_dump` 遍历窗口时，要么看不到这个 window，要么看到空 window（无节点）。
顺手还能防一手：普通读屏软件也读不到它 —— 对视障用户是缺点，对这个用途是优点。

## 五、代价与坑（决定做不做之前先看这几条）

1. **黑条会挡视野。** 智能体截图时顶部那条是黑的。如果它要点的东西正好在顶部（标题栏、搜索框），
   它会「看不见但点得着」，容易误判。对策：HUD 只在有事件时出现、静置自动收起、高度不超过状态栏下 3 行；
   再给 `phone_screenshot` 加一个参数 `withoutHud`（截之前临时隐藏，截完恢复）作为兜底。
2. **时间差。** 事件 → 手机渲染有几十到几百毫秒延迟，HUD 说的是「刚刚」，不是「此刻」。
3. **刷新频率。** 长任务里事件很密，必须节流（比如 300ms 合并一次），否则 WS 和绘制都白烧电。
4. **多会话。** 只显示「当前被操控的那个会话」，否则几路事件会打架；会话归属可由工具调用时带的 `session` 判定。
5. **权限被撤销。** 用户关掉「显示在其他应用的上层」后 `addView` 会抛 `BadTokenException`，要降级成「只在 App 内显示」。

## 六、备选方案对比

| 方案 | 隐蔽性 | 风险 | 结论 |
| --- | --- | --- | --- |
| **A. `FLAG_SECURE` 悬浮窗**（推荐） | 截图黑条 + 无障碍隐藏 | OEM 差异、遮挡 | 成本最低，两个 API 都稳定 |
| B. `SurfaceControl#setSkipScreenshot` | 更「干净」，截图里连黑条都没有 | 隐藏 API / 反射，版本一动就碎 | 想要极致体验再考虑，先不做 |
| C. 不隐藏，只做得不显眼 | 无 | 智能体会把 HUD 文字当成界面内容，直接污染判断 | 不可接受 |
| D. 把调试信息写进通知栏 | 用户要下拉才看得到 | 看不到「最近几条」 | 只适合终态提醒，不适合实时 |

## 七、如果要做，最小实现与验收

1. App 加 `SYSTEM_ALERT_WINDOW` 权限 + 设置页开关 + `HudOverlay` 单例（`addView`/`removeView`/`FLAG_SECURE`/`NO_HIDE_DESCENDANTS`）。
2. 服务加 `hud_push` 命令（`{lines: [{kind, text}], ttlMs}`），主线程更新 TextView。
3. 插件在现有 `session/event` 钩子里再攒一个 3~5 行的环形缓冲，节流 300ms 推一次；工具名翻成人话（`phone_tap_text` → 「点『设置』」）。
4. 验收四条：
   - 手机屏上能看到最近几条，且不挡点击；
   - `phone_screenshot` 里那一条是黑/空，不是文字；
   - `phone_ui_dump` 里找不到 HUD 的文字；
   - 关掉悬浮窗权限后 App 不崩、降级到「只在 App 内显示」。

工作量估计：Android 侧半天、插件侧两小时、真机验证一次。
