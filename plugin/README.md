# dsh-phone-control

A DeepSeek Harness plugin that drives a real Android phone: read the screen as an accessibility **node tree**,
tap by **text**, type into fields, screenshot only when it helps.

Docs: this file (English) · [简体中文](../README.zh-CN.md)

## Why

A 1080×2400 screenshot is 1–2 MB of PNG. The node tree for the same screen is 1–3 KB of JSON with labels,
view ids and clickable flags — usually a few hundred tokens instead of tens of thousands.

## Install

```bash
npm install dsh-phone-control --prefix ~/.dsh/profiles/desktop
```

Add `"dsh-phone-control"` to `dsh.profile.bundles` in that profile's `package.json`, then restart DSH.
The settings panel appears under *Settings → Phone bridge*.

## Pair in five steps

1. Install the Android app from the repository's releases page.
2. Enable its **Accessibility service**.
3. Open *Settings → Phone bridge* in DSH and read the 8-digit code.
4. Type the code into the app and tap **Connect**.
5. The panel turns green; the phone keeps a 30-day token and reconnects by itself afterwards.

## Tools

| Tool | What it does |
| --- | --- |
| `phone_ui_dump` | Accessibility tree as JSON (`maxDepth`, `maxNodes`, `includeInvisible`) |
| `phone_tap_text` | Click the widget matching text / content-description / view-id |
| `phone_set_text` | Write into a field located by hint or view-id |
| `phone_screenshot` | PNG of the current screen |
| `phone_tap` / `phone_long_press` / `phone_swipe` | Coordinate input |
| `phone_input` / `phone_key` | Type text; send `back` / `home` / `recents` / `notifications` |
| `phone_open_app` / `phone_current_app` / `phone_list_apps` | Launch, identify, enumerate apps |
| `phone_status` / `phone_pair_code` / `phone_disconnect` | Listener state, pairing code, drop session |

## Settings panel

Pairing code with countdown and a *New code* button, listening address/port (`0` = auto), bind address,
paired devices, open sessions, failed attempts, and a *Disconnect* button. Port changes rebind immediately and
persist to `<profile>/phone-bridge.json`.

## Protocol

Binary frames (`DSHP`, 19-byte header, 8 MB cap). Handshake: `HELLO` → `CHALLENGE` → `AUTH` → `OK`, session key
from ephemeral ECDH (prime256v1) + PBKDF2-stretched pairing code through HKDF, HMAC proofs both ways, then
AES-256-GCM with the sequence number in the nonce. Only the SHA-256 of the pairing token is stored.

## Node version

Requires Node.js 20 or newer. Runtime dependency: `ws`.

## License

MIT
