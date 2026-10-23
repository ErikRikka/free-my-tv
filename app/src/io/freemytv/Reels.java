package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * Instagram's phone app on the TV, driven by the remote. It only knows fingers, so while it's in front the remote's keys
 * become touches, in one of two ways:
 * - Reels: Down the next reel, Up the one before, OK pause, Right like (a double tap).
 * - Cursor (to sign in, for dialogs and the rest): the arrows move a pointer, OK taps under it, keyboard keys included.
 * Holding OK switches between the two, and the choice stays. Back is always Back.
 */
final class Reels {
    private Reels() {}

    static final String INSTAGRAM = "com.instagram.android";

    /** The app in front, from the window changes the key service sees. */
    static volatile String front = "";
    // The way picked last time (its windows don't say which screen they show): the pointer at first, to sign in.
    private static boolean reels, held;
    private static float x = -1, y = -1;
    private static View pointer;

    static void windowChanged(AccessibilityService s, CharSequence pkg, CharSequence cls) {
        if (pkg == null) return;
        String p = pkg.toString();
        if (p.contains("inputmethod") || p.equals("com.android.systemui")) return;   // the keyboard or a toast over the app
        if (p.equals(s.getPackageName()) && "android.view.View".equals(String.valueOf(cls))) return;   // our own pointer
        if (!p.equals(front)) AppLog.w("reels: in front " + p + " " + cls);
        front = p;
        showPointer(s, INSTAGRAM.equals(p) && !reels);
    }

    static boolean onKey(AccessibilityService s, KeyEvent e) {
        if (!INSTAGRAM.equals(front)) return false;
        int key = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
            if (down && e.getRepeatCount() == 0) held = false;
            else if (down && !held) {   // held: the other way of driving it
                held = true;
                reels = !reels;
                showPointer(s, !reels);
                Toast.makeText(s, reels ? "Пульт листает рилзы (держи OK — курсор)" : "Курсор: стрелки двигают, OK нажимает (держи OK — рилзы)",
                        Toast.LENGTH_LONG).show();
            } else if (!down && !held) {
                if (reels) tap(s, center(s)[0], center(s)[1], 1);
                else tap(s, x, y, 1);
            }
            return true;
        }
        boolean arrow = key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_UP
                || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (!arrow) return false;
        if (reels) {
            if (down && e.getRepeatCount() == 0) {
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) tap(s, center(s)[0], center(s)[1], 2);
                else if (key != KeyEvent.KEYCODE_DPAD_LEFT) swipe(s, key == KeyEvent.KEYCODE_DPAD_DOWN);
            }
            return true;
        }
        if (down) movePointer(s, key, e.getRepeatCount());
        return true;
    }

    private static float[] center(Context c) {
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        return new float[] {m.widthPixels / 2f, m.heightPixels / 2f};
    }

    // Touches go through the TV's own ADB (the shell's `input`, as from a computer): this TV accepts a service's gestures
    // and then drops them, having no touch screen. About half a second each, on a thread of their own.
    private static final java.util.concurrent.ExecutorService TOUCH = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static Adb adb;

    private static void input(Context c, String command) {
        Context app = c.getApplicationContext();
        TOUCH.execute(() -> {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    if (adb == null) adb = Adb.connect(app, 0, null);
                    adb.shell(command);
                    return;
                } catch (Exception e) {
                    AppLog.w("reels: " + command + ": " + e.getMessage());
                    if (adb != null) adb.close();
                    adb = null;
                }
            }
        });
    }

    private static void swipe(AccessibilityService s, boolean next) {
        DisplayMetrics m = s.getResources().getDisplayMetrics();
        int cx = m.widthPixels / 2, low = Math.round(m.heightPixels * 0.75f), high = Math.round(m.heightPixels * 0.25f);
        input(s, "input swipe " + cx + " " + (next ? low : high) + " " + cx + " " + (next ? high : low) + " 200");
    }

    private static void tap(AccessibilityService s, float tx, float ty, int times) {
        String one = "input tap " + Math.round(tx) + " " + Math.round(ty);
        input(s, times == 1 ? one : one + " & sleep 0.12; " + one + "; wait");   // a double tap: the two close together
    }

    // MARK: - The pointer

    private static void movePointer(AccessibilityService s, int key, int repeat) {
        DisplayMetrics m = s.getResources().getDisplayMetrics();
        float step = m.heightPixels * (repeat < 3 ? 0.03f : repeat < 10 ? 0.06f : 0.1f);   // faster the longer it's held
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) x -= step;
        if (key == KeyEvent.KEYCODE_DPAD_RIGHT) x += step;
        if (key == KeyEvent.KEYCODE_DPAD_UP) y -= step;
        if (key == KeyEvent.KEYCODE_DPAD_DOWN) y += step;
        x = Math.max(0, Math.min(m.widthPixels - 1, x));
        y = Math.max(0, Math.min(m.heightPixels - 1, y));
        placePointer(s);
    }

    private static void showPointer(AccessibilityService s, boolean show) {
        WindowManager wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
        if (show && pointer == null) {
            if (x < 0) {
                float[] c = center(s);
                x = c[0];
                y = c[1];
            }
            pointer = new View(s) {
                final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);

                @Override protected void onDraw(Canvas c) {
                    float r = getWidth() / 2f;
                    fill.setColor(0xCCFFFFFF);
                    ring.setColor(0xFF1E1E1E);
                    ring.setStyle(Paint.Style.STROKE);
                    ring.setStrokeWidth(r * 0.22f);
                    c.drawCircle(r, r, r * 0.7f, fill);
                    c.drawCircle(r, r, r * 0.7f, ring);
                }
            };
            wm.addView(pointer, params(s));
        } else if (!show && pointer != null) {
            wm.removeView(pointer);
            pointer = null;
        }
    }

    private static void placePointer(AccessibilityService s) {
        if (pointer == null) return;
        ((WindowManager) s.getSystemService(Context.WINDOW_SERVICE)).updateViewLayout(pointer, params(s));
    }

    private static WindowManager.LayoutParams params(Context c) {
        int size = Math.round(c.getResources().getDisplayMetrics().heightPixels * 0.035f);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = Math.round(x - size / 2f);
        p.y = Math.round(y - size / 2f);
        return p;
    }
}
