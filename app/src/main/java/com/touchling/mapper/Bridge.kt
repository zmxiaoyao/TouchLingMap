package com.touchling.mapper

import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v2.4.0 实时触控桥客户端。
 *
 * 通过 Shizuku（shell）或 su（root）启动 app_process 跑 TouchBridge，
 * 常驻进程直连 InputManager 注入 —— 每帧只向管道写一行文本，
 * 没有逐条 fork `/system/bin/input` 进程的开销（丝滑的关键）。
 *
 * 任何一步失败 → ok=false，Injector 的 down/move/up 自动回退 input 命令。
 */
object Bridge {

    @Volatile
    var ok = false
        private set

    private var writer: BufferedWriter? = null
    private var remote: Any? = null
    private var lastTry = 0L
    private var fails = 0

    /** 启动桥。useRoot=true 走 su，否则走 Shizuku。 */
    @Synchronized
    fun start(useRoot: Boolean, apkPath: String): Boolean {
        if (ok) return true
        val now = System.currentTimeMillis()
        if (now - lastTry < 2000) return false
        lastTry = now
        if (!apkPath.contains("base.apk")) {
            Diag.log("Bridge: 安装路径异常 $apkPath")
            return false
        }
        val sh = "exec env CLASSPATH='$apkPath' /system/bin/app_process /system/bin com.touchling.mapper.TouchBridge"
        return try {
            val proc: Any
            val out: OutputStream
            val inp: InputStream
            if (useRoot) {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", sh))
                drain(p.errorStream)
                proc = p
                out = p.outputStream
                inp = p.inputStream
            } else {
                val sp = shizukuProcess(sh) ?: run {
                    Diag.log("Bridge: Shizuku newProcess 不可用")
                    return false
                }
                proc = sp
                out = member(sp, "getOutputStream", OutputStream::class.java) as? OutputStream
                    ?: return false
                inp = member(sp, "getInputStream", InputStream::class.java) as? InputStream
                    ?: return false
            }
            // 握手：等第一行 MM_READY
            val ready = CountDownLatch(1)
            Thread {
                try {
                    val br = inp.bufferedReader()
                    val first = br.readLine()
                    if (first == "MM_READY") {
                        ready.countDown()
                    } else {
                        Diag.log("Bridge 握手失败: $first")
                    }
                    // 持续排空回复（防管道写满）
                    while (br.readLine() != null) {
                    }
                } catch (_: Throwable) {
                }
            }.apply { isDaemon = true; name = "bridge-reader"; start() }

            if (!ready.await(3, TimeUnit.SECONDS)) {
                Diag.log("Bridge 启动超时（3s 无 MM_READY）")
                destroy(proc)
                return false
            }
            remote = proc
            writer = BufferedWriter(OutputStreamWriter(out))
            ok = true
            fails = 0
            Diag.log("实时触控桥就绪（${if (useRoot) "root" else "shizuku"} · app_process）")
            true
        } catch (t: Throwable) {
            Diag.log("Bridge 启动异常: $t")
            false
        }
    }

    /** 写一行协议。返回 true = 已走桥；false = 调用方应回退 input 命令。 */
    @Synchronized
    fun write(cmd: String): Boolean {
        if (!ok) return false
        val w = writer ?: return false
        return try {
            w.write(cmd)
            w.newLine()
            w.flush()
            fails = 0
            true
        } catch (t: Throwable) {
            fails++
            if (fails > 8) {
                ok = false
                Diag.log("Bridge 连续写入失败 → 降级 input 命令: $t")
            }
            false
        }
    }

    @Synchronized
    fun stop() {
        if (ok) {
            try {
                writer?.write("Q")
                writer?.newLine()
                writer?.flush()
            } catch (_: Throwable) {
            }
        }
        try {
            writer?.close()
        } catch (_: Throwable) {
        }
        remote?.let { destroy(it) }
        writer = null
        remote = null
        ok = false
        Diag.log("实时触控桥已停止")
    }

    // ---------- Shizuku 进程工具（与 ShizukuInjector 同款反射） ----------

    private fun shizukuProcess(cmd: String): Any? {
        val alive = try {
            Shizuku::class.java.getMethod("pingBinder").invoke(null) == true
        } catch (_: Throwable) {
            false
        }
        if (!alive) return null
        val m = Shizuku::class.java.declaredMethods.firstOrNull {
            it.name == "newProcess" && Modifier.isStatic(it.modifiers)
        } ?: return null
        m.isAccessible = true
        return m.invoke(null, arrayOf("/system/bin/sh", "-c", cmd), null, null)
    }

    private fun member(p: Any, name: String, type: Class<*>): Any? {
        val ms = p.javaClass.declaredMethods
        val m = ms.firstOrNull { it.name == name && it.parameterCount == 0 }
            ?: ms.firstOrNull { it.parameterCount == 0 && type.isAssignableFrom(it.returnType) }
            ?: return null
        m.isAccessible = true
        return m.invoke(p)
    }

    private fun destroy(p: Any) {
        try {
            val m = p.javaClass.declaredMethods.firstOrNull {
                it.name == "destroy" && it.parameterCount == 0
            }
            m?.isAccessible = true
            m?.invoke(p)
        } catch (_: Throwable) {
        }
    }

    private fun drain(input: InputStream) {
        Thread {
            try {
                val buf = ByteArray(1024)
                while (input.read(buf) >= 0) {
                }
            } catch (_: Throwable) {
            }
        }.apply { isDaemon = true }.start()
    }
}