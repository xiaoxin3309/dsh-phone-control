// dsh-phone-control — 浏览器侧：设置里的「手机桥」页面。
//
// 这是一个手写的经典脚本（与 dsh-proxy-switcher 同样的形态），因为本包没有构建步骤：
// 只用 window.__ModuleLoader__ 注册一个工厂，工厂体在真正用到时才执行。
// 编辑时必须遵守：
//   - 不写 JSX、不写 import/export（工厂返回的就是模块导出）；
//   - 只能 require 平台种子模块（react、@deepseek-ai/dsh-client-ui-primitives 等）；
//   - id 必须与包名一致。
//
// 页面与宿主脚本之间只走本插件自己的 HTTP 路由（lib/routes.js），所以这里用 fetch
// 就够了，不需要 connection / locale 等服务。

window.__ModuleLoader__.load({
  id: "dsh-phone-control",
  factory: (require) => {
    let React = null;
    try {
      React = require("react");
    } catch (error) {
      React = null;
    }

    /** 必须与 lib/routes.js 的 BASE_PATH 一致（两半不共享模块）。 */
    const BASE = "/dsh-phone-control";

    /** 配套 App 的下载页：APK 不发 npm，只挂在 GitHub Release 上。 */
    const RELEASES_URL = "https://github.com/xiaoxin3309/dsh-phone-control/releases";

    /** 设置页里的位次：放在内置分区之后。 */
    const SECTION_ID = "phone-bridge";
    const SECTION_ORDER = 70;

    const name = "dsh-phone-control";
    const inject = ["slots"];

    const h = React ? React.createElement : null;

    // ---------------------------------------------------------------- styling
    const STYLE_ID = "dsh-phone-control-styles";
    const CSS = `
.dshpb-root { display: flex; flex-direction: column; gap: 14px; }
.dshpb-card {
  border: 1px solid var(--dsw-alias-border-secondary, rgba(0,0,0,.12));
  border-radius: 12px; padding: 14px 16px;
  background: var(--dsw-alias-bg-elevated, rgba(127,127,127,.04));
  display: flex; flex-direction: column; gap: 10px;
}
.dshpb-card-title { font-weight: 600; font-size: 14px; display: flex; align-items: center; gap: 8px; }
.dshpb-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; font-size: 13px; }
.dshpb-label { min-width: 86px; color: var(--dsw-alias-text-tertiary, rgba(0,0,0,.55)); font-size: 12px; }
.dshpb-code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 24px;
  letter-spacing: 4px; font-weight: 700;
  color: var(--dsw-alias-text-primary, inherit);
}
.dshpb-dim { color: var(--dsw-alias-text-tertiary, rgba(0,0,0,.55)); font-size: 12px; }
.dshpb-dot { width: 9px; height: 9px; border-radius: 50%; display: inline-block; }
.dshpb-btn {
  border: 1px solid var(--dsw-alias-border-secondary, rgba(0,0,0,.16));
  background: transparent; color: inherit; border-radius: 8px;
  padding: 6px 12px; font-size: 13px; cursor: pointer;
  text-decoration: none; display: inline-block;
}
.dshpb-btn:hover { background: var(--dsw-alias-bg-hover, rgba(127,127,127,.10)); }
.dshpb-btn:disabled { opacity: .5; cursor: default; }
.dshpb-btn-primary {
  border-color: transparent;
  background: var(--dsw-alias-accent, #2f6bff); color: #fff;
}
.dshpb-btn-primary:hover { filter: brightness(1.06); background: var(--dsw-alias-accent, #2f6bff); }
.dshpb-input {
  border: 1px solid var(--dsw-alias-border-secondary, rgba(0,0,0,.16));
  background: var(--dsw-alias-bg-base, transparent); color: inherit;
  border-radius: 8px; padding: 6px 10px; font-size: 13px; min-width: 120px;
}
.dshpb-banner { border-radius: 8px; padding: 8px 12px; font-size: 13px; }
.dshpb-ok { background: rgba(47,168,95,.12); color: var(--dsw-alias-text-primary, inherit); }
.dshpb-err { background: rgba(220,60,60,.14); color: var(--dsw-alias-text-primary, inherit); }
.dshpb-qr { width: 200px; height: 200px; border-radius: 10px; background: #fff; padding: 8px; }
`;

    function ensureStyle() {
      if (typeof document === "undefined") return;
      if (document.getElementById(STYLE_ID) !== null) return;
      const style = document.createElement("style");
      style.id = STYLE_ID;
      style.textContent = CSS;
      document.head.appendChild(style);
    }

    // ------------------------------------------------------------ data access
    async function call(path, body) {
      const init = body === undefined
        ? { method: "GET", credentials: "same-origin" }
        : {
            method: "POST",
            credentials: "same-origin",
            headers: { "content-type": "application/json" },
            body: JSON.stringify(body),
          };
      const response = await fetch(BASE + path, init);
      const payload = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(payload?.error ?? `HTTP ${response.status}`);
      return payload;
    }

    function fmtRemain(ms) {
      if (typeof ms !== "number" || !Number.isFinite(ms)) return "—";
      const total = Math.max(0, Math.round(ms / 1000));
      const m = Math.floor(total / 60);
      const s = total % 60;
      return `${m}:${String(s).padStart(2, "0")}`;
    }

    // ---------------------------------------------------------------- section
    function PhoneBridgeSection() {
      const [state, setState] = React.useState(null);
      const [error, setError] = React.useState(null);
      const [notice, setNotice] = React.useState(null);
      const [busy, setBusy] = React.useState(null);
      const [port, setPort] = React.useState("");
      const [bindHost, setBindHost] = React.useState("0.0.0.0");
      const [qrAddr, setQrAddr] = React.useState("");
      const [now, setNow] = React.useState(Date.now());

      React.useEffect(() => {
        try {
          setQrAddr(window.localStorage.getItem("dshpb.qr.address") ?? "");
        } catch (error) {
          setQrAddr("");
        }
      }, []);

      const saveQrAddr = (value) => {
        setQrAddr(value);
        try {
          window.localStorage.setItem("dshpb.qr.address", value);
        } catch (error) {
          /* 隐私模式下写不了 localStorage，不影响这次显示 */
        }
      };

      React.useEffect(() => {
        ensureStyle();
      }, []);

      React.useEffect(() => {
        let alive = true;
        let rotating = false;
        const tick = async () => {
          try {
            let next = await call("/state");
            if (!alive) return;
            // 配对码 5 分钟一换：宁可面板上永远显示一个当下的码，
            // 也不要让用户照着过期码去配、白吃一次 BAD_CODE / CODE_EXPIRED。
            const expiresAt = Number(next?.status?.codeExpiresAt ?? 0);
            if (expiresAt > 0 && expiresAt <= Date.now() && !rotating) {
              rotating = true;
              try {
                const fresh = await call("/refresh", {});
                if (fresh !== undefined) next = fresh;
              } catch (cause) {
                /* 换码失败就先把旧状态画出来，下一轮再试 */
              } finally {
                rotating = false;
              }
            }
            if (!alive) return;
            setState(next);
            setError(null);
          } catch (cause) {
            if (!alive) return;
            setError(String(cause?.message ?? cause));
          }
        };
        void tick();
        const timer = setInterval(() => void tick(), 2000);
        const clock = setInterval(() => setNow(Date.now()), 1000);
        return () => {
          alive = false;
          clearInterval(timer);
          clearInterval(clock);
        };
      }, []);

      React.useEffect(() => {
        if (state === null) return;
        setPort(String(state.settings?.requestedPort ?? state.settings?.port ?? 0));
        setBindHost(String(state.settings?.bindHost ?? "0.0.0.0"));
      }, [state?.settings?.port, state?.settings?.bindHost]);

      const status = state?.status ?? null;
      const connected = status?.connected === true;
      const dotColor = connected
        ? "#2FA85F"
        : status?.listening === true
          ? "#D08A22"
          : "#9AA0A6";
      const remainMs = status === null ? null : status.codeExpiresAt - now;

      // 二维码里放的就是「手机连接电脑需要的最小信息」：配对码 + 端口（可选地址）。
      // 手机端扫完自动填好，再点一次连接即可；配对码换新时二维码必须跟着变，
      // 所以 src 里带上 code 当缓存钥匙。
      const qrCode = status?.pairingCode ?? "";
      const qrPayload = (() => {
        if (qrCode === "") return "";
        const parts = [`v=1`, `c=${qrCode}`, `p=${status?.port ?? 0}`];
        const addr = qrAddr.trim();
        if (addr !== "") {
          if (/^wss?:\/\//iu.test(addr)) parts.push(`w=${encodeURIComponent(addr)}`);
          else parts.push(`h=${encodeURIComponent(addr)}`);
        }
        return `dsh-phone://pair?${parts.join("&")}`;
      })();

      const run = async (key, action, okText) => {
        setBusy(key);
        setNotice(null);
        setError(null);
        try {
          const next = await action();
          if (next !== undefined) setState(next);
          if (okText !== undefined) setNotice({ kind: "ok", text: okText });
        } catch (cause) {
          setNotice({ kind: "err", text: String(cause?.message ?? cause) });
        } finally {
          setBusy(null);
        }
      };

      const refreshCode = () => run("refresh", () => call("/refresh", {}), "已换新配对码。");
      const disconnect = () => run("disconnect", () => call("/disconnect", {}), "已断开当前手机连接。");
      const saveSettings = () =>
        run(
          "save",
          () =>
            call("/settings", {
              port: Number(port === "" ? 0 : port),
              bindHost,
            }),
          "已保存，监听器已按新设置重启（手机 App 会自动重连）。",
        );

      return h(
        "div",
        { className: "dshpb-root" },
        // ---- 状态
        h(
          "div",
          { className: "dshpb-card" },
          h(
            "div",
            { className: "dshpb-card-title" },
            h("span", { className: "dshpb-dot", style: { background: dotColor } }),
            "手机桥",
            h(
              "span",
              { className: "dshpb-dim" },
              connected ? "手机已连接" : status?.listening ? "等待手机连接" : "未监听",
            ),
          ),
          h(
            "div",
            { className: "dshpb-row" },
            h("span", { className: "dshpb-label" }, "配对码"),
            h("span", { className: "dshpb-code" }, status?.pairingCode ?? "——————"),
            h(
              "span",
              { className: "dshpb-dim" },
              remainMs === null ? "" : remainMs > 0 ? `剩余 ${fmtRemain(remainMs)}` : "已过期",
            ),
            h(
              "button",
              {
                className: "dshpb-btn",
                disabled: busy !== null,
                onClick: refreshCode,
              },
              busy === "refresh" ? "刷新中…" : "换一个",
            ),
          ),
          h(
            "div",
            { className: "dshpb-dim" },
            "在手机 App 的「配对码」里填这 8 位数字；配对成功后服务端会自动换码，App 里留旧码会报错（App 已在连接成功时自动清空）。",
          ),
          h(
            "div",
            { className: "dshpb-row" },
            h("span", { className: "dshpb-label" }, "监听"),
            h("span", null, `${status?.bindHost ?? "?"}:${status?.port ?? "?"}`),
            h(
              "span",
              { className: "dshpb-dim" },
              `已配对 ${status?.paired === true ? "是" : "否"} · 会话 ${status?.sessions ?? 0} · 失败 ${status?.failedAttempts ?? 0}`,
            ),
            h(
              "button",
              {
                className: "dshpb-btn",
                disabled: busy !== null || !connected,
                onClick: disconnect,
              },
              busy === "disconnect" ? "断开中…" : "断开手机",
            ),
          ),
        ),
        // ---- 手机 App 下载（App 不发 npm，只能从 GitHub Release 拿）
        h(
          "div",
          { className: "dshpb-card" },
          h("div", { className: "dshpb-card-title" }, "手机 App"),
          h(
            "div",
            { className: "dshpb-dim" },
            "配套的 Android App 不在 npm 里，去 GitHub Releases 下载最新的 dsh-phonebridge-v*.apk 装到手机上。覆盖安装会撤销无障碍授权，装完回 App 设置页重开一次；已经装过旧版的可以直接覆盖升级，配对凭据不会丢。",
          ),
          h(
            "div",
            { className: "dshpb-row" },
            h(
              "a",
              {
                className: "dshpb-btn",
                href: RELEASES_URL,
                target: "_blank",
                rel: "noopener noreferrer",
              },
              "打开 GitHub Releases 下载 APK",
            ),
            h("span", { className: "dshpb-dim" }, "github.com/xiaoxin3309/dsh-phone-control/releases"),
          ),
        ),
        // ---- 扫码配对
        h(
          "div",
          { className: "dshpb-card" },
          h("div", { className: "dshpb-card-title" }, "扫码配对"),
          qrPayload === ""
            ? h("div", { className: "dshpb-dim" }, "配对码还没生成，稍等一秒。")
            : h(
                "div",
                { className: "dshpb-row" },
                h("img", {
                  className: "dshpb-qr",
                  alt: "配对二维码",
                  width: 200,
                  height: 200,
                  src: `${BASE}/qr?s=200&d=${encodeURIComponent(qrPayload)}`,
                }),
                h(
                  "div",
                  { className: "dshpb-dim", style: { maxWidth: "320px" } },
                  "手机 App 里点「扫码配对」，对着这块图扫一下：自动填好配对码",
                  qrAddr.trim() === "" ? "和端口" : "、端口和地址",
                  "，再点「立即连接」即可。配对码每 5 分钟换新，图会跟着自动变。",
                ),
              ),
          h(
            "div",
            { className: "dshpb-row" },
            h("span", { className: "dshpb-label" }, "对外地址"),
            h("input", {
              className: "dshpb-input",
              value: qrAddr,
              placeholder: "可选，如 wss://你的域名/ws",
              onChange: (event) => saveQrAddr(event.target.value),
            }),
            h("span", { className: "dshpb-dim" }, "填了会一并写进二维码，手机扫码就不用再手填"),
          ),
        ),
        // ---- 监听设置
        h(
          "div",
          { className: "dshpb-card" },
          h("div", { className: "dshpb-card-title" }, "监听设置"),
          h(
            "div",
            { className: "dshpb-row" },
            h("span", { className: "dshpb-label" }, "端口"),
            h("input", {
              className: "dshpb-input",
              value: port,
              inputMode: "numeric",
              onChange: (event) => setPort(event.target.value.replace(/[^0-9]/gu, "")),
            }),
            h("span", { className: "dshpb-dim" }, "填 0 = 自动挑一个空闲端口（8000–9999）"),
          ),
          h(
            "div",
            { className: "dshpb-row" },
            h("span", { className: "dshpb-label" }, "绑定地址"),
            h(
              "select",
              {
                className: "dshpb-input",
                value: bindHost,
                onChange: (event) => setBindHost(event.target.value),
              },
              h("option", { value: "0.0.0.0" }, "0.0.0.0（局域网 / 隧道可连）"),
              h("option", { value: "127.0.0.1" }, "127.0.0.1（仅本机）"),
            ),
          ),
          h(
            "div",
            { className: "dshpb-row" },
            h(
              "button",
              {
                className: "dshpb-btn dshpb-btn-primary",
                disabled: busy !== null,
                onClick: saveSettings,
              },
              busy === "save" ? "保存中…" : "保存并重启监听",
            ),
            h(
              "span",
              { className: "dshpb-dim" },
              "改端口后记得把 cloudflared / 路由器转发的目标端口一起改掉。",
            ),
          ),
        ),
        notice !== null
          ? h(
              "div",
              { className: `dshpb-banner ${notice.kind === "ok" ? "dshpb-ok" : "dshpb-err"}` },
              notice.text,
            )
          : null,
        error !== null ? h("div", { className: "dshpb-banner dshpb-err" }, error) : null,
      );
    }

    /**
     * 注册设置页分区。
     * @param ctx - 客户端插件上下文。
     */
    function apply(ctx) {
      if (h === null) {
        ctx.logger?.warn?.("dsh-phone-control: react 不可用，手机桥设置页面已禁用");
        return;
      }
      ctx.slots.inject("settings.section", () => {
        try {
          return ctx.slots.register(
            {
              name: "settings.section",
              id: SECTION_ID,
              order: SECTION_ORDER,
              label: () => "手机桥",
              inject: () => ({}),
            },
            PhoneBridgeSection,
          );
        } catch (error) {
          ctx.logger?.warn?.("dsh-phone-control: 无法挂载手机桥设置页面");
          ctx.logger?.warn?.(error);
          return () => {};
        }
      });
    }

    return { apply, inject, name };
  },
});
