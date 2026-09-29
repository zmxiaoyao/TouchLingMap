package com.touchling.mapper

import rikka.shizuku.Shizuku
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.lang.reflect.Modifier

/**
 * Shizuku 注入器（无 Root 方案）
 * Shizuku 以 shell 身份运行，shell uid 拥有 INJECT_EVENTS 权限。
 * 注意：Shizuku 13.1.5 的 newProcess 是 private，这里通过反射调用
 * （Shizuku 是应用自带的库类，反射调用不受 Android 隐藏 API 限制）。
 */
class ShizukuInjector : Injector {

    private var writer: OutputStreamWriter? = null
    private var remote: Any? = null
    private var lastTry = 0L

    val available: Boolean get() = writer != null

    @Synchronized
    override fun start(): Boolean {
        if (writer != null) return true
        lastTry = System.currentTimeMillis()
        return try {
            if (!alive()) return false
            val proc = newRemoteProcess(
                arrayOf(
                    "/system/bin/sh", "-c",
                    "while IFS= read -r line; do eval \"\$line\" 2>/dev/null; done"
                )
            ) ?: return false
            val out = procMember(proc, "getOutputStream", OutputStream::class.java)
                as? OutputStream ?: return false
            val inp = procMember(proc, "getInputStream", InputStream::class.java) as? InputStream
            val err = procMember(proc, "getErrorStream", InputStream::class.java) as? InputStream
            inp?.let { drain(it) }
            err?.let { drain(it) }
            remote = proc
            writer = OutputStreamWriter(out)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 独立进程执行并回读输出（关键命令用，避免持久管道静默失败） */
    override fun exec(cmd: String): String {
        return try {
            if (!alive()) return "SHIZUKU_DOWN"
            val proc = newRemoteProcess(arrayOf("/system/bin/sh", "-c", cmd))
                ?: return "NO_PROC"
            val out = procMember(proc, "getInputStream", InputStream::class.java) as? InputStream
            val sb = StringBuilder()
            try {
                out?.bufferedReader()?.forEachLine { sb.append(it).append('\n') }
            } catch (_: Throwable) {}
            destroyProc(proc)
            sb.toString().trim()
        } catch (t: Throwable) {
            "EXEC_ERR:$t"
        }
    }

    /** v2.2.0：长驻进程（evdev 流式读取） */
    override fun spawn(cmd: String): SpawnedProcess? {
        return try {
            if (!alive()) return null
            val proc = newRemoteProcess(arrayOf("/system/bin/sh", "-c", cmd)) ?: return null
            val out = procMember(proc, "getInputStream", InputStream::class.java) as? InputStream
                ?: return null
            SpawnedProcess(out) { destroyProc(proc) }
        } catch (t: Throwable) {
            null
        }
    }

    private fun destroyProc(p: Any) {
        try {
            val m = p.javaClass.declaredMethods.firstOrNull {
                it.name == "destroy" && it.parameterCount == 0
            }
            m?.isAccessible = true
            m?.invoke(p)
        } catch (_: Throwable) {}
    }

    /** Shizuku binder 存活探测（返回类型按 Boolean/Number 兼容处理） */
    private fun alive(): Boolean = try {
        val v = Shizuku::class.java.getMethod("pingBinder").invoke(null)
        when (v) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            else -> false
        }
    } catch (t: Throwable) {
        false
    }

    /** 反射调用私有静态 Shizuku.newProcess(cmd, env, dir) */
    private fun newRemoteProcess(cmd: Array<String>): Any? {
        val m = Shizuku::class.java.declaredMethods.firstOrNull {
            it.name == "newProcess" && Modifier.isStatic(it.modifiers)
        } ?: return null
        m.isAccessible = true
        return m.invoke(null, cmd, null, null)
    }

    /** 从远端进程对象取流（先按名字，再按返回类型兜底） */
    private fun procMember(proc: Any, name: String, type: Class<*>): Any? {
        val ms = proc.javaClass.declaredMethods
        val m = ms.firstOrNull { it.name == name && it.parameterCount == 0 }
            ?: ms.firstOrNull { it.parameterCount == 0 && type.isAssignableFrom(it.returnType) }
            ?: return null
        m.isAccessible = true
        return m.invoke(proc)
    }

    private fun destroyRemote() {
        val p = remote ?: return
        try {
            val m = p.javaClass.declaredMethods.firstOrNull {
                it.name == "destroy" && it.parameterCount == 0
            }
            m?.isAccessible = true
            m?.invoke(p)
        } catch (_: Throwable) {}
    }

    private fun drain(input: InputStream) {
        Thread {
            try {
                val buf = ByteArray(1024)
                while (input.read(buf) >= 0) { /* 排空，防止管道写满阻塞 */ }
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
            w.write("\n")
            w.flush()
        } catch (t: Throwable) {
            destroyRemote()
            writer = null
            remote = null
        }
    }

    @Synchronized
    override fun close() {
        try { writer?.close() } catch (_: Throwable) {}
        destroyRemote()
        writer = null
        remote = null
    }
}