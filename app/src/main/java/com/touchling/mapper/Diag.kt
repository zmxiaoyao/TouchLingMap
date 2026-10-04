package com.touchling.mapper

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断日志：Logcat(tag=TouchLing) + 文件双写。
 * 文件位于 filesDir/diag.log，debug 包可通过 run-as 读取。
 */
object Diag {
    private const val TAG = "TouchLing"
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    @Volatile private var file: File? = null
    private var sinceCheck = 0

    fun init(appContext: android.content.Context) {
        if (file != null) return
        synchronized(this) {
            if (file != null) return
            try {
                val f = File(appContext.filesDir, "diag.log")
                if (f.exists() && f.length() > 200_000) {
                    File(appContext.filesDir, "diag.log.old").delete()
                    f.renameTo(File(appContext.filesDir, "diag.log.old"))
                }
                file = f
            } catch (t: Throwable) { }
        }
    }

    @Synchronized
    fun log(msg: String) {
        val line = "[${fmt.format(Date())}] $msg"
        Log.d(TAG, line)
        val f = file ?: return
        try {
            f.appendText(line + "\n")
            // v2.4.11：运行时轮转（此前只在 init 检查——长会话持续膨胀会拖慢每行 append）
            if (++sinceCheck >= 2000 && f.length() > 500_000) {
                sinceCheck = 0
                File(f.parentFile, "diag.log.old").delete()
                f.renameTo(File(f.parentFile, "diag.log.old"))
            }
        } catch (_: Throwable) {}
    }
}