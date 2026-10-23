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
 * - Reels: Down the next reel, Up the one before, OK pause, Right like, Left back to the pointer (on the tabs).
 * - Cursor (to sign in, for dialogs and the rest): the arrows move a pointer, OK taps under it, keyboard keys included.
 * Clicking its Reels tab with the pointer switches to Reels; holding OK switches either way. Back is always Back.
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
        if (!p.equals(front)) AppLog.w("reels: in front " + p);
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
                if (reels) {
                    tap(s, center(s)[0], center(s)[1]);
                } else {
                    tap(s, x, y);
                    DisplayMetrics m = s.getResources().getDisplayMetrics();
                    if (x < m.widthPixels * 0.07f && Math.abs(y - m.heightPixels * REELS_TAB) < m.heightPixels * 0.04f) {
                        reels = true;   // its Reels tab: from now on the arrows go through the reels
                        showPointer(s, false);
                    }
                }
            }
            return true;
        }
        boolean arrow = key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_UP
                || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (!arrow) return false;
        if (reels) {
            if (down && e.getRepeatCount() == 0) {
                DisplayMetrics m = s.getResources().getDisplayMetrics();
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    tap(s, m.widthPixels * LIKE_X, m.heightPixels * LIKE_Y);
                } else if (key == KeyEvent.KEYCODE_DPAD_LEFT) {   // to the tabs on the left, with the pointer on Reels
                    reels = false;
                    x = m.widthPixels * 0.033f;
                    y = m.heightPixels * REELS_TAB;
                    showPointer(s, true);
                } else {
                    swipe(s, key == KeyEvent.KEYCODE_DPAD_DOWN);
                }
            }
            return true;
        }
        if (down) movePointer(s, key, e.getRepeatCount());
        return true;
    }

    // Where things are in its landscape layout on a 16:9 screen, as parts of the width and height.
    private static final float REELS_TAB = 0.395f, LIKE_X = 0.691f, LIKE_Y = 0.304f;

    private static float[] center(Context c) {
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        return new float[] {m.widthPixels / 2f, m.heightPixels / 2f};
    }

    // Touches go through TouchServer, a small program of ours the TV's own ADB starts once (this TV takes a service's
    // gestures and then drops them, having no touch screen); `input` through ADB if it can't start. On a thread of their own.
    private static final java.util.concurrent.ExecutorService TOUCH = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static android.net.LocalSocket touch;
    private static java.io.Writer touchOut;
    private static boolean fresh;
    private static Adb adb;

    private static void input(Context c, String command) {
        Context app = c.getApplicationContext();
        TOUCH.execute(() -> {
            try {
                if (touchOut == null) openTouch(app);
                touchOut.write(command + "\n");
                touchOut.flush();
                return;
            } catch (Exception e) {
                AppLog.w("reels: touch server: " + e.getMessage());
                closeTouch();
            }
            shell(app, "input " + command);
        });
    }

    /** Connects to TouchServer, starting it the first time (and again once per run of this process, after an update). */
    private static void openTouch(Context app) throws Exception {
        if (!fresh) {
            fresh = true;
            try {
                connectTouch();
                touchOut.write("quit\n");   // the one left from an older version
                touchOut.flush();
                closeTouch();
                Thread.sleep(150);
            } catch (Exception ignored) {
                closeTouch();
            }
        } else {
            try {
                connectTouch();
                return;
            } catch (Exception ignored) {
                closeTouch();
            }
        }
        String apk = app.getApplicationInfo().sourceDir;
        shell(app, "CLASSPATH=" + apk + " setsid nohup app_process /system/bin " + TouchServer.class.getName() + " "
                + android.os.Process.myUid() + " >/dev/null 2>&1 &");
        for (int i = 0; i < 20; i++) {
            Thread.sleep(150);
            try {
                connectTouch();
                AppLog.w("reels: touch server up");
                return;
            } catch (Exception e) {
                closeTouch();
            }
        }
        throw new java.io.IOException("didn't start");
    }

    private static void connectTouch() throws java.io.IOException {
        touch = new android.net.LocalSocket();
        touch.connect(new android.net.LocalSocketAddress(TouchServer.SOCKET));
        touchOut = new java.io.OutputStreamWriter(touch.getOutputStream());
    }

    private static void closeTouch() {
        try {
            if (touch != null) touch.close();
        } catch (Exception ignored) {
        }
        touch = null;
        touchOut = null;
    }

    private static void shell(Context app, String command) {
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
    }

    private static void swipe(AccessibilityService s, boolean next) {
        DisplayMetrics m = s.getResources().getDisplayMetrics();
        // Two thirds of the screen, quickly: it turns the page only past half of it or with a real flick.
        int cx = m.widthPixels / 2, low = Math.round(m.heightPixels * 0.85f), high = Math.round(m.heightPixels * 0.18f);
        input(s, "swipe " + cx + " " + (next ? low : high) + " " + cx + " " + (next ? high : low) + " 160");
    }

    private static void tap(AccessibilityService s, float tx, float ty) {
        input(s, "tap " + Math.round(tx) + " " + Math.round(ty));
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
