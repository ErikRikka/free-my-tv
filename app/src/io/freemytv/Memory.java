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
 * or by itself) and a line about each recent conversation. The freshest facts go into its instructions at the start of
 * every conversation; older ones stay in the file, and it looks them up with recall when the talk turns to them.
 */
final class Memory {
    private Memory() {}

    private static final int IN_PROMPT = 200, KEPT = 5000, RECENT = 8;

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
        int from = Math.max(0, facts.length() - IN_PROMPT);
        for (int i = from; i < facts.length(); i++) s.append("\n- ").append(facts.optString(i));
        if (from > 0) {
            s.append("\nВ памяти есть ещё ").append(from).append(" более старых фактов: когда разговор касается прошлого, людей, "
                    + "вкусов или того, что ты не видишь выше, сначала поищи через recall.");
        }
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
        while (facts.length() > KEPT) facts.remove(0);
        try {
            m.put("facts", facts);
        } catch (Exception ignored) {
        }
        store(c, m);
        return "Запомнил: " + f;
    }

    /** Finds facts by words in them, best matches first; the found ones become fresh again (back into the instructions). */
    static synchronized String recall(Context c, String query) {
        java.util.List<String> words = new java.util.ArrayList<>();
        for (String w : query.toLowerCase(Locale.ROOT).replace('ё', 'е').split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 3) words.add(w.length() > 5 ? w.substring(0, w.length() - 2) : w);   // a rough stem for Russian endings
        }
        JSONObject m = load(c);
        JSONArray facts = list(m, "facts");
        java.util.List<int[]> hits = new java.util.ArrayList<>();   // {score, index}
        for (int i = 0; i < facts.length(); i++) {
            String f = facts.optString(i).toLowerCase(Locale.ROOT).replace('ё', 'е');
            int score = 0;
            for (String w : words) if (f.contains(w)) score++;
            if (score > 0) hits.add(new int[] {score, i});
        }
        if (hits.isEmpty()) return "В памяти ничего про это нет.";
        java.util.Collections.sort(hits, (x, y) -> x[0] != y[0] ? y[0] - x[0] : y[1] - x[1]);
        java.util.List<String> found = new java.util.ArrayList<>();
        for (int k = 0; k < Math.min(12, hits.size()); k++) found.add(facts.optString(hits.get(k)[1]));
        JSONArray rest = new JSONArray();
        for (int i = 0; i < facts.length(); i++) if (!found.contains(facts.optString(i))) rest.put(facts.optString(i));
        for (String f : found) rest.put(f);
        try {
            m.put("facts", rest);
        } catch (Exception ignored) {
        }
        store(c, m);
        StringBuilder s = new StringBuilder("Из памяти:");
        for (String f : found) s.append("\n- ").append(f);
        return s.toString();
    }

    /** Forgets the facts that mention what, or everything for "всё". */
    static synchronized String forget(Context c, String what) {
        String w = what.trim().toLowerCase(Locale.ROOT);
        if (w.isEmpty()) return "Не понял, что забыть.";   // a malformed call must not wipe everything
        if (w.equals("всё") || w.equals("все") || w.equals("everything")) {
            File f = file(c);
            if (f.exists()) f.renameTo(new File(f.getPath() + ".bak"));   // one step back, by hand, if it was a mistake
            store(c, new JSONObject());
            return "Память очищена.";
        }
        if (w.replaceAll("[^\\p{L}\\p{N}]", "").length() < 3) return "Слишком коротко: скажи, что именно забыть.";
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
