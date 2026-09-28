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
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import android.widget.Toast

/**
 * 前台服务：持有 MediaProjection，把主屏画面投到背屏 Presentation 上
 * 并持有注入器（Root / Shizuku 自动或按设置选择）供触控注入。
 */
class MirrorService : Service() {

    companion object {
        private const val CHANNEL = "mirror_channel"
        private const val NOTIF_ID = 10

        fun stop(c: Context) {
            c.startService(Intent(c, MirrorService::class.java).apply { action = "stop" })
        }
    }

    private var projection: MediaProjection? = null
    private var vdisplay: VirtualDisplay? = null
    private var presentation: MirrorPresentation? = null
    private var injector: Injector? = null
    private var tornDown = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 注入器在 startProjection 中按通道惰性创建（可随设置切换而重建）
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "start" -> {
                // 修复：重复启动前先释放旧的 Presentation/VirtualDisplay/Projection
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
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = try {
            mpm.getMediaProjection(code, data)
        } catch (t: Throwable) {
            null
        }
        if (mp == null) {
            toast("获取 MediaProjection 失败")
            stopSelf()
            return
        }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post { teardown(); stopSelf() }
            }
        }, mainHandler)

        // 找背屏：非默认显示屏、且不是自己创建的虚拟屏
        val dm = getSystemService(DisplayManager::class.java)
        val back = dm.displays.firstOrNull {
            it.displayId != Display.DEFAULT_DISPLAY && it.name != "touchling_mirror"
        }
        if (back == null) {
            toast("未检测到背屏（背屏是否已唤醒？）")
            mp.stop()
            stopSelf()
            return
        }
        toast("已找到背屏：${back.name}")

        val cfg = Cfg.load(this)
        // 按通道偏好创建/切换注入器（设置改了也能在下次开始时生效）
        val wantRoot = when (cfg.channel) {
            Injector.CH_ROOT -> true
            Injector.CH_SHIZUKU -> false
            else -> Injector.rootAvailable()
        }
        val cur = injector
        val inj: Injector = if (cur != null && (cur is RootInjector) == wantRoot) {
            cur.start(); cur
        } else {
            try { cur?.close() } catch (_: Throwable) {}
            val fresh: Injector = if (wantRoot) RootInjector() else ShizukuInjector()
            fresh.start()
            injector = fresh
            fresh
        }
        if (!inj.start()) {
            toast("注入通道未就绪（通道：${cfg.channel}）——镜像可用，但触摸不会生效")
        }

        val p = MirrorPresentation(this, back, inj, cfg)
        presentation = p
        p.onSurfaceReady = { surface ->
            val m = resources.displayMetrics
            vdisplay = mp.createVirtualDisplay(
                "touchling_mirror",
                m.widthPixels, m.heightPixels, m.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null
            )
            if (vdisplay == null) toast("VirtualDisplay 创建失败")
        }
        p.onDismissed = {
            mainHandler.post { teardown(); stopSelf() }
        }
        p.show()
    }

    private fun teardown() {
        if (tornDown) return
        tornDown = true
        try { vdisplay?.release() } catch (_: Throwable) {}
        vdisplay = null
        try { presentation?.onDismissed = null } catch (_: Throwable) {}
        try { presentation?.dismiss() } catch (_: Throwable) {}
        presentation = null
        try { projection?.stop() } catch (_: Throwable) {}
        projection = null
    }

    private fun toast(msg: String) {
        mainHandler.post {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        teardown()
        injector?.close()
        super.onDestroy()
    }
}