package com.touchling.mapper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * v0.6.0 主屏光标（悬浮窗 + 可换样式）
 *
 * 触控板/体感模式下，光标显示在【主屏】上——背屏当触控板，眼睛看主屏。
 * 支持：样式（圆点/十字/箭头/方框）、颜色（蓝/白/红/绿/黄）、大小（小/中/大）
 * 需要 SYSTEM_ALERT_WINDOW（悬浮窗）权限；无权限时上层回退为背屏内光标。
 */
class MainCursor(
    private val ctx: Context,
    private val style: Int,
    sizeDp: Int,
    private val colorIdx: Int
) {

    companion object {
        /** 0小 1中 2大 → dp */
        fun sizeDp(idx: Int): Int = when (idx) {
            0 -> 18
            2 -> 36
            else -> 26
        }

        /** 0蓝 1白 2红 3绿 4黄 */
        fun colorOf(idx: Int): Int = when (idx) {
            1 -> 0xFFFFFFFF.toInt()
            2 -> 0xFFFF3B30.toInt()
            3 -> 0xFF34C759.toInt()
            4 -> 0xFFFFCC00.toInt()
            else -> 0xFF2F9BFF.toInt()
        }
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density
    private val size = (density * sizeDp.coerceIn(8, 96)).toInt().coerceAtLeast(12)

    private var view: View? = null

    val available: Boolean
        get() = Settings.canDrawOverlays(ctx)

    // 样式化光标（自绘）
    private inner class CursorView(c: Context) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = (density * 2f).coerceAtLeast(1.5f)

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val col = colorOf(colorIdx)
            when (style) {
                1 -> { // 十字准星
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = stroke
                    paint.color = col
                    val gap = w * 0.18f
                    canvas.drawLine(w / 2f, 0f, w / 2f, h / 2f - gap, paint)
                    canvas.drawLine(w / 2f, h / 2f + gap, w / 2f, h, paint)
                    canvas.drawLine(0f, h / 2f, w / 2f - gap, h / 2f, paint)
                    canvas.drawLine(w / 2f + gap, h / 2f, w, h / 2f, paint)
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(w / 2f, h / 2f, stroke * 1.2f, paint)
                }
                2 -> { // 箭头
                    paint.style = Paint.Style.FILL
                    paint.color = col
                    val p = Path().apply {
                        moveTo(w * 0.12f, h * 0.06f)
                        lineTo(w * 0.12f, h * 0.92f)
                        lineTo(w * 0.42f, h * 0.66f)
                        lineTo(w * 0.62f, h * 0.98f)
                        lineTo(w * 0.76f, h * 0.90f)
                        lineTo(w * 0.56f, h * 0.58f)
                        lineTo(w * 0.92f, h * 0.52f)
                        close()
                    }
                    canvas.drawPath(p, paint)
                }
                3 -> { // 方框
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = stroke
                    paint.color = col
                    val inset = stroke
                    canvas.drawRect(inset, inset, w - inset, h - inset, paint)
                }
                else -> { // 圆点（默认）
                    paint.style = Paint.Style.FILL
                    paint.color = col
                    paint.alpha = 0x99
                    canvas.drawCircle(w / 2f, h / 2f, w / 2f - stroke, paint)
                    paint.alpha = 0xFF
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = stroke
                    canvas.drawCircle(w / 2f, h / 2f, w / 2f - stroke, paint)
                }
            }
        }
    }

    fun show() {
        if (!available) {
            Diag.log("主屏光标：无悬浮窗权限，跳过")
            return
        }
        main.post {
            if (view != null) return@post
            val v = CursorView(ctx)
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
                Diag.log("主屏光标已显示 style=$style size=$size color=$colorIdx")
            } catch (t: Throwable) {
                Diag.log("主屏光标添加失败: $t")
            }
        }
    }

    /** 移动光标到主屏坐标 (x, y)，单位 px */
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

    /** v2.0.0：光标回中 */
    fun center(x: Float, y: Float) {
        move(x, y)
    }
}