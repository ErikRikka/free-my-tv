package io.freemytv;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.Socket;

/**
 * Touches on the screen, fast. Started through the TV's own ADB (app_process, with the shell's right to inject input,
 * the way scrcpy does it) and kept running while Free My TV's key service is: `input tap` would start a new program for
 * every touch, about a second each. The TV's security rules don't let an app reach a program of the shell's, so this one
 * dials the app instead (a port on 127.0.0.1) and the first line must be the one-time key it was started with.
 * Commands, a line each: "tap x y", "swipe x1 y1 x2 y2 ms". It ends when the app hangs up.
 */
public final class TouchServer {
    private static Method inject;
    private static Object inputManager;

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        String key = args[1];
        Class<?> im = Class.forName("android.hardware.input.InputManager");
        inputManager = im.getMethod("getInstance").invoke(null);
        inject = im.getMethod("injectInputEvent", InputEvent.class, int.class);
        try (Socket app = new Socket("127.0.0.1", port)) {
            BufferedReader in = new BufferedReader(new InputStreamReader(app.getInputStream()));
            if (!key.equals(in.readLine())) return;
            for (String line; (line = in.readLine()) != null; ) {
                String[] w = line.trim().split(" ");
                try {
                    if (w[0].equals("tap") && w.length == 3) {
                        swipe(f(w[1]), f(w[2]), f(w[1]), f(w[2]), 40);
                    } else if (w[0].equals("swipe") && w.length == 6) {
                        swipe(f(w[1]), f(w[2]), f(w[3]), f(w[4]), (int) f(w[5]));
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static float f(String s) {
        return Float.parseFloat(s);
    }

    private static void swipe(float x1, float y1, float x2, float y2, int ms) throws Exception {
        long down = SystemClock.uptimeMillis();
        send(down, down, MotionEvent.ACTION_DOWN, x1, y1);
        // Each move stamped with the time it should have happened at, so a late thread doesn't read as a slow finger.
        int steps = Math.max(1, ms / 8);
        long at = down;
        for (int i = 1; i <= steps; i++) {
            at = down + (long) ms * i / steps;
            long wait = at - SystemClock.uptimeMillis();
            if (wait > 0) Thread.sleep(wait);
            float t = (float) i / steps;
            send(down, at, MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t);
        }
        send(down, at, MotionEvent.ACTION_UP, x2, y2);
    }

    private static void send(long down, long at, int action, float x, float y) throws Exception {
        MotionEvent e = MotionEvent.obtain(down, at, action, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        inject.invoke(inputManager, e, 0);
        e.recycle();
    }
}
