package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Now playing" from the Spotify Web API, on whatever device is playing (phone, computer, speaker). Needs
 * spotify_client_id, spotify_client_secret and spotify_refresh_token in config.properties (tools/spotify_token.py
 * gets the token once). Polled every 10 s while the home screen is showing.
 */
final class Spotify {
    interface Listener { void onSpotify(); }

    private static final long EVERY = 10_000;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private volatile String token;
    private volatile long tokenUntil, quietUntil;
    private volatile int failures;
    private volatile boolean busy, running;
    private String artUrl = "";

    volatile boolean playing;
    volatile String title = "", artist = "";
    volatile Bitmap art;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!running) return;
            fetch();
            main.postDelayed(this, EVERY);
        }
    };

    Spotify(Listener listener) {
        this.listener = listener;
    }

    static boolean configured() {
        return !Config.SPOTIFY_CLIENT_ID.isEmpty() && !Config.SPOTIFY_CLIENT_SECRET.isEmpty()
                && !Config.SPOTIFY_REFRESH_TOKEN.isEmpty();
    }

    void start() {
        if (!configured() || running) return;
        running = true;
        main.post(poll);
    }

    void stop() {
        running = false;
        main.removeCallbacks(poll);
    }

    private void fetch() {
        if (busy || System.currentTimeMillis() < quietUntil) return;
        busy = true;
        io.execute(() -> {
            boolean changed;
            try {
                changed = read();
                failures = 0;
            } catch (Exception e) {
                AppLog.w("spotify: " + e.getMessage());
                changed = playing;
                playing = false;
                // failing again and again (a revoked token, no network): wait longer each time, up to half an hour
                failures++;
                quietUntil = System.currentTimeMillis() + Math.min(30 * 60_000L, EVERY << Math.min(failures, 8));
            } finally {
                busy = false;
            }
            if (changed) main.post(listener::onSpotify);
        });
    }

    private boolean read() throws Exception {
        if (token == null || System.currentTimeMillis() > tokenUntil) refreshToken();
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.spotify.com/v1/me/player/currently-playing").openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        boolean was = playing;
        String wasTitle = title;
        try {
            int code = c.getResponseCode();
            if (code == 401) {
                token = null;
                return false;
            }
            if (code == 204) {
                playing = false;
                return was;
            }
            if (code != 200) throw new IOException("HTTP " + code);
            JSONObject o = new JSONObject(read(c.getInputStream()));
            JSONObject item = o.optJSONObject("item");
            if (item == null || !o.optBoolean("is_playing")) {
                playing = false;
                return was;
            }
            StringBuilder names = new StringBuilder();
            JSONArray artists = item.optJSONArray("artists");
            for (int i = 0; artists != null && i < artists.length(); i++) {
                if (names.length() > 0) names.append(", ");
                names.append(artists.getJSONObject(i).optString("name"));
            }
            if (names.length() == 0) {
                JSONObject show = item.optJSONObject("show");
                if (show != null) names.append(show.optString("name"));
            }
            String url = "";
            JSONObject album = item.optJSONObject("album");
            JSONArray images = album != null ? album.optJSONArray("images") : item.optJSONArray("images");
            if (images != null && images.length() > 0) url = images.getJSONObject(images.length() > 1 ? images.length() - 2 : 0).optString("url");
            if (!url.equals(artUrl)) {
                artUrl = url;
                art = url.isEmpty() ? null : image(url);
            }
            title = item.optString("name");
            artist = names.toString();
            playing = true;
            return !was || !title.equals(wasTitle);
        } finally {
            c.disconnect();
        }
    }

    private void refreshToken() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://accounts.spotify.com/api/token").openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        String basic = Base64.encodeToString((Config.SPOTIFY_CLIENT_ID + ":" + Config.SPOTIFY_CLIENT_SECRET)
                .getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        c.setRequestProperty("Authorization", "Basic " + basic);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        byte[] body = ("grant_type=refresh_token&refresh_token=" + URLEncoder.encode(Config.SPOTIFY_REFRESH_TOKEN, "UTF-8"))
                .getBytes(StandardCharsets.UTF_8);
        try {
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("token HTTP " + code);
            JSONObject o = new JSONObject(read(c.getInputStream()));
            token = o.getString("access_token");
            tokenUntil = System.currentTimeMillis() + (o.optLong("expires_in", 3600) - 60) * 1000L;
        } finally {
            c.disconnect();
        }
    }

    private static Bitmap image(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            try (InputStream in = c.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
