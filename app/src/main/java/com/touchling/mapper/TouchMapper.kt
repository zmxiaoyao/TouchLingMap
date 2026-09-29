package com.touchling.mapper

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** 运行配置（从 SharedPreferences 读取） */
class Cfg(
    val mode: String,
    val sens: Float,
    val mask: Int,
    val channel: String,
    val gyro: Boolean,
    val scroll2: Boolean,
    val invX: Boolean,
    val invY: Boolean,
    val cursorStyle: Int,
    val cursorSizeIdx: Int,
    val cursorColor: Int,
    val rearBg: Int
) {
    companion object {
        fun load(ctx: Context): Cfg {
            val sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            return Cfg(
                sp.getString("mode", "direct") ?: "direct",
                sp.getFloat("sens", 1f),
                sp.getInt("mask", 0),
                sp.getString("channel", "auto") ?: "auto",
                sp.getBoolean("gyro", false),
                sp.getBoolean("scroll2", true),
                sp.getBoolean("invX", false),
                sp.getBoolean("invY", false),
                sp.getInt("cursorStyle", 0),
                sp.getInt("cursorSizeIdx", 1),
                sp.getInt("cursorColor", 0),
                sp.getInt("rearBg", 0)
            )
        }
    }
}

/**
 * 触控映射器（在背屏窗口内运行）
 * - direct（灵触映射）：背屏坐标线性压缩到主屏坐标，直接注入点按/滑动
 * - pad（精密触控板）：手指相对位移 → 移动光标；轻点=点击；停住 0.4s 后拖动=拖拽
 */
class TouchMapper(
    private val injector: Injector,
    private val cfg: Cfg,
    private val mainW: Int,
    private val mainH: Int,
    private val onCursor: (Float, Float, Boolean) -> Unit
) {
    // 触控板光标（主屏坐标）
    private var cx = mainW / 2f
    private var cy = mainH / 2f

    // 手势状态
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var dragging = false
    private var longPressFired = false
    private var dragX = 0f
    private var dragY = 0f

    // 灵触模式节流
    private var lastSendT = 0L
    private var lastSendX = 0f
    private var lastSendY = 0f

    private val longPressRunnable = Runnable {
        if (!moved && !longPressFired) {
            longPressFired = true
            dragging = true
            dragX = cx
            dragY = cy
            injector.down(dragX, dragY)
            onCursor(cx, cy, true)
        }
    }

    fun handle(e: MotionEvent, view: View): Boolean {
        return if (cfg.mode == "pad") pad(e, view) else direct(e, view)
    }

    /** 灵触映射：背屏 → 主屏 直接压缩映射 */
    private fun direct(e: MotionEvent, view: View): Boolean {
        val bw = view.width.toFloat().coerceAtLeast(1f)
        val bh = view.height.toFloat().coerceAtLeast(1f)
        val x = (e.x / bw * mainW).coerceIn(0f, mainW - 1f)
        val y = (e.y / bh * mainH).coerceIn(0f, mainH - 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastSendX = x; lastSendY = y; lastSendT = now()
                injector.down(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                val t = now()
                if (hypot(x - lastSendX, y - lastSendY) >= 3f || t - lastSendT >= 40) {
                    injector.move(x, y)
                    lastSendX = x; lastSendY = y; lastSendT = t
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> injector.up(x, y)
        }
        return true
    }

    /** 精密触控板：相对位移光标 */
    private fun pad(e: MotionEvent, view: View): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                lastX = e.x; lastY = e.y
                moved = false; dragging = false; longPressFired = false
                view.postDelayed(longPressRunnable, 380)
                onCursor(cx, cy, true)
            }
            MotionEvent.ACTION_MOVE -> {
                val ix = if (cfg.invX) -1f else 1f
                val iy = if (cfg.invY) -1f else 1f
                val dx = (e.x - lastX) * ix
                val dy = (e.y - lastY) * iy
                lastX = e.x; lastY = e.y
                if (!longPressFired && hypot(e.x - downX, e.y - downY) > 12f) {
                    moved = true
                    view.removeCallbacks(longPressRunnable)
                }
                if (dragging) {
                    dragX = (dragX + dx * cfg.sens).coerceIn(0f, mainW - 1f)
                    dragY = (dragY + dy * cfg.sens).coerceIn(0f, mainH - 1f)
                    injector.move(dragX, dragY)
                    cx = dragX; cy = dragY
                } else if (moved) {
                    cx = (cx + dx * cfg.sens).coerceIn(0f, mainW - 1f)
                    cy = (cy + dy * cfg.sens).coerceIn(0f, mainH - 1f)
                }
                onCursor(cx, cy, true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.removeCallbacks(longPressRunnable)
                if (dragging) {
                    injector.up(dragX, dragY)
                    dragging = false
                } else if (!moved) {
                    injector.tap(cx, cy)
                }
                onCursor(cx, cy, true)
            }
        }
        return true
    }

    private fun now() = SystemClock.uptimeMillis()
}