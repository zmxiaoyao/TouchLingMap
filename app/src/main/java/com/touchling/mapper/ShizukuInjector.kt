package com.touchling.mapper

import rikka.shizuku.Shizuku
import java.io.InputStream
import java.io.OutputStreamWriter

/**
 * Shizuku 注入器（无 Root 方案）
 * Shizuku 以 shell 身份运行，shell uid 拥有 INJECT_EVENTS 权限。
 * 保持一个常驻 sh，逐行执行 input 命令（命令绝对路径见 Injector 接口默认方法）。
 */
class ShizukuInjector : Injector {

    private var writer: OutputStreamWriter? = null
    private var process: Process? = null
    private var lastTry = 0L

    val available: Boolean get() = writer != null

    @Synchronized
    override fun start(): Boolean {
        if (writer != null) return true
        lastTry = System.currentTimeMillis()
        return try {
            if (!Shizuku.pingBinder() || !Shizuku.checkSelfPermission()) return false
            val p = Shizuku.newProcess(
                arrayOf(
                    "/system/bin/sh", "-c",
                    "while IFS= read -r line; do eval \"\$line\" 2>/dev/null; done"
                ),
                null, null
            )
            // 排空 stdout/stderr，防止管道写满导致 shell 阻塞
            drain(p.inputStream)
            drain(p.errorStream)
            process = p
            writer = OutputStreamWriter(p.outputStream)
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun drain(input: InputStream) {
        Thread {
            try {
                val buf = ByteArray(1024)
                while (input.read(buf) >= 0) { /* 排空 */ }
            } catch (_: Throwable) {}
        }.apply { isDaemon = true }.start()
    }

    private fun ensure(): OutputStreamWriter? {
        writer?.let { return it }
        if (System.currentTimeMillis() - lastTry < 1500) return null
        start()
        return writer
    }

    @Synchronized
    override fun send(cmd: String) {
        val w = ensure() ?: return
        try {
            w.write(cmd)
            w.write('\n')
            w.flush()
        } catch (t: Throwable) {
            try { process?.destroy() } catch (_: Throwable) {}
            process = null
            writer = null
        }
    }

    @Synchronized
    override fun close() {
        try { writer?.close() } catch (_: Throwable) {}
        try { process?.destroy() } catch (_: Throwable) {}
        writer = null
        process = null
    }
}