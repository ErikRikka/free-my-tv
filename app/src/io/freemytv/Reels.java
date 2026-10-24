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
 * It opens on Reels (from the home screen), so the remote starts out scrolling them; clicking its Reels tab with the
 * pointer switches back to that, and holding OK switches either way. Back is always Back.
 */
final class Reels {
    private Reels() {}

    static final String INSTAGRAM = "com.instagram.android", LITE = "com.instagram.lite";

    /** The app in front, from the window changes the key service sees. */
    static volatile String front = "";
    private static boolean reels, held;
    private static float x = -1, y = -1;
    private static View pointer;

    static void windowChanged(AccessibilityService s, CharSequence pkg, CharSequence cls) {
        if (pkg == null) return;
        String p = pkg.toString();
        if (p.contains("inputmethod") || p.equals("com.android.systemui")) return;   // the keyboard or a toast over the app
        if (p.equals(s.getPackageName()) && "android.view.View".equals(String.valueOf(cls))) return;   // our own pointer
        if (!p.equals(front)) AppLog.w("reels: in front " + p);
        if (ours(p) && !p.equals(front)) {   // it opens on Reels (reelsIntent): the remote scrolls them
            reels = true;
            AppLog.w("reels: opened, scrolling");
        }
        front = p;
        showPointer(s, ours(p) && !reels);
        if (ours(p)) warmTouch(s);
    }

    /** The app already in front when the service starts (it only hears of the ones that open after). */
    static void findFront(AccessibilityService s) {
        Context app = s.getApplicationContext();
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        TOUCH.execute(() -> {
            try {
                if (adb == null) adb = Adb.connect(app, 0, null);
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("mResumedActivity: ActivityRecord\\{\\S+ \\S+ ([\\w.]+)/")
                        .matcher(adb.shell("dumpsys activity activities | grep mResumedActivity"));
                if (m.find()) {
                    String pkg = m.group(1);
                    main.post(() -> {   // as it was left, not as if just opened
                        if (!front.isEmpty()) return;
                        front = pkg;
                        AppLog.w("reels: in front " + pkg + " (already)");
                        showPointer(s, ours(pkg) && !reels);
                        if (ours(pkg)) warmTouch(s);
                    });
                }
            } catch (Exception e) {
                AppLog.w("reels: front app: " + e.getMessage());
            }
        });
    }

    static boolean opensOnReels(String pkg) {
        return ours(pkg);
    }

    /** Instagram (or Lite) straight on its Reels. */
    static android.content.Intent reelsIntent(String pkg) {
        return new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.instagram.com/reels/"))
                .setPackage(pkg).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static boolean ours(String pkg) {
        return INSTAGRAM.equals(pkg) || LITE.equals(pkg);
    }

    static boolean onKey(AccessibilityService s, KeyEvent e) {
        if (!ours(front)) return false;
        int key = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
            if (down && e.getRepeatCount() == 0) held = false;
            else if (down && !held) {   // held: the other way of driving it
                held = true;
                reels = !reels;
                AppLog.w("reels: held OK, " + (reels ? "scrolling" : "pointer"));
                showPointer(s, !reels);
                Toast.makeText(s, reels ? "Пульт листает рилзы (держи OK — курсор)" : "Курсор: стрелки двигают, OK нажимает (держи OK — рилзы)",
                        Toast.LENGTH_LONG).show();
            } else if (!down && !held) {
                if (reels) {
                    tap(s, center(s)[0], center(s)[1]);
                } else {
                    tap(s, x, y);
                    DisplayMetrics m = s.getResources().getDisplayMetrics();
                    if (INSTAGRAM.equals(front) && x < m.widthPixels * 0.07f && Math.abs(y - m.heightPixels * REELS_TAB) < m.heightPixels * 0.04f) {
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
                    if (INSTAGRAM.equals(front)) tap(s, m.widthPixels * LIKE_X, m.heightPixels * LIKE_Y);   // Lite's heart is elsewhere
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

    // Touches go through TouchServer, a small program of ours the TV's own ADB starts (this TV takes a service's gestures
    // and then drops them, having no touch screen); `input` through ADB if it can't start. On a thread of their own.
    private static final java.util.concurrent.ExecutorService TOUCH = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static java.io.Writer touchOut;
    private static Adb adb;

    private static void input(Context c, String command) {
        Context app = c.getApplicationContext();
        TOUCH.execute(() -> {
            try {
                if (touchOut == null) openTouch(app);
                touchOut.write(command + "\n");
                touchOut.flush();
                AppLog.w("reels: " + command);
                return;
            } catch (Exception e) {
                AppLog.w("reels: touch server: " + e.getMessage());
                touchOut = null;
            }
            shell(app, "input " + command);
        });
    }

    /** Starts TouchServer ahead of the first press, so that one isn't slow either. */
    private static void warmTouch(Context c) {
        Context app = c.getApplicationContext();
        TOUCH.execute(() -> {
            if (touchOut != null) return;
            try {
                openTouch(app);
            } catch (Exception e) {
                AppLog.w("reels: touch server: " + e.getMessage());
                touchOut = null;
            }
        });
    }

    /** Starts TouchServer and waits for it to dial in on a port of ours, with a key only it was given. */
    private static void openTouch(Context app) throws Exception {
        try (java.net.ServerSocket listen = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            listen.setSoTimeout(5000);
            String key = Long.toHexString(new java.security.SecureRandom().nextLong()) + Long.toHexString(System.nanoTime());
            shell(app, "CLASSPATH=" + app.getApplicationInfo().sourceDir + " setsid nohup app_process /system/bin "
                    + TouchServer.class.getName() + " " + listen.getLocalPort() + " " + key + " >/data/local/tmp/freemytv-touch.log 2>&1 & sleep 1");   // the session ending at once would take it along
            java.net.Socket server = listen.accept();
            server.setTcpNoDelay(true);
            touchOut = new java.io.OutputStreamWriter(server.getOutputStream());
            touchOut.write(key + "\n");
            touchOut.flush();
            AppLog.w("reels: touch server up");
        }
    }

    private static void shell(Context app, String command) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (adb == null) adb = Adb.connect(app, 0, null);
                String out = adb.shell(command).trim();
                if (!out.isEmpty()) AppLog.w("reels: shell said: " + out);
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
