package com.touchling.mapper

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import android.widget.Toast

/**
 * v0.3.0 前台服务（参考 MRSS / Mirror2RearUltra 重构）：
 * - MediaProjection 会话持有（静态桥接给 RearActivity）
 * - 通过注入器 shell 执行：
 *     ① am force-stop com.xiaomi.subscreencenter（处刑 + keeper 持续杀死）
 *     ② am start --display <背屏id> -n .../.RearActivity（拉起背屏镜像 Activity 并点火）
 * - 停止时 monkey 拉回官方背屏中心恢复现场
 * - 会话守卫：旧投影的 onStop 不会误杀新会话
 */
class MirrorService : Service() {

    companion object {
        private const val CHANNEL = "mirror_channel"
        private const val NOTIF_ID = 10

        /** RearActivity 静态桥 */
        @Volatile var projection: MediaProjection? = null
        @Volatile var injectorInstance: Injector? = null

        /** v0.5.0 主屏光标：触控板/体感模式的光标显示在主屏（悬浮窗） */
        @Volatile var mainCursor: MainCursor? = null
        @Volatile var cursorSink: ((Float, Float) -> Unit)? = null

        /** v1.0.0：只把主题/玩具显示到背屏（不做触摸映射注入） */
        @Volatile var displayOnly = false

        /** v2.0.0：免投屏（背屏黑屏当触控板，不申请屏幕捕获权限） */
        @Volatile var noProjection = false

        /** v2.2.0：服务是否在运行（给磁贴/悬浮球/自动启停用） */
        @Volatile var running = false

        /** v2.2.0：一键快速启动（免投屏触控，不需要屏幕捕获权限） */
        fun startQuick(c: Context) {
            Diag.log("startQuick（免投屏）")
            c.startForegroundService(
                Intent(c, MirrorService::class.java).apply {
                    action = "start"
                    putExtra("displayOnly", false)
                    putExtra("noProjection", true)
                }
            )
        }

        fun stop(c: Context) {
            Diag.log("外部请求 stop")
            c.startService(Intent(c, MirrorService::class.java).apply { action = "stop" })
        }

        /** v2.0.0：请求把光标拉回主屏中心（给 UI"光标回中"用） */
        @Volatile var centerRequest = false
        @Volatile var screenW = 0
        @Volatile var screenH = 0
    }

    private var tornDown = false
    private var sessionMp: MediaProjection? = null
    private var keeper: Thread? = null
    @Volatile private var keeperRunning = false
    private var watchdog: Thread? = null
    @Volatile private var watchdogRunning = false
    private var evdev: EvdevTouch? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        Diag.init(applicationContext)
        Diag.log("MirrorService onCreate pid=${android.os.Process.myPid()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Diag.log("onStartCommand action=${intent?.action}")
        when (intent?.action) {
            "start" -> {
                teardown()
                tornDown = false
                displayOnly = intent.getBooleanExtra("displayOnly", false)
                val noProj = intent.getBooleanExtra("noProjection", false)
                noProjection = noProj
                Diag.log("displayOnly=$displayOnly noProjection=$noProj")
                startAsForeground(noProj)
                val code = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra("resultData", Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra("resultData")
                }
                if (data == null && !noProj) {
                    Diag.log("resultData 为空 → stopSelf")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startProjection(code, data, noProj)
            }
            "stop" -> {
                teardown()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground(specialUse: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "背屏映射", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("触灵映射运行中")
            .setOngoing(true)
            .build()
        // v1.2.0：只上屏模式没有投屏会话，必须用 specialUse 类型，否则 Android 14 直接抛异常
        Diag.log("startForeground specialUse=$specialUse")
        try {
            if (specialUse) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            }
        } catch (t: Throwable) {
            Diag.log("带类型 startForeground 失败: $t")
            try {
                startForeground(NOTIF_ID, n)
            } catch (t2: Throwable) {
                Diag.log("startForeground 失败: $t2")
            }
        }
        Diag.log("startForeground 调用完成（specialUse=$specialUse）")
    }

    private fun startProjection(code: Int, data: Intent?, noProj: Boolean) {
        // v2.2.2：整段兜底，任何异常写日志而不是让进程静默死亡
        try {
        // 1. 投影会话（noProjection 模式：只显示主题/玩具，完全不需要投屏授权）
        if (noProj) {
            Diag.log("noProjection 模式：跳过投屏会话（主题/玩具不需要）")
        } else {
            if (data == null) {
                Diag.log("非 noProjection 但 data 为空 → stopSelf")
                stopSelf()
                return
            }
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val mp = try {
                mpm.getMediaProjection(code, data)
            } catch (t: Throwable) {
                Diag.log("getMediaProjection 抛异常: $t")
                null
            }
            if (mp == null) {
                Diag.log("mp=null → stopSelf")
                toast("获取 MediaProjection 失败")
                stopSelf()
                return
            }
            sessionMp = mp
            val selfMp = mp
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Diag.log("MediaProjection.onStop 回调")
                    mainHandler.post {
                        // 会话守卫：只处理自己的会话（防旧回调杀新会话）
                        if (projection === selfMp) {
                            Diag.log("会话 onStop → teardown+stopSelf")
                            teardown()
                            stopSelf()
                        } else {
                            Diag.log("忽略过期会话 onStop")
                        }
                    }
                }
            }, mainHandler)
            projection = mp
        }

        // 2. 枚举并找背屏（v2.0.0：支持用户指定背屏 display id）
        Diag.log("步骤2：枚举显示器（startProjection 正常进行中）")
        val cfg0 = Cfg.load(this)
        val dm = getSystemService(DisplayManager::class.java)
        val all = dm.displays
        for (d in all) Diag.log("Display id=${d.displayId} name=${d.name} state=${d.state}")
        val back = if (cfg0.rearDisplayId >= 0) {
            all.firstOrNull { it.displayId == cfg0.rearDisplayId }
        } else {
            all.firstOrNull {
                it.displayId != Display.DEFAULT_DISPLAY && it.name != "touchling_mirror"
            }
        }
        if (back == null) {
            Diag.log("未找到背屏 → stopSelf")
            toast("未检测到背屏（背屏是否已唤醒？）")
            try { sessionMp?.stop() } catch (_: Throwable) {}
            stopSelf()
            return
        }

        // 3. 注入通道（Root / Shizuku）
        val cfg = Cfg.load(this)

        // 3.5 主屏光标（v0.5.0）：触控板/体感模式 → 光标画在主屏，背屏当触控板
        val mc = MainCursor(this, cfg.cursorStyle, cfg.cursorDp, cfg.cursorColor)
        mainCursor = mc
        if (!displayOnly && cfg.toy == 0 && cfg.htmlTheme == 0 && (cfg.mode == "pad" || cfg.gyro)) {
            if (mc.available) {
                mc.show()
                cursorSink = { x, y -> mc.move(x, y) }
                Diag.log("主屏光标已启用（mode=${cfg.mode} gyro=${cfg.gyro} dp=${cfg.cursorDp}）")
            } else {
                cursorSink = null
                Diag.log("无悬浮窗权限 → 光标回退到背屏")
                toast("未授予「悬浮窗」权限，光标将回退显示在背屏")
            }
        } else {
            cursorSink = null
            Diag.log("灵触模式：无需光标")
        }
        val wantRoot = when (cfg.channel) {
            Injector.CH_ROOT -> true
            Injector.CH_SHIZUKU -> false
            else -> Injector.rootAvailable()
        }
        val cur = injectorInstance
        val inj: Injector = if (cur != null && (cur is RootInjector) == wantRoot) {
            cur
        } else {
            try { cur?.close() } catch (_: Throwable) {}
            if (wantRoot) RootInjector() else ShizukuInjector()
        }
        val ready = inj.start()
        injectorInstance = inj
        Diag.log("注入器=${inj.javaClass.simpleName} ready=$ready channel=${cfg.channel}")

        // 4. 处刑背屏中心：keeper 线程用独立进程执行（失败可见）
        keeperRunning = true
        keeper = Thread {
            var logged = false
            while (keeperRunning) {
                val out = inj.exec("am force-stop com.xiaomi.subscreencenter")
                if (!logged && (out.contains("EXEC_ERR") || out.contains("DOWN"))) {
                    Diag.log("keeper 异常: $out")
                    logged = true
                }
                try { Thread.sleep(4000) } catch (_: Throwable) { break }
            }
        }.apply {
            isDaemon = true
            name = "subcenter-keeper"
            start()
        }
        Diag.log("keeper 线程已启动")

        // 5. 背屏投放（v2.2.2）：优先 shell `am start --display` 直投背屏（不遮挡当前软件，体验同妙妙）
        val dispId = back.displayId
        mainHandler.postDelayed({
            // v2.2.1：免投屏模式没有投影会话，也要走"点亮+搬运"（否则黑屏卡在主屏）
            if (projection == null && !noProjection) return@postDelayed
            Thread {
                try {
                    Thread.sleep(if (noProjection) 150 else 700)
                } catch (_: Throwable) {
                }
                // —— 直投背屏（失败回退"主屏启动 + 搬运"）——
                val shOut = try {
                    inj.exec("am start --display $dispId -n com.touchling.mapper/.RearActivity")
                } catch (t: Throwable) {
                    "ERR:$t"
                }
                val directOk = shOut.contains("Starting") || shOut.contains("Intent")
                Diag.log("--display 直投 display=$dispId ok=$directOk out=${shOut.take(90)}")
                if (!directOk) {
                    mainHandler.post {
                        try {
                            startActivity(
                                Intent(this, RearActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                            Diag.log("回退：主屏启动 RearActivity（隐形）")
                        } catch (t: Throwable) {
                            Diag.log("启动 RearActivity 失败: $t")
                        }
                    }
                }
                // 等 onCreate 写入 taskId（最多 2s）
                var tid = RearActivity.lastTaskId
                var waited = 0
                while (tid <= 0 && waited < 2000) {
                    try {
                        Thread.sleep(100)
                    } catch (_: Throwable) {
                    }
                    waited += 100
                    tid = RearActivity.lastTaskId
                }
                Diag.log("搬运前 taskId=$tid arrived=${RearActivity.arrived}")
                if (tid <= 0) {
                    Diag.log("无 taskId，放弃搬运")
                    return@Thread
                }
                // 点亮背屏（MIUI 私有电源事务，参考 Mirror2RearUltra）
                val wake = inj.exec(
                    "UP=\$(awk '{printf \"%d\", \$1*1000}' /proc/uptime); " +
                        "service call power 16777210 i64 \$UP i32 1 s16 CAMERA_CALL"
                )
                Diag.log("点亮背屏: ${wake.ifBlank { "无输出" }}")
                for (i in 1..3) {
                    if (RearActivity.arrived) break
                    val out = inj.exec("am display move-stack $tid $dispId")
                    Diag.log("搬运#$i → display=$dispId: ${out.ifBlank { "OK" }}")
                    try { Thread.sleep(1200) } catch (_: Throwable) {}
                }
                Diag.log("搬运结束 arrived=${RearActivity.arrived} alive=${RearActivity.alive}")
            }.apply { isDaemon = true }.start()
        }, if (noProjection) 300 else 800)

        if (!ready) {
            toast("注入通道未就绪——镜像可用，但触摸不会生效")
        }

        // v2.2.1 evdev 直读（放宽：镜像/免投屏 均可；灵触/触控板 均可；体感暂不支持并写明原因）
        when {
            !cfg.evdev -> {}
            displayOnly -> Diag.log("evdev 跳过：只显示模式（无触摸映射）")
            cfg.mode == "gyro" ->
                Diag.log("evdev 跳过：体感光标模式暂不支持直读（请改用灵触映射或精密触控板）")
            cfg.mode != "pad" && cfg.mode != "direct" ->
                Diag.log("evdev 跳过：未知模式 ${cfg.mode}")
            else -> {
                if (cfg.mode == "pad" && cursorSink == null) {
                    Diag.log("evdev 警告：无悬浮窗权限 → 光标不会移动（请授予「显示在其他应用上层」）")
                }
                val targetX = back.mode.physicalWidth * 100 - 1
                val dev = EvdevTouch.detectDevice({ c -> inj.exec(c) }, targetX)
                if (dev == null) {
                    Diag.log("evdev: 未找到匹配背屏宽度($targetX) 的触摸设备")
                } else {
                    val dm = resources.displayMetrics
                    val m = TouchMapper(inj, cfg, dm.widthPixels, dm.heightPixels) { x, y, _ ->
                        cursorSink?.invoke(x, y)
                    }
                    evdev = EvdevTouch(this, m, { c -> inj.spawn(c) }) { Diag.log(it) }
                    evdev?.start(
                        dev,
                        back.mode.physicalWidth * 100 - 1,
                        back.mode.physicalHeight * 100 - 1
                    )
                    Diag.log(
                        "evdev 直读已启动 dev=$dev mode=${cfg.mode} " +
                            "noMirror=${cfg.noMirror} noProjection=$noProjection"
                    )
                }
            }
        }

        // 4.5 背屏看门狗（v1.3.0）：背屏息屏会休眠，任务会被退回主屏 → 自动点亮 + 重新搬运
        watchdogRunning = true
        watchdog = Thread {
            var lastState = -999
            while (watchdogRunning) {
                try {
                    val dm2 = getSystemService(DisplayManager::class.java)
                    val d1 = dm2.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
                    val st = d1?.state ?: -1
                    if (st != lastState) {
                        Diag.log("看门狗：背屏 state=$st")
                        lastState = st
                    }
                    if (d1 != null) {
                        val asleep = st == Display.STATE_OFF || st == Display.STATE_DOZE ||
                                st == Display.STATE_DOZE_SUSPEND || st == Display.STATE_UNKNOWN
                        if (asleep) {
                            val w = inj.exec(
                                "UP=\$(awk '{printf \"%d\", \$1*1000}' /proc/uptime); " +
                                    "service call power 16777210 i64 \$UP i32 1 s16 CAMERA_CALL"
                            )
                            Diag.log("看门狗点亮背屏: ${w.ifBlank { "OK" }}")
                        }
                        // 任务被退回主屏 → 重新搬运（仅在有内容需要在背屏时）
                        val tid = RearActivity.lastTaskId
                        if (RearActivity.alive && !RearActivity.arrived && tid > 0 && !asleep) {
                            val out = inj.exec("am display move-stack $tid ${d1.displayId}")
                            Diag.log("看门狗重搬运: ${out.ifBlank { "OK" }}")
                        }
                    }
                } catch (t: Throwable) {
                    Diag.log("看门狗异常: $t")
                }
                try {
                    Thread.sleep(5000)
                } catch (_: Throwable) {
                    break
                }
            }
            Diag.log("看门狗停止")
        }.apply {
            isDaemon = true
            name = "rear-watchdog"
            start()
        }
        Diag.log("看门狗已启动")
        } catch (t: Throwable) {
            Diag.log("startProjection 异常: ${t.stackTraceToString()}")
            toast("启动异常：${t.message}")
            stopSelf()
        }
    }

    private fun teardown() {
        if (tornDown) return
        tornDown = true
        Diag.log("teardown 开始")
        keeperRunning = false
        keeper = null
        watchdogRunning = false
        watchdog = null
        try {
            evdev?.stop()
        } catch (_: Throwable) {
        }
        evdev = null
        // 收起主屏光标
        cursorSink = null
        try { mainCursor?.hide() } catch (_: Throwable) {}
        mainCursor = null
        // 恢复官方背屏中心
        try {
            injectorInstance?.send(
                "monkey -p com.xiaomi.subscreencenter -c android.intent.category.LAUNCHER 1"
            )
        } catch (_: Throwable) {}
        projection = null
        try { sessionMp?.stop() } catch (_: Throwable) {}
        sessionMp = null
        Diag.log("teardown 完成")
    }

    private fun toast(msg: String) {
        mainHandler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        running = false
        Diag.log("MirrorService onDestroy")
        teardown()
        try { injectorInstance?.close() } catch (_: Throwable) {}
        injectorInstance = null
        super.onDestroy()
    }
}