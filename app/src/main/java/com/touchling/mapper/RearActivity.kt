package com.touchling.mapper

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 背屏镜像 Activity（v0.4.0）
 * 流程：主屏隐形启动 → shell 搬运任务到背屏（am display move-stack）→ 展开全屏开始工作。
 * 功能：灵触映射 / 精密触控板（光标）/ 体感空鼠 / 双指滚动 / 双指轻点=快捷面板
 */
class RearActivity : Activity() {

    companion object {
        @Volatile var lastTaskId = -1
        @Volatile var arrived = false
        @Volatile var alive = false
    }

    private var vdisplay: VirtualDisplay? = null
    private var mapper: TouchMapper? = null
    private var cfg: Cfg? = null
    private var injector: Injector? = null
    private var cursor: View? = null
    private var panel: LinearLayout? = null
    // v0.7.0 背屏互动玩具
    private var toyBox: FrameLayout? = null
    private var curToy = 0
    private var expanded = false
    private var touchCount = 0
    private var mainW = 1200
    private var mainH = 2608

    // 体感
    private var sensorManager: SensorManager? = null
    private var gyroListener: SensorEventListener? = null
    private var lastGyroT = 0L
    private var gx = 0f
    private var gy = 0f

    // 双指
    private var twoActive = false
    private var twoMoved = 0f
    private var twoLastY = 0f
    private var twoLastScrollT = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diag.init(applicationContext)
        Diag.log("RearActivity onCreate display=${display?.displayId} task=$taskId")
        lastTaskId = taskId
        arrived = false
        alive = true

        // 主屏阶段：全屏但全透明+不可触摸（保证 WM 视为可见，避免小窗被忽略）
        val wl = window.attributes
        wl.alpha = 0f
        window.attributes = wl
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )

        val dm = getSystemService(DisplayManager::class.java)
        val main = dm.getDisplay(Display.DEFAULT_DISPLAY)
        mainW = main.mode.physicalWidth
        mainH = main.mode.physicalHeight
        val dpi = applicationContext.resources.displayMetrics.densityDpi

        cfg = Cfg.load(this)
        injector = MirrorService.injectorInstance
        Diag.log("注入器=${injector?.javaClass?.simpleName ?: "null"} gyro=${cfg?.gyro} scroll2=${cfg?.scroll2}")
val frame = BackFrame(this)

        // v0.8.0 HTML 主题模式：WebView 渲染（内置示例 / AI 生成）
        val htmlMode = cfg?.htmlTheme ?: 0
        if (htmlMode != 0) {
            val prefName = getSharedPreferences("cfg", MODE_PRIVATE)
                .getString("aiThemeFile", "") ?: ""
            val aiFile = if (prefName.isNotBlank()) {
                java.io.File(filesDir, "themes/$prefName")
            } else {
                java.io.File(filesDir, "themes/ai.html")
            }
            val wv = HtmlThemeView(this, htmlMode, aiFile)
            frame.addView(wv, FrameLayout.LayoutParams(-1, -1))
            // 右上角退出按钮（v1.3.0）
            frame.addView(Button(this).apply {
                text = "退出"
                textSize = 12f
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = (16 * resources.displayMetrics.density).toFloat()
                    setColor(0x88000000.toInt())
                }
                setOnClickListener { MirrorService.stop(this@RearActivity) }
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = (12 * resources.displayMetrics.density).toInt()
                rightMargin = (12 * resources.displayMetrics.density).toInt()
            })
            setContentView(frame)
            applyWindowMode()
            Diag.log("HTML主题模式=$htmlMode")
            return
        }


        // v0.7.0 背屏互动玩具：选了玩具则跳过映射流程（纯本地互动，不注入主屏）
        val toyId = cfg?.toy ?: 0
        if (toyId != 0) {
            toyBox = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
            frame.addView(toyBox, FrameLayout.LayoutParams(-1, -1))
            buildToy(toyId)
            frame.addView(Button(this).apply {
                text = "切换玩具"
                textSize = 12f
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = (16 * resources.displayMetrics.density).toFloat()
                    setColor(0x88000000.toInt())
                }
                setOnClickListener { cycleToy() }
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = (14 * resources.displayMetrics.density).toInt()
                rightMargin = (14 * resources.displayMetrics.density).toInt()
            })
            // 玩具自己处理触摸（子 View 正常分发），不注入主屏
            frame.touchHandler = null
            setContentView(frame)
            applyWindowMode()
            Diag.log("背屏玩具模式 toy=$toyId")
            return
        }

        val sv = SurfaceView(this)
        frame.addView(sv, FrameLayout.LayoutParams(-1, -1))

        // 背屏显示样式（v0.6.0）：0镜像 / 1纯黑 / 2网格
        // v2.0.0：免投屏模式下强制纯黑（背屏不需要显示主屏画面，省电且不烧屏）
        val rearBg = if (MirrorService.noProjection) 1 else (cfg?.rearBg ?: 0)
        Diag.log("背屏底色模式=$rearBg (noProjection=${MirrorService.noProjection})")
        if (rearBg != 0) {
            sv.visibility = View.GONE
            frame.setBackgroundColor(Color.BLACK)
            if (rearBg == 2) {
                frame.addView(GridView(this), FrameLayout.LayoutParams(-1, -1))
            }
        }

        val mask = View(this).apply {
            setBackgroundColor(Color.BLACK)
            alpha = (cfg?.mask ?: 0) / 100f
        }
        frame.addView(mask, FrameLayout.LayoutParams(-1, -1))

        // 光标（圆形 + 半透明）
        val cs = (22 * resources.displayMetrics.density).toInt()
        cursor = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xAAFFFFFF.toInt())
                setStroke((2 * resources.displayMetrics.density).toInt(), 0xFF007AFF.toInt())
            }
            visibility = View.GONE
        }
        frame.addView(cursor, FrameLayout.LayoutParams(cs, cs, Gravity.TOP or Gravity.START))

        // 快捷面板（默认隐藏）
        panel = buildPanel()
        frame.addView(
            panel,
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = (24 * resources.displayMetrics.density).toInt()
            }
        )

        // 触摸分发
        frame.touchHandler = { e ->
            if (touchCount < 10) {
                Diag.log("触摸#$touchCount p=${e.pointerCount} act=${e.actionMasked}")
                touchCount++
            }
            when {
                e.pointerCount >= 2 && (cfg?.scroll2 ?: true) -> twoFinger(e)
                (cfg?.gyro ?: false) -> gyroTouch(e)
                else -> mapper?.handle(e, frame) ?: false
            }
        }

        setupMapper(frame)
        setupGyro(frame)

        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if ((cfg?.rearBg ?: 0) != 0) {
                    Diag.log("背屏样式=${cfg?.rearBg}（非镜像）→ 跳过 VirtualDisplay")
                    return
                }
                val proj = MirrorService.projection ?: run {
                    Diag.log("surfaceCreated 但 projection=null")
                    return
                }
                vdisplay = proj.createVirtualDisplay(
                    "touchling_rear", mainW, mainH, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    holder.surface, null, null
                )
                Diag.log("VirtualDisplay=${vdisplay != null}")
            }

            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {}
        })

        setContentView(frame)
        applyWindowMode()
    }

    private fun buildToy(id: Int) {
        val box = toyBox ?: return
        box.removeAllViews()
        val v = Toys.build(this, id)
        if (v != null) {
            box.addView(v, FrameLayout.LayoutParams(-1, -1))
            Diag.log("玩具=${Toys.names.getOrElse(id) { "?" }}")
        }
        curToy = id
    }

    private fun cycleToy() {
        var next = curToy + 1
        if (next > Toys.names.size - 1) next = 1
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putInt("toy", next).apply()
        buildToy(next)
        try {
            android.widget.Toast.makeText(
                this, "已切换到：${Toys.names.getOrElse(next) { "?" }}", android.widget.Toast.LENGTH_SHORT
            ).show()
        } catch (_: Throwable) {
        }
    }

    private fun setupMapper(frame: BackFrame) {
        // v2.2.0：evdev 直读模式下，背屏视图只吞掉触摸（防系统误触），不参与映射
        if (cfg?.evdev == true) {
            Diag.log("evdev 直读模式：背屏视图仅吞触摸，输入由内核直读驱动")
            frame.touchHandler = { true }
            return
        }
        val inj = injector ?: return
        mapper = TouchMapper(inj, cfg!!, mainW, mainH) { x, y, visible -> moveCursorTo(x, y, visible) }
    }

    private fun moveCursorTo(x: Float, y: Float, visible: Boolean) {
        // v0.5.0：光标优先画在【主屏】（悬浮窗）——背屏当触控板，眼睛看主屏
        val sink = MirrorService.cursorSink
        if (sink != null) {
            sink.invoke(x, y)
            // 主屏已有光标，背屏内光标不再显示（避免"重影"）
            cursor?.visibility = View.INVISIBLE
            return
        }
        // 回退：无悬浮窗权限时，光标画在背屏镜像内
        val c = cursor ?: return
        val fw = c.parent?.let { (it as View).width } ?: 1
        val fh = c.parent?.let { (it as View).height } ?: 1
        val lp = c.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = (x / mainW * fw).toInt() - c.width / 2
        lp.topMargin = (y / mainH * fh).toInt() - c.height / 2
        c.layoutParams = lp
        if (visible) c.visibility = View.VISIBLE
    }

    // ---------- 双指：滚动 / 轻点开面板 ----------
    private fun twoFinger(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE -> {
                if (!twoActive) {
                    twoActive = true
                    twoMoved = 0f
                    twoLastY = e.getY(0)
                }
                val y = e.getY(0)
                val dy = y - twoLastY
                twoLastY = y
                twoMoved += abs(dy)
                val t = SystemClock.uptimeMillis()
                if (abs(dy) > 12 && t - twoLastScrollT > 140) {
                    twoLastScrollT = t
                    val step = if (dy < 0) 260 else -260
                    val cx = mainW / 2
                    val cy = mainH / 2
                    injector?.exec(
                        "/system/bin/input swipe $cx $cy $cx ${(cy + step).coerceIn(50, mainH - 50)} 90"
                    )
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                if (twoActive && twoMoved < 24f) togglePanel()
                twoActive = false
            }
        }
        return true
    }

    // ---------- 体感空鼠 ----------
    private fun setupGyro(@Suppress("UNUSED_PARAMETER") frame: BackFrame) {
        if (cfg?.gyro != true) return
        val sm = getSystemService(SensorManager::class.java)
        sensorManager = sm
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        gx = mainW / 2f
        gy = mainH / 2f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(ev: SensorEvent) {
                if (!expanded) return
                val t = SystemClock.uptimeMillis()
                if (t - lastGyroT < 40) return
                lastGyroT = t
                // values: 0=pitch轴 1=roll轴 2=yaw轴（支持左右/上下反转 + 死区 + 校准）
                val ix = if (cfg?.invX == true) -1f else 1f
                val iy = if (cfg?.invY == true) -1f else 1f
                val gz = cfg?.deadZone ?: 0f
                val calX = cfg?.gyroCalX ?: 0f
                val calY = cfg?.gyroCalY ?: 0f
                val rvX = ev.values[2] - calX
                val rvY = ev.values[0] - calY
                if (kotlin.math.abs(rvX) < gz && kotlin.math.abs(rvY) < gz) return
                val dx = -rvX * 14f * ix
                val dy = -rvY * 14f * iy
                gx = (gx + dx).coerceIn(0f, mainW - 1f)
                gy = (gy + dy).coerceIn(0f, mainH - 1f)
                moveCursorTo(gx, gy, true)
            }

            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        gyroListener = listener
        sm.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
    }

    /** 体感模式下的触摸：轻点=在光标处点击；拖动=继续移动光标 */
    private fun gyroTouch(e: MotionEvent): Boolean {
        val inj = injector ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gDownX = e.x; gDownY = e.y; gMoved = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                gx = (gx + (e.x - gDownX) * 1.2f * (if (cfg?.invX == true) -1f else 1f)).coerceIn(0f, mainW - 1f)
                gy = (gy + (e.y - gDownY) * 1.2f * (if (cfg?.invY == true) -1f else 1f)).coerceIn(0f, mainH - 1f)
                gMoved += hypot(e.x - gDownX, e.y - gDownY)
                gDownX = e.x; gDownY = e.y
                moveCursorTo(gx, gy, true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (gMoved < 20f) inj.tap(gx, gy)
                moveCursorTo(gx, gy, true)
            }
        }
        return true
    }

    private var gDownX = 0f
    private var gDownY = 0f
    private var gMoved = 0f

    // ---------- 快捷面板 ----------
    private fun buildPanel(): LinearLayout {
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = (22 * resources.displayMetrics.density).toFloat()
                setColor(0xCC1C1C1E.toInt())
            }
            val pad = (8 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            visibility = View.GONE
        }
        fun addBtn(label: String, key: Int) {
            p.addView(Button(this).apply {
                text = label
                textSize = 13f
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = (16 * resources.displayMetrics.density).toFloat()
                    setColor(0xFF2C2C2E.toInt())
                }
                setOnClickListener {
                    injector?.key(key)
                    panel?.visibility = View.GONE
                }
            })
        }
        addBtn("返回", 4)
        addBtn("主页", 3)
        addBtn("任务", 187)
        addBtn("截图", 120)
        addBtn("音量-", 25)
        addBtn("音量+", 24)
        return p
    }

    private fun togglePanel() {
        val p = panel ?: return
        p.visibility = if (p.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        Diag.log("快捷面板 ${if (p.visibility == View.VISIBLE) "显示" else "隐藏"}")
    }

    // ---------- 窗口形态 ----------
    private fun applyWindowMode() {
        val onRear = (display?.displayId ?: 0) != Display.DEFAULT_DISPLAY
        if (onRear && !expanded) {
            expanded = true
            arrived = true
            Diag.log("已抵达背屏 display=${display?.displayId} → 展开")
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
            val wl = window.attributes
            wl.alpha = 1f
            window.attributes = wl
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
        Diag.log("onConfigChanged display=${display?.displayId}")
        // v1.3.0：更新"是否在背屏"标志，供服务端看门狗自愈
        arrived = (display?.displayId ?: 0) != 0
        applyWindowMode()
    }

    override fun onDestroy() {
        super.onDestroy()
        alive = false
        try { gyroListener?.let { sensorManager?.unregisterListener(it) } } catch (_: Throwable) {}
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