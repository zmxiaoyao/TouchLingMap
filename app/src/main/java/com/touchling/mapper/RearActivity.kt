package com.touchling.mapper

import android.app.Activity
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import java.io.File

/**
 * 背屏镜像 Activity（v0.3.2）
 * HyperOS 禁止直接启动到背屏，改成：
 *   1. 本 Activity 先在主屏以 1x1 隐形启动（服务里 startActivity，允许）
 *   2. 把 taskId 写到 Android/data 供 shell 读取
 *   3. shell 通过 `service call activity_task 50` 把任务搬到背屏
 *   4. 检测到自己在背屏（displayId!=0）→ 全屏 + 点亮 + 可触摸
 */
class RearActivity : Activity() {

    private var vdisplay: VirtualDisplay? = null
    private var mapper: TouchMapper? = null
    private var touchCount = 0
    private var expanded = false
    private var mainW = 1200
    private var mainH = 2608

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diag.init(applicationContext)
        Diag.log("RearActivity onCreate displayId=${display?.displayId} taskId=$taskId")

        // 写 taskId 供 shell 搬运
        try {
            val f = File(getExternalFilesDir(null), "taskid.txt")
            f.writeText(taskId.toString())
            Diag.log("taskId 已写入 ${f.absolutePath}")
        } catch (t: Throwable) {
            Diag.log("写 taskId 失败: $t")
        }

        // 主屏阶段：1x1 隐形、不抢触摸
        window.setLayout(1, 1)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )

        // 主屏尺寸
        val dm = getSystemService(DisplayManager::class.java)
        val main = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val mode = main.mode
        mainW = mode.physicalWidth
        mainH = mode.physicalHeight
        val dpi = applicationContext.resources.displayMetrics.densityDpi

        val cfg = Cfg.load(this)
        val frame = BackFrame(this)

        val sv = SurfaceView(this)
        frame.addView(
            sv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val mask = View(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            alpha = cfg.mask / 100f
        }
        frame.addView(
            mask,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val cursorSize = (26 * resources.displayMetrics.density).toInt()
        val cursor = View(this).apply {
            setBackgroundColor(0xDDFFFFFF.toInt())
            visibility = View.GONE
        }
        frame.addView(
            cursor,
            FrameLayout.LayoutParams(cursorSize, cursorSize, Gravity.TOP or Gravity.START)
        )

        val inj = MirrorService.injectorInstance
        Diag.log("注入器=${inj?.javaClass?.simpleName ?: "null"}")
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
        applyWindowMode()
    }

    /** 根据当前所在显示屏切换窗口形态 */
    private fun applyWindowMode() {
        val onRear = (display?.displayId ?: 0) != Display.DEFAULT_DISPLAY
        if (onRear && !expanded) {
            expanded = true
            Diag.log("已抵达背屏 displayId=${display?.displayId} → 展开全屏")
            window.clearFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            )
            window.addFlags(
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            )
            try { window.setDecorFitsSystemWindows(false) } catch (_: Throwable) {}
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
        }
    }

    override fun onResume() {
        super.onResume()
        applyWindowMode()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Diag.log("onConfigurationChanged displayId=${display?.displayId}")
        applyWindowMode()
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