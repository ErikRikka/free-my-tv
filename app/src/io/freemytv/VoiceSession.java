package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.voice.VoiceInteractionSession;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.util.regex.Pattern;

/**
 * The assistant. The remote's mic button opens this session and it stays open as a conversation: you speak, it answers
 * aloud (ChatGPT through Brain, spoken by Speaker), then it listens again, until you say "хватит" / "пока", stay silent
 * for a while, or ask it to open something. Your voice goes to a whisper.cpp server on your computer (whisper_url).
 */
final class VoiceSession extends VoiceInteractionSession {
    private static final int RATE = 16000;
    private static final Pattern STOP = Pattern.compile("^(стоп|хватит|отмена|отбой|пока|до свидания|всё|все|спасибо|ладно|закройся|выход|"
            + "спасибо большое|всё спасибо|все спасибо|пока пока)( .*)?$");
    private static final Pattern JUNK = Pattern.compile("(?i)субтитр|продолжение следует|спасибо за просмотр|редактор|корректор|"
            + "dimatorzok|amara\\.org");

    private final Context context;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status, heard, answer;
    private volatile boolean busy, closing;
    private Speaker speaker;
    private volatile Live live;
    private volatile long endedAt;

    VoiceSession(Context c) {
        super(c);
        context = c;
    }

    @Override public View onCreateContentView() {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        float w = context.getResources().getDisplayMetrics().widthPixels;
        int pad = Math.round(w * 0.022f);
        box.setPadding(pad * 2, pad, pad * 2, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6141416);
        bg.setCornerRadius(pad * 0.7f);
        bg.setStroke(2, 0x33FFFFFF);
        box.setBackground(bg);
        status = text(w * 0.016f, 0xFFAEAEB2, Typeface.DEFAULT_BOLD);
        heard = text(w * 0.022f, 0xFFFFFFFF, Typeface.DEFAULT_BOLD);
        answer = text(w * 0.019f, 0xFFE8E8EC, Typeface.DEFAULT);
        box.addView(status);
        box.addView(heard);
        box.addView(answer);
        LinearLayout frame = new LinearLayout(context);
        frame.setGravity(Gravity.BOTTOM);
        frame.setPadding(pad * 3, 0, pad * 3, pad * 4);
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
        Live.note(context, "panel shown" + (busy ? ", mic button" : ""));
        ui.removeCallbacksAndMessages(null);   // a close still pending from the last conversation would end this one
        if (busy) {   // the mic button again: the conversation ends, or wakes the remote's mic
            micButton();
            return;
        }
        if (System.currentTimeMillis() - endedAt < 1500) {   // the same press that ended it, arriving a second way
            finish();
            return;
        }
        busy = true;
        closing = false;
        TvTools.left = false;
        TvTools.launcher = intent -> {
            try {
                startAssistantActivity(intent);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        };
        speaker = live() ? null : new Speaker(context);
        show(live() ? "Подключаюсь…" : "● Слушаю…", "", "");
        new Thread(this::conversation).start();
    }

    private void conversation() {
        if (live()) {
            talkLive();
            return;
        }
        try {
            boolean first = true;
            int empty = 0;
            while (!closing) {
                byte[] pcm = listen(first ? 7000 : 9000);
                if (pcm == null) {
                    if (first) show("", "Не расслышал, нажми микрофон ещё раз", "");
                    break;
                }
                show("Распознаю…", "", "");
                String said = clean(Whisper.transcribe(Config.WHISPER_URL, pcm, RATE));
                AppLog.w("voice: heard \"" + said + "\"");
                if (said.isEmpty()) {
                    if (++empty >= 2) break;
                    show("● Слушаю…", "", "");
                    continue;
                }
                empty = 0;
                first = false;
                if (STOP.matcher(normalize(said)).matches()) {
                    show("", "«" + said + "»", "До встречи!");
                    speaker.say("До встречи!");
                    speaker.finish();
                    break;
                }
                if (Brain.key().isEmpty()) {
                    show("Ключ " + Brain.name() + " не задан (" + (Brain.openai() ? "openai_key" : "anthropic_key") + " в config.properties)",
                            "«" + said + "»", "");
                    break;
                }
                answerTo(said);
                if (TvTools.left) break;
                sleep(350);   // the room's echo of the last words
                show("● Слушаю…", "«" + said + "»", answer.getText().toString());
            }
        } catch (Exception e) {
            AppLog.w("voice: " + e);
            show("Ошибка", String.valueOf(e.getMessage()), "");
            sleep(4000);
        } finally {
            if (speaker != null) {
                speaker.finish();
                speaker.release();
                speaker = null;
            }
            busy = false;
            ui.postDelayed(this::finish, 1200);
        }
    }

    private static boolean live() {
        return Config.VOICE_MODE.equalsIgnoreCase("live") && !Config.OPENAI_KEY.isEmpty();
    }

    private void talkLive() {
        try {
            Live l = new Live(context, this::show);
            live = l;
            if (!closing) l.run();
        } catch (Exception e) {
            Live.note(context, "live failed: " + e);
            show("Ошибка", String.valueOf(e.getMessage()), "");
            sleep(4000);
        } finally {
            live = null;
            if (speaker != null) {
                speaker.release();
                speaker = null;
            }
            busy = false;
            ui.postDelayed(this::finish, 300);
        }
    }

    private void micButton() {
        Live l = live;
        if (l != null && l.micAsleep()) l.wake();
        else end();
    }

    private void end() {
        closing = true;
        endedAt = System.currentTimeMillis();
        Live l = live;
        if (l != null) l.close();
        if (speaker != null) speaker.stop();
        show("", "", "До встречи!");
        ui.postDelayed(this::finish, 400);
    }

    @Override public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_SEARCH || keyCode == android.view.KeyEvent.KEYCODE_VOICE_ASSIST
                || keyCode == android.view.KeyEvent.KEYCODE_ASSIST) {
            if (event.getRepeatCount() == 0) micButton();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** Asks the brain and speaks each sentence as soon as it is written. */
    private void answerTo(String said) throws Exception {
        show("Думаю…", "«" + said + "»", "");
        final StringBuilder shown = new StringBuilder(), sentence = new StringBuilder();
        long t0 = System.currentTimeMillis();
        String reply = Brain.ask(context, said, delta -> {
            shown.append(delta);
            sentence.append(delta);
            show("", "«" + said + "»", shown.toString());
            int cut = lastSentenceEnd(sentence);
            if (cut > 0) {
                speaker.say(sentence.substring(0, cut));
                sentence.delete(0, cut);
            }
        });
        if (sentence.toString().trim().length() > 0) speaker.say(sentence.toString());
        AppLog.w("voice: answer (" + (System.currentTimeMillis() - t0) + " ms to write): " + reply);
        speaker.finish();
        AppLog.w("voice: spoken in " + (System.currentTimeMillis() - t0) + " ms");
    }

    /** Index just past the last finished sentence in the text so far (a sentence of at least 10 letters), or 0. */
    private static int lastSentenceEnd(CharSequence s) {
        for (int i = s.length() - 2; i >= 9; i--) {
            char c = s.charAt(i);
            if ((c == '.' || c == '!' || c == '?' || c == '…' || c == '\n') && Character.isWhitespace(s.charAt(i + 1))) return i + 1;
        }
        return 0;
    }

    private static String normalize(String s) {
        return s.toLowerCase().replace('ё', 'е').replaceAll("[^\\p{L}\\p{N} ]+", " ").replaceAll("\\s+", " ").trim();
    }

    private static String clean(String s) {
        String t = s.trim();
        if (t.length() < 2 || JUNK.matcher(t).find()) return "";
        return t;
    }

    /** Records until you've said something and paused (0.7 s), or 15 s; 16 kHz mono PCM, or null when nobody spoke. */
    private byte[] listen(long waitForSpeech) {
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
            long start = System.currentTimeMillis(), lastVoice = 0, firstVoice = 0;
            double floor = 0;
            int blocks = 0;
            boolean spoke = false;
            while (!closing) {
                int n = r.read(buf, 0, buf.length);
                if (n <= 0) continue;
                double e = 0;
                for (int i = 0; i < n; i++) e += buf[i] * (double) buf[i];
                double rms = Math.sqrt(e / n);
                long now = System.currentTimeMillis();
                if (blocks < 6) floor = Math.max(floor, rms);   // the first 0.3 s: how quiet the room is
                blocks++;
                if (rms > Math.max(450, floor * 2.5)) {
                    if (!spoke) firstVoice = now;
                    spoke = true;
                    lastVoice = now;
                }
                if (spoke || now - start < 1000) {
                    for (int i = 0; i < n; i++) {
                        out.write(buf[i] & 0xFF);
                        out.write((buf[i] >> 8) & 0xFF);
                    }
                }
                if (spoke && now - lastVoice > 700 && lastVoice - firstVoice > 250) break;
                if (spoke && now - lastVoice > 700) {   // a click or a cough, not speech: start over
                    spoke = false;
                    out.reset();
                    start = now;
                }
                if (!spoke && now - start > waitForSpeech) return null;
                if (now - start > 15000) break;
            }
        } finally {
            r.release();
        }
        if (closing || out.size() < 8000) return null;   // closed meanwhile, or under 0.25 s of sound
        return out.toByteArray();
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

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    // Pressing the mic button first closes "system dialogs", this panel among them, and only then shows the session
    // again: closing waits a moment, and the press (onShow) cancels it, so the conversation goes on.
    private final Runnable closeForDialogs = this::hide;

    @Override public void onCloseSystemDialogs() {
        ui.postDelayed(closeForDialogs, 600);
    }

    @Override public void onHide() {
        super.onHide();
        Live.note(context, "panel hidden");
        closing = true;
        Live l = live;
        if (l != null) l.close();
        if (speaker != null) speaker.stop();
    }
}
