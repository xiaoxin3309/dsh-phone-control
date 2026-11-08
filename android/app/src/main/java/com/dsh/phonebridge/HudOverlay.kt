package com.dsh.phonebridge

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * 手机端悬浮 HUD：在屏幕顶部显示「智能体刚刚做了什么」。
 *
 * 三条硬约束（对应已拍板的方案）：
 *  1. 默认关闭，必须在设置页显式打开 + 系统「显示在其他应用的上层」授权才出现；
 *  2. 只显示动作（点了什么 / 输入了什么 / 读了哪个文件），不显示模型正文；
 *  3. 智能体自己看不见它 —— 截图与控件树之前先摘窗、等一帧、再抓、抓完恢复，
 *     所以截出来的图里既没有 HUD 文字，也没有黑带。用户手动截图仍然看得见。
 *
 * 窗口本身是 NOT_FOCUSABLE | NOT_TOUCHABLE 的点穿窗口，不抢焦点、不挡点击。
 * 所有方法都必须在主线程调用（服务通过 main Handler 调到这儿）。
 */
class HudOverlay(
    private val context: Context,
    /**
     * 当前前台包名。手机桥自己的界面在前台时要摘窗（见 [syncForeground]）。
     * 由服务注入（无障碍服务能读根节点包名），取不到时返回 null，那就照常显示。
     */
    private val foreground: () -> String? = { null },
) {

    companion object {
        private const val TAG = "PhoneBridgeHud"

        /** 设置页开关写的键；HudOverlay 自己也监听它，关掉立刻摘窗。 */
        const val KEY_ENABLED = "hud_enabled"

        /** 最多显示几行，超出丢最旧的。 */
        const val MAX_LINES = 5

        /** 单行最多多少字，超出用 … 截断（窗口只有一行高）。 */
        const val MAX_CHARS = 48

        /** 一轮结束后的停留时间；中途的行由电脑端用 ttlMs=0 挂住，不自动淡出。 */
        const val IDLE_MS = 5_000L

        /** ttlMs 上限：再久也总有个兜底，免得 HUD 永远挂在屏幕上。 */
        private const val MAX_TTL_MS = 600_000L

        private const val FADE_MS = 220L

        /** 前台包名的巡检间隔（事件漏了也能在一秒内收起）。 */
        private const val WATCH_MS = 800L
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PhoneBridgeService.PREFS, Context.MODE_PRIVATE)

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val lines = ArrayDeque<String>()

    private var root: FrameLayout? = null
    private var card: LinearLayout? = null
    private var added = false
    private var hiddenDepth = 0
    private var faded = true
    private var fadeRunnable: Runnable? = null
    private var suppressed = false
    private var lastTtl = IDLE_MS

    /** 有内容在屏上（或被抑制）时巡检前台，一进手机桥自己的界面就摘窗。 */
    private val watch = object : Runnable {
        override fun run() {
            syncForeground()
            if (!faded && lines.isNotEmpty()) main.postDelayed(this, WATCH_MS)
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_ENABLED && !enabled()) {
            clear()
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    // ------------------------------------------------------------------ state

    fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun permitted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /** 给 `hud_state` 用的快照。 */
    fun state(): JSONObject = JSONObject()
        .put("enabled", enabled())
        .put("permission", permitted())
        .put("visible", added)
        .put("lines", JSONArray(lines.toList()))

    /**
     * 推一批新行。返回给电脑端的结果里带 reason，方便排查「为什么没显示」：
     * disabled / permission / no_service / shown。
     */
    fun push(incoming: List<String>, ttlMs: Long): JSONObject {
        if (!enabled()) return result(false, "disabled", 0)
        if (!permitted()) return result(false, "permission", 0)
        if (incoming.isEmpty()) return result(false, "empty", lines.size)

        for (raw in incoming) {
            val text = collapse(raw)
            if (text.isEmpty()) continue
            // 连续重复（同一个坐标连点两次）只留一条，免得五行全是「点击 (540, 1200)」。
            if (lines.isNotEmpty() && lines.last() == text) continue
            lines.addLast(text)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }

        faded = false
        lastTtl = if (ttlMs <= 0L) 0L else ttlMs.coerceIn(1_000L, MAX_TTL_MS)
        syncForeground()
        if (!suppressed) {
            render()
            ensureAdded()
        }
        applyFade()
        startWatch()
        return result(added, if (suppressed) "self" else if (added) "shown" else "pending", lines.size)
    }

    /** 一轮结束 / 换会话时清空，避免下次显示上一轮残留。 */
    fun clear() {
        cancelFade()
        main.removeCallbacks(watch)
        lines.clear()
        faded = true
        detach()
    }

    // ------------------------------------------------------- 截图前的临时摘窗

    /**
     * 截图 / 读控件树之前调用：立刻摘窗，等一个渲染帧后执行 [onReady]。
     * 调用方拿到结果后必须调 [afterCapture]（放在 finally 里），否则 HUD 不会回来。
     */
    fun beforeCapture(onReady: () -> Unit) {
        hiddenDepth += 1
        cancelFade()
        detach()
        // 窗口是 removeViewImmediate 摘的，但合成器可能还留着上一帧；
        // 等一帧再抓，避免截到残影。
        Choreographer.getInstance().postFrameCallback {
            try {
                onReady()
            } catch (error: Throwable) {
                Log.w(TAG, "capture callback failed: ${error.message}")
            }
        }
    }

    fun afterCapture() {
        hiddenDepth = (hiddenDepth - 1).coerceAtLeast(0)
        if (hiddenDepth > 0 || faded || lines.isEmpty()) return
        syncForeground()
        if (suppressed) return
        render()
        ensureAdded()
        applyFade()
    }

    fun destroy() {
        clear()
        runCatching { prefs.unregisterOnSharedPreferenceChangeListener(prefListener) }
    }

    // ---------------------------------------------------------------- 内部实现

    private fun result(shown: Boolean, reason: String, count: Int): JSONObject = JSONObject()
        .put("shown", shown)
        .put("reason", reason)
        .put("lines", count)

    private fun collapse(text: String): String {
        val flat = TextUtils.join(" ", text.trim().split(Regex("\\s+")))
        return if (flat.length <= MAX_CHARS) flat else flat.take(MAX_CHARS - 1) + "…"
    }

    private fun render() {
        val box = views().second
        box.removeAllViews()
        for (text in lines) {
            box.addView(lineView(text))
        }
    }

    private fun ensureAdded() {
        if (hiddenDepth > 0 || suppressed) return
        val view = views().first
        // 正在淡出时来了新事件：把动画掐掉、透明度拉回来，否则新行会跟着旧内容一起消失。
        view.animate().cancel()
        view.alpha = 1f
        if (added) return
        try {
            windowManager.addView(view, params())
            added = true
        } catch (error: Throwable) {
            // 授权被撤销、或系统拒绝时不要连累截图与点击，安静地不显示即可。
            Log.w(TAG, "overlay addView failed: ${error.message}")
            added = false
        }
    }

    private fun detach() {
        val view = root ?: return
        if (!added) return
        try {
            windowManager.removeViewImmediate(view)
        } catch (error: Throwable) {
            Log.w(TAG, "overlay removeView failed: ${error.message}")
        }
        added = false
    }

    private fun fadeOut() {
        if (!added) {
            faded = true
            return
        }
        val view = root ?: return
        view.animate()
            .alpha(0f)
            .setDuration(FADE_MS)
            .withEndAction {
                detach()
                faded = true
                lines.clear()
            }
            .start()
    }

    private fun resetFade(ttlMs: Long) {
        cancelFade()
        val runnable = Runnable { fadeOut() }
        fadeRunnable = runnable
        main.postDelayed(runnable, ttlMs)
    }

    private fun cancelFade() {
        fadeRunnable?.let { main.removeCallbacks(it) }
        fadeRunnable = null
    }

    /** ttl=0 表示「整轮不清空」，只等 turn/end 给出停留时间后再淡出。 */
    private fun applyFade() {
        if (lastTtl <= 0L) cancelFade() else resetFade(lastTtl)
    }

    private fun startWatch() {
        main.removeCallbacks(watch)
        if (!faded && lines.isNotEmpty()) main.postDelayed(watch, WATCH_MS)
    }

    /**
     * 手机桥自己的界面在前台时整块摘掉（不是淡出）：那是用户在配对/看设置，
     * 不该有我们自己的浮层盖在上面。等用户切走，再从缓存的行重新显示。
     */
    private fun syncForeground() {
        val pkg = runCatching { foreground() }.getOrNull()
        val self = pkg != null && pkg == context.packageName
        if (self == suppressed) return
        suppressed = self
        if (self) {
            detach()
            cancelFade()
            return
        }
        if (faded || lines.isEmpty()) return
        render()
        ensureAdded()
        applyFade()
    }

    // ------------------------------------------------------------------- 视图

    /** 懒建视图：外框（透明、撑满宽度，用来给卡片留左右边距）+ 卡片。 */
    private fun views(): Pair<FrameLayout, LinearLayout> {
        root?.let { outer -> card?.let { inner -> return outer to inner } }
        val outer = FrameLayout(context)
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#C0000000"))
                cornerRadius = dp(12f).toFloat()
            }
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        }
        outer.addView(
            inner,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                leftMargin = dp(12f)
                rightMargin = dp(12f)
            },
        )
        root = outer
        card = inner
        return outer to inner
    }

    private fun lineView(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(0, dp(1f), 0, dp(1f))
    }

    private fun params(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeight() + dp(8f)
        }
    }

    private fun statusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else dp(24f)
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()
}
