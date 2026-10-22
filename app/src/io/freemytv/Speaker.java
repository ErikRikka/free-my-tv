package io.freemytv;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.speech.tts.TextToSpeech;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Speaks the answer sentence by sentence. With an OpenAI key: OpenAI's text-to-speech (a natural voice), raw 24 kHz audio
 * streamed straight into an AudioTrack, the next sentences already being fetched while one plays, so there are no gaps.
 * Without one (or for Claude): the TV's own voice.
 */
final class Speaker {
    private static final int RATE = 24000;
    private static final byte[] END = new byte[0];

    private final Context context;
    private final boolean cloud = Brain.openai() && !Config.OPENAI_KEY.isEmpty();
    private volatile boolean cloudBroken;   // OpenAI's voice refused (a key without the audio permission, no credits): the TV's voice takes over
    private final ExecutorService fetchers = Executors.newFixedThreadPool(2);
    private final BlockingQueue<BlockingQueue<byte[]>> playlist = new LinkedBlockingQueue<>();
    private volatile boolean stopped, pending;
    private volatile int queued, played;
    private Thread player;
    private AudioTrack track;
    private TextToSpeech tts;
    private boolean ttsReady;

    Speaker(Context c) {
        context = c;
        if (cloud) {
            player = new Thread(this::play, "speaker");
            player.start();
        }
        tts = new TextToSpeech(c, st -> {
            if (st == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("ru", "RU"));
                ttsReady = true;
            }
        });
    }

    void say(String sentence) {
        String text = sentence.trim();
        if (text.isEmpty() || stopped) return;
        queued++;
        if (!cloud || cloudBroken) {
            speakWithTv(text);
            return;
        }
        BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
        playlist.add(chunks);
        fetchers.execute(() -> fetch(text, chunks));
    }

    /** Waits until everything said so far has been heard. */
    void finish() {
        if (!cloud) {
            sleep(300);
            while (!stopped && tts != null && tts.isSpeaking()) sleep(80);
            return;
        }
        long deadline = System.currentTimeMillis() + 90_000;
        while (!stopped && played < queued && System.currentTimeMillis() < deadline) sleep(60);
        sleep(200);
        while (!stopped && cloudBroken && tts != null && tts.isSpeaking()) sleep(80);
        AudioTrack t = track;
        if (t != null && !stopped) {   // the end of the audio still sitting in the track's buffer
            long until = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < until && t.getPlaybackHeadPosition() < written) sleep(40);
        }
    }

    void stop() {
        stopped = true;
        playlist.add(new LinkedBlockingQueue<byte[]>() {{ add(END); }});
        if (tts != null) tts.stop();
        AudioTrack t = track;
        if (t != null) {
            try {
                t.pause();
                t.flush();
            } catch (RuntimeException ignored) {
            }
        }
    }

    void release() {
        stop();
        fetchers.shutdownNow();
        if (tts != null) tts.shutdown();
        AudioTrack t = track;
        if (t != null) {
            try {
                t.release();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void speakWithTv(String text) {
        for (int i = 0; i < 20 && !ttsReady; i++) sleep(100);
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_ADD, null, "s" + System.nanoTime());
    }

    private void fetch(String text, BlockingQueue<byte[]> chunks) {
        boolean ok = false;
        try {
            JSONObject req = new JSONObject().put("model", Config.OPENAI_TTS_MODEL).put("voice", Config.OPENAI_VOICE).put("input", text)
                    .put("response_format", "pcm")
                    .put("instructions", "Говори по-русски, живо и тепло, как добрый знакомый в разговоре, чуть быстрее обычного, без пафоса.");
            HttpURLConnection c = (HttpURLConnection) new URL("https://api.openai.com/v1/audio/speech").openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(30000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + Config.OPENAI_KEY);
            c.setRequestProperty("content-type", "application/json");
            try {
                try (OutputStream out = c.getOutputStream()) {
                    out.write(req.toString().getBytes(StandardCharsets.UTF_8));
                }
                if (c.getResponseCode() != 200) throw new IOException("TTS HTTP " + c.getResponseCode());
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[4800];   // 100 ms of audio
                    int n;
                    while ((n = in.read(buf)) > 0 && !stopped) {
                        chunks.add(java.util.Arrays.copyOf(buf, n));
                        ok = true;
                    }
                }
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            AppLog.w("voice: tts: " + e.getMessage());
        } finally {
            if (!ok && !stopped) {
                cloudBroken = true;
                speakWithTv(text);
            }
            chunks.add(END);
        }
    }

    private volatile long written;   // frames written to the track

    private void play() {
        int size = Math.max(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), 9600);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(size * 4).setTransferMode(AudioTrack.MODE_STREAM).build();
        track.play();
        try {
            while (!stopped) {
                BlockingQueue<byte[]> chunks = playlist.poll(500, TimeUnit.MILLISECONDS);
                if (chunks == null) continue;
                byte[] chunk;
                while (!stopped && (chunk = chunks.poll(20, TimeUnit.SECONDS)) != null && chunk != END) {
                    track.write(chunk, 0, chunk.length);
                    written += chunk.length / 2;
                }
                played++;
            }
        } catch (InterruptedException ignored) {
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
