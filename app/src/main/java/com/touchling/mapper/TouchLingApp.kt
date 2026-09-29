package com.touchling.mapper

import android.app.Application

/**
 * v2.2.2 全局未捕获异常兜底：
 * 任何线程的崩溃都先写进 diag.log（带堆栈），方便定位"点了没反应/黑屏"这类静默死亡。
 */
class TouchLingApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Diag.init(this)
        val def = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                Diag.log("未捕获异常[${t.name}]: ${e.stackTraceToString()}")
            } catch (_: Throwable) {
            }
            def?.uncaughtException(t, e)
        }
    }
}