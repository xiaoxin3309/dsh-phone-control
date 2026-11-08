# 手机端「思考/动作」悬浮 HUD —— 可行性分析

> 需求（2026-11-08）：智能体操控手机时，在屏幕上部半透明地显示最近几条「思考 + 动作」；
> 但这块显示**不能**被智能体自己的截图和无障碍抓取看到。先只讨论，不动手。
>
> 2026-11-08 追加：能不能改成「智能体抓屏/读界面的那一刻把悬浮窗摘掉」，而不是给它打安全标记？

## 结论先说

**可行，而且「抓的那一刻摘掉」比「打 `FLAG_SECURE` 标记」更好。** 不需要 root，也不需要改系统。

两条路线都能达到「智能体看不见」，但代价完全不同：

| 路线 | 做法 | 智能体看到 | 人看到 | 代价 |
| --- | --- | --- | --- | --- |
| **A′. 抓帧瞬间摘窗口**（推荐） | 收到抓屏/读界面命令 → `removeViewImmediate` → 等下一帧 → 抓 → `addView` | 干净的原始画面 | 几乎察觉不到（约一帧，16–33 ms） | 每次感知多一帧延迟；只覆盖「智能体自己的抓取」 |
| A. 给窗口打 `FLAG_SECURE` | 窗口常驻，标记为安全窗口 | 顶部一条**黑带** | 正常 | OEM 行为差异；黑带可能挡住智能体要点的东西 |

推荐 **A′ 为主、A 为兜底**：A′ 解决「黑带挡视野」这个最大的实际问题，
A 只是在「别的工具/用户手动截图时也不该看见」这个更严的要求下补一层。

两条都不需要 `FLAG_SECURE` 之外的隐藏 API。

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
4. 因为走 A′ 路线，**不必为了「别挡黑带」而压得很窄**：可以放心用 3~5 行、字号大一点。

## 三、主方案 A′：抓帧瞬间摘掉窗口

关键点：**抓屏这件事是我们自己的代码在做的**（`phone_screenshot` 走 MediaProjection 虚拟显示器，
`phone_ui_dump` 走我们自己的 `AccessibilityService`），所以「先摘窗口、再抓、再放回去」
是一个完全自己掌控的同步序列，不需要和外部程序抢时序。

### 时序（截图）

```
WS 收到 screenshot 命令
  → 主线程 hud.hide()（removeViewImmediate：视图立刻从窗口层移除，不等下一帧动画）
  → 记录 hideAt = SystemClock.uptimeMillis()
  → ImageReader.acquireLatestImage()：时间戳 < hideAt 的帧一律丢弃，只吃移除之后产生的新帧
  → 编码成 PNG 回传
  → hud.show()（同一帧之后加回；人眼看到的是一帧的闪动，基本无感）
```

两处细节决定这条路靠不靠谱：

- **必须 `removeViewImmediate`**，不能用 `GONE` / `alpha = 0`：后者留下的窗口仍可能被合成器采样，
  也仍然在无障碍窗口列表里。
- **必须按时间戳等「新帧」**，不能「睡 16 ms 再抓」：虚拟显示器是连续出帧的，
  睡固定时长会偶发抓到摘除前的那一帧。`acquireLatestImage` 拿到旧时间戳就 `close()` 再等。

### 时序（读界面）

`phone_ui_dump` 走无障碍：`rootInActiveWindow` 枚举的是**窗口列表**。
窗口被 `removeViewImmediate` 移除后，它的 `AccessibilityNodeInfo` 就不在树里了，
所以顺序是 `hide() → dump() → show()`，同样不需要等帧，因为无障碍是同步查询。

### 代价

- 每次感知多约一帧（16–33 ms）。现在的命令往返本来就 200 ms 上下，这一帧可以忽略。
- 摘/加窗口有极小概率抛 `BadTokenException`（OEM 在窗口服务卡顿时），要 try/catch 并记一条日志，
  不能因为 HUD 失败影响截图本身 —— **HUD 永远是旁路**。
- 只覆盖「智能体自己的抓取」。用户在手机上手滑截图、或用 adb 截图、或别的 App 录屏，
  仍然会拍到 HUD。这是 A′ 的边界，也正是下面 A 存在的理由。

## 四、兜底方案 A：`FLAG_SECURE`

`WindowManager.LayoutParams.FLAG_SECURE` 的语义是「这个窗口的内容不进入截图/录屏/投屏」，
`MediaProjection` 抓帧时安全窗口会被涂黑。这就是银行 App 防截屏的机制，属于受支持行为。

如果决定启用它，最好做成**可开关**（默认关）：

- 打开后，任何抓帧（包括用户手动截图、别的录屏）里都没有 HUD —— 严格满足「不被捕获」；
- 但 A′ 正好不生效的场合（智能体抓取）已经有 A′ 了，所以 `FLAG_SECURE` 只为「外人」服务；
- 若以后要做「把手机画面转给电脑看」的投屏功能，`FLAG_SECURE` 会一并挡掉，那时必须能关掉。

已知变数：个别 OEM（vivo/OriginOS 这类）对安全层的处理有差异，可能表现为整帧变暗而不是局部黑条，要实测。

## 五、不让无障碍看见（A′ 已覆盖，再补一道）

A′ 的摘窗口天然让 `phone_ui_dump` 看不到。但如果以后 HUD 改成「常驻显示、不随抓取摘除」，
就需要显式声明：

- 根 View `setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)`
  —— 自己和所有子节点都不再上报；
- 不加 `FLAG_NOT_FOCUSABLE` 之外的焦点能力，避免成为可聚焦窗口。

这条几乎零成本，建议不管走哪条路线都加上（防御性）。

## 六、代价与坑（决定做不做之前先看这几条）

1. **A′ 的边界是「智能体自己的抓取」**。用户手动截图/别的录屏仍会看到 HUD。
   要彻底，就开 `FLAG_SECURE`（接受黑带）或接受这个边界 —— 建议接受，因为需求本来就是「智能体看不见」。
2. **时间差。** 事件 → 手机渲染有几十到几百毫秒延迟，HUD 说的是「刚刚」，不是「此刻」。
3. **刷新频率。** 长任务里事件很密，必须节流（比如 300ms 合并一次），否则 WS 和绘制都白烧电。
4. **多会话。** 只显示「当前被操控的那个会话」，否则几路事件会打架；会话归属可由工具调用时带的 `session` 判定。
5. **权限被撤销。** 用户关掉「显示在其他应用的上层」后 `addView` 会抛 `BadTokenException`，
   要降级成「只在 App 内显示」，并且**不能影响截图/点击**。
6. **闪动感。** A′ 每抓一次就闪一下（一帧）。真机上如果肉眼能看出闪烁，退一步：
   只在 `phone_screenshot`/`phone_ui_dump` 摘除，`phone_tap` 这类不需要摘（点击不抓屏）。

## 七、备选方案对比

| 方案 | 隐蔽性 | 风险 | 结论 |
| --- | --- | --- | --- |
| **A′. 抓取瞬间摘窗口**（推荐） | 智能体自己抓 = 干净画面 | 只覆盖自家抓取；多一帧延迟 | 首选，最大好处是没有黑带 |
| A. `FLAG_SECURE` 常驻 | 任何抓取都看不见 | OEM 差异；顶部黑带挡视野 | 作为可开关的兜底 |
| A′ + A 同时用 | 智能体干净、外人也看不见 | 黑带只在 A 开启时出现 | 想要「绝对不漏」时这么配 |
| B. `SurfaceControl#setSkipScreenshot` | 截图里连黑条都没有 | 隐藏 API / 反射，版本一动就碎 | 不做 |
| C. 不隐藏，只做得不显眼 | 无 | 智能体会把 HUD 文字当成界面内容，直接污染判断 | 不可接受 |
| D. 把调试信息写进通知栏 | 用户要下拉才看得到 | 看不到「最近几条」 | 只适合终态提醒，不适合实时 |

## 八、如果要做，最小实现与验收

1. App 加 `SYSTEM_ALERT_WINDOW` 权限 + 设置页开关 + `HudOverlay` 单例
   （`show()` / `hide()` / `hideForCapture { block: () -> T }`，内部 `removeViewImmediate` + try/catch）。
2. 把 `phone_screenshot` 与 `phone_ui_dump` 的抓取动作包进 `hideForCapture { ... }`；
   `ImageReader` 那边加时间戳过滤（丢弃早于摘除时刻的帧）。
3. 服务加 `hud_push` 命令（`{lines: [{kind, text}], ttlMs}`），主线程更新 TextView。
4. 插件在现有 `session/event` 钩子里再攒一个 3~5 行的环形缓冲，节流 300ms 推一次；
   工具名翻成人话（`phone_tap_text` → 「点『设置』」）。
5. 验收五条：
   - 手机屏上能看到最近几条，且不挡点击（点它下面的按钮仍然生效）；
   - `phone_screenshot` 里**完全没有** HUD 的文字，也不是黑带；
   - `phone_ui_dump` 里找不到 HUD 的文字；
   - 连拍 10 张，没有一张漏出 HUD（验证时间戳过滤是否生效）；
   - 关掉悬浮窗权限后 App 不崩，截图/点击照常，HUD 静默降级。

工作量估计：Android 侧半天（A′ 的时序调试占大头）、插件侧两小时、真机验证一次。
