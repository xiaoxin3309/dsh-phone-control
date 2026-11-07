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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream

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
    private var lastHost = ""
    private var lastPort = 8788
    private var lastCode = ""
    private var lastWsUrl = ""
    private var lastToken = ""

    override fun onCreate() {
        super.onCreate()
        createChannel()
        registerNetworkCallback()
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
            connect(host, port, code, token, keepLogin, wsUrl)
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
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun connect(host: String, port: Int, code: String, token: String, keepLogin: Boolean, wsUrl: String) {
        client?.stop()
        fatalStop = false
        this.keepLogin = keepLogin
        state = "connecting"
        val label = wsUrl.ifEmpty { "$host:$port" }
        broadcast("connecting", "正在连接 $label")
        updateNotification("正在连接 $label")
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "android"
        val effectiveToken = if (keepLogin) {
            token.ifEmpty { lastToken.ifEmpty { TokenStore.get(this) } }
        } else {
            TokenStore.clear(this)
            ""
        }
        val bridge = BridgeClient(
            host = host,
            port = port,
            code = code.ifEmpty { null },
            token = effectiveToken.ifEmpty { null },
            deviceId = deviceId,
            listener = this,
            wsUrl = wsUrl.ifEmpty { null },
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
        val fatal = listOf("TOKEN_INVALID", "BAD_CODE", "CODE_EXPIRED", "LOCKED", "没有配对码")
            .any { detail.contains(it) }
        if (fatal) {
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
            val token = if (keepLogin) lastToken.ifEmpty { TokenStore.get(this) } else ""
            connect(lastHost, lastPort, lastCode, token, keepLogin, lastWsUrl)
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
        val token = if (keepLogin) lastToken.ifEmpty { TokenStore.get(this) } else ""
        connect(lastHost, lastPort, lastCode, token, keepLogin, lastWsUrl)
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
        when (command) {
            "screenshot" -> {
                if (accessibility == null) {
                    replyError(id, "ACCESSIBILITY_OFF", "无障碍服务未开启")
                    return
                }
                accessibility.captureBitmap { bitmap ->
                    if (bitmap == null) {
                        replyError(id, "SCREENSHOT_FAILED", "截图失败，可能遇到安全窗口或系统限频")
                        return@captureBitmap
                    }
                    try {
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
                    } catch (error: Throwable) {
                        replyError(id, "SCREENSHOT_FAILED", error.message ?: "截图编码失败")
                    } finally {
                        bitmap.recycle()
                    }
                }
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
                val tree = accessibility.dumpUi(
                    maxDepth = args.optInt("maxDepth", 30).coerceIn(1, 60),
                    maxNodes = args.optInt("maxNodes", 400).coerceIn(20, 2000),
                    includeInvisible = args.optBoolean("includeInvisible", false),
                )
                tree.put("dumpedAt", System.currentTimeMillis())
                reply(id, tree)
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

            else -> replyError(id, "UNSUPPORTED", "手机桥不支持命令：$command")
        }
    }

    private fun reply(id: Long, result: JSONObject) {
        client?.sendResult(id, true, result, null, null)
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
        const val CHANNEL = "phone_bridge_link"
        const val NOTIFICATION_ID = 41
    }
}
