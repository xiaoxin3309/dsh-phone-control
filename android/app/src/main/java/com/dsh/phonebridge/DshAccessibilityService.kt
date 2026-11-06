package com.dsh.phonebridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class DshAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        toast("无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 事件流留给后续的 UI 树/等待能力；当前按需读取根节点即可。
    }

    override fun onInterrupt() {
        // no-op
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun captureBitmap(callback: (Bitmap?) -> Unit) {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    var bitmap: Bitmap? = null
                    try {
                        bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (_: Throwable) {
                        bitmap = null
                    } finally {
                        result.hardwareBuffer.close()
                    }
                    callback(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    callback(null)
                }
            },
        )
    }

    fun captureToFile() {
        captureBitmap { bitmap ->
            if (bitmap == null) {
                toast("截图失败")
                return@captureBitmap
            }
            try {
                val dir = getExternalFilesDir(null) ?: filesDir
                val file = File(dir, "m0-screenshot-${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                toast("截图已保存: ${file.name}")
            } catch (t: Throwable) {
                toast("截图失败: ${t.message}")
            } finally {
                bitmap.recycle()
            }
        }
    }

    fun tapCenter() {
        val metrics = resources.displayMetrics
        tap(metrics.widthPixels / 2f, metrics.heightPixels / 2f, 50) { ok ->
            toast(if (ok) "点击完成" else "点击取消")
        }
    }

    fun tap(x: Float, y: Float, durationMs: Long, callback: (Boolean) -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(path, durationMs, callback)
    }

    fun longPress(x: Float, y: Float, durationMs: Long, callback: (Boolean) -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(path, durationMs.coerceIn(100, 10_000), callback)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long, callback: (Boolean) -> Unit) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        dispatch(path, durationMs.coerceIn(50, 10_000), callback)
    }

    private fun dispatch(path: Path, durationMs: Long, callback: (Boolean) -> Unit) {
        main.post {
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            val accepted = dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        callback(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        callback(false)
                    }
                },
                null,
            )
            if (!accepted) callback(false)
        }
    }

    fun inputText(text: String, callback: (Boolean) -> Unit) {
        main.post {
            val root = rootInActiveWindow
            val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused == null) {
                callback(false)
                return@post
            }
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            callback(focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
        }
    }

    fun globalAction(action: Int): Boolean = performGlobalAction(action)

    fun currentPackage(): String? = rootInActiveWindow?.packageName?.toString()

    // ---- 节点树（比截图便宜一到两个数量级） ----

    private val scratch = Rect()

    fun dumpUi(maxDepth: Int, maxNodes: Int, includeInvisible: Boolean): JSONObject {
        val root = rootInActiveWindow
            ?: return JSONObject()
                .put("package", JSONObject.NULL)
                .put("nodes", JSONArray())
                .put("count", 0)
                .put("truncated", false)
        val nodes = JSONArray()
        var count = 0
        var truncated = false

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > maxDepth || truncated) return
            if (count >= maxNodes) {
                truncated = true
                return
            }
            if (includeInvisible || node.isVisibleToUser) {
                val entry = JSONObject()
                entry.put("i", count)
                entry.put("d", depth)
                entry.put("c", node.className?.toString()?.substringAfterLast('.') ?: "?")
                val text = node.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) entry.put("t", clip(text))
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                if (desc.isNotEmpty()) entry.put("cd", clip(desc))
                val viewId = node.viewIdResourceName
                if (!viewId.isNullOrEmpty()) entry.put("id", viewId.substringAfterLast('/'))
                node.getBoundsInScreen(scratch)
                if (scratch.width() > 0 && scratch.height() > 0) {
                    entry.put(
                        "b",
                        JSONArray()
                            .put(scratch.left).put(scratch.top)
                            .put(scratch.right).put(scratch.bottom),
                    )
                }
                val flags = StringBuilder()
                if (node.isClickable) flags.append("click ")
                if (node.isLongClickable) flags.append("long ")
                if (node.isEditable) flags.append("edit ")
                if (node.isScrollable) flags.append("scroll ")
                if (node.isCheckable) flags.append(if (node.isChecked) "checked " else "unchecked ")
                if (node.isSelected) flags.append("sel ")
                if (node.isFocused) flags.append("foc ")
                if (!node.isEnabled) flags.append("dis ")
                if (flags.isNotEmpty()) entry.put("f", flags.toString().trim())
                nodes.put(entry)
                count += 1
            }
            for (child in 0 until node.childCount) walk(node.getChild(child), depth + 1)
        }

        walk(root, 0)
        return JSONObject()
            .put("package", root.packageName?.toString() ?: JSONObject.NULL)
            .put("nodes", nodes)
            .put("count", count)
            .put("truncated", truncated)
    }

    fun clickByText(query: String, exact: Boolean, index: Int): JSONObject {
        val match = findNode(query, exact, index)
            ?: return JSONObject()
                .put("ok", false)
                .put("code", "NODE_NOT_FOUND")
                .put("message", "屏幕上找不到「$query」，可用 phone_ui_dump 查看当前控件")
        val node = match.first

        // 一是控件自己/祖先支持 ACTION_CLICK（最稳、与坐标无关）
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 8) {
            if (current.isClickable && current.isEnabled &&
                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                return JSONObject()
                    .put("ok", true)
                    .put("method", if (hops == 0) "action" else "ancestor-action")
                    .put("hops", hops)
                    .put("matched", match.second)
                    .put("text", clip(node.text?.toString()?.trim().orEmpty()))
            }
            current = current.parent
            hops += 1
        }

        // 二是不支持 ACTION_CLICK 的自绘控件：退回按 bounds 中心点手势点击
        node.getBoundsInScreen(scratch)
        if (scratch.width() <= 0 || scratch.height() <= 0) {
            return JSONObject()
                .put("ok", false)
                .put("code", "NODE_NOT_CLICKABLE")
                .put("message", "「$query」既不可点击也没有可点的区域")
        }
        val cx = scratch.exactCenterX()
        val cy = scratch.exactCenterY()
        tap(cx, cy, 50) { ok ->
            if (!ok) toast("按文字点击失败：$query")
        }
        return JSONObject()
            .put("ok", true)
            .put("method", "gesture")
            .put("matched", match.second)
            .put("text", clip(node.text?.toString()?.trim().orEmpty()))
            .put("x", cx.toDouble())
            .put("y", cy.toDouble())
    }

    fun setTextByTarget(query: String?, text: String): JSONObject {
        val root = rootInActiveWindow
            ?: return JSONObject().put("ok", false).put("code", "NO_WINDOW").put("message", "读不到当前窗口")
        val start = if (query.isNullOrBlank()) {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        } else {
            findNode(query, false, 0)?.first
        }
        val target = findEditable(start, 0)
            ?: return JSONObject()
                .put("ok", false)
                .put("code", "NOT_EDITABLE")
                .put("message", if (query.isNullOrBlank()) "当前没有聚焦的输入框" else "「$query」附近没有可写入的输入框")

        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        return if (ok) {
            JSONObject().put("ok", true).put("text", clip(text)).put("chars", text.length)
        } else {
            JSONObject()
                .put("ok", false)
                .put("code", "SET_TEXT_FAILED")
                .put("message", "该输入框拒绝直接写入，改用 phone_input（走键盘）")
        }
    }

    private fun findEditable(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 6) return null
        if (node.isEditable && node.isEnabled) return node
        for (child in 0 until node.childCount) {
            val hit = findEditable(node.getChild(child), depth + 1)
            if (hit != null) return hit
        }
        return null
    }

    /** 按 text / contentDescription / viewId 找节点；可点击的优先。 */
    private fun findNode(
        query: String,
        exact: Boolean,
        index: Int,
    ): Pair<AccessibilityNodeInfo, String>? {
        val root = rootInActiveWindow ?: return null
        val needle = query.trim()
        if (needle.isEmpty()) return null
        val lower = needle.lowercase()
        val matches = ArrayList<Pair<AccessibilityNodeInfo, String>>()

        fun matchedBy(node: AccessibilityNodeInfo): String? {
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val viewId = node.viewIdResourceName?.substringAfterLast('/')?.lowercase().orEmpty()
            return if (exact) {
                when {
                    text == needle -> "text"
                    desc == needle -> "desc"
                    viewId == lower -> "id"
                    else -> null
                }
            } else {
                when {
                    text.lowercase().contains(lower) -> "text"
                    desc.lowercase().contains(lower) -> "desc"
                    viewId.contains(lower) -> "id"
                    else -> null
                }
            }
        }

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 40 || matches.size >= 200) return
            if (node.isVisibleToUser) {
                val hit = matchedBy(node)
                if (hit != null) matches.add(node to hit)
            }
            for (child in 0 until node.childCount) walk(node.getChild(child), depth + 1)
        }

        walk(root, 0)
        if (matches.isEmpty()) return null
        val ordered = matches.sortedByDescending { it.first.isClickable }
        return ordered.getOrNull(index.coerceAtLeast(0)) ?: ordered.first()
    }

    private fun clip(value: String): String =
        if (value.length <= 160) value else value.substring(0, 160) + "…"

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        @Volatile
        var instance: DshAccessibilityService? = null
            private set
    }
}
