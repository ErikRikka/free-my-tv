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
    private volatile boolean closed, interrupted, leaving, saidBye, micAsleep;
    private volatile long playUntil, lastHeard = System.currentTimeMillis();
    private SSLSocket socket;
    private OutputStream out;
    private AudioTrack track;
    private boolean echoCancel;
    private String heard = "", answer = "";
    private static final int KEEP_ITEMS = 16;
    private final java.util.List<String> kept = new java.util.ArrayList<>();
    private final long[] tokens = new long[5];   // input audio, input text, of them cached, output audio, output text

    Live(Context c, Screen s) {
        context = c;
        screen = s;
    }

    /** Talks until close(), a goodbye, an app opened, or 3 quiet minutes. */
    void run() throws Exception {
        log("live start, " + Config.OPENAI_REALTIME_MODEL);
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
        screen.show("● Говорите", "", "");
        try {
            read();
        } catch (IOException e) {
            if (!closed) throw e;   // closed by us: the socket's read just stops
        } finally {
            close();
            audio.add(FLUSH);
            mic.join(1500);
            player.join(3000);
            if (!player.isAlive()) track.release();
            log("live tokens: in audio " + tokens[0] + ", in text " + tokens[1] + " (cached " + tokens[2] + "), out audio "
                    + tokens[3] + ", out text " + tokens[4]);
            save();
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
                .put("instructions", Brain.system(context) + ReelsActivity.nowShowing(context) + " Это живой голосовой разговор: говори естественно, с интонацией, как "
                        + "человек, коротко, не повторяй вопрос. Если тебя перебили, не извиняйся, просто слушай.")
                .put("audio", new JSONObject()
                        .put("input", new JSONObject().put("format", pcm)
                                .put("turn_detection", new JSONObject().put("type", "semantic_vad").put("eagerness", "high"))
                                .put("transcription", new JSONObject().put("model", "gpt-4o-mini-transcribe").put("language", "ru")))
                        .put("output", new JSONObject().put("format", pcm).put("voice", Config.OPENAI_REALTIME_VOICE)
                                .put("speed", Config.OPENAI_VOICE_SPEED)))
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
            case "response.output_audio_transcript.done":
                said("assistant", e.optString("transcript"));
                break;
            case "response.output_audio_transcript.delta":
                answer += e.optString("delta");
                screen.show("", heard, answer);
                break;
            case "input_audio_buffer.speech_started":   // you started talking: it stops and listens
                lastHeard = System.currentTimeMillis();
                if (speaking()) log("live interrupted while speaking");
                interrupted = true;
                audio.clear();
                audio.add(FLUSH);
                screen.show("● Слушаю…", "", "");
                break;
            case "conversation.item.input_audio_transcription.completed":
                said("user", e.optString("transcript"));
                heard = "«" + e.optString("transcript").trim() + "»";
                log("live heard " + heard);
                screen.show("", heard, answer);
                break;
            case "response.function_call_arguments.done":   // run when the response is done, all of its calls together
                synchronized (calls) {
                    calls.add(new String[] {e.optString("name"), e.optString("arguments"), e.optString("call_id")});
                }
                break;
            case "conversation.item.done": {   // only the last turns stay: OpenAI reads the whole conversation again before every answer
                JSONObject item = e.optJSONObject("item");
                if (item != null && !item.optString("id").isEmpty() && !kept.contains(item.optString("id"))) kept.add(item.optString("id"));
                while (kept.size() > KEEP_ITEMS) {
                    send(new JSONObject().put("type", "conversation.item.delete").put("item_id", kept.remove(0)));
                }
                break;
            }
            case "response.done": {   // after the answer that follows an app opening, the panel goes
                JSONObject usage = e.optJSONObject("response") != null ? e.getJSONObject("response").optJSONObject("usage") : null;
                if (usage != null) {
                    JSONObject in = usage.optJSONObject("input_token_details"), out = usage.optJSONObject("output_token_details");
                    if (in != null) {
                        tokens[0] += in.optInt("audio_tokens");
                        tokens[1] += in.optInt("text_tokens");
                        tokens[2] += in.optInt("cached_tokens");
                    }
                    if (out != null) {
                        tokens[3] += out.optInt("audio_tokens");
                        tokens[4] += out.optInt("text_tokens");
                    }
                }
                java.util.List<String[]> toRun;
                synchronized (calls) {
                    toRun = new java.util.ArrayList<>(calls);
                    calls.clear();
                }
                if (!toRun.isEmpty()) run(toRun);
                else if (leaving) {
                    drain();
                    close();
                }
                break;
            }
            case "error":
                log("live " + e.optJSONObject("error"));
                break;
            default:
        }
    }

    private final java.util.List<String[]> calls = new java.util.ArrayList<>();   // {name, arguments, call id}

    /**
     * The calls of one finished response, one after another on a thread of their own, then their results and a single
     * request for the answer: one per call would race (a second answer refused while the first runs, or written before
     * the other result is in).
     */
    private void run(java.util.List<String[]> toRun) {
        new Thread(() -> {
            try {
                for (String[] c : toRun) {
                    String name = c[0], args = c[1], result;
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
                    log(name + " " + args + " -> " + result);
                    said("assistant", "[" + name + " " + args + " → " + result + "]");
                    send(new JSONObject().put("type", "conversation.item.create").put("item", new JSONObject()
                            .put("type", "function_call_output").put("call_id", c[2]).put("output", result)));
                }
                if (!saidBye) send(new JSONObject().put("type", "response.create"));
                else {
                    sleep(300);   // the goodbye's last words are still arriving
                    drain();
                    close();
                }
            } catch (Exception ex) {
                log("live " + ex);
            }
        }, "live-tools").start();
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

    // MARK: - The conversation in OpenAI's Logs

    private final JSONArray transcript = new JSONArray();

    private void said(String role, String text) {
        if (text.trim().isEmpty()) return;
        synchronized (lines) {
            try {
                transcript.put(new JSONObject().put("role", role).put("content", text.trim()));
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Keeps the conversation as text in your OpenAI account (platform.openai.com → Logs): a stored response whose input is
     * what was said, titled by a small model.
     */
    private void save() {
        final JSONArray said;
        synchronized (lines) {
            if (transcript.length() == 0) return;
            try {
                said = new JSONArray(transcript.toString());   // a copy: a late tool's line can't change it mid-send
            } catch (Exception e) {
                return;
            }
        }
        new Thread(() -> {
            try {
                JSONObject schema = new JSONObject().put("type", "object").put("additionalProperties", false)
                        .put("required", new JSONArray().put("title").put("summary").put("new_facts"))
                        .put("properties", new JSONObject()
                                .put("title", new JSONObject().put("type", "string"))
                                .put("summary", new JSONObject().put("type", "string"))
                                .put("new_facts", new JSONObject().put("type", "array").put("items", new JSONObject().put("type", "string"))));
                JSONObject req = new JSONObject().put("model", "gpt-5.4-mini").put("store", true)
                        .put("reasoning", new JSONObject().put("effort", "none"))
                        .put("metadata", new JSONObject().put("app", "free-my-tv").put("kind", "voice"))
                        .put("instructions", "Это запись голосового разговора с помощником на телевизоре. Верни: title, название "
                                + "в 3-6 слов; summary, одно короткое предложение о чём говорили (для памяти помощника); new_facts, "
                                + "новые долговременные факты о собеседнике и его близких (имя, вкусы, привычки, планы, просьбы на "
                                + "будущее), каждый короткой фразой от третьего лица, без того, что уже есть в памяти, и без "
                                + "сиюминутного (погода, открытые фильмы). Нет новых фактов, верни пустой список. Память сейчас:"
                                + Memory.prompt(context))
                        .put("text", new JSONObject().put("format", new JSONObject().put("type", "json_schema").put("name", "memo")
                                .put("strict", true).put("schema", schema)))
                        .put("input", said);
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("https://api.openai.com/v1/responses").openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(30000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Authorization", "Bearer " + Config.OPENAI_KEY);
                c.setRequestProperty("content-type", "application/json");
                try (OutputStream o = c.getOutputStream()) {
                    o.write(req.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = c.getResponseCode();
                log("conversation saved to OpenAI Logs: HTTP " + code + ", " + said.length() + " lines");
                if (code == 200) {
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    try (InputStream in = c.getInputStream()) {
                        byte[] buf = new byte[8192];
                        for (int n; (n = in.read(buf)) > 0; ) body.write(buf, 0, n);
                    }
                    JSONArray output = new JSONObject(body.toString("UTF-8")).getJSONArray("output");
                    for (int i = 0; i < output.length(); i++) {
                        JSONArray content = output.getJSONObject(i).optJSONArray("content");
                        for (int j = 0; content != null && j < content.length(); j++) {
                            String text = content.getJSONObject(j).optString("text");
                            if (!text.startsWith("{")) continue;
                            JSONObject memo = new JSONObject(text);
                            Memory.afterConversation(context, memo.optString("summary"), memo.optJSONArray("new_facts"));
                            log("memory: " + memo.optString("summary") + " | new facts " + memo.optJSONArray("new_facts"));
                        }
                    }
                }
                c.disconnect();
            } catch (Exception e) {
                log("conversation not saved: " + e);
            }
        }).start();
    }

    // MARK: - Sound

    private static final int MIC_RATE = 16000;   // the remote's mic only works as a "voice recognition" source, at 16 kHz

    private void listen() {
        long reopened = 0;
        while (!closed) {
            if (!record()) return;
            if (System.currentTimeMillis() - reopened > 8000) {   // the remote gives 30 s per press; once after a pause, in case it listens again
                log("live mic went silent, reopening it");
                sleep(1500);
                reopened = System.currentTimeMillis();
                continue;
            }
            // The remote has stopped sending sound until its mic button is pressed (VoiceSession then calls wake()).
            log("live mic asleep, waiting for the mic button");
            micAsleep = true;
            screen.show("Микрофон пульта уснул", "", "Нажми кнопку микрофона, чтобы продолжить");
            long until = System.currentTimeMillis() + 180_000;
            while (micAsleep && !closed && System.currentTimeMillis() < until) {
                ping();   // outside the lock: a write stuck on a dead line must not hold up the mic button
                synchronized (micLock) {
                    try {
                        if (micAsleep) micLock.wait(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
            if (micAsleep) {
                close();
                return;
            }
            reopened = 0;
            lastHeard = System.currentTimeMillis();
            screen.show("● Говорите", "", "");
        }
    }

    /** Whether the remote's mic has gone to sleep: the mic button then wakes it instead of ending the conversation. */
    boolean micAsleep() {
        return micAsleep;
    }

    void wake() {
        log("live mic button: waking the mic");
        synchronized (micLock) {
            micAsleep = false;
            micLock.notifyAll();
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
            android.media.AudioDeviceInfo usb = usbMic();
            if (usb != null) r.setPreferredDevice(usb);   // a USB mic hears all the time; the remote's gives 30 s per press
            if (AcousticEchoCanceler.isAvailable() && (aec = AcousticEchoCanceler.create(r.getAudioSessionId())) != null) {
                echoCancel = aec.setEnabled(true) == android.media.audiofx.AudioEffect.SUCCESS;
            }
            log("live mic, echo cancel " + echoCancel);
            r.startRecording();
            android.media.AudioDeviceInfo d = r.getRoutedDevice();
            if (d != null) log("live mic is " + d.getProductName() + " (type " + d.getType() + ")");
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
                if (peak > 60 || usb != null) lastSound = now;   // a room is never this quiet: the remote has switched its mic off
                else if (now - lastSound > 3000) return true;
                if (System.currentTimeMillis() - levelAt > 2000) {   // to tell a silent mic from a quiet room
                    log("live mic level " + (int) loudest);
                    loudest = 0;
                    levelAt = System.currentTimeMillis();
                }
                boolean deaf;
                if (echoCancel) deaf = false;
                else if (Config.LIVE_MUTE_WHILE_SPEAKING) {   // muted while it speaks, and a moment after for the room's echo
                    if (speaking() || !audio.isEmpty()) lastSpoke = now;
                    deaf = now - lastSpoke < 400;
                } else deaf = echo(in, n, now);
                ping();
                send(new JSONObject().put("type", "input_audio_buffer.append")
                        .put("audio", Base64.encodeToString(deaf ? quiet : up, 0, frames * 2, Base64.NO_WRAP)));
                if (!speaking() && audio.isEmpty() && System.currentTimeMillis() - lastHeard > 180_000) {
                    log("live, 3 quiet minutes");
                    close();
                }
            }
        } catch (Exception e) {
            if (!closed) log("live mic " + e);
            close();
        } finally {
            r.release();
            if (aec != null) aec.release();
        }
        return false;
    }

    private android.media.AudioDeviceInfo usbMic() {
        android.media.AudioManager am = (android.media.AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        for (android.media.AudioDeviceInfo d : am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)) {
            int t = d.getType();
            if (t == android.media.AudioDeviceInfo.TYPE_USB_DEVICE || t == android.media.AudioDeviceInfo.TYPE_USB_HEADSET) return d;
        }
        return null;
    }

    // MARK: - Echo
    // No echo canceller on this TV, so a gate: what it plays is known, so is how loud it comes back into the mic (learned
    // as it talks). While it speaks, the mic goes through only when it's clearly louder than that echo (you talking),
    // and stays open a moment so your words aren't cut; otherwise OpenAI hears silence and it doesn't interrupt itself.

    private static final int FAR = 512;
    private final long[] farAt = new long[FAR];
    private final float[] farLevel = new float[FAR];
    private int farNext;
    private double echoGain;
    private long gateOpenUntil;

    /** Notes how loud each 20 ms of the answer is and when it will be heard. */
    private void heard(byte[] pcm, int off, int len, long startsAt) {
        int step = RATE / 50 * 2;
        for (int i = off; i + 1 < off + len; i += step) {
            int end = Math.min(off + len, i + step);
            double e = 0;
            int k = 0;
            for (int j = i; j + 1 < end; j += 2, k++) {
                int v = (short) ((pcm[j] & 0xFF) | (pcm[j + 1] << 8));
                e += (double) v * v;
            }
            synchronized (farAt) {
                farAt[farNext] = startsAt + (i - off) / 2 * 1000L / RATE;
                farLevel[farNext] = (float) Math.sqrt(e / Math.max(1, k));
                farNext = (farNext + 1) % FAR;
            }
        }
    }

    /** The loudest the TV was in the last half second, allowing for the delay through its speakers into the mic. */
    private double farNow(long now) {
        double max = 0;
        synchronized (farAt) {
            for (int i = 0; i < FAR; i++) if (farAt[i] > now - 500 && farAt[i] < now + 60) max = Math.max(max, farLevel[i]);
        }
        return max;
    }

    /** True when this block of the mic is only the TV's own voice and should be sent as silence. */
    private boolean echo(short[] in, int n, long now) {
        double e = 0;
        for (int i = 0; i < n; i++) e += (double) in[i] * in[i];
        double mic = Math.sqrt(e / Math.max(1, n)), far = farNow(now);
        if (far < 150) return false;   // it isn't speaking: everything goes through
        double r = mic / far;
        if (echoGain == 0) echoGain = r;
        else if (r < echoGain * 2) echoGain = Math.max(0.005, echoGain * 0.85 + r * 0.15);   // learns the echo, not your voice
        if (r > echoGain * 3.2 && mic > 300) {
            if (now > gateOpenUntil) log(String.format(java.util.Locale.ROOT, "live you over its voice: mic %.0f, %.1f× its echo", mic, r / echoGain));
            gateOpenUntil = now + 700;
        }
        return now > gateOpenUntil;
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
            playLoop(head);
        } catch (IllegalStateException e) {   // the track was released as the conversation ended
        }
    }

    private void playLoop(ByteArrayOutputStream head) {
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
            long now = System.currentTimeMillis(), startsAt = Math.max(playUntil, now);
            heard(pcm, off, n, startsAt);
            off += n;
            playUntil = startsAt + n / 2 * 1000L / RATE;
        }
    }

    // MARK: - WebSocket

    private void connect() throws IOException {
        String host = "api.openai.com";
        java.net.Socket plain = new java.net.Socket();
        plain.connect(new InetSocketAddress(host, 443), 8000);
        // TLS over it, told the host (SNI); a bare SSLSocket checks the certificate chain but not whose it is, so that's
        // checked too: any trusted certificate for another name would otherwise get the key and the room's sound.
        socket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(plain, host, 443, true);
        socket.setSoTimeout(10000);   // until the conversation is set up
        socket.startHandshake();
        if (!javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(host, socket.getSession())) {
            socket.close();
            throw new IOException("ChatGPT live: сертификат не от " + host);
        }
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
        socket.setSoTimeout(45_000);   // a ping goes out every 15 s (listen): 45 s of nothing means the line is dead
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
            if (n == 126) n = (next(in) << 8) | next(in);
            else if (n == 127) {
                n = 0;
                for (int i = 0; i < 8; i++) n = (n << 8) | next(in);
            }
            if (n > 16_000_000) throw new IOException("live: frame of " + n + " bytes");
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

    private long pingAt;

    /** A ping every 15 s: the server's pong keeps the reader's 45 s timeout from taking a quiet line for a dead one. */
    private void ping() {
        long now = System.currentTimeMillis();
        if (now - pingAt < 15_000) return;
        pingAt = now;
        try {
            frame(0x9, new byte[0]);
        } catch (IOException e) {
            close();
        }
    }

    private static int next(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new IOException("live: connection closed");
        return b;
    }

    private void send(JSONObject o) throws IOException {
        frame(0x1, o.toString().getBytes(StandardCharsets.UTF_8));
    }

    private final SecureRandom random = new SecureRandom();

    // Three locks for three things: a write blocked on a stalled line must not hold up the transcript or the mic button
    // (wake() runs on the main thread).
    private final Object writing = new Object(), lines = new Object(), micLock = new Object();

    private void frame(int op, byte[] data) throws IOException {
        synchronized (writing) {
            frameLocked(op, data);
        }
    }

    private void frameLocked(int op, byte[] data) throws IOException {
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

    /**
     * To the log and to voice.log (adb pull /sdcard/Android/data/io.freemytv/files/voice.log): TV logs overflow in minutes.
     * The file holds what was said, so only from Android 11 on, where other apps can't read an app's folder there.
     */
    private void log(String message) {
        note(context, message);
    }

    static void note(Context context, String message) {
        AppLog.w("voice: " + message);
        if (android.os.Build.VERSION.SDK_INT < 30) return;
        try {
            java.io.File f = new java.io.File(context.getExternalFilesDir(null), "voice.log");
            if (f.length() > 512_000) f.delete();
            try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
                w.write(new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT).format(new java.util.Date()) + " " + message + "\n");
            }
        } catch (Exception ignored) {
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
