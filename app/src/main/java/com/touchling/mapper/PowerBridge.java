package com.touchling.mapper;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * v2.4.7 背屏电源桥（1:1 移植参考实现 RearPowerBridge 的行为）：
 *
 *   app_process（shell/root 权限）运行，
 *   ① per-display WakeLock：PowerManager.newWakeLock(10, tag, displayId).acquire()
 *   ② 若背屏未亮：PowerManager.wakeUp(now, 2, tag, displayId)（隐藏 per-display 重载）
 *   ③ 输出 MM_POWER_READY 后靠 stdin 阻塞保活（进程存活 = 持锁中）
 *
 * 用法: app_process ... com.touchling.mapper.PowerBridge <displayId>
 * 退出（stdin EOF 或被 kill）时释放 WakeLock。
 */
public final class PowerBridge {

    public static void main(String[] args) {
        PowerManager.WakeLock wakeLock = null;
        try {
            if (args.length != 1) {
                throw new IllegalArgumentException("display id required");
            }
            int displayId = Integer.parseInt(args[0]);
            if (displayId <= 0) {
                throw new IllegalArgumentException("invalid rear display");
            }
            Looper.prepareMainLooper();
            Context ctx = systemContext();
            Display display = ((DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE))
                    .getDisplay(displayId);
            if (display == null) {
                throw new IllegalStateException("rear display unavailable");
            }
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            Class<?> ci = Integer.TYPE;
            // ① 先持常亮锁（先摆好"常亮"意图）
            PowerManager.WakeLock wl = (PowerManager.WakeLock) PowerManager.class
                    .getMethod("newWakeLock", ci, String.class, ci)
                    .invoke(pm, 10, "TouchLing:RearDisplay", displayId);
            wakeLock = wl;
            wl.acquire();
            // ② 再唤醒（仅当未亮时）
            if (display.getState() != 2) {
                PowerManager.class.getMethod("wakeUp", Long.TYPE, ci, String.class, ci)
                        .invoke(pm, SystemClock.uptimeMillis(), 2, "TouchLing rear display", displayId);
            }
            System.out.println("MM_POWER_READY");
            // ③ 保活（进程存活期间持锁；stdin EOF 即退出释放）
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            while (reader.readLine() != null) {
            }
            reader.close();
            if (wl.isHeld()) {
                wl.release();
            }
        } catch (Throwable t) {
            System.out.println("MM_POWER_ERROR:" + t);
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        }
    }

    private static Context systemContext() throws Exception {
        try {
            Class<?> shm = Class.forName("com.android.internal.os.ApplicationSharedMemory");
            shm.getMethod("setInstance", shm)
                    .invoke(null, shm.getMethod("create", (Class<?>[]) null).invoke(null));
        } catch (ClassNotFoundException unused) {
            // 旧版本系统无此类，忽略
        }
        Class<?> at = Class.forName("android.app.ActivityThread");
        Method systemMain = at.getDeclaredMethod("systemMain", (Class<?>[]) null);
        systemMain.setAccessible(true);
        Object obj = systemMain.invoke(null);
        Method getSystemContext = at.getDeclaredMethod("getSystemContext", (Class<?>[]) null);
        getSystemContext.setAccessible(true);
        return (Context) getSystemContext.invoke(obj);
    }
}