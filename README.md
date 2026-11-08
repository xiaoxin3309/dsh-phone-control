# DSH Phone Control

Let an AI agent running in [DeepSeek Harness](https://github.com/deepseek-ai) (DSH) drive a real Android phone:
read the screen as a **node tree**, tap by **text**, type into fields, take screenshots, and fall back to raw
coordinates only when it has to.

**English** | [简体中文](README.zh-CN.md)

---

## Why a node tree instead of screenshots

A 1080×2400 screenshot is roughly 1–2 MB of PNG and a big chunk of the model's context. The accessibility
tree for the same screen is 1–3 KB of JSON — typically a few hundred tokens — and it comes with the things a
screenshot does not: labels, view ids, clickable flags, editable flags, bounds.

| Approach | Payload | What it gives you |
| --- | --- | --- |
| `phone_screenshot` | 1–2 MB PNG | Pixels; coordinates must be guessed |
| `phone_ui_dump` | 1–3 KB JSON | Text, ids, clickable/editable flags, bounds |
| `phone_tap_text` | a few hundred bytes | Clicks the right widget without any pixel math |

The node tree cannot see everything: games drawn on a `SurfaceView`, some Flutter/Unity UIs, and custom
widgets marked `importantForAccessibility="no"` are invisible to it. That is exactly when you fall back to
`phone_screenshot` plus `phone_tap`.

## What is in this repository

```
plugin/        DSH plugin: 22 tools + a settings panel (npm package `dsh-phone-control`)
android/       Android app that runs the accessibility service and the bridge client
verification/  Node.js scripts that exercise the protocol, the settings API and the packaged tarball
```

## Requirements

- DeepSeek Harness `>= 0.2.0-rc.1`
- Node.js `>= 20`
- Android 11 (API 30) or newer
- The phone and the computer reachable from each other — same Wi-Fi, or a tunnel (see below)

## Quick start

1. **Install the plugin** into your DSH profile (see [Install](#install)).
2. **Restart DSH.** The settings panel appears under *Settings → Phone bridge*.
3. **Install the Android app** on the phone (APK from the releases page of this repository).
4. Open the app, enable **Accessibility service** (required for screenshots and taps).
5. In DSH's *Phone bridge* panel, read the **8-digit pairing code** and type it into the app — or skip the
   typing and scan the QR code shown in the same panel (*Scan pairing* in the app fills in code, port and
   host for you).
6. Tap **Connect**. The panel turns green and the app's notification says connected.

Pairing stores a 30-day token on the phone, so later launches reconnect by themselves.

## Install

### From npm

```bash
npm install dsh-phone-control --prefix ~/.dsh/profiles/desktop
```

Then add the bundle name to `~/.dsh/profiles/desktop/package.json`:

```json
{
  "dsh": {
    "profile": {
      "bundles": ["...your existing bundles...", "dsh-phone-control"]
    }
  }
}
```

Restart DSH afterwards.

### From a tarball

```bash
npm pack            # produces dsh-phone-control-0.1.2.tgz
npm install ./dsh-phone-control-0.1.2.tgz --prefix ~/.dsh/profiles/desktop
```

### From source

```bash
git clone <this repository>
npm install <repo>/plugin --prefix ~/.dsh/profiles/desktop
```

`plugin/` ships its own `node_modules`-free layout; the runtime dependencies are `ws` and `qrcode`.

## The settings panel

*Settings → Phone bridge* shows:

- connection dot (green connected / amber connecting / grey idle)
- the 8-digit pairing code with a countdown and a **New code** button
- **a QR code of the pairing payload** (`dsh-phone://pair?v=1&c=…&p=…&w=…`), re-rendered whenever the code or
  the advertised host changes — scan it from the app instead of typing
- the listening address and port
- how many devices are paired, how many sessions are open, how many failed attempts happened
- a **Disconnect** button
- a port field (`0` = pick a free port automatically) and a bind-address selector

The QR image is served by the plugin's own HTTP route (`GET /dsh-phone-control/qr`) on the DSH web port, so it
needs no internet access and no extra client-side dependency.

Changing the port rebinds the listener immediately and persists the choice to
`<profile>/phone-bridge.json`. The chosen port is written back on first start, so it stays stable across
restarts.

## Reaching the phone from outside your LAN

The phone connects out to the computer, so the computer must be reachable. Two options:

**Cloudflare Tunnel (quick)**

```bash
cloudflared tunnel --url http://127.0.0.1:8788     # prints a public https hostname
```

Set the app's *WebSocket address* to `wss://<that-hostname>/ws`.

**Named tunnel (stable)**

```bash
cloudflared tunnel create dsh-phone
cloudflared tunnel route dns dsh-phone dsh.example.com
cloudflared service install
```

Config:

```yaml
tunnel: dsh-phone
credentials-file: C:\Users\<you>\.cloudflared\<tunnel-id>.json
ingress:
  - hostname: dsh.example.com
    service: http://127.0.0.1:8788
  - service: http_status:404
```

`https://dsh.example.com/health` answering `dsh-phone-bridge ok` means the tunnel is up. Tailscale or any
other overlay network works just as well — the app only needs a TCP route to the port above.

## Tools

| Tool | What it does |
| --- | --- |
| `phone_status` | Listener state, connected device, pairing code, port, pending requests |
| `phone_pair_code` | Current pairing code + TTL; `refresh: true` issues a new one and clears the lockout |
| `phone_ui_dump` | Accessibility tree as JSON (`i/d/c/t/cd/id/b/f`, `maxDepth`, `maxNodes`, `includeInvisible`) |
| `phone_tap_text` | Click the widget matching text / content-description / view-id, with gesture fallback |
| `phone_set_text` | Write into a field, located by hint text or view-id |
| `phone_screenshot` | PNG of the current screen |
| `phone_tap` / `phone_long_press` / `phone_swipe` | Coordinate input, same pixel space as the screenshot |
| `phone_input` | Type into the focused field |
| `phone_key` | `back`, `home`, `recents`, `notifications` |
| `phone_open_app` / `phone_current_app` / `phone_list_apps` | Launch, identify, enumerate apps |
| `phone_fs_list` / `phone_fs_stat` | List a directory (default `/sdcard`) / stat a path |
| `phone_fs_read` / `phone_fs_write` | Read a file (UTF-8 or base64, paged with `offset`/`length`) / write text or base64 |
| `phone_fs_mkdir` / `phone_fs_rm` | Create a directory tree / delete a file or directory |
| `phone_disconnect` | Drop the current session; `forget: true` also revokes every issued token |
| `phone_notify` | Push a notification to the phone (`title` / `body` / `session`); `probe: true` reports the app-side permission and switches |

Typical loop:

```
phone_ui_dump                        → find the login button by text
phone_tap_text {text: "Sign in"}     → clicks it
phone_set_text {target: "Email", text: "..."}  → fills the field
phone_ui_dump                        → verify the screen changed
```

Two guards are on by default:

- While the phone's foreground app **is** the bridge itself, `phone_tap` / `phone_tap_text` / `phone_set_text` /
  `phone_swipe` / `phone_input` answer `SELF_PROTECTED`, so an agent cannot drive the app that is letting it in.
  The *Permissions & security* card has a switch (off by default) to lift that.
- File tools only reach the app's private directory until **all-files access** is granted; otherwise they answer
  `PERMISSION_DENIED`. The same card links straight to the system toggle.

## Session notifications

Two things are worth a notification when you are not looking at the screen: a turn that just finished, and a question that is waiting on you. The plugin watches every session's events and pushes both to the phone:

- `turn/end` with `reason.kind === 'completed'` → **"<session> finished"**, with the tail of the last answer.
- `tool/call` to `ask_user_question` → **"<session> is waiting for you"**, with the question text.

The label is the session title (falling back to the first eight characters of its id), so several sessions stay distinguishable — including titles set before the plugin loaded, which are read back from the session log once. If the phone is offline, or the user switched a category off, nothing is sent and nothing fails — the tool call returns `{sent: false, reason}` instead. The app has its own card for the system permission plus a master switch and one switch per category; ask for the state with `phone_notify { probe: true }`.

You can also push by hand: `phone_notify { title, body, session }`. The `phone-notify` skill has the "when is it worth interrupting the user" heuristics.
## How it works

```
phone  ── WebSocket ──►  computer (DSH plugin, port 8788 by default)
        binary frames     JSON commands / JSON results
```

- Framing: 19-byte header (`DSHP`, version, type, flags, 64-bit sequence, payload length), 8 MB cap.
- Handshake: `HELLO` → `CHALLENGE` → `AUTH` → `OK`. The session key is derived from an ephemeral ECDH
  exchange (prime256v1) combined with a PBKDF2-stretched pairing code (or a pre-shared secret) through HKDF;
  both sides prove possession with HMAC over a transcript. Steady-state traffic is AES-256-GCM with the
  sequence number in the nonce.
- Pairing issues a random 256-bit token; only its SHA-256 hash is stored (`<profile>/phone-bridge-token.json`).
  Every token ever issued stays valid **indefinitely** (the newest 16 are kept), so a phone that slept
  overnight, was force-stopped, or got reinstalled can still reconnect without a fresh pairing code.
- The pairing code expires after 5 minutes, rotates after every successful pairing, and 5 wrong attempts
  lock pairing for 5 minutes. A forced refresh lifts the lock.
- The HTTP settings API only answers same-origin or loopback requests.

## Security notes

- The bridge listens on `0.0.0.0:8788` by default. Exposing that port to the internet without a tunnel and
  without TLS is a bad idea; put it behind the tunnel or bind it to `127.0.0.1` when the app uses the tunnel.
- The accessibility service can read and click anything on screen, and the file tools can read and write
  anything the app can reach once all-files access is granted. Only pair devices you control, keep the
  *allow remote control of this app* switch off unless you need it, and remove the pairing (`Disconnect` +
  revoke the token) when you are done.
- Debug logging of tokens is off by default; set `DSH_PHONE_DEBUG=1` only while diagnosing.

## Troubleshooting

| Symptom | Meaning | Fix |
| --- | --- | --- |
| `NO_DEVICE` | No phone connected | Check the app's status, the port, and the tunnel |
| `TOKEN_INVALID` | Stored token rejected | Pair again with a fresh code |
| `BAD_CODE` | Wrong pairing code | Read the current code in the panel; it rotates after each success |
| `CODE_EXPIRED` | 5 minutes passed | Press **New code** |
| `LOCKED` | Too many wrong codes | Wait for the countdown, or press **New code** |
| `ACCESSIBILITY_OFF` | Service not enabled | Enable it in the app, and re-enable it after every app update |
| `NODE_NOT_FOUND` | No widget matched | Dump the tree, or fall back to a screenshot |
| `NOT_EDITABLE` | Matched node takes no text | Target the text field itself |
| `SELF_PROTECTED` | An input command hit the bridge app's own screen | Switch the app to another screen, or enable *allow remote control of this app* |
| `PERMISSION_DENIED` | File path outside the app sandbox without all-files access | Grant *all files access* in the app's *Permissions & security* card |
| Timeouts | Phone asleep or offline | Wake the phone; the app reconnects automatically |

## Building the Android app

```bash
cd android
gradle :app:assembleDebug      # requires JDK 21 and Android SDK 36
```

The APK lands in `android/app/build/outputs/apk/debug/app-debug.apk`.

Note: installing an update revokes the accessibility permission — Android does that for every app, so
re-enable it after each install.

## Tests

```bash
node verification/pair-token-test.mjs    # pairing, tokens, lockout, rotation
node verification/plugin-smoke.mjs       # tool registration and wiring
node verification/settings-smoke.mjs     # settings API, port handling
node verification/pack-install-smoke.mjs # packaged tarball installs and boots
```

## License

MIT — see [LICENSE](LICENSE).
