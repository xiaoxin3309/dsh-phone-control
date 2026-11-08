package com.dsh.phonebridge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 会话通知：智能体「答完了」「在等你回答」时在手机上弹一条。
 *
 * 和前台那条「手机桥连接」（[PhoneBridgeService.CHANNEL]）分开两个渠道：
 * 连接状态是常驻低优通知，会话通知要能被用户单独静音 / 关闭。
 * 三个开关都在本机设置页，默认全开；系统通知权限单独算一道门。
 */
object NotifyCenter {
    const val CHANNEL = "dsh_session_notice"
    private const val CHANNEL_NAME = "会话通知"
    private const val NOTICE_ID = 42

    const val KIND_COMPLETE = "complete"
    const val KIND_QUESTION = "question"
    const val KIND_MANUAL = "manual"

    private const val KEY_ENABLED = "notify_enabled"
    private const val KEY_ON_COMPLETE = "notify_on_complete"
    private const val KEY_ON_QUESTION = "notify_on_question"
    private const val KEY_ON_MANUAL = "notify_on_manual"

    fun prefs(context: Context) =
        context.getSharedPreferences(PhoneBridgeService.PREFS, Context.MODE_PRIVATE)

    /** 系统层面的通知权限（Android 13 起用户可单独关掉）。 */
    fun permitted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        val channel = NotificationChannel(CHANNEL, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = "会话回答完成、或智能体提问等你回复时提醒"
        manager.createNotificationChannel(channel)
    }

    /**
     * 按当前开关决定要不要弹，弹了就返回 "shown"，否则返回被哪一道门拦下。
     * 返回值会原样回给电脑端，方便排查「为什么手机上没看到」。
     */
    fun show(
        context: Context,
        kind: String,
        title: String,
        body: String,
        sessionKey: String,
    ): String {
        if (!permitted(context)) return "permission"
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_ENABLED, true)) return "disabled"
        val kindOn = when (kind) {
            KIND_COMPLETE -> prefs.getBoolean(KEY_ON_COMPLETE, true)
            KIND_QUESTION -> prefs.getBoolean(KEY_ON_QUESTION, true)
            else -> prefs.getBoolean(KEY_ON_MANUAL, true)
        }
        if (!kindOn) return "kind_off"

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return "no_service"
        ensureChannel(context)

        val open = PendingIntent.getActivity(
            context,
            NOTICE_ID,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title.ifBlank { "DSH 会话" })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        return try {
            // 用会话做 tag：同一个会话只留最新一条，不同会话互不覆盖。
            manager.notify(sessionKey.ifBlank { "dsh-session" }, NOTICE_ID, notification)
            "shown"
        } catch (error: Throwable) {
            android.util.Log.w("PhoneBridge", "会话通知失败：${error.message}")
            "failed"
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) ensureChannel(context)
    }

    fun setKindEnabled(context: Context, kind: String, enabled: Boolean) {
        val key = when (kind) {
            KIND_COMPLETE -> KEY_ON_COMPLETE
            KIND_QUESTION -> KEY_ON_QUESTION
            KIND_MANUAL -> KEY_ON_MANUAL
            else -> return
        }
        prefs(context).edit().putBoolean(key, enabled).apply()
    }

    fun kindEnabled(context: Context, kind: String): Boolean {
        val prefs = prefs(context)
        return when (kind) {
            KIND_COMPLETE -> prefs.getBoolean(KEY_ON_COMPLETE, true)
            KIND_QUESTION -> prefs.getBoolean(KEY_ON_QUESTION, true)
            else -> prefs.getBoolean(KEY_ON_MANUAL, true)
        }
    }

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)
}
