package io.freemytv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Sleep timer from the home screen's corner button: each press steps off → 15 → 30 → 60 → 90 min → off. When it
 * runs out, the TV goes to standby through its own ADB (the same key the remote's power button sends).
 */
public class SleepTimer extends BroadcastReceiver {
    static final int[] MINUTES = {0, 15, 30, 60, 90};

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("sleep", Context.MODE_PRIVATE);
    }

    /** Minutes left, or 0 when off. */
    static int left(Context c) {
        long until = sp(c).getLong("until", 0);
        long ms = until - System.currentTimeMillis();
        return ms <= 0 ? 0 : (int) Math.ceil(ms / 60_000.0);
    }

    /** The next step after the current one; returns its minutes (0 = off). */
    static int step(Context c) {
        int left = left(c), next = 0;
        int index = left == 0 ? 0 : sp(c).getInt("index", 0);
        if (index + 1 < MINUTES.length) next = MINUTES[index + 1];
        set(c, next, next == 0 ? 0 : index + 1);
        return next;
    }

    private static void set(Context c, int minutes, int index) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, new Intent(c, SleepTimer.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.cancel(pi);
        long until = minutes == 0 ? 0 : System.currentTimeMillis() + minutes * 60_000L;
        sp(c).edit().putLong("until", until).putInt("index", index).apply();
        if (until != 0) am.setWindow(AlarmManager.RTC_WAKEUP, until, 30_000, pi);
    }

    @Override public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        sp(app).edit().putLong("until", 0).putInt("index", 0).apply();
        PendingResult result = goAsync();
        new Thread(() -> {
            try (Adb adb = Adb.connect(app, 10_000, null)) {
                adb.shell("input keyevent KEYCODE_SLEEP");
                AppLog.w("sleep timer: standby");
            } catch (Exception e) {
                AppLog.w("sleep timer: " + e.getMessage());
            } finally {
                result.finish();
            }
        }).start();
    }
}
