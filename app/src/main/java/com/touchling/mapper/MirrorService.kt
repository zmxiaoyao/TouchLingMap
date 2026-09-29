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

        fun stop(c: Context) {
            Diag.log("外部请求 stop")
            c.startService(Intent(c, MirrorService::class.java).apply { action = "stop" })
        }
    }

    private var tornDown = false
    private var sessionMp: MediaProjection? = null
    private var keeper: Thread? = null
    @Volatile private var keeperRunning = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Diag.init(applicationContext)
        Diag.log("MirrorService onCreate pid=${android.os.Process.myPid()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Diag.log("onStartCommand action=${intent?.action}")
        when (intent?.action) {
            "start" -> {
                teardown()
                tornDown = false
                startAsForeground()
                val code = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra("resultData", Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra("resultData")
                }
                if (data == null) {
                    Diag.log("resultData 为空 → stopSelf")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startProjection(code, data)
            }
            "stop" -> {
                teardown()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "背屏映射", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("触灵映射运行中")
            .setOngoing(true)
            .build()
        try {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } catch (t: Throwable) {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun startProjection(code: Int, data: Intent) {
        // 1. 投影会话
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

        // 2. 枚举并找背屏
        val dm = getSystemService(DisplayManager::class.java)
        val all = dm.displays
        for (d in all) Diag.log("Display id=${d.displayId} name=${d.name} state=${d.state}")
        val back = all.firstOrNull {
            it.displayId != Display.DEFAULT_DISPLAY && it.name != "touchling_mirror"
        }
        if (back == null) {
            Diag.log("未找到背屏 → stopSelf")
            toast("未检测到背屏（背屏是否已唤醒？）")
            mp.stop()
            stopSelf()
            return
        }

        // 3. 注入通道（Root / Shizuku）
        val cfg = Cfg.load(this)
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

        // 4. 处刑背屏中心 + keeper 持续杀死（MRSS 技巧）
        keeperRunning = true
        keeper = Thread {
            while (keeperRunning) {
                try { inj.send("am force-stop com.xiaomi.subscreencenter") } catch (_: Throwable) {}
                try { Thread.sleep(2500) } catch (_: Throwable) { break }
            }
        }.apply {
            isDaemon = true
            name = "subcenter-keeper"
            start()
        }
        Diag.log("keeper 线程已启动")

        // 5. 背屏投放（v0.3.2 方案，参考 MRSS）：
        //    HyperOS 禁止 startActivity 直接上背屏（launchDisplayId 会被拒），
        //    因此：先在本屏正常启动 RearActivity，再由 shell 用
        //    `service call activity_task 50` 把它的任务整体搬到背屏。
        val dispId = back.displayId
        try {
            startActivity(
                Intent(this, RearActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Diag.log("已在主屏启动 RearActivity（等待搬运）")
        } catch (t: Throwable) {
            Diag.log("主屏启动 RearActivity 失败: $t")
        }

        mainHandler.postDelayed({
            if (projection == null) return@postDelayed
            val logPath = "/sdcard/Download/背屏映射/amstart.log"
            val cmd = "tid=\$(cat /sdcard/Android/data/com.touchling.mapper/files/taskid.txt 2>/dev/null); " +
                "echo \"taskId=\$tid\" > $logPath; " +
                "if [ -n \"\$tid\" ]; then " +
                "am display move-stack \$tid $dispId >> $logPath 2>&1; " +
                "sleep 1; dumpsys activity activities | grep -m2 RearActivity >> $logPath 2>&1; " +
                "else echo 'taskid.txt 缺失' >> $logPath; fi"
            inj.send(cmd)
            Diag.log("已发送搬任务命令 → display=$dispId")
        }, 900)

        if (!ready) {
            toast("注入通道未就绪——镜像可用，但触摸不会生效")
        }
    }

    private fun teardown() {
        if (tornDown) return
        tornDown = true
        Diag.log("teardown 开始")
        keeperRunning = false
        keeper = null
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
        Diag.log("MirrorService onDestroy")
        teardown()
        try { injectorInstance?.close() } catch (_: Throwable) {}
        injectorInstance = null
        super.onDestroy()
    }
}