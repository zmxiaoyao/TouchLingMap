package com.touchling.mapper

import java.io.InputStream
import java.io.OutputStreamWriter

/**
 * Root 注入器：持有常驻 `su` root shell，逐行向 stdin 写入 input 命令。
 * root shell 的 INJECT_EVENTS 不受限制，注入延迟低于 Shizuku。
 * 注意：首次使用会弹出 Root 授权弹窗（Magisk/KernelSU），需手动同意一次。
 */
class RootInjector : Injector {

    private var writer: OutputStreamWriter? = null
    private var process: Process? = null
    private var lastTry = 0L

    val available: Boolean get() = writer != null

    @Synchronized
    override fun start(): Boolean {
        if (writer != null) return true
        lastTry = System.currentTimeMillis()
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su"))
            // 排空 stdout/stderr，防止管道写满导致 root shell 阻塞
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
            // su 被拒绝或进程死亡 → 清理，等待下次重试
            try { process?.destroy() } catch (_: Throwable) {}
            process = null
            writer = null
        }
    }

    @Synchronized
    override fun close() {
        try { writer?.write("exit\n"); writer?.flush() } catch (_: Throwable) {}
        try { writer?.close() } catch (_: Throwable) {}
        try { process?.destroy() } catch (_: Throwable) {}
        writer = null
        process = null
    }
}