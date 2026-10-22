package io.freemytv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.voice.VoiceInteractionSession;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/**
 * The assistant: the remote's mic button opens this session. It records your voice (stops by itself when you pause),
 * sends it to a whisper.cpp server on your computer (config.properties "whisper_url"), shows what it heard and, with a
 * key (see Brain), the answer from ChatGPT or Claude, spoken aloud.
 */
final class VoiceSession extends VoiceInteractionSession {
    private static final int RATE = 16000;
    private final Context context;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status, heard, answer;
    private volatile boolean busy;
    private TextToSpeech tts;
    private boolean ttsReady;

    VoiceSession(Context c) {
        super(c);
        context = c;
    }

    @Override public View onCreateContentView() {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(context.getResources().getDisplayMetrics().widthPixels * 0.022f);
        box.setPadding(pad * 2, pad, pad * 2, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6141416);
        bg.setCornerRadius(pad);
        bg.setStroke(2, 0x33FFFFFF);
        box.setBackground(bg);
        float w = context.getResources().getDisplayMetrics().widthPixels;
        status = text(w * 0.016f, 0xFFAEAEB2, Typeface.DEFAULT_BOLD);
        heard = text(w * 0.024f, 0xFFFFFFFF, Typeface.DEFAULT_BOLD);
        answer = text(w * 0.020f, 0xFFE8E8EC, Typeface.DEFAULT);
        box.addView(status);
        box.addView(heard);
        box.addView(answer);
        LinearLayout frame = new LinearLayout(context);
        frame.setGravity(Gravity.BOTTOM);
        frame.setPadding(pad * 3, 0, pad * 3, pad * 5);
        frame.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return frame;
    }

    private TextView text(float px, int color, Typeface face) {
        TextView t = new TextView(context);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setPadding(0, 6, 0, 6);
        return t;
    }

    @Override public void onShow(Bundle args, int flags) {
        super.onShow(args, flags);
        if (busy) return;
        busy = true;
        if (tts == null) {
            tts = new TextToSpeech(context, st -> {
                if (st == TextToSpeech.SUCCESS) {
                    tts.setLanguage(new Locale("ru", "RU"));
                    ttsReady = true;
                }
            });
        } else {
            tts.stop();
        }
        show("Слушаю…", "", "");
        new Thread(this::run).start();
    }

    private void run() {
        try {
            byte[] pcm = listen();
            if (pcm == null) {
                show("", "Не расслышал, нажми микрофон ещё раз", "");
                finishLater(3000);
                return;
            }
            show("Распознаю…", "", "");
            String said = Whisper.transcribe(Config.WHISPER_URL, pcm, RATE);
            AppLog.w("voice: heard \"" + said + "\"");
            if (said.isEmpty()) {
                show("", "Ничего не разобрал", "");
                finishLater(3000);
                return;
            }
            show(Brain.key().isEmpty() ? "" : "Думаю…", "«" + said + "»", "");
            if (Brain.key().isEmpty()) {
                show("Ключ " + Brain.name() + " не задан (" + (Brain.openai() ? "openai_key" : "anthropic_key") + " в config.properties)", "«" + said + "»", "");
                finishLater(8000);
                return;
            }
            String reply = Brain.ask(said);
            show("", "«" + said + "»", reply);
            speak(reply);
            finishLater(Math.min(30000, 6000 + reply.length() * 70L));
        } catch (Exception e) {
            AppLog.w("voice: " + e);
            show("Ошибка", String.valueOf(e.getMessage()), "");
            finishLater(6000);
        } finally {
            busy = false;
        }
    }

    /** Records until you've said something and paused (or 15 s); 16 kHz mono PCM, or null when nobody spoke. */
    private byte[] listen() {
        int size = Math.max(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 8192);
        AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, size);
        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
            r.release();
            throw new IllegalStateException("микрофон недоступен");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        short[] buf = new short[RATE / 20];
        try {
            r.startRecording();
            long start = System.currentTimeMillis(), lastVoice = 0;
            double floor = 0;
            int blocks = 0;
            boolean spoke = false;
            while (true) {
                int n = r.read(buf, 0, buf.length);
                if (n <= 0) continue;
                double e = 0;
                for (int i = 0; i < n; i++) e += buf[i] * (double) buf[i];
                double rms = Math.sqrt(e / n);
                long now = System.currentTimeMillis();
                if (blocks < 6) floor = Math.max(floor, rms);   // the first 0.3 s: how quiet the room is
                blocks++;
                double threshold = Math.max(450, floor * 2.5);
                if (rms > threshold) {
                    spoke = true;
                    lastVoice = now;
                }
                if (spoke || now - start < 1000) {
                    for (int i = 0; i < n; i++) {
                        out.write(buf[i] & 0xFF);
                        out.write((buf[i] >> 8) & 0xFF);
                    }
                }
                if (spoke && now - lastVoice > 1100) break;
                if (!spoke && now - start > 7000) return null;
                if (now - start > 15000) break;
            }
        } finally {
            r.release();
        }
        return out.toByteArray();
    }

    private void speak(String text) {
        for (int i = 0; i < 20 && !ttsReady; i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
        }
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "answer");
    }

    private void show(String s, String h, String a) {
        ui.post(() -> {
            if (status == null) return;
            status.setText(s);
            status.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
            heard.setText(h);
            heard.setVisibility(h.isEmpty() ? View.GONE : View.VISIBLE);
            answer.setText(a);
            answer.setVisibility(a.isEmpty() ? View.GONE : View.VISIBLE);
        });
    }

    private void finishLater(long ms) {
        ui.postDelayed(this::hide, ms);
    }

    @Override public void onHide() {
        super.onHide();
        if (tts != null) tts.stop();
    }

    @Override public void onDestroy() {
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
        super.onDestroy();
    }
}
