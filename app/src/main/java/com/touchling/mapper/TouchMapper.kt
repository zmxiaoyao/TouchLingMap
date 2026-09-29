package com.touchling.mapper

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
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
    val rearBg: Int,
    val toy: Int,
    val htmlTheme: Int,
    // v2.0.0 免投屏触控 + 手感参数
    val noMirror: Boolean,
    val smoothMs: Int,
    val deadZone: Float,
    val cursorDp: Int,
    val rearRot: Int,
    val gyroCalX: Float,
    val gyroCalY: Float,
    val rearDisplayId: Int,
    // v2.2.0 自动化
    val evdev: Boolean,
    val bootAuto: Boolean,
    val autoApp: Boolean,
    val autoApps: String,
    val ballOn: Boolean,
    // v2.3.0 手势映射参数
    val gThreshold: Float, // 识别阈值 0.05~0.35，默认 0.12
    val gSwipeLen: Float, // 滑动距离（占屏幕比例）0.15~0.75，默认 0.46
    val gSwipeMs: Int, // 滑动时长 120~650ms，默认 260
    val gTapX: Float, // 点击 X（归一化）默认 0.5
    val gTapY: Float, // 点击 Y（归一化）默认 0.5
    val gInvert: Boolean, // 手势上下反转
    // v2.4.0 手感对齐 + 独占背屏触摸
    val capture: Boolean, // 独占背屏触摸（EVIOCGRAB 内核层拦截）
    val padSens: Float // 触控板速度 0.3~3.0 默认 1.4
) {
    /** 平滑系数：把"平滑时间(ms)"换算成每帧插值比例 */
    val smoothFactor: Float
        get() = if (smoothMs <= 0) 1f else (16f / smoothMs.toFloat()).coerceIn(0.08f, 1f)

    companion object {
        /** v2.2.2：带类型安全的读取（单项损坏只回退默认值，不再让整个进程崩掉） */
        fun load(ctx: Context): Cfg {
            val sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            var corrupted = false
            fun b(k: String, d: Boolean) = try {
                sp.getBoolean(k, d)
            } catch (_: Throwable) { corrupted = true; d }
            fun i(k: String, d: Int) = try {
                sp.getInt(k, d)
            } catch (_: Throwable) { corrupted = true; d }
            fun f(k: String, d: Float) = try {
                sp.getFloat(k, d)
            } catch (_: Throwable) { corrupted = true; d }
            fun s(k: String, d: String) = try {
                sp.getString(k, d) ?: d
            } catch (_: Throwable) { corrupted = true; d }

            // v2.4.1：手势映射模式已下线，历史配置自动迁移回灵触映射
            val mode = s("mode", "direct").let { if (it == "gesture") "direct" else it }
            // 平滑：smoothing（秒，默认 0.045）与旧 smoothMs（ms）兼容
            val smoothSec = f("smoothing", -1f)
            val smoothMsV =
                if (smoothSec >= 0f) (smoothSec * 1000f + 0.5f).toInt() else i("smoothMs", 0)
            val cfg = Cfg(
                mode,
                // v2.4.0 手感参数：key/范围/默认值与主流触控方案完全一致
                f("sensitivity", 1.5f),
                i("mask", 0),
                s("channel", "auto"),
                // v2.1.0：模式=体感光标 时强制开启体感
                b("gyro", false) || mode == "gyro",
                b("scroll2", true),
                b("invertX", false),
                b("invertY", false),
                i("cursorStyle", 2),
                i("cursorSizeIdx", 1),
                i("cursorColor", 0),
                i("rearBg", 0),
                i("toy", 0),
                i("htmlTheme", 0),
                b("noMirror", true),
                smoothMsV,
                f("deadzone", 0.018f),
                i("cursorSize", 32),
                i("rearRot", 0),
                f("gyroCalX", 0f),
                f("gyroCalY", 0f),
                i("rearDisplayId", -1),
                b("evdev", true),
                b("bootAuto", false),
                b("autoApp", false),
                s("autoApps", ""),
                b("ballOn", false),
                // v2.4.0 手势参数（默认值 = 主流方案默认）
                f("threshold", 0.12f),
                f("swipeLength", 0.46f),
                i("swipeMs", 260),
                f("tapX", 0.5f),
                f("tapY", 0.5f),
                b("invertSwipe", false),
                b("capture", true),
                f("touchpad", 1.4f)
            )
            if (corrupted) Diag.log("Cfg.load: 存在类型损坏的配置项，已回退默认值")
            return cfg
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
    private val onCursor: (Float, Float, Boolean) -> Unit,
    // v2.4.5：体感光标当前位置读取（服务级陀螺仪驱动，-1 表示未初始化）
    private val gyroPos: () -> Pair<Float, Float> = { -1f to -1f }
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

    // v2.0.0 平滑滤波累加量
    private var smoothX = 0f
    private var smoothY = 0f

    private val longPressRunnable = Runnable {
        if (!moved && !longPressFired) {
            longPressFired = true
            dragging = true
            dragX = cx
            dragY = cy
            emitDown(dragX, dragY)
            onCursor(cx, cy, true)
        }
    }

    fun handle(e: MotionEvent, view: View): Boolean {
        return when (cfg.mode) {
            "gyro" -> gyroTouch(e, view) // v2.4.6：体感光标模式（陀螺仪管移动，触摸轻点=点击、拖动=移光标）
            "pad" -> pad(e, view)
            "gesture" -> gesture(e, view) // v2.3.0 手势映射
            else -> direct(e, view)
        }
    }

    // ================= v2.3.0 手势映射 =================
    // 识别：DOWN 记起点 → MOVE 记最大位移 hypot（归一化）→ UP 判定
    //   位移 >= threshold → 滑动手势（主轴定方向）→ 注入系统级 input -d 0 swipe（中心±swipeLen/2, swipeMs）
    //   否则短按 → input -d 0 tap（tapX, tapY）；长按(>=700ms) → 同点长 swipe
    // 注入带冷却（上一手势执行中忽略新手势）（冷却期防重入）
    private var gDown = false
    private var gSX = 0f
    private var gSY = 0f
    private var gMax = 0f
    private var gT0 = 0L
    private var gCool = 0L

    private fun gesture(e: MotionEvent, view: View): Boolean {
        val bw = view.width.toFloat().coerceAtLeast(1f)
        val bh = view.height.toFloat().coerceAtLeast(1f)
        var nx = e.x / bw
        var ny = e.y / bh
        val rot = cfg.rearRot
        if (rot == 90) {
            val t = nx; nx = 1f - ny; ny = t
        } else if (rot == 180) {
            nx = 1f - nx; ny = 1f - ny
        } else if (rot == 270) {
            val t = nx; nx = ny; ny = 1f - t
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gDown = true; gSX = nx; gSY = ny; gMax = 0f; gT0 = now()
            }
            MotionEvent.ACTION_MOVE -> if (gDown) {
                gMax = maxOf(gMax, hypot(nx - gSX, ny - gSY))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!gDown) return true
                gDown = false
                if (now() < gCool) return null2()
                val dur = now() - gT0
                val dx = nx - gSX
                val dy = ny - gSY
                when {
                    gMax >= cfg.gThreshold -> {
                        if (abs(dx) >= abs(dy)) {
                            injectSwipe(if (dx > 0) "right" else "left")
                        } else {
                            val downWard = if (cfg.gInvert) dy <= 0 else dy > 0
                            injectSwipe(if (downWard) "down" else "up")
                        }
                    }
                    dur >= 700 -> {
                        // 长按：同点长 swipe（系统识别为长按）
                        val x = (mainW * cfg.gTapX).toInt().coerceIn(1, mainW - 2)
                        val y = (mainH * cfg.gTapY).toInt().coerceIn(1, mainH - 2)
                        injector.send("/system/bin/input -d 0 swipe $x $y $x $y 800")
                        gCool = now() + 900
                    }
                    else -> {
                        val x = (mainW * cfg.gTapX).toInt().coerceIn(1, mainW - 2)
                        val y = (mainH * cfg.gTapY).toInt().coerceIn(1, mainH - 2)
                        injector.send("/system/bin/input -d 0 tap $x $y")
                        gCool = now() + 120
                    }
                }
            }
        }
        return true
    }

    private fun null2(): Boolean = true

    /** 注入系统级滑动：起点/终点 = 主屏中心 ± swipeLen/2（clamp 到 6%~94% 屏内） */
    private fun injectSwipe(dir: String) {
        val w = mainW.toFloat()
        val h = mainH.toFloat()
        val horiz = dir == "left" || dir == "right"
        val half = (cfg.gSwipeLen * if (horiz) w else h) / 2f
        val cx = w / 2f
        val cy = h / 2f
        val x1: Int
        val y1: Int
        val x2: Int
        val y2: Int
        if (horiz) {
            val c = cx.coerceIn(0.06f * w + half, 0.94f * w - half)
            val a = (if (dir == "right") c - half else c + half).toInt().coerceIn(1, mainW - 2)
            val b = (if (dir == "right") c + half else c - half).toInt().coerceIn(1, mainW - 2)
            x1 = a; y1 = cy.toInt(); x2 = b; y2 = cy.toInt()
        } else {
            val c = cy.coerceIn(0.06f * h + half, 0.94f * h - half)
            val a = (if (dir == "down") c - half else c + half).toInt().coerceIn(1, mainH - 2)
            val b = (if (dir == "down") c + half else c - half).toInt().coerceIn(1, mainH - 2)
            x1 = cx.toInt(); y1 = a; x2 = cx.toInt(); y2 = b
        }
        injector.send("/system/bin/input -d 0 swipe $x1 $y1 $x2 $y2 ${cfg.gSwipeMs}")
        gCool = now() + cfg.gSwipeMs + 60
        Diag.log("手势注入: $dir swipe($x1,$y1→$x2,$y2,${cfg.gSwipeMs}ms)")
    }

    /** 灵触映射：背屏 → 主屏 直接压缩映射（支持背屏方向旋转） */
    private fun direct(e: MotionEvent, view: View): Boolean {
        val bw = view.width.toFloat().coerceAtLeast(1f)
        val bh = view.height.toFloat().coerceAtLeast(1f)
        var nx = e.x / bw
        var ny = e.y / bh
        // 背屏方向（0/90/180/270）坐标旋转
        val rot = cfg.rearRot
        if (rot == 90) {
            val t = nx; nx = 1f - ny; ny = t
        } else if (rot == 180) {
            nx = 1f - nx; ny = 1f - ny
        } else if (rot == 270) {
            val t = nx; nx = ny; ny = 1f - t
        }
        // 灵敏度（0.2~4.0，默认 1.5 → 缩放系数 1.0，围绕屏幕中心缩放有效区）
        val k = cfg.sens / 1.5f
        val x = ((nx - 0.5f) * mainW * k + mainW * 0.5f).coerceIn(0f, mainW - 1f)
        var y = ((ny - 0.5f) * mainH * k + mainH * 0.5f).coerceIn(0f, mainH - 1f)
        // v2.4.3：上下反转（手势参数 invertSwipe 即时作用于灵触映射）
        if (cfg.gInvert) y = mainH - 1f - y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastSendX = x; lastSendY = y; lastSendT = now()
                emitDown(x, y) // v2.4.8：补上统计包装（此前漏包装导致"注入统计 D=0"误报）
            }
            MotionEvent.ACTION_MOVE -> {
                val t = now()
                // v2.4.4：桥通道零 fork 开销 → 全帧率注入（16ms≈60fps）；仅回退 input 命令时保留节流。
                // 抖音等对手势轨迹密度敏感的应用需要连续事件流
                if (!Bridge.ok || hypot(x - lastSendX, y - lastSendY) >= 3f || t - lastSendT >= 16) {
                    emitMove(x, y)
                    lastSendX = x; lastSendY = y; lastSendT = t
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> emitUp(x, y)
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
                // v2.0.0 平滑：低通滤波，减小抖动（0ms = 不滤波）
                val a = cfg.smoothFactor
                smoothX += ((e.x - lastX) * ix - smoothX) * a
                smoothY += ((e.y - lastY) * iy - smoothY) * a
                val dx = smoothX
                val dy = smoothY
                lastX = e.x; lastY = e.y
                // v2.4.5：点击判定阈值改用「识别阈值×屏宽」（默认 0.12 → 约背屏宽 12%，手指微动不再误判为拖动）
                if (!longPressFired && hypot(e.x - downX, e.y - downY) > cfg.gThreshold * view.width) {
                    moved = true
                    view.removeCallbacks(longPressRunnable)
                }
                if (dragging) {
                    dragX = (dragX + dx * cfg.padSens).coerceIn(0f, mainW - 1f)
                    dragY = (dragY + dy * cfg.padSens).coerceIn(0f, mainH - 1f)
                    emitMove(dragX, dragY)
                    cx = dragX; cy = dragY
                } else if (moved) {
                    cx = (cx + dx * cfg.padSens).coerceIn(0f, mainW - 1f)
                    cy = (cy + dy * cfg.padSens).coerceIn(0f, mainH - 1f)
                }
                onCursor(cx, cy, true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.removeCallbacks(longPressRunnable)
                if (dragging) {
                    emitUp(dragX, dragY)
                    dragging = false
                } else if (!moved) {
                    injector.tap(cx, cy)
                }
                onCursor(cx, cy, true)
            }
        }
        return true
    }

    // ================= v2.4.4 注入统计（诊断抖音等对手势敏感的场景） =================
    private var statT = 0L
    private var statD = 0
    private var statM = 0
    private var statU = 0

    /** 每秒最多输出一行：通道（桥/回退）+ 事件计数，判断事件到底有没有发出去 */
    private fun stat(kind: Char) {
        when (kind) {
            'D' -> statD++
            'M' -> statM++
            'U' -> statU++
        }
        val t = now()
        if (statT == 0L) {
            statT = t
            return
        }
        if (t - statT >= 1000) {
            if (statD + statM + statU > 0) {
                Diag.log(
                    "注入统计: 通道=${if (Bridge.ok) "桥" else "input命令(回退)"} " +
                        "D=$statD M=$statM U=$statU /s"
                )
            }
            statT = t
            statD = 0
            statM = 0
            statU = 0
        }
    }

    private fun emitDown(x: Float, y: Float) {
        injector.down(x, y)
        stat('D')
    }

    private fun emitMove(x: Float, y: Float) {
        injector.move(x, y)
        stat('M')
    }

    private fun emitUp(x: Float, y: Float) {
        injector.up(x, y)
        stat('U')
    }

        /** v2.4.6：体感光标模式下的触摸（轻点=点击光标处；拖动=移动光标） */
    private var gtMoved = false
    private var gtLastX = 0f
    private var gtLastY = 0f

    private fun gyroTouch(e: MotionEvent, view: View): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                gtMoved = false
                gtLastX = e.x
                gtLastY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(e.x - downX, e.y - downY) > cfg.gThreshold * view.width) {
                    gtMoved = true
                }
                if (gtMoved) {
                    // 拖动=按触摸位移平移光标（位置经 onCursor 同步到服务级光标状态）
                    val p = gyroPos()
                    if (p.first >= 0f && p.second >= 0f) {
                        val nx = (p.first + (e.x - gtLastX) * 1.6f).coerceIn(0f, mainW - 1f)
                        val ny = (p.second + (e.y - gtLastY) * 1.6f).coerceIn(0f, mainH - 1f)
                        onCursor(nx, ny, true)
                    }
                }
                gtLastX = e.x
                gtLastY = e.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!gtMoved) {
                    val p = gyroPos()
                    val px = if (p.first >= 0f) p.first else mainW / 2f
                    val py = if (p.second >= 0f) p.second else mainH / 2f
                    injector.tap(px, py)
                }
            }
        }
        return true
    }

    private fun now() = SystemClock.uptimeMillis()
}