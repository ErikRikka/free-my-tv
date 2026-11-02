package io.freemytv;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;

/**
 * A quiet fireplace after a long while without a key on the home screen: res/raw/fire.ogg ("fireplace.wav" by
 * martats on Freesound, CC0; 2.7 min), looped without end: each lap's last 10 s fade out while the next lap's first 10 s
 * fade in, mixed here, so there is no seam to hear. It fades in over 10 s and plays at a sixth of the loudness of 3% of
 * the TV's maximum volume, whatever the volume is set to (the track's own gain makes up the difference, so the TV's
 * volume setting is never touched); any key fades it out.
 */
final class Ambient {
    private static final float SHARE = 0.03f;
    private static final float QUIETER = 1 / 6f;   // asked for: a sixth of the loudness of 3% of the maximum (-15.6 dB)
    private static final long FADE_IN = 10_000, FADE_OUT = 1_500;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private Loop loop;
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
        if (loop == null) {
            loop = new Loop(context);
            loop.start();
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

    private void applyVolume() {
        Loop l = loop;
        if (l != null) l.volume(fade * gain() * QUIETER);
    }

    /** The track's gain that turns the TV's current volume into 3% of its maximum (1 when the TV is lower than that). */
    private float gain() {
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), now = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (now <= 0) return 0;
        int want = Math.max(1, Math.round(max * SHARE));
        if (now <= want) return 1;
        int device = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
        AudioDeviceInfo routed = loop == null ? null : loop.routed();
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
        if (loop != null) loop.finish();
        loop = null;
        fade = 0;
        AppLog.w("campfire: off");
    }

    /**
     * The sound decoded as it plays, on its own thread, lap after lap. The last 10 s of a lap are held back and, at its
     * end, mixed with the first 10 s (kept from the first lap) in an equal-power crossfade; the next lap then goes on
     * from 10 s in. Only those 20 s of sound are ever in memory.
     */
    static final class Loop extends Thread {
        private static final int XFADE_S = 10;
        private final Context context;
        private volatile boolean done;
        private volatile float volume;
        private AudioTrack track;

        Loop(Context c) {
            super("campfire");
            context = c;
        }

        void volume(float v) {
            volume = v;
            AudioTrack t = track;
            if (t != null) t.setVolume(v);
        }

        AudioDeviceInfo routed() {
            AudioTrack t = track;
            return t == null ? null : t.getRoutedDevice();
        }

        void finish() {
            done = true;
            interrupt();
        }

        @Override public void run() {
            short[] head = null, ring = null;
            int rate = 0, headN = 0;
            try {
                for (int lap = 0; !done; lap++) {
                    MediaExtractor ex = new MediaExtractor();
                    MediaCodec codec = null;
                    try {
                        android.content.res.AssetFileDescriptor fd = context.getResources().openRawResourceFd(R.raw.fire);
                        ex.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
                        fd.close();
                        ex.selectTrack(0);
                        MediaFormat f = ex.getTrackFormat(0);
                        int channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        if (rate == 0) {
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                            head = new short[rate * XFADE_S];
                            ring = new short[rate * XFADE_S];
                            track = new AudioTrack.Builder()
                                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                                    .setBufferSizeInBytes(Math.max(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO,
                                            AudioFormat.ENCODING_PCM_16BIT) * 4, rate))   // half a second
                                    .setTransferMode(AudioTrack.MODE_STREAM).build();
                            track.setVolume(volume);
                            track.play();
                        }
                        codec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME));
                        codec.configure(f, null, null, 0);
                        codec.start();
                        int held = 0, ringAt = 0;   // the ring holds the latest `held` samples, oldest at ringAt
                        int skip = lap == 0 ? 0 : headN;   // already heard, in the crossfade
                        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                        boolean inputDone = false, outputDone = false;
                        short[] mono = new short[0];
                        while (!outputDone && !done) {
                            if (!inputDone) {
                                int i = codec.dequeueInputBuffer(10_000);
                                if (i >= 0) {
                                    java.nio.ByteBuffer b = codec.getInputBuffer(i);
                                    int n = ex.readSampleData(b, 0);
                                    if (n < 0) {
                                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                        inputDone = true;
                                    } else {
                                        codec.queueInputBuffer(i, 0, n, ex.getSampleTime(), 0);
                                        ex.advance();
                                    }
                                }
                            }
                            int o = codec.dequeueOutputBuffer(info, 10_000);
                            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                channels = codec.getOutputFormat().getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                            }
                            if (o < 0) continue;
                            java.nio.ShortBuffer pcm = codec.getOutputBuffer(o).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                            int frames = info.size / 2 / Math.max(1, channels);
                            if (mono.length < frames) mono = new short[frames];
                            for (int j = 0; j < frames; j++) {
                                int sum = 0;
                                for (int ch = 0; ch < channels; ch++) sum += pcm.get(info.offset / 2 + j * channels + ch);
                                mono[j] = (short) (sum / Math.max(1, channels));
                            }
                            codec.releaseOutputBuffer(o, false);
                            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                            int from = 0;
                            if (lap == 0) {   // keep the start for the seams to come
                                int keep = Math.min(frames, head.length - headN);
                                System.arraycopy(mono, 0, head, headN, keep);
                                headN += keep;
                            } else if (skip > 0) {
                                from = Math.min(frames, skip);
                                skip -= from;
                            }
                            for (int j = from; j < frames; j++) {   // into the ring; what falls out of it is played
                                if (held == ring.length) {
                                    play(ring, ringAt, 1);
                                    ring[ringAt] = mono[j];
                                    ringAt = (ringAt + 1) % ring.length;
                                } else {
                                    ring[(ringAt + held) % ring.length] = mono[j];
                                    held++;
                                }
                            }
                            flush();
                        }
                        // The seam: the held-back end fading out into the start fading in.
                        int n = Math.min(held, headN);
                        short[] mix = new short[n];
                        for (int j = 0; j < held - n; j++) play(ring, (ringAt + j) % ring.length, 1);
                        for (int j = 0; j < n; j++) {
                            double t = (j + 0.5) / n * Math.PI / 2;
                            double v = ring[(ringAt + held - n + j) % ring.length] * Math.cos(t) + head[j] * Math.sin(t);
                            mix[j] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(v)));
                        }
                        flush();
                        write(mix, n);
                    } finally {
                        if (codec != null) {
                            try {
                                codec.stop();
                            } catch (RuntimeException ignored) {
                            }
                            codec.release();
                        }
                        ex.release();
                    }
                }
            } catch (Exception e) {
                if (!done) AppLog.w("campfire: " + e);
            } finally {
                AudioTrack t = track;
                track = null;
                if (t != null) {
                    try {
                        t.pause();
                        t.flush();
                    } catch (RuntimeException ignored) {
                    }
                    t.release();
                }
            }
        }

        private final short[] out = new short[4096];
        private int outN;

        private void play(short[] from, int at, int n) {
            for (int j = 0; j < n; j++) {
                out[outN++] = from[at + j];
                if (outN == out.length) flush();
            }
        }

        private void flush() {
            if (outN > 0) write(out, outN);
            outN = 0;
        }

        private void write(short[] pcm, int n) {
            for (int off = 0; off < n && !done; ) {
                int w = track.write(pcm, off, n - off);
                if (w <= 0) break;
                off += w;
            }
        }
    }
}
