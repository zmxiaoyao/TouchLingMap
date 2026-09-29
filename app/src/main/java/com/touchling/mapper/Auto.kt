package com.touchling.mapper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * v2.2.0 自动化与快捷（参考「妙妙背屏」）
 * - ControlTile   控制中心磁贴，一键启停
 * - BootReceiver  开机自启
 * - WatchService  按应用自动启停（需要「使用情况访问」权限）
 * - FloatBall     悬浮开关（可拖动小圆点）
 */

// ---------------------------------------------------------------- 磁贴

class ControlTile : TileService() {
    override fun onClick() {
        super.onClick()
        try {
            if (MirrorService.running) {
                MirrorService.stop(this)
            } else {
                MirrorService.startQuick(this)
            }
            qsTile?.state =
                if (MirrorService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            qsTile?.updateTile()
            Diag.log("磁贴点击 running=${MirrorService.running}")
        } catch (t: Throwable) {
            Diag.log("Tile: $t")
        }
    }
}

// ---------------------------------------------------------------- 开机自启

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED) return
        val sp = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        Diag.log(
            "开机广播 bootAuto=${sp.getBoolean("bootAuto", false)} " +
                "autoApp=${sp.getBoolean("autoApp", false)}"
        )
        if (sp.getBoolean("autoApp", false)) {
            try {
                c.startForegroundService(Intent(c, WatchService::class.java))
            } catch (t: Throwable) {
                Diag.log("WatchService 启动失败: $t")
            }
        }
        if (sp.getBoolean("bootAuto", false)) {
            MirrorService.startQuick(c)
        }
    }
}

// ---------------------------------------------------------------- 按应用自动启停

class WatchService : Service() {
    companion object {
        @Volatile var running = false
    }

    @Volatile private var run = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        running = true
        run = true
        startFg()
        Thread {
            var warned = false
            while (run) {
                try {
                    val sp = getSharedPreferences("cfg", MODE_PRIVATE)
                    val on = sp.getBoolean("autoApp", false)
                    val apps = (sp.getString("autoApps", "") ?: "")
                        .split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    if (on && apps.isNotEmpty()) {
                        val front = frontApp()
                        when {
                            front == null -> {
                                if (!warned) {
                                    Diag.log("watch: 取不到前台应用（需授予「使用情况访问」权限）")
                                    warned = true
                                }
                            }
                            apps.contains(front) -> {
                                warned = false
                                if (!MirrorService.running) {
                                    Diag.log("自动启停: 进入 $front → 上屏")
                                    MirrorService.startQuick(this)
                                }
                            }
                            else -> {
                                warned = false
                                if (MirrorService.running) {
                                    Diag.log("自动启停: 离开白名单($front) → 停止")
                                    MirrorService.stop(this)
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Diag.log("watch: $t")
                }
                try {
                    Thread.sleep(2500)
                } catch (_: Throwable) {
                    break
                }
            }
            running = false
        }.apply {
            isDaemon = true
            name = "app-watch"
            start()
        }
        return START_STICKY
    }

    private fun startFg() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel("watch", "自动启停", NotificationManager.IMPORTANCE_LOW)
            )
            val n = android.app.Notification.Builder(this, "watch")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("按应用自动启停运行中")
                .setOngoing(true)
                .build()
            try {
                startForeground(
                    9001, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } catch (t: Throwable) {
                try {
                    startForeground(9001, n)
                } catch (t2: Throwable) {
                    Diag.log("watch fg fail: $t2")
                }
            }
        } catch (t: Throwable) {
            Diag.log("watch fg err: $t")
        }
    }

    private fun frontApp(): String? {
        return try {
            val us = getSystemService(UsageStatsManager::class.java)
            val now = System.currentTimeMillis()
            val it = us.queryEvents(now - 5000, now)
            val ev = UsageEvents.Event()
            var pkg: String? = null
            while (it.hasNextEvent()) {
                it.getNextEvent(ev)
                if (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED
                ) {
                    pkg = ev.packageName
                }
            }
            pkg
        } catch (t: Throwable) {
            null
        }
    }

    override fun onDestroy() {
        run = false
        running = false
        super.onDestroy()
    }
}

// ---------------------------------------------------------------- 悬浮开关

object FloatBall {
    private var wm: WindowManager? = null
    private var view: View? = null

    fun show(c: Context): Boolean {
        if (view != null) return true
        return try {
            val app = c.applicationContext
            val w = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val d = app.resources.displayMetrics.density
            val size = (48 * d).toInt()
            val v = TextView(app).apply {
                text = if (MirrorService.running) "■" else "▶"
                textSize = 15f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setBackgroundColor(0xCC1B1C20.toInt())
            }
            val lp = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = app.resources.displayMetrics.widthPixels - size - (16 * d).toInt()
                y = app.resources.displayMetrics.heightPixels / 2
            }
            var lastRawX = 0f
            var lastRawY = 0f
            var downX = 0f
            var downY = 0f
            var moved = false
            v.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        lastRawX = e.rawX; lastRawY = e.rawY
                        moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (Math.abs(e.rawX - downX) + Math.abs(e.rawY - downY) > 8 * d) {
                            moved = true
                        }
                        val dx = e.rawX - lastRawX
                        val dy = e.rawY - lastRawY
                        lastRawX = e.rawX; lastRawY = e.rawY
                        if (moved) {
                            lp.x += dx.toInt()
                            lp.y += dy.toInt()
                            try {
                                w.updateViewLayout(v, lp)
                            } catch (_: Throwable) {
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!moved) {
                            if (MirrorService.running) {
                                MirrorService.stop(app)
                                v.text = "▶"
                            } else {
                                MirrorService.startQuick(app)
                                v.text = "■"
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
            w.addView(v, lp)
            wm = w
            view = v
            Diag.log("悬浮开关已显示")
            true
        } catch (t: Throwable) {
            Diag.log("悬浮开关失败: $t")
            false
        }
    }

    fun hide() {
        try {
            wm?.removeView(view)
        } catch (_: Throwable) {
        }
        view = null
        wm = null
    }

    fun refresh() {
        val v = view as? TextView ?: return
        v.text = if (MirrorService.running) "■" else "▶"
    }
}