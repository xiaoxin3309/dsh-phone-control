package com.dsh.phonebridge

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.net.URLDecoder
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var settingsButton: TextView

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0f
    private var startY = 0f
    private var dragging = false
    private var baseBrightness = -1f
    private var keyboardShown = false
    private var loadedUrl: String? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var fileChooserAllowMultiple = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            applyWindowPreferences()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = findViewById<View>(R.id.root)
        installKeyboardInsets(root)
        WindowInsetsControllerCompat(window, root).isAppearanceLightStatusBars = true

        webView = findViewById(R.id.webView)
        settingsButton = findViewById(R.id.settingsButton)
        baseBrightness = window.attributes.screenBrightness

        WebView.setWebContentsDebuggingEnabled(true)
        @SuppressLint("SetJavaScriptEnabled")
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (isIgnoredResource(request?.url?.toString())) return
                if (request?.isForMainFrame == true) {
                    toast("页面加载失败: ${error?.description}")
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                if (isIgnoredResource(request?.url?.toString())) return
                if (request?.isForMainFrame == true) {
                    toast("页面加载失败: HTTP ${errorResponse?.statusCode}")
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (view != null) {
                    installDownloadHook(view)
                    installKeyboardHook(view)
                }
            }
        }

        webView.addJavascriptInterface(DownloadBridge(), "DshDownload")
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(url, userAgent, contentDisposition, mimeType)
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                if (fileChooserCallback != null) {
                    fileChooserCallback?.onReceiveValue(null)
                }
                fileChooserCallback = callback
                fileChooserAllowMultiple = fileChooserParams?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
                openFilePicker()
                return true
            }
        }

        settingsButton.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = view.x
                    startY = view.y
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (abs(dx) > 8f || abs(dy) > 8f) dragging = true
                    view.x = clamp(startX + dx, 0f, (view.parent as android.view.View).width - view.width.toFloat())
                    view.y = clamp(startY + dy, 0f, (view.parent as android.view.View).height - view.height.toFloat())
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    } else {
                        prefs().edit()
                            .putFloat(KEY_BUTTON_X, view.x)
                            .putFloat(KEY_BUTTON_Y, view.y)
                            .apply()
                    }
                    true
                }

                else -> false
            }
        }

        settingsButton.post { restoreButtonPosition() }
        loadDsh(force = true)
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            IntentFilter(PhoneBridgeService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        unregisterReceiver(stateReceiver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        applyWindowPreferences()
        loadDsh(force = false)
        maybeAutoConnect()
    }

    /**
     * 覆盖安装 / 手动打开 App 时自动把手机桥拉起来。
     *
     * BootReceiver 的 MY_PACKAGE_REPLACED 在部分机型上不可靠（v0.0.20 实测装完不自动连），
     * 所以主界面回到前台时补一次：只要勾过「保持登录」、有地址、有已保存 token，
     * 且服务当前不是 connected/connecting，就拉起前台服务。
     */
    private fun maybeAutoConnect() {
        val prefs = prefs()
        if (!prefs.getBoolean(KEY_KEEP_LOGIN, false)) return
        val host = prefs.getString(KEY_BRIDGE_HOST, "").orEmpty().trim()
        val wsUrl = prefs.getString(KEY_WS_URL, "").orEmpty().trim()
        if (host.isEmpty() && wsUrl.isEmpty()) return
        if (TokenStore.get(this).isEmpty()) return
        val state = PhoneBridgeService.lastState
        if (state == "connected" || state == "connecting") return
        val port = prefs.getString(KEY_BRIDGE_PORT, "").orEmpty().trim()
            .toIntOrNull()?.coerceIn(1, 65535) ?: 8788
        val intent = Intent(this, PhoneBridgeService::class.java)
            .setAction(PhoneBridgeService.ACTION_CONNECT)
            .putExtra(PhoneBridgeService.EXTRA_HOST, host)
            .putExtra(PhoneBridgeService.EXTRA_PORT, port)
            .putExtra(PhoneBridgeService.EXTRA_CODE, "")
            .putExtra(PhoneBridgeService.EXTRA_WS_URL, wsUrl)
            .putExtra(PhoneBridgeService.EXTRA_KEEP_LOGIN, true)
        runCatching { ContextCompat.startForegroundService(this, intent) }
            .onFailure { toast("自动连接启动失败：${it.message}") }
    }

    override fun onDestroy() {
        restoreBrightness()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cancelFileChooser()
        webView.destroy()
        super.onDestroy()
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            if (fileChooserAllowMultiple) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }
        try {
            startActivityForResult(intent, REQUEST_FILE_PICK)
        } catch (error: Exception) {
            toast("无法打开文件选择器：${error.message}")
            cancelFileChooser()
        }
    }

    private fun cancelFileChooser() {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        fileChooserAllowMultiple = false
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FILE_PICK) {
            val uris: Array<Uri>? = when {
                resultCode != RESULT_OK || data == null -> null
                data.clipData != null -> {
                    val count = data.clipData!!.itemCount
                    val list = ArrayList<Uri>(count)
                    for (i in 0 until count) list.add(data.clipData!!.getItemAt(i).uri)
                    list.toTypedArray()
                }
                else -> {
                    val uri = data.data ?: run { cancelFileChooser(); return }
                    arrayOf(uri)
                }
            }
            fileChooserCallback?.onReceiveValue(uris)
            fileChooserCallback = null
            fileChooserAllowMultiple = false
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    private fun loadDsh(force: Boolean) {
        val raw = prefs().getString(KEY_DSH_URL, "").orEmpty().trim()
        if (raw.isEmpty()) {
            webView.loadUrl("about:blank")
            loadedUrl = null
            return
        }
        val normalized = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
        if (force || loadedUrl != normalized) {
            loadedUrl = normalized
            webView.loadUrl(normalized)
        }
    }

    private fun applyWindowPreferences() {
        val prefs = prefs()
        val active = PhoneBridgeService.isConnected
        if (active && prefs.getBoolean(KEY_KEEP_AWAKE, true)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val attrs = window.attributes
        if (active && prefs.getBoolean(KEY_DIM_ON_CONTROL, false)) {
            val level = prefs.getInt(KEY_DIM_LEVEL, 5).coerceIn(1, 50) / 100f
            attrs.screenBrightness = level
        } else {
            attrs.screenBrightness = baseBrightness
        }
        window.attributes = attrs
    }

    private fun restoreBrightness() {
        val attrs = window.attributes
        attrs.screenBrightness = baseBrightness
        window.attributes = attrs
    }

    private fun restoreButtonPosition() {
        val x = prefs().getFloat(KEY_BUTTON_X, Float.NaN)
        val y = prefs().getFloat(KEY_BUTTON_Y, Float.NaN)
        val parent = settingsButton.parent as android.view.View
        if (x.isNaN() || y.isNaN()) {
            settingsButton.x = parent.width - settingsButton.width - 24f
            settingsButton.y = parent.height * 0.35f
        } else {
            settingsButton.x = clamp(x, 0f, parent.width - settingsButton.width.toFloat())
            settingsButton.y = clamp(y, 0f, parent.height - settingsButton.height.toFloat())
        }
    }

    private fun clamp(value: Float, min: Float, max: Float): Float = value.coerceIn(min, max.coerceAtLeast(min))

    private fun prefs() = getSharedPreferences(PhoneBridgeService.PREFS, MODE_PRIVATE)

    private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /**
     * 键盘（IME）弹出时把整页抬起来。
     * targetSdk 36 + edge-to-edge 之后系统不再帮我们 resize，只能自己读 [WindowInsetsCompat.Type.ime]：
     * 底部内边距 = max(导航栏, 键盘)，WebView 随之变矮 → 页面 viewport 变小 → 输入框跟着上移。
     */
    private fun installKeyboardInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            applyRootInsets(view, insets)
            insets
        }
    }

    private fun applyRootInsets(root: View, insets: WindowInsetsCompat) {
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        // 键盘在：让出键盘高度；键盘不在：手势导航下不再额外留白，三键导航才让出按键栏
        val bottom = if (ime.bottom > 0) maxOf(bars.bottom, ime.bottom) else idleBottomInset(bars.bottom)
        val shown = ime.bottom > 0
        if (shown != keyboardShown) {
            keyboardShown = shown
            if (::webView.isInitialized) {
                webView.evaluateJavascript("window.__dshKeyboardOpen=$shown;", null)
            }
        }
        if (root.paddingLeft != bars.left || root.paddingTop != bars.top ||
            root.paddingRight != bars.right || root.paddingBottom != bottom
        ) {
            root.setPadding(bars.left, bars.top, bars.right, bottom)
        }
    }

    /** 键盘收起时底部要留多少：手势导航直接铺到底，否则整页会被导航栏顶高一截。 */
    private fun idleBottomInset(barsBottom: Int): Int {
        if (barsBottom <= 0) return 0
        val gesture = try {
            Settings.Secure.getInt(contentResolver, "navigation_mode", -1) == 2
        } catch (error: Exception) {
            null
        }
        return when (gesture) {
            true -> 0
            false -> barsBottom
            null -> if (barsBottom <= dp(30)) 0 else barsBottom   // 读不到就按高度猜
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun handleDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        when {
            url.startsWith("data:", ignoreCase = true) -> downloadDataUrl(url, contentDisposition, mimeType)
            url.startsWith("blob:", ignoreCase = true) -> downloadBlob(url, contentDisposition, mimeType)
            url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) -> {
                enqueueHttpDownload(url, userAgent, contentDisposition, mimeType)
            }
            else -> toast("暂不支持该下载地址：${url.take(40)}")
        }
    }

    private fun enqueueHttpDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        try {
            val name = filenameFor(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setDescription("正在下载")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            if (!mimeType.isNullOrBlank()) request.setMimeType(mimeType)
            if (!userAgent.isNullOrBlank()) request.addRequestHeader("User-Agent", userAgent)
            request.addRequestHeader("Cache-Control", "no-cache, no-store")
            request.addRequestHeader("Pragma", "no-cache")
            val cookie = CookieManager.getInstance().getCookie(url)
            if (!cookie.isNullOrBlank()) request.addRequestHeader("Cookie", cookie)
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            toast("开始下载：$name")
        } catch (error: Exception) {
            toast("下载失败：${error.message ?: "未知错误"}")
        }
    }

    private fun downloadBlob(url: String, contentDisposition: String?, mimeType: String?) {
        val name = filenameFor(url, contentDisposition, mimeType)
        val js = """
            (function() {
              var url = ${org.json.JSONObject.quote(url)};
              var name = ${org.json.JSONObject.quote(name)};
              var last = window.__dshLastDownload;
              if (last && last.url === url && last.name) name = last.name;
              function saveWithMeta(meta, blob) {
                if (meta && meta.name) name = meta.name;
                var finalMime = (meta && meta.mime) || (blob && blob.type) || 'application/octet-stream';
                var reader = new FileReader();
                reader.onloadend = function() {
                  var result = String(reader.result || '');
                  var comma = result.indexOf(',');
                  DshDownload.saveBase64(name, finalMime, comma >= 0 ? result.slice(comma + 1) : '');
                };
                reader.onerror = function() { DshDownload.failed('读取文件失败'); };
                reader.readAsDataURL(blob);
              }
              var cached = window.__dshBlobMap && window.__dshBlobMap[url];
              if (cached) { saveWithMeta(window.__dshBlobMeta && window.__dshBlobMeta[url], cached); return; }
              var xhr = new XMLHttpRequest();
              xhr.open('GET', url, true);
              xhr.responseType = 'blob';
              xhr.onload = function() {
                if (xhr.status !== 200 && xhr.status !== 0) {
                  DshDownload.failed('HTTP ' + xhr.status);
                  return;
                }
                var cd = '';
                try { cd = xhr.getResponseHeader('Content-Disposition') || ''; } catch (e) {}
                var meta = {
                  name: (function() {
                    var m = cd.match(/filename\*?=(?:UTF-8'')?"?([^";]+)"?/i);
                    return m ? decodeURIComponent(m[1]) : '';
                  })(),
                  mime: xhr.responseType === 'blob' && xhr.response && xhr.response.type ? xhr.response.type : ''
                };
                saveWithMeta(meta, xhr.response);
              };
              xhr.onerror = function() { DshDownload.failed('获取文件失败'); };
              xhr.send();
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
        toast("正在获取文件：$name")
    }

    private fun installKeyboardHook(view: WebView) {
        val js = """
            (function() {
              if (window.__dshKeyboardHook) return;
              window.__dshKeyboardHook = true;
              var focused = null;
              function reveal() {
                if (!window.__dshKeyboardOpen) return;
                var el = focused;
                if (!el || !el.isConnected) return;
                try {
                  var rect = el.getBoundingClientRect();
                  var vv = window.visualViewport;
                  var top = vv ? vv.offsetTop : 0;
                  var bottom = vv ? vv.offsetTop + vv.height : window.innerHeight;
                  if (rect.bottom > bottom - 12 || rect.top < top + 12) {
                    el.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'smooth' });
                  }
                } catch (e) {}
              }
              document.addEventListener('focusin', function(event) {
                var el = event.target;
                if (!el) return;
                var tag = el.tagName;
                if (tag !== 'INPUT' && tag !== 'TEXTAREA' && !el.isContentEditable) return;
                focused = el;
                setTimeout(reveal, 260);
                setTimeout(reveal, 620);
              }, true);
              if (window.visualViewport) {
                window.visualViewport.addEventListener('resize', function() {
                  if (focused && document.activeElement === focused) reveal();
                });
              }
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private fun installDownloadHook(view: WebView) {
        val js = """
            (function() {
              if (window.__dshDownloadHook) return;
              window.__dshDownloadHook = true;
              window.__dshBlobMap = window.__dshBlobMap || {};
              window.__dshBlobMeta = window.__dshBlobMeta || {};
              var origCreate = URL.createObjectURL;
              URL.createObjectURL = function(obj, meta) {
                var url = origCreate.call(URL, obj);
                try {
                  window.__dshBlobMap[url] = obj;
                  if (meta && (meta.name || meta.mime)) window.__dshBlobMeta[url] = { name: meta.name || '', mime: meta.mime || '' };
                } catch (e) {}
                return url;
              };
              var origRevoke = URL.revokeObjectURL;
              URL.revokeObjectURL = function(url) {
                // 页面（如 dsh-workspace 的下载按钮）常常在 click() 之后立刻 revoke，
                // 这里推迟释放，给拦截器留出读取 blob 的窗口。
                setTimeout(function() {
                  try {
                    delete window.__dshBlobMap[url];
                    if (window.__dshBlobMeta) delete window.__dshBlobMeta[url];
                    origRevoke.call(URL, url);
                  } catch (e) {}
                }, 60000);
              };
              var UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
              var EXT_RE = /([^\s/\\:*?"<>|]+\.(?:apk|zip|pdf|png|jpg|jpeg|webp|gif|txt|json|md|csv|xlsx|docx|pptx|mp4|mp3|bin|tar|gz|7z|rar|html|js|css|xml|yaml|yml|log|kt|java|py|sh|ps1|toml|ini|conf))/i;
              function usable(name) {
                var n = String(name || '').trim().replace(/\s+/g, ' ');
                if (!n || n.length > 120) return '';
                if (UUID_RE.test(n)) return '';                                  // blob: 地址末段的裸 UUID 不是文件名
                if (/^download(?:file)?(?:\.[a-z0-9]{1,8})?$/i.test(n)) return '';
                return n;
              }
              function baseName(name) {
                var n = usable(name);
                return n ? n.split('/').pop().split('\\').pop() : '';
              }
              function pickName(anchor) {
                var declared = baseName(anchor.getAttribute('download') || anchor.download || '');
                if (declared) return declared;
                var scope = anchor.parentElement;
                for (var i = 0; i < 4 && scope; i++) {
                  var match = (scope.innerText || '').trim().match(EXT_RE);
                  if (match) return match[1];
                  scope = scope.parentElement;
                }
                var label = baseName(anchor.getAttribute('title') || anchor.getAttribute('aria-label') || '');
                if (label) return label;
                var titleMatch = (document.title || '').trim().match(EXT_RE);
                if (titleMatch) return titleMatch[1];
                try {
                  var pathName = decodeURIComponent(location.pathname.split('/').pop() || '');
                  if (pathName && pathName.indexOf('.') >= 0) return pathName;
                } catch (e) {}
                return '';
              }
              function deliver(name, mime, blob) {
                if (!blob) { DshDownload.failed('没有取到文件内容'); return; }
                var finalMime = mime || blob.type || 'application/octet-stream';
                var reader = new FileReader();
                reader.onloadend = function() {
                  var result = String(reader.result || '');
                  var comma = result.indexOf(',');
                  DshDownload.saveBase64(name, finalMime, comma >= 0 ? result.slice(comma + 1) : '');
                };
                reader.onerror = function() { DshDownload.failed('读取文件失败'); };
                reader.readAsDataURL(blob);
              }
              function saveFromUrl(href, name) {
                var meta = window.__dshBlobMeta && window.__dshBlobMeta[href];
                var finalName = (meta && meta.name) || name || '';
                if (finalName) window.__dshLastDownload = { url: href, name: finalName };
                var blob = window.__dshBlobMap && window.__dshBlobMap[href];
                if (blob) {
                  deliver(finalName, (meta && meta.mime) || blob.type, blob);
                  return true;
                }
                // blob 没被 createObjectURL 钩子记下（或已被回收）：自己取一次
                try {
                  var xhr = new XMLHttpRequest();
                  xhr.open('GET', href, true);
                  xhr.responseType = 'blob';
                  xhr.onload = function() {
                    if (!xhr.response) { DshDownload.failed('HTTP ' + xhr.status); return; }
                    var mime = '';
                    try { mime = xhr.getResponseHeader('Content-Type') || ''; } catch (e) {}
                    deliver(finalName, (meta && meta.mime) || xhr.response.type || mime, xhr.response);
                  };
                  xhr.onerror = function() { DshDownload.failed('获取文件失败'); };
                  xhr.send();
                  return true;
                } catch (e) {
                  return false;
                }
              }
              function intercept(anchor, event) {
                var href = (anchor && anchor.href) || '';
                if (href.indexOf('blob:') !== 0 && href.indexOf('data:') !== 0) return false;
                var saved = saveFromUrl(href, pickName(anchor));
                if (saved && event) {
                  event.preventDefault();
                  event.stopImmediatePropagation();
                }
                return saved;
              }
              // 1) 真实的链接触发
              document.addEventListener('click', function(event) {
                var target = event.target;
                var anchor = target && target.closest ? target.closest('a') : null;
                if (anchor) intercept(anchor, event);
              }, true);
              // 2) 代码里 new 出来、没挂到文档上的游离 <a> 再 .click()（dsh-workspace 等插件就这么做），
              //    文档上的监听器收不到，只能改写原型方法。
              try {
                var origAnchorClick = HTMLAnchorElement.prototype.click;
                HTMLAnchorElement.prototype.click = function() {
                  try {
                    if (intercept(this, null)) return;   // 已交给 App 保存，阻止 WebView 默认下载
                  } catch (e) {}
                  return origAnchorClick.apply(this, arguments);
                };
              } catch (e) {}
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private fun downloadDataUrl(url: String, contentDisposition: String?, mimeType: String?) {
        try {
            val comma = url.indexOf(',')
            if (comma < 0) throw IllegalArgumentException("无效的 data URL")
            val header = url.substring(5, comma)
            val payload = url.substring(comma + 1)
            val mime = header.substringBefore(';').ifBlank { mimeType ?: "application/octet-stream" }
            val bytes = if (header.contains(";base64", ignoreCase = true)) {
                Base64.decode(payload, Base64.DEFAULT)
            } else {
                URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
            }
            saveToDownloads(filenameFor(url, contentDisposition, mime), mime, bytes)
        } catch (error: Exception) {
            toast("下载失败：${error.message ?: "无效数据"}")
        }
    }

    private fun saveToDownloads(name: String, mimeType: String, bytes: ByteArray) {
        val finalName = sanitizeFilename(name, mimeType)
        val generic = mimeType.isBlank() || mimeType.lowercase().startsWith("application/octet-stream") ||
            (mimeType == "text/plain" && finalName.contains('.'))
        val finalMime = if (generic) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(finalName.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
        } else {
            mimeType
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
            put(MediaStore.MediaColumns.MIME_TYPE, finalMime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法创建下载文件")
        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IllegalStateException("无法写入下载文件")
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        runOnUiThread { toast("已保存到下载：$finalName") }
    }

    private fun sanitizeFilename(name: String, fallbackMime: String? = null): String {
        var n = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(120)
        val stem = n.substringBeforeLast('.', n)
        // 裸 UUID（blob: 地址末段，WebView 默认下载时拿到的就是它）或 downloadfile.* 都不是真文件名
        if (n.isEmpty() || n.startsWith("downloadfile.") || UUID_RE.matches(stem)) {
            val ext = n.substringAfterLast('.', "").takeIf { it.length in 1..8 }
                ?: knownExtension(fallbackMime) ?: "bin"
            n = "dsh-${System.currentTimeMillis()}.$ext"
        }
        // 无扩展名时按 MIME 补（只在 MIME 本身够具体时才补，避免给 .md 之类硬塞 .bin）
        if (!n.contains('.')) {
            knownExtension(fallbackMime)?.let { n += ".$it" }
        }
        return n
    }

    /** MIME 太泛（空 / octet-stream / text-plain）时返回 null，免得瞎造扩展名。 */
    private fun knownExtension(mime: String?): String? {
        if (mime.isNullOrBlank()) return null
        val m = mime.lowercase().substringBefore(';').trim()
        if (m.isEmpty() || m == "application/octet-stream" || m == "text/plain") return null
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(m)
    }

    private fun filenameFor(url: String, contentDisposition: String?, mimeType: String?): String {
        var name = URLUtil.guessFileName(url, contentDisposition, mimeType)
        if (name.isBlank() || name.startsWith("downloadfile.")) {
            name = pageFilename() ?: name
        }
        return sanitizeFilename(name, mimeType)
    }

    private fun pageFilename(): String? {
        val pattern = Regex("([^\\s/\\\\:*?\"<>|]+\\.(?:apk|zip|pdf|png|jpg|jpeg|webp|gif|txt|json|md|csv|xlsx|docx|pptx|mp4|mp3|bin|tar|gz|7z|rar|html|js|css|xml|yaml|yml))", RegexOption.IGNORE_CASE)
        val candidates = listOf(webView.title.orEmpty(), webView.url.orEmpty())
        for (candidate in candidates) {
            pattern.find(candidate)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }

    inner class DownloadBridge {
        @JavascriptInterface
        fun saveBase64(name: String?, mimeType: String?, base64: String?) {
            if (base64.isNullOrEmpty()) {
                failed("文件内容为空")
                return
            }
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                saveToDownloads(name.orEmpty(), mimeType ?: "application/octet-stream", bytes)
            } catch (error: Exception) {
                failed(error.message ?: "保存失败")
            }
        }

        @JavascriptInterface
        fun failed(message: String?) {
            runOnUiThread { toast("下载失败：${message ?: "未知错误"}") }
        }
    }

    private fun isIgnoredResource(url: String?): Boolean {
        if (url == null) return false
        return url.contains("127.0.0.1:18787") || url.contains("/__bili/")
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQUEST_FILE_PICK = 1001
        private const val KEY_DSH_URL = "dsh_url"
        private const val KEY_KEEP_AWAKE = "keep_awake"
        private const val KEY_DIM_ON_CONTROL = "dim_on_control"
        private const val KEY_DIM_LEVEL = "dim_level"
        private const val KEY_BUTTON_X = "settings_button_x"
        private const val KEY_BUTTON_Y = "settings_button_y"
        private const val KEY_BRIDGE_HOST = "bridge_host"
        private const val KEY_BRIDGE_PORT = "bridge_port"
        private const val KEY_WS_URL = "bridge_ws_url"
        private const val KEY_KEEP_LOGIN = "keep_login"
    }
}
