package com.dsh.phonebridge

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * 「手机桥自己的界面是不是正在前台」。
 *
 * 悬浮 HUD 需要在用户打开手机桥 App（配对、看设置、教程）时整块收起。判断这件事
 * **不能用 AccessibilityService 读根节点**：`rootInActiveWindow` 是同步的跨进程调用，
 * 主线程上最长能卡 5 秒，而 HUD 每 800ms 就要问一次，结果就是主线程被拖死、进程被
 * 当 ANR 杀掉（浮层消失、截图超时、App 掉线）。
 *
 * 用 Application 的 Activity 生命周期回调来判断：便宜、准确、纯本地。
 */
object AppPresence {

    /** 有几个自己的 Activity 处于「已启动未停止」状态。 */
    @Volatile
    private var resumed = 0

    @Volatile
    private var attached = false

    val isForeground: Boolean get() = resumed > 0

    fun attach(app: Application) {
        if (attached) return
        attached = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                resumed += 1
            }

            override fun onActivityStopped(activity: Activity) {
                if (resumed > 0) resumed -= 1
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
