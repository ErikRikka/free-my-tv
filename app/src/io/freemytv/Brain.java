package io.freemytv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The assistant's brain, with a short memory: ChatGPT (OpenAI Chat Completions, openai_key) or Claude (Anthropic Messages API,
 * anthropic_key), whichever assistant_provider in config.properties names.
 */
final class Brain {
    private Brain() {}

    static boolean openai() {
        return Config.ASSISTANT_PROVIDER.equalsIgnoreCase("openai") || Config.ASSISTANT_PROVIDER.equalsIgnoreCase("chatgpt");
    }

    static String key() {
        return openai() ? Config.OPENAI_KEY : Config.ANTHROPIC_KEY;
    }

    static String name() {
        return openai() ? "ChatGPT" : "Claude";
    }

    private static final String SYSTEM = "Ты голосовой помощник на телевизоре. Отвечай по-русски, коротко и разговорно: "
            + "одна-три фразы, без списков, без markdown и без эмодзи, потому что ответ зачитывается вслух.";
    private static final long FORGET_AFTER = 10 * 60_000L;
    private static final List<String[]> history = new ArrayList<>();   // {role, text}
    private static long lastAt;

    static synchronized String ask(String said) throws Exception {
        long now = System.currentTimeMillis();
        if (now - lastAt > FORGET_AFTER) history.clear();
        lastAt = now;
        history.add(new String[] {"user", said});
        while (history.size() > 12) history.remove(0);
        boolean gpt = openai();
        JSONArray messages = new JSONArray();
        if (gpt) messages.put(new JSONObject().put("role", "system").put("content", SYSTEM));
        for (String[] m : history) messages.put(new JSONObject().put("role", m[0]).put("content", m[1]));
        JSONObject req = gpt
                ? new JSONObject().put("model", Config.OPENAI_MODEL).put("max_completion_tokens", 400).put("messages", messages)
                : new JSONObject().put("model", Config.ASSISTANT_MODEL).put("max_tokens", 400).put("system", SYSTEM).put("messages", messages);
        HttpURLConnection c = (HttpURLConnection) new URL(gpt ? "https://api.openai.com/v1/chat/completions"
                : "https://api.anthropic.com/v1/messages").openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(40000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        if (gpt) {
            c.setRequestProperty("Authorization", "Bearer " + Config.OPENAI_KEY);
        } else {
            c.setRequestProperty("x-api-key", Config.ANTHROPIC_KEY);
            c.setRequestProperty("anthropic-version", "2023-06-01");
        }
        c.setRequestProperty("content-type", "application/json");
        try {
            try (OutputStream out = c.getOutputStream()) {
                out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            String body = read(code == 200 ? c.getInputStream() : c.getErrorStream());
            if (code != 200) {
                history.remove(history.size() - 1);
                throw new IOException(name() + ": HTTP " + code + (code == 401 ? " (неверный ключ)" : code == 429 ? " (лимит или нет оплаты)" : ""));
            }
            String reply;
            if (gpt) {
                reply = new JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content").trim();
            } else {
                JSONArray content = new JSONObject(body).getJSONArray("content");
                StringBuilder text = new StringBuilder();
                for (int i = 0; i < content.length(); i++) text.append(content.getJSONObject(i).optString("text"));
                reply = text.toString().trim();
            }
            history.add(new String[] {"assistant", reply});
            return reply;
        } finally {
            c.disconnect();
        }
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
