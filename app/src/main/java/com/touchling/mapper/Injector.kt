package com.touchling.mapper

import java.io.InputStream

/**
 * 统一触控注入接口。
 * 两个通道（Root / Shizuku）都持有常驻 shell，逐行执行 input 命令。
 * 命令一律使用绝对路径，避免 shell PATH 差异导致 command not found。
 */
interface Injector {
    fun start(): Boolean
    fun send(cmd: String)
    fun close()

    /** 独立进程执行命令并回读输出（用于搬运任务等关键命令，失败可见） */
    fun exec(cmd: String): String

    /** v2.2.0：启动长驻进程并返回 stdout 流（evdev 直读背屏触摸用） */
    fun spawn(cmd: String): SpawnedProcess? = null

    /** v2.4.0：优先走实时触控桥（app_process 注入，丝滑），失败回退 input 命令 */
    fun down(x: Float, y: Float) {
        val xi = x.toInt()
        val yi = y.toInt()
        if (!Bridge.write("D $xi $yi")) {
            send("/system/bin/input motionevent DOWN $xi $yi")
        }
    }

    fun move(x: Float, y: Float) {
        val xi = x.toInt()
        val yi = y.toInt()
        if (!Bridge.write("M $xi $yi")) {
            send("/system/bin/input motionevent MOVE $xi $yi")
        }
    }

    fun up(x: Float, y: Float) {
        val xi = x.toInt()
        val yi = y.toInt()
        if (!Bridge.write("U $xi $yi")) {
            send("/system/bin/input motionevent UP $xi $yi")
        }
    }

    fun tap(x: Float, y: Float) {
        val xi = x.toInt()
        val yi = y.toInt()
        if (Bridge.ok && Bridge.write("D $xi $yi")) {
            Thread {
                try {
                    Thread.sleep(60)
                } catch (_: Throwable) {
                }
                Bridge.write("U $xi $yi")
            }.apply { isDaemon = true }.start()
            return
        }
        send(
            "/system/bin/input motionevent DOWN $xi $yi; " +
                "/system/bin/sleep 0.05; " +
                "/system/bin/input motionevent UP $xi $yi"
        )
    }

    /** 注入按键（4=返回 3=Home 187=多任务 26=电源） */
    fun key(code: Int) {
        if (!Bridge.write("K $code 0")) {
            send("/system/bin/input keyevent $code")
        }
    }

    companion object {
        const val CH_AUTO = "auto"
        const val CH_SHIZUKU = "shizuku"
        const val CH_ROOT = "root"

        /** 设备是否存在 su（只做路径探测，不实际请求 root） */
        fun rootAvailable(): Boolean = try {
            val p = Runtime.getRuntime()
                .exec(arrayOf("/system/bin/sh", "-c", "command -v su"))
            val ok = p.waitFor() == 0
            try { p.inputStream.close() } catch (_: Throwable) {}
            try { p.errorStream.close() } catch (_: Throwable) {}
            ok
        } catch (t: Throwable) {
            false
        }

        /** 按通道偏好创建注入器（auto = 有 Root 用 Root，否则用 Shizuku） */
        fun create(channel: String): Injector = when (channel) {
            CH_ROOT -> RootInjector()
            CH_SHIZUKU -> ShizukuInjector()
            else -> if (rootAvailable()) RootInjector() else ShizukuInjector()
        }
    }
}

/** v2.2.0：长驻进程句柄（evdev 流式读取），用完 close() */
class SpawnedProcess(val stream: InputStream, private val closer: () -> Unit) {
    fun close() {
        try {
            closer()
        } catch (_: Throwable) {
        }
        try {
            stream.close()
        } catch (_: Throwable) {
        }
    }
}