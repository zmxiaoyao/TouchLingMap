package com.touchling.mapper;

import android.os.IBinder;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Method;

/**
 * v2.4.0 实时触控桥（app_process 端入口）。
 *
 * 由客户端通过 shell/root 执行：
 *   exec env CLASSPATH='&lt;base.apk&gt;' /system/bin/app_process /system/bin com.touchling.mapper.TouchBridge
 *
 * stdin 行协议（每行回 MM_OK / MM_ERROR）：
 *   D x y  按下        M x y  移动        U x y  抬起
 *   C x y  取消        K code 0 按键        Q      退出
 * 启动成功输出 MM_READY。
 *
 * 注入路径：ServiceManager → IInputManager.injectInputEvent（shell/root 持有
 * INJECT_EVENTS 权限），常驻进程直接注入，无逐条 fork input 进程的开销。
 */
public final class TouchBridge {
    private boolean active;
    private long downTime;
    private float lastX;
    private float lastY;
    private final Object manager;
    private final Method inject;
    private final Method setDisplayId;

    private TouchBridge() throws Exception {
        IBinder b = (IBinder) Class.forName("android.os.ServiceManager")
            .getMethod("getService", String.class).invoke(null, "input");
        if (b == null) {
            throw new IllegalStateException("input service unavailable");
        }
        Object im = Class.forName("android.hardware.input.IInputManager$Stub")
            .getMethod("asInterface", IBinder.class).invoke(null, b);
        this.manager = im;
        Class<?> ci = Integer.TYPE;
        this.inject = im.getClass().getMethod("injectInputEvent", InputEvent.class, ci);
        this.setDisplayId = MotionEvent.class.getMethod("setDisplayId", ci);
    }

    private void inject(InputEvent ev) throws Exception {
        if (Boolean.FALSE.equals(this.inject.invoke(this.manager, ev, 0))) {
            throw new IllegalStateException("input service rejected event");
        }
    }

    private void event(int action, float x, float y) throws Exception {
        MotionEvent ev = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, x, y, 0
        );
        try {
            ev.setSource(4098); // InputDevice.SOURCE_TOUCHSCREEN
            setDisplayId.invoke(ev, 0);
            inject(ev);
        } finally {
            ev.recycle();
        }
    }

    private void command(String line) throws Exception {
        String[] p = line.split(" ");
        if (p.length != 3) {
            throw new IllegalArgumentException("invalid command");
        }
        if ("K".equals(p[0])) {
            int code = Integer.parseInt(p[1]);
            long t = SystemClock.uptimeMillis();
            inject(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
            inject(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
            return;
        }
        float x = Float.parseFloat(p[1]);
        float y = Float.parseFloat(p[2]);
        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            throw new IllegalArgumentException("invalid coordinate");
        }
        switch (p[0]) {
            case "C":
                if (active) {
                    event(3, lastX, lastY);
                    active = false;
                }
                break;
            case "D":
                if (active) {
                    event(3, lastX, lastY);
                }
                downTime = SystemClock.uptimeMillis();
                active = true;
                event(0, x, y);
                break;
            case "M":
                if (active) {
                    event(2, x, y);
                }
                break;
            case "U":
                if (active) {
                    event(1, x, y);
                    active = false;
                }
                break;
            default:
                throw new IllegalArgumentException("invalid action");
        }
        lastX = x;
        lastY = y;
    }

    public static void main(String[] args) {
        PrintWriter w = new PrintWriter(System.out, true);
        try {
            TouchBridge bridge = new TouchBridge();
            w.println("MM_READY");
            BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = r.readLine()) != null) {
                if ("Q".equals(line)) {
                    break;
                }
                try {
                    bridge.command(line);
                    w.println("MM_OK");
                } catch (Exception e) {
                    w.println("MM_ERROR:" + e);
                }
            }
            try {
                r.close();
            } catch (Exception ignored) {
            }
        } catch (Throwable t) {
            w.println("MM_FATAL:" + t);
        }
        System.exit(0);
    }
}