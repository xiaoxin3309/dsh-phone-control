package com.dsh.phonebridge

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView
import kotlin.math.roundToInt

/**
 * 按固定「高 / 宽」比确定自身高度的 SurfaceView。
 *
 * Camera1 的 `setPreviewDisplay(SurfaceHolder)` 会把预览缓冲区**直接拉伸**铺满
 * SurfaceView：取景框是竖屏满屏、缓冲区是横向 720p 时，画面就被横向压扁
 * （二维码看起来像长条，扫不出来也不奇怪）。CameraX 有 `ScaleType` 可选，
 * Camera1 没有，只能让 View 自己按缓冲区的比例决定大小，多出来的部分留给父容器
 * 的黑边。
 */
class AspectSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs) {

    /** 高度 / 宽度。<= 0 表示不干预，沿用父容器给的高度。 */
    var heightRatio: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (heightRatio <= 0f || width <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        setMeasuredDimension(width, (width * heightRatio).roundToInt())
    }
}
