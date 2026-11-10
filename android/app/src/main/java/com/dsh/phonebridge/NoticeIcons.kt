package com.dsh.phonebridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat

/**
 * 通知里的「应用图标」：两处通知都取桌面图标，不再用系统自带的聊天 / 蓝牙图标。
 *
 * 分两层，是因为 Android 对通知图标有两种完全不同的要求：
 * - smallIcon 会被系统当成蒙版染色（只认 alpha），彩色的整幅图标会糊成一团白方块，
 *   所以小图标用从桌面图标抠出来的鲸鱼剪影（单色资源 drawable-*dpi/ic_stat_dsh.png）；
 * - largeIcon 才是真彩色，直接把桌面图标画成位图 [large]；
 * - 再配一次品牌色 #4D6BFE，通知抬头的小图标底色跟 App 一致。
 *
 * ⚠️ 这里刻意不再暴露任何常量给调用方：0.0.44 崩过一次，`object` 里的
 * `const val SMALL = R.drawable.ic_stat_dsh` 被别的类引用时，Kotlin 增量编译产出的
 * 调用点是 `invoke-virtual NoticeIcons.getSMALL()I`，而类里只有静态字段、没有这个
 * 方法，一进 buildNotification 就 NoSuchMethodError 把整个进程带走。调用方现在直接
 * 写 `R.drawable.ic_stat_dsh` 和颜色字面量，绕开所有跨类访问器。
 */
object NoticeIcons {
    /** 通知大图标边长：通知图在 xxhdpi 上约 192px，256 足够且不占内存。 */
    private const val LARGE_SIZE = 256

    @Volatile
    private var cached: Bitmap? = null

    /**
     * 桌面图标（@mipmap/ic_launcher）渲成位图。API 26 起它是自适应图标（XML），
     * BitmapFactory 解不出来，只能先取 Drawable 再画到 Canvas 上。
     * 画失败就返回 null——宁可通知少一张大图，也不能让发通知这件事抛异常。
     */
    fun large(context: Context): Bitmap? {
        cached?.let { if (!it.isRecycled) return it }
        return try {
            val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher) ?: return null
            val bitmap = Bitmap.createBitmap(LARGE_SIZE, LARGE_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, LARGE_SIZE, LARGE_SIZE)
            drawable.draw(canvas)
            cached = bitmap
            bitmap
        } catch (error: Throwable) {
            android.util.Log.w("PhoneBridge", "通知大图标生成失败：${error.message}")
            null
        }
    }
}
