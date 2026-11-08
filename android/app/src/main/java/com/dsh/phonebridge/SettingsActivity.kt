package com.dsh.phonebridge

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var bridgeHost: EditText
    private lateinit var bridgePort: EditText
    private lateinit var bridgeCode: EditText
    private lateinit var bridgeWsUrl: EditText
    private lateinit var bridgeStatus: TextView
    private lateinit var tokenStatus: TextView
    private lateinit var keepLogin: CheckBox
    private lateinit var keepAwake: CheckBox
    private lateinit var dimOnControl: CheckBox
    private lateinit var dimLevel: SeekBar
    private lateinit var dimLabel: TextView
    private lateinit var logView: TextView
    private lateinit var accessibilityStatus: TextView
    private lateinit var statusDot: TextView
    private lateinit var cameraSwitch: MaterialSwitch
    private lateinit var cameraStatus: TextView
    private lateinit var filesSwitch: MaterialSwitch
    private lateinit var filesStatus: TextView
    private lateinit var selfControlSwitch: MaterialSwitch
    private lateinit var notifySwitch: MaterialSwitch
    private lateinit var notifyCompleteSwitch: MaterialSwitch
    private lateinit var notifyQuestionSwitch: MaterialSwitch
    private lateinit var notifyStatus: TextView
    private lateinit var notifyPermissionButton: Button

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getStringExtra(PhoneBridgeService.EXTRA_STATE) ?: return
            val detail = intent.getStringExtra(PhoneBridgeService.EXTRA_DETAIL).orEmpty()
            renderState(state, detail)
            log("手机桥 $state: $detail")
            if (state == "connected") bridgeStatus.postDelayed({ refreshTokenStatus() }, 1200)
        }
    }

    /** 扫电脑上的配对二维码 → 回填配对码 / 端口 / 地址 */
    private val scanLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val code = data.getStringExtra(ScanActivity.EXTRA_CODE).orEmpty()
            if (code.isEmpty()) return@registerForActivityResult
            val port = data.getStringExtra(ScanActivity.EXTRA_PORT).orEmpty()
            val ws = data.getStringExtra(ScanActivity.EXTRA_WS).orEmpty()
            val host = data.getStringExtra(ScanActivity.EXTRA_HOST).orEmpty()
            bridgeCode.setText(code)
            if (port.isNotEmpty()) bridgePort.setText(port)
            if (ws.isNotEmpty()) bridgeWsUrl.setText(ws)
            if (host.isNotEmpty()) bridgeHost.setText(host)
            log("扫码成功：配对码已填好${if (port.isNotEmpty()) "、端口 $port" else ""}${if (ws.isNotEmpty()) "、地址 $ws" else ""}")
            // 二维码里已经带了「码 + 端口（+ 地址）」，够连接了：直接连，不用再点一次。
            if (code.isNotEmpty() && (host.isNotEmpty() || ws.isNotEmpty())) {
                toast("已扫到配对信息，正在连接…")
                connectBridge()
            } else {
                toast("已填入扫码结果，补上电脑地址后点「立即连接」")
            }
        }

    /** 统一渲染连接状态标签；设置页重新打开时也用它回填最近状态 */
    private fun renderState(state: String, detail: String) {
        val method = if (state == "connected") {
            if (bridgeCode.text.toString().isNotBlank()) "配对码" else "已保存 token"
        } else {
            null
        }
        bridgeStatus.text = buildString {
            append("连接状态：").append(state)
            if (method != null) append("（").append(method).append("）")
            append("\n").append(detail)
        }
        statusDot.setTextColor(
            ContextCompat.getColor(
                this,
                when (state) {
                    "connected" -> R.color.dsh_ok
                    "connecting" -> R.color.dsh_warn
                    else -> R.color.dsh_idle
                },
            ),
        )
        // 配对成功的瞬间电脑端会把配对码换新，旧码已经失效。清空输入框并落盘，
        // 否则用户再点一次「立即连接」就会拿旧码去试，白白吃一次 BAD_CODE。
        // 但前提是登录凭证确实已经存下来了——以前不看这一点就清空，
        // 一旦 token 没落盘（旧版本 / 清过凭证），用户就会同时失去码和 token，
        // 变成「缺少配对码或登录凭证」的死角。
        if (state == "connected" && bridgeCode.text.toString().isNotBlank()) {
            if (TokenStore.get(this).isNotEmpty()) {
                bridgeCode.setText("")
                prefs().edit().putString(KEY_BRIDGE_CODE, "").apply()
                log("已配对：电脑端已换新配对码，输入框已清空；后续连接直接用已保存的登录凭证")
            } else {
                log("配对成功，但登录凭证未写入；保留配对码以便下次重连")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = findViewById<View>(R.id.settingsRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, root).isAppearanceLightStatusBars = !night

        urlInput = findViewById(R.id.dshUrl)
        bridgeHost = findViewById(R.id.bridgeHost)
        bridgePort = findViewById(R.id.bridgePort)
        bridgeCode = findViewById(R.id.bridgeCode)
        bridgeWsUrl = findViewById(R.id.bridgeWsUrl)
        bridgeStatus = findViewById(R.id.bridgeStatus)
        tokenStatus = findViewById(R.id.tokenStatus)
        keepLogin = findViewById(R.id.keepLogin)
        keepAwake = findViewById(R.id.keepAwake)
        dimOnControl = findViewById(R.id.dimOnControl)
        dimLevel = findViewById(R.id.dimLevel)
        dimLabel = findViewById(R.id.dimLabel)
        logView = findViewById(R.id.logView)
        accessibilityStatus = findViewById(R.id.accessibilityStatus)
        statusDot = findViewById(R.id.statusDot)
        cameraSwitch = findViewById(R.id.cameraSwitch)
        cameraStatus = findViewById(R.id.cameraStatus)
        filesSwitch = findViewById(R.id.filesSwitch)
        filesStatus = findViewById(R.id.filesStatus)
        selfControlSwitch = findViewById(R.id.selfControlSwitch)
        notifySwitch = findViewById(R.id.notifySwitch)
        notifyCompleteSwitch = findViewById(R.id.notifyCompleteSwitch)
        notifyQuestionSwitch = findViewById(R.id.notifyQuestionSwitch)
        notifyStatus = findViewById(R.id.notifyStatus)
        notifyPermissionButton = findViewById(R.id.notifyPermissionButton)
        showVersion()

        val prefs = prefs()
        urlInput.setText(prefs.getString(KEY_DSH_URL, ""))
        bridgeHost.setText(prefs.getString(KEY_BRIDGE_HOST, ""))
        bridgePort.setText(prefs.getString(KEY_BRIDGE_PORT, "8788"))
        bridgeCode.setText(prefs.getString(KEY_BRIDGE_CODE, ""))
        bridgeWsUrl.setText(prefs.getString(KEY_WS_URL, ""))
        keepLogin.isChecked = prefs.getBoolean(KEY_KEEP_LOGIN, true)
        keepAwake.isChecked = prefs.getBoolean(KEY_KEEP_AWAKE, true)
        dimOnControl.isChecked = prefs.getBoolean(KEY_DIM_ON_CONTROL, false)
        dimLevel.progress = prefs.getInt(KEY_DIM_LEVEL, 5)
        dimLabel.text = "亮度：${dimLevel.progress.coerceAtLeast(1)}%（仅 App 窗口）"
        dimLevel.isEnabled = dimOnControl.isChecked
        refreshTokenStatus()

        findViewById<Button>(R.id.saveButton).setOnClickListener { saveSettings() }
        findViewById<Button>(R.id.connectButton).setOnClickListener { connectBridge() }
        findViewById<Button>(R.id.disconnectButton).setOnClickListener { disconnectBridge() }
        findViewById<Button>(R.id.clearTokenButton).setOnClickListener {
            TokenStore.clear(this)
            refreshTokenStatus()
            toast("已清除保存的登录凭证")
        }
        findViewById<Button>(R.id.guideButton).setOnClickListener {
            startActivity(Intent(this, GuideActivity::class.java))
        }
        findViewById<View>(R.id.githubLinkBox).setOnClickListener { openRepoInBrowser() }
        findViewById<Button>(R.id.scanQrButton).setOnClickListener { openScanner() }

        // 权限与安全：开关反映系统里的真实授权状态，打开时才去申请
        refreshPermissionUi()
        selfControlSwitch.isChecked = prefs().getBoolean(KEY_ALLOW_SELF_CONTROL, false)
        cameraSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (!hasCameraPermission()) requestCameraPermission()
            } else if (hasCameraPermission()) {
                refreshPermissionUi()
                toast("关掉开关不会撤销系统权限；要彻底撤销请到系统「应用 → 权限」里关闭摄像头")
            }
        }
        filesSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (!hasAllFilesAccess()) openAllFilesSettings()
            } else if (hasAllFilesAccess()) {
                refreshPermissionUi()
                toast("「所有文件访问」只能在系统设置里关闭，取消后 App 只能访问自己的私有目录")
            }
        }
        selfControlSwitch.setOnCheckedChangeListener { _, checked ->
            prefs().edit().putBoolean(KEY_ALLOW_SELF_CONTROL, checked).apply()
            if (checked) {
                log("已允许远端操作本 App 界面：配对码、端口、权限开关都能被远程点到，请谨慎")
            } else {
                log("已禁止远端操作本 App 界面（默认）：tap/文字点击/输入类命令会被 SELF_PROTECTED 拒绝")
            }
        }

        // 会话通知：电脑端推来的「答完了 / 等你回复」要不要在本机弹，由这三个开关决定
        notifySwitch.isChecked = NotifyCenter.enabled(this)
        notifyCompleteSwitch.isChecked = NotifyCenter.kindEnabled(this, NotifyCenter.KIND_COMPLETE)
        notifyQuestionSwitch.isChecked = NotifyCenter.kindEnabled(this, NotifyCenter.KIND_QUESTION)
        refreshNotifyUi()
        notifySwitch.setOnCheckedChangeListener { _, checked ->
            NotifyCenter.setEnabled(this, checked)
            if (checked && !NotifyCenter.permitted(this)) requestNotificationPermission()
            refreshNotifyUi()
        }
        notifyCompleteSwitch.setOnCheckedChangeListener { _, checked ->
            NotifyCenter.setKindEnabled(this, NotifyCenter.KIND_COMPLETE, checked)
            refreshNotifyUi()
        }
        notifyQuestionSwitch.setOnCheckedChangeListener { _, checked ->
            NotifyCenter.setKindEnabled(this, NotifyCenter.KIND_QUESTION, checked)
            refreshNotifyUi()
        }
        notifyPermissionButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) requestNotificationPermission() else openAppNotificationSettings()
        }

        keepAwake.setOnCheckedChangeListener { _, checked ->
            prefs().edit().putBoolean(KEY_KEEP_AWAKE, checked).apply()
        }
        dimOnControl.setOnCheckedChangeListener { _, checked ->
            prefs().edit().putBoolean(KEY_DIM_ON_CONTROL, checked).apply()
            dimLevel.isEnabled = checked
        }
        dimLevel.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                dimLabel.text = "亮度：${progress.coerceAtLeast(1)}%（仅 App 窗口）"
                prefs().edit().putInt(KEY_DIM_LEVEL, progress).apply()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.screenshotButton).setOnClickListener {
            val service = DshAccessibilityService.instance
            if (service == null) {
                log("无障碍未连接，无法截图")
                toast("请先启用无障碍服务")
            } else {
                service.captureToFile()
            }
        }
        findViewById<Button>(R.id.tapButton).setOnClickListener {
            val service = DshAccessibilityService.instance
            if (service == null) {
                log("无障碍未连接，无法点击")
                toast("请先启用无障碍服务")
            } else {
                service.tapCenter()
            }
        }
    }

    private fun openScanner() {
        if (!hasCameraPermission()) {
            toast("请先打开「权限与安全 → 摄像头」，扫码要用它")
            return
        }
        scanLauncher.launch(Intent(this, ScanActivity::class.java))
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            toast("摄像头用于电脑端远程拍照 / 扫码配对，只在你打开开关后才申请")
        }
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
    }

    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    /** 「所有文件访问」只能由用户在系统页面授权，这里直接跳到本 App 的那一页 */
    private fun openAllFilesSettings() {
        if (Build.VERSION.SDK_INT < 30) {
            toast("当前系统版本不需要「所有文件访问」，已按普通存储权限处理")
            return
        }
        val perApp = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        try {
            startActivity(perApp)
        } catch (e: Exception) {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                .onFailure { toast("没有找到「所有文件访问」设置页") }
        }
    }

    private fun refreshPermissionUi() {
        val camera = hasCameraPermission()
        cameraSwitch.isChecked = camera
        cameraStatus.text = getString(if (camera) R.string.perm_camera_on else R.string.perm_camera_off)
        val files = hasAllFilesAccess()
        filesSwitch.isChecked = files
        filesStatus.text = getString(if (files) R.string.perm_files_on else R.string.perm_files_off)
    }

    /** 顶部那行小字：手机上报出来的版本号与包名，用来确认装的是不是刚构建的那个包。 */
    private fun showVersion() {
        val view = findViewById<TextView>(R.id.settingsVersion) ?: return
        val info = try {
            if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
        } catch (error: Exception) {
            null
        }
        if (info == null) return
        val code = if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
        view.text = getString(R.string.settings_version_fmt, info.versionName ?: "?", code, packageName)
    }

    private fun refreshNotifyUi() {
        val permitted = NotifyCenter.permitted(this)
        val master = NotifyCenter.enabled(this)
        notifyStatus.text = getString(
            R.string.notify_status_fmt,
            getString(if (permitted) R.string.notify_perm_on else R.string.notify_perm_off),
            if (master) "开" else "关",
            if (NotifyCenter.kindEnabled(this, NotifyCenter.KIND_COMPLETE)) "开" else "关",
            if (NotifyCenter.kindEnabled(this, NotifyCenter.KIND_QUESTION)) "开" else "关",
        )
        notifyPermissionButton.visibility = if (permitted) View.GONE else View.VISIBLE
        val active = master && permitted
        notifyCompleteSwitch.isEnabled = active
        notifyQuestionSwitch.isEnabled = active
    }

    private fun openAppNotificationSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            )
        } catch (e: Exception) {
            toast("没有找到本 App 的通知设置页")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            refreshPermissionUi()
            if (hasCameraPermission()) {
                log("摄像头权限已授予")
            } else {
                log("摄像头权限未授予")
            }
        }
        refreshNotifyUi()
    }

    /** 用系统默认浏览器打开开源仓库；本机没有浏览器时退化成复制链接 */
    private fun openRepoInBrowser() {
        val url = "https://github.com/xiaoxin3309/dsh-phone-control"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("repo", url))
            toast("没有可用的浏览器，链接已复制")
        }
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
        val enabled = DshAccessibilityService.instance != null ||
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.contains("$packageName/${DshAccessibilityService::class.java.name}") == true
        accessibilityStatus.text =
            if (enabled) getString(R.string.accessibility_enabled) else getString(R.string.accessibility_disabled)
        // 服务里已经连着，但设置页是后打开的 —— 广播早发过了，这里回填最近状态，
        // 否则会显示上一次的（甚至默认的「未连接」）标签。
        val lastState = PhoneBridgeService.lastState
        if (lastState.isNotEmpty()) renderState(lastState, PhoneBridgeService.lastDetail)
        // 从系统设置页回来时权限可能已经变了
        refreshPermissionUi()
        refreshNotifyUi()
    }

    private fun saveSettings() {
        var raw = urlInput.text.toString().trim()
        if (raw.isNotEmpty() && !raw.startsWith("http://") && !raw.startsWith("https://")) {
            raw = "https://$raw"
            urlInput.setText(raw)
        }
        prefs().edit()
            .putString(KEY_DSH_URL, raw)
            .putString(KEY_BRIDGE_HOST, bridgeHost.text.toString().trim())
            .putString(KEY_BRIDGE_PORT, bridgePort.text.toString().trim())
            .putString(KEY_BRIDGE_CODE, bridgeCode.text.toString().trim())
            .putString(KEY_WS_URL, bridgeWsUrl.text.toString().trim())
            .putBoolean(KEY_KEEP_LOGIN, keepLogin.isChecked)
            .apply()
        finish()
    }

    private fun connectBridge() {
        val host = bridgeHost.text.toString().trim()
        val wsUrl = bridgeWsUrl.text.toString().trim()
        if (host.isEmpty() && wsUrl.isEmpty()) {
            toast("请填写电脑地址或 WebSocket 地址")
            return
        }
        if (wsUrl.isNotEmpty() && !wsUrl.startsWith("ws://") && !wsUrl.startsWith("wss://")) {
            toast("WebSocket 地址应以 ws:// 或 wss:// 开头")
            return
        }
        val port = bridgePort.text.toString().trim().toIntOrNull()?.coerceIn(1, 65535) ?: 8788
        val code = bridgeCode.text.toString().trim()
        val token = TokenStore.get(this)
        if (code.isEmpty() && token.isEmpty()) {
            toast("没有配对码，也没有已保存的登录凭证；请填写 8 位配对码")
            bridgeStatus.text = "连接状态：缺少配对码或登录凭证"
            return
        }
        if (code.isNotEmpty() && !code.matches(Regex("\\d{8}"))) {
            toast("配对码应为 8 位数字")
            return
        }
        prefs().edit()
            .putString(KEY_BRIDGE_HOST, host)
            .putString(KEY_BRIDGE_PORT, port.toString())
            .putString(KEY_BRIDGE_CODE, code)
            .putString(KEY_WS_URL, wsUrl)
            .putBoolean(KEY_KEEP_LOGIN, keepLogin.isChecked)
            .apply()
        requestNotificationPermission()
        val intent = Intent(this, PhoneBridgeService::class.java)
            .setAction(PhoneBridgeService.ACTION_CONNECT)
            .putExtra(PhoneBridgeService.EXTRA_HOST, host)
            .putExtra(PhoneBridgeService.EXTRA_PORT, port)
            .putExtra(PhoneBridgeService.EXTRA_CODE, code)
            .putExtra(PhoneBridgeService.EXTRA_WS_URL, wsUrl)
            .putExtra(PhoneBridgeService.EXTRA_KEEP_LOGIN, keepLogin.isChecked)
        ContextCompat.startForegroundService(this, intent)
        val method = if (code.isNotEmpty()) "配对码" else "已保存 token"
        bridgeStatus.text = "连接状态：正在连接 ${wsUrl.ifEmpty { "$host:$port" }}（$method）"
    }

    private fun disconnectBridge() {
        startService(Intent(this, PhoneBridgeService::class.java).setAction(PhoneBridgeService.ACTION_DISCONNECT))
        bridgeStatus.text = "连接状态：已请求断开"
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun refreshTokenStatus() {
        val token = TokenStore.get(this)
        tokenStatus.text = if (token.isEmpty()) getString(R.string.token_missing) else getString(R.string.token_saved)
    }

    private fun prefs() = getSharedPreferences(PhoneBridgeService.PREFS, MODE_PRIVATE)

    private fun log(message: String) {
        val line = "[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}] $message"
        val placeholder = getString(R.string.log_empty)
        val current = logView.text?.toString().orEmpty()
        logView.text = if (current.isEmpty() || current == placeholder) line else "$current\n$line"
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_CAMERA = 101
        private const val KEY_DSH_URL = "dsh_url"
        private const val KEY_BRIDGE_HOST = "bridge_host"
        private const val KEY_BRIDGE_PORT = "bridge_port"
        private const val KEY_BRIDGE_CODE = "bridge_code"
        private const val KEY_WS_URL = "bridge_ws_url"
        private const val KEY_KEEP_LOGIN = "keep_login"
        private const val KEY_KEEP_AWAKE = "keep_awake"
        private const val KEY_DIM_ON_CONTROL = "dim_on_control"
        private const val KEY_DIM_LEVEL = "dim_level"
        private const val KEY_ALLOW_SELF_CONTROL = "allow_self_control"
    }
}
