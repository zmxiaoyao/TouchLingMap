package com.touchling.mapper

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import java.io.InputStream

/**
 * v2.2.0 evdev 直读背屏触摸（内核层直读原理）
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

    fun start(devicePath: String, w: Int, h: Int, grabPath: String? = null) {
        if (running) return
        device = devicePath
        maxX = w
        maxY = h
        running = true
        thread = Thread {
            onLog("evdev 线程启动 device=$devicePath ($w x $h) 独占=${grabPath != null}")
            try {
                var sp: SpawnedProcess? = null
                var br: java.io.BufferedReader? = null

                // v2.4.0：独占背屏触摸（EVIOCGRAB）—— 失败自动回退 getevent 监听
                if (grabPath != null) {
                    // v2.4.9修订：加 exec —— sh 直接替换为目标进程（进程名=libgrab.so），
                    // 关流/杀进程时不会留下"sh 壳 + 孤儿 grab"结构（历史 EBUSY 根源之一）
                    val g = spawn("exec '$grabPath' '$devicePath'")
                    if (g != null) {
                        val gbr = g.stream.bufferedReader()
                        val first = gbr.readLine()
                        if (first == "G_READY") {
                            sp = g
                            br = gbr
                            onLog("独占背屏触摸已接管（EVIOCGRAB，其他应用收不到背屏触摸）")
                        } else {
                            onLog("独占失败: ${first ?: "无响应"} → 回退 getevent 监听")
                            try {
                                g.close()
                            } catch (_: Throwable) {
                            }
                        }
                    } else {
                        onLog("独占: 无法创建进程 → 回退 getevent 监听")
                    }
                }

                if (br == null) {
                    // v2.4.9修订：加 exec（同上，避免 getevent 孤儿进程残留）
                    sp = spawn("exec /system/bin/getevent -lt '$devicePath'")
                    if (sp == null) {
                        onLog("evdev: 无法创建进程（通道不支持 spawn）")
                        running = false
                        return@Thread
                    }
                    br = sp.stream.bufferedReader()
                }
                spawned = sp
                val fw = ((w + 1) / 100).coerceAtLeast(1)
                val fh = ((h + 1) / 100).coerceAtLeast(1)
                fakeView = View(ctx).apply { layout(0, 0, fw, fh) }
                var pendX = -1f
                var pendY = -1f
                var wantDown = false
                var down = false // v2.4.6：当前是否已发出 DOWN（状态机自动补齐，兼容不标准触摸协议）
                var justUp = false // v2.4.9修订：抬手抑制——UP之后的残留帧不再"自动补 DOWN"（防悬空按压）
                val reader = br
                while (running) {
                    val line = reader.readLine() ?: break
                    when {
                        // ---- grab 工具协议（十进制，首字符区分） ----
                        line.startsWith("X ") -> {
                            val v = line.substring(2).trim().toFloatOrNull() ?: -1f
                            if (v != pendX) { pendX = v; justUp = false } // 坐标变化 = 新触摸开始
                        }
                        line.startsWith("Y ") -> {
                            val v = line.substring(2).trim().toFloatOrNull() ?: -1f
                            if (v != pendY) { pendY = v; justUp = false }
                        }
                        line == "B 1" -> {
                            justUp = false // 明确的按下信号
                            if (pendX >= 0 && pendY >= 0) {
                                if (!down) {
                                    emit(MotionEvent.ACTION_DOWN, pendX, pendY)
                                    down = true
                                }
                            } else {
                                wantDown = true
                            }
                        }
                        line == "B 0" -> {
                            wantDown = false
                            if (down) {
                                if (pendX >= 0 && pendY >= 0) {
                                    emit(MotionEvent.ACTION_UP, pendX, pendY)
                                }
                                down = false
                            }
                            justUp = true // 抬手后进入抑制态（防残留帧幽灵 DOWN）
                        }
                        line == "S" -> {
                            if (pendX >= 0 && pendY >= 0) {
                                if (down) {
                                    emit(MotionEvent.ACTION_MOVE, pendX, pendY)
                                } else if (wantDown || !justUp) {
                                    // v2.4.6：若设备不发 B 事件，第一帧 SYN 时自动补一个 DOWN
                                    // v2.4.9修订：UP 之后的残留帧（justUp）不再补，防"悬空按压"
                                    wantDown = false
                                    emit(MotionEvent.ACTION_DOWN, pendX, pendY)
                                    down = true
                                }
                            }
                        }
                        // ---- getevent 协议（十六进制） ----
                        line.contains("ABS_MT_POSITION_X") -> {
                            val v = hexOf(line)
                            if (v >= 0 && v.toFloat() != pendX) { pendX = v.toFloat(); justUp = false }
                        }
                        line.contains("ABS_MT_POSITION_Y") -> {
                            val v = hexOf(line)
                            if (v >= 0 && v.toFloat() != pendY) { pendY = v.toFloat(); justUp = false }
                        }
                        line.contains("BTN_TOUCH") && line.contains("DOWN") -> {
                            justUp = false
                            if (pendX >= 0 && pendY >= 0 && !down) {
                                emit(MotionEvent.ACTION_DOWN, pendX, pendY)
                                down = true
                            }
                        }
                        line.contains("BTN_TOUCH") && line.contains("UP") -> {
                            if (down) {
                                if (pendX >= 0 && pendY >= 0) {
                                    emit(MotionEvent.ACTION_UP, pendX, pendY)
                                }
                                down = false
                            }
                            justUp = true
                        }
                        // v2.4.9修订：TRACKING_ID —— 现代触摸屏"只发 TRACKING_ID、不发 BTN_TOUCH"时
                        // 抬手/按下的关键信号（ffffffff = 抬手）
                        line.contains("ABS_MT_TRACKING_ID") -> {
                            val last = line.trim().split(Regex("\\s+")).lastOrNull() ?: ""
                            if (last.equals("ffffffff", true)) {
                                if (down) {
                                    if (pendX >= 0 && pendY >= 0) {
                                        emit(MotionEvent.ACTION_UP, pendX, pendY)
                                    }
                                    down = false
                                }
                                justUp = true
                            } else {
                                justUp = false // 新触点 ID = 新触摸开始
                            }
                        }
                        line.contains("SYN_REPORT") -> {
                            if (pendX >= 0 && pendY >= 0) {
                                if (down) {
                                    emit(MotionEvent.ACTION_MOVE, pendX, pendY)
                                } else if (!justUp) {
                                    // v2.4.9修订：与 grab 路径统一 —— 首帧自动补 DOWN，UP 后残留帧不再补
                                    emit(MotionEvent.ACTION_DOWN, pendX, pendY)
                                    down = true
                                }
                            }
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