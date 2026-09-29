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
        try { f.appendText(line + "\n") } catch (_: Throwable) {}
    }
}