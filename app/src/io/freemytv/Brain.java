package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The assistant's brain, with a short memory. ChatGPT (OpenAI Chat Completions, openai_key): the answer streams in, so the
 * first sentence can be spoken while the rest is still being written, and it can act on the TV through TvTools (open an
 * app or a movie, set the sleep timer, change the volume...). Claude (Anthropic Messages API, anthropic_key) answers in
 * text only. Which one: assistant_provider in config.properties.
 */
final class Brain {
    private Brain() {}

    /** Receives the answer as it is written. */
    interface Sink {
        void text(String delta);
    }

    static boolean openai() {
        return Config.ASSISTANT_PROVIDER.equalsIgnoreCase("openai") || Config.ASSISTANT_PROVIDER.equalsIgnoreCase("chatgpt");
    }

    static String key() {
        return openai() ? Config.OPENAI_KEY : Config.ANTHROPIC_KEY;
    }

    static String name() {
        return openai() ? "ChatGPT" : "Claude";
    }

    private static final String SYSTEM = "Ты голосовой помощник на телевизоре, с тобой разговаривают голосом. Отвечай по-русски, "
            + "тепло и разговорно, как живой собеседник: обычно одна-три короткие фразы, без списков, без markdown, без эмодзи и "
            + "без ссылок, потому что ответ зачитывается вслух. Когда просят что-то сделать на телевизоре (открыть приложение, "
            + "включить фильм или видео, поставить таймер сна, изменить громкость, назвать погоду, выключить телевизор), "
            + "вызови подходящую функцию и после неё коротко скажи, что сделал. Фильмы ищи по русскому названию. Если функция "
            + "сообщила, что не получилось, честно скажи об этом. Телевизор принадлежит одному человеку, имя не называй.";
    private static final long FORGET_AFTER = 10 * 60_000L;
    private static final List<JSONObject> history = new ArrayList<>();
    private static long lastAt;

    static synchronized String ask(Context c, String said, Sink sink) throws Exception {
        long now = System.currentTimeMillis();
        if (now - lastAt > FORGET_AFTER) history.clear();
        lastAt = now;
        history.add(new JSONObject().put("role", "user").put("content", said));
        trim();
        try {
            return openai() ? chatgpt(c, sink) : claude(sink);
        } catch (Exception e) {
            while (!history.isEmpty() && !"user".equals(history.get(history.size() - 1).optString("role"))) history.remove(history.size() - 1);
            if (!history.isEmpty()) history.remove(history.size() - 1);   // the question that failed
            throw e;
        }
    }

    private static void trim() {
        while (history.size() > 24 || (!history.isEmpty() && !"user".equals(history.get(0).optString("role")) && history.size() > 1)) {
            history.remove(0);
        }
    }

    static String system() {
        return SYSTEM + " Сейчас " + new SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", new Locale("ru")).format(new Date()) + ".";
    }

    // MARK: - ChatGPT

    private static String chatgpt(Context c, Sink sink) throws Exception {
        StringBuilder spoken = new StringBuilder();
        for (int round = 0; round < 4; round++) {
            JSONArray messages = new JSONArray().put(new JSONObject().put("role", "system").put("content", system()));
            for (JSONObject m : history) messages.put(m);
            JSONObject req = new JSONObject().put("model", Config.OPENAI_MODEL).put("max_completion_tokens", 500).put("stream", true)
                    .put("messages", messages).put("tools", TvTools.schemas(c)).put("tool_choice", "auto");
            req.put("store", true).put("metadata", new JSONObject().put("app", "free-my-tv").put("kind", "voice"));   // in your account's Logs
            if (Config.OPENAI_MODEL.matches("(gpt-[5-9]|o\\d).*")) req.put("reasoning_effort", "none");   // thinking models take tools only without it, and answer faster
            HttpURLConnection conn = open("https://api.openai.com/v1/chat/completions");
            conn.setRequestProperty("Authorization", "Bearer " + Config.OPENAI_KEY);
            StringBuilder text = new StringBuilder();
            Map<Integer, String[]> calls = new TreeMap<>();   // {id, name, arguments}
            try {
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(req.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                if (code != 200) {
                    throw new IOException("ChatGPT: HTTP " + code + (code == 401 ? " (неверный ключ)" : code == 429 ? " (лимит или нет оплаты)"
                            : code == 403 ? " (OpenAI не пускает с этого адреса)" : ""));
                }
                try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    for (String line; (line = r.readLine()) != null; ) {
                        if (!line.startsWith("data:")) continue;
                        String data = line.substring(5).trim();
                        if (data.equals("[DONE]")) break;
                        JSONArray choices = new JSONObject(data).optJSONArray("choices");
                        if (choices == null || choices.length() == 0) continue;
                        JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                        if (delta == null) continue;
                        String piece = delta.optString("content", "");
                        if (!piece.isEmpty() && !delta.isNull("content")) {
                            text.append(piece);
                            spoken.append(piece);
                            sink.text(piece);
                        }
                        JSONArray tc = delta.optJSONArray("tool_calls");
                        for (int i = 0; tc != null && i < tc.length(); i++) {
                            JSONObject t = tc.getJSONObject(i);
                            String[] call = calls.get(t.optInt("index"));
                            if (call == null) calls.put(t.optInt("index"), call = new String[] {"", "", ""});
                            if (t.has("id")) call[0] = t.optString("id");
                            JSONObject f = t.optJSONObject("function");
                            if (f != null) {
                                if (f.has("name")) call[1] += f.optString("name");
                                if (f.has("arguments")) call[2] += f.optString("arguments");
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect();
            }
            if (calls.isEmpty()) {
                history.add(new JSONObject().put("role", "assistant").put("content", text.toString()));
                return spoken.toString().trim();
            }
            JSONArray toolCalls = new JSONArray();
            for (String[] call : calls.values()) {
                toolCalls.put(new JSONObject().put("id", call[0]).put("type", "function")
                        .put("function", new JSONObject().put("name", call[1]).put("arguments", call[2].isEmpty() ? "{}" : call[2])));
            }
            history.add(new JSONObject().put("role", "assistant").put("content", text.length() == 0 ? JSONObject.NULL : text.toString())
                    .put("tool_calls", toolCalls));
            for (String[] call : calls.values()) {
                String result;
                try {
                    result = TvTools.run(c, call[1], new JSONObject(call[2].isEmpty() ? "{}" : call[2]));
                } catch (Exception e) {
                    result = "Не получилось: " + e.getMessage();
                }
                AppLog.w("voice: " + call[1] + " " + call[2] + " -> " + result);
                history.add(new JSONObject().put("role", "tool").put("tool_call_id", call[0]).put("content", result));
            }
        }
        return spoken.toString().trim();
    }

    // MARK: - Claude (text only)

    private static String claude(Sink sink) throws Exception {
        JSONArray messages = new JSONArray();
        for (JSONObject m : history) {
            String role = m.optString("role"), content = m.optString("content", "");
            if ((role.equals("user") || role.equals("assistant")) && !content.isEmpty() && !m.has("tool_calls")) messages.put(m);
        }
        JSONObject req = new JSONObject().put("model", Config.ASSISTANT_MODEL).put("max_tokens", 400).put("system", system())
                .put("messages", messages);
        HttpURLConnection c = open("https://api.anthropic.com/v1/messages");
        c.setRequestProperty("x-api-key", Config.ANTHROPIC_KEY);
        c.setRequestProperty("anthropic-version", "2023-06-01");
        try {
            try (OutputStream out = c.getOutputStream()) {
                out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            String body = read(code == 200 ? c.getInputStream() : c.getErrorStream());
            if (code != 200) throw new IOException("Claude: HTTP " + code + (code == 401 ? " (неверный ключ)" : ""));
            JSONArray content = new JSONObject(body).getJSONArray("content");
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < content.length(); i++) text.append(content.getJSONObject(i).optString("text"));
            String reply = text.toString().trim();
            history.add(new JSONObject().put("role", "assistant").put("content", reply));
            sink.text(reply);
            return reply;
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(40000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("content-type", "application/json");
        return c;
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }
}
