package com.touchling.mapper

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * 显示在背屏上的窗口：
 * SurfaceView 接收主屏镜像画面 → 黑遮罩 → 光标（触控板模式）
 * 整个窗口接收背屏触摸并交给 TouchMapper 处理
 */
class MirrorPresentation(
    context: Context,
    display: Display,
    private val injector: Injector,
    private val cfg: Cfg
) : Presentation(context, display, android.R.style.Theme_Material_NoActionBar_Fullscreen) {

    var onSurfaceReady: ((Surface) -> Unit)? = null
    var onDismissed: (() -> Unit)? = null

    private var mapper: TouchMapper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 背屏常亮（防休眠）
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try { window?.setDecorFitsSystemWindows(false) } catch (_: Throwable) {}

        val app = context.applicationContext
        val mm = app.resources.displayMetrics
        val mainW = mm.widthPixels
        val mainH = mm.heightPixels

        val frame = BackFrame(app)

        // 1. 镜像画面
        val sv = SurfaceView(app)
        frame.addView(
            sv,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        // 2. 防烧屏黑遮罩
        val mask = View(app).apply {
            setBackgroundColor(Color.BLACK)
            alpha = cfg.mask / 100f
        }
        frame.addView(
            mask,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        // 3. 触控板光标
        val cursorSize = (26 * app.resources.displayMetrics.density).toInt()
        val cursor = View(app).apply {
            setBackgroundColor(0xDDFFFFFF.toInt())
            visibility = View.GONE
        }
        frame.addView(
            cursor,
            FrameLayout.LayoutParams(cursorSize, cursorSize, Gravity.TOP or Gravity.START)
        )

        // 4. 触摸映射
        mapper = TouchMapper(injector, cfg, mainW, mainH) { x, y, visible ->
            val bw = frame.width.takeIf { it > 0 } ?: 1
            val bh = frame.height.takeIf { it > 0 } ?: 1
            val lp = cursor.layoutParams as FrameLayout.LayoutParams
            lp.leftMargin = (x / mainW * bw).toInt()
            lp.topMargin = (y / mainH * bh).toInt()
            cursor.layoutParams = lp
            cursor.visibility = if (visible) View.VISIBLE else View.GONE
        }
        frame.touchHandler = { e -> mapper?.handle(e, frame) ?: false }

        // 5. Surface 就绪后回调给服务创建 VirtualDisplay
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                onSurfaceReady?.invoke(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })

        setContentView(frame)
    }

    override fun onDisplayRemoved() {
        super.onDisplayRemoved()
        onDismissed?.invoke()
    }

    private class BackFrame(ctx: Context) : FrameLayout(ctx) {
        var touchHandler: ((MotionEvent) -> Boolean)? = null

        override fun onTouchEvent(event: MotionEvent): Boolean {
            return touchHandler?.invoke(event) ?: false
        }
    }
}