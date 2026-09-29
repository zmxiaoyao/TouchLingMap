package com.touchling.mapper

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import java.io.InputStream

/**
 * v2.2.0 evdev 直读背屏触摸（参考「妙妙背屏」原理）
 *
 * 通过 getevent -lt /dev/input/eventN 流式读取内核触摸事件，
 * 解析 ABS_MT_POSITION_X/Y + BTN_TOUCH，合成 MotionEvent 喂给 TouchMapper，
 * 于是**背屏不需要任何 Activity 接管触摸**（可保持黑屏/息屏态点亮）。
 *
 * 优势：不依赖投屏、不依赖背屏 Activity、延迟低（内核直读）。
 */
class EvdevTouch(
    private val ctx: Context,
    private val mapper: TouchMapper,
    private val spawn: (String) -> SpawnedProcess?,
    private val onLog: (String) -> Unit
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var spawned: SpawnedProcess? = null
    private var fakeView: View? = null
    private var device: String? = null
    private var maxX = 97599
    private var maxY = 59599

    fun start(devicePath: String, w: Int, h: Int) {
        if (running) return
        device = devicePath
        maxX = w
        maxY = h
        running = true
        thread = Thread {
            onLog("evdev 线程启动 device=$devicePath ($w x $h)")
            try {
                val sp = spawn("/system/bin/getevent -lt $devicePath")
                if (sp == null) {
                    onLog("evdev: 无法创建进程（通道不支持 spawn）")
                    running = false
                    return@Thread
                }
                spawned = sp
                val fw = ((w + 1) / 100).coerceAtLeast(1)
                val fh = ((h + 1) / 100).coerceAtLeast(1)
                fakeView = View(ctx).apply { layout(0, 0, fw, fh) }
                var pendX = -1f
                var pendY = -1f
                val br = sp.stream.bufferedReader()
                while (running) {
                    val line = br.readLine() ?: break
                    when {
                        line.contains("ABS_MT_POSITION_X") -> {
                            val v = hexOf(line)
                            if (v >= 0) pendX = v
                        }
                        line.contains("ABS_MT_POSITION_Y") -> {
                            val v = hexOf(line)
                            if (v >= 0) pendY = v
                        }
                        line.contains("BTN_TOUCH") && line.contains("DOWN") -> {
                            if (pendX >= 0 && pendY >= 0) emit(
                                MotionEvent.ACTION_DOWN, pendX, pendY
                            )
                        }
                        line.contains("BTN_TOUCH") && line.contains("UP") -> {
                            if (pendX >= 0 && pendY >= 0) emit(
                                MotionEvent.ACTION_UP, pendX, pendY
                            )
                        }
                        line.contains("SYN_REPORT") -> {
                            if (pendX >= 0 && pendY >= 0) emit(
                                MotionEvent.ACTION_MOVE, pendX, pendY
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                if (running) onLog("evdev 线程异常: $t")
            } finally {
                running = false
                onLog("evdev 线程结束")
            }
        }.apply {
            isDaemon = true
            name = "evdev-touch"
            start()
        }
    }

    fun stop() {
        running = false
        try {
            spawned?.close()
        } catch (_: Throwable) {
        }
        spawned = null
        thread = null
        onLog("evdev 已停止")
    }

    private fun emit(action: Int, px: Float, py: Float) {
        val fv = fakeView ?: return
        val x = (px / maxX * fv.width).coerceIn(0f, fv.width - 1f)
        val y = (py / maxY * fv.height).coerceIn(0f, fv.height - 1f)
        val ev = MotionEvent.obtain(
            SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action, x, y, 0
        )
        try {
            mapper.handle(ev, fv)
        } catch (t: Throwable) {
            onLog("evdev 处理异常: $t")
        } finally {
            ev.recycle()
        }
    }

    /** 从 getevent 行里取末尾的十六进制数值 */
    private fun hexOf(line: String): Int {
        val parts = line.trim().split(Regex("\\s+"))
        val last = parts.lastOrNull() ?: return -1
        return try {
            if (last.startsWith("DOWN") || last.startsWith("UP")) -1
            else Integer.parseInt(last, 16)
        } catch (_: Throwable) {
            -1
        }
    }

    companion object {
        /**
         * 从 getevent -lp 输出里找出匹配背屏分辨率的触摸设备。
         * @param run injector.exec 执行器
         * @param targetX 背屏物理宽×100 - 1（如 976*100-1=97599）
         */
        fun detectDevice(run: (String) -> String, targetX: Int): String? {
            return try {
                val out = run("getevent -lp")
                if (out.isEmpty() || out.contains("SHIZUKU_DOWN")) return null
                var path: String? = null
                var best: String? = null
                for (raw in out.lines()) {
                    val t = raw.trim()
                    if (t.startsWith("add device")) {
                        val i = t.indexOf("/dev/input/")
                        path = if (i >= 0) t.substring(i).trim() else null
                    }
                    if (path != null && t.startsWith("ABS_MT_POSITION_X")) {
                        val mx = Regex("max\\s+(\\d+)").find(t)
                            ?.groupValues?.get(1)?.toIntOrNull()
                        if (mx != null && kotlin.math.abs(mx - targetX) < 2000) {
                            best = path
                        }
                    }
                }
                best
            } catch (t: Throwable) {
                null
            }
        }
    }
}