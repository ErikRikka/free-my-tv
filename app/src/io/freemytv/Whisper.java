package io.freemytv;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Speech to text through a whisper.cpp server on your computer (whisper-server, POST /inference). A plain socket, since
 * the app's network rules forbid unencrypted web requests and this one never leaves your home network.
 */
final class Whisper {
    private Whisper() {}

    static String transcribe(String url, byte[] pcm16, int rate) throws IOException {
        if (url.isEmpty()) throw new IOException("whisper_url не задан в config.properties");
        if (pcm16.length < 8000) return "";   // under 0.25 s: the server rejects such a file
        URL u = new URL(url);
        int port = u.getPort() > 0 ? u.getPort() : 80;
        byte[] wav = wav(pcm16, rate);
        String boundary = "----freemytv" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        field(body, boundary, "response_format", "json");
        field(body, boundary, "language", "ru");
        field(body, boundary, "temperature", "0.0");
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(wav);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(u.getHost(), port), 4000);
            s.setSoTimeout(60000);
            OutputStream out = s.getOutputStream();
            out.write(("POST " + (u.getPath().isEmpty() ? "/" : u.getPath()) + " HTTP/1.1\r\nHost: " + u.getHost() + ":" + port
                    + "\r\nContent-Type: multipart/form-data; boundary=" + boundary + "\r\nContent-Length: " + body.size()
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.writeTo(out);
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream resp = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) resp.write(buf, 0, n);
            String text = resp.toString("UTF-8");
            int split = text.indexOf("\r\n\r\n");
            if (split < 0 || !text.startsWith("HTTP/1.1 200")) throw new IOException("whisper: " + text.split("\r\n")[0]);
            String json = text.substring(split + 4);
            int a = json.indexOf('{'), b = json.lastIndexOf('}');
            return new JSONObject(json.substring(a, b + 1)).optString("text", "").trim();
        } catch (org.json.JSONException e) {
            throw new IOException("whisper: ответ не разобран");
        }
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] wav(byte[] pcm, int rate) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44 + pcm.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes()).putInt(pcm.length).put(pcm);
        return b.array();
    }
}
