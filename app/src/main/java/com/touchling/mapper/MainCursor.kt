package com.touchling.mapper

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * v0.5.0 主屏光标（悬浮窗实现）
 *
 * 设计初衷：触控板/体感空鼠的**光标应该显示在主屏上**——
 * 背屏当触控板（手指在背屏移动/点击），眼睛看主屏上的光标。
 *
 * 需要 SYSTEM_ALERT_WINDOW（悬浮窗）权限；无权限时上层回退为背屏内光标。
 */
class MainCursor(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density
    private val size = (density * 24).toInt().coerceAtLeast(12)

    private var view: View? = null

    /** 是否具备悬浮窗权限 */
    val available: Boolean
        get() = Settings.canDrawOverlays(ctx)

    fun show() {
        if (!available) {
            Diag.log("主屏光标：无悬浮窗权限，跳过")
            return
        }
        main.post {
            if (view != null) return@post
            val v = View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x99FFFFFF.toInt())
                    setStroke((density * 2).toInt().coerceAtLeast(1), 0xFF2F9BFF.toInt())
                }
            }
            val metrics = ctx.resources.displayMetrics
            val lp = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = metrics.widthPixels / 2 - size / 2
            lp.y = metrics.heightPixels / 2 - size / 2
            try {
                wm.addView(v, lp)
                view = v
                Diag.log("主屏光标已显示 size=$size")
            } catch (t: Throwable) {
                Diag.log("主屏光标添加失败: $t")
            }
        }
    }

    /** 移动光标到主屏坐标 (x, y)，单位为像素 */
    fun move(x: Float, y: Float) {
        main.post {
            val v = view ?: return@post
            val lp = v.layoutParams as? WindowManager.LayoutParams ?: return@post
            lp.x = (x - size / 2f).toInt()
            lp.y = (y - size / 2f).toInt()
            try {
                wm.updateViewLayout(v, lp)
            } catch (_: Throwable) {
            }
        }
    }

    fun hide() {
        main.post {
            val v = view ?: return@post
            try {
                wm.removeView(v)
            } catch (_: Throwable) {
            }
            view = null
            Diag.log("主屏光标已隐藏")
        }
    }
}
