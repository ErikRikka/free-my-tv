package io.freemytv;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Touches on the screen, fast. Started once through the TV's own ADB (app_process, with the shell's right to inject input,
 * the way scrcpy does it) and then kept running: `input tap` would start a new program for every touch, about a second
 * each. Listens on a local socket and takes commands only from Free My TV's own user id:
 * "tap x y", "swipe x1 y1 x2 y2 ms", "quit".
 */
public final class TouchServer {
    static final String SOCKET = "freemytv-touch";

    private static Method inject;
    private static Object inputManager;

    public static void main(String[] args) throws Exception {
        int allowed = Integer.parseInt(args[0]);
        Class<?> im = Class.forName("android.hardware.input.InputManager");
        inputManager = im.getMethod("getInstance").invoke(null);
        inject = im.getMethod("injectInputEvent", InputEvent.class, int.class);
        LocalServerSocket server = new LocalServerSocket(SOCKET);
        while (true) {
            try (LocalSocket client = server.accept()) {
                if (client.getPeerCredentials().getUid() != allowed) continue;
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                for (String line; (line = in.readLine()) != null; ) {
                    String[] w = line.trim().split(" ");
                    if (w[0].equals("quit")) return;
                    if (w[0].equals("tap") && w.length == 3) {
                        swipe(f(w[1]), f(w[2]), f(w[1]), f(w[2]), 40);
                    } else if (w[0].equals("swipe") && w.length == 6) {
                        swipe(f(w[1]), f(w[2]), f(w[3]), f(w[4]), (int) f(w[5]));
                    }
                }
            } catch (Exception ignored) {
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
