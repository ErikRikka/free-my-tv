package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * What the assistant remembers between conversations, in a file on the TV: facts about you (it keeps them when you ask,
 * or by itself) and a line about each recent conversation. Goes into its instructions at the start of every conversation.
 */
final class Memory {
    private Memory() {}

    private static final int FACTS = 60, RECENT = 8;

    private static File file(Context c) {
        return new File(c.getFilesDir(), "assistant-memory.json");
    }

    private static JSONObject load(Context c) {
        try {
            byte[] b = java.nio.file.Files.readAllBytes(file(c).toPath());
            return new JSONObject(new String(b, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void store(Context c, JSONObject m) {
        File f = file(c), tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(m.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            AppLog.w("voice: memory not saved: " + e);
            return;
        }
        if (!tmp.renameTo(f)) AppLog.w("voice: memory not saved");
    }

    private static JSONArray list(JSONObject m, String name) {
        JSONArray a = m.optJSONArray(name);
        return a != null ? a : new JSONArray();
    }

    /** For the instructions: what it knows about you and what you talked about lately. */
    static synchronized String prompt(Context c) {
        JSONObject m = load(c);
        JSONArray facts = list(m, "facts"), recent = list(m, "recent");
        if (facts.length() == 0 && recent.length() == 0) {
            return " Пока ты ничего не знаешь о собеседнике. Узнаёшь что-то стоящее (имя, вкусы, близких, планы), сохраняй через remember.";
        }
        StringBuilder s = new StringBuilder(" Твоя память о собеседнике (используй естественно, не пересказывай её целиком):");
        for (int i = 0; i < facts.length(); i++) s.append("\n- ").append(facts.optString(i));
        if (recent.length() > 0) {
            s.append("\nНедавние разговоры:");
            for (int i = 0; i < recent.length(); i++) s.append("\n- ").append(recent.optString(i));
        }
        s.append("\nНовое стоящее запоминай через remember, а если просят забыть, вызывай forget.");
        return s.toString();
    }

    static synchronized String remember(Context c, String fact) {
        String f = fact.trim();
        if (f.isEmpty()) return "Нечего запоминать.";
        JSONObject m = load(c);
        JSONArray facts = list(m, "facts");
        for (int i = 0; i < facts.length(); i++) if (facts.optString(i).equalsIgnoreCase(f)) return "Это уже есть в памяти.";
        facts.put(f);
        while (facts.length() > FACTS) facts.remove(0);
        try {
            m.put("facts", facts);
        } catch (Exception ignored) {
        }
        store(c, m);
        return "Запомнил: " + f;
    }

    /** Forgets the facts that mention what, or everything for "всё". */
    static synchronized String forget(Context c, String what) {
        String w = what.trim().toLowerCase(Locale.ROOT);
        if (w.isEmpty() || w.equals("всё") || w.equals("все") || w.equals("everything")) {
            store(c, new JSONObject());
            return "Память очищена.";
        }
        JSONObject m = load(c);
        JSONArray facts = list(m, "facts"), kept = new JSONArray();
        int gone = 0;
        for (int i = 0; i < facts.length(); i++) {
            if (facts.optString(i).toLowerCase(Locale.ROOT).contains(w)) gone++;
            else kept.put(facts.optString(i));
        }
        try {
            m.put("facts", kept);
        } catch (Exception ignored) {
        }
        store(c, m);
        return gone == 0 ? "В памяти нет ничего про «" + what + "»." : "Забыл (" + gone + ").";
    }

    /** After a conversation: a line about it, and the facts a small model found worth keeping. */
    static synchronized void afterConversation(Context c, String summary, JSONArray newFacts) {
        if (newFacts != null) for (int i = 0; i < newFacts.length(); i++) remember(c, newFacts.optString(i));
        if (summary == null || summary.trim().isEmpty()) return;
        JSONObject m = load(c);
        JSONArray recent = list(m, "recent");
        recent.put(new SimpleDateFormat("d MMMM, HH:mm", new Locale("ru")).format(new Date()) + ": " + summary.trim());
        while (recent.length() > RECENT) recent.remove(0);
        try {
            m.put("recent", recent);
        } catch (Exception ignored) {
        }
        store(c, m);
    }
}
