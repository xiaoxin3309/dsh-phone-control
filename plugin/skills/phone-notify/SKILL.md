---
name: phone-notify
description: 往已配对的 Android 手机推一条通知（会注明来自哪个会话），或查询手机端通知权限与开关。会话回答结束、智能体提问需要回复时，插件会自动推送。
user-invocable: true
disable-model-invocation: false
---

# phone-notify

手机通知有两条来源：**插件自动推**（用户不必开口）和**你主动推**（`phone_notify`）。两者走同一个通知渠道，同一会话只保留最新一条。

## 什么时候该主动推

- 用户在离开电脑前说了「好了叫我 / 跑完告诉我 / 有事喊我」——中途你不用每条都推，收尾时推一条。
- 一件耗时的事做完了、或卡在必须他本人处理的地方（要验证码、要人脸、要在手机上手点），而他已经不在电脑前。
- 用户明确让你「发到手机上」。

不该推的情况：用户就在电脑前看着会话、每完成一步都推一遍、把同一件事重复推多次（插件对同一会话同类通知有 4 秒节流，别指望靠连发绕过）。

## 工具

- `phone_notify`：`title`（缺省「来自 DSH 的提醒」）、`body`（正文，约 240 字内最合适）、`session`（会话名或标识，手机上按它区分不同会话；缺省 `dsh-manual`）。
- `phone_notify { probe: true }`：只查询手机端状态，返回 `{permission, enabled, complete, question, manual}` 五个布尔——分别对应系统通知权限、总开关、「回答完成」开关、「等你回答」开关、手动通知开关。

返回 `{ok, sent, reason}`：`sent: true` 表示手机真的弹了；`false` 时 `reason` 说明原因——

| reason | 含义 | 怎么办 |
| --- | --- | --- |
| `no_device` | 手机没连上 | 让用户在 App 里连接电脑 |
| `permission` | 系统通知权限没给 | 让用户在 App「会话通知」卡片里授权 |
| `disabled` | 用户关了总开关 | 同上，别绕过用户设置 |
| `kind_off` | 这一类通知被单独关掉 | 同上 |
| `no_service` | 手机端通知服务不可用 | 让用户重开 App |
| `timeout` / `error` | 手机没回包 | 重试一次，仍失败就直说 |
| `throttled` | 4 秒内同一会话同类通知重复 | 等一下或换个说法，别连发 |

## 自动通知（你不用管，但要知道）

插件订阅了所有会话的 `session/event`：

- `turn/end` 且 `reason.kind === 'completed'` → 「『会话名』已回答」+ 最后一轮的回复摘要。
- `tool/call` 且工具是 `ask_user_question` → 「『会话名』在等你回答」+ 问题原文。

会话名来自 `session/title` 事件（用户改名或自动生成时更新），没拿到标题时退化成本会话 id 的前 8 位。这些推送是否真的落到手机上，取决于 App 里的两个开关和系统权限，所以「用户说没收到」时先 `phone_notify { probe: true }`。

## 汇报口径

推成功了就说推了；`reason` 不是 `shown` 就照实说哪一环关着，不要用「已发送」糊过去。
