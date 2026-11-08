package com.dsh.phonebridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val prefs = context.getSharedPreferences(PhoneBridgeService.PREFS, Context.MODE_PRIVATE)
        val host = prefs.getString("bridge_host", "").orEmpty()
        val token = TokenStore.get(context)
        val wsUrl = prefs.getString("ws_url", "").orEmpty()
        // 开机自启 = 「保持登录」的一部分：用户取消了勾选，就别自己连回来。
        val keepLogin = prefs.getBoolean("keep_login", true)
        if (!keepLogin) return
        if ((host.isEmpty() && wsUrl.isEmpty()) || token.isEmpty()) return

        val connect = Intent(context, PhoneBridgeService::class.java)
            .setAction(PhoneBridgeService.ACTION_CONNECT)
            .putExtra(PhoneBridgeService.EXTRA_HOST, host)
            .putExtra(PhoneBridgeService.EXTRA_PORT, prefs.getString("bridge_port", "8788")?.toIntOrNull() ?: 8788)
            .putExtra(PhoneBridgeService.EXTRA_CODE, prefs.getString("bridge_code", "").orEmpty())
            .putExtra(PhoneBridgeService.EXTRA_TOKEN, token)
            .putExtra(PhoneBridgeService.EXTRA_WS_URL, wsUrl)
            .putExtra(PhoneBridgeService.EXTRA_KEEP_LOGIN, keepLogin)
        runCatching { ContextCompat.startForegroundService(context, connect) }
    }
}
