package io.freemytv;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

/**
 * A quiet fireplace after a long while without a key on the home screen: res/raw/fire.ogg ("fireplace.wav" by
 * martats on Freesound, CC0; compressed so it stays even, 2.7 min, its end crossfaded
 * into its start), played back to back by two players so there's no gap at the seam. It fades in over 10 s and plays
 * at a sixth of the loudness of 3% of the TV's maximum volume, whatever the volume is set to (the player's own gain makes up the
 * difference, so the TV's volume setting is never touched); any key fades it out.
 */
final class Ambient {
    private static final float SHARE = 0.03f;
    private static final float QUIETER = 1 / 6f;   // asked for: a sixth of the loudness of 3% of the maximum (-15.6 dB)
    private static final long FADE_IN = 10_000, FADE_OUT = 1_500;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private MediaPlayer current, next;
    private boolean on;
    private float fade;             // 0 to 1
    private long fadeAt;
    private float fadeFrom;

    private final Runnable step = new Runnable() {
        @Override public void run() {
            long t = android.os.SystemClock.uptimeMillis() - fadeAt;
            float to = on ? 1 : 0;
            float k = Math.min(1, t / (float) (on ? FADE_IN : FADE_OUT));
            fade = fadeFrom + (to - fadeFrom) * k * k * (3 - 2 * k);
            applyVolume();
            if (k < 1) {
                handler.postDelayed(this, 50);
            } else if (on) {
                handler.postDelayed(this, 2000);   // follows the remote's volume while it plays
            } else {
                release();
            }
        }
    };

    Ambient(Context c) {
        context = c.getApplicationContext();
        audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    boolean playing() {
        return on;
    }

    void start() {
        if (on) return;
        if (audio.isMusicActive()) return;   // something already plays (music on the TV itself): no fire on top of it
        try {
            if (current == null) {
                current = player();
                next = player();
                current.setNextMediaPlayer(next);
                current.setVolume(0, 0);
                current.start();
            }
        } catch (RuntimeException e) {
            AppLog.w("campfire: " + e.getMessage());
            release();
            return;
        }
        on = true;
        AppLog.w("campfire: on");
        restartFade();
    }

    void stop() {
        if (!on) return;
        on = false;
        restartFade();
    }

    private void restartFade() {
        fadeFrom = fade;
        fadeAt = android.os.SystemClock.uptimeMillis();
        handler.removeCallbacks(step);
        handler.post(step);
    }

    /** A player for the loop; when one ends, the other is already playing and this one queues up again behind it. */
    private MediaPlayer player() {
        MediaPlayer p = MediaPlayer.create(context, R.raw.fire, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                audio.generateAudioSessionId());
        p.setOnCompletionListener(done -> {
            MediaPlayer playing = done == current ? next : current;
            current = playing;
            next = done;
            try {
                done.stop();
                done.prepare();     // a local file: quick
                playing.setNextMediaPlayer(done);
            } catch (java.io.IOException | RuntimeException e) {
                AppLog.w("campfire loop: " + e.getMessage());
            }
            applyVolume();
        });
        return p;
    }

    private void applyVolume() {
        float v = fade * gain() * QUIETER;
        if (current != null) current.setVolume(v, v);
        if (next != null) next.setVolume(v, v);
    }

    /** The player's gain that turns the TV's current volume into 3% of its maximum (1 when the TV is lower than that). */
    private float gain() {
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), now = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (now <= 0) return 0;
        int want = Math.max(1, Math.round(max * SHARE));
        if (now <= want) return 1;
        int device = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
        AudioDeviceInfo routed = current == null ? null : current.getRoutedDevice();
        if (routed != null) device = routed.getType();
        try {
            float db = audio.getStreamVolumeDb(AudioManager.STREAM_MUSIC, want, device)
                    - audio.getStreamVolumeDb(AudioManager.STREAM_MUSIC, now, device);
            if (!Float.isNaN(db) && !Float.isInfinite(db)) return (float) Math.min(1, Math.pow(10, db / 20));
        } catch (RuntimeException ignored) {
        }
        float r = want / (float) now;   // no curve from the TV: a common one (volume steps roughly squared)
        return r * r;
    }

    private void release() {
        handler.removeCallbacks(step);
        for (MediaPlayer p : new MediaPlayer[] {current, next}) {
            if (p == null) continue;
            try {
                p.release();
            } catch (RuntimeException ignored) {
            }
        }
        current = next = null;
        fade = 0;
        AppLog.w("campfire: off");
    }
}
