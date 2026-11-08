package com.dsh.phonebridge

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

class PhoneBridgeService : Service(), BridgeClient.Listener {

    private val main = Handler(Looper.getMainLooper())
    private var client: BridgeClient? = null
    private var keepLogin = true
    private var wakeLock: PowerManager.WakeLock? = null
    private var state = "idle"
    private var manualStop = false
    private var retryScheduled = false
    private var retryAttempt = 0
    private var retryRunnable: Runnable? = null
    private var fatalStop = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    /** 悬浮 HUD（默认关，见 HudOverlay）；截图 / 读控件树前会临时摘窗。 */
    private var hud: HudOverlay? = null
    private var lastHost = ""
    private var lastPort = 8788
    private var lastCode = ""
    private var lastWsUrl = ""
    private var lastToken = ""
    /** 上一次握手用的是配对码（true）还是已保存 token（false）。 */
    private var lastPreferCode = false
    /** 认证类失败后只翻转一次凭据，防止码和 token 互相踢成死循环。 */
    private var credentialFlipped = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        registerNetworkCallback()
        hud = HudOverlay(this) { DshAccessibilityService.instance?.foreground() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            disconnect("用户断开")
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CONNECT) {
            val host = intent.getStringExtra(EXTRA_HOST)?.trim().orEmpty()
            val port = intent.getIntExtra(EXTRA_PORT, 8788)
            val code = intent.getStringExtra(EXTRA_CODE)?.trim().orEmpty()
            val token = intent.getStringExtra(EXTRA_TOKEN)?.trim().orEmpty()
            val keepLogin = intent.getBooleanExtra(EXTRA_KEEP_LOGIN, true)
            val wsUrl = intent.getStringExtra(EXTRA_WS_URL)?.trim().orEmpty()
            if (host.isEmpty() && wsUrl.isEmpty()) {
                broadcast("disconnected", "没有填写电脑地址或 WebSocket 地址")
                stopSelf()
                return START_NOT_STICKY
            }
            startForegroundNow(if (wsUrl.isNotEmpty()) "正在连接 $wsUrl" else "正在连接 $host:$port")
            manualStop = false
            retryAttempt = 0
            lastHost = host
            lastPort = port
            lastCode = code
            lastWsUrl = wsUrl
            credentialFlipped = false
            connect(host, port, code, token, keepLogin, wsUrl, preferCode = code.isNotEmpty())
            return START_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        manualStop = true
        unregisterNetworkCallback()
        main.removeCallbacksAndMessages(null)
        retryScheduled = false
        retryRunnable = null
        isConnected = false
        client?.stop()
        client = null
        releaseWake()
        // 服务没了，HUD 也不能留在屏幕上。
        hud?.destroy()
        hud = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun connect(
        host: String,
        port: Int,
        code: String,
        token: String,
        keepLogin: Boolean,
        wsUrl: String,
        preferCode: Boolean = false,
    ) {
        client?.stop()
        fatalStop = false
        this.keepLogin = keepLogin
        lastPreferCode = preferCode
        state = "connecting"
        val label = wsUrl.ifEmpty { "$host:$port" }
        broadcast("connecting", "正在连接 $label")
        updateNotification("正在连接 $label")
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "android"
        // token 一律保留并优先使用：「保持登录」只管「下次要不要自动连」，
        // 不该决定「还要不要留着凭据」。以前这里在 keepLogin=false 时直接清库，
        // 用户取消勾选再连一次，token 就没了，之后只能重新扫码配对。
        val effectiveToken = token.ifEmpty { lastToken.ifEmpty { TokenStore.get(this) } }
        val bridge = BridgeClient(
            host = host,
            port = port,
            code = code.ifEmpty { null },
            token = effectiveToken.ifEmpty { null },
            deviceId = deviceId,
            listener = this,
            wsUrl = wsUrl.ifEmpty { null },
            preferCode = preferCode,
        )
        client = bridge
        bridge.start()
    }

    override fun onState(state: String, detail: String) {
        this.state = state
        isConnected = state == "connected"
        // 记一份最近状态，设置页打开时用来立即回填（否则会一直显示上一次的标签）
        lastState = state
        lastDetail = detail
        if (state == "connected") {
            retryAttempt = 0
            credentialFlipped = false
            // 连上之后 token 一定在手：把上次用过的码丢掉。
            // 旧码留在 lastCode 里，下次电脑端换码就会拿它去撞 BAD_CODE。
            if (lastCode.isNotEmpty() && TokenStore.get(this).isNotEmpty()) lastCode = ""
            acquireWake()
            updateNotification("已连接：$detail")
        } else {
            releaseWake()
            updateNotification("未连接：$detail")
            if (state == "disconnected" || state == "error") scheduleRetry(detail)
        }
        broadcast(state, detail)
    }

    private fun scheduleRetry(detail: String) {
        if (manualStop || retryScheduled) return
        // 「保持登录」不再影响凭据，只影响自动重连：取消勾选 = 断开后不自己连回来。
        if (!keepLogin) return
        val fatal = listOf("TOKEN_INVALID", "BAD_CODE", "CODE_EXPIRED", "LOCKED", "没有配对码")
            .any { detail.contains(it) }
        if (fatal) {
            // 认证失败先别判死刑：手上还有另一份凭据就换它再试一次。
            // 老码撞 BAD_CODE → 改用 token；token 失效 → 清掉再改用码。
            // 只翻转一次，免得两份凭据互相踢成死循环。
            if (!credentialFlipped && !detail.contains("LOCKED")) {
                credentialFlipped = true
                if (detail.contains("TOKEN_INVALID")) {
                    lastToken = ""
                    TokenStore.clear(this)
                }
                retryScheduled = true
                val flip = !lastPreferCode
                val runnable = Runnable {
                    retryScheduled = false
                    retryRunnable = null
                    if (manualStop) return@Runnable
                    val token = lastToken.ifEmpty { TokenStore.get(this) }
                    if (flip && lastCode.isEmpty() && token.isEmpty()) {
                        fatalStop = true
                        onState("error", detail)
                        return@Runnable
                    }
                    connect(lastHost, lastPort, lastCode, token, keepLogin, lastWsUrl, preferCode = flip)
                }
                retryRunnable = runnable
                broadcast("connecting", "换个凭据重试（${if (flip) "配对码" else "已保存 token"}）")
                main.postDelayed(runnable, 300)
                return
            }
            fatalStop = true
            if (detail.contains("TOKEN_INVALID")) {
                lastToken = ""
                TokenStore.clear(this)
            }
            return
        }
        retryScheduled = true
        val delay = (1000L shl retryAttempt.coerceAtMost(4)).coerceAtMost(30_000L)
        retryAttempt += 1
        val label = lastWsUrl.ifEmpty { "$lastHost:$lastPort" }
        broadcast("connecting", "连接断开，${delay / 1000}s 后重试（$label）")
        val runnable = Runnable {
            retryScheduled = false
            retryRunnable = null
            if (manualStop) return@Runnable
            val token = lastToken.ifEmpty { TokenStore.get(this) }
            // 重连默认用 token：码是会换的，token 不会。
            connect(lastHost, lastPort, lastCode, token, keepLogin, lastWsUrl, preferCode = lastPreferCode)
        }
        retryRunnable = runnable
        main.postDelayed(runnable, delay)
    }

    // 网络恢复（飞行模式关闭、Wi-Fi/移动数据切回）时立刻重连，不必等指数退避。
    private fun registerNetworkCallback() {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post { onNetworkAvailable() }
            }
        }
        try {
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (_: Throwable) {
            networkCallback = null
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            manager.unregisterNetworkCallback(callback)
        } catch (_: Throwable) {
        }
    }

    private fun onNetworkAvailable() {
        if (manualStop || fatalStop || isConnected || state == "connecting") return
        if (lastWsUrl.isEmpty() && lastHost.isEmpty()) return
        retryRunnable?.let { main.removeCallbacks(it) }
        retryRunnable = null
        retryScheduled = false
        retryAttempt = 0
        broadcast("connecting", "网络已恢复，立即重连")
        val token = lastToken.ifEmpty { TokenStore.get(this) }
        connect(lastHost, lastPort, lastCode, token, keepLogin, lastWsUrl, preferCode = lastPreferCode && token.isEmpty())
    }

    override fun onToken(token: String) {
        lastToken = token
        // 永远把签发到的 token 落盘：它只是 App 私有目录里的一个凭据，
        // 「保持登录」只决定下次要不要自动连，不该决定「要不要保存」。
        // 否则用户拿码配对一次、我们又把码清空，重启后就会两头都空。
        TokenStore.save(this, token)
    }

    override fun onCommand(id: Long, command: String, args: JSONObject) {
        main.post { dispatch(id, command, args) }
    }

    private fun dispatch(id: Long, command: String, args: JSONObject) {
        val accessibility = DshAccessibilityService.instance
        // 默认禁止远程操作「手机桥自己的界面」：配对码、端口、权限开关都在那个页面上，
        // 一旦允许远程点它，等于把提权入口交给了远端。用户可在设置页显式打开。
        if (command in SELF_GUARDED && selfControlBlocked(accessibility)) {
            replyError(
                id,
                "SELF_PROTECTED",
                "已禁止远程操作手机桥自身的设置页（如需放开，请在本机「设置 → 权限与安全 → 允许远程操作本 App」里打开）",
            )
            return
        }
        when (command) {
            "screenshot" -> {
                if (accessibility == null) {
                    replyError(id, "ACCESSIBILITY_OFF", "无障碍服务未开启")
                    return
                }
                val overlay = hud
                // HUD 要在这一帧之前摘掉，所以整个截图被包进 beforeCapture 的帧回调里。
                val capture: () -> Unit = capture@{
                    accessibility.captureBitmap { bitmap ->
                        try {
                            if (bitmap == null) {
                                replyError(id, "SCREENSHOT_FAILED", "截图失败，可能遇到安全窗口或系统限频")
                            } else {
                                val out = ByteArrayOutputStream()
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                                val bytes = out.toByteArray()
                                val result = JSONObject()
                                    .put("imageB64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                                    .put("width", bitmap.width)
                                    .put("height", bitmap.height)
                                    .put("bytes", bytes.size)
                                    .put("capturedAt", System.currentTimeMillis())
                                reply(id, result)
                            }
                        } catch (error: Throwable) {
                            replyError(id, "SCREENSHOT_FAILED", error.message ?: "截图编码失败")
                        } finally {
                            bitmap?.recycle()
                            main.post { overlay?.afterCapture() }
                        }
                    }
                }
                if (overlay != null) overlay.beforeCapture(capture) else capture()
            }

            "tap", "long_press" -> {
                if (accessibility == null) {
                    replyError(id, "ACCESSIBILITY_OFF", "无障碍服务未开启")
                    return
                }
                val x = args.optDouble("x", Double.NaN).toFloat()
                val y = args.optDouble("y", Double.NaN).toFloat()
                if (x.isNaN() || y.isNaN()) {
                    replyError(id, "BAD_ARGS", "缺少 x/y")
                    return
                }
                val duration = args.optLong("durationMs", if (command == "long_press") 800L else 50L)
                val callback: (Boolean) -> Unit = { ok ->
                    if (ok) reply(id, JSONObject().put("ok", true)) else replyError(id, "GESTURE_FAILED", "手势被系统取消")
                }
                if (command == "tap") accessibility.tap(x, y, duration, callback)
                else accessibility.longPress(x, y, duration, callback)
            }

            "swipe" -> {
                if (accessibility == null) {
                    replyError(id, "ACCESSIBILITY_OFF", "无障碍服务未开启")
                    return
                }
                accessibility.swipe(
                    args.optDouble("x1", Double.NaN).toFloat(),
                    args.optDouble("y1", Double.NaN).toFloat(),
                    args.optDouble("x2", Double.NaN).toFloat(),
                    args.optDouble("y2", Double.NaN).toFloat(),
                    args.optLong("durationMs", 300L),
                ) { ok ->
                    if (ok) reply(id, JSONObject().put("ok", true)) else replyError(id, "GESTURE_FAILED", "滑动被系统取消")
                }
            }

            "key" -> {
                if (accessibility == null) {
                    replyError(id, "ACCESSIBILITY_OFF", "无障碍服务未开启")
                    return
                }
                val action = when (args.optString("key")) {
                    "back" -> AccessibilityService.GLOBAL_ACTION_BACK
                    "home" -> AccessibilityService.GLOBAL_ACTION_HOME
                    "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                    "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                    else -> -1
                }
                if (action < 0) {
                    replyError(id, "BAD_ARGS", "不支持的按键")
                } else if (accessibility.globalAction(action)) {
                    reply(id, JSONObject().put("ok", true))
                } else {
                    replyError(id, "KEY_FAILED", "系统拒绝执行全局按键")
                }
            }

            "input" -> {
                if (accessibility == null) {
                    replyError(id, "ACCESSIBILITY_OFF", "无障碍服务未开启")
                    return
                }
                accessibility.inputText(args.optString("text")) { ok ->
                    if (ok) reply(id, JSONObject().put("ok", true))
                    else replyError(id, "INPUT_UNSUPPORTED", "当前输入框不支持直接写入，请让用户手动输入")
                }
            }

            "open_app" -> {
                val packageName = args.optString("package")
                val launch = packageManager.getLaunchIntentForPackage(packageName)
                if (launch == null) {
                    replyError(id, "APP_NOT_FOUND", "找不到可启动的应用：$packageName")
                } else {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launch)
                    reply(id, JSONObject().put("ok", true).put("package", packageName))
                }
            }

            "current_app" -> {
                val packageName = accessibility?.currentPackage()
                reply(id, JSONObject().put("package", packageName ?: JSONObject.NULL))
            }

            "ui_dump" -> {
                if (accessibility == null) {
                    replyError(id, "ACCESSIBILITY_OFF", "无障碍服务未开启")
                    return
                }
                val overlay = hud
                val dump: () -> Unit = dump@{
                    val tree = accessibility.dumpUi(
                        maxDepth = args.optInt("maxDepth", 30).coerceIn(1, 60),
                        maxNodes = args.optInt("maxNodes", 400).coerceIn(20, 2000),
                        includeInvisible = args.optBoolean("includeInvisible", false),
                    )
                    tree.put("dumpedAt", System.currentTimeMillis())
                    reply(id, tree)
                    main.post { overlay?.afterCapture() }
                }
                // 正常情况下 HUD 是独立窗口、本来就进不了 rootInActiveWindow 的树，
                // 但这里仍然摘一次窗，免得个别机型的无障碍实现把 overlay 也算进去。
                if (overlay != null) overlay.beforeCapture(dump) else dump()
            }

            "tap_text" -> {
                if (accessibility == null) {
                    replyError(id, "ACCESSIBILITY_OFF", "无障碍服务未开启")
                    return
                }
                val text = args.optString("text").trim()
                if (text.isEmpty()) {
                    replyError(id, "BAD_ARGS", "缺少 text")
                    return
                }
                val result = accessibility.clickByText(
                    text,
                    args.optBoolean("exact", false),
                    args.optInt("index", 0),
                )
                if (result.optBoolean("ok")) reply(id, result)
                else replyError(id, result.optString("code", "NODE_NOT_FOUND"), result.optString("message", "找不到目标"))
            }

            "set_text" -> {
                if (accessibility == null) {
                    replyError(id, "ACCESSIBILITY_OFF", "无障碍服务未开启")
                    return
                }
                val result = accessibility.setTextByTarget(
                    args.optString("target").trim().ifEmpty { null },
                    args.optString("text"),
                )
                if (result.optBoolean("ok")) reply(id, result)
                else replyError(id, result.optString("code", "SET_TEXT_FAILED"), result.optString("message", "写入失败"))
            }

            "notify" -> {
                // 会话通知：电脑端（插件自动触发器或 phone_notify 工具）发来一条，
                // 要不要真弹由本机三个开关 + 系统通知权限决定，结果回给电脑端。
                val kind = args.optString("kind", NotifyCenter.KIND_MANUAL)
                val reason = NotifyCenter.show(
                    this,
                    kind,
                    args.optString("title"),
                    args.optString("body"),
                    args.optString("session"),
                )
                reply(
                    id,
                    JSONObject()
                        .put("shown", reason == "shown")
                        .put("reason", reason),
                )
            }

            "notify_state" -> {
                reply(
                    id,
                    JSONObject()
                        .put("permission", NotifyCenter.permitted(this))
                        .put("enabled", NotifyCenter.enabled(this))
                        .put("complete", NotifyCenter.kindEnabled(this, NotifyCenter.KIND_COMPLETE))
                        .put("question", NotifyCenter.kindEnabled(this, NotifyCenter.KIND_QUESTION))
                        .put("manual", NotifyCenter.kindEnabled(this, NotifyCenter.KIND_MANUAL)),
                )
            }

            "hud_push" -> {
                val overlay = hud
                if (overlay == null) {
                    replyError(id, "NO_SERVICE", "悬浮 HUD 未初始化")
                    return
                }
                val incoming = ArrayList<String>()
                val array = args.optJSONArray("lines")
                if (array != null) {
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val text = item.optString("text").trim()
                        if (text.isEmpty()) continue
                        val prefix = when (item.optString("kind")) {
                            "done" -> "✓ "
                            "think" -> "… "
                            else -> "▸ "
                        }
                        incoming.add(prefix + text)
                    }
                }
                reply(id, overlay.push(incoming, args.optLong("ttlMs", HudOverlay.IDLE_MS)))
            }

            "hud_state" -> {
                val overlay = hud
                reply(
                    id,
                    overlay?.state() ?: JSONObject()
                        .put("enabled", false)
                        .put("permission", false)
                        .put("visible", false)
                        .put("lines", JSONArray()),
                )
            }

            "hud_clear" -> {
                hud?.clear()
                reply(id, JSONObject().put("ok", true))
            }

            "list_apps" -> {
                val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                    .asSequence()
                    .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
                    .map { info ->
                        JSONObject()
                            .put("package", info.packageName)
                            .put("label", info.loadLabel(packageManager).toString())
                    }
                    .sortedBy { it.optString("label") }
                    .toList()
                reply(id, JSONObject().put("apps", org.json.JSONArray(apps)))
            }

            "fs_list" -> {
                val path = args.optString("path").ifEmpty { "/sdcard" }
                fsDenied(path)?.let { replyError(id, "PERMISSION_DENIED", it); return }
                val dir = File(path)
                if (!dir.exists()) {
                    replyError(id, "FS_NOT_FOUND", "路径不存在：$path")
                    return
                }
                if (!dir.isDirectory) {
                    replyError(id, "FS_NOT_DIR", "不是目录：$path")
                    return
                }
                val max = args.optInt("maxEntries", 500).coerceIn(1, 5000)
                val all = dir.listFiles()?.sortedWith(
                    compareByDescending<File> { it.isDirectory }
                        .thenBy { it.name.lowercase() },
                ) ?: emptyList()
                val list = JSONArray()
                var added = 0
                for (child in all) {
                    if (added >= max) break
                    list.put(
                        JSONObject()
                            .put("name", child.name)
                            .put("path", child.absolutePath)
                            .put("dir", child.isDirectory)
                            .put("size", if (child.isFile) child.length() else 0L)
                            .put("mtime", child.lastModified())
                            .put("readable", child.canRead())
                            .put("writable", child.canWrite()),
                    )
                    added++
                }
                reply(
                    id,
                    JSONObject()
                        .put("path", dir.absolutePath)
                        .put("count", added)
                        .put("total", all.size)
                        .put("truncated", all.size > added)
                        .put("entries", list),
                )
            }

            "fs_stat" -> {
                val path = args.optString("path")
                if (path.isEmpty()) {
                    replyError(id, "BAD_ARGS", "缺少 path")
                    return
                }
                fsDenied(path)?.let { replyError(id, "PERMISSION_DENIED", it); return }
                val file = File(path)
                if (!file.exists()) {
                    replyError(id, "FS_NOT_FOUND", "路径不存在：$path")
                    return
                }
                reply(
                    id,
                    JSONObject()
                        .put("path", file.absolutePath)
                        .put("exists", true)
                        .put("dir", file.isDirectory)
                        .put("size", if (file.isFile) file.length() else 0L)
                        .put("mtime", file.lastModified())
                        .put("readable", file.canRead())
                        .put("writable", file.canWrite()),
                )
            }

            "fs_read" -> {
                val path = args.optString("path")
                if (path.isEmpty()) {
                    replyError(id, "BAD_ARGS", "缺少 path")
                    return
                }
                fsDenied(path)?.let { replyError(id, "PERMISSION_DENIED", it); return }
                val file = File(path)
                if (!file.exists() || !file.isFile) {
                    replyError(id, "FS_NOT_FOUND", "不是可读文件：$path")
                    return
                }
                val offset = args.optLong("offset", 0L).coerceAtLeast(0L)
                val size = file.length()
                if (offset > size) {
                    replyError(id, "FS_RANGE", "offset 超出文件长度（size=$size）")
                    return
                }
                val length = args.optLong("length", 512L * 1024).coerceIn(1L, 4L * 1024 * 1024)
                val want = minOf(length, size - offset).toInt()
                val buffer = ByteArray(want)
                val read = RandomAccessFile(file, "r").use { raf ->
                    raf.seek(offset)
                    var total = 0
                    while (total < want) {
                        val n = raf.read(buffer, total, want - total)
                        if (n <= 0) break
                        total += n
                    }
                    total
                }
                val chunk = if (read == want) buffer else buffer.copyOf(read)
                reply(
                    id,
                    JSONObject()
                        .put("path", file.absolutePath)
                        .put("offset", offset)
                        .put("bytes", chunk.size)
                        .put("nextOffset", offset + chunk.size)
                        .put("size", size)
                        .put("eof", offset + chunk.size >= size)
                        .put("dataB64", Base64.encodeToString(chunk, Base64.NO_WRAP)),
                )
            }

            "fs_write" -> {
                val path = args.optString("path")
                val data = args.optString("dataB64")
                if (path.isEmpty()) {
                    replyError(id, "BAD_ARGS", "缺少 path")
                    return
                }
                fsDenied(path)?.let { replyError(id, "PERMISSION_DENIED", it); return }
                val bytes = try {
                    Base64.decode(data, Base64.DEFAULT)
                } catch (error: Throwable) {
                    replyError(id, "BAD_ARGS", "dataB64 不是合法 base64")
                    return
                }
                val file = File(path)
                file.parentFile?.let { if (!it.exists() && !it.mkdirs()) { replyError(id, "FS_MKDIR_FAILED", "无法创建目录：${it.absolutePath}"); return } }
                val offset = args.optLong("offset", 0L).coerceAtLeast(0L)
                val truncate = args.optBoolean("truncate", true)
                try {
                    RandomAccessFile(file, "rw").use { raf ->
                        if (truncate) raf.setLength(0)
                        raf.seek(offset)
                        raf.write(bytes)
                    }
                } catch (error: Throwable) {
                    replyError(id, "FS_WRITE_FAILED", error.message ?: "写入失败")
                    return
                }
                reply(
                    id,
                    JSONObject()
                        .put("path", file.absolutePath)
                        .put("written", bytes.size)
                        .put("size", file.length())
                        .put("nextOffset", offset + bytes.size),
                )
            }

            "fs_mkdir" -> {
                val path = args.optString("path")
                if (path.isEmpty()) {
                    replyError(id, "BAD_ARGS", "缺少 path")
                    return
                }
                fsDenied(path)?.let { replyError(id, "PERMISSION_DENIED", it); return }
                val dir = File(path)
                val ok = dir.exists() && dir.isDirectory || dir.mkdirs()
                if (ok) reply(id, JSONObject().put("ok", true).put("path", dir.absolutePath))
                else replyError(id, "FS_MKDIR_FAILED", "创建目录失败：$path")
            }

            "fs_rm" -> {
                val path = args.optString("path")
                if (path.isEmpty()) {
                    replyError(id, "BAD_ARGS", "缺少 path")
                    return
                }
                fsDenied(path)?.let { replyError(id, "PERMISSION_DENIED", it); return }
                val file = File(path)
                if (!file.exists()) {
                    replyError(id, "FS_NOT_FOUND", "路径不存在：$path")
                    return
                }
                val recursive = args.optBoolean("recursive", false)
                if (file.isDirectory && !recursive) {
                    replyError(id, "FS_NOT_EMPTY", "是目录，需要 recursive=true 才会删除")
                    return
                }
                val ok = file.deleteRecursively()
                reply(id, JSONObject().put("ok", ok).put("path", file.absolutePath))
            }

            else -> replyError(id, "UNSUPPORTED", "手机桥不支持命令：$command")
        }
    }

    private fun reply(id: Long, result: JSONObject) {
        client?.sendResult(id, true, result, null, null)
    }

    /**
     * 文件命令的权限闸：有「所有文件访问」就全放行；
     * 没有的话只放行 App 私有目录（filesDir / cacheDir / 外部私有目录），
     * 这样关掉开关时也不能拿它当跳板读别人的数据。
     */
    private fun fsDenied(path: String?): String? {
        if (Environment.isExternalStorageManager()) return null
        val target = runCatching { File(path ?: "").canonicalPath }.getOrElse { return "路径不合法" }
        val allowed = listOfNotNull(
            filesDir, cacheDir, getExternalFilesDir(null), getExternalCacheDir(),
        ).map { runCatching { it.canonicalPath }.getOrNull() }.filterNotNull()
        if (allowed.any { target == it || target.startsWith(it + File.separator) }) return null
        return "没有「所有文件访问」权限：请到本机「设置 → 权限与安全 → 文件读写」打开；当前只允许访问 App 私有目录"
    }

    private fun replyError(id: Long, code: String, message: String) {
        client?.sendResult(id, false, null, code, message)
    }

    private fun disconnect(reason: String) {
        manualStop = true
        main.removeCallbacksAndMessages(null)
        retryScheduled = false
        client?.stop()
        client = null
        releaseWake()
        broadcast("disconnected", reason)
        updateNotification("已断开：$reason")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun broadcast(state: String, detail: String) {
        sendBroadcast(
            Intent(ACTION_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_DETAIL, detail),
        )
    }

    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(POWER_SERVICE) as PowerManager
        val lock = manager.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "dsh-phone-bridge:screen",
        )
        lock.setReferenceCounted(false)
        lock.acquire()
        wakeLock = lock
    }

    private fun releaseWake() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL, "手机桥连接", NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun startForegroundNow(text: String) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(text),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
    }

    private fun updateNotification(text: String) {
        // 通知不是关键路径：通知权限被撤销 / 渠道被删时 notify 会抛异常，
        // 不能让它把 onState 里的重试逻辑一起带走（v0.0.19 之前就踩过这个坑）。
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (error: Throwable) {
            android.util.Log.w("PhoneBridge", "更新通知失败：${error.message}")
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnectIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, PhoneBridgeService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("DSH 手机桥")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "断开连接", disconnectIntent)
            .build()
    }

    companion object {
        @Volatile var isConnected = false
        /** 最近一次状态与说明，供设置页回填（进程内共享，不落盘） */
        @Volatile var lastState: String = ""
        @Volatile var lastDetail: String = ""
        const val ACTION_CONNECT = "com.dsh.phonebridge.CONNECT"
        const val ACTION_DISCONNECT = "com.dsh.phonebridge.DISCONNECT"
        const val ACTION_STATE = "com.dsh.phonebridge.STATE"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_CODE = "code"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_KEEP_LOGIN = "keep_login"
        const val EXTRA_WS_URL = "ws_url"
        const val EXTRA_STATE = "state"
        const val EXTRA_DETAIL = "detail"
        const val PREFS = "dsh_phone_bridge"
        const val KEY_TOKEN = "bridge_token"
        const val KEY_ALLOW_SELF_CONTROL = "allow_self_control"
        const val CHANNEL = "phone_bridge_link"
        const val NOTIFICATION_ID = 41

        /** 会被「禁止远程操作本 App」这条规则拦下的命令（只读类不拦） */
        private val SELF_GUARDED = setOf("tap", "long_press", "swipe", "tap_text", "set_text", "input")
    }

    /**
     * 当前前台是不是手机桥自己、且用户没有显式放开远程操作。
     * 设置页里的配对码、端口、权限开关都属于「提权入口」，默认不给远端碰。
     */
    private fun selfControlBlocked(accessibility: DshAccessibilityService?): Boolean {
        val allow = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(KEY_ALLOW_SELF_CONTROL, false)
        if (allow) return false
        val foreground = accessibility?.foreground() ?: return false
        return foreground == packageName
    }
}
