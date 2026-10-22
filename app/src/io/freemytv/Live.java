package io.freemytv;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A live conversation, as in ChatGPT's voice mode: your voice streams to OpenAI's Realtime API and its voice streams back,
 * it notices when you start talking and stops to listen, and it acts on the TV through TvTools. Runs until close().
 */
final class Live {
    /** What the panel shows. */
    interface Screen {
        void show(String status, String heard, String answer);
    }

    private static final int RATE = 24000;
    private static final byte[] FLUSH = new byte[0];

    private final Context context;
    private final Screen screen;
    private final BlockingQueue<byte[]> audio = new LinkedBlockingQueue<>();
    private volatile boolean closed, interrupted, leaving, saidBye;
    private volatile long playUntil, lastHeard = System.currentTimeMillis();
    private SSLSocket socket;
    private OutputStream out;
    private AudioTrack track;
    private boolean echoCancel;
    private String heard = "", answer = "";

    Live(Context c, Screen s) {
        context = c;
        screen = s;
    }

    /** Talks until close(), a goodbye, an app opened, or 3 quiet minutes. */
    void run() throws Exception {
        connect();
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
.setBufferSizeInBytes(Math.max(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2, RATE / 5 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM).build();
        track.play();
        Thread player = new Thread(this::play, "live-player"), mic = new Thread(this::listen, "live-mic");
        player.start();
        mic.start();
        send(new JSONObject().put("type", "session.update").put("session", session()));
        send(new JSONObject().put("type", "response.create").put("response", new JSONObject()
                .put("instructions", "Коротко поздоровайся одной живой фразой, без вопросов списком.")));
        screen.show("● Говорите", "", "");
        try {
            read();
        } catch (IOException e) {
            if (!closed) throw e;   // closed by us: the socket's read just stops
        } finally {
            close();
            audio.add(FLUSH);
            mic.join(1500);
            player.join(1500);
            track.release();
        }
    }

    void close() {
        closed = true;
        SSLSocket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    boolean leftForApp() {
        return leaving;
    }

    private JSONObject session() throws Exception {
        JSONArray tools = new JSONArray();
        JSONArray chat = TvTools.schemas(context);
        for (int i = 0; i < chat.length(); i++) {
            JSONObject f = chat.getJSONObject(i).getJSONObject("function");
            tools.put(new JSONObject().put("type", "function").put("name", f.getString("name"))
                    .put("description", f.getString("description")).put("parameters", f.getJSONObject("parameters")));
        }
        tools.put(new JSONObject().put("type", "function").put("name", "end_conversation")
                .put("description", "Закончить разговор, когда собеседник прощается или просит замолчать. Перед этим попрощайся.")
                .put("parameters", new JSONObject().put("type", "object").put("properties", new JSONObject())));
        JSONObject pcm = new JSONObject().put("type", "audio/pcm").put("rate", RATE);
        return new JSONObject().put("type", "realtime")
                .put("instructions", Brain.system() + " Это живой голосовой разговор: говори естественно, с интонацией, как "
                        + "человек, коротко, не повторяй вопрос. Если тебя перебили, не извиняйся, просто слушай.")
                .put("audio", new JSONObject()
                        .put("input", new JSONObject().put("format", pcm)
                                .put("turn_detection", new JSONObject().put("type", "semantic_vad").put("eagerness", "high"))
                                .put("transcription", new JSONObject().put("model", "gpt-4o-mini-transcribe").put("language", "ru")))
                        .put("output", new JSONObject().put("format", pcm).put("voice", Config.OPENAI_REALTIME_VOICE)))
                .put("tools", tools);
    }

    private void handle(JSONObject e) throws Exception {
        switch (e.optString("type")) {
            case "response.created":
                interrupted = false;
                answer = "";
                break;
            case "response.output_audio.delta":
                if (!interrupted) audio.add(Base64.decode(e.optString("delta"), Base64.DEFAULT));
                break;
            case "response.output_audio.done":   // a little silence pushes the last words out of the track's buffer
                if (!interrupted) audio.add(new byte[RATE / 3 * 2]);
                break;
            case "response.output_audio_transcript.delta":
                answer += e.optString("delta");
                screen.show("", heard, answer);
                break;
            case "input_audio_buffer.speech_started":   // you started talking: it stops and listens
                lastHeard = System.currentTimeMillis();
                if (speaking()) AppLog.w("voice: live interrupted while speaking");
                interrupted = true;
                audio.clear();
                audio.add(FLUSH);
                screen.show("● Слушаю…", "", "");
                break;
            case "conversation.item.input_audio_transcription.completed":
                heard = "«" + e.optString("transcript").trim() + "»";
                AppLog.w("voice: live heard " + heard);
                screen.show("", heard, answer);
                break;
            case "response.function_call_arguments.done":
                call(e.optString("name"), e.optString("arguments"), e.optString("call_id"));
                break;
            case "response.done": {   // after the answer that follows an app opening, the panel goes
                JSONArray output = e.optJSONObject("response") != null ? e.getJSONObject("response").optJSONArray("output") : null;
                boolean calls = false;
                for (int i = 0; output != null && i < output.length(); i++) {
                    if ("function_call".equals(output.getJSONObject(i).optString("type"))) calls = true;
                }
                if (leaving && !calls) {
                    drain();
                    close();
                }
                break;
            }
            case "error":
                AppLog.w("voice: live " + e.optJSONObject("error"));
                break;
            default:
        }
    }

    private void call(String name, String args, String id) {
        new Thread(() -> {
            String result;
            if (name.equals("end_conversation")) {
                saidBye = true;
                result = "Разговор закончен.";
            } else {
                try {
                    TvTools.left = false;
                    result = TvTools.run(context, name, new JSONObject(args.isEmpty() ? "{}" : args));
                    if (TvTools.left) leaving = true;
                } catch (Exception ex) {
                    result = "Не получилось: " + ex.getMessage();
                }
            }
            AppLog.w("voice: " + name + " " + args + " -> " + result);
            try {
                send(new JSONObject().put("type", "conversation.item.create").put("item", new JSONObject()
                        .put("type", "function_call_output").put("call_id", id).put("output", result)));
                if (!saidBye) send(new JSONObject().put("type", "response.create"));
                else {
                    sleep(300);   // the goodbye's last words are still arriving
                    drain();
                    close();
                }
            } catch (Exception ex) {
                AppLog.w("voice: live " + ex);
            }
        }).start();
    }

    /** Waits until what was said has been heard (the panel then closes). */
    private void drain() {
        long until = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < until && (!audio.isEmpty() || speaking())) sleep(50);
    }

    /** Whether the TV is still saying something: by the clock, since a track's position stalls on some TVs. */
    private boolean speaking() {
        return System.currentTimeMillis() < playUntil;
    }

    // MARK: - Sound

    private static final int MIC_RATE = 16000;   // the remote's mic only works as a "voice recognition" source, at 16 kHz

    private void listen() {
        while (!closed) {
            if (!record()) return;
            AppLog.w("voice: live mic went silent, reopening it");
        }
    }

    /** Streams the mic until the conversation ends (false) or the remote stops sending sound (true: open it again). */
    private boolean record() {
        int size = Math.max(AudioRecord.getMinBufferSize(MIC_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 8192);
        AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, MIC_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, size * 2);
        AcousticEchoCanceler aec = null;
        try {
            if (r.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("микрофон недоступен");
            if (AcousticEchoCanceler.isAvailable() && (aec = AcousticEchoCanceler.create(r.getAudioSessionId())) != null) {
                echoCancel = aec.setEnabled(true) == android.media.audiofx.AudioEffect.SUCCESS;
            }
            AppLog.w("voice: live mic, echo cancel " + echoCancel);
            r.startRecording();
            short[] in = new short[MIC_RATE / 10];   // 100 ms
            byte[] up = new byte[RATE / 10 * 2], quiet = new byte[up.length];
            long lastSpoke = 0, levelAt = System.currentTimeMillis(), lastSound = levelAt;
            double loudest = 0;
            while (!closed) {
                int n = r.read(in, 0, in.length);
                if (n <= 0) continue;
                int frames = upsample(in, n, up);
                int peak = 0;
                for (int i = 0; i < n; i++) peak = Math.max(peak, Math.abs(in[i]));
                loudest = Math.max(loudest, peak);
                long now = System.currentTimeMillis();
                if (peak > 60) lastSound = now;   // a room is never this quiet: the remote has switched its mic off
                else if (now - lastSound > 3000) return true;
                if (System.currentTimeMillis() - levelAt > 2000) {   // to tell a silent mic from a quiet room
                    AppLog.w("voice: live mic level " + (int) loudest);
                    loudest = 0;
                    levelAt = System.currentTimeMillis();
                }
                // Muted while it speaks: without echo cancelling the TV can hear its own voice and interrupt itself.
                boolean guard = !echoCancel && Config.LIVE_MUTE_WHILE_SPEAKING;
                boolean deaf = guard && (speaking() || !audio.isEmpty());
                if (deaf) lastSpoke = System.currentTimeMillis();
                boolean tail = guard && System.currentTimeMillis() - lastSpoke < 400;   // the room's echo
                send(new JSONObject().put("type", "input_audio_buffer.append")
                        .put("audio", Base64.encodeToString(deaf || tail ? quiet : up, 0, frames * 2, Base64.NO_WRAP)));
                if (!speaking() && audio.isEmpty() && System.currentTimeMillis() - lastHeard > 180_000) {
                    AppLog.w("voice: live, 3 quiet minutes");
                    close();
                }
            }
        } catch (Exception e) {
            if (!closed) AppLog.w("voice: live mic " + e);
            close();
        } finally {
            r.release();
            if (aec != null) aec.release();
        }
        return false;
    }

    /** 16 kHz to the 24 kHz the Realtime API takes (3 samples for every 2), into little-endian bytes; returns the frames. */
    private static int upsample(short[] in, int n, byte[] out) {
        int frames = n * 3 / 2;
        for (int i = 0; i < frames; i++) {
            int a = i * 2 / 3, b = Math.min(a + 1, n - 1);
            int rem = i * 2 % 3;
            int v = (in[a] * (3 - rem) + in[b] * rem) / 3;
            out[2 * i] = (byte) v;
            out[2 * i + 1] = (byte) (v >> 8);
        }
        return frames;
    }

    private void play() {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        try {
            while (!closed || !audio.isEmpty()) {
                byte[] chunk = audio.poll(200, TimeUnit.MILLISECONDS);
                if (chunk == null) {
                    if (head.size() > 0) {   // the end of a short answer
                        write(head.toByteArray());
                        head.reset();
                    }
                    continue;
                }
                if (chunk == FLUSH) {
                    head.reset();
                    track.pause();
                    track.flush();
                    playUntil = 0;
                    track.play();
                    continue;
                }
                if (!speaking() && head.size() < RATE / 4 * 2) {   // 250 ms in hand before a new answer starts
                    head.write(chunk, 0, chunk.length);
                    if (head.size() < RATE / 4 * 2) continue;
                    chunk = head.toByteArray();
                    head.reset();
                }
                write(chunk);
            }
        } catch (InterruptedException ignored) {
        }
    }

    private void write(byte[] pcm) {
        int len = pcm.length & ~1;
        for (int off = 0; off < len && !interrupted; ) {
            int n = track.write(pcm, off, len - off);
            if (n <= 0) break;
            off += n;
            long now = System.currentTimeMillis();
            playUntil = Math.max(playUntil, now) + n / 2 * 1000L / RATE;
        }
    }

    // MARK: - WebSocket

    private void connect() throws IOException {
        String host = "api.openai.com";
        socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
        socket.connect(new InetSocketAddress(host, 443), 8000);
        socket.setSoTimeout(0);
        socket.startHandshake();
        out = socket.getOutputStream();
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        out.write(("GET /v1/realtime?model=" + Config.OPENAI_REALTIME_MODEL + " HTTP/1.1\r\nHost: " + host
                + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: "
                + Base64.encodeToString(nonce, Base64.NO_WRAP) + "\r\nAuthorization: Bearer " + Config.OPENAI_KEY + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.flush();
        InputStream in = socket.getInputStream();
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) throw new IOException("live: connection closed");
            head.append((char) b);
        }
        String status = head.substring(0, head.indexOf("\r\n"));
        if (!status.contains(" 101 ")) {
            throw new IOException("ChatGPT live: " + status + (status.contains(" 401 ") ? " (неверный ключ)" : status.contains(" 403 ")
                    ? " (ключу не хватает прав на Realtime)" : ""));
        }
    }

    private void read() throws Exception {
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        while (!closed) {
            int b0 = in.read(), b1 = in.read();
            if (b0 < 0 || b1 < 0) break;
            long n = b1 & 0x7F;
            if (n == 126) n = (in.read() << 8) | in.read();
            else if (n == 127) {
                n = 0;
                for (int i = 0; i < 8; i++) n = (n << 8) | in.read();
            }
            byte[] p = new byte[(int) n];
            for (int off = 0; off < n; ) {
                int r = in.read(p, off, (int) n - off);
                if (r < 0) throw new IOException("live: connection closed");
                off += r;
            }
            int op = b0 & 0x0F;
            if (op == 8) break;
            if (op == 9) {
                frame(0xA, p);
                continue;
            }
            if (op == 0xA) continue;
            msg.write(p);
            if ((b0 & 0x80) != 0) {
                handle(new JSONObject(msg.toString("UTF-8")));
                msg.reset();
            }
        }
    }

    private void send(JSONObject o) throws IOException {
        frame(0x1, o.toString().getBytes(StandardCharsets.UTF_8));
    }

    private final SecureRandom random = new SecureRandom();

    private synchronized void frame(int op, byte[] data) throws IOException {
        if (out == null) return;
        ByteArrayOutputStream f = new ByteArrayOutputStream(data.length + 14);
        f.write(0x80 | op);
        if (data.length < 126) f.write(0x80 | data.length);
        else if (data.length < 65536) {
            f.write(0x80 | 126);
            f.write(data.length >> 8);
            f.write(data.length);
        } else {
            f.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) f.write((int) ((long) data.length >> (8 * i)));
        }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        f.write(mask, 0, 4);
        for (int i = 0; i < data.length; i++) f.write(data[i] ^ mask[i & 3]);
        f.writeTo(out);
        out.flush();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
