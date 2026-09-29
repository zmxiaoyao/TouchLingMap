package com.touchling.mapper

import android.app.Activity
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * 背屏镜像 Activity（v0.3.0 核心，参考 Mirror2RearUltra + MRSS）
 * 由服务通过 `am start --display <背屏id>` 启动到背屏上：
 * - 相比 Presentation，Activity 窗口能正常获得显示与输入焦点
 * - FLAG_TURN_SCREEN_ON 点亮背屏 + FLAG_KEEP_SCREEN_ON 保持常亮
 * - SurfaceView 显示 MediaProjection 镜像；接收背屏触摸 → 注入主屏
 */
class RearActivity : Activity() {

    private var vdisplay: VirtualDisplay? = null
    private var mapper: TouchMapper? = null
    private var touchCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diag.init(applicationContext)
        Diag.log("RearActivity onCreate displayId=${display?.displayId}")

        // 点亮并保持背屏常亮（参考 MRSS RearScreenWakeupActivity）
        window.addFlags(
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )
        try { window.setDecorFitsSystemWindows(false) } catch (_: Throwable) {}

        // 主屏尺寸（资源默认跟随主屏；再用 Display mode 兜底）
        val dm = getSystemService(DisplayManager::class.java)
        val main = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val mode = main.mode
        val mainW = mode.physicalWidth
        val mainH = mode.physicalHeight
        val dpi = applicationContext.resources.displayMetrics.densityDpi
        Diag.log("主屏=${mainW}x${mainH}@$dpi 背屏=${display?.name}")

        val cfg = Cfg.load(this)
        val frame = BackFrame(this)

        // 镜像画面
        val sv = SurfaceView(this)
        frame.addView(
            sv,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        // 防烧屏黑遮罩
        val mask = View(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            alpha = cfg.mask / 100f
        }
        frame.addView(
            mask,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        // 触控板光标
        val cursorSize = (26 * resources.displayMetrics.density).toInt()
        val cursor = View(this).apply {
            setBackgroundColor(0xDDFFFFFF.toInt())
            visibility = View.GONE
        }
        frame.addView(
            cursor,
            FrameLayout.LayoutParams(cursorSize, cursorSize, Gravity.TOP or Gravity.START)
        )

        // 触摸映射（注入器来自服务）
        val inj = MirrorService.injectorInstance
        Diag.log("RearActivity 注入器=${inj?.javaClass?.simpleName ?: "null"}")
        if (inj != null) {
            mapper = TouchMapper(inj, cfg, mainW, mainH) { x, y, visible ->
                val bw = frame.width.takeIf { it > 0 } ?: 1
                val bh = frame.height.takeIf { it > 0 } ?: 1
                val lp = cursor.layoutParams as FrameLayout.LayoutParams
                lp.leftMargin = (x / mainW * bw).toInt()
                lp.topMargin = (y / mainH * bh).toInt()
                cursor.layoutParams = lp
                cursor.visibility = if (visible) View.VISIBLE else View.GONE
            }
        }
        frame.touchHandler = { e ->
            if (touchCount < 8) {
                Diag.log("背屏触摸#$touchCount action=${e.actionMasked} x=${e.x} y=${e.y}")
                touchCount++
            }
            mapper?.handle(e, frame) ?: false
        }

        // Surface 就绪 → 从服务的 MediaProjection 创建 VirtualDisplay
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                val proj = MirrorService.projection
                if (proj == null) {
                    Diag.log("surfaceCreated 但 projection 为 null！")
                    return
                }
                vdisplay = proj.createVirtualDisplay(
                    "touchling_rear", mainW, mainH, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    holder.surface, null, null
                )
                Diag.log("VirtualDisplay 创建: ${vdisplay != null}")
            }

            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {}
        })

        setContentView(frame)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { vdisplay?.release() } catch (_: Throwable) {}
        Diag.log("RearActivity onDestroy")
    }

    private class BackFrame(ctx: android.content.Context) : FrameLayout(ctx) {
        var touchHandler: ((MotionEvent) -> Boolean)? = null

        override fun onTouchEvent(event: MotionEvent): Boolean {
            return touchHandler?.invoke(event) ?: false
        }
    }
}