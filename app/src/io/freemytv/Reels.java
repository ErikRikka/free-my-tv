package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.widget.Toast;

/**
 * Instagram's phone app on the TV, driven by the remote: it only knows fingers, so while it's in front the remote's
 * keys become swipes and taps. Down: the next reel, Up: the one before, OK: pause, Right: like (a double tap).
 * Holding OK gives the remote back to the app as it is (to sign in, type, look around) and holding it again takes it.
 */
final class Reels {
    private Reels() {}

    static final String INSTAGRAM = "com.instagram.android";

    /** The app in front, from the window changes the key service sees. */
    static volatile String front = "";
    private static volatile boolean on = true;
    private static boolean held;

    static void windowChanged(CharSequence pkg) {
        if (pkg == null) return;
        String p = pkg.toString();
        if (p.contains("inputmethod") || p.equals("com.android.systemui")) return;   // the keyboard or a toast over the app
        front = p;
    }

    static boolean onKey(AccessibilityService s, KeyEvent e) {
        if (!INSTAGRAM.equals(front)) return false;
        int key = e.getKeyCode();
        boolean ok = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER;
        if (ok) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                held = false;
                return on;
            }
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() > 0) {
                if (!held) {
                    held = true;
                    on = !on;
                    Toast.makeText(s, on ? "Пульт листает рилзы" : "Пульт в обычном режиме (держи OK, чтобы вернуть)",
                            Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_UP) {
                if (held || !on) return held;
                tap(s, 1);
                return true;
            }
            return true;
        }
        if (!on || (key != KeyEvent.KEYCODE_DPAD_DOWN && key != KeyEvent.KEYCODE_DPAD_UP && key != KeyEvent.KEYCODE_DPAD_RIGHT)) {
            return false;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) tap(s, 2);
            else swipe(s, key == KeyEvent.KEYCODE_DPAD_DOWN);
        }
        return true;
    }

    private static void swipe(AccessibilityService s, boolean next) {
        DisplayMetrics m = s.getResources().getDisplayMetrics();
        float x = m.widthPixels / 2f, low = m.heightPixels * 0.75f, high = m.heightPixels * 0.25f;
        Path p = new Path();
        p.moveTo(x, next ? low : high);
        p.lineTo(x, next ? high : low);
        s.dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, 220)).build(), null, null);
    }

    private static void tap(AccessibilityService s, int times) {
        DisplayMetrics m = s.getResources().getDisplayMetrics();
        GestureDescription.Builder g = new GestureDescription.Builder();
        for (int i = 0; i < times; i++) {
            Path p = new Path();
            p.moveTo(m.widthPixels / 2f, m.heightPixels / 2f);
            g.addStroke(new GestureDescription.StrokeDescription(p, i * 140L, 40));
        }
        s.dispatchGesture(g.build(), null, null);
    }
}
